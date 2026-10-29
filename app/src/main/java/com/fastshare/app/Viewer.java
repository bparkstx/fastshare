package com.fastshare.app;

import android.app.Activity;
import android.content.Intent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decides which files FileBandit shows itself, and opens the viewer. */
final class Viewer {
    private Viewer() { }

    enum Type { IMAGE, VIDEO, AUDIO, PDF, TEXT }

    /** Photos in the same folder, so the viewer can swipe between them. Kept in memory, not in the Intent. */
    static List<File> sessionFiles = new ArrayList<>();

    static Type typeOf(File f) {
        String ext = FileOps.extension(f.getName());
        if (ext.equals("pdf")) return Type.PDF;
        if (ext.equals("svg")) return null;
        switch (FileOps.kindOfName(f.getName())) {
            case IMAGE: return Type.IMAGE;
            case VIDEO: return Type.VIDEO;
            case AUDIO: return Type.AUDIO;
            case TEXT: return Type.TEXT;
            default: break;
        }
        // small extensionless files such as README, LICENSE, Makefile
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (!n.contains(".") && f.length() < 200_000 && (n.equals("readme") || n.equals("license") || n.equals("makefile")
                || n.equals("changelog") || n.equals("notice"))) return Type.TEXT;
        return null;
    }

    /** Opens the built-in viewer, or another app if FileBandit can't show this kind of file. */
    static void open(Activity a, File f, List<File> neighbours) {
        if (typeOf(f) == null) {
            FileActions.open(a, f);
            return;
        }
        sessionFiles = new ArrayList<>();
        if (neighbours != null && typeOf(f) == Type.IMAGE) {
            for (File n : neighbours) if (typeOf(n) == Type.IMAGE) sessionFiles.add(n);
        }
        if (!sessionFiles.contains(f)) {
            sessionFiles.clear();
            sessionFiles.add(f);
        }
        a.startActivity(new Intent(a, ViewerActivity.class).putExtra(ViewerActivity.EXTRA_PATH, f.getPath()));
    }
}
