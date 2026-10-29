package com.fastshare.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The apps installed on this phone, with what's needed to list, back up and share them. */
final class AppList {
    private AppList() { }

    static final class App {
        final String pkg;
        final String label;
        final String version;
        final long size;              // all the APK files together
        final long installed;
        final long updated;
        final boolean system;
        final File baseApk;
        final List<File> allApks;
        Drawable icon;

        App(String pkg, String label, String version, long size, long installed, long updated,
            boolean system, File baseApk, List<File> allApks) {
            this.pkg = pkg;
            this.label = label;
            this.version = version;
            this.size = size;
            this.installed = installed;
            this.updated = updated;
            this.system = system;
            this.baseApk = baseApk;
            this.allApks = allApks;
        }

        boolean isSplit() {
            return allApks.size() > 1;
        }

        /** "Notes_1.4.2" style name used for backups. */
        String backupName() {
            String clean = label.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (clean.isEmpty()) clean = pkg;
            String v = version == null ? "" : version.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\s]", "_");
            return v.isEmpty() ? clean : clean + "_" + v;
        }
    }

    static List<App> load(Context c, boolean includeSystem, boolean withIcons) {
        PackageManager pm = c.getPackageManager();
        List<App> out = new ArrayList<>();
        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(0);
        } catch (Exception e) {
            return out;
        }
        for (PackageInfo pi : pkgs) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (system && !includeSystem) continue;
            String label;
            try {
                CharSequence l = ai.loadLabel(pm);
                label = l == null ? pi.packageName : l.toString();
            } catch (Exception e) {
                label = pi.packageName;
            }
            List<File> apks = new ArrayList<>();
            long size = 0;
            if (ai.sourceDir != null) {
                File base = new File(ai.sourceDir);
                apks.add(base);
                size += base.length();
            }
            if (ai.splitSourceDirs != null) {
                for (String s : ai.splitSourceDirs) {
                    File f = new File(s);
                    apks.add(f);
                    size += f.length();
                }
            }
            App app = new App(pi.packageName, label, pi.versionName, size, pi.firstInstallTime, pi.lastUpdateTime,
                    system, apks.isEmpty() ? null : apks.get(0), apks);
            if (withIcons) {
                try {
                    app.icon = pm.getApplicationIcon(ai);
                } catch (Exception ignored) { }
            }
            out.add(app);
        }
        sort(out, Sort.NAME);
        return out;
    }

    enum Sort { NAME, SIZE, INSTALLED, UPDATED }

    static void sort(List<App> apps, final Sort sort) {
        Collections.sort(apps, new Comparator<App>() {
            @Override public int compare(App a, App b) {
                switch (sort) {
                    case SIZE: return Long.compare(b.size, a.size);
                    case INSTALLED: return Long.compare(b.installed, a.installed);
                    case UPDATED: return Long.compare(b.updated, a.updated);
                    default: return a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT));
                }
            }
        });
    }
}
