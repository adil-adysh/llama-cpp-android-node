package com.example.llamanode.service

import android.content.Context
import android.content.Intent
import com.example.llamanode.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

enum class BackendType(val label: String, val arg: String) {
    AUTO("Auto (Vulkan GPU -> CPU)", "AUTO"),
    VULKAN("Vulkan GPU (Adreno / Mali)", "VULKAN"),
    CPU("CPU (ARM Neon / DotProd)", "CPU"),
    BOTH("Both (Vulkan + CPU)", "BOTH")
}

enum class NetworkBinding(val label: String, val host: String) {
    LOOPBACK("Loopback (127.0.0.1)", "127.0.0.1"),
    LAN("LAN / Wi-Fi (0.0.0.0)", "0.0.0.0")
}

object RpcServerManager {
    private const val MAX_LOGS = 500

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _ipAddress = MutableStateFlow("127.0.0.1")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    val port = MutableStateFlow(50052)
    val enableCache = MutableStateFlow(true)
    val selectedBackend = MutableStateFlow(BackendType.AUTO)
    val networkBinding = MutableStateFlow(NetworkBinding.LOOPBACK)
    val threadCount = MutableStateFlow(0)

    private val logQueue = ConcurrentLinkedDeque<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun refreshIp() {
        _ipAddress.value = if (networkBinding.value == NetworkBinding.LOOPBACK) {
            "127.0.0.1"
        } else {
            NetworkUtils.getLocalIpAddress()
        }
    }

    fun setState(state: ServerState) {
        _serverState.value = state
    }

    fun addLog(message: String) {
        logQueue.add(message)
        while (logQueue.size > MAX_LOGS) {
            logQueue.pollFirst()
        }
        _logs.value = logQueue.toList()
    }

    fun clearLogs() {
        logQueue.clear()
        _logs.value = emptyList()
    }

    fun startServer(context: Context) {
        if (_serverState.value == ServerState.RUNNING || _serverState.value == ServerState.STARTING) return
        refreshIp()
        val intent = Intent(context, RpcServerService::class.java).apply {
            action = RpcServerService.ACTION_START
            putExtra(RpcServerService.EXTRA_HOST, networkBinding.value.host)
            putExtra(RpcServerService.EXTRA_PORT, port.value)
            putExtra(RpcServerService.EXTRA_CACHE, enableCache.value)
            putExtra(RpcServerService.EXTRA_BACKEND, selectedBackend.value.arg)
            putExtra(RpcServerService.EXTRA_THREADS, threadCount.value)
        }
        context.startForegroundService(intent)
    }

    fun stopServer(context: Context) {
        val intent = Intent(context, RpcServerService::class.java).apply {
            action = RpcServerService.ACTION_STOP
        }
        context.startService(intent)
    }
}
