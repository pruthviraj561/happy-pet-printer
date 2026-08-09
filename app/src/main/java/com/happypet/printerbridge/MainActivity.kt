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

    private lateinit var statusText: TextView
    private lateinit var printerText: TextView
    private lateinit var selectedText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var testButton: Button
    private lateinit var bluetoothButton: Button
    private lateinit var usbButton: Button
    private lateinit var wifiButton: Button
    private lateinit var findBluetoothButton: Button
    private lateinit var findUsbButton: Button
    private lateinit var wifiFields: LinearLayout
    private lateinit var ipEditText: EditText
    private lateinit var portEditText: EditText

    private var connectionType = ConnectionType.BLUETOOTH
    private var selectedDevice: BluetoothDevice? = null
    private var selectedUsbDevice: UsbDevice? = null
    private var connection: PrinterConnection? = null

    private val usbManager: UsbManager by lazy {
        getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            if (device == null) {
                setStatus("USB permission response did not include a printer.")
                return
            }

            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                selectedUsbDevice = device
                connectSelectedUsbDevice()
            } else {
                setStatus("USB permission was denied.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerUsbPermissionReceiver()
        buildUi()
        requestBluetoothPermissionsIfNeeded()
    }

    override fun onDestroy() {
        connection?.disconnect()
        connection = null
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val scroll = ScrollView(this).apply { addView(root) }

        val title = TextView(this).apply {
            text = "Happy Pet Printer"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Connect a Bluetooth, USB, or Wi-Fi thermal printer and send a test receipt."
            textSize = 16f
            setPadding(0, dp(6), 0, dp(18))
        }

        statusText = TextView(this).apply {
            text = "Status: Ready"
            textSize = 16f
            setPadding(0, 0, 0, dp(14))
        }

        val typeLabel = TextView(this).apply {
            text = "Connection Type"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }

        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        bluetoothButton = Button(this).apply {
            text = "Bluetooth"
            setOnClickListener { selectConnectionType(ConnectionType.BLUETOOTH) }
        }
        usbButton = Button(this).apply {
            text = "USB"
            setOnClickListener { selectConnectionType(ConnectionType.USB) }
        }
        wifiButton = Button(this).apply {
            text = "Wi-Fi"
            setOnClickListener { selectConnectionType(ConnectionType.WIFI) }
        }

        listOf(bluetoothButton, usbButton, wifiButton).forEach {
            typeRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val happyPetButton = Button(this).apply {
            text = "Connect to Happy Pet"
            setOnClickListener {
                showComingSoon(
                    "Happy Pet connection is the next integration layer. " +
                        "The standalone printer test is ready first."
                )
            }
        }

        findBluetoothButton = Button(this).apply {
            text = "Find Paired Bluetooth Printers"
            setOnClickListener { findPairedBluetoothPrinters() }
        }

        findUsbButton = Button(this).apply {
            text = "Detect USB Printers"
            setOnClickListener { detectUsbPrinters() }
        }

        wifiFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        ipEditText = EditText(this).apply {
            hint = "Printer IP address"
            singleLine = true
            inputType = InputType.TYPE_CLASS_PHONE
        }

        portEditText = EditText(this).apply {
            hint = "Port (default 9100)"
            setText("9100")
            singleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        wifiFields.addView(ipEditText)
        wifiFields.addView(portEditText)

        printerText = TextView(this).apply {
            text = "No printer detected."
            textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }

        selectedText = TextView(this).apply {
            text = "Selected printer: None"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(10))
        }

        connectButton = Button(this).apply {
            text = "Connect Printer"
            isEnabled = false
            setOnClickListener { connectSelectedPrinter() }
        }

        disconnectButton = Button(this).apply {
            text = "Disconnect"
            isEnabled = false
            setOnClickListener { disconnectPrinter() }
        }

        testButton = Button(this).apply {
            text = "TEST PRINT"
            isEnabled = false
            setOnClickListener { testPrint() }
        }

        val note = TextView(this).apply {
            text = "Bluetooth uses the existing classic Bluetooth SPP implementation. " +
                "USB sends raw ESC/POS data through the Android USB host interface. " +
                "Wi-Fi uses a TCP connection to the printer, port 9100 by default."
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        }

        listOf(
            title, subtitle, statusText, typeLabel, typeRow, happyPetButton,
            findBluetoothButton, findUsbButton, wifiFields, printerText,
            selectedText, connectButton, disconnectButton, testButton, note
        ).forEach { root.addView(it) }

        setContentView(scroll)
        selectConnectionType(ConnectionType.BLUETOOTH)
    }

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun selectConnectionType(type: ConnectionType) {
        connection?.disconnect()
        connection = null
        connectionType = type
        disconnectButton.isEnabled = false
        testButton.isEnabled = false
        connectButton.isEnabled = false

        val bluetooth = type == ConnectionType.BLUETOOTH
        val usb = type == ConnectionType.USB
        val wifi = type == ConnectionType.WIFI

        findBluetoothButton.visibility = if (bluetooth) View.VISIBLE else View.GONE
        findUsbButton.visibility = if (usb) View.VISIBLE else View.GONE
        wifiFields.visibility = if (wifi) View.VISIBLE else View.GONE

        bluetoothButton.isEnabled = !bluetooth
        usbButton.isEnabled = !usb
        wifiButton.isEnabled = !wifi

        printerText.text = when (type) {
            ConnectionType.BLUETOOTH -> "No paired printer found."
            ConnectionType.USB -> "No USB printer detected."
            ConnectionType.WIFI -> "Enter the printer IP address and connect."
        }
        selectedText.text = "Selected printer: None"

        when (type) {
            ConnectionType.BLUETOOTH -> {
                setStatus("Bluetooth selected.")
                if (!hasBluetoothPermission()) requestBluetoothPermissionsIfNeeded()
            }
            ConnectionType.USB -> {
                setStatus("USB selected. Connect the printer through OTG and detect it.")
                detectUsbPrinters()
            }
            ConnectionType.WIFI -> setStatus("Wi-Fi selected.")
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val required = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (required.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, required.toTypedArray(), REQUEST_BLUETOOTH)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun findPairedBluetoothPrinters() {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionsIfNeeded()
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            setStatus("This Android device does not support Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            setStatus("Bluetooth is turned off. Turn it on and try again.")
            return
        }

        val paired = try {
            adapter.bondedDevices.toList()
        } catch (_: SecurityException) {
            setStatus("Bluetooth permission is required.")
            return
        }

        if (paired.isEmpty()) {
            printerText.text = "No paired Bluetooth devices found.\n\nPair the printer from Android Bluetooth settings first."
            selectedDevice = null
            connectButton.isEnabled = false
            return
        }

        val likelyPrinters = paired.filter {
            val major = it.bluetoothClass?.majorDeviceClass
            major == android.bluetooth.BluetoothClass.Device.Major.IMAGING ||
                major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
        }
        val candidates = if (likelyPrinters.isNotEmpty()) likelyPrinters else paired

        printerText.text = candidates.mapIndexed { index, device ->
            "${index + 1}. ${device.name ?: "Unknown device"}\n${device.address}"
        }.joinToString("\n\n")

        selectedDevice = candidates.first()
        val name = try { selectedDevice?.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
        selectedText.text = "Selected printer: $name"
        connectButton.isEnabled = true
        setStatus("Bluetooth printer selected.")
    }

    private fun detectUsbPrinters() {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            selectedUsbDevice = null
            connectButton.isEnabled = false
            printerText.text = "No USB device detected. Connect the thermal printer through an OTG adapter and try again."
            setStatus("No USB device found.")
            return
        }

        val candidates = devices.mapNotNull { device ->
            val printerInterface = UsbPrinterConnection.findPrinterInterface(device)
            if (printerInterface != null) Triple(device, printerInterface.first, printerInterface.second) else null
        }

        if (candidates.isEmpty()) {
            selectedUsbDevice = null
            connectButton.isEnabled = false
            printerText.text = "USB devices were found, but no bulk OUT printer interface was detected."
            setStatus("No compatible USB printer interface found.")
            return
        }

        val selected = candidates.first()
        selectedUsbDevice = selected.first
        val device = selected.first
        val name = device.productName ?: device.deviceName ?: "USB Printer"
        printerText.text = candidates.mapIndexed { index, item ->
            val itemName = item.first.productName ?: item.first.deviceName ?: "USB Printer"
            "${index + 1}. $itemName\nVID: ${item.first.vendorId}  PID: ${item.first.productId}"
        }.joinToString("\n\n")
        selectedText.text = "Selected printer: $name"
        connectButton.isEnabled = true

        if (usbManager.hasPermission(device)) {
            setStatus("USB printer detected and permission already granted.")
        } else {
            setStatus("USB printer detected. Connect to request permission.")
        }
    }

    private fun connectSelectedUsbDevice() {
        val device = selectedUsbDevice ?: run {
            setStatus("Detect a USB printer first.")
            return
        }

        val printerInterface = UsbPrinterConnection.findPrinterInterface(device)
        if (printerInterface == null) {
            setStatus("The selected USB device has no compatible printer interface.")
            return
        }

        if (!usbManager.hasPermission(device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                flags
            )
            setStatus("Requesting USB printer permission...")
            usbManager.requestPermission(device, permissionIntent)
            return
        }

        val usbConnection = usbManager.openDevice(device)
        if (usbConnection == null) {
            setStatus("Android could not open the USB printer.")
            return
        }

        val name = device.productName ?: device.deviceName ?: "USB Printer"
        connection?.disconnect()
        setStatus("Connecting to $name...")

        connection = UsbPrinterConnection(
            connection = usbConnection,
            printerInterface = printerInterface.first,
            outEndpoint = printerInterface.second,
            onConnected = {
                runOnUiThread {
                    setStatus("Connected to $name")
                    connectButton.isEnabled = false
                    disconnectButton.isEnabled = true
                    testButton.isEnabled = true
                }
            },
            onDisconnected = {
                runOnUiThread {
                    setStatus("Disconnected")
                    connectButton.isEnabled = selectedUsbDevice != null
                    disconnectButton.isEnabled = false
                    testButton.isEnabled = false
                }
            },
            onError = { error ->
                runOnUiThread {
                    setStatus("USB error: $error")
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                    testButton.isEnabled = false
                }
            }
        )
        connection?.connect()
    }

    private fun connectSelectedPrinter() {
        when (connectionType) {
            ConnectionType.BLUETOOTH -> connectSelectedBluetoothPrinter()
            ConnectionType.USB -> connectSelectedUsbDevice()
            ConnectionType.WIFI -> connectWifiPrinter()
        }
    }

    private fun connectSelectedBluetoothPrinter() {
        val device = selectedDevice ?: run {
            setStatus("Find a Bluetooth printer first.")
            return
        }
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionsIfNeeded()
            return
        }

        connection?.disconnect()
        val name = try { device.name ?: "Bluetooth Printer" } catch (_: SecurityException) { "Bluetooth Printer" }
        setStatus("Connecting to $name...")

        connection = BluetoothPrinterConnection(
            device = device,
            onConnected = {
                runOnUiThread {
                    setStatus("Connected to $name")
                    connectButton.isEnabled = false
                    disconnectButton.isEnabled = true
                    testButton.isEnabled = true
                }
            },
            onDisconnected = {
                runOnUiThread {
                    setStatus("Disconnected")
                    connectButton.isEnabled = selectedDevice != null
                    disconnectButton.isEnabled = false
                    testButton.isEnabled = false
                }
            },
            onError = { error ->
                runOnUiThread {
                    setStatus("Bluetooth error: $error")
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                    testButton.isEnabled = false
                }
            }
        )
        connection?.connect()
    }

    private fun connectWifiPrinter() {
        val host = ipEditText.text.toString().trim()
        if (host.isBlank()) {
            setStatus("Enter the Wi-Fi printer IP address.")
            return
        }

        val port = portEditText.text.toString().trim().toIntOrNull() ?: 9100
        if (port !in 1..65535) {
            setStatus("Enter a valid port between 1 and 65535.")
            return
        }

        connection?.disconnect()
        setStatus("Connecting to $host:$port...")

        connection = WifiPrinterConnection(
            host = host,
            port = port,
            onConnected = {
                runOnUiThread {
                    setStatus("Connected to $host:$port")
                    selectedText.text = "Selected printer: $host:$port"
                    printerText.text = "Wi-Fi printer\nIP: $host\nPort: $port"
                    connectButton.isEnabled = false
                    disconnectButton.isEnabled = true
                    testButton.isEnabled = true
                }
            },
            onDisconnected = {
                runOnUiThread {
                    setStatus("Disconnected")
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                    testButton.isEnabled = false
                }
            },
            onError = { error ->
                runOnUiThread {
                    setStatus("Wi-Fi error: $error")
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                    testButton.isEnabled = false
                }
            }
        )
        connection?.connect()
    }

    private fun disconnectPrinter() {
        connection?.disconnect()
        connection = null
    }

    private fun testPrint() {
        val active = connection
        if (active == null || !active.isConnected) {
            setStatus("Printer is not connected.")
            return
        }

        val printerName: String
        val connectionName: String

        when (connectionType) {
            ConnectionType.BLUETOOTH -> {
                printerName = try { selectedDevice?.name ?: "Bluetooth Printer" } catch (_: SecurityException) { "Bluetooth Printer" }
                connectionName = "Bluetooth"
            }
            ConnectionType.USB -> {
                printerName = selectedUsbDevice?.productName ?: selectedUsbDevice?.deviceName ?: "USB Printer"
                connectionName = "USB"
            }
            ConnectionType.WIFI -> {
                printerName = ipEditText.text.toString().trim()
                connectionName = "Wi-Fi"
            }
        }

        setStatus("Sending test print...")
        val bytes = EscPosTestReceipt.create(
            printerName = printerName,
            connection = connectionName
        )
        active.print(bytes)
        setStatus("Test print command sent. Check the printer.")
        Toast.makeText(this, "Test print sent", Toast.LENGTH_SHORT).show()
    }

    private fun showComingSoon(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setStatus(value: String) {
        statusText.text = "Status: $value"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
