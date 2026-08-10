package com.happypet.printerbridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

class BluetoothPrinterConnection(
    private val device: BluetoothDevice,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) : PrinterConnection {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    override val isConnected: Boolean get() = socket?.isConnected == true

    override fun connect() {
        Thread {
            var lastError: Exception? = null
            try {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

                // Keep the normal SPP connection as the first attempt.
                var s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    s.connect()
                } catch (first: Exception) {
                    lastError = first
                    try { s.close() } catch (_: Exception) {}

                    // Some thermal printers advertise SPP but fail the secure
                    // RFCOMM connection. Retry using the insecure SPP channel.
                    s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                }

                socket = s
                onConnected()
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                val message = e.message ?: lastError?.message ?: "Bluetooth connection failed"
                onError(message)
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
                val s = socket ?: throw IOException("Bluetooth printer is not connected")
                s.outputStream.write(data)
                s.outputStream.flush()
            } catch (e: Exception) {
                onError(e.message ?: "Bluetooth print failed")
            }
        }.start()
    }
}
