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
    private val output: String = "Starting RPC server\ntransport      : TCP\n"
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
        alive = false
    }

    override fun destroyForcibly(): Process {
        wasForciblyDestroyed = true
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
}
