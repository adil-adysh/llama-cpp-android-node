package com.example.llamanode.service

import android.content.Context
import android.content.Intent
import com.example.llamanode.util.HardwareCapabilities
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
    AUTO("Auto (GPU-first)", "AUTO"),
    VULKAN("Vulkan GPU", "VULKAN"),
    CPU("ARM CPU", "CPU"),
    BOTH("Vulkan + CPU", "BOTH");

    companion object {
        fun fromArg(arg: String): BackendType {
            return entries.firstOrNull { it.arg.equals(arg, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class NetworkBinding(val label: String, val host: String) {
    LOOPBACK("Loopback (127.0.0.1)", "127.0.0.1"),
    LAN("LAN / Wi-Fi (0.0.0.0)", "0.0.0.0")
}

data class ActiveDevice(
    val id: String,          // e.g. "Vulkan0", "CPU"
    val name: String,        // e.g. "Mali-G715", "CPU"
    val memoryInfo: String   // e.g. "11257 MiB, 11257 MiB free"
)

data class ActiveBackendInfo(
    val requestedBackend: BackendType,
    val effectiveBackend: BackendType = requestedBackend,
    val activeDevices: List<ActiveDevice>,
    val isFallback: Boolean = false,
    val fallbackReason: String? = null
)

object RpcServerManager {
    private const val MAX_LOGS = 500
    private const val PREFS_NAME = "llamanode_prefs"
    private const val KEY_BACKEND = "pref_selected_backend"
    private const val KEY_PORT = "pref_port"
    private const val KEY_CACHE = "pref_cache"
    private const val KEY_BINDING = "pref_binding"

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

    private val _hardwareCapabilities = MutableStateFlow(HardwareCapabilities())
    val hardwareCapabilities: StateFlow<HardwareCapabilities> = _hardwareCapabilities.asStateFlow()

    private val _activeBackendInfo = MutableStateFlow<ActiveBackendInfo?>(null)
    val activeBackendInfo: StateFlow<ActiveBackendInfo?> = _activeBackendInfo.asStateFlow()

    private val logQueue = ConcurrentLinkedDeque<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun initPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedBackend = prefs.getString(KEY_BACKEND, null)
        if (savedBackend != null) {
            try {
                selectedBackend.value = BackendType.valueOf(savedBackend)
            } catch (_: Exception) {}
        }
        val savedPort = prefs.getInt(KEY_PORT, -1)
        if (savedPort in 1024..65535) {
            port.value = savedPort
        }
        if (prefs.contains(KEY_CACHE)) {
            enableCache.value = prefs.getBoolean(KEY_CACHE, true)
        }
        val savedBinding = prefs.getString(KEY_BINDING, null)
        if (savedBinding != null) {
            try {
                networkBinding.value = NetworkBinding.valueOf(savedBinding)
            } catch (_: Exception) {}
        }
    }

    fun savePreferences(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BACKEND, selectedBackend.value.name)
            .putInt(KEY_PORT, port.value)
            .putBoolean(KEY_CACHE, enableCache.value)
            .putString(KEY_BINDING, networkBinding.value.name)
            .apply()
    }

    fun setBackend(context: Context, backend: BackendType) {
        selectedBackend.value = backend
        savePreferences(context)
    }

    fun setNetworkBinding(context: Context, binding: NetworkBinding) {
        networkBinding.value = binding
        refreshIp()
        savePreferences(context)
    }

    fun setPort(context: Context, p: Int) {
        if (isValidPort(p)) {
            port.value = p
            savePreferences(context)
        }
    }

    fun setEnableCache(context: Context, enabled: Boolean) {
        enableCache.value = enabled
        savePreferences(context)
    }

    fun setHardwareCapabilities(caps: HardwareCapabilities) {
        _hardwareCapabilities.value = caps
    }

    fun setActiveBackendInfo(info: ActiveBackendInfo?) {
        _activeBackendInfo.value = info
    }

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
        if (state == ServerState.STOPPED) {
            _activeBackendInfo.value = null
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
