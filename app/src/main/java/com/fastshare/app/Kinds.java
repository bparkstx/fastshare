package com.fastshare.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

/** Icon and colour for each kind of file. */
final class Kinds {
    private Kinds() { }

    @DrawableRes
    static int icon(FileOps.Kind k) {
        switch (k) {
            case FOLDER: return R.drawable.ic_folder;
            case IMAGE: return R.drawable.ic_image;
            case VIDEO: return R.drawable.ic_video;
            case AUDIO: return R.drawable.ic_audio;
            case DOCUMENT: return R.drawable.ic_doc;
            case ARCHIVE: return R.drawable.ic_archive;
            case APK: return R.drawable.ic_apk;
            case TEXT: return R.drawable.ic_doc;
            default: return R.drawable.ic_file;
        }
    }

    @ColorRes
    private static int colorRes(FileOps.Kind k) {
        switch (k) {
            case FOLDER: return R.color.kind_folder;
            case IMAGE: return R.color.kind_image;
            case VIDEO: return R.color.kind_video;
            case AUDIO: return R.color.kind_audio;
            case DOCUMENT: return R.color.kind_document;
            case ARCHIVE: return R.color.kind_archive;
            case APK: return R.color.kind_apk;
            case TEXT: return R.color.kind_text;
            default: return R.color.kind_other;
        }
    }

    static int color(Context c, FileOps.Kind k) {
        return ContextCompat.getColor(c, colorRes(k));
    }

    /** Soft, tinted rounded square (or circle) to sit behind an icon. */
    static GradientDrawable badge(int color, float radiusPx, boolean circle) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(ColorUtils.setAlphaComponent(color, 0x2E));
        return d;
    }

    static GradientDrawable solid(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radiusPx);
        d.setColor(color);
        return d;
    }
}
