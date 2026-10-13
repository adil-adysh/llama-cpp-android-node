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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.example.llamanode.service.ServerState
import com.example.llamanode.updater.UpdateManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val serverState by RpcServerManager.serverState.collectAsStateWithLifecycle()
    val ipAddress by RpcServerManager.ipAddress.collectAsStateWithLifecycle()
    val port by RpcServerManager.port.collectAsStateWithLifecycle()
    val enableCache by RpcServerManager.enableCache.collectAsStateWithLifecycle()
    val selectedBackend by RpcServerManager.selectedBackend.collectAsStateWithLifecycle()
    val networkBinding by RpcServerManager.networkBinding.collectAsStateWithLifecycle()
    val logs by RpcServerManager.logs.collectAsStateWithLifecycle()

    var portInput by remember(port) { mutableStateOf(port.toString()) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdates by remember { mutableStateOf(false) }

    // Request notification permission on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            RpcServerManager.startServer(context)
        } else {
            Toast.makeText(context, "Notification permission required for background server", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        RpcServerManager.refreshIp()
    }

    fun startServerWithPermission() {
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
                    TextButton(onClick = { RpcServerManager.refreshIp() }) {
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
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusColor by animateColorAsState(
                                    when (serverState) {
                                        ServerState.RUNNING -> Color(0xFF4CAF50)
                                        ServerState.STARTING -> Color(0xFFFF9800)
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

                        Spacer(modifier = Modifier.height(12.dp))

                        // Configuration Row (editable when stopped)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = {
                                    portInput = it
                                    it.toIntOrNull()?.let { p -> RpcServerManager.port.value = p }
                                },
                                label = { Text("Port") },
                                enabled = serverState == ServerState.STOPPED,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Checkbox(
                                    checked = enableCache,
                                    onCheckedChange = { RpcServerManager.enableCache.value = it },
                                    enabled = serverState == ServerState.STOPPED
                                )
                                Text("Cache (-c)", style = MaterialTheme.typography.bodyMedium)
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
                                    if (serverState == ServerState.STOPPED) {
                                        RpcServerManager.networkBinding.value = NetworkBinding.LOOPBACK
                                        RpcServerManager.refreshIp()
                                    }
                                },
                                label = { Text("Loopback (127.0.0.1 - Safe)", fontSize = 12.sp) },
                                enabled = serverState == ServerState.STOPPED,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = networkBinding == NetworkBinding.LAN,
                                onClick = {
                                    if (serverState == ServerState.STOPPED) {
                                        RpcServerManager.networkBinding.value = NetworkBinding.LAN
                                        RpcServerManager.refreshIp()
                                    }
                                },
                                label = { Text("LAN / Wi-Fi (0.0.0.0)", fontSize = 12.sp) },
                                enabled = serverState == ServerState.STOPPED,
                                modifier = Modifier.weight(1f)
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
                        Text(
                            text = "Compute Backend",
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
                                selected = selectedBackend == BackendType.AUTO,
                                onClick = {
                                    if (serverState == ServerState.STOPPED) {
                                        RpcServerManager.selectedBackend.value = BackendType.AUTO
                                    }
                                },
                                label = { Text("Auto (GPU)", fontSize = 12.sp) },
                                enabled = serverState == ServerState.STOPPED,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = selectedBackend == BackendType.VULKAN,
                                onClick = {
                                    if (serverState == ServerState.STOPPED) {
                                        RpcServerManager.selectedBackend.value = BackendType.VULKAN
                                    }
                                },
                                label = { Text("Vulkan GPU", fontSize = 12.sp) },
                                enabled = serverState == ServerState.STOPPED,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedBackend == BackendType.CPU,
                                onClick = {
                                    if (serverState == ServerState.STOPPED) {
                                        RpcServerManager.selectedBackend.value = BackendType.CPU
                                    }
                                },
                                label = { Text("ARM CPU", fontSize = 12.sp) },
                                enabled = serverState == ServerState.STOPPED,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = selectedBackend == BackendType.BOTH,
                                onClick = {
                                    if (serverState == ServerState.STOPPED) {
                                        RpcServerManager.selectedBackend.value = BackendType.BOTH
                                    }
                                },
                                label = { Text("Vulkan + CPU", fontSize = 12.sp) },
                                enabled = serverState == ServerState.STOPPED,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Start / Stop Button
                        if (serverState == ServerState.STOPPED || serverState == ServerState.ERROR) {
                            Button(
                                onClick = { startServerWithPermission() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("START RPC SERVER", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { RpcServerManager.stopServer(context) },
                                modifier = Modifier.fillMaxWidth(),
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
                            "Option 1: Over USB Cable (Fastest / Lowest Latency)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeSnippetBox(
                            code = "adb forward tcp:$port tcp:$port",
                            onCopy = { copyToClipboard("adb forward tcp:$port tcp:$port", "ADB Forward Command") }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeSnippetBox(
                            code = "llama-cli.exe -m model.gguf --rpc 127.0.0.1:$port -ngl 99",
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
                            Row {
                                TextButton(onClick = { RpcServerManager.clearLogs() }) {
                                    Text("Clear")
                                }
                            }
                        }

                        val listState = rememberLazyListState()
                        LaunchedEffect(logs.size) {
                            if (logs.isNotEmpty()) {
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
                            "Bundled: llama.cpp ${UpdateManager.CURRENT_LLAMA_CPP_BUILD} (Qualcomm Snapdragon ARM64)",
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
                                            onSuccess = { tag ->
                                                updateStatusMessage = if (tag == UpdateManager.CURRENT_LLAMA_CPP_BUILD) {
                                                    "Up to date ($tag)"
                                                } else {
                                                    "New release available: $tag"
                                                }
                                            },
                                            onFailure = { err ->
                                                updateStatusMessage = "Check failed: ${err.message}"
                                            }
                                        )
                                    }
                                },
                                enabled = !isCheckingUpdates
                            ) {
                                Text("Check for Updates")
                            }

                            updateStatusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
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
                modifier = Modifier.size(28.dp)
            ) {
                Text("📋", fontSize = 14.sp)
            }
        }
    }
}
