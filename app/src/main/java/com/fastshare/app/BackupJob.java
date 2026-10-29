package com.fastshare.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the photo backup with the user's saved settings. Used by the schedule and by the "Back up now" button. */
final class BackupJob {
    private BackupJob() { }

    static final class Outcome {
        Backup.Summary summary;
        String error;               // set when nothing could be done at all
        boolean connectionFailed;
        boolean alreadyRunning;
    }

    /** One backup at a time, whoever asked. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    static boolean isRunning() {
        return RUNNING.get();
    }

    static String defaultDest() {
        String model = Build.MODEL == null || Build.MODEL.trim().isEmpty() ? "Android" : Build.MODEL.trim();
        return "/FileBandit Backup/" + model.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    static Backup.Options options(Context c) {
        Prefs p = new Prefs(c);
        Backup.Options o = new Backup.Options();
        File root = Environment.getExternalStorageDirectory();
        for (String name : p.backupFolders()) o.folders.add(new File(root, name));
        o.destRoot = p.backupDest().isEmpty() ? defaultDest() : p.backupDest();
        o.byDate = p.backupByDate();
        o.includeVideos = p.backupVideos();
        return o;
    }

    static BackupLog logFor(Context c, String connectionId) {
        return new BackupLog(new File(c.getApplicationContext().getFilesDir(), "backup_" + connectionId.replaceAll("[^A-Za-z0-9]", "") + ".tsv"));
    }

    static String describe(Backup.Summary s) {
        if (s.uploaded == 0 && s.failed == 0 && !s.cancelled) {
            return s.skipped > 0 ? "Everything was already backed up" : "No new photos or videos";
        }
        StringBuilder sb = new StringBuilder("Sent " + Fmt.plural(s.uploaded, "file", "files") + " (" + Fmt.bytes(s.bytes) + ")");
        if (s.failed > 0) sb.append(", ").append(s.failed).append(" failed");
        if (s.cancelled) sb.append(", stopped early");
        if (s.stoppedEarly) sb.append(", the server stopped answering");
        return sb.toString();
    }

    /** Runs a backup now. Safe to call from a background thread only. */
    static Outcome run(Context context, Backup.Listener listener) {
        Context c = context.getApplicationContext();
        Outcome out = new Outcome();
        if (!RUNNING.compareAndSet(false, true)) {
            out.alreadyRunning = true;
            return out;
        }
        Prefs p = new Prefs(c);
        try {
            if (!Access.allFiles()) {
                out.error = "Allow file access in the Files tab first.";
                return out;
            }
            Connection conn = p.backupConnection().isEmpty() ? null : RemoteSession.store(c).get(p.backupConnection());
            if (conn == null) {
                out.error = "Choose where to back up to first.";
                return out;
            }
            Remote r;
            try {
                Connection copy = conn.copy();
                r = Remotes.open(copy);
                if (!copy.hostKey.equals(conn.hostKey)) {
                    conn.hostKey = copy.hostKey;
                    RemoteSession.store(c).save(conn);
                }
            } catch (IOException e) {
                out.error = e.getMessage();
                out.connectionFailed = true;
                p.setBackupLast("Couldn't connect: " + e.getMessage(), System.currentTimeMillis());
                return out;
            }
            try {
                out.summary = Backup.run(r, options(c), logFor(c, conn.id), listener);
            } finally {
                try { r.close(); } catch (IOException ignored) { }
            }
            p.setBackupLast(describe(out.summary), System.currentTimeMillis());
            return out;
        } finally {
            RUNNING.set(false);
        }
    }
}
