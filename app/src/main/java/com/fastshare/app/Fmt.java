package com.fastshare.app;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Small formatting helpers shared by the UI and the server. */
public final class Fmt {
    private Fmt() { }

    /** Decimal units (1 MB = 1,000,000 bytes), the same way Android's own storage screens count. */
    public static String bytes(long b) {
        if (b < 0) return "";
        if (b < 1000) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1000 && i < u.length - 1) {
            v /= 1000;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    public static String speed(long bytes, long nanos) {
        double sec = Math.max(nanos / 1e9, 0.001);
        return String.format(Locale.US, "%.1f MB/s", bytes / 1e6 / sec);
    }

    public static String date(long millis) {
        if (millis <= 0) return "";
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(millis));
    }

    public static String dateTime(long millis) {
        if (millis <= 0) return "";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(millis));
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago", or the date for older things. */
    public static String ago(long then, long now) {
        long d = Math.max(0, now - then);
        long min = d / 60_000;
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 24) return h + " h ago";
        long days = h / 24;
        if (days < 14) return days + (days == 1 ? " day ago" : " days ago");
        return date(then);
    }

    public static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
