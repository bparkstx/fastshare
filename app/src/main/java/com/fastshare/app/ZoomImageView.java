package com.fastshare.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/** An image that can be pinched, double-tapped and dragged, and that lets a pager swipe once it hits an edge. */
public class ZoomImageView extends AppCompatImageView {

    public interface TapListener {
        void onTap();
    }

    private static final float MAX_ZOOM = 6f;

    private final Matrix m = new Matrix();
    private final float[] vals = new float[9];
    private final RectF rect = new RectF();
    private float fitScale = 1f;
    private float lastX;
    private float lastY;
    private TapListener tapListener;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;

    public ZoomImageView(Context c) {
        this(c, null);
    }

    public ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float cur = scale();
                float target = Math.max(fitScale * 0.95f, Math.min(fitScale * MAX_ZOOM, cur * d.getScaleFactor()));
                float f = target / cur;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixTranslation();
                setImageMatrix(m);
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) tapListener.onTap();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (scale() > fitScale * 1.05f) {
                    fit();
                } else {
                    float f = fitScale * 2.5f / scale();
                    m.postScale(f, f, e.getX(), e.getY());
                    fixTranslation();
                    setImageMatrix(m);
                }
                return true;
            }
        });
    }

    public void setTapListener(TapListener l) {
        tapListener = l;
    }

    /** True while the image is zoomed in beyond "fit to screen". */
    public boolean isZoomed() {
        return scale() > fitScale * 1.02f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        fit();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        fit();
    }

    private void fit() {
        Drawable d = getDrawable();
        int vw = getWidth();
        int vh = getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float s = Math.min((float) vw / dw, (float) vh / dh);
        m.reset();
        m.postScale(s, s);
        m.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f);
        fitScale = s;
        setImageMatrix(m);
    }

    private float scale() {
        m.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    private RectF imageRect() {
        Drawable d = getDrawable();
        rect.set(0, 0, d == null ? 0 : d.getIntrinsicWidth(), d == null ? 0 : d.getIntrinsicHeight());
        m.mapRect(rect);
        return rect;
    }

    /** Stops the image being dragged so far that empty space shows beside it. */
    private void fixTranslation() {
        RectF r = imageRect();
        float dx = 0;
        float dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        m.postTranslate(dx, dy);
    }

    private void claimTouches(boolean claim) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(claim);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (getDrawable() == null) return super.onTouchEvent(ev);
        scaleDetector.onTouchEvent(ev);
        gestures.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = ev.getX();
                lastY = ev.getY();
                claimTouches(isZoomed());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                claimTouches(true);
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int keep = ev.getActionIndex() == 0 ? 1 : 0;
                lastX = ev.getX(keep);
                lastY = ev.getY(keep);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress() || ev.getPointerCount() > 1) {
                    claimTouches(true);
                } else if (isZoomed()) {
                    float dx = ev.getX() - lastX;
                    float dy = ev.getY() - lastY;
                    RectF r = imageRect();
                    boolean pushingPastLeft = dx > 0 && r.left >= -1;
                    boolean pushingPastRight = dx < 0 && r.right <= getWidth() + 1;
                    if (pushingPastLeft || pushingPastRight) {
                        claimTouches(false);            // let the pager swipe to the next photo
                    } else {
                        claimTouches(true);
                        m.postTranslate(dx, dy);
                        fixTranslation();
                        setImageMatrix(m);
                    }
                }
                lastX = ev.getX();
                lastY = ev.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                claimTouches(false);
                break;
            default:
                break;
        }
        return true;
    }
}
