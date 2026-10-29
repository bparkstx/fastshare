package com.fastshare.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Shows a photo with four corner handles to drag onto the corners of the page. */
public class CropView extends View {
    private Bitmap bitmap;
    private final double[] qx = new double[4];
    private final double[] qy = new double[4];
    private final Matrix toView = new Matrix();
    private final Matrix toBitmap = new Matrix();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int dragging = -1;
    private final float handleRadius;

    public CropView(Context c) {
        this(c, null);
    }

    public CropView(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        handleRadius = 13 * d;
        line.setColor(Color.parseColor("#1A73E8"));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        dim.setColor(0x88000000);
        handle.setColor(Color.WHITE);
        handleRing.setColor(Color.parseColor("#1A73E8"));
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(3f * d);
    }

    public void setImage(Bitmap b, DocScan.Quad q) {
        bitmap = b;
        setQuad(q);
    }

    public void setQuad(DocScan.Quad q) {
        for (int i = 0; i < 4; i++) {
            qx[i] = q.x[i];
            qy[i] = q.y[i];
        }
        invalidate();
    }

    /** The corners in the picture's own pixels. */
    public DocScan.Quad getQuad() {
        return new DocScan.Quad(qx[0], qy[0], qx[1], qy[1], qx[2], qy[2], qx[3], qy[3]);
    }

    private void layoutMatrix() {
        if (bitmap == null || getWidth() == 0) return;
        RectF src = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        float pad = handleRadius * 1.2f;
        RectF dst = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
        toView.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        toView.invert(toBitmap);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null) return;
        layoutMatrix();
        c.drawBitmap(bitmap, toView, null);
        float[] p = new float[8];
        for (int i = 0; i < 4; i++) {
            p[2 * i] = (float) qx[i];
            p[2 * i + 1] = (float) qy[i];
        }
        toView.mapPoints(p);
        Path outside = new Path();
        outside.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        Path quad = new Path();
        quad.moveTo(p[0], p[1]);
        for (int i = 1; i < 4; i++) quad.lineTo(p[2 * i], p[2 * i + 1]);
        quad.close();
        outside.op(quad, Path.Op.DIFFERENCE);
        c.drawPath(outside, dim);
        c.drawPath(quad, line);
        for (int i = 0; i < 4; i++) {
            c.drawCircle(p[2 * i], p[2 * i + 1], handleRadius, handle);
            c.drawCircle(p[2 * i], p[2 * i + 1], handleRadius, handleRing);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return false;
        layoutMatrix();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float[] p = new float[8];
                for (int i = 0; i < 4; i++) {
                    p[2 * i] = (float) qx[i];
                    p[2 * i + 1] = (float) qy[i];
                }
                toView.mapPoints(p);
                float best = handleRadius * 3.2f;
                dragging = -1;
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(p[2 * i] - e.getX(), p[2 * i + 1] - e.getY());
                    if (d < best) {
                        best = d;
                        dragging = i;
                    }
                }
                return dragging >= 0;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging < 0) return false;
                moveTo(dragging, e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = -1;
                return true;
            default:
                return false;
        }
    }

    /** Moves one corner to a spot on screen, keeping it inside the picture. */
    void moveTo(int corner, float viewX, float viewY) {
        layoutMatrix();
        float[] p = {viewX, viewY};
        toBitmap.mapPoints(p);
        qx[corner] = Math.max(0, Math.min(bitmap.getWidth(), p[0]));
        qy[corner] = Math.max(0, Math.min(bitmap.getHeight(), p[1]));
        invalidate();
    }

    /** Where a corner is on screen. */
    float[] cornerOnScreen(int corner) {
        layoutMatrix();
        float[] p = {(float) qx[corner], (float) qy[corner]};
        toView.mapPoints(p);
        return p;
    }
}
