package com.happypet.printerbridge

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors

/** Local/LAN HTTP bridge. It delegates actual printer I/O to PrinterConnection. */
class BrowserPrintServer(private val context: Context) {
    companion object {
        const val DEFAULT_PORT = 18181
    }

    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activePrinter: PrinterConnection? = null
    @Volatile private var activePrinterName: String? = null
    @Volatile private var activePrinterType: String? = null

    fun start(port: Int = DEFAULT_PORT) {
        if (running) return
        running = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                while (running) {
                    val socket = try { serverSocket?.accept() } catch (_: Exception) { null }
                    socket?.let { executor.execute { handle(it) } }
                }
            } catch (_: Exception) {
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun isRunning() = running && serverSocket?.isClosed == false

    fun setActivePrinter(connection: PrinterConnection?, name: String?, type: String?) {
        activePrinter = connection
        activePrinterName = name
        activePrinterType = type
    }

    fun clearActivePrinter(connection: PrinterConnection? = null) {
        if (connection == null || activePrinter === connection) {
            activePrinter = null
            activePrinterName = null
            activePrinterType = null
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine() ?: return
            val parts = request.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val p = line.indexOf(':')
                if (p > 0) headers[line.substring(0, p).trim().lowercase()] = line.substring(p + 1).trim()
            }
            if (method == "OPTIONS") return send(client, 200, "{\"ok\":true}")
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val chars = CharArray(length)
            if (length > 0) reader.read(chars)
            val body = String(chars)

            when {
                method == "GET" && path == "/api/v1/status" -> send(client, 200, status())
                method == "POST" && path == "/api/v1/print" -> print(body, client)
                method == "POST" && path == "/api/v1/disconnect" -> {
                    activePrinter?.disconnect(); clearActivePrinter(); send(client, 200, status())
                }
                else -> send(client, 404, "{\"ok\":false,\"error\":\"ENDPOINT_NOT_FOUND\"}")
            }
        }
    }

    private fun print(body: String, socket: Socket) {
        val data = jsonValue(body, "dataBase64")
            ?: return send(socket, 400, "{\"ok\":false,\"error\":\"DATA_REQUIRED\"}")
        val printer = activePrinter
        if (printer == null || !printer.isConnected) {
            return send(socket, 409, "{\"ok\":false,\"error\":\"PRINTER_NOT_CONNECTED\"}")
        }
        val bytes = try { Base64.getDecoder().decode(data) } catch (_: Exception) { null }
            ?: return send(socket, 400, "{\"ok\":false,\"error\":\"INVALID_BASE64\"}")
        try {
            printer.print(bytes)
            send(socket, 200, "{\"ok\":true,\"success\":true,\"bytes\":${bytes.size},\"printer\":${quote(activePrinterName)}}")
        } catch (e: Exception) {
            send(socket, 500, "{\"ok\":false,\"error\":\"PRINT_FAILED\",\"message\":${quote(e.message)}}")
        }
    }

    private fun status(): String {
        val connected = activePrinter?.isConnected == true
        val urls = localUrls().joinToString(",") { quote(it) }
        return "{\"ok\":true,\"serverRunning\":$running,\"urls\":[$urls],\"printer\":{\"connected\":$connected,\"name\":${quote(activePrinterName)},\"type\":${quote(activePrinterType)}}}"
    }

    private fun localUrls(): List<String> {
        val result = mutableListOf("http://127.0.0.1:$DEFAULT_PORT")
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { n ->
                if (!n.isUp || n.isLoopback) return@forEach
                n.inetAddresses.toList().filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.forEach {
                    result.add("http://${it.hostAddress}:$DEFAULT_PORT")
                }
            }
        } catch (_: Exception) {}
        return result.distinct()
    }

    private fun jsonValue(body: String, key: String): String? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)

    private fun quote(value: String?): String = value?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" } ?: "null"

    private fun send(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Internal Server Error"; else -> "Error" }
        val response = "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(response.toByteArray(StandardCharsets.UTF_8)); out.write(bytes); out.flush()
    }
}
