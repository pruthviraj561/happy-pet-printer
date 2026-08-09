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
            try {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                onConnected()
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                onError(e.message ?: "Bluetooth connection failed")
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
