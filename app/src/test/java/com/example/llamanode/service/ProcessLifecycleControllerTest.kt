package com.example.llamanode.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        assertTrue(started)

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

        controller.start(createConfig())
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

        // Wait for readiness timeout (or trigger probe completion)
        var retries = 0
        while (controller.state != ServerState.ERROR && retries < 120) {
            delay(50)
            retries++
        }

        assertEquals(ServerState.ERROR, controller.state)
        // CRITICAL P0-2 check: Process MUST be terminated and not left alive!
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
        controller.destroySync(timeoutMs = 1000L)

        assertEquals(ServerState.STOPPED, controller.state)
        assertTrue(fakeProcess.wasDestroyed)
        assertFalse(fakeProcess.isAlive)
        assertNull(controller.processInstance)
        assertTrue(shutdownCompleted.get())
    }
}
