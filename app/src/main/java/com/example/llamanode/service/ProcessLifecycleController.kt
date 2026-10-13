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
    private val onShutdownComplete: () -> Unit = {}
) {
    private val lifecycleMutex = Mutex()
    private var activeJob: Job? = null
    @Volatile private var currentProcess: Process? = null
    @Volatile private var currentState: ServerState = ServerState.STOPPED

    val state: ServerState get() = currentState
    val isRunning: Boolean get() = currentState == ServerState.RUNNING
    val processInstance: Process? get() = currentProcess

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

        activeJob = scope.launch(ioDispatcher) {
            runProcessSupervision(config)
        }
        return StartResult.STARTED
    }

    private suspend fun runProcessSupervision(config: ServerConfig) {
        var proc: Process? = null
        var logJob: Job? = null
        var isReadyDetected = false

        try {
            val cmd = buildCommandLine(config)
            val env = buildEnvironment(config)
            onLog("Command: ${cmd.joinToString(" ")}")
            onLog("LD_LIBRARY_PATH: ${env["LD_LIBRARY_PATH"]}")

            val launchedProc = try {
                processLauncher(cmd, env, config.cacheDir)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                withContext(NonCancellable) {
                    lifecycleMutex.withLock {
                        currentState = ServerState.ERROR
                        onStateChanged(ServerState.ERROR)
                        onError("Failed to launch process: ${t.message}")
                    }
                    onShutdownComplete()
                }
                return
            }

            // CRITICAL-01: Guard against Stop / Cancellation during process launch
            val shouldAbort = withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (currentState != ServerState.STARTING) {
                        onLog("Process created after state left STARTING ($currentState); terminating immediately")
                        terminateProcessGracefully(launchedProc, 800L)
                        true
                    } else {
                        proc = launchedProc
                        currentProcess = launchedProc
                        false
                    }
                }
            }
            if (shouldAbort) {
                onShutdownComplete()
                return
            }

            coroutineContext.ensureActive()

            // Stream process output
            logJob = scope.launch(ioDispatcher) {
                try {
                    val reader = BufferedReader(InputStreamReader(launchedProc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            onLog(it)
                            if (it.contains("transport      : TCP") || it.contains("Starting RPC server")) {
                                isReadyDetected = true
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Probe readiness with 5s timeout
            val isReady = probeReadiness(config.host, config.port, launchedProc) { isReadyDetected }
            if (!isReady) {
                logJob.cancel()
                val alive = launchedProc.isAlive
                val exitCode = if (!alive) {
                    try { launchedProc.exitValue() } catch (_: Exception) { null }
                } else null

                // Terminate running process if readiness timed out
                if (alive) {
                    terminateProcessGracefully(launchedProc, 800L)
                }

                withContext(NonCancellable) {
                    lifecycleMutex.withLock {
                        currentProcess = null
                        currentState = ServerState.ERROR
                        onStateChanged(ServerState.ERROR)
                        if (exitCode != null) {
                            onError("Server failed to initialize and exited with code $exitCode")
                        } else {
                            onError("Server did not start listening on port ${config.port} within 5 seconds")
                        }
                    }
                    onShutdownComplete()
                }
                return
            }

            // Successfully reached readiness
            lifecycleMutex.withLock {
                if (currentState == ServerState.STARTING) {
                    currentState = ServerState.RUNNING
                    onStateChanged(ServerState.RUNNING)
                    onLog("--- Server is READY and listening on ${config.host}:${config.port} ---")
                }
            }

            // Await process exit
            val exitCode = withContext(ioDispatcher) { launchedProc.waitFor() }
            logJob.join()

            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    currentProcess = null
                    if (currentState == ServerState.STOPPING) {
                        currentState = ServerState.STOPPED
                        onStateChanged(ServerState.STOPPED)
                        onLog("--- Server stopped cleanly ---")
                    } else if (exitCode != 0) {
                        currentState = ServerState.ERROR
                        onStateChanged(ServerState.ERROR)
                        onError("Server process crashed with exit code $exitCode")
                    } else {
                        currentState = ServerState.STOPPED
                        onStateChanged(ServerState.STOPPED)
                        onLog("--- Server process terminated with code 0 ---")
                    }
                }
                onShutdownComplete()
            }

        } catch (ce: CancellationException) {
            onLog("Server process supervision cancelled")
            proc?.let { terminateProcessGracefully(it, 800L) }
            logJob?.cancel()
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    currentProcess = null
                    if (currentState != ServerState.ERROR) {
                        currentState = ServerState.STOPPED
                        onStateChanged(ServerState.STOPPED)
                    }
                }
                onShutdownComplete()
            }
            throw ce
        } catch (t: Throwable) {
            onLog("Server execution exception: ${t.message}")
            proc?.let { terminateProcessGracefully(it, 800L) }
            logJob?.cancel()
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    currentProcess = null
                    currentState = ServerState.ERROR
                    onStateChanged(ServerState.ERROR)
                    onError("Server execution exception: ${t.message}")
                }
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

    suspend fun stop(): Boolean = lifecycleMutex.withLock {
        if (currentState == ServerState.STOPPED) {
            return false
        }

        onLog("Initiating graceful shutdown...")
        currentState = ServerState.STOPPING
        onStateChanged(ServerState.STOPPING)

        val jobToCancel = activeJob
        activeJob = null

        val proc = currentProcess
        currentProcess = null

        if (proc != null && proc.isAlive) {
            terminateProcessGracefully(proc, 800L)
        }

        jobToCancel?.cancel()

        currentState = ServerState.STOPPED
        onStateChanged(ServerState.STOPPED)
        onShutdownComplete()
        return true
    }

    fun destroySync(timeoutMs: Long = 800L) {
        activeJob?.cancel()
        activeJob = null

        val proc = currentProcess
        currentProcess = null

        if (proc != null && proc.isAlive) {
            terminateProcessSync(proc, timeoutMs)
        }

        // HIGH-02: Do NOT overwrite ERROR state on service destruction
        if (currentState != ServerState.ERROR) {
            currentState = ServerState.STOPPED
            onStateChanged(ServerState.STOPPED)
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
