package com.example.llamanode.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class DeviceDiscoveryManagerTest {

    @Test
    fun testParseProbeOutputWithVulkanAndCpu() {
        val lines = listOf(
            "ggml_vulkan: Found 1 Vulkan devices:",
            "ggml_vulkan: 0 = Mali-G715 (Mali-G715) | uma: 1 | fp16: 1 | matrix cores: KHR_coopmat",
            "error: unknown device: __probe__",
            "available devices:",
            "  Vulkan0: Mali-G715 (11257 MiB, 11257 MiB free)",
            "  CPU: CPU (11572 MiB, 11572 MiB free)"
        )

        val caps = DeviceDiscoveryManager.parseProbeOutput(lines)

        assertTrue(caps.isDiscovered)
        assertTrue(caps.hasVulkan)
        assertNotNull(caps.vulkanDevice)
        assertEquals("Vulkan0", caps.vulkanDevice?.id)
        assertEquals("Mali-G715", caps.vulkanDevice?.name)
        assertEquals("11257 MiB, 11257 MiB free", caps.vulkanDevice?.memoryInfo)

        assertNotNull(caps.cpuDevice)
        assertEquals("CPU", caps.cpuDevice?.id)
        assertEquals("CPU", caps.cpuDevice?.name)
        assertEquals("11572 MiB, 11572 MiB free", caps.cpuDevice?.memoryInfo)
        assertEquals(2, caps.allDevices.size)
    }

    @Test
    fun testParseProbeOutputCpuOnly() {
        val lines = listOf(
            "ggml_vulkan: Found 0 Vulkan devices",
            "error: unknown device: __probe__",
            "available devices:",
            "  CPU: CPU (8192 MiB, 8192 MiB free)"
        )

        val caps = DeviceDiscoveryManager.parseProbeOutput(lines)

        assertTrue(caps.isDiscovered)
        assertFalse(caps.hasVulkan)
        assertEquals(null, caps.vulkanDevice)
        assertNotNull(caps.cpuDevice)
        assertEquals("CPU", caps.cpuDevice?.id)
        assertEquals("8192 MiB, 8192 MiB free", caps.cpuDevice?.memoryInfo)
    }

    @Test
    fun testParseProbeOutputEmptyFallback() {
        val lines = listOf(
            "error: unable to run binary"
        )

        val caps = DeviceDiscoveryManager.parseProbeOutput(lines)

        assertTrue(caps.isDiscovered)
        assertFalse(caps.hasVulkan)
        assertNotNull(caps.cpuDevice)
        assertEquals("CPU", caps.cpuDevice?.id)
    }

    @Test
    fun testProbeNativeBinaryWithTimeoutEnforcesDeadlineAndReapsProcess() = runBlocking {
        val hangingProcess = HangingDiscoveryProcess()
        val tempFile = File.createTempFile("fake_binary", ".so")
        tempFile.deleteOnExit()

        val startTime = System.currentTimeMillis()
        val result = DeviceDiscoveryManager.probeNativeBinaryWithTimeout(
            binaryFile = tempFile,
            nativeLibDir = "/fake/lib",
            timeoutMs = 150L,
            processLauncher = { _, _ -> hangingProcess }
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertNull("Timed out probe must return null", result)
        assertTrue("Process must be forcibly destroyed on timeout", hangingProcess.wasForciblyDestroyed)
        assertTrue("Timeout must be enforced reasonably fast (< 1500ms)", elapsed < 1500L)
    }

    @Test
    fun testProbeNativeBinaryWithTimeoutSucceedsWithinDeadline() = runBlocking {
        val normalProcess = NormalDiscoveryProcess(
            "ggml_vulkan: Found 1 Vulkan devices:\n" +
            "available devices:\n" +
            "  Vulkan0: Mali-G715 (11257 MiB, 11257 MiB free)\n" +
            "  CPU: CPU (11572 MiB, 11572 MiB free)\n"
        )
        val tempFile = File.createTempFile("fake_binary", ".so")
        tempFile.deleteOnExit()

        val result = DeviceDiscoveryManager.probeNativeBinaryWithTimeout(
            binaryFile = tempFile,
            nativeLibDir = "/fake/lib",
            timeoutMs = 1000L,
            processLauncher = { _, _ -> normalProcess }
        )

        assertNotNull(result)
        assertTrue(result?.hasVulkan == true)
        assertEquals("Vulkan0", result?.vulkanDevice?.id)
        assertEquals("CPU", result?.cpuDevice?.id)
    }
}

class HangingDiscoveryProcess(
    private var alive: Boolean = true
) : Process() {
    var wasForciblyDestroyed = false
    private val closed = AtomicBoolean(false)

    private val hangingStream = object : InputStream() {
        override fun read(): Int {
            while (!closed.get() && alive) {
                Thread.sleep(10)
            }
            return -1
        }
        override fun close() {
            closed.set(true)
        }
    }

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = hangingStream
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        while (alive) {
            Thread.sleep(10)
        }
        return 0
    }

    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException("Process not terminated")
        return 0
    }

    override fun destroy() {
        alive = false
        closed.set(true)
    }

    override fun destroyForcibly(): Process {
        wasForciblyDestroyed = true
        alive = false
        closed.set(true)
        return this
    }

    override fun isAlive(): Boolean = alive
}

class NormalDiscoveryProcess(
    private val output: String
) : Process() {
    private var alive = false

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() {}
    override fun destroyForcibly(): Process = this
    override fun isAlive(): Boolean = alive
}
