package com.example.cluster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.cluster.model.PodPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class NodeDaemonService : Service() {

    inner class LocalBinder : Binder() {
        fun getBackend(): ClusterBackendManager = ClusterBackendManager.getInstance(applicationContext)
    }

    companion object {
        private const val CHANNEL_ID = "kubemesh_node_channel"
        private const val NOTIFICATION_ID = 7443
        private const val EXTRA_ENDPOINT = "extra_endpoint"
        private const val EXTRA_ROLE = "extra_role"
        private const val ACTION_STOP = "com.example.kubemesh.STOP_DAEMON"

        fun start(context: Context, endpoint: String, roleLabel: String) {
            val intent = Intent(context, NodeDaemonService::class.java).apply {
                putExtra(EXTRA_ENDPOINT, endpoint)
                putExtra(EXTRA_ROLE, roleLabel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeDaemonService::class.java))
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ClusterBackendManager.getInstance(applicationContext).onDaemonStoppedExternally()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val backend = ClusterBackendManager.getInstance(applicationContext)
        backend.ensureStarted()

        createNotificationChannel()
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT)
            ?: backend.state.value.localNode?.endpoint
            ?: "LAN Ready"
        val role = intent?.getStringExtra(EXTRA_ROLE)
            ?: backend.state.value.config.role.displayName
        val notification = buildNotification(
            endpoint = endpoint,
            role = role,
            peersOnline = backend.state.value.onlinePeersCount,
            runningPods = backend.state.value.pods.count { it.phase == PodPhase.RUNNING }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        observeBackendStatus(backend)
        return START_STICKY
    }

    private fun observeBackendStatus(backend: ClusterBackendManager) {
        if (notificationObserverJob?.isActive == true) return
        notificationObserverJob = serviceScope.launch {
            backend.state
                .map { s ->
                    NotificationStateSummary(
                        endpoint = s.localNode?.endpoint ?: "0.0.0.0:${s.serverPort}",
                        roleLabel = s.config.role.displayName,
                        peersOnline = s.onlinePeersCount,
                        runningPods = s.pods.count { it.phase == PodPhase.RUNNING }
                    )
                }
                .distinctUntilChanged()
                .collect { summary ->
                    val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getSystemService(NotificationManager::class.java)
                    } else {
                        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    }
                    manager?.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            endpoint = summary.endpoint,
                            role = summary.roleLabel,
                            peersOnline = summary.peersOnline,
                            runningPods = summary.runningPods
                        )
                    )
                }
        }
    }

    private data class NotificationStateSummary(
        val endpoint: String,
        val roleLabel: String,
        val peersOnline: Int,
        val runningPods: Int
    )

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KubeMesh::NodeDaemonWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wm != null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "KubeMesh::NodeDaemonWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        }
    }

    override fun onDestroy() {
        notificationObserverJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.daemon_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.daemon_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(
        endpoint: String,
        role: String,
        peersOnline: Int = 0,
        runningPods: Int = 0
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NodeDaemonService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusLine = buildString {
            append("Endpoint $endpoint · ${1 + peersOnline} node(s)")
            if (runningPods > 0) {
                append(" · $runningPods pod(s) active")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${getString(R.string.daemon_notification_title)} · $role")
            .setContentText(statusLine)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop Agent", stopIntent)
            .build()
    }
}
