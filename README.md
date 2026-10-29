# FastShare

An Android **file manager** with built-in **fast file transfer to an iPhone** (or any device with a browser).
The transfer feature runs a small, speed-tuned web server on the Android phone. The other phone just opens a
link in its browser, so there is nothing to install on the iPhone.

## Get the APK

Every push to `main` builds the app on GitHub and publishes `FastShare.apk` under **Releases > latest**. Open that
file on your Android phone to install it (allow "Install unknown apps" when asked).
Pushes to other branches publish a test build as a pre-release called **preview**.

To build locally: `./gradlew assembleRelease` (needs JDK 17 and the Android SDK).

## Files tab

* Browse internal storage and SD cards / USB drives, with quick access folders and storage usage.
* Browse by type: Images, Videos, Audio, Documents, Apps, Archives.
* Press and hold to select. Copy, move, delete, rename, share, view details, extract zip files, create folders.
* Search, sort, show hidden files, photo and video thumbnails.
* The first time, Android asks you to allow **All files access**. The app explains the steps on screen.

## Transfer tab (step by step)

1. **Connect the phones.** Same Wi-Fi on both, or choose **Hotspot** to make a private network with no router.
2. **Scan the code** with the iPhone **Camera** app and tap the banner. It opens in Safari.
3. **Move files.**
   * *Android to iPhone:* in the Files tab press and hold files, tap the send arrow. They appear on the iPhone page, where you tap **Download**.
     Folders are sent as a zip. You can also use **Share > FastShare** from any other app.
   * *iPhone to Android:* on the iPhone page tap **Photos & videos** or **Files app**. Files are saved in `Downloads/FastShare`.

The Transfer tab shows live status (server, Wi-Fi, whether the iPhone is connected) and progress bars with speed.

### If the iPhone can't open the link

* Use the Camera app and **Safari**. In Chrome or other browsers on iPhone the page can fail with
  `ERR_CONNECTION_TIMED_OUT` until you turn on **Settings > Chrome > Local Network**.
* The iPhone must be on Wi-Fi (not mobile data) and on the **same network** as the Android phone. Turn off VPNs.
* Guest Wi-Fi and routers with "AP isolation" stop phones seeing each other. Use **Hotspot** mode.
* The app has a **Test this phone's server** button to tell an app problem from a network problem.

## Speed notes

* Plain Wi-Fi TCP with 1 MB buffers, no compression and no encryption overhead, so speed is limited by the Wi-Fi link.
  5 GHz is far faster than 2.4 GHz.
* The link contains a random token; anyone on the same network without it is refused.
* The iPhone photo picker may convert HEIC photos or compress videos. Use **Files app** for untouched originals.

## Limits

* Android 11 or newer. The iPhone side is a web page, not an app.
* NFC tap-to-send is not possible between Android and iPhone (iOS does not allow it), so the QR code replaces it.
* Keep the app open (the Transfer tab keeps the screen on) while a transfer runs.

## Development

`./gradlew testDebugUnitTest` runs the tests: file operations, the HTTP server over real sockets, and the app's
screens driven on the JVM with Robolectric. Screenshots of every screen are written to `app/build/shots/`.
`tools/gen_icons.py` regenerates the vector icons.
