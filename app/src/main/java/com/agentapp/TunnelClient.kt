package com.agentapp

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TunnelClient(
    private val serverUrl: String,
    private val agentToken: String,
    private val onStatus: (status: String, port: Int, connected: Boolean) -> Unit
) {
    private val TAG = "TunnelClient"

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isActive = true
    private var assignedPort = 0

    // Активные каналы: conn_id → Channel
    private val channels = ConcurrentHashMap<String, TunnelChannel>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun start() {
        while (isActive) {
            connect()
            delay(5000) // Reconnect delay
        }
    }

    fun stop() {
        isActive = false
        webSocket?.close(1000, "Stopped")
        channels.values.forEach { it.close() }
        channels.clear()
        scope.cancel()
    }

    private suspend fun connect() {
        val wsUrl = buildWsUrl()
        Log.i(TAG, "Connecting to $wsUrl")
        onStatus("🔄 Подключение к серверу...", 0, false)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val connected = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened")
                // Регистрируемся
                ws.send(JSONObject().apply {
                    put("type", "register")
                    put("token", agentToken)
                }.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    handleMessage(JSONObject(text))
                    if (!connected.isCompleted) connected.complete(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Message parse error: $e")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onStatus("❌ Ошибка: ${t.message?.take(50)}", 0, false)
                if (!connected.isCompleted) connected.completeExceptionally(t)
                disconnected.complete(Unit)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $reason")
                if (isActive) onStatus("⚠️ Соединение потеряно", 0, false)
                disconnected.complete(Unit)
            }
        })

        // Ждём отключения
        disconnected.await()
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.getString("type")) {

            "registered" -> {
                assignedPort = msg.getInt("port")
                Log.i(TAG, "Registered! Port: $assignedPort")
                onStatus("✅ Активен (порт $assignedPort)", assignedPort, true)
            }

            "new_conn" -> {
                val connId = msg.getString("conn_id")
                val host = msg.getString("host")
                val port = msg.getInt("port")
                Log.d(TAG, "New connection: $connId → $host:$port")
                openChannel(connId, host, port)
            }

            "data" -> {
                val connId = msg.getString("conn_id")
                val payload = Base64.getDecoder().decode(msg.getString("payload"))
                channels[connId]?.writeToTarget(payload)
            }

            "conn_close" -> {
                val connId = msg.getString("conn_id")
                channels.remove(connId)?.close()
            }

            "pong" -> {}

            else -> Log.d(TAG, "Unknown message: ${msg.getString("type")}")
        }
    }

    private fun openChannel(connId: String, host: String, port: Int) {
        scope.launch {
            try {
                val socket = withContext(Dispatchers.IO) {
                    Socket(host, port)
                }

                val channel = TunnelChannel(
                    connId = connId,
                    socket = socket,
                    onData = { data ->
                        // Данные от Telegram → на сервер
                        val encoded = Base64.getEncoder().encodeToString(data)
                        webSocket?.send(JSONObject().apply {
                            put("type", "data")
                            put("conn_id", connId)
                            put("payload", encoded)
                        }.toString())
                    },
                    onClose = {
                        channels.remove(connId)
                        webSocket?.send(JSONObject().apply {
                            put("type", "conn_close")
                            put("conn_id", connId)
                        }.toString())
                    }
                )

                channels[connId] = channel

                // Говорим серверу что готовы
                webSocket?.send(JSONObject().apply {
                    put("type", "conn_ready")
                    put("conn_id", connId)
                }.toString())

                // Начинаем читать ответы от Telegram
                channel.startReading()

            } catch (e: Exception) {
                Log.e(TAG, "Channel open error: $e")
                webSocket?.send(JSONObject().apply {
                    put("type", "conn_close")
                    put("conn_id", connId)
                }.toString())
            }
        }
    }

    private fun buildWsUrl(): String {
        // Приводим URL к правильному формату
        val url = serverUrl
            .trim()
            .trimEnd('/')

        return when {
            url.startsWith("ws://") || url.startsWith("wss://") -> "$url/tunnel/connect"
            url.startsWith("http://") -> url.replace("http://", "ws://") + "/tunnel/connect"
            url.startsWith("https://") -> url.replace("https://", "wss://") + "/tunnel/connect"
            else -> "ws://$url/tunnel/connect"
        }
    }
}
