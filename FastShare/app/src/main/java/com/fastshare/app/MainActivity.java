package com.fastshare.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements FileServer.Listener {

    private static final int REQ_PICK = 1;
    private static final int REQ_PERM = 2;
    private static final int DEFAULT_PORT = 8080;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private FileServer server;
    private String token;
    private int port = DEFAULT_PORT;

    private WifiManager wifi;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.LocalOnlyHotspotReservation hotspot;

    private String wifiQrText;
    private String hotspotInfo;
    private List<String> candidateUrls = new ArrayList<>();
    private boolean showingWifiQr = false;

    private ImageView qrView;
    private TextView captionView;
    private TextView urlsView;
    private TextView sharedView;
    private TextView progressView;
    private TextView logView;
    private Button hotspotBtn;
    private Button toggleBtn;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            // Keeps the Wi-Fi radio in its fastest, lowest-latency mode while the app is on screen.
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "fastshare");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }

        token = randomToken(8);
        server = new FileServer(this, token, this);
        try {
            port = server.start(DEFAULT_PORT);
        } catch (Exception e) {
            captionView.setText("Could not start the server: " + e.getMessage());
            return;
        }
        refreshAddresses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (server != null) refreshAddresses();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHotspot();
        if (server != null) server.stop();
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : Color.rgb(26, 115, 232));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColor(Color.rgb(26, 115, 232));
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), Color.rgb(205, 210, 218));
        }
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(244, 245, 247));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int dark = Color.rgb(21, 24, 29);
        int muted = Color.rgb(102, 112, 133);

        root.addView(text("FastShare", 28, true, dark));
        root.addView(text("Scan the code with the other phone's camera, or type the link into its browser. "
                + "Both phones must be on the same Wi-Fi, or use the hotspot below.", 14, false, muted));

        qrView = new ImageView(this);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(240), dp(240));
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        qlp.topMargin = dp(16);
        qrView.setLayoutParams(qlp);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(qrView);

        captionView = text("Starting...", 14, true, dark);
        captionView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(captionView);

        urlsView = text("", 13, false, muted);
        urlsView.setGravity(Gravity.CENTER_HORIZONTAL);
        urlsView.setTextIsSelectable(true);
        root.addView(urlsView);

        toggleBtn = button("Show link code", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showingWifiQr = !showingWifiQr;
                renderQr();
            }
        });
        toggleBtn.setVisibility(View.GONE);
        root.addView(toggleBtn);

        hotspotBtn = button("Start hotspot (no Wi-Fi network needed)", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hotspot == null) startHotspot(); else stopHotspot();
            }
        });
        root.addView(hotspotBtn);

        root.addView(text("Files on this phone that others can download", 13, true, muted));
        root.addView(button("Choose files to share", true, new View.OnClickListener() {
            @Override public void onClick(View v) { pickFiles(); }
        }));
        sharedView = text("Nothing shared yet.", 14, false, dark);
        root.addView(sharedView);
        root.addView(button("Clear shared list", false, new View.OnClickListener() {
            @Override public void onClick(View v) { if (server != null) server.clearShared(); }
        }));

        root.addView(text("Files sent to this phone are saved in Downloads/FastShare.", 13, false, muted));

        progressView = text("", 14, true, Color.rgb(26, 115, 232));
        root.addView(progressView);

        logView = text("", 13, false, dark);
        root.addView(logView);

        setContentView(scroll);
    }

    // ------------------------------------------------------------------ QR + addresses

    private static String randomToken(int len) {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** Finds this phone's private IPv4 addresses (Wi-Fi, hotspot, USB tether), best guess first. */
    private void refreshAddresses() {
        List<String> wlan = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")
                        || name.startsWith("dummy") || name.startsWith("v4-")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        boolean hotspotLike = ip.endsWith(".1");
                        // When our own hotspot is running, the hotspot address is the .1 one.
                        if (hotspot != null ? hotspotLike : !hotspotLike) wlan.add(0, ip);
                        else if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) wlan.add(ip);
                        else other.add(ip);
                    }
                }
            }
        } catch (Exception ignored) { }

        List<String> ips = new ArrayList<>(wlan);
        for (String ip : other) if (!ips.contains(ip)) ips.add(ip);

        candidateUrls = new ArrayList<>();
        for (String ip : ips) candidateUrls.add("http://" + ip + ":" + port + "/?t=" + token);
        renderQr();
    }

    private void renderQr() {
        String content;
        if (showingWifiQr && wifiQrText != null) {
            content = wifiQrText;
            captionView.setText("Step 1: scan to join this phone's hotspot");
            urlsView.setText(hotspotInfo == null ? "" : hotspotInfo);
            toggleBtn.setText("Next: show link code");
        } else if (!candidateUrls.isEmpty()) {
            content = candidateUrls.get(0);
            captionView.setText(wifiQrText != null ? "Step 2: scan to open FastShare" : "Scan to open FastShare");
            StringBuilder sb = new StringBuilder();
            for (String u : candidateUrls) sb.append(u).append('\n');
            urlsView.setText(sb.toString().trim());
            toggleBtn.setText("Show hotspot join code");
        } else {
            qrView.setImageBitmap(null);
            captionView.setText("Not connected to Wi-Fi. Join the same Wi-Fi as the other phone, or start the hotspot.");
            urlsView.setText("");
            toggleBtn.setVisibility(wifiQrText != null ? View.VISIBLE : View.GONE);
            return;
        }
        toggleBtn.setVisibility(wifiQrText != null ? View.VISIBLE : View.GONE);
        qrView.setImageBitmap(makeQr(content, dp(224)));
    }

    private Bitmap makeQr(String text, int px) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (WriterException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ hotspot

    private static String wifiEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    private void startHotspot() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_PERM);
            return;
        }
        hotspotBtn.setEnabled(false);
        captionView.setText("Starting hotspot...");
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspot = reservation;
                    String ssid = null;
                    String pass = null;
                    try {
                        SoftApConfiguration cfg = reservation.getSoftApConfiguration();
                        ssid = cfg.getSsid();
                        pass = cfg.getPassphrase();
                    } catch (Exception ignored) { }
                    if (ssid == null) {
                        captionView.setText("Hotspot started, but its name could not be read. "
                                + "Check Settings > Hotspot for the name and password.");
                    } else {
                        hotspotInfo = "Network: " + ssid + (pass != null ? "\nPassword: " + pass : "");
                        wifiQrText = "WIFI:T:WPA;S:" + wifiEscape(ssid) + ";P:" + wifiEscape(pass == null ? "" : pass) + ";;";
                        showingWifiQr = true;
                    }
                    hotspotBtn.setEnabled(true);
                    hotspotBtn.setText("Stop hotspot");
                    log("Hotspot started");
                    // The hotspot network interface takes a moment to get its address.
                    ui.postDelayed(new Runnable() { @Override public void run() { refreshAddresses(); } }, 1500);
                    ui.postDelayed(new Runnable() { @Override public void run() { refreshAddresses(); } }, 4000);
                    refreshAddresses();
                }

                @Override
                public void onStopped() {
                    hotspot = null;
                    clearHotspotUi();
                }

                @Override
                public void onFailed(int reason) {
                    hotspot = null;
                    hotspotBtn.setEnabled(true);
                    clearHotspotUi();
                    captionView.setText("Hotspot failed (code " + reason + "). Make sure Location is turned on, "
                            + "and that you're not already using the regular hotspot or tethering.");
                }
            }, ui);
        } catch (Exception e) {
            hotspotBtn.setEnabled(true);
            captionView.setText("Could not start hotspot: " + e.getMessage());
        }
    }

    private void stopHotspot() {
        if (hotspot != null) {
            try { hotspot.close(); } catch (Exception ignored) { }
            hotspot = null;
        }
        clearHotspotUi();
    }

    private void clearHotspotUi() {
        wifiQrText = null;
        hotspotInfo = null;
        showingWifiQr = false;
        if (hotspotBtn != null) {
            hotspotBtn.setText("Start hotspot (no Wi-Fi network needed)");
            hotspotBtn.setEnabled(true);
        }
        if (server != null) refreshAddresses();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startHotspot();
            } else {
                captionView.setText("The hotspot needs the \"Nearby devices\" (or Location) permission.");
            }
        }
    }

    // ------------------------------------------------------------------ file picking

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || server == null) return;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) addUri(clip.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            addUri(data.getData());
        }
    }

    private void addUri(Uri uri) {
        String name = "file";
        long size = -1;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
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
        server.addShared(uri, name, size);
    }

    // ------------------------------------------------------------------ FileServer.Listener (called from worker threads)

    private void log(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                logLines.addFirst(msg);
                while (logLines.size() > 8) logLines.removeLast();
                StringBuilder sb = new StringBuilder();
                for (String l : logLines) sb.append(l).append('\n');
                logView.setText(sb.toString().trim());
            }
        });
    }

    @Override public void onLog(String message) { log(message); }

    @Override
    public void onProgress(final String message) {
        ui.post(new Runnable() {
            @Override public void run() { progressView.setText(message); }
        });
    }

    @Override
    public void onFilesChanged() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (server == null) return;
                List<FileServer.Shared> list = server.getShared();
                if (list.isEmpty()) {
                    sharedView.setText("Nothing shared yet.");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (FileServer.Shared f : list) {
                    sb.append(f.name);
                    if (f.size >= 0) sb.append("  (").append(FileServer.fmtBytes(f.size)).append(')');
                    sb.append('\n');
                }
                sharedView.setText(sb.toString().trim());
            }
        });
    }
}
