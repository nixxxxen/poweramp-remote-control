package dev.powerampremote.phone;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageButton;

/** ImageButton that releases drawable-owned motion when its presentation host detaches. */
public final class MotionImageButton extends ImageButton {
    public MotionImageButton(Context context) {
        super(context);
    }

    public MotionImageButton(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public MotionImageButton(
            Context context,
            AttributeSet attributes,
            int defaultStyleAttribute
    ) {
        super(context, attributes, defaultStyleAttribute);
    }

    void stopImageMotion() {
        Drawable drawable = getDrawable();
        if (drawable instanceof ControlMotionDrawables.MotionLifecycle) {
            ((ControlMotionDrawables.MotionLifecycle) drawable).stopMotion();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopImageMotion();
        super.onDetachedFromWindow();
    }
}
