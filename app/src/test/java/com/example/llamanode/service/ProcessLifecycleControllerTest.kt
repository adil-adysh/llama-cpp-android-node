package com.example.llamanode.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FakeProcess(
    private var alive: Boolean = true,
    private var exitCode: Int = 0,
    private val output: String = "Starting RPC server\ntransport      : TCP\n",
    private val destroyExitCode: Int = 0,
    private val forceDestroyExitCode: Int = 137
) : Process() {
    var wasDestroyed: Boolean = false
    var wasForciblyDestroyed: Boolean = false

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        while (alive) {
            Thread.sleep(10)
        }
        return exitCode
    }

    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException("Process not terminated")
        return exitCode
    }

    override fun destroy() {
        wasDestroyed = true
        exitCode = destroyExitCode
        alive = false
    }

    override fun destroyForcibly(): Process {
        wasForciblyDestroyed = true
        exitCode = forceDestroyExitCode
        alive = false
        return this
    }

    override fun isAlive(): Boolean = alive

    fun kill(code: Int) {
        alive = false
        exitCode = code
    }
}

class ProcessLifecycleControllerTest {

    private lateinit var testScope: CoroutineScope
    private val stateTransitions = mutableListOf<ServerState>()
    private val logs = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val shutdownCompleted = AtomicBoolean(false)
    private lateinit var tempDir: File
    private lateinit var dummyBinary: File

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.IO + Job())
        stateTransitions.clear()
        logs.clear()
        errors.clear()
        shutdownCompleted.set(false)

        tempDir = File(System.getProperty("java.io.tmpdir"), "llamanode_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
        dummyBinary = File(tempDir, "libggml-rpc-server.so")
        dummyBinary.createNewFile()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        tempDir.deleteRecursively()
    }

    private fun createConfig(
        host: String = "127.0.0.1",
        port: Int = 50052,
        backend: String = "VULKAN",
        threads: Int = 4,
        useCache: Boolean = true
    ): ServerConfig {
        return ServerConfig(
            binaryPath = dummyBinary.absolutePath,
            host = host,
            port = port,
            useCache = useCache,
            backend = backend,
            threads = threads,
            cacheDir = File(tempDir, "cache"),
            filesDir = File(tempDir, "files"),
            nativeLibDir = tempDir.absolutePath
        )
    }

    @Test
    fun testCommandLineConstruction() {
        val controller = ProcessLifecycleController(scope = testScope)
        val configVulkan = createConfig(backend = "VULKAN", threads = 6, useCache = true)
        val cmdVulkan = controller.buildCommandLine(configVulkan)

        assertEquals(dummyBinary.absolutePath, cmdVulkan[0])
        assertTrue(cmdVulkan.contains("-H"))
        assertTrue(cmdVulkan.contains("127.0.0.1"))
        assertTrue(cmdVulkan.contains("-p"))
        assertTrue(cmdVulkan.contains("50052"))
        assertTrue(cmdVulkan.contains("-c"))
        assertTrue(cmdVulkan.contains("-t"))
        assertTrue(cmdVulkan.contains("6"))
        assertTrue(cmdVulkan.contains("-d"))
        assertTrue(cmdVulkan.contains("Vulkan0"))

        val configCpu = createConfig(backend = "CPU", threads = 0, useCache = false)
        val cmdCpu = controller.buildCommandLine(configCpu)
        assertFalse(cmdCpu.contains("-c"))
        assertFalse(cmdCpu.contains("-t"))
        assertTrue(cmdCpu.contains("CPU"))
    }

    @Test
    fun testSuccessfulStartupAndReadiness() = runBlocking {
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        val started = controller.start(createConfig())
        assertEquals(StartResult.STARTED, started)

        // Wait until RUNNING state is reached
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.RUNNING, controller.state)
        assertTrue(stateTransitions.contains(ServerState.STARTING))
        assertTrue(stateTransitions.contains(ServerState.RUNNING))
        assertTrue(logs.any { it.contains("Server is READY and listening") })

        // Now stop the server cleanly
        val stopped = controller.stop()
        assertTrue(stopped)
        assertEquals(ServerState.STOPPED, controller.state)
        assertTrue(fakeProcess.wasDestroyed)
        assertTrue(shutdownCompleted.get())
    }

    @Test
    fun testStopWhileReadinessProbing() = runBlocking {
        // Process is alive but port is not ready yet
        val fakeProcess = FakeProcess(alive = true, exitCode = 0, output = "Starting up...\n")
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> false }, // Never ready
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        val started = controller.start(createConfig())
        assertEquals(StartResult.STARTED, started)
        delay(100)
        assertEquals(ServerState.STARTING, controller.state)

        // Stop while still probing
        val stopped = controller.stop()
        assertTrue(stopped)
        assertEquals(ServerState.STOPPED, controller.state)
        assertTrue(fakeProcess.wasDestroyed)
        assertFalse(fakeProcess.isAlive)
        assertNull(controller.processInstance)
    }

    @Test
    fun testReadinessTimeoutWithProcessStillAlive() = runBlocking {
        // Fake process that stays alive, but never opens port
        val hangingProcess = FakeProcess(alive = true, exitCode = 0, output = "")
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> hangingProcess },
            portChecker = { _, _ -> false },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())

        // Wait for readiness timeout
        var retries = 0
        while (controller.state != ServerState.ERROR && retries < 120) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.ERROR, controller.state)
        assertTrue("Hanging process should have been destroyed on timeout", hangingProcess.wasDestroyed)
        assertFalse("Hanging process should not be alive", hangingProcess.isAlive)
        assertNull(controller.processInstance)
        assertTrue(errors.any { it.contains("did not start listening") })
        assertTrue(shutdownCompleted.get())
    }

    @Test
    fun testNativeProcessCrashNonzeroExit() = runBlocking {
        val crashingProcess = FakeProcess(alive = false, exitCode = 139, output = "Segmentation fault\n")
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> crashingProcess },
            portChecker = { _, _ -> false },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())

        var retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.ERROR, controller.state)
        assertTrue(errors.any { it.contains("exited with code 139") })
        assertNull(controller.processInstance)
        assertTrue(shutdownCompleted.get())
    }

    @Test
    fun testMultipleRapidStartStopCalls() = runBlocking {
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        // Rapid start -> stop -> start -> stop
        controller.start(createConfig())
        controller.stop()
        controller.start(createConfig())
        controller.stop()

        assertEquals(ServerState.STOPPED, controller.state)
        assertNull(controller.processInstance)
    }

    @Test
    fun testServiceDestructionProcessCleanup() = runBlocking {
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())
        delay(50)

        // Simulates Android RpcServerService.onDestroy()
        controller.destroySync(timeoutMs = 800L)

        assertEquals(ServerState.STOPPED, controller.state)
        assertTrue(fakeProcess.wasDestroyed)
        assertFalse(fakeProcess.isAlive)
        assertNull(controller.processInstance)
        assertTrue(shutdownCompleted.get())
    }

    @Test
    fun testStopWhileProcessLauncherBlocked() = runBlocking {
        val launchEntered = CountDownLatch(1)
        val unblockLauncher = CountDownLatch(1)
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                launchEntered.countDown()
                unblockLauncher.await(5, TimeUnit.SECONDS)
                fakeProcess
            },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        // Start server in background
        controller.start(createConfig())

        // Wait until launcher is actively blocked
        assertTrue(launchEntered.await(2, TimeUnit.SECONDS))
        assertEquals(ServerState.STARTING, controller.state)

        // Stop while launcher is blocked
        val stopped = controller.stop()
        assertTrue(stopped)
        assertEquals(ServerState.STOPPED, controller.state)

        // Unblock launcher now
        unblockLauncher.countDown()

        // Wait for coroutine to handle process return and terminate it
        delay(300)

        // CRITICAL INVARIANT: The process launched after Stop must be destroyed and not orphaned!
        assertTrue("Process launched after stop must be destroyed", fakeProcess.wasDestroyed)
        assertFalse(fakeProcess.isAlive)
        assertNull(controller.processInstance)
    }

    @Test
    fun testDestroySyncWhileProcessLauncherBlocked() = runBlocking {
        val launchEntered = CountDownLatch(1)
        val unblockLauncher = CountDownLatch(1)
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                launchEntered.countDown()
                unblockLauncher.await(5, TimeUnit.SECONDS)
                fakeProcess
            },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())
        assertTrue(launchEntered.await(2, TimeUnit.SECONDS))

        // Destroy service synchronously while launcher is blocked
        controller.destroySync(timeoutMs = 500L)
        assertEquals(ServerState.STOPPED, controller.state)

        // Unblock launcher
        unblockLauncher.countDown()
        delay(300)

        assertTrue("Process created after destroy must be destroyed", fakeProcess.wasDestroyed)
        assertFalse(fakeProcess.isAlive)
        assertNull(controller.processInstance)
    }

    @Test
    fun testDuplicateStartWhileStartingDoesNotDisrupt() = runBlocking {
        val unblockLauncher = CountDownLatch(1)
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                unblockLauncher.await(5, TimeUnit.SECONDS)
                fakeProcess
            },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        val firstStart = controller.start(createConfig())
        assertEquals(StartResult.STARTED, firstStart)
        assertEquals(ServerState.STARTING, controller.state)

        // Duplicate start while already starting
        val secondStart = controller.start(createConfig())
        assertEquals(StartResult.ALREADY_ACTIVE, secondStart)
        assertEquals(ServerState.STARTING, controller.state)

        // Unblock launcher and let server reach RUNNING
        unblockLauncher.countDown()
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.RUNNING, controller.state)

        // Duplicate start while already running
        val thirdStart = controller.start(createConfig())
        assertEquals(StartResult.ALREADY_ACTIVE, thirdStart)
        assertEquals(ServerState.RUNNING, controller.state)

        controller.stop()
        assertEquals(ServerState.STOPPED, controller.state)
    }

    @Test
    fun testServiceDestructionPreservesErrorState() = runBlocking {
        val crashingProcess = FakeProcess(alive = false, exitCode = 1, output = "Init error\n")
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> crashingProcess },
            portChecker = { _, _ -> false },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())

        var retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.ERROR, controller.state)
        assertTrue(errors.isNotEmpty())

        // Simulate Android service onDestroy calling destroySync
        controller.destroySync(timeoutMs = 500L)

        // HIGH-02: State MUST remain ERROR and not be wiped to STOPPED
        assertEquals(ServerState.ERROR, controller.state)
    }

    @Test
    fun testIntentionalSigterm143ReportsStoppedWithoutError() = runBlocking {
        val fakeProcess = FakeProcess(alive = true, exitCode = 0, destroyExitCode = 143)
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)

        // User stops the server -> SIGTERM (143) is intentional
        val stopped = controller.stop()
        assertTrue(stopped)
        assertEquals(ServerState.STOPPED, controller.state)
        assertTrue(fakeProcess.wasDestroyed)
        assertEquals(143, fakeProcess.exitValue())
        assertTrue("No error should be recorded for intentional stop with exit code 143", errors.isEmpty())
        assertTrue("Log should indicate clean stop by user", logs.any { it.contains("stopped cleanly by user") })
        assertTrue(shutdownCompleted.get())
    }

    @Test
    fun testUnexpectedSigterm143ReportsError() = runBlocking {
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)

        // Process killed externally by SIGTERM (143) without user stop request
        fakeProcess.kill(143)

        retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.ERROR, controller.state)
        assertTrue(errors.any { it.contains("unexpected SIGTERM (exit code 143)") })
        assertTrue(shutdownCompleted.get())
    }

    @Test
    fun testUnexpectedSigkill137SimulatedOOMReportsError() = runBlocking {
        val fakeProcess = FakeProcess(alive = true, exitCode = 0)
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)

        // Process killed by Android Low Memory Killer (SIGKILL 137) without user stop request
        fakeProcess.kill(137)

        retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.ERROR, controller.state)
        assertTrue(errors.any { it.contains("unexpected SIGKILL/OOM (exit code 137)") })
        assertTrue(shutdownCompleted.get())
    }

    @Test
    fun testUserForcedTerminationDistinguished() = runBlocking {
        val fakeProcess = FakeProcess(alive = true, exitCode = 0, forceDestroyExitCode = 137)
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> fakeProcess },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        controller.start(createConfig())
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)

        controller.currentRunInstance?.forcedKillRequested = true
        val stopped = controller.stop()
        assertTrue(stopped)

        assertEquals(ServerState.STOPPED, controller.state)
        assertTrue(logs.any { it.contains("forced termination") })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testStopFollowedByRestartDoesNotOverwriteNewWorkerState() = runBlocking {
        var processCounter = 0
        val proc1 = FakeProcess(alive = true, exitCode = 0, destroyExitCode = 143)
        val proc2 = FakeProcess(alive = true, exitCode = 0)

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                processCounter++
                if (processCounter == 1) proc1 else proc2
            },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCompleted.set(true) }
        )

        // Start run 1
        controller.start(createConfig())
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)
        val run1Id = controller.currentRunInstance?.id
        assertNotNull(run1Id)

        // Stop run 1
        controller.stop()
        assertEquals(ServerState.STOPPED, controller.state)

        // Start run 2
        val start2 = controller.start(createConfig())
        assertEquals(StartResult.STARTED, start2)
        val run2Id = controller.currentRunInstance?.id
        assertNotNull(run2Id)
        assertTrue(run2Id!! > run1Id!!)

        retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)

        // If proc1 delayed exit occurs now, it must NOT overwrite run2's RUNNING state with STOPPED
        proc1.kill(143)
        delay(100)

        assertEquals(ServerState.RUNNING, controller.state)
        assertEquals(proc2, controller.processInstance)
        assertEquals(run2Id, controller.currentRunInstance?.id)
    }

    @Test
    fun testOldProcessCannotRemoveForegroundServiceOrLocksOfNewRun() = runBlocking {
        var shutdownCount = 0
        var processCounter = 0
        val proc1 = FakeProcess(alive = true, exitCode = 0, destroyExitCode = 143)
        val proc2 = FakeProcess(alive = true, exitCode = 0)

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                processCounter++
                if (processCounter == 1) proc1 else proc2
            },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onShutdownComplete = { shutdownCount++ }
        )

        controller.start(createConfig())
        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(1L, controller.currentRunInstance?.id)

        controller.stop()
        assertEquals(1, shutdownCount)

        controller.start(createConfig())
        retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(2L, controller.currentRunInstance?.id)

        // Simulate an old proc1 delayed termination event
        proc1.kill(143)
        delay(100)

        // Shutdown count must remain 1 because proc1 was superseded by run #2
        assertEquals("Old run must not call onShutdownComplete for newer run", 1, shutdownCount)
        assertEquals(ServerState.RUNNING, controller.state)
    }

    @Test
    fun testAutoModeVulkanSuccessDoesNotFallback() = runBlocking {
        var activeBackend: ActiveBackendInfo? = null
        val proc = FakeProcess(
            alive = true,
            exitCode = 0,
            output = "ggml_vulkan: Found 1 Vulkan devices:\nDevices:\n  Vulkan0: Mali-G715 (11257 MiB, 11257 MiB free)\ntransport      : TCP\n"
        )
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> proc },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onActiveBackendChanged = { activeBackend = it }
        )

        val res = controller.start(createConfig(backend = "AUTO"))
        assertEquals(StartResult.STARTED, res)

        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.RUNNING, controller.state)
        assertEquals(false, controller.currentRunInstance?.isFallbackActive)
        assertNotNull(activeBackend)
        assertEquals(BackendType.AUTO, activeBackend?.requestedBackend)
        assertEquals(BackendType.AUTO, activeBackend?.effectiveBackend)
        assertEquals(false, activeBackend?.isFallback)
        assertTrue(activeBackend?.activeDevices?.any { it.id == "Vulkan0" } == true)
    }

    @Test
    fun testAutoModeVulkanInitFailureTriggersCpuFallback() = runBlocking {
        var processLaunchCount = 0
        var activeBackend: ActiveBackendInfo? = null
        val proc1 = FakeProcess(
            alive = false,
            exitCode = 1,
            output = "ggml_vulkan: failed to initialize Vulkan device\n"
        )
        val proc2 = FakeProcess(
            alive = true,
            exitCode = 0,
            output = "Devices:\n  CPU: CPU (11572 MiB, 11572 MiB free)\ntransport      : TCP\n"
        )

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { cmd, _, _ ->
                processLaunchCount++
                if (processLaunchCount == 1) {
                    assertFalse("Attempt 1 in AUTO mode should not have -d CPU", cmd.contains("CPU"))
                    proc1
                } else {
                    assertTrue("Attempt 2 fallback should pass -d CPU", cmd.contains("CPU"))
                    proc2
                }
            },
            portChecker = { _, _ -> processLaunchCount > 1 },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onActiveBackendChanged = { activeBackend = it }
        )

        controller.start(createConfig(backend = "AUTO"))

        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(2, processLaunchCount)
        assertEquals(ServerState.RUNNING, controller.state)
        assertEquals(true, controller.currentRunInstance?.isFallbackActive)
        assertNotNull(activeBackend)
        assertEquals(BackendType.AUTO, activeBackend?.requestedBackend)
        assertEquals(BackendType.CPU, activeBackend?.effectiveBackend)
        assertEquals(true, activeBackend?.isFallback)
        assertTrue(activeBackend?.activeDevices?.any { it.id == "CPU" } == true)
        assertTrue(logs.any { it.contains("[AUTO-FALLBACK]") })
    }

    @Test
    fun testAutoModeUnrelatedPortConflictDoesNotFallback() = runBlocking {
        var processLaunchCount = 0
        val proc = FakeProcess(
            alive = false,
            exitCode = 1,
            output = "bind: Address already in use\n"
        )

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                processLaunchCount++
                proc
            },
            portChecker = { _, _ -> false },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) }
        )

        controller.start(createConfig(backend = "AUTO"))

        var retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals("Must NOT launch fallback for port conflict", 1, processLaunchCount)
        assertEquals(ServerState.ERROR, controller.state)
        assertTrue(errors.any { it.contains("Port conflict") || it.contains("Address already in use") || it.contains("exit code 1") })
    }

    @Test
    fun testAutoModeUnexplainedCrashDoesNotFallback() = runBlocking {
        var processLaunchCount = 0
        val proc = FakeProcess(
            alive = false,
            exitCode = 139,
            output = "Segmentation fault\n"
        )

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                processLaunchCount++
                proc
            },
            portChecker = { _, _ -> false },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) }
        )

        controller.start(createConfig(backend = "AUTO"))

        var retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals("Must NOT launch fallback for unexplained crash without Vulkan error", 1, processLaunchCount)
        assertEquals(ServerState.ERROR, controller.state)
    }

    @Test
    fun testExplicitVulkanFailureDoesNotFallback() = runBlocking {
        var processLaunchCount = 0
        val proc = FakeProcess(
            alive = false,
            exitCode = 1,
            output = "ggml_vulkan: failed to initialize Vulkan device\n"
        )

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                processLaunchCount++
                proc
            },
            portChecker = { _, _ -> false },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) }
        )

        controller.start(createConfig(backend = "VULKAN"))

        var retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals("Explicit VULKAN mode must not fallback to CPU", 1, processLaunchCount)
        assertEquals(ServerState.ERROR, controller.state)
    }

    @Test
    fun testAutoModeRuntimeCrashDoesNotFallback() = runBlocking {
        var processLaunchCount = 0
        val proc = FakeProcess(
            alive = true,
            exitCode = 0,
            output = "Devices:\n  Vulkan0: Mali-G715 (11257 MiB, 11257 MiB free)\ntransport      : TCP\n"
        )

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                processLaunchCount++
                proc
            },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) }
        )

        controller.start(createConfig(backend = "AUTO"))

        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)

        // Mid-session crash: kill process with exit code 139
        proc.kill(139)

        retries = 0
        while (controller.state != ServerState.ERROR && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals("Must NOT switch backends mid-session once RUNNING", 1, processLaunchCount)
        assertEquals(ServerState.ERROR, controller.state)
        assertTrue(errors.any { it.contains("crashed with SIGSEGV") })
    }

    @Test
    fun testStartupCancellationDuringFallback() = runBlocking {
        var processLaunchCount = 0
        val proc1 = FakeProcess(
            alive = false,
            exitCode = 1,
            output = "ggml_vulkan: failed to initialize Vulkan device\n"
        )
        val proc2 = FakeProcess(
            alive = true,
            exitCode = 0,
            destroyExitCode = 143,
            output = "Devices:\n  CPU: CPU (11572 MiB, 11572 MiB free)\n"
        )

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ ->
                processLaunchCount++
                if (processLaunchCount == 1) proc1 else proc2
            },
            portChecker = { _, _ -> false },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) }
        )

        controller.start(createConfig(backend = "AUTO"))

        // Wait until fallback attempt 2 is spawned
        var retries = 0
        while (processLaunchCount < 2 && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(2, processLaunchCount)

        // Cancel during fallback
        val stopped = controller.stop()
        assertTrue(stopped)
        assertEquals(ServerState.STOPPED, controller.state)
        assertTrue("proc2 must be destroyed on cancellation", proc2.wasDestroyed)
    }

    @Test
    fun testAutoModeNoDeviceLogsReportsUnverifiedWithoutInventingGpu() = runBlocking {
        var activeBackend: ActiveBackendInfo? = null
        val proc = FakeProcess(
            alive = true,
            exitCode = 0,
            output = "Starting RPC server\ntransport      : TCP\n"
        )
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> proc },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onActiveBackendChanged = { activeBackend = it }
        )

        val res = controller.start(createConfig(backend = "AUTO"))
        assertEquals(StartResult.STARTED, res)

        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.RUNNING, controller.state)
        assertNotNull(activeBackend)
        assertEquals(BackendType.AUTO, activeBackend?.requestedBackend)
        assertEquals(BackendType.AUTO, activeBackend?.effectiveBackend)
        assertTrue("activeDevices must be empty when no device log is parsed, never inventing Vulkan0",
            activeBackend?.activeDevices?.isEmpty() == true)
        assertFalse(activeBackend?.isFallback == true)
    }

    @Test
    fun testAutoModeCpuOnlyDeviceReportsCpu() = runBlocking {
        var activeBackend: ActiveBackendInfo? = null
        val proc = FakeProcess(
            alive = true,
            exitCode = 0,
            output = "Devices:\n  CPU: CPU (11572 MiB, 11572 MiB free)\ntransport      : TCP\n"
        )
        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> proc },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) },
            onActiveBackendChanged = { activeBackend = it }
        )

        val res = controller.start(createConfig(backend = "AUTO"))
        assertEquals(StartResult.STARTED, res)

        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.RUNNING, controller.state)
        assertNotNull(activeBackend)
        assertEquals(BackendType.AUTO, activeBackend?.requestedBackend)
        assertEquals(BackendType.AUTO, activeBackend?.effectiveBackend)
        assertEquals(1, activeBackend?.activeDevices?.size)
        assertEquals("CPU", activeBackend?.activeDevices?.first()?.id)
        assertFalse("Native CPU selection in AUTO mode without Vulkan failure is not fallback", activeBackend?.isFallback == true)
    }

    @Test
    fun testThreadSafeLogCollectionUnderConcurrentStream() = runBlocking {
        val pipedOut = java.io.PipedOutputStream()
        val pipedIn = java.io.PipedInputStream(pipedOut)
        val writerAlive = java.util.concurrent.atomic.AtomicBoolean(true)

        val writerThread = Thread {
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(pipedOut))
            var counter = 0
            try {
                writer.write("Starting RPC server\n")
                writer.write("transport      : TCP\n")
                writer.flush()
                while (writerAlive.get()) {
                    writer.write("Log line ${counter++}\n")
                    writer.flush()
                    Thread.sleep(1)
                }
            } catch (_: Exception) {}
        }
        writerThread.start()

        val streamingProc = object : Process() {
            private var alive = true
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = pipedIn
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int {
                while (alive) {
                    Thread.sleep(10)
                }
                return 0
            }
            override fun exitValue(): Int {
                if (alive) throw IllegalThreadStateException("Process alive")
                return 0
            }
            override fun destroy() {
                alive = false
                writerAlive.set(false)
                try { pipedOut.close() } catch (_: Exception) {}
            }
            override fun destroyForcibly(): Process {
                destroy()
                return this
            }
        }

        val controller = ProcessLifecycleController(
            scope = testScope,
            processLauncher = { _, _, _ -> streamingProc },
            portChecker = { _, _ -> true },
            onStateChanged = { stateTransitions.add(it) },
            onLog = { logs.add(it) },
            onError = { errors.add(it) }
        )

        controller.start(createConfig(backend = "AUTO"))

        var retries = 0
        while (controller.state != ServerState.RUNNING && retries < 50) {
            delay(50)
            retries++
        }
        assertEquals(ServerState.RUNNING, controller.state)

        for (i in 0 until 50) {
            val logSnapshot = logs.toList()
            assertTrue(logSnapshot.isNotEmpty())
            delay(5)
        }

        controller.stop()
        writerAlive.set(false)
        try { pipedOut.close() } catch (_: Exception) {}
        writerThread.join(1000)
    }
}
