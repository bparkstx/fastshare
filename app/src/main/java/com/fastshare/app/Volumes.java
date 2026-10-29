package com.fastshare.app;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The phone's storage volumes: internal storage plus any mounted SD card or USB drive. */
final class Volumes {
    private Volumes() { }

    /** Tests can add volumes without a real SD card. */
    static volatile List<File> testExtra;

    static File primary() {
        return Environment.getExternalStorageDirectory();
    }

    static List<File> roots(Context c) {
        List<File> out = new ArrayList<>();
        out.add(primary());
        try {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume sv : sm.getStorageVolumes()) {
                File d = sv.getDirectory();
                if (d != null && !sv.isPrimary() && Environment.MEDIA_MOUNTED.equals(sv.getState())) out.add(d);
            }
        } catch (Exception ignored) { }
        List<File> extra = testExtra;
        if (extra != null) out.addAll(extra);
        return out;
    }

    /** The volume a file lives on (longest matching root), or internal storage if none matches. */
    static File rootOf(Context c, File f) {
        return rootOf(roots(c), f);
    }

    static File rootOf(List<File> roots, File f) {
        File best = roots.isEmpty() ? primary() : roots.get(0);
        int bestLen = -1;
        for (File r : roots) {
            String rp = r.getPath();
            if ((f.getPath().equals(rp) || f.getPath().startsWith(rp + "/")) && rp.length() > bestLen) {
                best = r;
                bestLen = rp.length();
            }
        }
        return best;
    }

    static String label(Context c, File root) {
        if (root.equals(primary())) return "Internal storage";
        try {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume sv : sm.getStorageVolumes()) {
                if (root.equals(sv.getDirectory())) return sv.getDescription(c);
            }
        } catch (Exception ignored) { }
        return root.getName();
    }
}
