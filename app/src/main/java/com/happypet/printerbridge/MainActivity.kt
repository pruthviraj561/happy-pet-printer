package com.happypet.printerbridge

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_BLUETOOTH = 1001
        private const val ACTION_USB_PERMISSION = "com.happypet.printerbridge.USB_PERMISSION"
    }

    private enum class ConnectionType { BLUETOOTH, USB, WIFI }

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var selected: TextView
    private lateinit var connect: Button
    private lateinit var disconnect: Button
    private lateinit var test: Button
    private lateinit var bluetoothTab: Button
    private lateinit var usbTab: Button
    private lateinit var wifiTab: Button
    private lateinit var bluetoothFind: Button
    private lateinit var usbFind: Button
    private lateinit var wifiFields: LinearLayout
    private lateinit var ip: EditText
    private lateinit var port: EditText

    private var type = ConnectionType.BLUETOOTH
    private var bluetoothDevice: BluetoothDevice? = null
    private var usbDevice: UsbDevice? = null
    private var printerConnection: PrinterConnection? = null
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device == null) {
                setStatus("USB permission response did not include a device.")
                return
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                usbDevice = device
                connectUsb()
            } else {
                setStatus("USB permission was denied.")
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        registerUsbReceiver()
        buildUi()
        requestBluetoothPermission()
    }

    override fun onDestroy() {
        printerConnection?.disconnect()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "Happy Pet Printer"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Bluetooth, USB and Wi-Fi thermal printer test"
            textSize = 16f
            setPadding(0, dp(6), 0, dp(16))
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, dp(12)) }
        root.addView(status)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bluetoothTab = Button(this).apply { text = "Bluetooth"; setOnClickListener { selectType(ConnectionType.BLUETOOTH) } }
        usbTab = Button(this).apply { text = "USB"; setOnClickListener { selectType(ConnectionType.USB) } }
        wifiTab = Button(this).apply { text = "Wi-Fi"; setOnClickListener { selectType(ConnectionType.WIFI) } }
        listOf(bluetoothTab, usbTab, wifiTab).forEach { tabs.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(tabs)

        bluetoothFind = Button(this).apply {
            text = "Find Paired Bluetooth Printers"
            setOnClickListener { findBluetooth() }
        }
        root.addView(bluetoothFind)

        usbFind = Button(this).apply {
            text = "Detect USB Printers"
            setOnClickListener { findUsb() }
        }
        root.addView(usbFind)

        wifiFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ip = EditText(this).apply {
            hint = "Printer IP address"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        port = EditText(this).apply {
            hint = "Port"
            setText("9100")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        wifiFields.addView(ip)
        wifiFields.addView(port)
        root.addView(wifiFields)

        details = TextView(this).apply { textSize = 15f; setPadding(0, dp(10), 0, dp(6)) }
        selected = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        root.addView(details)
        root.addView(selected)

        connect = Button(this).apply { text = "Connect Printer"; setOnClickListener { connectSelected() } }
        disconnect = Button(this).apply { text = "Disconnect"; setOnClickListener { disconnectPrinter() } }
        test = Button(this).apply { text = "TEST PRINT"; setOnClickListener { testPrint() } }
        root.addView(connect)
        root.addView(disconnect)
        root.addView(test)

        setContentView(scroll)
        selectType(ConnectionType.BLUETOOTH)
    }

    private fun selectType(newType: ConnectionType) {
        printerConnection?.disconnect()
        printerConnection = null
        type = newType
        bluetoothTab.isEnabled = newType != ConnectionType.BLUETOOTH
        usbTab.isEnabled = newType != ConnectionType.USB
        wifiTab.isEnabled = newType != ConnectionType.WIFI
        bluetoothFind.visibility = if (newType == ConnectionType.BLUETOOTH) View.VISIBLE else View.GONE
        usbFind.visibility = if (newType == ConnectionType.USB) View.VISIBLE else View.GONE
        wifiFields.visibility = if (newType == ConnectionType.WIFI) View.VISIBLE else View.GONE
        connect.isEnabled = newType == ConnectionType.WIFI && ip.text.toString().trim().isNotEmpty()
        disconnect.isEnabled = false
        test.isEnabled = false
        selected.text = "Selected printer: None"
        when (newType) {
            ConnectionType.BLUETOOTH -> { setStatus("Bluetooth selected."); details.text = "Find a paired Bluetooth printer." }
            ConnectionType.USB -> { setStatus("USB selected."); details.text = "Connect the printer through USB OTG."; findUsb() }
            ConnectionType.WIFI -> { setStatus("Wi-Fi selected."); details.text = "Enter the printer IP address. Port 9100 is the default." }
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BLUETOOTH)
    }

    private fun bluetoothAllowed() = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun findBluetooth() {
        if (!bluetoothAllowed()) { requestBluetoothPermission(); return }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { setStatus("Bluetooth is not supported on this device."); return }
        if (!adapter.isEnabled) { setStatus("Bluetooth is turned off."); return }
        val devices = try { adapter.bondedDevices.toList() } catch (_: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            bluetoothDevice = null
            connect.isEnabled = false
            details.text = "No paired Bluetooth devices found. Pair the printer in Android settings first."
            return
        }
        bluetoothDevice = devices.first()
        details.text = devices.mapIndexed { i, d ->
            val name = try { d.name ?: "Unknown device" } catch (_: SecurityException) { "Unknown device" }
            "${i + 1}. $name\n${d.address}"
        }.joinToString("\n\n")
        val name = try { bluetoothDevice?.name ?: "Bluetooth Printer" } catch (_: SecurityException) { "Bluetooth Printer" }
        selected.text = "Selected printer: $name"
        connect.isEnabled = true
        setStatus("Bluetooth printer selected.")
    }

    private fun findUsb() {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            usbDevice = null
            connect.isEnabled = false
            details.text = "No USB device detected. Connect the printer through OTG and try again."
            setStatus("No USB device found.")
            return
        }
        val candidate = devices.firstOrNull { UsbPrinterConnection.findPrinterInterface(it) != null }
        if (candidate == null) {
            usbDevice = null
            connect.isEnabled = false
            details.text = "USB devices were found, but no compatible bulk OUT printer interface was detected."
            setStatus("No compatible USB printer found.")
            return
        }
        usbDevice = candidate
        val name = candidate.productName ?: candidate.deviceName ?: "USB Printer"
        details.text = "USB printer\nVID: ${candidate.vendorId}\nPID: ${candidate.productId}"
        selected.text = "Selected printer: $name"
        connect.isEnabled = true
        setStatus(if (usbManager.hasPermission(candidate)) "USB permission already granted." else "USB printer detected. Tap Connect Printer for permission.")
    }

    private fun connectSelected() {
        when (type) {
            ConnectionType.BLUETOOTH -> connectBluetooth()
            ConnectionType.USB -> connectUsb()
            ConnectionType.WIFI -> connectWifi()
        }
    }

    private fun connectBluetooth() {
        val device = bluetoothDevice ?: return setStatus("Find a Bluetooth printer first.")
        if (!bluetoothAllowed()) { requestBluetoothPermission(); return }
        val name = try { device.name ?: "Bluetooth Printer" } catch (_: SecurityException) { "Bluetooth Printer" }
        setStatus("Connecting to $name...")
        printerConnection = BluetoothPrinterConnection(device, { connected("Bluetooth", name) }, { disconnected() }, { error("Bluetooth", it) })
        printerConnection?.connect()
    }

    private fun connectUsb() {
        val device = usbDevice ?: return setStatus("Detect a USB printer first.")
        val endpoint = UsbPrinterConnection.findPrinterInterface(device) ?: return setStatus("No compatible USB printer interface found.")
        if (!usbManager.hasPermission(device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val intent = PendingIntent.getBroadcast(this, device.deviceId, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            setStatus("Requesting USB printer permission...")
            usbManager.requestPermission(device, intent)
            return
        }
        val usbConnection = usbManager.openDevice(device) ?: return setStatus("Android could not open the USB printer.")
        val name = device.productName ?: device.deviceName ?: "USB Printer"
        printerConnection = UsbPrinterConnection(usbConnection, endpoint.first, endpoint.second, { connected("USB", name) }, { disconnected() }, { error("USB", it) })
        setStatus("Connecting to $name...")
        printerConnection?.connect()
    }

    private fun connectWifi() {
        val host = ip.text.toString().trim()
        if (host.isEmpty()) return setStatus("Enter the Wi-Fi printer IP address.")
        val printerPort = port.text.toString().trim().toIntOrNull() ?: 9100
        if (printerPort !in 1..65535) return setStatus("Enter a valid port between 1 and 65535.")
        printerConnection = WifiPrinterConnection(host, printerPort, { connected("Wi-Fi", "$host:$printerPort") }, { disconnected() }, { error("Wi-Fi", it) })
        setStatus("Connecting to $host:$printerPort...")
        printerConnection?.connect()
    }

    private fun connected(kind: String, name: String) = runOnUiThread {
        setStatus("Connected to $name")
        selected.text = "Selected printer: $name ($kind)"
        connect.isEnabled = false
        disconnect.isEnabled = true
        test.isEnabled = true
    }

    private fun disconnected() = runOnUiThread {
        setStatus("Disconnected")
        connect.isEnabled = true
        disconnect.isEnabled = false
        test.isEnabled = false
    }

    private fun error(kind: String, message: String) = runOnUiThread {
        setStatus("$kind error: $message")
        connect.isEnabled = true
        disconnect.isEnabled = false
        test.isEnabled = false
    }

    private fun disconnectPrinter() {
        printerConnection?.disconnect()
        printerConnection = null
    }

    private fun testPrint() {
        val connection = printerConnection ?: return setStatus("Printer is not connected.")
        if (!connection.isConnected) return setStatus("Printer is not connected.")
        val name = selected.text.toString().removePrefix("Selected printer: ").substringBefore(" (").ifBlank { "Printer" }
        connection.print(EscPosTestReceipt.create(name, type.name.replace('_', ' ')))
        setStatus("Test print command sent.")
        Toast.makeText(this, "Test print sent", Toast.LENGTH_SHORT).show()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbReceiver, filter)
    }

    private fun setStatus(message: String) {
        if (::status.isInitialized) status.text = "Status: $message"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
