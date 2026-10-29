package com.fastshare.app;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything about the transfer feature that outlives a screen: the web server, which devices are
 * connected, the list of shared items, transfer progress, the hotspot, and this phone's addresses.
 * Screens listen for changes and read the current state.
 */
public final class ShareHub implements FileServer.Listener, FileServer.Backend {

    public interface HubListener {
        /** Something changed; re-read whatever you display. Always called on the main thread. */
        void onHubChanged();
    }

    /** A device that has recently talked to the server. */
    public static final class Client {
        public final String ip;
        public final String userAgent;
        volatile long lastSeen;

        Client(String ip, String ua) {
            this.ip = ip;
            this.userAgent = ua == null ? "" : ua;
        }

        /** "iPhone (Safari)", "iPhone (Chrome)", "Windows PC"... */
        public String label() {
            String ua = userAgent;
            String device;
            if (ua.contains("iPhone")) device = "iPhone";
            else if (ua.contains("iPad")) device = "iPad";
            else if (ua.contains("Android")) device = "Android phone";
            else if (ua.contains("Macintosh")) device = "Mac";
            else if (ua.contains("Windows")) device = "Windows PC";
            else device = "Device";
            String browser = "";
            if (ua.contains("CriOS") || ua.contains("Chrome/")) browser = "Chrome";
            else if (ua.contains("FxiOS") || ua.contains("Firefox/")) browser = "Firefox";
            else if (ua.contains("EdgiOS") || ua.contains("Edg/")) browser = "Edge";
            else if (ua.contains("Safari/")) browser = "Safari";
            return browser.isEmpty() ? device : device + " (" + browser + ")";
        }
    }

    public enum HotspotState { OFF, STARTING, ON }

    static final int PORT = 8080;
    private static final long ACTIVE_WINDOW_MS = 12_000;

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<HubListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean notifyPending = new AtomicBoolean();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // server
    private FileServer server;
    private String token = randomToken(8);
    private int port = PORT;
    private String error;

    // devices and transfers
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private volatile boolean everConnected;
    private volatile long lastClientNotify;
    private final List<FileServer.Transfer> transfers = new CopyOnWriteArrayList<>();
    private final History history;
    private final Prefs prefs;

    // network
    private Net.Info net = new Net.Info(false, new ArrayList<Net.Addr>());
    private int addrIndex;
    private ConnectivityManager.NetworkCallback netCallback;
    private WifiManager.WifiLock wifiLock;

    // hotspot
    private WifiManager.LocalOnlyHotspotReservation hotspot;
    private HotspotState hotspotState = HotspotState.OFF;
    private String hotspotSsid;
    private String hotspotPass;
    private String hotspotError;

    ShareHub(Context app) {
        this.app = app.getApplicationContext();
        this.history = new History(new File(this.app.getFilesDir(), "transfer_history.tsv"));
        this.prefs = new Prefs(this.app);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (server != null && server.isRunning()) return;
        token = randomToken(8);
        server = new FileServer(token, this, this);
        server.setPin(prefs.linkPin());
        try {
            port = server.start(PORT);
            error = null;
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        try {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "fastshare");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }
        watchNetwork();
        refreshNet();
    }

    public synchronized void stop() {
        stopHotspot();
        if (server != null) server.stop();
        server = null;
        try {
            if (netCallback != null) {
                ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(netCallback);
            }
        } catch (Exception ignored) { }
        netCallback = null;
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) { }
    }

    private void watchNetwork() {
        if (netCallback != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { postRefresh(); }
                @Override public void onLost(Network n) { postRefresh(); }
                @Override public void onLinkPropertiesChanged(Network n, android.net.LinkProperties lp) { postRefresh(); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), netCallback);
        } catch (Exception ignored) {
            netCallback = null;
        }
    }

    private void postRefresh() {
        ui.post(new Runnable() {
            @Override public void run() { refreshNet(); }
        });
    }

    public void addListener(HubListener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(HubListener l) {
        listeners.remove(l);
    }

    /** Coalesces bursts of changes into one main-thread callback. */
    private void changed() {
        if (!notifyPending.compareAndSet(false, true)) return;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                notifyPending.set(false);
                for (HubListener l : listeners) l.onHubChanged();
            }
        }, 120);
    }

    // ------------------------------------------------------------------ state for the screens

    public boolean isRunning() { return server != null && server.isRunning(); }
    public String error() { return error; }
    public int port() { return port; }
    public Net.Info net() { return net; }
    public boolean everConnected() { return everConnected; }
    public List<FileServer.Transfer> transfers() { return transfers; }
    public History history() { return history; }

    // ------------------------------------------------------------------ shared clipboard

    public FileServer.Note addNote(String text) {
        return server == null ? null : server.addNote(text, true);
    }

    public List<FileServer.Note> notes() {
        return server == null ? new ArrayList<FileServer.Note>() : server.getNotes();
    }

    public void clearNotes() {
        if (server != null) server.clearNotes();
    }


    /** The PIN devices must enter after opening the link, or empty when there isn't one. */
    public String linkPin() { return prefs.linkPin(); }

    public void setLinkPin(String pin) {
        prefs.setLinkPin(pin);
        if (server != null) server.setPin(pin);
        changed();
    }
    public List<FileServer.Shared> shared() { return server == null ? new ArrayList<FileServer.Shared>() : server.getShared(); }

    /** Devices that talked to the server in the last few seconds. */
    public List<Client> activeClients() {
        long now = System.currentTimeMillis();
        List<Client> out = new ArrayList<>();
        for (Client c : clients.values()) {
            if (now - c.lastSeen < ACTIVE_WINDOW_MS) out.add(c);
        }
        return out;
    }

    public String urlFor(Net.Addr a) {
        return "http://" + a.ip + ":" + port + "/?t=" + token;
    }

    /** The link to show as a QR code, or null when this phone has no usable address. */
    public String currentUrl() {
        List<Net.Addr> addrs = net.addrs;
        if (addrs.isEmpty()) return null;
        return urlFor(addrs.get(addrIndex % addrs.size()));
    }

    public Net.Addr currentAddr() {
        List<Net.Addr> addrs = net.addrs;
        return addrs.isEmpty() ? null : addrs.get(addrIndex % addrs.size());
    }

    public int addrIndex() { return addrs().isEmpty() ? 0 : addrIndex % addrs().size(); }
    public List<Net.Addr> addrs() { return net.addrs; }

    public void nextAddress() {
        addrIndex++;
        changed();
    }

    public void refreshNet() {
        Net.Info fresh = Net.snapshot(app, hotspotState == HotspotState.ON);
        String before = currentUrlKey();
        net = fresh;
        if (!before.equals(currentUrlKey())) addrIndex = 0;
        changed();
    }

    private String currentUrlKey() {
        StringBuilder sb = new StringBuilder();
        for (Net.Addr a : net.addrs) sb.append(a.ip).append(',');
        return sb.toString();
    }

    // ------------------------------------------------------------------ shared items

    public void addFiles(List<File> files) {
        if (server == null) return;
        for (File f : files) {
            boolean dup = false;
            for (FileServer.Shared s : server.getShared()) {
                if (f.equals(s.ref)) { dup = true; break; }
            }
            if (!dup) server.addShared(f, f.getName(), f.length(), f.isDirectory());
        }
    }

    public void addUris(List<Uri> uris) {
        if (server == null) return;
        ContentResolver cr = app.getContentResolver();
        for (Uri uri : uris) {
            String name = "file";
            long size = -1;
            Cursor c = null;
            try {
                c = cr.query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            server.addShared(uri, name, size, false);
        }
    }

    public void removeShared(String id) {
        if (server != null) server.removeShared(id);
    }

    public void clearShared() {
        if (server != null) server.clearShared();
    }

    public void clearFinishedTransfers() {
        for (FileServer.Transfer t : transfers) {
            if (t.state != FileServer.State.ACTIVE) transfers.remove(t);
        }
        changed();
    }

    // ------------------------------------------------------------------ FileServer.Listener (worker threads)

    @Override
    public void onClient(String ip, String userAgent) {
        long now = System.currentTimeMillis();
        Client c = clients.get(ip);
        boolean isNew = c == null;
        if (isNew) {
            c = new Client(ip, userAgent);
            clients.put(ip, c);
        }
        c.lastSeen = now;
        everConnected = true;
        if (isNew || now - lastClientNotify > 2000) {
            lastClientNotify = now;
            changed();
        }
    }

    @Override
    public void onSharedChanged() {
        changed();
    }

    @Override
    public void onNotesChanged() {
        changed();
    }

    @Override
    public void onTransfer(FileServer.Transfer t) {
        if (t.state != FileServer.State.ACTIVE) history.record(History.of(t));
        if (!transfers.contains(t)) {
            transfers.add(0, t);
            while (transfers.size() > 40) transfers.remove(transfers.size() - 1);
        }
        changed();
    }

    // ------------------------------------------------------------------ FileServer.Backend

    @Override
    public byte[] indexPage() throws IOException {
        InputStream in = app.getAssets().open("index.html");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    @Override
    public String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        if (!maker.isEmpty() && !model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) {
            return Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
        }
        return model;
    }

    @Override
    public InputStream openShared(Object ref) throws IOException {
        if (!(ref instanceof Uri)) throw new IOException("Unknown item");
        InputStream in = app.getContentResolver().openInputStream((Uri) ref);
        if (in == null) throw new IOException("Cannot open file");
        return in;
    }

    @Override
    public File partialDir() {
        return new File(app.getCacheDir(), "uploads");
    }

    @Override
    public FileServer.Sink openSink(String fileName) throws IOException {
        final ContentResolver resolver = app.getContentResolver();
        String ext = FileOps.extension(fileName);
        String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = FileOps.mimeOfName(fileName);

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileBandit");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri dest = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (dest == null) throw new IOException("Cannot create a file in Downloads");
        final OutputStream os = resolver.openOutputStream(dest);
        if (os == null) {
            try { resolver.delete(dest, null, null); } catch (Exception ignored) { }
            throw new IOException("Cannot write to Downloads");
        }
        return new FileServer.Sink() {
            @Override public OutputStream stream() { return os; }

            @Override public void commit() {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(dest, done, null, null);
            }

            @Override public void abort() {
                try { resolver.delete(dest, null, null); } catch (Exception ignored) { }
            }

            @Override public String location() { return "Downloads/FileBandit"; }
        };
    }

    // ------------------------------------------------------------------ hotspot

    public HotspotState hotspotState() { return hotspotState; }
    public String hotspotSsid() { return hotspotSsid; }
    public String hotspotPass() { return hotspotPass; }
    public String hotspotError() { return hotspotError; }

    /** The text that makes an iPhone camera offer to join the hotspot. */
    public String hotspotJoinCode() {
        if (hotspotSsid == null) return null;
        String p = hotspotPass == null ? "" : hotspotPass;
        return p.isEmpty()
                ? "WIFI:T:nopass;S:" + wifiEscape(hotspotSsid) + ";;"
                : "WIFI:T:WPA;S:" + wifiEscape(hotspotSsid) + ";P:" + wifiEscape(p) + ";;";
    }

    private static String wifiEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** The caller checks the Nearby devices (Android 13+) / Location permission first; a refusal is caught below. */
    @SuppressLint("MissingPermission")
    public void startHotspot() {
        if (hotspotState != HotspotState.OFF) return;
        hotspotState = HotspotState.STARTING;
        hotspotError = null;
        changed();
        try {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspot = reservation;
                    try {
                        SoftApConfiguration cfg = reservation.getSoftApConfiguration();
                        hotspotSsid = cfg.getSsid();
                        hotspotPass = cfg.getPassphrase();
                    } catch (Exception ignored) { }
                    if (hotspotSsid == null) {
                        hotspotError = "The hotspot started but its name couldn't be read. Check Settings > Hotspot.";
                    }
                    hotspotState = HotspotState.ON;
                    refreshNet();
                    // the hotspot interface takes a moment to get its address
                    ui.postDelayed(new Runnable() { @Override public void run() { refreshNet(); } }, 1500);
                    ui.postDelayed(new Runnable() { @Override public void run() { refreshNet(); } }, 4000);
                }

                @Override
                public void onStopped() {
                    hotspot = null;
                    resetHotspot();
                }

                @Override
                public void onFailed(int reason) {
                    hotspot = null;
                    resetHotspot();
                    hotspotError = "The hotspot couldn't start (code " + reason + "). Turn on Location, "
                            + "and make sure the regular hotspot or tethering is off.";
                    changed();
                }
            }, ui);
        } catch (Exception e) {
            resetHotspot();
            hotspotError = "Couldn't start the hotspot: " + e.getMessage();
            changed();
        }
    }

    public void stopHotspot() {
        if (hotspot != null) {
            try { hotspot.close(); } catch (Exception ignored) { }
            hotspot = null;
        }
        resetHotspot();
    }

    private void resetHotspot() {
        hotspotState = HotspotState.OFF;
        hotspotSsid = null;
        hotspotPass = null;
        refreshNet();
    }

    // ------------------------------------------------------------------ self test

    public interface TestCallback {
        void done(String message);
    }

    /** Connects to this phone's own server the same way the iPhone would, to tell app problems from network problems. */
    public void selfTest(final TestCallback cb) {
        final String url = currentUrl();
        bg.execute(new Runnable() {
            @Override public void run() {
                final String msg;
                if (!isRunning()) {
                    msg = "The server isn't running" + (error == null ? "." : ": " + error);
                } else if (url == null) {
                    msg = "This phone has no Wi-Fi or hotspot address yet, so there's nothing for the iPhone to connect to.";
                } else {
                    String m;
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url.replace("/?t=", "/api/info?t=")).openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(4000);
                        int code = c.getResponseCode();
                        m = code == 200
                                ? "✓ The server works and answers at " + url.substring(0, url.indexOf("/?")) + ". "
                                + "If the iPhone still can't open it, the cause is the network or the iPhone's browser settings "
                                + "(see the list above)."
                                : "The server answered with an unexpected status (" + code + ").";
                    } catch (Exception e) {
                        m = "Couldn't reach the server from this phone itself (" + e.getMessage() + ").";
                    }
                    msg = m;
                }
                ui.post(new Runnable() {
                    @Override public void run() { cb.done(msg); }
                });
            }
        });
    }

    private static String randomToken(int len) {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }
}
