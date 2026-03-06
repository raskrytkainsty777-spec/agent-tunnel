package com.agentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agentapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Получаем статус из Service
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: return
            val port = intent.getIntExtra("port", 0)
            val isConnected = intent.getBooleanExtra("connected", false)

            binding.tvStatus.text = status
            binding.tvPort.text = if (port > 0) "SOCKS5 порт: $port" else ""

            if (isConnected) {
                binding.btnConnect.text = "Отключиться"
                binding.btnConnect.setBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
                )
                binding.cardStatus.setCardBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, R.color.status_connected)
                )
            } else {
                binding.btnConnect.text = "Подключиться"
                binding.btnConnect.setBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, R.color.primary)
                )
                binding.cardStatus.setCardBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, R.color.status_disconnected)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedSettings()
        setupButtons()

        // Запрос разрешения на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TunnelService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // Запросить текущий статус
        sendBroadcast(Intent(TunnelService.ACTION_GET_STATUS))
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        binding.etServerUrl.setText(prefs.getString("server_url", ""))
        binding.etToken.setText(prefs.getString("agent_token", ""))
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit()
            .putString("server_url", binding.etServerUrl.text.toString().trim())
            .putString("agent_token", binding.etToken.text.toString().trim())
            .apply()
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            val token = binding.etToken.text.toString().trim()

            if (TunnelService.isRunning) {
                // Отключаемся
                stopService(Intent(this, TunnelService::class.java))
                return@setOnClickListener
            }

            // Валидация
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (token.isEmpty()) {
                Toast.makeText(this, "Введите токен агента", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveSettings()

            // Запускаем сервис
            val intent = Intent(this, TunnelService::class.java).apply {
                putExtra("server_url", serverUrl)
                putExtra("agent_token", token)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
