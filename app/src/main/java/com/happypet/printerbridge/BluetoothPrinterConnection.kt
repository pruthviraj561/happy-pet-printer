package com.happypet.printerbridge

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
        // Standard Serial Port Profile UUID commonly used by classic Bluetooth receipt printers.
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override fun connect() {
        Thread {
            try {
                val adapter = device.adapter
                adapter.cancelDiscovery()

                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                socket = newSocket

                onConnected()
            } catch (e: Exception) {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                onError(e.message ?: "Bluetooth connection failed")
            }
        }.start()
    }

    override fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        onDisconnected()
    }

    override fun print(data: ByteArray) {
        Thread {
            try {
                val current = socket
                    ?: throw IOException("Bluetooth printer is not connected")

                current.outputStream.use { output ->
                    output.write(data)
                    output.flush()
                }

                // A Bluetooth socket normally stays connected until explicitly closed.
                // Closing the output stream may vary by implementation, so this class
                // can be refined after testing the actual printer.
            } catch (e: Exception) {
                onError(e.message ?: "Bluetooth print failed")
            }
        }.start()
    }
}
