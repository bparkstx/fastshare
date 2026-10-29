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

    /** PIN the iPhone/PC page asks for, or empty when the link works on its own. */
    String linkPin() {
        return sp.getString("linkPin", "");
    }

    void setLinkPin(String pin) {
        sp.edit().putString("linkPin", pin == null ? "" : pin).apply();
    }
}
