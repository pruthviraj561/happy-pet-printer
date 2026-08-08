# Happy Pet Printer Bridge - App V1

This project is the **Android-side application only**.

The first milestone is intentionally narrow:

> Pair a Bluetooth thermal printer, connect to it, and send an ESC/POS test receipt.

The Happy Pet web/backend connection is represented by a single **Connect to Happy Pet** entry point in the UI, but production API/WebSocket integration is intentionally not enabled yet.

## Current V1 scope

- Android phone/tablet/POS.
- Bluetooth permission handling.
- Paired Bluetooth printer discovery.
- Classic Bluetooth SPP connection.
- ESC/POS test receipt.
- Connect/disconnect.
- Basic status display.
- Clean printer-connection abstraction for future USB/Wi-Fi adapters.

## First hardware test

1. Pair the Seznik Veer printer from Android Bluetooth settings.
2. Open Happy Pet Printer.
3. Allow Bluetooth permissions.
4. Tap **Find Paired Bluetooth Printers**.
5. Confirm the printer appears.
6. Tap **Connect Printer**.
7. Wait for `Connected`.
8. Tap **TEST PRINT**.
9. Confirm the receipt prints.

## Important protocol note

This V1 assumes the printer supports classic Bluetooth Serial Port Profile (SPP) and ESC/POS.

If the Seznik Veer model uses another Bluetooth protocol, the connection adapter must be changed to match that model. Do not assume protocol compatibility only from the brand name.

## Planned next layers

After Bluetooth hardware validation:

1. USB adapter.
2. Wi-Fi adapter.
3. Persistent printer configuration.
4. Print queue.
5. Retry/reconnect.
6. Duplicate protection.
7. Happy Pet device pairing.
8. Authenticated WebSocket.
9. Backend print-job API.
10. Happy Pet web integration.

## Production architecture

```text
Happy Pet Web
      |
      v
Happy Pet Backend
      |
      | Authenticated WebSocket
      v
Happy Pet Printer Bridge
      |
      +---- USB
      +---- Bluetooth
      +---- Wi-Fi
      |
      v
Physical Printer
```
