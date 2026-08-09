package com.happypet.printerbridge

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class WifiPrinterConnection(
    private val host: String,
    private val port: Int = 9100,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) : PrinterConnection {
    private var socket: Socket? = null
    override val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    override fun connect() {
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                onConnected()
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                onError(e.message ?: "Wi-Fi printer connection failed")
            }
        }.start()
    }

    override fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        onDisconnected()
    }

    override fun print(data: ByteArray) {
        Thread {
            try {
                val s = socket ?: throw IOException("Wi-Fi printer is not connected")
                val out = s.getOutputStream()
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                onError(e.message ?: "Wi-Fi print failed")
            }
        }.start()
    }
}
