package com.example.llamanode.service

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class ServerConfig(
    val binaryPath: String,
    val host: String,
    val port: Int,
    val useCache: Boolean,
    val backend: String,
    val threads: Int,
    val cacheDir: File,
    val filesDir: File,
    val nativeLibDir: String,
    val extraLdLibraryPath: String? = null
)

data class ActiveRun(
    val id: Long,
    val requestedBackend: String = "AUTO",
    @Volatile var process: Process? = null,
    @Volatile var stopRequested: Boolean = false,
    @Volatile var forcedKillRequested: Boolean = false,
    @Volatile var isFallbackActive: Boolean = false,
    @Volatile var fallbackReason: String? = null
)

enum class StartResult {
    STARTED,
    ALREADY_ACTIVE,
    FAILED
}

class ProcessLifecycleController(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val processLauncher: (cmd: List<String>, env: Map<String, String>, workDir: File) -> Process = { cmd, env, dir ->
        ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).apply {
            environment().putAll(env)
        }.start()
    },
    private val portChecker: (host: String, port: Int) -> Boolean = { h, p ->
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(h, p), 200)
                true
            }
        } catch (_: Exception) {
            false
        }
    },
    private val onStateChanged: (ServerState) -> Unit = { RpcServerManager.setState(it) },
    private val onLog: (String) -> Unit = { RpcServerManager.addLog(it) },
    private val onError: (String) -> Unit = { RpcServerManager.setError(it) },
    private val onActiveBackendChanged: (ActiveBackendInfo?) -> Unit = { RpcServerManager.setActiveBackendInfo(it) },
    private val onShutdownComplete: () -> Unit = {}
) {
    private val lifecycleMutex = Mutex()
    private var activeJob: Job? = null
    private var runIdCounter: Long = 0L
    @Volatile private var currentRun: ActiveRun? = null
    @Volatile private var currentProcess: Process? = null
    @Volatile private var currentState: ServerState = ServerState.STOPPED

    val state: ServerState get() = currentState
    val isRunning: Boolean get() = currentState == ServerState.RUNNING
    val processInstance: Process? get() = currentRun?.process ?: currentProcess
    val currentRunInstance: ActiveRun? get() = currentRun

    private val deviceLineRegex = Regex("""^\s*(Vulkan\d*|CPU\d*):\s+([^(]+?)\s+\(([^)]+)\)""", RegexOption.IGNORE_CASE)

    fun buildCommandLine(config: ServerConfig): List<String> {
        val cmd = mutableListOf(
            config.binaryPath,
            "-H", config.host,
            "-p", config.port.toString()
        )
        if (config.useCache) {
            cmd.add("-c")
        }
        if (config.threads > 0) {
            cmd.add("-t")
            cmd.add(config.threads.toString())
        }
        when (config.backend.uppercase()) {
            "VULKAN" -> {
                cmd.add("-d")
                cmd.add("Vulkan0")
            }
            "CPU" -> {
                cmd.add("-d")
                cmd.add("CPU")
            }
            "BOTH" -> {
                cmd.add("-d")
                cmd.add("Vulkan0,CPU")
            }
        }
        return cmd
    }

    fun buildEnvironment(config: ServerConfig): Map<String, String> {
        val existingLd = config.extraLdLibraryPath ?: System.getenv("LD_LIBRARY_PATH")
        val ldLibPath = if (!existingLd.isNullOrEmpty()) {
            "${config.nativeLibDir}:$existingLd"
        } else {
            "${config.nativeLibDir}:/system/lib64:/vendor/lib64"
        }
        return mapOf(
            "LD_LIBRARY_PATH" to ldLibPath,
            "HOME" to config.filesDir.absolutePath,
            "TMPDIR" to config.cacheDir.absolutePath,
            "XDG_CACHE_HOME" to config.cacheDir.absolutePath
        )
    }

    suspend fun start(config: ServerConfig): StartResult = lifecycleMutex.withLock {
        if (currentState == ServerState.STARTING || currentState == ServerState.RUNNING || currentState == ServerState.STOPPING) {
            onLog("Start request ignored; current state is $currentState")
            return StartResult.ALREADY_ACTIVE
        }

        currentState = ServerState.STARTING
        onStateChanged(ServerState.STARTING)
        onActiveBackendChanged(null)
        onLog("--- Initializing LlamaNode RPC Server on ${config.host}:${config.port} (${config.backend}) ---")
        if (config.host == "0.0.0.0") {
            onLog("SECURITY WARNING: Binding to 0.0.0.0 (LAN). Upstream RPC traffic is unauthenticated and unencrypted. Ensure you are on a trusted private network.")
        }

        val binaryFile = File(config.binaryPath)
        if (!binaryFile.exists()) {
            val errMsg = "Native binary not found at ${config.binaryPath}"
            currentState = ServerState.ERROR
            onStateChanged(ServerState.ERROR)
            onError(errMsg)
            onShutdownComplete()
            return StartResult.FAILED
        }

        if (!config.cacheDir.exists()) {
            config.cacheDir.mkdirs()
        }

        val run = ActiveRun(id = ++runIdCounter, requestedBackend = config.backend)
        currentRun = run

        activeJob = scope.launch(ioDispatcher) {
            runProcessSupervision(run, config)
        }
        return StartResult.STARTED
    }

    /**
     * Determines whether a startup failure is reasonably attributable to Vulkan initialization or device availability.
     * Port conflicts, configuration errors, and unexplained crashes return false.
     */
    fun isVulkanInitializationFailure(logs: List<String>, exitCode: Int?): Pair<Boolean, String?> {
        val portConflictPatterns = listOf(
            "address already in use",
            "failed to bind",
            "eaddrinuse"
        )
        val hasPortConflict = logs.any { line ->
            portConflictPatterns.any { pattern -> line.contains(pattern, ignoreCase = true) }
        }
        if (hasPortConflict) {
            return Pair(false, "Port conflict (Address already in use)")
        }

        val vulkanFailurePatterns = listOf(
            "ggml_vulkan: Found 0 Vulkan devices",
            "ggml_vulkan: 0 Vulkan devices",
            "ggml_vulkan: failed",
            "failed to initialize vulkan",
            "vkcreateinstance",
            "vkcreatedevice",
            "vk_error",
            "vulkan device not found",
            "error: unknown device: vulkan",
            "cannot find vulkan",
            "no vulkan devices",
            "vulkan not available",
            "failed to create vulkan instance"
        )

        for (line in logs) {
            for (pattern in vulkanFailurePatterns) {
                if (line.contains(pattern, ignoreCase = true)) {
                    return Pair(true, line.trim())
                }
            }
        }

        return Pair(false, null)
    }

    private suspend fun runProcessSupervision(run: ActiveRun, initialConfig: ServerConfig) {
        var currentConfig = initialConfig
        val isAutoMode = initialConfig.backend.equals("AUTO", ignoreCase = true)
        var hasReachedRunning = false
        var activeProc: Process? = null
        var logJob: Job? = null

        try {
            // Attempt 1 (or only attempt if not auto-fallback)
            val launchResult1 = launchAndSuperviseAttempt(run, currentConfig, isFallback = false)
            activeProc = launchResult1.process
            logJob = launchResult1.logJob

            if (launchResult1.shouldAbort) {
                finalizeCleanStop(run)
                return
            }

            if (!launchResult1.isReady) {
                logJob?.cancel()
                val exitCode1 = launchResult1.exitCode
                if (activeProc != null && activeProc.isAlive) {
                    terminateProcessGracefully(activeProc, 800L)
                }

                if (run.stopRequested) {
                    finalizeCleanStop(run)
                    return
                }

                // Check for Auto Fallback: strictly before reaching RUNNING
                val (isVulkanFail, failReason) = isVulkanInitializationFailure(launchResult1.capturedLogs, exitCode1)

                if (isAutoMode && isVulkanFail) {
                    onLog("[AUTO-FALLBACK] Vulkan initialization failed during startup: $failReason")
                    onLog("[AUTO-FALLBACK] Seamlessly retrying with ARM CPU backend...")

                    run.isFallbackActive = true
                    run.fallbackReason = failReason

                    // Transition config to CPU for fallback
                    currentConfig = initialConfig.copy(backend = "CPU")

                    // Attempt 2: CPU Fallback
                    val launchResult2 = launchAndSuperviseAttempt(run, currentConfig, isFallback = true)
                    activeProc = launchResult2.process
                    logJob = launchResult2.logJob

                    if (launchResult2.shouldAbort) {
                        finalizeCleanStop(run)
                        return
                    }

                    if (!launchResult2.isReady) {
                        logJob?.cancel()
                        val exitCode2 = launchResult2.exitCode
                        if (activeProc != null && activeProc.isAlive) {
                            terminateProcessGracefully(activeProc, 800L)
                        }

                        if (run.stopRequested) {
                            finalizeCleanStop(run)
                            return
                        }

                        finalizeError(
                            run,
                            exitCode2,
                            "Server failed to initialize even after CPU fallback" +
                                if (exitCode2 != null) " (exit code $exitCode2)" else " (readiness timeout)"
                        )
                        return
                    }
                } else {
                    // Non-recoverable startup error, port conflict, or explicit mode failure
                    val errorDetail = when {
                        failReason != null -> failReason
                        exitCode1 != null -> "Server failed to initialize and exited with code $exitCode1"
                        else -> "Server did not start listening on port ${currentConfig.port} within 5 seconds"
                    }
                    finalizeError(run, exitCode1, errorDetail)
                    return
                }
            }

            // Server reached readiness
            hasReachedRunning = true
            lifecycleMutex.withLock {
                if (currentRun?.id == run.id && currentState == ServerState.STARTING && !run.stopRequested) {
                    currentState = ServerState.RUNNING
                    onStateChanged(ServerState.RUNNING)
                    val modeTag = if (run.isFallbackActive) "ARM CPU (Auto Fallback)" else currentConfig.backend
                    onLog("--- Server is READY and listening on ${currentConfig.host}:${currentConfig.port} ($modeTag) ---")
                }
            }

            // Await process exit (Once RUNNING, fallback is strictly forbidden)
            val proc = activeProc
            val exitCode = if (proc != null) {
                try {
                    withContext(ioDispatcher) { proc.waitFor() }
                } catch (ce: CancellationException) {
                    try { proc.waitFor() } catch (_: Exception) { proc.exitValue() }
                }
            } else 0
            logJob?.join()

            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (currentRun?.id != run.id) {
                        onLog("Process run #${run.id} finished after newer run #${currentRun?.id} started; ignoring state change")
                        return@withLock
                    }

                    currentProcess = null
                    currentRun = null
                    activeJob = null

                    when {
                        run.stopRequested -> {
                            currentState = ServerState.STOPPED
                            onStateChanged(ServerState.STOPPED)
                            onActiveBackendChanged(null)
                            if (run.forcedKillRequested) {
                                onLog("--- Server stopped (forced termination) ---")
                            } else {
                                onLog("--- Server stopped cleanly by user ---")
                            }
                        }
                        exitCode == 0 -> {
                            currentState = ServerState.STOPPED
                            onStateChanged(ServerState.STOPPED)
                            onActiveBackendChanged(null)
                            onLog("--- Server process terminated with code 0 ---")
                        }
                        else -> {
                            // Runtime crash: strictly report ERROR without fallback
                            currentState = ServerState.ERROR
                            onStateChanged(ServerState.ERROR)
                            val signalMsg = when (exitCode) {
                                143 -> "terminated by unexpected SIGTERM (exit code 143)"
                                137 -> "killed by unexpected SIGKILL/OOM (exit code 137)"
                                139 -> "crashed with SIGSEGV (exit code 139)"
                                else -> "crashed with exit code $exitCode"
                            }
                            onError("Server process $signalMsg")
                        }
                    }
                    onShutdownComplete()
                }
            }

        } catch (ce: CancellationException) {
            onLog("Server process supervision cancelled (run #${run.id})")
            run.process?.let { terminateProcessGracefully(it, 800L) }
            logJob?.cancel()
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (currentRun?.id == run.id) {
                        currentProcess = null
                        currentRun = null
                        activeJob = null
                        onActiveBackendChanged(null)
                        if (run.stopRequested || currentState == ServerState.STOPPING) {
                            currentState = ServerState.STOPPED
                            onStateChanged(ServerState.STOPPED)
                            if (run.forcedKillRequested) {
                                onLog("--- Server stopped (forced termination) ---")
                            } else {
                                onLog("--- Server stopped cleanly by user ---")
                            }
                        } else if (currentState != ServerState.ERROR) {
                            currentState = ServerState.STOPPED
                            onStateChanged(ServerState.STOPPED)
                        }
                        onShutdownComplete()
                    }
                }
            }
            throw ce
        } catch (t: Throwable) {
            onLog("Server execution exception (run #${run.id}): ${t.message}")
            run.process?.let { terminateProcessGracefully(it, 800L) }
            logJob?.cancel()
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (currentRun?.id == run.id) {
                        currentProcess = null
                        currentRun = null
                        activeJob = null
                        onActiveBackendChanged(null)
                        currentState = ServerState.ERROR
                        onStateChanged(ServerState.ERROR)
                        onError("Server execution exception: ${t.message}")
                        onShutdownComplete()
                    }
                }
            }
        }
    }

    private data class AttemptResult(
        val process: Process?,
        val logJob: Job?,
        val isReady: Boolean,
        val exitCode: Int?,
        val shouldAbort: Boolean,
        val capturedLogs: List<String>
    )

    private suspend fun launchAndSuperviseAttempt(
        run: ActiveRun,
        config: ServerConfig,
        isFallback: Boolean
    ): AttemptResult {
        var isReadyDetected = false
        val capturedLogs = CopyOnWriteArrayList<String>()
        val detectedDevices = CopyOnWriteArrayList<ActiveDevice>()

        val cmd = buildCommandLine(config)
        val env = buildEnvironment(config)
        onLog("Command: ${cmd.joinToString(" ")}")
        onLog("LD_LIBRARY_PATH: ${env["LD_LIBRARY_PATH"]}")

        val launchedProc = try {
            processLauncher(cmd, env, config.cacheDir)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return AttemptResult(
                process = null,
                logJob = null,
                isReady = false,
                exitCode = null,
                shouldAbort = false,
                capturedLogs = capturedLogs.toList()
            )
        }

        // Guard against Stop/Cancellation during spawn
        val shouldAbort = withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (run.stopRequested || currentState != ServerState.STARTING || currentRun?.id != run.id) {
                    onLog("Process created after stop requested or state left STARTING ($currentState); terminating immediately")
                    terminateProcessGracefully(launchedProc, 800L)
                    true
                } else {
                    run.process = launchedProc
                    currentProcess = launchedProc
                    false
                }
            }
        }
        if (shouldAbort) {
            return AttemptResult(
                process = launchedProc,
                logJob = null,
                isReady = false,
                exitCode = null,
                shouldAbort = true,
                capturedLogs = capturedLogs.toList()
            )
        }

        coroutineContext.ensureActive()

        // Stream process output and parse active devices
        var inDevicesSection = false
        val logJob = scope.launch(ioDispatcher) {
            try {
                val reader = BufferedReader(InputStreamReader(launchedProc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { rawLine ->
                        capturedLogs.add(rawLine)
                        onLog(rawLine)

                        val trimmed = rawLine.trim()
                        if (trimmed == "Devices:") {
                            inDevicesSection = true
                        } else if (inDevicesSection) {
                            deviceLineRegex.find(trimmed)?.let { match ->
                                val devId = match.groupValues[1].trim()
                                val devName = match.groupValues[2].trim()
                                val mem = match.groupValues[3].trim()
                                detectedDevices.add(ActiveDevice(id = devId, name = devName, memoryInfo = mem))

                                onActiveBackendChanged(
                                    ActiveBackendInfo(
                                        requestedBackend = BackendType.fromArg(run.requestedBackend),
                                        effectiveBackend = BackendType.fromArg(config.backend),
                                        activeDevices = detectedDevices.toList(),
                                        isFallback = run.isFallbackActive,
                                        fallbackReason = run.fallbackReason
                                    )
                                )
                            }
                            if (trimmed.startsWith("transport") || trimmed.contains("TCP")) {
                                inDevicesSection = false
                            }
                        }

                        if (rawLine.contains("transport      : TCP") || rawLine.contains("Starting RPC server")) {
                            isReadyDetected = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val isReady = probeReadiness(config.host, config.port, launchedProc) { isReadyDetected }
        val alive = launchedProc.isAlive
        val exitCode = if (!alive) {
            try { launchedProc.exitValue() } catch (_: Exception) { null }
        } else null

        // If process exited, ensure reader job finishes consuming remaining buffered output
        if (!alive) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(300L) { logJob.join() }
            } catch (_: Exception) {}
        }

        // If ready, report actual confirmed devices or empty list (unverified)
        // Never invent or fabricate a GPU device when unconfirmed by native output
        if (isReady && detectedDevices.isEmpty()) {
            onActiveBackendChanged(
                ActiveBackendInfo(
                    requestedBackend = BackendType.fromArg(run.requestedBackend),
                    effectiveBackend = BackendType.fromArg(config.backend),
                    activeDevices = emptyList(),
                    isFallback = run.isFallbackActive,
                    fallbackReason = run.fallbackReason
                )
            )
        }

        return AttemptResult(
            process = launchedProc,
            logJob = logJob,
            isReady = isReady,
            exitCode = exitCode,
            shouldAbort = false,
            capturedLogs = capturedLogs.toList()
        )
    }

    private suspend fun finalizeCleanStop(run: ActiveRun) = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (currentRun?.id == run.id) {
                currentState = ServerState.STOPPED
                onStateChanged(ServerState.STOPPED)
                onActiveBackendChanged(null)
                currentRun = null
                currentProcess = null
                activeJob = null
                onLog("--- Server stopped during startup by user ---")
                onShutdownComplete()
            }
        }
    }

    private suspend fun finalizeError(run: ActiveRun, exitCode: Int?, errorMsg: String) = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (currentRun?.id == run.id) {
                currentProcess = null
                currentRun = null
                activeJob = null
                onActiveBackendChanged(null)
                currentState = ServerState.ERROR
                onStateChanged(ServerState.ERROR)
                onError(errorMsg)
                onShutdownComplete()
            }
        }
    }

    private suspend fun probeReadiness(
        host: String,
        port: Int,
        process: Process,
        isLogSignaled: () -> Boolean
    ): Boolean = withContext(ioDispatcher) {
        val deadline = System.currentTimeMillis() + 5000L
        val probeHost = if (host == "0.0.0.0") "127.0.0.1" else host

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                return@withContext false
            }

            if (isLogSignaled()) {
                if (portChecker(probeHost, port)) {
                    return@withContext true
                }
            } else if (portChecker(probeHost, port)) {
                return@withContext true
            }

            delay(150)
        }
        return@withContext false
    }

    suspend fun stop(): Boolean {
        val runToStop: ActiveRun?
        val jobToCancel: Job?
        val procToTerminate: Process?

        lifecycleMutex.withLock {
            if (currentState == ServerState.STOPPED) {
                return false
            }

            runToStop = currentRun
            if (runToStop == null && activeJob == null) {
                currentState = ServerState.STOPPED
                onStateChanged(ServerState.STOPPED)
                onActiveBackendChanged(null)
                onShutdownComplete()
                return false
            }

            onLog("Initiating graceful shutdown...")
            currentState = ServerState.STOPPING
            onStateChanged(ServerState.STOPPING)

            runToStop?.stopRequested = true
            procToTerminate = runToStop?.process ?: currentProcess
            jobToCancel = activeJob
        }

        // Terminate process outside of lifecycleMutex to prevent deadlock with supervisor
        if (procToTerminate != null && procToTerminate.isAlive) {
            terminateProcessGracefully(procToTerminate, 800L)
        }

        jobToCancel?.cancel()
        try {
            jobToCancel?.join()
        } catch (_: Exception) {}

        lifecycleMutex.withLock {
            if (currentRun == runToStop && (currentState == ServerState.STOPPING || currentState != ServerState.ERROR && currentState != ServerState.STOPPED)) {
                currentProcess = null
                currentRun = null
                activeJob = null
                currentState = ServerState.STOPPED
                onStateChanged(ServerState.STOPPED)
                onActiveBackendChanged(null)
                onShutdownComplete()
            }
        }
        return true
    }

    fun destroySync(timeoutMs: Long = 800L) {
        val runToStop = currentRun
        runToStop?.stopRequested = true
        runToStop?.forcedKillRequested = true

        activeJob?.cancel()
        activeJob = null

        val proc = runToStop?.process ?: currentProcess
        currentProcess = null
        currentRun = null

        if (proc != null && proc.isAlive) {
            terminateProcessSync(proc, timeoutMs)
        }

        // HIGH-02: Do NOT overwrite ERROR state on service destruction
        if (currentState != ServerState.ERROR) {
            currentState = ServerState.STOPPED
            onStateChanged(ServerState.STOPPED)
            onActiveBackendChanged(null)
        }
        onShutdownComplete()
    }

    suspend fun terminateProcessGracefully(proc: Process, timeoutMs: Long = 800L) = withContext(NonCancellable + ioDispatcher) {
        if (!proc.isAlive) {
            try { proc.waitFor() } catch (_: Exception) {}
            return@withContext
        }
        try {
            proc.destroy() // SIGTERM
        } catch (_: Exception) {}

        val deadline = System.currentTimeMillis() + timeoutMs
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            delay(40)
        }

        if (proc.isAlive) {
            try {
                proc.destroyForcibly() // SIGKILL
            } catch (_: Exception) {}
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                proc.waitFor(200, TimeUnit.MILLISECONDS)
            } else {
                proc.waitFor()
            }
        } catch (_: Exception) {}
    }

    fun terminateProcessSync(proc: Process, timeoutMs: Long = 800L) {
        if (!proc.isAlive) {
            try { proc.waitFor() } catch (_: Exception) {}
            return
        }
        try {
            proc.destroy() // SIGTERM
        } catch (_: Exception) {}

        val gracefulTime = minOf(timeoutMs, 400L)
        val deadline = System.currentTimeMillis() + gracefulTime
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                break
            }
        }

        if (proc.isAlive) {
            try {
                proc.destroyForcibly() // SIGKILL
            } catch (_: Exception) {}
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                proc.waitFor(200, TimeUnit.MILLISECONDS)
            } else {
                val waitDeadline = System.currentTimeMillis() + 200L
                while (proc.isAlive && System.currentTimeMillis() < waitDeadline) {
                    Thread.sleep(20)
                }
            }
        } catch (_: Exception) {}
    }
}
