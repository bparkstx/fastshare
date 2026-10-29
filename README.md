# FileBandit

<img src="app/src/main/res/drawable-nodpi/mascot.png" width="96" align="right" alt="FileBandit raccoon">

A full **file manager for Android** with **fast file transfer to an iPhone or a computer** built in. Nothing has to
be installed on the other device: it just opens a link in its browser.

## Get the APK

Every push to `main` builds the app on GitHub and publishes `FileBandit.apk` under **Releases > latest**. Open that
file on your Android phone to install it (allow "Install unknown apps" when asked).
Pushes to other branches publish a test build as a pre-release called **preview**.

The app id is still `com.fastshare.app`, so it installs **over** the old FastShare app and keeps its settings.

To build locally: `./gradlew assembleRelease` (needs JDK 17 and the Android SDK).

## What it does

### Files tab

* Browse internal storage and SD cards / USB drives, quick access folders, **recent files** and **favorites**.
* Browse by type: images, videos, audio, documents, apps, archives. List or **grid** view.
* Press and hold to select. Copy, move, rename, share, details. **Batch rename** (numbering, find and replace,
  case, date). Select all, invert, select by type.
* **Recycle bin and undo**: deleting moves things to the bin (30 days) and every action offers **Undo**.
* **Compress** to zip. **Extract** zip, 7z, rar, tar, tar.gz, tar.bz2, tar.xz, gz.
* **Built-in viewers**: picture gallery with zoom and swipe, video and audio player, PDF reader, text editor.
* Search (including subfolders), sort, show hidden files.

### Storage

**Storage** shows where the space went, and finds the largest files, duplicate files (matched by content, not name) and
leftovers (caches, thumbnails, old installers, partial downloads). Cleaning moves things to the recycle bin.

### Apps

Lists installed apps with their size and dates. **Back up** an app's APK to `FileBandit/Apps`, share it,
open its system settings, or uninstall it.

### Photo backup

**Photo backup** (Files home) copies new photos and videos to a computer or NAS you've added under *Network locations*.
Switch on *Back up automatically* and it runs about every six hours, on Wi-Fi only, when the battery isn't low, and only
when your server can be reached, so it simply waits until you're home. It sorts into year and month folders, never sends a
file twice (even after reinstalling), and resumes if it's interrupted. *Remove backed-up photos from this phone* moves what
the server really still has to the Trash (it checks the server first).

### Clean up photos

**Clean photos** finds near-copies of the same scene, blurry shots, black frames and screenshots older than three months,
then lets you swipe through them: left moves a photo to the Trash, right keeps it (and it isn't suggested again), with Undo.

### Scan documents

**Scan** turns a photo of a page into a straight, clean PDF: it finds the page, flattens the perspective, whitens the paper
and darkens the ink (Clean, Gray, B&W or Raw), lets you drag the corners if it guessed wrong, and joins any number of pages
into one PDF in `Documents/FileBandit Scans`.

### Search inside files

**Search inside** finds words in photos and PDFs (receipts, whiteboards, letters), in text files, and what a photo shows
("dog", "beach", "food"). It's read on the phone with Google's on-device models, which are part of the app, so nothing is
uploaded. The first visit reads everything once; after that only new files.

### Private vault

Files you move into the vault are encrypted (AES-256-GCM). The key is protected by your PIN (PBKDF2, 210,000
rounds) and by a key that lives in the phone's Keystore, so a copy of the vault folder is useless elsewhere.
You can add **fingerprint unlock**. The vault locks itself 30 seconds after you leave, hides itself from screenshots
and the app switcher, and wipes the temporary decrypted copy it makes to show a file.
A **decoy vault** can have its own PIN: typing it opens a separate vault that looks the same, for when someone makes you
open it. Wrong PIN tries are logged and shown to you the next time you unlock the real vault.
**There is no "forgot PIN".** If you forget it, the files cannot be recovered.

### Network locations

**Network** on the Files tab connects to servers and browses them like a folder:

| Type | For |
| --- | --- |
| **SFTP** (SSH) | Linux machines, Macs with Remote Login, many NAS boxes. Password or pasted private key. The server's key is remembered the first time and checked after that. |
| **SMB** | Windows shared folders, Macs with File Sharing, most NAS boxes (SMB 2/3). |
| **FTP** and **FTP over TLS** | Routers with a USB disk, older NAS boxes, web hosting. |
| **WebDAV** (http and https) | Nextcloud, ownCloud, many NAS boxes. |

Open files, **download** (to `Downloads/FileBandit/<name>`), **upload** (from the picker, or select files in the Files
tab and choose *Upload to network location*), rename, delete, new folder. Saved passwords are encrypted with the
Keystore. For your own server with a self-made certificate there is a *Trust this server's certificate* option.

Google Drive, Dropbox and OneDrive are **not** included: they need an app registration with their developer
programs (and for Google, a verification process) that only the app's publisher can do. WebDAV and SFTP cover
Nextcloud and most home servers.

### Transfer tab (iPhone and computers)

1. **Connect the phones.** Same Wi-Fi on both, or choose **Hotspot** to make a private network with no router.
2. **Scan the code** with the iPhone **Camera** app and tap the banner. It opens in Safari. On a computer, type or
   paste the link (use **Share link** or **Copy link**).
3. **Move files.**
   * *Android to the other device:* in the Files tab press and hold files, tap the send arrow, or use
     **Share > FileBandit** from any app. They appear on the page, where you tap **Download**, or **Download all** for
     one zip. Folders are sent as a zip.
   * *Other device to Android:* on the page tap **Photos & videos** or **Files app** (on a computer you can also drag
     files onto the page). Files are saved in `Downloads/FileBandit`.

Also on the Transfer tab:

* **Use this phone as a drive.** Switch it on and Windows Explorer, Mac Finder or a Linux file manager can open the phone's
  files over Wi-Fi (WebDAV), no cable and no browser. Read-only by default; allow changes to copy files in. Deleting from the
  computer goes to the phone's Trash. The address contains a long secret and stays the same after restarts.
* **Shared clipboard.** Send a link, address or code to the other device and back, from the page or the app (or share text
  to FileBandit from any app).

New in 3.0:

* **Resumable.** Uploads go up in chunks. If the Wi-Fi blips or the iPhone screen locks, the page asks the phone how
  much arrived and carries on from there. Downloads support HTTP range requests, so a browser can resume them.
* **PIN on the link.** Turn on *Ask for a PIN* and the page asks for a 4-digit PIN before showing anything. Five wrong
  tries lock that device out for 30 seconds, then longer.
* **History** of everything sent and received, kept across restarts.

### If the iPhone can't open the link

* Use the Camera app and **Safari**. In Chrome or other browsers on iPhone the page can fail with
  `ERR_CONNECTION_TIMED_OUT` until you turn on **Settings > Chrome > Local Network**.
* The iPhone must be on Wi-Fi (not mobile data) and on the **same network** as the Android phone. Turn off VPNs.
* Guest Wi-Fi and routers with "AP isolation" stop phones seeing each other. Use **Hotspot** mode.
* The app has a **Test this phone's server** button to tell an app problem from a network problem.

## Notes and limits

* Android 11 or newer. The other device only needs a browser.
* The first time, Android asks you to allow **All files access**. The app explains the steps on screen.
* Transfers are plain Wi-Fi TCP with no encryption overhead; the link contains a random token and can have a PIN.
  Anyone who can see your Wi-Fi traffic could read a transfer, so use a network you trust or the Hotspot.
* The iPhone photo picker may convert HEIC photos or compress videos. Use **Files app** for untouched originals.
* NFC tap-to-send is not possible between Android and iPhone (iOS does not allow it), so the QR code replaces it.
* FTP (without TLS) and WebDAV over http send passwords unencrypted. Use the secure types where you can.
* Keep the app open while a transfer runs.
* The photo search uses models bundled in the app (about 40 MB of the download). Its first pass over a big library can take
  a few minutes. Searching words in photos works best for printed text.
* The document scanner finds the page by contrast, so a white page on a dark surface works best. If it guesses wrong, drag
  the four corners.

## Development

`./gradlew testDebugUnitTest` runs about 350 tests:

* file operations, recycle bin, archives, batch rename, analyzer, vault encryption, history and connection storage
  as plain JUnit tests;
* the transfer server over real sockets (including resume, ranges, PIN and lockout);
* the network clients against **real servers on localhost**: Apache MINA FTP in-process, and `wsgidav` (WebDAV),
  impacket's `smbserver` (SMB) and `asyncssh` (SFTP, see `tools/sftp_test_server.py`) as separate processes. Those three
  are installed with `pip install wsgidav cheroot impacket asyncssh`; point `FB_PYENV_BIN` at the folder holding them.
  Where they are missing those tests are skipped. CI installs them, plus `litmus` (the WebDAV compliance suite, run against the
  phone's own WebDAV server) and `poppler-utils` (which opens the scanner's PDFs);
* every screen driven on the JVM with Robolectric, with screenshots written to `app/build/shots/`.

The web page the other device sees is `app/src/main/assets/index.html`.
`tools/gen_icons.py` regenerates the vector icons and `tools/make_icon.py` the launcher icon.

### Signing

CI signs the APK with the key stored in the `FASTSHARE_SIGNING_KEY` repository secret (`password:base64-keystore`),
so every build installs as an update over the last. Without it the build falls back to the debug key.
