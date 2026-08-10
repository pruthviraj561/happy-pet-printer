package com.happypet.printerbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

class UsbPrinterConnection(
    private val connection: UsbDeviceConnection,
    private val printerInterface: UsbInterface,
    private val outEndpoint: UsbEndpoint,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) : PrinterConnection {
    private var active = false

    override val isConnected: Boolean
        get() = active

    override fun connect() = start()

    fun start() {
        try {
            if (!connection.claimInterface(printerInterface, true)) {
                throw IOException("Could not claim USB printer interface")
            }
            active = true
            onConnected()
        } catch (e: Exception) {
            active = false
            try { connection.close() } catch (_: Exception) {}
            onError(e.message ?: "USB connection failed")
        }
    }

    override fun disconnect() {
        try {
            if (active) connection.releaseInterface(printerInterface)
        } catch (_: Exception) {
        }
        active = false
        try { connection.close() } catch (_: Exception) {}
        onDisconnected()
    }

    override fun print(data: ByteArray) {
        Thread {
            try {
                if (!active) throw IOException("USB printer is not connected")

                var offset = 0
                while (offset < data.size) {
                    val length = minOf(4096, data.size - offset)
                    val sent = connection.bulkTransfer(
                        outEndpoint,
                        data,
                        offset,
                        length,
                        5000
                    )
                    if (sent <= 0) {
                        throw IOException("USB printer did not accept print data")
                    }
                    offset += sent
                }
            } catch (e: Exception) {
                onError(e.message ?: "USB print failed")
            }
        }.start()
    }

    companion object {
        fun findPrinterInterface(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
            // Prefer the standard USB printer class (class 7).
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue

                for (e in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(e)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        return Pair(intf, endpoint)
                    }
                }
            }

            // Some thermal printers expose a vendor-specific interface instead
            // of class 7. Fall back to the first bulk OUT endpoint so those
            // printers can still receive raw ESC/POS data.
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (e in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(e)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        return Pair(intf, endpoint)
                    }
                }
            }

            return null
        }
    }
}
