package com.example.cluster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class NodeDaemonService : Service() {

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT) ?: "LAN Ready"
        val role = intent?.getStringExtra(EXTRA_ROLE) ?: "Node Agent"
        val notification = buildNotification(endpoint, role)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
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

    private fun buildNotification(endpoint: String, role: String): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${getString(R.string.daemon_notification_title)} · $role")
            .setContentText("Endpoint $endpoint ready for mesh workloads")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop Agent", stopIntent)
            .build()
    }
}
