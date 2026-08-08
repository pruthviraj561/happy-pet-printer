package com.happypet.printerbridge

import java.io.ByteArrayOutputStream

object EscPosTestReceipt {

    fun create(printerName: String, connection: String): ByteArray {
        val out = ByteArrayOutputStream()

        // Initialize
        out.write(byteArrayOf(0x1B, 0x40))

        // Center
        out.write(byteArrayOf(0x1B, 0x61, 0x01))
        out.write("HAPPY PET\n".toByteArray())
        out.write("PRINTER TEST\n".toByteArray())

        // Left
        out.write(byteArrayOf(0x1B, 0x61, 0x00))
        out.write("--------------------------------\n".toByteArray())
        out.write("Printer   : $printerName\n".toByteArray())
        out.write("Connection: $connection\n".toByteArray())
        out.write("--------------------------------\n".toByteArray())

        // Bold
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write("TEST PRINT SUCCESSFUL\n".toByteArray())
        out.write(byteArrayOf(0x1B, 0x45, 0x00))

        out.write("\nThank you!\n\n\n".toByteArray())

        // Feed
        out.write(byteArrayOf(0x1B, 0x64, 0x04))

        // Full cut command. Printers without a cutter normally ignore it.
        out.write(byteArrayOf(0x1D, 0x56, 0x00))

        return out.toByteArray()
    }
}
