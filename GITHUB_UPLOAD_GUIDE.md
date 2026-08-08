# Upload this project to GitHub

Do not upload only the four files from the root.

The repository must contain:

```text
.github/workflows/build-apk.yml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/happypet/printerbridge/MainActivity.kt
app/src/main/java/com/happypet/printerbridge/PrinterConnection.kt
app/src/main/java/com/happypet/printerbridge/BluetoothPrinterConnection.kt
app/src/main/java/com/happypet/printerbridge/EscPosTestReceipt.kt
app/src/main/res/values/styles.xml
build.gradle.kts
gradle.properties
settings.gradle.kts
README.md
```

If GitHub's web uploader does not preserve folders when uploading the whole project, create the folders in GitHub first and upload the files into their matching paths.

After all files are present:

1. Open **Actions**.
2. Select **Build Happy Pet Printer APK**.
3. Click **Run workflow**.
4. Wait for the build to finish.
5. Open the successful workflow run.
6. Under **Artifacts**, download `happy-pet-printer-debug-apk`.
7. Extract it and install `app-debug.apk` on the Android device.

The APK is a debug build for testing.
