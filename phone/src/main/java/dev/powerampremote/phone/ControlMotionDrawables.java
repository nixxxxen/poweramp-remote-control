package dev.powerampremote.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/** Small drawable-only animations that leave button geometry and touch targets fixed. */
final class ControlMotionDrawables {
    interface MotionLifecycle {
        void stopMotion();
    }

    static final class PlayPause extends BinaryMorphDrawable {
        private final Path morphPath = new Path();

        PlayPause(float density) {
            super(density, 28f, 190L, Paint.Style.FILL);
        }

        @Override
        protected void drawGlyph(Canvas canvas, Rect bounds, Paint paint, float progress) {
            float left = bounds.left;
            float top = bounds.top;
            float width = bounds.width();
            float height = bounds.height();

            morphPath.reset();
            if (progress <= 0f) {
                morphPath.moveTo(x(left, width, 8f), y(top, height, 5f));
                morphPath.lineTo(x(left, width, 19f), y(top, height, 12f));
                morphPath.lineTo(x(left, width, 8f), y(top, height, 19f));
                morphPath.close();
                canvas.drawPath(morphPath, paint);
                return;
            }

            morphPath.moveTo(x(left, width, lerp(8f, 6f, progress)),
                    y(top, height, lerp(5f, 5f, progress)));
            morphPath.lineTo(x(left, width, lerp(12.7f, 10f, progress)),
                    y(top, height, lerp(8f, 5f, progress)));
            morphPath.lineTo(x(left, width, lerp(12.7f, 10f, progress)),
                    y(top, height, lerp(16f, 19f, progress)));
            morphPath.lineTo(x(left, width, lerp(8f, 6f, progress)),
                    y(top, height, lerp(19f, 19f, progress)));
            morphPath.close();

            morphPath.moveTo(x(left, width, lerp(12.7f, 14f, progress)),
                    y(top, height, lerp(8f, 5f, progress)));
            morphPath.lineTo(x(left, width, lerp(19f, 18f, progress)),
                    y(top, height, lerp(12f, 5f, progress)));
            morphPath.lineTo(x(left, width, lerp(19f, 18f, progress)),
                    y(top, height, lerp(12f, 19f, progress)));
            morphPath.lineTo(x(left, width, lerp(12.7f, 14f, progress)),
                    y(top, height, lerp(16f, 19f, progress)));
            morphPath.close();

            canvas.drawPath(morphPath, paint);
        }
    }

    static final class Shuffle extends BinaryMorphDrawable {
        private final Path upperPath = new Path();
        private final Path lowerPath = new Path();

        Shuffle(float density) {
            super(density, 24f, 220L, Paint.Style.STROKE);
        }

        @Override
        protected void drawGlyph(Canvas canvas, Rect bounds, Paint paint, float progress) {
            float left = bounds.left;
            float top = bounds.top;
            float width = bounds.width();
            float height = bounds.height();
            paint.setStrokeWidth(Math.max(1f, Math.min(width, height) / 12f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float upperEndY = lerp(7.2f, 16.8f, progress);
            float lowerEndY = lerp(16.8f, 7.2f, progress);
            upperPath.reset();
            upperPath.moveTo(x(left, width, 4.2f), y(top, height, 7.2f));
            upperPath.cubicTo(
                    x(left, width, 8f), y(top, height, 7.2f),
                    x(left, width, 12.4f), y(top, height, upperEndY),
                    x(left, width, 16f), y(top, height, upperEndY)
            );
            addArrowHead(upperPath, left, top, width, height, upperEndY);

            lowerPath.reset();
            lowerPath.moveTo(x(left, width, 4.2f), y(top, height, 16.8f));
            lowerPath.cubicTo(
                    x(left, width, 8f), y(top, height, 16.8f),
                    x(left, width, 12.4f), y(top, height, lowerEndY),
                    x(left, width, 16f), y(top, height, lowerEndY)
            );
            addArrowHead(lowerPath, left, top, width, height, lowerEndY);

            canvas.drawPath(upperPath, paint);
            canvas.drawPath(lowerPath, paint);
        }

        private static void addArrowHead(
                Path path,
                float left,
                float top,
                float width,
                float height,
                float endY
        ) {
            path.moveTo(x(left, width, 16.7f), y(top, height, endY - 2.2f));
            path.lineTo(x(left, width, 19.6f), y(top, height, endY));
            path.lineTo(x(left, width, 16.7f), y(top, height, endY + 2.2f));
        }
    }

    static final class Pulse extends Drawable implements MotionLifecycle, Drawable.Callback {
        private static final long PULSE_DURATION_MILLISECONDS = 520L;
        private static final float PEAK_SCALE = 1.14f;
        private static final float PEAK_PROGRESS = 0.42f;

        private final Drawable child;
        private final DecelerateInterpolator interpolator = new DecelerateInterpolator();
        private ValueAnimator animator;
        private float glyphScale = 1f;

        Pulse(Drawable child) {
            if (child == null) throw new IllegalArgumentException("Pulse child is required");
            this.child = child.mutate();
            this.child.setCallback(this);
        }

        void pulse(boolean animationsEnabled) {
            cancelAnimator();
            if (!animationsEnabled) {
                glyphScale = 1f;
                invalidateSelf();
                return;
            }
            float startScale = glyphScale;
            ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
            animator = next;
            next.setDuration(PULSE_DURATION_MILLISECONDS);
            next.setInterpolator(interpolator);
            next.addUpdateListener(valueAnimator -> {
                if (animator != valueAnimator) return;
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    animator = null;
                    valueAnimator.cancel();
                    glyphScale = 1f;
                    invalidateSelf();
                    return;
                }
                float progress = (float) valueAnimator.getAnimatedValue();
                if (progress < PEAK_PROGRESS) {
                    glyphScale = lerp(
                            startScale,
                            PEAK_SCALE,
                            progress / PEAK_PROGRESS
                    );
                } else {
                    glyphScale = lerp(
                            PEAK_SCALE,
                            1f,
                            (progress - PEAK_PROGRESS) / (1f - PEAK_PROGRESS)
                    );
                }
                invalidateSelf();
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator != animation) return;
                    animator = null;
                    glyphScale = 1f;
                    invalidateSelf();
                }
            });
            next.start();
        }

        void reset() {
            cancelAnimator();
            glyphScale = 1f;
            invalidateSelf();
        }

        @Override
        public void stopMotion() {
            reset();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int saveCount = canvas.save();
            canvas.scale(glyphScale, glyphScale, bounds.exactCenterX(), bounds.exactCenterY());
            child.draw(canvas);
            canvas.restoreToCount(saveCount);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            child.setBounds(bounds);
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean changed = child.setState(state);
            if (changed) invalidateSelf();
            return changed;
        }

        @Override
        public boolean isStateful() {
            return child.isStateful();
        }

        @Override
        public int getIntrinsicWidth() {
            return child.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return child.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            child.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            child.setColorFilter(colorFilter);
        }

        @Override
        public void setTintList(ColorStateList tint) {
            child.setTintList(tint);
        }

        @Override
        public void setTintMode(PorterDuff.Mode tintMode) {
            child.setTintMode(tintMode);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }

        private void cancelAnimator() {
            ValueAnimator previous = animator;
            animator = null;
            if (previous != null) previous.cancel();
        }
    }

    static final class Nudge extends Drawable implements MotionLifecycle, Drawable.Callback {
        private static final long NUDGE_DURATION_MILLISECONDS = 180L;
        private static final float PEAK_PROGRESS = 0.42f;
        private static final float MAX_TRANSLATION_FRACTION = 0.11f;

        private final Drawable child;
        private final float direction;
        private final DecelerateInterpolator interpolator = new DecelerateInterpolator();
        private ValueAnimator animator;
        private float translationX;

        Nudge(Drawable child, int direction) {
            if (child == null) throw new IllegalArgumentException("Nudge child is required");
            if (direction == 0) throw new IllegalArgumentException("Nudge direction is required");
            this.child = child.mutate();
            this.child.setCallback(this);
            this.direction = direction < 0 ? -1f : 1f;
        }

        void nudge(boolean animationsEnabled) {
            cancelAnimator();
            if (!animationsEnabled) {
                translationX = 0f;
                invalidateSelf();
                return;
            }
            float startTranslation = translationX;
            ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
            animator = next;
            next.setDuration(NUDGE_DURATION_MILLISECONDS);
            next.setInterpolator(interpolator);
            next.addUpdateListener(valueAnimator -> {
                if (animator != valueAnimator) return;
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    animator = null;
                    valueAnimator.cancel();
                    translationX = 0f;
                    invalidateSelf();
                    return;
                }
                float progress = (float) valueAnimator.getAnimatedValue();
                float peakTranslation = direction * getBounds().width()
                        * MAX_TRANSLATION_FRACTION;
                if (progress < PEAK_PROGRESS) {
                    translationX = lerp(
                            startTranslation,
                            peakTranslation,
                            progress / PEAK_PROGRESS
                    );
                } else {
                    translationX = lerp(
                            peakTranslation,
                            0f,
                            (progress - PEAK_PROGRESS) / (1f - PEAK_PROGRESS)
                    );
                }
                invalidateSelf();
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator != animation) return;
                    animator = null;
                    translationX = 0f;
                    invalidateSelf();
                }
            });
            next.start();
        }

        @Override
        public void stopMotion() {
            cancelAnimator();
            translationX = 0f;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            int saveCount = canvas.save();
            canvas.translate(translationX, 0f);
            child.draw(canvas);
            canvas.restoreToCount(saveCount);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            child.setBounds(bounds);
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean changed = child.setState(state);
            if (changed) invalidateSelf();
            return changed;
        }

        @Override
        public boolean isStateful() {
            return child.isStateful();
        }

        @Override
        public int getIntrinsicWidth() {
            return child.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return child.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            child.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            child.setColorFilter(colorFilter);
        }

        @Override
        public void setTintList(ColorStateList tint) {
            child.setTintList(tint);
        }

        @Override
        public void setTintMode(PorterDuff.Mode tintMode) {
            child.setTintMode(tintMode);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }

        private void cancelAnimator() {
            ValueAnimator previous = animator;
            animator = null;
            if (previous != null) previous.cancel();
        }
    }

    abstract static class BinaryMorphDrawable extends Drawable
            implements MotionLifecycle {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final DecelerateInterpolator interpolator = new DecelerateInterpolator();
        private final int intrinsicSizePixels;
        private final long fullDurationMilliseconds;
        private ValueAnimator animator;
        private float progress;
        private float targetProgress;
        private ColorStateList tintList;
        private PorterDuff.Mode tintMode = PorterDuff.Mode.SRC_IN;
        private ColorFilter explicitColorFilter;
        private PorterDuffColorFilter tintFilter;

        BinaryMorphDrawable(
                float density,
                float intrinsicSizeDp,
                long fullDurationMilliseconds,
                Paint.Style style
        ) {
            intrinsicSizePixels = Math.max(1, Math.round(intrinsicSizeDp * density));
            this.fullDurationMilliseconds = fullDurationMilliseconds;
            paint.setColor(0xffffffff);
            paint.setStyle(style);
        }

        final float progress() {
            return progress;
        }

        final void setProgressImmediately(float target) {
            cancelAnimator();
            targetProgress = clamp(target);
            progress = targetProgress;
            invalidateSelf();
        }

        final void animateTo(float target, boolean animationsEnabled) {
            float resolvedTarget = clamp(target);
            cancelAnimator();
            targetProgress = resolvedTarget;
            float distance = Math.abs(resolvedTarget - progress);
            if (!animationsEnabled || distance <= 0.001f) {
                progress = resolvedTarget;
                invalidateSelf();
                return;
            }

            ValueAnimator next = ValueAnimator.ofFloat(progress, resolvedTarget);
            animator = next;
            next.setDuration(Math.max(60L, Math.round(fullDurationMilliseconds * distance)));
            next.setInterpolator(interpolator);
            next.addUpdateListener(valueAnimator -> {
                if (animator != valueAnimator) return;
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    animator = null;
                    valueAnimator.cancel();
                    progress = targetProgress;
                    invalidateSelf();
                    return;
                }
                progress = (float) valueAnimator.getAnimatedValue();
                invalidateSelf();
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator != animation) return;
                    animator = null;
                    progress = targetProgress;
                    invalidateSelf();
                }
            });
            next.start();
        }

        @Override
        public final void stopMotion() {
            setProgressImmediately(targetProgress);
        }

        @Override
        public final void draw(Canvas canvas) {
            drawGlyph(canvas, getBounds(), paint, progress);
        }

        protected abstract void drawGlyph(
                Canvas canvas,
                Rect bounds,
                Paint paint,
                float progress
        );

        @Override
        public final int getIntrinsicWidth() {
            return intrinsicSizePixels;
        }

        @Override
        public final int getIntrinsicHeight() {
            return intrinsicSizePixels;
        }

        @Override
        public final void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public final void setColorFilter(ColorFilter colorFilter) {
            explicitColorFilter = colorFilter;
            applyColorFilter();
            invalidateSelf();
        }

        @Override
        public final void setTintList(ColorStateList tint) {
            tintList = tint;
            updateTint(getState());
        }

        @Override
        public final void setTintMode(PorterDuff.Mode tintMode) {
            this.tintMode = tintMode == null ? PorterDuff.Mode.SRC_IN : tintMode;
            updateTint(getState());
        }

        @Override
        protected final boolean onStateChange(int[] state) {
            return updateTint(state);
        }

        @Override
        public final boolean isStateful() {
            return tintList != null && tintList.isStateful();
        }

        @Override
        @SuppressWarnings("deprecation")
        public final int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        private void cancelAnimator() {
            ValueAnimator previous = animator;
            animator = null;
            if (previous != null) previous.cancel();
        }

        private boolean updateTint(int[] state) {
            PorterDuffColorFilter previous = tintFilter;
            tintFilter = tintList == null ? null : new PorterDuffColorFilter(
                    tintList.getColorForState(state, tintList.getDefaultColor()),
                    tintMode
            );
            applyColorFilter();
            invalidateSelf();
            return previous != tintFilter;
        }

        private void applyColorFilter() {
            paint.setColorFilter(explicitColorFilter == null ? tintFilter : explicitColorFilter);
        }
    }

    private ControlMotionDrawables() {
    }

    private static float x(float left, float width, float viewportX) {
        return left + width * viewportX / 24f;
    }

    private static float y(float top, float height, float viewportY) {
        return top + height * viewportY / 24f;
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
