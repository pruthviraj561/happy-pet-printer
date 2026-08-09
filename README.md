# Happy Pet Printer Bridge V2

V2 keeps the proven Bluetooth SPP + ESC/POS path and adds USB Host and Wi-Fi raw TCP printing.

### V2 test order
1. Re-test Bluetooth.
2. Test USB using a device with USB host/OTG and a compatible printer.
3. Test Wi-Fi with a printer that accepts raw TCP printing on port 9100.

USB discovery automatically selects standard USB Printer Class (class 7) interfaces with a bulk OUT endpoint. Vendor-specific USB printers may need a model-specific driver.

Wi-Fi uses the printer's local IP address and TCP port 9100. The printer must support raw ESC/POS over that port.

The Happy Pet cloud connection is intentionally not implemented yet. It will use HTTPS/WSS separately from local printer transport.
