package com.fastshare.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** A rounded horizontal bar split into coloured parts, like the usage bars in phone storage settings. */
public class UsageBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private long[] values = new long[0];
    private int[] colors = new int[0];
    private int trackColor = 0x1F808080;

    public UsageBar(Context c) {
        super(c);
    }

    public UsageBar(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setSegments(long[] values, int[] colors, int trackColor) {
        this.values = values;
        this.colors = colors;
        this.trackColor = trackColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        clip.reset();
        rect.set(0, 0, w, h);
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setColor(trackColor);
        canvas.drawRect(0, 0, w, h, paint);
        long total = 0;
        for (long v : values) total += v;
        if (total > 0) {
            float x = 0;
            for (int i = 0; i < values.length; i++) {
                float seg = w * values[i] / total;
                if (seg <= 0) continue;
                paint.setColor(colors[i]);
                canvas.drawRect(x, 0, Math.min(w, x + seg), h, paint);
                x += seg;
            }
        }
        canvas.restore();
    }
}
