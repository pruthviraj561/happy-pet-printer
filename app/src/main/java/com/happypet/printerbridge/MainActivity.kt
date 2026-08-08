package com.happypet.printerbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
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
    }

    private lateinit var statusText: TextView
    private lateinit var printerText: TextView
    private lateinit var selectedText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var testButton: Button

    private var selectedDevice: BluetoothDevice? = null
    private var connection: BluetoothPrinterConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBluetoothPermissionsIfNeeded()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val scroll = ScrollView(this).apply {
            addView(root)
        }

        val title = TextView(this).apply {
            text = "Happy Pet Printer"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Connect a Bluetooth printer and send a test receipt."
            textSize = 16f
            setPadding(0, dp(6), 0, dp(18))
        }

        statusText = TextView(this).apply {
            text = "Status: Ready"
            textSize = 16f
            setPadding(0, 0, 0, dp(14))
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

        val findButton = Button(this).apply {
            text = "Find Paired Bluetooth Printers"
            setOnClickListener {
                findPairedBluetoothPrinters()
            }
        }

        printerText = TextView(this).apply {
            text = "No paired printer found."
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
            text =
                "First test target: Bluetooth thermal printer using classic Bluetooth SPP " +
                    "and ESC/POS. Once this works, USB, Wi-Fi and the Happy Pet web connection " +
                    "can be added without changing the printer workflow."
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        }

        listOf(
            title,
            subtitle,
            statusText,
            happyPetButton,
            findButton,
            printerText,
            selectedText,
            connectButton,
            disconnectButton,
            testButton,
            note
        ).forEach { root.addView(it) }

        setContentView(scroll)
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val required = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (required.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                required.toTypedArray(),
                REQUEST_BLUETOOTH
            )
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            )
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
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission is required.")
            return
        }

        if (paired.isEmpty()) {
            printerText.text =
                "No paired Bluetooth devices found.\n\n" +
                    "First pair the Seznik Veer printer from Android Bluetooth settings."
            selectedDevice = null
            connectButton.isEnabled = false
            return
        }

        // Prefer common imaging/peripheral device classes, but keep all paired
        // devices available because some thermal printers advertise unusual classes.
        val likelyPrinters = paired.filter {
            val major = it.bluetoothClass?.majorDeviceClass
            major == android.bluetooth.BluetoothClass.Device.Major.IMAGING ||
                major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
        }

        val candidates = if (likelyPrinters.isNotEmpty()) likelyPrinters else paired

        printerText.text = candidates.mapIndexed { index, device ->
            "${index + 1}. ${device.name ?: "Unknown device"}\n${device.address}"
        }.joinToString("\n\n")

        // V1 deliberately selects the first candidate. A later UI can allow
        // selecting any device from the list.
        selectedDevice = candidates.first()

        val name = try {
            selectedDevice?.name ?: "Unknown"
        } catch (_: SecurityException) {
            "Unknown"
        }

        selectedText.text = "Selected printer: $name"
        connectButton.isEnabled = true
        setStatus("Bluetooth printer selected.")
    }

    private fun connectSelectedPrinter() {
        val device = selectedDevice ?: run {
            setStatus("Select a printer first.")
            return
        }

        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionsIfNeeded()
            return
        }

        connection?.disconnect()

        val name = try {
            device.name ?: "Bluetooth Printer"
        } catch (_: SecurityException) {
            "Bluetooth Printer"
        }

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

        val name = try {
            selectedDevice?.name ?: "Bluetooth Printer"
        } catch (_: SecurityException) {
            "Bluetooth Printer"
        }

        setStatus("Sending test print...")

        val bytes = EscPosTestReceipt.create(
            printerName = name,
            connection = "Bluetooth"
        )

        active.print(bytes)

        // The current transport reports connection/write errors.
        // Physical paper output should be confirmed during hardware testing.
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
