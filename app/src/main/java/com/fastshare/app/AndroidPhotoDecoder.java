package com.fastshare.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;

/** Reads a photo at a small size (never the full picture) as greyscale, for the photo cleaner. */
final class AndroidPhotoDecoder implements PhotoAnalysis.Decoder {
    private static final int MAX_SIDE = 512;

    @Override
    public PhotoAnalysis.Gray decode(File f) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not a picture: " + f.getName());
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("Couldn't read " + f.getName());
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            int[] argb = new int[w * h];
            b.getPixels(argb, 0, w, 0, 0, w, h);
            int[] gray = new int[w * h];
            for (int i = 0; i < gray.length; i++) {
                int c = argb[i];
                gray[i] = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            }
            return new PhotoAnalysis.Gray(w, h, gray, bounds.outWidth, bounds.outHeight);
        } finally {
            b.recycle();
        }
    }
}
