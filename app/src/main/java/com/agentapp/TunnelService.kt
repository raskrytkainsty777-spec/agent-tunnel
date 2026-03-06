package com.agentapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class TunnelService : Service() {

    companion object {
        const val CHANNEL_ID = "tunnel_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATUS = "com.agentapp.STATUS"
        const val ACTION_GET_STATUS = "com.agentapp.GET_STATUS"

        var isRunning = false
        var currentPort = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelClient: TunnelClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra("server_url") ?: return START_NOT_STICKY
        val token = intent.getStringExtra("agent_token") ?: return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("🔄 Подключение..."))
        isRunning = true

        scope.launch {
            tunnelClient = TunnelClient(
                serverUrl = serverUrl,
                agentToken = token,
                onStatus = { status, port, connected ->
                    currentPort = port
                    updateNotification(status)
                    broadcastStatus(status, port, connected)
                }
            )
            tunnelClient?.start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        currentPort = 0
        scope.cancel()
        tunnelClient?.stop()
        broadcastStatus("⚪ Отключено", 0, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastStatus(status: String, port: Int, connected: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra("status", status)
            putExtra("port", port)
            putExtra("connected", connected)
        })
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent Tunnel")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tunnel Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Туннель агента активен"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
