package dev.powerampremote.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Flexible artwork container whose measured width and height are always identical. */
public final class SquareArtworkFrameLayout extends FrameLayout {
    public SquareArtworkFrameLayout(Context context) {
        super(context);
    }

    public SquareArtworkFrameLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public SquareArtworkFrameLayout(
            Context context,
            AttributeSet attributes,
            int defaultStyleAttribute
    ) {
        super(context, attributes, defaultStyleAttribute);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int size = Math.max(0, Math.min(getMeasuredWidth(), getMeasuredHeight()));
        int squareSpec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
        super.onMeasure(squareSpec, squareSpec);
        setMeasuredDimension(size, size);
    }
}
