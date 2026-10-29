package com.fastshare.app;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/** An image view whose height always equals its width (thumbnail grid cells). */
public class SquareImageView extends AppCompatImageView {
    public SquareImageView(Context c) {
        super(c);
    }

    public SquareImageView(Context c, AttributeSet a) {
        super(c, a);
    }

    public SquareImageView(Context c, AttributeSet a, int d) {
        super(c, a, d);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
