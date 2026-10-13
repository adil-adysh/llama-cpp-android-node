package com.example.llamanode.service

import android.content.Context
import android.content.Intent
import com.example.llamanode.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _ipAddress = MutableStateFlow("127.0.0.1")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    val port = MutableStateFlow(50052)
    val enableCache = MutableStateFlow(true)
    val selectedBackend = MutableStateFlow(BackendType.AUTO)
    val networkBinding = MutableStateFlow(NetworkBinding.LOOPBACK)
    val threadCount = MutableStateFlow(0)

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val logQueue = ConcurrentLinkedDeque<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun isValidPort(p: Int): Boolean = p in 1024..65535

    fun refreshIp() {
        _ipAddress.value = if (networkBinding.value == NetworkBinding.LOOPBACK) {
            "127.0.0.1"
        } else {
            NetworkUtils.getLocalIpAddress()
        }
    }

    fun setState(state: ServerState) {
        _serverState.value = state
        if (state != ServerState.ERROR) {
            _errorMessage.value = null
        }
    }

    fun setError(message: String) {
        _errorMessage.value = message
        _serverState.value = ServerState.ERROR
        addLog("ERROR: $message")
    }

    fun clearError() {
        _errorMessage.value = null
        if (_serverState.value == ServerState.ERROR) {
            _serverState.value = ServerState.STOPPED
        }
    }

    fun getCacheDirectory(context: Context): File {
        return File(context.cacheDir, "llama_rpc")
    }

    suspend fun refreshCacheSize(dir: File): Long = withContext(Dispatchers.IO) {
        if (!dir.exists()) {
            _cacheSizeBytes.value = 0L
            return@withContext 0L
        }
        val size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        _cacheSizeBytes.value = size
        size
    }

    suspend fun refreshCacheSize(context: Context): Long = refreshCacheSize(getCacheDirectory(context))

    suspend fun clearCache(dir: File): Boolean = withContext(Dispatchers.IO) {
        val deleted = if (dir.exists()) dir.deleteRecursively() else true
        refreshCacheSize(dir)
        deleted
    }

    suspend fun clearCache(context: Context): Boolean = clearCache(getCacheDirectory(context))

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

    @Synchronized
    fun startServer(context: Context) {
        if (_serverState.value == ServerState.RUNNING || 
            _serverState.value == ServerState.STARTING ||
            _serverState.value == ServerState.STOPPING) {
            return
        }
        
        if (!isValidPort(port.value)) {
            setError("Invalid port ${port.value}. Port must be between 1024 and 65535.")
            return
        }

        clearError()
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

    @Synchronized
    fun stopServer(context: Context) {
        if (_serverState.value == ServerState.STOPPED) return
        _serverState.value = ServerState.STOPPING
        val intent = Intent(context, RpcServerService::class.java).apply {
            action = RpcServerService.ACTION_STOP
        }
        context.startService(intent)
    }
}
