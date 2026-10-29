package com.fastshare.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Finds files by type across the whole phone using Android's media index. */
final class MediaQuery {
    private MediaQuery() { }

    enum Category {
        IMAGES("Images", R.drawable.ic_image, FileOps.Kind.IMAGE,
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
        VIDEOS("Videos", R.drawable.ic_video, FileOps.Kind.VIDEO,
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
        AUDIO("Audio", R.drawable.ic_audio, FileOps.Kind.AUDIO,
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO),
        DOCUMENTS("Documents", R.drawable.ic_doc, FileOps.Kind.DOCUMENT,
                "(mime_type='application/pdf' OR mime_type='application/msword' OR mime_type='application/rtf'"
                        + " OR mime_type LIKE 'application/vnd.%' AND mime_type<>'application/vnd.android.package-archive'"
                        + " OR mime_type LIKE 'text/%')"),
        APPS("Apps", R.drawable.ic_apk, FileOps.Kind.APK,
                "mime_type='application/vnd.android.package-archive'"),
        ARCHIVES("Archives", R.drawable.ic_archive, FileOps.Kind.ARCHIVE,
                "(mime_type='application/zip' OR mime_type='application/x-rar-compressed' OR mime_type='application/vnd.rar'"
                        + " OR mime_type='application/x-7z-compressed' OR mime_type='application/x-tar' OR mime_type='application/gzip')");

        final String title;
        final int icon;
        final FileOps.Kind kind;
        final String where;

        Category(String title, int icon, FileOps.Kind kind, String where) {
            this.title = title;
            this.icon = icon;
            this.kind = kind;
            this.where = where;
        }
    }

    /** Newest first. Returns an empty list if the index can't be read. */
    static List<FileOps.Item> items(Context ctx, Category cat, boolean showHidden, int limit) {
        List<FileOps.Item> out = new ArrayList<>();
        Cursor c = null;
        try {
            Uri uri = MediaStore.Files.getContentUri("external");
            Bundle args = new Bundle();
            args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, cat.where);
            args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    new String[]{MediaStore.MediaColumns.DATE_MODIFIED});
            args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
            args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
            c = ctx.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA}, args, null);
            if (c == null) return out;
            while (c.moveToNext()) {
                String path = c.getString(0);
                if (path == null) continue;
                File f = new File(path);
                if (!f.isFile()) continue;
                if (!showHidden && (f.getName().startsWith(".") || path.contains("/."))) continue;
                out.add(FileOps.Item.of(f));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }
}
