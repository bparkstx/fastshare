package com.fastshare.app;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads small previews for photos and videos without blocking scrolling. */
final class Thumbs {
    private Thumbs() { }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(120);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static String key(File f) {
        return f.getPath() + "@" + f.lastModified();
    }

    static Bitmap cached(File f) {
        return CACHE.get(key(f));
    }

    interface Done {
        void show(Bitmap b);
    }

    /** Fetches a preview in the background; {@code done} runs on the main thread (only if a preview exists). */
    static void load(final File f, final boolean video, final int px, final Done done) {
        final String k = key(f);
        POOL.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = CACHE.get(k);
                if (b == null) {
                    try {
                        Size size = new Size(px, px);
                        b = video
                                ? ThumbnailUtils.createVideoThumbnail(f, size, new CancellationSignal())
                                : ThumbnailUtils.createImageThumbnail(f, size, new CancellationSignal());
                    } catch (Throwable ignored) {
                        b = null;
                    }
                    if (b != null) CACHE.put(k, b);
                }
                final Bitmap result = b;
                if (result != null) {
                    UI.post(new Runnable() {
                        @Override public void run() { done.show(result); }
                    });
                }
            }
        });
    }
}
