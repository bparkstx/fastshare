# FastShare

Fast local file transfer between an Android phone and an iPhone (or any other device with a browser).
The Android app runs a tiny, speed-tuned web server. The other phone just opens a link in its browser,
so there is nothing to install on the iPhone.

## Get the APK

Push this repo to GitHub. The **Build APK** workflow runs automatically (or run it from the Actions tab),
and publishes `FastShare.apk` under **Releases > latest**. Open that file on your Android phone to install it
(allow "Install unknown apps" when asked).

To build locally instead: open the folder in Android Studio and choose Build > Build APK, or run
`./gradlew assembleRelease` (needs JDK 17 and the Android SDK).

## Use it

1. Open FastShare on the Android phone. It shows a QR code and a link.
2. On the other phone, scan the QR code with the camera app (or type the link into the browser).
   Both phones need to be on the same Wi-Fi.
3. **iPhone to Android:** tap "Choose files" in the browser page. Files land in Downloads/FastShare.
   **Android to iPhone:** tap "Choose files to share" in the app, then tap the file in the browser page.

No shared Wi-Fi? Tap **Start hotspot** in the app. Scan the first code to join it, then the second code to open the page.
Another option that is often faster: turn on the iPhone's Personal Hotspot (leave "Maximize Compatibility" off so it uses 5 GHz),
join it from the Android phone, and use the link the app shows.

## Speed notes

* Transfers go over plain Wi-Fi TCP with 1 MB buffers, no compression and no encryption overhead, so speed is limited by the
  Wi-Fi link itself. 5 GHz (Wi-Fi 5/6) is far faster than 2.4 GHz.
* Keep both screens on and the app in the foreground during a transfer.
* The link contains a random token; anyone on the same network without it is refused.
* The iPhone photo picker in Safari may convert HEIC photos or compress videos. Pick from Files for untouched originals.

## Limits

* Android 11 or newer. The iPhone side is a web page, not an app.
* NFC tap-to-send is not possible between Android and iPhone (iOS does not allow it), so the QR code replaces it.
