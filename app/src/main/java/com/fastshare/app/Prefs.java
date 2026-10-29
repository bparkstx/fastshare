package com.fastshare.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers the user's view settings. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("fastshare", Context.MODE_PRIVATE);
    }

    FileOps.Sort sort() {
        try {
            return FileOps.Sort.valueOf(sp.getString("sort", FileOps.Sort.NAME.name()));
        } catch (IllegalArgumentException e) {
            return FileOps.Sort.NAME;
        }
    }

    boolean ascending() {
        return sp.getBoolean("asc", true);
    }

    void setSort(FileOps.Sort s, boolean asc) {
        sp.edit().putString("sort", s.name()).putBoolean("asc", asc).apply();
    }

    boolean grid() {
        return sp.getBoolean("grid", false);
    }

    void setGrid(boolean v) {
        sp.edit().putBoolean("grid", v).apply();
    }

    boolean showHidden() {
        return sp.getBoolean("hidden", false);
    }

    void setShowHidden(boolean v) {
        sp.edit().putBoolean("hidden", v).apply();
    }

    /** Pinned files and folders, in the order they were added. */
    java.util.List<String> favorites() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp.getString("favorites", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    void setFavorites(java.util.List<String> paths) {
        sp.edit().putString("favorites", new org.json.JSONArray(paths).toString()).apply();
    }

    boolean hotspotMethod() {
        return sp.getBoolean("hotspotMethod", false);
    }

    void setHotspotMethod(boolean v) {
        sp.edit().putBoolean("hotspotMethod", v).apply();
    }

    // ------------------------------------------------------------------ the phone as a drive

    boolean driveEnabled() {
        return sp.getBoolean("driveOn", false);
    }

    void setDriveEnabled(boolean v) {
        sp.edit().putBoolean("driveOn", v).apply();
    }

    /** The folder shown on the computer. Empty means all of internal storage. */
    String driveFolder() {
        return sp.getString("driveFolder", "");
    }

    void setDriveFolder(String path) {
        sp.edit().putString("driveFolder", path == null ? "" : path).apply();
    }

    boolean driveWritable() {
        return sp.getBoolean("driveWritable", false);
    }

    void setDriveWritable(boolean v) {
        sp.edit().putBoolean("driveWritable", v).apply();
    }

    /** Secret part of the drive's address. Made once, so a mounted drive keeps working after the app restarts. */
    String driveToken() {
        String t = sp.getString("driveToken", "");
        if (t.isEmpty()) t = newDriveToken();
        return t;
    }

    String newDriveToken() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        sp.edit().putString("driveToken", sb.toString()).apply();
        return sb.toString();
    }

    /** PIN the iPhone/PC page asks for, or empty when the link works on its own. */
    String linkPin() {
        return sp.getString("linkPin", "");
    }

    void setLinkPin(String pin) {
        sp.edit().putString("linkPin", pin == null ? "" : pin).apply();
    }

    // ------------------------------------------------------------------ photo backup

    boolean backupOn() { return sp.getBoolean("backupOn", false); }
    void setBackupOn(boolean v) { sp.edit().putBoolean("backupOn", v).apply(); }

    String backupConnection() { return sp.getString("backupConn", ""); }
    void setBackupConnection(String id) { sp.edit().putString("backupConn", id == null ? "" : id).apply(); }

    String backupDest() { return sp.getString("backupDest", ""); }
    void setBackupDest(String v) { sp.edit().putString("backupDest", v == null ? "" : v).apply(); }

    /** Folder names under internal storage that are backed up. */
    java.util.Set<String> backupFolders() {
        return new java.util.LinkedHashSet<>(sp.getStringSet("backupFolders", new java.util.LinkedHashSet<>(java.util.Arrays.asList("DCIM"))));
    }
    void setBackupFolders(java.util.Set<String> v) { sp.edit().putStringSet("backupFolders", new java.util.HashSet<>(v)).apply(); }

    boolean backupByDate() { return sp.getBoolean("backupByDate", true); }
    void setBackupByDate(boolean v) { sp.edit().putBoolean("backupByDate", v).apply(); }

    boolean backupVideos() { return sp.getBoolean("backupVideos", true); }
    void setBackupVideos(boolean v) { sp.edit().putBoolean("backupVideos", v).apply(); }

    boolean backupCharging() { return sp.getBoolean("backupCharging", false); }
    void setBackupCharging(boolean v) { sp.edit().putBoolean("backupCharging", v).apply(); }

    String backupLast() { return sp.getString("backupLast", ""); }
    long backupLastTime() { return sp.getLong("backupLastTime", 0); }
    void setBackupLast(String text, long time) { sp.edit().putString("backupLast", text).putLong("backupLastTime", time).apply(); }

    // ------------------------------------------------------------------ photo cleaner

    /** Photos the person chose to keep, so they aren't suggested again. */
    java.util.Set<String> cleanKept() {
        return new java.util.HashSet<>(sp.getStringSet("cleanKept", new java.util.HashSet<String>()));
    }

    void setCleanKept(java.util.Set<String> keys) {
        sp.edit().putStringSet("cleanKept", new java.util.HashSet<>(keys)).apply();
    }
}
