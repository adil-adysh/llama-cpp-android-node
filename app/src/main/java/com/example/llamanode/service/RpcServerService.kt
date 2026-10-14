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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class RpcServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var lifecycleController: ProcessLifecycleController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        lifecycleController = ProcessLifecycleController(
            scope = serviceScope,
            ioDispatcher = Dispatchers.IO,
            onStateChanged = { state ->
                RpcServerManager.setState(state)
                if (state == ServerState.RUNNING || state == ServerState.STOPPED) {
                    serviceScope.launch {
                        RpcServerManager.refreshCacheSize(this@RpcServerService)
                    }
                }
            },
            onLog = { msg -> RpcServerManager.addLog(msg) },
            onError = { err -> RpcServerManager.setError(err) },
            onActiveBackendChanged = { info -> RpcServerManager.setActiveBackendInfo(info) },
            onShutdownComplete = {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    lifecycleController.stop()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 50052)
                val useCache = intent.getBooleanExtra(EXTRA_CACHE, true)
                val backend = intent.getStringExtra(EXTRA_BACKEND) ?: "AUTO"
                val threads = intent.getIntExtra(EXTRA_THREADS, 0)

                val config = ServerConfig(
                    binaryPath = File(applicationInfo.nativeLibraryDir, BINARY_NAME).absolutePath,
                    host = host,
                    port = port,
                    useCache = useCache,
                    backend = backend,
                    threads = threads,
                    cacheDir = RpcServerManager.getCacheDirectory(this@RpcServerService),
                    filesDir = filesDir,
                    nativeLibDir = applicationInfo.nativeLibraryDir
                )

                acquireLocks()
                startForeground(NOTIFICATION_ID, buildNotification(port))

                serviceScope.launch {
                    when (lifecycleController.start(config)) {
                        StartResult.STARTED -> {
                            // Initiated startup
                        }
                        StartResult.ALREADY_ACTIVE -> {
                            // Idempotent duplicate intent: keep running!
                        }
                        StartResult.FAILED -> {
                            releaseLocks()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
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
            manager?.createNotificationChannel(channel)
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
        lifecycleController.destroySync(800L)
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
