package dev.powerampremote.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** GPU-friendly dark gradient and softly moving color fields for the main player only. */
public final class ArtworkThemeBackgroundView extends View {
    private static final long PALETTE_TRANSITION_MILLISECONDS = 900L;
    private static final long MOTION_CYCLE_MILLISECONDS = 36_000L;
    private static final float TWO_PI = (float) (Math.PI * 2d);

    private final Paint basePaint = new Paint();
    private final Paint contrastPaint = new Paint(Paint.DITHER_FLAG);
    private final AccelerateDecelerateInterpolator transitionInterpolator =
            new AccelerateDecelerateInterpolator();

    private ArtworkPalette transitionStart = ArtworkPalette.FALLBACK;
    private ArtworkPalette transitionEnd = ArtworkPalette.FALLBACK;
    private float transitionProgress = 1f;
    private float motionPhase;
    private ThemeLayer startLayer;
    private ThemeLayer endLayer;
    private ValueAnimator paletteAnimator;
    private ValueAnimator motionAnimator;
    private boolean hostVisible;

    public ArtworkThemeBackgroundView(Context context) {
        this(context, null);
    }

    public ArtworkThemeBackgroundView(Context context, AttributeSet attributes) {
        this(context, attributes, 0);
    }

    public ArtworkThemeBackgroundView(
            Context context,
            AttributeSet attributes,
            int defaultStyleAttribute
    ) {
        super(context, attributes, defaultStyleAttribute);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        basePaint.setColor(0xff080c11);
    }

    void onHostStart() {
        hostVisible = true;
        if (!animationsEnabled()) {
            applyImmediately(transitionEnd);
            return;
        }
        if (transitionProgress < 1f && !transitionStart.equals(transitionEnd)) {
            startPaletteAnimator();
        }
        startMotionAnimator();
    }

    void onHostStop() {
        hostVisible = false;
        freezePaletteTransition();
        stopMotionAnimator();
    }

    void setPalette(ArtworkPalette palette) {
        ArtworkPaletteTransition.Plan plan = ArtworkPaletteTransition.retarget(
                transitionStart,
                transitionEnd,
                transitionProgress,
                palette,
                hostVisible && animationsEnabled()
        );
        cancelPaletteAnimator();
        transitionStart = plan.start;
        transitionEnd = plan.end;
        transitionProgress = plan.animate ? 0f : 1f;
        rebuildLayers();
        invalidate();
        if (plan.animate) startPaletteAnimator();
    }

    void restoreState(
            ArtworkPalette current,
            ArtworkPalette target,
            float restoredMotionPhase
    ) {
        cancelPaletteAnimator();
        transitionStart = current == null ? ArtworkPalette.FALLBACK : current;
        transitionEnd = target == null ? transitionStart : target;
        transitionProgress = transitionStart.equals(transitionEnd) ? 1f : 0f;
        motionPhase = normalizePhase(restoredMotionPhase);
        rebuildLayers();
        invalidate();
    }

    ArtworkPalette currentPaletteSnapshot() {
        return ArtworkPalette.interpolate(
                transitionStart,
                transitionEnd,
                transitionProgress
        );
    }

    ArtworkPalette targetPaletteSnapshot() {
        return transitionEnd;
    }

    float motionPhaseSnapshot() {
        return motionPhase;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        rebuildLayers();
        if (height > 0) {
            contrastPaint.setShader(new LinearGradient(
                    0f,
                    0f,
                    0f,
                    height,
                    new int[]{0x52070a0e, 0x70070a0e, 0x8a070a0e},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP
            ));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), basePaint);
        if (transitionProgress >= 1f || transitionStart.equals(transitionEnd)) {
            drawLayer(canvas, endLayer, 1f);
        } else {
            drawLayer(canvas, startLayer, 1f - transitionProgress);
            drawLayer(canvas, endLayer, transitionProgress);
        }
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), contrastPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        hostVisible = false;
        cancelPaletteAnimator();
        stopMotionAnimator();
        super.onDetachedFromWindow();
    }

    private void startPaletteAnimator() {
        if (!hostVisible || !animationsEnabled()
                || transitionProgress >= 1f || transitionStart.equals(transitionEnd)) {
            if (!animationsEnabled()) applyImmediately(transitionEnd);
            return;
        }
        cancelPaletteAnimator();
        float initialProgress = transitionProgress;
        ValueAnimator animator = ValueAnimator.ofFloat(initialProgress, 1f);
        paletteAnimator = animator;
        animator.setDuration(Math.max(
                1L,
                Math.round(PALETTE_TRANSITION_MILLISECONDS * (1f - initialProgress))
        ));
        animator.setInterpolator(transitionInterpolator);
        animator.addUpdateListener(valueAnimator -> {
            if (!animationsEnabled()) {
                applyImmediately(transitionEnd);
                stopMotionAnimator();
                return;
            }
            transitionProgress = (float) valueAnimator.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (paletteAnimator != animation) return;
                paletteAnimator = null;
                transitionStart = transitionEnd;
                transitionProgress = 1f;
                startLayer = endLayer;
                invalidate();
            }
        });
        animator.start();
    }

    private void startMotionAnimator() {
        if (!hostVisible || !animationsEnabled() || motionAnimator != null) return;
        float initialPhase = motionPhase;
        ValueAnimator animator = ValueAnimator.ofFloat(initialPhase, initialPhase + 1f);
        motionAnimator = animator;
        animator.setDuration(MOTION_CYCLE_MILLISECONDS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(null);
        animator.addUpdateListener(valueAnimator -> {
            if (!animationsEnabled()) {
                stopMotionAnimator();
                return;
            }
            motionPhase = normalizePhase((float) valueAnimator.getAnimatedValue());
            invalidate();
        });
        animator.start();
    }

    private void freezePaletteTransition() {
        if (paletteAnimator == null) return;
        ArtworkPalette current = currentPaletteSnapshot();
        ArtworkPalette target = transitionEnd;
        cancelPaletteAnimator();
        transitionStart = current;
        transitionEnd = target;
        transitionProgress = current.equals(target) ? 1f : 0f;
        rebuildLayers();
    }

    private void applyImmediately(ArtworkPalette palette) {
        cancelPaletteAnimator();
        ArtworkPalette resolved = palette == null ? ArtworkPalette.FALLBACK : palette;
        transitionStart = resolved;
        transitionEnd = resolved;
        transitionProgress = 1f;
        rebuildLayers();
        invalidate();
    }

    private void cancelPaletteAnimator() {
        ValueAnimator animator = paletteAnimator;
        paletteAnimator = null;
        if (animator != null) animator.cancel();
    }

    private void stopMotionAnimator() {
        ValueAnimator animator = motionAnimator;
        motionAnimator = null;
        if (animator != null) animator.cancel();
    }

    private void rebuildLayers() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            startLayer = null;
            endLayer = null;
            return;
        }
        startLayer = ThemeLayer.create(transitionStart, getWidth(), getHeight());
        endLayer = transitionStart.equals(transitionEnd)
                ? startLayer : ThemeLayer.create(transitionEnd, getWidth(), getHeight());
    }

    private void drawLayer(Canvas canvas, ThemeLayer layer, float alpha) {
        if (layer == null || alpha <= 0f) return;
        int layerAlpha = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        layer.gradientPaint.setAlpha(layerAlpha);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), layer.gradientPaint);

        float angle = motionPhase * TWO_PI;
        drawBlob(
                canvas,
                layer.blobPaints[0],
                layer.radius,
                getWidth() * (0.18f + 0.09f * (float) Math.sin(angle)),
                getHeight() * (0.22f + 0.07f * (float) Math.cos(angle * 0.83f)),
                layerAlpha
        );
        drawBlob(
                canvas,
                layer.blobPaints[1],
                layer.radius,
                getWidth() * (0.78f + 0.08f * (float) Math.cos(angle * 0.71f)),
                getHeight() * (0.46f + 0.08f * (float) Math.sin(angle * 0.67f)),
                layerAlpha
        );
        drawBlob(
                canvas,
                layer.blobPaints[2],
                layer.radius,
                getWidth() * (0.42f + 0.10f * (float) Math.sin(angle * 0.59f + 1.7f)),
                getHeight() * (0.82f + 0.06f * (float) Math.cos(angle * 0.77f + 0.8f)),
                layerAlpha
        );
    }

    private static void drawBlob(
            Canvas canvas,
            Paint paint,
            float radius,
            float centerX,
            float centerY,
            int alpha
    ) {
        paint.setAlpha(alpha);
        int saveCount = canvas.save();
        canvas.translate(centerX, centerY);
        canvas.drawCircle(0f, 0f, radius, paint);
        canvas.restoreToCount(saveCount);
    }

    private static boolean animationsEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    private static float normalizePhase(float value) {
        float normalized = value % 1f;
        return normalized < 0f ? normalized + 1f : normalized;
    }

    private static final class ThemeLayer {
        final Paint gradientPaint;
        final Paint[] blobPaints;
        final float radius;

        ThemeLayer(Paint gradientPaint, Paint[] blobPaints, float radius) {
            this.gradientPaint = gradientPaint;
            this.blobPaints = blobPaints;
            this.radius = radius;
        }

        static ThemeLayer create(ArtworkPalette palette, int width, int height) {
            Paint gradient = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            gradient.setShader(new LinearGradient(
                    0f,
                    0f,
                    width,
                    height,
                    new int[]{
                            ArtworkPalette.withAlpha(palette.colorAt(0), 132),
                            ArtworkPalette.withAlpha(palette.colorAt(1), 106),
                            ArtworkPalette.withAlpha(palette.colorAt(2), 82),
                            0x00080c11
                    },
                    new float[]{0f, 0.36f, 0.72f, 1f},
                    Shader.TileMode.CLAMP
            ));

            float radius = Math.max(width, height) * 0.68f;
            Paint[] blobs = new Paint[3];
            for (int index = 0; index < blobs.length; index++) {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
                int color = palette.colorAt(index);
                paint.setShader(new RadialGradient(
                        0f,
                        0f,
                        radius,
                        new int[]{
                                ArtworkPalette.withAlpha(color, 106),
                                ArtworkPalette.withAlpha(color, 48),
                                ArtworkPalette.withAlpha(color, 0)
                        },
                        new float[]{0f, 0.48f, 1f},
                        Shader.TileMode.CLAMP
                ));
                blobs[index] = paint;
            }
            return new ThemeLayer(gradient, blobs, radius);
        }
    }
}
