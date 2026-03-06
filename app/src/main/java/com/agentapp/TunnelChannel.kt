package com.agentapp

import android.util.Log
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TunnelChannel(
    private val connId: String,
    private val socket: Socket,
    private val onData: (ByteArray) -> Unit,
    private val onClose: () -> Unit
) {
    private val TAG = "TunnelChannel"
    private val closed = AtomicBoolean(false)

    private val outputStream = socket.getOutputStream()
    private val inputStream = socket.getInputStream()

    // Читаем данные от Telegram сервера и отправляем на наш сервер
    fun startReading() {
        Thread {
            val buffer = ByteArray(8192)
            try {
                while (!closed.get()) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    onData(buffer.copyOf(bytesRead))
                }
            } catch (e: IOException) {
                if (!closed.get()) {
                    Log.d(TAG, "Channel $connId read ended: ${e.message}")
                }
            } finally {
                close()
            }
        }.also { it.isDaemon = true }.start()
    }

    // Пишем данные от сервера → в Telegram
    fun writeToTarget(data: ByteArray) {
        if (closed.get()) return
        try {
            outputStream.write(data)
            outputStream.flush()
        } catch (e: IOException) {
            Log.d(TAG, "Channel $connId write error: ${e.message}")
            close()
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close()
            } catch (e: Exception) {}
            onClose()
        }
    }
}
