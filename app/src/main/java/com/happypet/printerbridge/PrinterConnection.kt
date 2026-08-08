package com.happypet.printerbridge

interface PrinterConnection {
    val isConnected: Boolean
    fun connect()
    fun disconnect()
    fun print(data: ByteArray)
}
