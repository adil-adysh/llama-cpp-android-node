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
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class RpcServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var serverProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRpcServer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 50052)
                val useCache = intent.getBooleanExtra(EXTRA_CACHE, true)
                val backend = intent.getStringExtra(EXTRA_BACKEND) ?: "AUTO"
                val threads = intent.getIntExtra(EXTRA_THREADS, 0)
                startRpcServer(host, port, useCache, backend, threads)
            }
        }
        return START_STICKY
    }

    private fun startRpcServer(host: String, port: Int, useCache: Boolean, backend: String, threads: Int) {
        if (serverProcess != null) return

        RpcServerManager.setState(ServerState.STARTING)
        RpcServerManager.addLog("--- Starting LlamaNode RPC Server on $host:$port (Backend: $backend) ---")
        if (host == "0.0.0.0") {
            RpcServerManager.addLog("WARNING: Bound to 0.0.0.0 (LAN). RPC traffic is unauthenticated. Use only on trusted networks.")
        }

        acquireLocks()
        startForeground(NOTIFICATION_ID, buildNotification(port))

        serviceScope.launch {
            try {
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val binaryFile = File(nativeLibDir, BINARY_NAME)

                if (!binaryFile.exists()) {
                    val errMsg = "Error: Native binary $BINARY_NAME not found in $nativeLibDir"
                    RpcServerManager.addLog(errMsg)
                    RpcServerManager.setState(ServerState.ERROR)
                    stopSelf()
                    return@launch
                }

                // Ensure local cache directory exists
                val cacheDir = File(filesDir, ".cache/llama.cpp/rpc")
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
                    // "AUTO": let ggml-rpc-server default logic choose Vulkan GPU, fallback to CPU
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
                    directory(filesDir)
                    environment()["LD_LIBRARY_PATH"] = ldLibPath
                    environment()["HOME"] = filesDir.absolutePath
                    environment()["TMPDIR"] = cacheDir.absolutePath
                    redirectErrorStream(true)
                }

                val process = processBuilder.start()
                serverProcess = process
                RpcServerManager.setState(ServerState.RUNNING)

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { RpcServerManager.addLog(it) }
                }

                val exitCode = process.waitFor()
                RpcServerManager.addLog("--- Process exited with code $exitCode ---")
            } catch (e: Exception) {
                RpcServerManager.addLog("Execution Exception: ${e.message}")
                e.printStackTrace()
            } finally {
                serverProcess = null
                RpcServerManager.setState(ServerState.STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopRpcServer() {
        RpcServerManager.addLog("Stopping RPC Server...")
        serverProcess?.let {
            try {
                it.destroy()
                it.waitFor()
            } catch (_: Exception) {
                it.destroyForcibly()
            }
        }
        serverProcess = null
        RpcServerManager.setState(ServerState.STOPPED)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        stopRpcServer()
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
