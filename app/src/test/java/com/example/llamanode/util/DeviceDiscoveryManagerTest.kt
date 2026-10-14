package com.example.llamanode.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
