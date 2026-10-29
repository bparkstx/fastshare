package com.fastshare.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.List;

/** The transfer tab: connect the phones, scan, and move files. */
public class TransferFragment extends Fragment implements ShareHub.HubListener {

    private static final int OK = 0;
    private static final int WARN = 1;
    private static final int BAD = 2;
    private static final int IDLE = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ShareHub hub;
    private Prefs prefs;

    private View dotServer, dotNet, dotClient;
    private TextView statusServer, statusNet, statusClient;
    private MaterialButtonToggleGroup methodGroup, qrModeGroup;
    private TextView step1Title, step1Body, step2Body, hotspotInfo, qrCaption, linkText, helpText, selfTestResult, sharedTitle;
    private View hotspotBox, sharedCard, activityCard, helpBody;
    private MaterialButton hotspotBtn, copyBtn, shareBtn, otherAddrBtn;
    private com.google.android.material.materialswitch.MaterialSwitch pinSwitch;
    private TextView pinText, pinHelp;
    private boolean pinBinding;
    private ImageView qrView, helpChevron;
    private LinearLayout sharedList, activityList;

    private boolean qrShowJoin = true;
    private boolean helpUserSet;
    private boolean helpOpen;
    private long qrShownAt;
    private String lastQr = "";
    private String lastSharedSig = "";
    private String lastActivitySig = "";
    private boolean ticking;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                FastShareApp.hub(requireContext()).addUris(uris);
                Toast.makeText(requireContext(), "Added " + Fmt.plural(uris.size(), "file", "files")
                        + ". Now open the link on the iPhone.", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String> hotspotPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) FastShareApp.hub(requireContext()).startHotspot();
                else Toast.makeText(requireContext(),
                        "The hotspot needs the “Nearby devices” (or Location) permission.", Toast.LENGTH_LONG).show();
            });

    // ------------------------------------------------------------------ lifecycle

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_transfer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle state) {
        super.onViewCreated(v, state);
        hub = FastShareApp.hub(requireContext());
        prefs = new Prefs(requireContext());

        dotServer = v.findViewById(R.id.dotServer);
        dotNet = v.findViewById(R.id.dotNet);
        dotClient = v.findViewById(R.id.dotClient);
        statusServer = v.findViewById(R.id.statusServer);
        statusNet = v.findViewById(R.id.statusNet);
        statusClient = v.findViewById(R.id.statusClient);
        methodGroup = v.findViewById(R.id.methodGroup);
        qrModeGroup = v.findViewById(R.id.qrModeGroup);
        step1Title = v.findViewById(R.id.step1Title);
        step1Body = v.findViewById(R.id.step1Body);
        step2Body = v.findViewById(R.id.step2Body);
        hotspotBox = v.findViewById(R.id.hotspotBox);
        hotspotInfo = v.findViewById(R.id.hotspotInfo);
        hotspotBtn = v.findViewById(R.id.hotspotBtn);
        qrView = v.findViewById(R.id.qrView);
        qrCaption = v.findViewById(R.id.qrCaption);
        linkText = v.findViewById(R.id.linkText);
        copyBtn = v.findViewById(R.id.copyBtn);
        shareBtn = v.findViewById(R.id.shareBtn);
        pinSwitch = v.findViewById(R.id.pinSwitch);
        pinText = v.findViewById(R.id.pinText);
        pinHelp = v.findViewById(R.id.pinHelp);
        otherAddrBtn = v.findViewById(R.id.otherAddrBtn);
        sharedCard = v.findViewById(R.id.sharedCard);
        sharedTitle = v.findViewById(R.id.sharedTitle);
        sharedList = v.findViewById(R.id.sharedList);
        activityCard = v.findViewById(R.id.activityCard);
        activityList = v.findViewById(R.id.activityList);
        helpBody = v.findViewById(R.id.helpBody);
        helpText = v.findViewById(R.id.helpText);
        helpChevron = v.findViewById(R.id.helpChevron);
        selfTestResult = v.findViewById(R.id.selfTestResult);

        methodGroup.check(prefs.hotspotMethod() ? R.id.methodHotspot : R.id.methodWifi);
        methodGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            prefs.setHotspotMethod(id == R.id.methodHotspot);
            lastQr = "";
            render();
        });
        qrModeGroup.check(R.id.qrJoin);
        qrModeGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            qrShowJoin = id == R.id.qrJoin;
            render();
        });

        hotspotBtn.setOnClickListener(x -> toggleHotspot());
        copyBtn.setOnClickListener(x -> copyLink());
        shareBtn.setOnClickListener(x -> shareLink());
        pinSwitch.setOnCheckedChangeListener((b, on) -> {
            if (pinBinding) return;
            if (on) hub.setLinkPin(String.format(java.util.Locale.US, "%04d", new java.security.SecureRandom().nextInt(10000)));
            else hub.setLinkPin("");
            render();
        });
        pinText.setOnClickListener(x -> {
            if (hub.linkPin().isEmpty()) return;
            hub.setLinkPin(String.format(java.util.Locale.US, "%04d", new java.security.SecureRandom().nextInt(10000)));
            Toast.makeText(requireContext(), "New PIN. Devices that were signed in have to enter it again.", Toast.LENGTH_SHORT).show();
            render();
        });
        v.findViewById(R.id.historyBtn).setOnClickListener(x -> startActivity(new android.content.Intent(requireContext(), HistoryActivity.class)));
        otherAddrBtn.setOnClickListener(x -> hub.nextAddress());
        v.findViewById(R.id.pickFilesBtn).setOnClickListener(x -> {
            Toast.makeText(requireContext(),
                    "Press and hold a file to select it, then tap the send arrow.", Toast.LENGTH_LONG).show();
            ((MainActivity) requireActivity()).showFiles(null);
        });
        v.findViewById(R.id.pickOtherBtn).setOnClickListener(x -> picker.launch(new String[]{"*/*"}));
        v.findViewById(R.id.openReceivedBtn).setOnClickListener(x -> {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileBandit");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            ((MainActivity) requireActivity()).showFiles(dir);
        });
        v.findViewById(R.id.sharedClear).setOnClickListener(x -> hub.clearShared());
        v.findViewById(R.id.activityClear).setOnClickListener(x -> hub.clearFinishedTransfers());
        v.findViewById(R.id.helpHeader).setOnClickListener(x -> {
            helpUserSet = true;
            helpOpen = !currentHelpOpen();
            render();
        });
        v.findViewById(R.id.selfTestBtn).setOnClickListener(x -> {
            selfTestResult.setText("Testing…");
            hub.selfTest(msg -> {
                if (isAdded() && getView() != null) selfTestResult.setText(msg);
            });
        });
        render();
    }

    @Override
    public void onStart() {
        super.onStart();
        hub.addListener(this);
        hub.refreshNet();
        ticking = true;
        ui.post(ticker);
    }

    @Override
    public void onStop() {
        super.onStop();
        hub.removeListener(this);
        ticking = false;
        ui.removeCallbacks(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!ticking) return;
            render();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void onHubChanged() {
        render();
    }

    // ------------------------------------------------------------------ actions

    private void toggleHotspot() {
        if (hub.hotspotState() == ShareHub.HotspotState.ON) {
            hub.stopHotspot();
            return;
        }
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
            hub.startHotspot();
        } else {
            hotspotPermission.launch(perm);
        }
    }

    private void copyLink() {
        String url = hub.currentUrl();
        if (url == null) return;
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("FileBandit link", url));
        Toast.makeText(requireContext(), "Link copied. Paste it into Safari on the iPhone.", Toast.LENGTH_SHORT).show();
    }

    private void shareLink() {
        String url = hub.currentUrl();
        if (url == null) return;
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT, "FileBandit link");
        send.putExtra(android.content.Intent.EXTRA_TEXT, "Open this on the same Wi-Fi to send files to my phone or get files from it:\n" + url);
        startActivity(android.content.Intent.createChooser(send, "Share link"));
    }

    // ------------------------------------------------------------------ rendering

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private void status(View dot, TextView text, int state, String message) {
        int c;
        switch (state) {
            case OK: c = color(R.color.fs_ok); break;
            case WARN: c = color(R.color.fs_warn); break;
            case BAD: c = color(R.color.fs_err); break;
            default: c = color(R.color.fs_muted);
        }
        dot.setBackgroundTintList(ColorStateList.valueOf(c));
        text.setText(message);
    }

    private boolean currentHelpOpen() {
        if (helpUserSet) return helpOpen;
        // Nobody has connected 20 seconds after the code appeared: show the fixes without being asked.
        return !hub.everConnected() && qrShownAt > 0 && System.currentTimeMillis() - qrShownAt > 20_000;
    }

    private void render() {
        if (!isAdded() || getView() == null) return;
        final boolean hotspotMode = prefs.hotspotMethod();
        final Net.Info net = hub.net();
        final ShareHub.HotspotState hs = hub.hotspotState();
        final boolean running = hub.isRunning();

        // --- status
        status(dotServer, statusServer, running ? OK : BAD,
                running ? "Transfer server is running" : "Server isn't running" + (hub.error() == null ? "" : ": " + hub.error()));
        Net.Addr cur = hub.currentAddr();
        if (hs == ShareHub.HotspotState.ON) {
            status(dotNet, statusNet, OK, "Hotspot is on" + (cur != null ? " (" + cur.ip + ")" : ""));
        } else if (net.wifi && cur != null) {
            status(dotNet, statusNet, OK, "Connected to Wi-Fi (" + cur.ip + ")");
        } else if (cur != null) {
            status(dotNet, statusNet, WARN, "On a network, but not Wi-Fi (" + cur.ip + ")");
        } else {
            status(dotNet, statusNet, WARN, hotspotMode ? "Hotspot is off" : "Not connected to Wi-Fi");
        }
        List<ShareHub.Client> active = hub.activeClients();
        if (!active.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ShareHub.Client c : active) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(c.label());
            }
            status(dotClient, statusClient, OK, sb + " connected");
        } else if (hub.everConnected()) {
            status(dotClient, statusClient, WARN, "The iPhone went quiet. Keep its page open.");
        } else {
            status(dotClient, statusClient, IDLE, "Waiting for the iPhone to open the link…");
        }

        // --- step 1: connect
        hotspotBox.setVisibility(hotspotMode ? View.VISIBLE : View.GONE);
        if (hotspotMode) {
            step1Title.setText("Start this phone’s hotspot");
            String body = "This phone makes its own private Wi-Fi network and the iPhone joins it. "
                    + "No router or internet needed. The iPhone won’t have internet while it is joined.";
            if (hub.hotspotError() != null) body += "\n\n" + hub.hotspotError();
            step1Body.setText(body);
            boolean showInfo = hs == ShareHub.HotspotState.ON && hub.hotspotSsid() != null;
            hotspotInfo.setVisibility(showInfo ? View.VISIBLE : View.GONE);
            if (showInfo) {
                hotspotInfo.setText("Network:  " + hub.hotspotSsid()
                        + (hub.hotspotPass() != null ? "\nPassword:  " + hub.hotspotPass() : ""));
            }
            hotspotBtn.setEnabled(hs != ShareHub.HotspotState.STARTING);
            hotspotBtn.setText(hs == ShareHub.HotspotState.ON ? "Stop hotspot"
                    : hs == ShareHub.HotspotState.STARTING ? "Starting…" : "Start hotspot");
        } else {
            step1Title.setText("Join the same Wi-Fi on both phones");
            step1Body.setText(cur == null || !net.wifi
                    ? "This phone isn’t on Wi-Fi yet. Join your Wi-Fi network in Android Settings, then come back. "
                    + "Join the same network on the iPhone and turn off any VPN. "
                    + "No Wi-Fi available? Use Hotspot above."
                    : "Both phones need to be on the same Wi-Fi network and have no VPN on. "
                    + "Guest networks often block this. If the iPhone can’t connect, switch to Hotspot above.");
        }

        // --- step 2: scan
        String content;
        boolean linkShown = false;
        if (hotspotMode && hs != ShareHub.HotspotState.ON) {
            content = null;
            qrModeGroup.setVisibility(View.GONE);
            step2Body.setText("Start the hotspot in step 1. A code to scan will appear here.");
            qrCaption.setText("");
            linkText.setText("");
        } else if (hotspotMode && qrShowJoin && hub.hotspotJoinCode() != null) {
            content = hub.hotspotJoinCode();
            qrModeGroup.setVisibility(View.VISIBLE);
            step2Body.setText("On the iPhone, open the Camera app, point it at this code and tap Join. "
                    + "When it says connected, tap “2. Open page” above.");
            qrCaption.setText("Scan to join “" + hub.hotspotSsid() + "”");
            linkText.setText("Or join it by hand in the iPhone’s Wi-Fi settings with the password above.");
        } else {
            content = hub.currentUrl();
            linkShown = true;
            qrModeGroup.setVisibility(hotspotMode ? View.VISIBLE : View.GONE);
            step2Body.setText("Open the iPhone Camera app, point it at the code and tap the banner that appears. "
                    + "It opens in Safari. Don’t scan with Chrome or the Google app.");
            if (content == null) {
                qrCaption.setText("No address yet");
                linkText.setText("This phone has no Wi-Fi or hotspot address. Connect it as described in step 1.");
            } else {
                qrCaption.setText("Scan with the iPhone Camera");
                StringBuilder sb = new StringBuilder(content);
                List<Net.Addr> addrs = hub.addrs();
                if (addrs.size() > 1) {
                    sb.append("\nAddress ").append(hub.addrIndex() + 1).append(" of ").append(addrs.size())
                            .append(" (").append(cur.label).append("). If the iPhone can’t connect, tap Other address.");
                }
                linkText.setText(sb);
                if (qrShownAt == 0) qrShownAt = System.currentTimeMillis();
            }
        }
        copyBtn.setVisibility(linkShown && content != null ? View.VISIBLE : View.GONE);
        shareBtn.setVisibility(linkShown && content != null ? View.VISIBLE : View.GONE);
        String linkPin = hub.linkPin();
        pinBinding = true;
        pinSwitch.setChecked(!linkPin.isEmpty());
        pinBinding = false;
        pinText.setText(linkPin);
        pinText.setVisibility(linkPin.isEmpty() ? View.GONE : View.VISIBLE);
        pinHelp.setText(linkPin.isEmpty()
                ? "Anyone on the same Wi-Fi who has the link can use it. Turn this on to ask for a PIN first."
                : "The other device asks for this PIN after it opens the link. Tap the number for a new one.");
        otherAddrBtn.setVisibility(linkShown && hub.addrs().size() > 1 ? View.VISIBLE : View.GONE);
        if (content == null) {
            lastQr = "";
            qrView.setImageResource(R.drawable.ic_wifi);
            qrView.setColorFilter(color(R.color.fs_line));
            qrView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            qrView.setPadding(dp(60), dp(60), dp(60), dp(60));
        } else if (!content.equals(lastQr)) {
            lastQr = content;
            Bitmap b = Qr.make(content, dp(216));
            qrView.setColorFilter(null);
            qrView.setPadding(dp(10), dp(10), dp(10), dp(10));
            qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qrView.setImageBitmap(b);
        }

        renderShared();
        renderActivity();
        renderHelp();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void renderShared() {
        List<FileServer.Shared> shared = hub.shared();
        StringBuilder sig = new StringBuilder();
        for (FileServer.Shared s : shared) sig.append(s.id).append(';');
        sharedCard.setVisibility(shared.isEmpty() ? View.GONE : View.VISIBLE);
        if (sig.toString().equals(lastSharedSig)) return;
        lastSharedSig = sig.toString();
        sharedList.removeAllViews();
        sharedTitle.setText("Ready to send (" + shared.size() + ")");
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (final FileServer.Shared s : shared) {
            View row = inf.inflate(R.layout.item_shared_row, sharedList, false);
            FileOps.Kind kind = s.dir ? FileOps.Kind.FOLDER : FileOps.kindOfName(s.name);
            ImageView icon = row.findViewById(R.id.sIcon);
            int c = Kinds.color(requireContext(), kind);
            icon.setImageResource(Kinds.icon(kind));
            icon.setColorFilter(c);
            icon.setBackground(Kinds.badge(c, dp(10), false));
            ((TextView) row.findViewById(R.id.sName)).setText(s.name);
            ((TextView) row.findViewById(R.id.sSub)).setText(s.dir ? "Folder, sent as a zip" : Fmt.bytes(s.size));
            row.findViewById(R.id.sRemove).setOnClickListener(x -> hub.removeShared(s.id));
            sharedList.addView(row);
        }
    }

    private void renderActivity() {
        List<FileServer.Transfer> ts = hub.transfers();
        StringBuilder sig = new StringBuilder();
        for (FileServer.Transfer t : ts) sig.append(t.id).append(':').append(t.done).append(t.state).append(';');
        activityCard.setVisibility(ts.isEmpty() ? View.GONE : View.VISIBLE);
        if (sig.toString().equals(lastActivitySig)) return;
        lastActivitySig = sig.toString();
        activityList.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (FileServer.Transfer t : ts) {
            View row = inf.inflate(R.layout.item_activity_row, activityList, false);
            ((TextView) row.findViewById(R.id.aTitle)).setText((t.incoming ? "Received:  " : "Sent:  ") + t.name);
            LinearProgressIndicator bar = row.findViewById(R.id.aBar);
            TextView sub = (TextView) row.findViewById(R.id.aSub);
            int c;
            String text;
            if (t.state == FileServer.State.DONE) {
                c = color(R.color.fs_ok);
                text = "✓ " + (t.incoming ? "Saved in " + t.detail : "Sent") + "  ·  " + Fmt.bytes(t.done) + "  ·  " + t.speed();
                bar.setIndeterminate(false);
                bar.setProgress(1000);
            } else if (t.state == FileServer.State.FAILED) {
                c = color(R.color.fs_err);
                text = "Interrupted after " + Fmt.bytes(t.done) + ". Try again.";
                bar.setIndeterminate(false);
                bar.setProgress(t.total > 0 ? (int) (t.done * 1000 / t.total) : 0);
            } else {
                c = color(R.color.fs_primary);
                if (t.total > 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) Math.min(1000, t.done * 1000 / t.total));
                    text = (t.done * 100 / t.total) + "%  ·  " + Fmt.bytes(t.done) + " of " + Fmt.bytes(t.total) + "  ·  " + t.speed();
                } else {
                    bar.setIndeterminate(true);
                    text = Fmt.bytes(t.done) + "  ·  " + t.speed();
                }
            }
            bar.setIndicatorColor(c);
            sub.setText(text);
            sub.setTextColor(t.state == FileServer.State.ACTIVE ? color(R.color.fs_muted) : c);
            activityList.addView(row);
        }
    }

    private void renderHelp() {
        boolean open = currentHelpOpen();
        helpBody.setVisibility(open ? View.VISIBLE : View.GONE);
        helpChevron.setRotation(open ? 180 : 0);
        String url = hub.currentUrl();
        helpText.setText(
                "1.  Scan with the iPhone Camera app and open the link in Safari. In Chrome, Google or other browsers "
                        + "on iPhone the page can fail with “ERR_CONNECTION_TIMED_OUT” until Local Network access is on: "
                        + "iPhone Settings › Chrome › Local Network.\n\n"
                        + "2.  The iPhone must be on Wi-Fi (not mobile data) and joined to the same Wi-Fi network as this phone.\n\n"
                        + "3.  Guest Wi-Fi and some routers (“AP isolation”) stop phones from seeing each other. "
                        + "Switch to Hotspot at the top, which needs no router.\n\n"
                        + "4.  Turn off VPNs on both phones.\n\n"
                        + "5.  More than one address? Tap Other address and scan again.\n\n"
                        + "6.  Or type the link into Safari by hand:\n" + (url == null ? "(no address yet)" : url));
    }
}
