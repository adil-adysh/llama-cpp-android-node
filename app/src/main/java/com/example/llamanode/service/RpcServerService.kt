package com.example.llamanode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.llamanode.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class RpcServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var serverProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var activeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    stopRpcServerAsync()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 50052)
                val useCache = intent.getBooleanExtra(EXTRA_CACHE, true)
                val backend = intent.getStringExtra(EXTRA_BACKEND) ?: "AUTO"
                val threads = intent.getIntExtra(EXTRA_THREADS, 0)
                startRpcServerAsync(host, port, useCache, backend, threads)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startRpcServerAsync(host: String, port: Int, useCache: Boolean, backend: String, threads: Int) {
        if (serverProcess != null) return

        RpcServerManager.setState(ServerState.STARTING)
        RpcServerManager.addLog("--- Initializing LlamaNode RPC Server on $host:$port ($backend) ---")
        if (host == "0.0.0.0") {
            RpcServerManager.addLog("SECURITY WARNING: Binding to 0.0.0.0 (LAN). Upstream RPC traffic is unauthenticated and unencrypted. Ensure you are on a trusted private network.")
        }

        acquireLocks()
        startForeground(NOTIFICATION_ID, buildNotification(port))

        activeJob = serviceScope.launch {
            try {
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val binaryFile = File(nativeLibDir, BINARY_NAME)

                if (!binaryFile.exists()) {
                    val errMsg = "Native binary $BINARY_NAME not found in $nativeLibDir"
                    RpcServerManager.setError(errMsg)
                    cleanupAndStop()
                    return@launch
                }

                val cacheDir = RpcServerManager.getCacheDirectory(this@RpcServerService)
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                val cmd = mutableListOf(
                    binaryFile.absolutePath,
                    "-H", host,
                    "-p", port.toString()
                )
                if (useCache) {
                    cmd.add("-c")
                }
                if (threads > 0) {
                    cmd.add("-t")
                    cmd.add(threads.toString())
                }
                when (backend.uppercase()) {
                    "VULKAN" -> {
                        cmd.add("-d")
                        cmd.add("Vulkan0")
                    }
                    "CPU" -> {
                        cmd.add("-d")
                        cmd.add("CPU")
                    }
                    "BOTH" -> {
                        cmd.add("-d")
                        cmd.add("Vulkan0,CPU")
                    }
                }

                val existingLd = System.getenv("LD_LIBRARY_PATH")
                val ldLibPath = if (!existingLd.isNullOrEmpty()) {
                    "$nativeLibDir:$existingLd"
                } else {
                    "$nativeLibDir:/system/lib64:/vendor/lib64"
                }

                RpcServerManager.addLog("Command: ${cmd.joinToString(" ")}")
                RpcServerManager.addLog("LD_LIBRARY_PATH: $ldLibPath")

                val processBuilder = ProcessBuilder(cmd).apply {
                    directory(cacheDir)
                    environment()["LD_LIBRARY_PATH"] = ldLibPath
                    environment()["HOME"] = filesDir.absolutePath
                    environment()["TMPDIR"] = cacheDir.absolutePath
                    environment()["XDG_CACHE_HOME"] = cacheDir.absolutePath
                    redirectErrorStream(true)
                }

                val process = processBuilder.start()
                serverProcess = process

                // Stream stdout/stderr in a coroutine
                var isReadyDetected = false
                val logJob = launch(Dispatchers.IO) {
                    try {
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let {
                                RpcServerManager.addLog(it)
                                if (it.contains("transport      : TCP") || it.contains("Starting RPC server")) {
                                    isReadyDetected = true
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Probe TCP readiness with timeout (up to 5s)
                val isReady = probeServerReadiness(host, port, process) { isReadyDetected }
                if (!isReady) {
                    if (!process.isAlive) {
                        val exitCode = process.exitValue()
                        RpcServerManager.setError("Server failed to initialize and exited with code $exitCode")
                    } else {
                        RpcServerManager.setError("Server did not start listening on port $port within 5 seconds")
                    }
                    logJob.cancel()
                    cleanupAndStop()
                    return@launch
                }

                RpcServerManager.setState(ServerState.RUNNING)
                RpcServerManager.addLog("--- Server is READY and listening on $host:$port ---")
                RpcServerManager.refreshCacheSize(this@RpcServerService)

                // Wait asynchronously for process exit
                val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                logJob.join()

                RpcServerManager.refreshCacheSize(this@RpcServerService)
                if (RpcServerManager.serverState.value == ServerState.STOPPING) {
                    RpcServerManager.setState(ServerState.STOPPED)
                    RpcServerManager.addLog("--- Server stopped cleanly ---")
                } else if (exitCode != 0) {
                    RpcServerManager.setError("Server process crashed with exit code $exitCode")
                } else {
                    RpcServerManager.setState(ServerState.STOPPED)
                    RpcServerManager.addLog("--- Server process terminated with code 0 ---")
                }
            } catch (e: Exception) {
                RpcServerManager.setError("Execution exception: ${e.message}")
            } finally {
                cleanupAndStop()
            }
        }
    }

    private suspend fun probeServerReadiness(
        host: String,
        port: Int,
        process: Process,
        isLogSignaled: () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 5000L
        val probeHost = if (host == "0.0.0.0") "127.0.0.1" else host

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                return@withContext false
            }

            if (isLogSignaled()) {
                // Confirm with brief socket connect
                if (canConnectToPort(probeHost, port)) {
                    return@withContext true
                }
            } else if (canConnectToPort(probeHost, port)) {
                return@withContext true
            }

            delay(150)
        }
        return@withContext false
    }

    private fun canConnectToPort(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun stopRpcServerAsync() {
        withContext(Dispatchers.IO) {
            RpcServerManager.addLog("Initiating graceful shutdown...")
            val proc = serverProcess
            if (proc != null && proc.isAlive) {
                try {
                    proc.destroy() // SIGTERM
                    val terminated = withTimeoutOrNull(3000L) {
                        while (proc.isAlive) {
                            delay(100)
                        }
                        true
                    }
                    if (terminated == null && proc.isAlive) {
                        RpcServerManager.addLog("Process did not exit in 3s; killing forcibly...")
                        proc.destroyForcibly() // SIGKILL
                    }
                } catch (e: Exception) {
                    proc.destroyForcibly()
                }
            }
            serverProcess = null
            RpcServerManager.refreshCacheSize(this@RpcServerService)
        }
    }

    private fun cleanupAndStop() {
        serverProcess = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaNode:RpcWakeLock").apply {
                acquire(12 * 60 * 60 * 1000L) // 12 hours max safety limit
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LlamaNode:RpcWifiLock").apply {
                acquire()
            }
            RpcServerManager.addLog("Acquired CPU WakeLock & High-Performance WifiLock")
        } catch (e: Exception) {
            RpcServerManager.addLog("Warning acquiring locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LlamaNode RPC Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the llama.cpp RPC server running in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(this, RpcServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val ip = RpcServerManager.ipAddress.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LlamaNode RPC Active")
            .setContentText("Listening on $ip:$port")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.launch {
            stopRpcServerAsync()
        }
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.llamarpc.node.ACTION_START"
        const val ACTION_STOP = "com.llamarpc.node.ACTION_STOP"
        const val EXTRA_HOST = "EXTRA_HOST"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_CACHE = "EXTRA_CACHE"
        const val EXTRA_BACKEND = "EXTRA_BACKEND"
        const val EXTRA_THREADS = "EXTRA_THREADS"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "llama_rpc_server_channel"
        private const val BINARY_NAME = "libggml-rpc-server.so"
    }
}
