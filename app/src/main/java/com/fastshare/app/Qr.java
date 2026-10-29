package com.fastshare.app;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

final class Qr {
    private Qr() { }

    /** Draws a QR code at the largest whole-pixel scale that fits in {@code px}, so the edges stay crisp. */
    static Bitmap make(String text, int px) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints);
            int modules = m.getWidth();
            int scale = Math.max(1, px / modules);
            int size = modules * scale;
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = m.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, size, 0, 0, size, size);
            return bmp;
        } catch (WriterException e) {
            return null;
        }
    }
}
