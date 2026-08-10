package com.happypet.printerbridge

import android.app.Application

class PrinterBridgeApplication : Application() {
    lateinit var browserPrintServer: BrowserPrintServer
        private set

    override fun onCreate() {
        super.onCreate()
        browserPrintServer = BrowserPrintServer(this)
        browserPrintServer.start(BrowserPrintServer.DEFAULT_PORT)
    }

    override fun onTerminate() {
        if (::browserPrintServer.isInitialized) browserPrintServer.stop()
        super.onTerminate()
    }
}
