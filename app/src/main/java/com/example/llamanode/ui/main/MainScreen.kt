package com.example.llamanode.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.llamanode.service.BackendType
import com.example.llamanode.service.NetworkBinding
import com.example.llamanode.service.RpcServerManager
import com.example.llamanode.util.DeviceDiscoveryManager
import java.io.File
import com.example.llamanode.service.ServerState
import com.example.llamanode.updater.AppReleaseInfo
import com.example.llamanode.updater.UpdateManager
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val serverState by RpcServerManager.serverState.collectAsStateWithLifecycle()
    val errorMessage by RpcServerManager.errorMessage.collectAsStateWithLifecycle()
    val ipAddress by RpcServerManager.ipAddress.collectAsStateWithLifecycle()
    val port by RpcServerManager.port.collectAsStateWithLifecycle()
    val enableCache by RpcServerManager.enableCache.collectAsStateWithLifecycle()
    val cacheSizeBytes by RpcServerManager.cacheSizeBytes.collectAsStateWithLifecycle()
    val selectedBackend by RpcServerManager.selectedBackend.collectAsStateWithLifecycle()
    val networkBinding by RpcServerManager.networkBinding.collectAsStateWithLifecycle()
    val hardwareCapabilities by RpcServerManager.hardwareCapabilities.collectAsStateWithLifecycle()
    val activeBackendInfo by RpcServerManager.activeBackendInfo.collectAsStateWithLifecycle()
    val logs by RpcServerManager.logs.collectAsStateWithLifecycle()

    val canConfigure = serverState == ServerState.STOPPED || serverState == ServerState.ERROR

    var portInput by remember(port) { mutableStateOf(port.toString()) }
    val isPortValid = remember(portInput) {
        portInput.toIntOrNull()?.let { RpcServerManager.isValidPort(it) } ?: false
    }

    var autoScrollLogs by remember { mutableStateOf(true) }
    var showLanWarningDialog by remember { mutableStateOf(false) }
    var updateReleaseInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdates by remember { mutableStateOf(false) }

    // Request notification permission on Android 13+ (non-blocking)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            RpcServerManager.startServer(context)
        } else {
            Toast.makeText(context, "Notification permission denied; starting server without status bar notifications", Toast.LENGTH_LONG).show()
            RpcServerManager.startServer(context)
        }
    }

    LaunchedEffect(Unit) {
        RpcServerManager.initPreferences(context)
        RpcServerManager.refreshIp()
        RpcServerManager.refreshCacheSize(context)
        val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libggml-rpc-server.so").absolutePath
        val caps = DeviceDiscoveryManager.discoverCapabilities(context, binaryPath, context.applicationInfo.nativeLibraryDir)
        RpcServerManager.setHardwareCapabilities(caps)
    }

    fun startServerWithPermission() {
        if (!isPortValid) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                RpcServerManager.startServer(context)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            RpcServerManager.startServer(context)
        }
    }

    fun copyToClipboard(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied: $label", Toast.LENGTH_SHORT).show()
    }

    // LAN Exposure Warning Dialog
    if (showLanWarningDialog) {
        AlertDialog(
            onDismissRequest = { showLanWarningDialog = false },
            title = { Text("Confirm LAN Exposure (0.0.0.0)") },
            text = {
                Text(
                    "SECURITY WARNING: The upstream llama.cpp RPC server has no encryption or authentication.\n\n" +
                    "Exposing it to 0.0.0.0 allows any device on your Wi-Fi network to submit computation jobs to your phone.\n\n" +
                    "For secure operation, use Loopback (127.0.0.1) with USB cable (ADB port forwarding) or a private VPN tunnel."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        RpcServerManager.networkBinding.value = NetworkBinding.LAN
                        RpcServerManager.refreshIp()
                        showLanWarningDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Expose to LAN")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLanWarningDialog = false }) {
                    Text("Keep Loopback")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LlamaNode", fontWeight = FontWeight.Bold)
                        Text(
                            "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} • Vulkan & CPU",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { RpcServerManager.refreshIp() },
                        modifier = Modifier.semantics { contentDescription = "Refresh device IP address" }
                    ) {
                        Text("Refresh IP")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Error banner if any
            if (errorMessage != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Error",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = errorMessage.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { RpcServerManager.clearError() }) {
                                Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            // 1. Status & Control Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = "Server status: ${serverState.name} on $ipAddress:$port"
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusColor by animateColorAsState(
                                    when (serverState) {
                                        ServerState.RUNNING -> Color(0xFF4CAF50)
                                        ServerState.STARTING -> Color(0xFFFF9800)
                                        ServerState.STOPPING -> Color(0xFFFF9800)
                                        ServerState.STOPPED -> Color(0xFF9E9E9E)
                                        ServerState.ERROR -> Color(0xFFF44336)
                                    },
                                    label = "statusColor"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = serverState.name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "$ipAddress:$port",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Active Devices & Auto Fallback Status Card
                        val currentActiveBackend = activeBackendInfo
                        AnimatedVisibility(visible = currentActiveBackend != null || serverState == ServerState.RUNNING || serverState == ServerState.STARTING) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (currentActiveBackend?.isFallback == true) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = if (currentActiveBackend?.isFallback == true) {
                                            "Active backend fallback: CPU active because Vulkan initialization failed. Effective backend: ${currentActiveBackend.effectiveBackend.label}. Reason: ${currentActiveBackend.fallbackReason.orEmpty()}"
                                        } else if (currentActiveBackend?.activeDevices.isNullOrEmpty()) {
                                            "Active backend: ${currentActiveBackend?.effectiveBackend?.label ?: selectedBackend.label}. Device not yet verified."
                                        } else {
                                            "Active backend: ${currentActiveBackend.effectiveBackend.label}. ${currentActiveBackend.activeDevices.joinToString { "${it.id}: ${it.name}" }}"
                                        }
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (currentActiveBackend?.isFallback == true) "⚠️ Auto Fallback Active" else "⚡ Active Devices",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (currentActiveBackend?.isFallback == true) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        )
                                        Text(
                                            text = if (currentActiveBackend?.isFallback == true) {
                                                "(Requested: ${currentActiveBackend.requestedBackend.label} → Effective: ${currentActiveBackend.effectiveBackend.label})"
                                            } else {
                                                "(Requested: ${currentActiveBackend?.requestedBackend?.label ?: selectedBackend.label})"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (currentActiveBackend?.activeDevices.isNullOrEmpty()) {
                                        Text(
                                            text = if (serverState == ServerState.STARTING) "Initializing backend devices..." else "Device not yet verified",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        currentActiveBackend.activeDevices.forEach { dev ->
                                            Text(
                                                text = "• ${dev.id}: ${dev.name} (${dev.memoryInfo})",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    if (currentActiveBackend?.isFallback == true && !currentActiveBackend.fallbackReason.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Reason: ${currentActiveBackend.fallbackReason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Configuration Row (editable when stopped or in error)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { input ->
                                    portInput = input
                                    input.toIntOrNull()?.let { p ->
                                        if (RpcServerManager.isValidPort(p)) {
                                            RpcServerManager.setPort(context, p)
                                        }
                                    }
                                },
                                label = { Text("Port") },
                                isError = !isPortValid && portInput.isNotEmpty(),
                                supportingText = {
                                    if (!isPortValid && portInput.isNotEmpty()) {
                                        Text("1024..65535", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                enabled = canConfigure,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "RPC Server Port" }
                            )

                            // Unified Cache Clickable Row
                            Column(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .padding(top = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = canConfigure) {
                                            RpcServerManager.setEnableCache(context, !enableCache)
                                        }
                                        .semantics {
                                            contentDescription = "Tensor Cache: ${if (enableCache) "Enabled" else "Disabled"}"
                                        }
                                ) {
                                    Checkbox(
                                        checked = enableCache,
                                        onCheckedChange = { RpcServerManager.setEnableCache(context, it) },
                                        enabled = canConfigure
                                    )
                                    Text("Tensor Cache (-c)", style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = "Size: ${formatBytes(cacheSizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Clear",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable(enabled = canConfigure) {
                                                scope.launch {
                                                    RpcServerManager.clearCache(context)
                                                    Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .semantics { contentDescription = "Clear tensor cache on disk" }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Network Interface Exposure
                        Text(
                            text = "Network Interface Exposure",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = networkBinding == NetworkBinding.LOOPBACK,
                                onClick = {
                                    if (canConfigure) {
                                        RpcServerManager.setNetworkBinding(context, NetworkBinding.LOOPBACK)
                                    }
                                },
                                label = { Text("Loopback (127.0.0.1 - Safe)", fontSize = 12.sp) },
                                enabled = canConfigure,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Bind to safe loopback interface 127.0.0.1" }
                            )
                            FilterChip(
                                selected = networkBinding == NetworkBinding.LAN,
                                onClick = {
                                    if (canConfigure) {
                                        showLanWarningDialog = true
                                    }
                                },
                                label = { Text("LAN / Wi-Fi (0.0.0.0)", fontSize = 12.sp) },
                                enabled = canConfigure,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Bind to LAN interface 0.0.0.0" }
                            )
                        }

                        // Security Warning Banner when LAN is selected
                        AnimatedVisibility(visible = networkBinding == NetworkBinding.LAN) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("⚠️", fontSize = 16.sp)
                                    Text(
                                        text = "Security notice: llama.cpp RPC protocol has no encryption or authentication. Anyone on your local Wi-Fi can run arbitrary computations.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Backend Selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Compute Backend",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hardwareCapabilities.isDiscovered) {
                                val gpuTitle = hardwareCapabilities.vulkanDevice?.name ?: "Vulkan GPU"
                                Text(
                                    text = if (hardwareCapabilities.hasVulkan) "HW: $gpuTitle + CPU" else "HW: CPU Only",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.semantics {
                                        contentDescription = if (hardwareCapabilities.hasVulkan) {
                                            "Detected hardware: $gpuTitle and ARM CPU"
                                        } else {
                                            "Detected hardware: ARM CPU only. No Vulkan GPU detected."
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedBackend == BackendType.AUTO,
                                onClick = {
                                    if (canConfigure) {
                                        RpcServerManager.setBackend(context, BackendType.AUTO)
                                    }
                                },
                                label = { Text("Auto (GPU)", fontSize = 12.sp) },
                                enabled = canConfigure,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Select Auto backend, GPU first with automatic CPU fallback. Currently ${if (selectedBackend == BackendType.AUTO) "selected" else "not selected"}."
                                    }
                            )
                            val isVulkanSupported = !hardwareCapabilities.isDiscovered || hardwareCapabilities.hasVulkan
                            FilterChip(
                                selected = selectedBackend == BackendType.VULKAN,
                                onClick = {
                                    if (canConfigure && isVulkanSupported) {
                                        RpcServerManager.setBackend(context, BackendType.VULKAN)
                                    }
                                },
                                label = { Text("Vulkan GPU", fontSize = 12.sp) },
                                enabled = canConfigure && isVulkanSupported,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Select Vulkan GPU strict backend. ${if (hardwareCapabilities.hasVulkan) hardwareCapabilities.vulkanDevice?.name.orEmpty() else ""}. Currently ${if (selectedBackend == BackendType.VULKAN) "selected" else "not selected"}."
                                    }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedBackend == BackendType.CPU,
                                onClick = {
                                    if (canConfigure) {
                                        RpcServerManager.setBackend(context, BackendType.CPU)
                                    }
                                },
                                label = { Text("ARM CPU", fontSize = 12.sp) },
                                enabled = canConfigure,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Select ARM CPU strict backend. Currently ${if (selectedBackend == BackendType.CPU) "selected" else "not selected"}."
                                    }
                            )
                            val isBothSupported = !hardwareCapabilities.isDiscovered || hardwareCapabilities.hasVulkan
                            FilterChip(
                                selected = selectedBackend == BackendType.BOTH,
                                onClick = {
                                    if (canConfigure && isBothSupported) {
                                        RpcServerManager.setBackend(context, BackendType.BOTH)
                                    }
                                },
                                label = { Text("Vulkan + CPU", fontSize = 12.sp) },
                                enabled = canConfigure && isBothSupported,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Select Both Vulkan and CPU devices. Currently ${if (selectedBackend == BackendType.BOTH) "selected" else "not selected"}."
                                    }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Start / Stop Button
                        if (serverState == ServerState.STOPPED || serverState == ServerState.ERROR) {
                            Button(
                                onClick = { startServerWithPermission() },
                                enabled = isPortValid,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "Start RPC Server" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("START RPC SERVER", fontWeight = FontWeight.Bold)
                            }
                        } else if (serverState == ServerState.STARTING) {
                            Button(
                                onClick = { RpcServerManager.stopServer(context) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("STARTING... (TAP TO CANCEL)", fontWeight = FontWeight.Bold)
                            }
                        } else if (serverState == ServerState.STOPPING) {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("STOPPING SERVER...", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { RpcServerManager.stopServer(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "Stop RPC Server" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("STOP SERVER", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 2. Windows Connect Helpers Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Connect from Windows PC",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // USB ADB Option
                        Text(
                            "Option 1: Over USB Cable (Fastest / Recommended)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeSnippetBox(
                            code = "adb forward tcp:$port tcp:$port",
                            label = "ADB Forward Command",
                            onCopy = { copyToClipboard("adb forward tcp:$port tcp:$port", "ADB Forward Command") }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeSnippetBox(
                            code = "llama-cli.exe -m model.gguf --rpc 127.0.0.1:$port -ngl 99",
                            label = "USB Run Command",
                            onCopy = { copyToClipboard("llama-cli.exe -m model.gguf --rpc 127.0.0.1:$port -ngl 99", "USB Run Command") }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // Wi-Fi Option
                        Text(
                            "Option 2: Over Wi-Fi Network",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeSnippetBox(
                            code = "llama-cli.exe -m model.gguf --rpc $ipAddress:$port -ngl 99",
                            label = "Wi-Fi Run Command",
                            onCopy = { copyToClipboard("llama-cli.exe -m model.gguf --rpc $ipAddress:$port -ngl 99", "Wi-Fi Run Command") }
                        )
                    }
                }
            }

            // 3. Live Terminal Logs Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Console Logs (${logs.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Follow",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Switch(
                                    checked = autoScrollLogs,
                                    onCheckedChange = { autoScrollLogs = it },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .semantics { contentDescription = "Auto-scroll terminal logs" }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = {
                                        copyToClipboard(logs.joinToString("\n"), "All Logs")
                                    },
                                    modifier = Modifier.semantics { contentDescription = "Copy all console logs to clipboard" }
                                ) {
                                    Text("Copy")
                                }
                                TextButton(
                                    onClick = { RpcServerManager.clearLogs() },
                                    modifier = Modifier.semantics { contentDescription = "Clear console logs" }
                                ) {
                                    Text("Clear")
                                }
                            }
                        }

                        val listState = rememberLazyListState()
                        LaunchedEffect(logs.size) {
                            if (autoScrollLogs && logs.isNotEmpty()) {
                                listState.animateScrollToItem(logs.size - 1)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E1E))
                                .padding(8.dp)
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    "No logs yet. Start server to view output.",
                                    color = Color(0xFF757575),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            } else {
                                LazyColumn(state = listState) {
                                    items(logs) { line ->
                                        Text(
                                            text = line,
                                            color = Color(0xFFE0E0E0),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. About & Update Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Engine & Updates",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "App Version: ${UpdateManager.currentVersion} • ARM64 (Vulkan + CPU)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isCheckingUpdates = true
                                    updateStatusMessage = "Checking GitHub..."
                                    scope.launch {
                                        val result = UpdateManager.checkLatestRelease()
                                        isCheckingUpdates = false
                                        result.fold(
                                            onSuccess = { info ->
                                                updateReleaseInfo = info
                                                updateStatusMessage = if (info.isNewer) {
                                                    "New release: ${info.tagName}"
                                                } else {
                                                    "Up to date (${info.tagName})"
                                                }
                                            },
                                            onFailure = { err ->
                                                updateStatusMessage = "Check failed: ${err.message}"
                                            }
                                        )
                                    }
                                },
                                enabled = !isCheckingUpdates,
                                modifier = Modifier.semantics { contentDescription = "Check for app updates on GitHub" }
                            ) {
                                Text("Check for Updates")
                            }

                            updateReleaseInfo?.let { info ->
                                if (info.isNewer && info.apkDownloadUrl != null) {
                                    Button(
                                        onClick = {
                                            UpdateManager.openDownloadUrl(context, info.apkDownloadUrl)
                                        },
                                        modifier = Modifier.semantics { contentDescription = "Download APK update" }
                                    ) {
                                        Text("Download APK")
                                    }
                                }
                            }
                        }

                        updateStatusMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun CodeSnippetBox(
    code: String,
    label: String,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "Copy $label" }
            ) {
                Text("📋", fontSize = 14.sp)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
