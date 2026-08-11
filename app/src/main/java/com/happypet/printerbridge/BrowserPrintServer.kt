package com.happypet.printerbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** HTTP bridge between Happy Pet Web and the existing printer connection layer. */
class BrowserPrintServer(private val context: Context) {
    companion object { const val DEFAULT_PORT = 18181 }

    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activePrinter: PrinterConnection? = null
    @Volatile private var activePrinterName: String? = null
    @Volatile private var activePrinterAddress: String? = null
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
            } catch (_: Exception) { running = false }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        disconnectPrinter()
    }

    fun isRunning() = running && serverSocket?.isClosed == false

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
                method == "GET" && (path == "/" || path == "/test") -> sendHtml(client)
                method == "GET" && path == "/api/v1/status" -> send(client, 200, status())
                method == "GET" && path == "/api/v1/printers" -> send(client, 200, pairedPrinters())
                method == "POST" && path == "/api/v1/printer/connect" -> connect(body, client)
                method == "POST" && path == "/api/v1/printer/disconnect" -> { disconnectPrinter(); send(client, 200, status()) }
                method == "POST" && path == "/api/v1/test-print" -> testPrint(client)
                method == "POST" && path == "/api/v1/print" -> print(body, client)
                else -> send(client, 404, error("ENDPOINT_NOT_FOUND"))
            }
        }
    }

    private fun connect(body: String, socket: Socket) {
        val address = jsonValue(body, "address")?.trim()
            ?: return send(socket, 400, error("BLUETOOTH_ADDRESS_REQUIRED"))
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return send(socket, 503, error("BLUETOOTH_NOT_SUPPORTED"))
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return send(socket, 403, error("BLUETOOTH_PERMISSION_REQUIRED"))
        }
        val device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { null }
            ?: return send(socket, 400, error("INVALID_BLUETOOTH_ADDRESS"))

        disconnectPrinter()
        val name = try { device.name ?: "Bluetooth Printer" } catch (_: SecurityException) { "Bluetooth Printer" }

        lateinit var connection: BluetoothPrinterConnection
        connection = BluetoothPrinterConnection(device, {
            activePrinterType = "Bluetooth"
        }, {
            if (activePrinter === connection) {
                activePrinter = null
                activePrinterName = null
                activePrinterAddress = null
                activePrinterType = null
            }
        }, { _ ->
            if (activePrinter === connection) activePrinterType = "Bluetooth"
        })

        activePrinter = connection
        activePrinterName = name
        activePrinterAddress = device.address
        activePrinterType = "Bluetooth"

        executor.execute {
            try { connection.connect() }
            catch (_: Exception) { if (activePrinter === connection) disconnectPrinter() }
        }
        send(socket, 202, "{\"ok\":true,\"success\":true,\"status\":\"CONNECTING\",\"printer\":${quote(name)},\"address\":${quote(device.address)}}")
    }

    private fun print(body: String, socket: Socket) {
        val printer = activePrinter
        if (printer == null || !printer.isConnected) return send(socket, 409, error("PRINTER_NOT_CONNECTED"))
        val data = jsonValue(body, "dataBase64") ?: return send(socket, 400, error("DATA_REQUIRED"))
        val bytes = try { Base64.decode(data, Base64.DEFAULT) } catch (_: Exception) { null }
            ?: return send(socket, 400, error("INVALID_BASE64"))
        try {
            printer.print(bytes)
            send(socket, 200, "{\"ok\":true,\"success\":true,\"bytes\":${bytes.size},\"printer\":${quote(activePrinterName)}}")
        } catch (e: Exception) {
            send(socket, 500, "{\"ok\":false,\"success\":false,\"error\":\"PRINT_FAILED\",\"message\":${quote(e.message)}}")
        }
    }

    private fun testPrint(socket: Socket) {
        val printer = activePrinter
        if (printer == null || !printer.isConnected) return send(socket, 409, error("PRINTER_NOT_CONNECTED"))
        try {
            printer.print(EscPosTestReceipt.create(activePrinterName ?: "Bluetooth Printer", "Browser Bridge"))
            send(socket, 200, "{\"ok\":true,\"success\":true,\"printer\":${quote(activePrinterName)}}")
        } catch (e: Exception) {
            send(socket, 500, "{\"ok\":false,\"success\":false,\"error\":\"PRINT_FAILED\",\"message\":${quote(e.message)}}")
        }
    }

    private fun disconnectPrinter() {
        try { activePrinter?.disconnect() } catch (_: Exception) {}
        activePrinter = null
        activePrinterName = null
        activePrinterAddress = null
        activePrinterType = null
    }

    private fun status(): String {
        val connected = activePrinter?.isConnected == true
        return "{\"ok\":true,\"serverRunning\":${isRunning()},\"bridgePort\":$DEFAULT_PORT,\"printer\":{\"connected\":$connected,\"name\":${quote(activePrinterName)},\"address\":${quote(activePrinterAddress)},\"type\":${quote(activePrinterType)}},\"urls\":[${localUrls().joinToString(",") { quote(it) }}]}"
    }

    private fun pairedPrinters(): String {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "{\"ok\":false,\"printers\":[],\"error\":\"BLUETOOTH_NOT_SUPPORTED\"}"
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return "{\"ok\":false,\"printers\":[],\"error\":\"BLUETOOTH_PERMISSION_REQUIRED\"}"
        val devices = try { adapter.bondedDevices.toList().sortedBy { (try { it.name } catch (_: Exception) { null }) ?: "" } } catch (_: Exception) { emptyList() }
        val json = devices.joinToString(",") { d ->
            val name = try { d.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
            "{\"name\":${quote(name)},\"address\":${quote(d.address)}}"
        }
        return "{\"ok\":true,\"printers\":[$json]}"
    }

    private fun localUrls(): List<String> {
        val result = mutableListOf("http://127.0.0.1:$DEFAULT_PORT")
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
                if (!network.isUp || network.isLoopback) return@forEach
                network.inetAddresses.toList().filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.forEach { result.add("http://${it.hostAddress}:$DEFAULT_PORT") }
            }
        } catch (_: Exception) {}
        return result.distinct()
    }

    private fun sendHtml(socket: Socket) {
        val html = """
<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>Happy Pet Printer Bridge</title>
<style>body{font-family:Arial,sans-serif;max-width:720px;margin:30px auto;padding:0 18px}button{padding:12px 16px;margin:5px 0;width:100%}pre{background:#f4f4f4;padding:12px;white-space:pre-wrap}</style></head>
<body><h2>Happy Pet Printer Bridge</h2><p id='status'>Checking bridge...</p><button onclick='loadPrinters()'>Find Paired Printers</button><div id='printers'></div><button onclick='testPrint()'>Send ESC/POS Test Print</button><pre id='result'></pre>
<script>
const api=location.origin;
async function apiCall(path,options={}){const r=await fetch(api+path,options);const j=await r.json();document.getElementById('result').textContent=JSON.stringify(j,null,2);return j}
async function status(){const j=await apiCall('/api/v1/status');document.getElementById('status').textContent='Bridge: '+(j.serverRunning?'RUNNING':'STOPPED')+' | Printer: '+(j.printer.connected?'CONNECTED':'NOT CONNECTED')+' | '+(j.printer.name||'None')}
async function loadPrinters(){const j=await apiCall('/api/v1/printers');const box=document.getElementById('printers');box.innerHTML='';(j.printers||[]).forEach(p=>{const b=document.createElement('button');b.textContent='Connect: '+p.name+' ('+p.address+')';b.onclick=()=>connect(p.address);box.appendChild(b)})}
async function connect(address){await apiCall('/api/v1/printer/connect',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({address})});setTimeout(status,1200)}
async function testPrint(){await apiCall('/api/v1/test-print',{method:'POST'});setTimeout(status,500)}
status();
</script></body></html>
""".trimIndent()
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream(); out.write(response.toByteArray(StandardCharsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun jsonValue(body: String, key: String): String? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
    private fun quote(value: String?): String = value?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" } ?: "null"
    private fun error(code: String) = "{\"ok\":false,\"success\":false,\"error\":\"$code\"}"

    private fun send(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Error" }
        val response = "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream(); out.write(response.toByteArray(StandardCharsets.UTF_8)); out.write(bytes); out.flush()
    }
}
