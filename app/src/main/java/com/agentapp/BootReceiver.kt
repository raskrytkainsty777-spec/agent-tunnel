package com.agentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "") ?: ""
            val token = prefs.getString("agent_token", "") ?: ""

            if (serverUrl.isNotEmpty() && token.isNotEmpty()) {
                // Автозапуск после перезагрузки если были настройки
                val serviceIntent = Intent(context, TunnelService::class.java).apply {
                    putExtra("server_url", serverUrl)
                    putExtra("agent_token", token)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
