package com.example.llamanode.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class DiscoveredDevice(
    val id: String,          // e.g. "Vulkan0", "CPU"
    val name: String,        // e.g. "Mali-G715", "CPU"
    val memoryInfo: String   // e.g. "11257 MiB, 11257 MiB free"
)

data class HardwareCapabilities(
    val hasVulkan: Boolean = false,
    val vulkanDevice: DiscoveredDevice? = null,
    val cpuDevice: DiscoveredDevice? = null,
    val allDevices: List<DiscoveredDevice> = emptyList(),
    val isDiscovered: Boolean = false,
    val discoverySource: String = "pending"
)

object DeviceDiscoveryManager {

    private val deviceLineRegex = Regex("""^\s*(Vulkan\d*|CPU\d*):\s+([^(]+?)\s+\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val vulkanModelRegex = Regex("""ggml_vulkan:\s*\d+\s*=\s*([^(|]+)""")

    suspend fun discoverCapabilities(
        context: Context,
        binaryPath: String,
        nativeLibDir: String,
        timeoutMs: Long = 2500L,
        processLauncher: (cmd: List<String>, env: Map<String, String>) -> Process = { cmd, env ->
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            pb.start()
        }
    ): HardwareCapabilities = withContext(Dispatchers.IO) {
        val binaryFile = File(binaryPath)
        if (!binaryFile.exists() || !binaryFile.canExecute()) {
            return@withContext getPackageManagerFallback(context)
        }

        val discovered = probeNativeBinaryWithTimeout(binaryFile, nativeLibDir, timeoutMs, processLauncher)
        discovered ?: getPackageManagerFallback(context)
    }

    suspend fun probeNativeBinaryWithTimeout(
        binaryFile: File,
        nativeLibDir: String,
        timeoutMs: Long,
        processLauncher: (cmd: List<String>, env: Map<String, String>) -> Process
    ): HardwareCapabilities? = withContext(Dispatchers.IO) {
        var proc: Process? = null
        val lines = CopyOnWriteArrayList<String>()
        var readerJob: Job? = null

        try {
            val cmd = listOf(binaryFile.absolutePath, "-d", "__probe_discovery__")
            val ldLibPath = "$nativeLibDir:/system/lib64:/vendor/lib64"
            val env = mapOf("LD_LIBRARY_PATH" to ldLibPath)

            proc = processLauncher(cmd, env)
            val currentProc = proc

            readerJob = launch(Dispatchers.IO) {
                try {
                    val reader = BufferedReader(InputStreamReader(currentProc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { lines.add(it) }
                    }
                } catch (_: Exception) {}
            }

            val completedInTime = withTimeoutOrNull(timeoutMs) {
                readerJob.join()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    currentProc.waitFor(500L, TimeUnit.MILLISECONDS)
                } else {
                    currentProc.waitFor()
                }
                true
            }

            if (completedInTime == true) {
                parseProbeOutput(lines.toList())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            readerJob?.cancel()
            proc?.let { p ->
                try { p.inputStream.close() } catch (_: Exception) {}
                try { p.errorStream.close() } catch (_: Exception) {}
                try { p.outputStream.close() } catch (_: Exception) {}
                try {
                    p.destroyForcibly()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        p.waitFor(200L, TimeUnit.MILLISECONDS)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun parseProbeOutput(lines: List<String>): HardwareCapabilities {
        var hasVulkan = false
        var vulkanDevice: DiscoveredDevice? = null
        var cpuDevice: DiscoveredDevice? = null
        val allDevices = mutableListOf<DiscoveredDevice>()
        var foundGpuModel: String? = null

        for (l in lines) {
            val trimmed = l.trim()

            // Check Vulkan driver detection
            if (trimmed.contains("ggml_vulkan: Found") && !trimmed.contains("Found 0 Vulkan devices")) {
                hasVulkan = true
            }

            vulkanModelRegex.find(trimmed)?.let { match ->
                foundGpuModel = match.groupValues[1].trim()
            }

            // Parse device lines: e.g. "  Vulkan0: Mali-G715 (11257 MiB, 11257 MiB free)"
            deviceLineRegex.find(trimmed)?.let { match ->
                val id = match.groupValues[1].trim()
                val rawName = match.groupValues[2].trim()
                val memory = match.groupValues[3].trim()

                val resolvedName = if (id.startsWith("Vulkan", ignoreCase = true) && !foundGpuModel.isNullOrBlank()) {
                    foundGpuModel
                } else {
                    rawName
                }

                val dev = DiscoveredDevice(id = id, name = resolvedName, memoryInfo = memory)
                allDevices.add(dev)

                if (id.startsWith("Vulkan", ignoreCase = true)) {
                    hasVulkan = true
                    if (vulkanDevice == null) {
                        vulkanDevice = dev
                    }
                } else if (id.equals("CPU", ignoreCase = true)) {
                    cpuDevice = dev
                }
            }
        }

        // If CPU device was not explicitly listed in probe output, add default CPU
        if (cpuDevice == null) {
            val hardwareName = try { Build.HARDWARE?.ifBlank { "ARM CPU" } ?: "ARM CPU" } catch (_: Exception) { "ARM CPU" }
            cpuDevice = DiscoveredDevice(
                id = "CPU",
                name = hardwareName,
                memoryInfo = "System RAM available"
            )
            allDevices.add(cpuDevice)
        }

        return HardwareCapabilities(
            hasVulkan = hasVulkan,
            vulkanDevice = vulkanDevice,
            cpuDevice = cpuDevice,
            allDevices = allDevices,
            isDiscovered = true,
            discoverySource = "native_probe"
        )
    }

    fun getPackageManagerFallback(context: Context): HardwareCapabilities {
        val pm = context.packageManager
        val hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

        val vulkanDev = if (hasVulkan) {
            DiscoveredDevice(
                id = "Vulkan0",
                name = "${Build.HARDWARE} GPU",
                memoryInfo = "Vulkan supported"
            )
        } else null

        val cpuDev = DiscoveredDevice(
            id = "CPU",
            name = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"})",
            memoryInfo = "System RAM"
        )

        val devices = mutableListOf<DiscoveredDevice>()
        vulkanDev?.let { devices.add(it) }
        devices.add(cpuDev)

        return HardwareCapabilities(
            hasVulkan = hasVulkan,
            vulkanDevice = vulkanDev,
            cpuDevice = cpuDev,
            allDevices = devices,
            isDiscovered = true,
            discoverySource = "system_features"
        )
    }
}
