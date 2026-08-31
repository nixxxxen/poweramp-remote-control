package dev.powerampremote.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;

import java.util.Objects;

/** Two-layer square artwork surface with one shared swipe/button transition animator. */
public final class ArtworkTransitionFrameLayout extends SquareArtworkFrameLayout {
    interface GestureListener {
        boolean isNavigationAvailable();

        ArtworkPresentationStore.Entry findCachedNeighbor(
                ArtworkNavigationCoordinator.Direction direction
        );

        boolean onSwipeCommitted(
                ArtworkNavigationCoordinator.Direction direction,
                String previewContentIdentity
        );
    }

    private enum SpareRole { NONE, PREVIEW, CONFIRMED }

    private static final long CONTENT_TRANSITION_MILLISECONDS = 340L;
    private static final long PENDING_SETTLE_MILLISECONDS = 180L;
    private static final long RETURN_MILLISECONDS = 200L;

    private final AccelerateDecelerateInterpolator interpolator =
            new AccelerateDecelerateInterpolator();

    private ImageView currentLayer;
    private ImageView spareLayer;
    private String currentContentIdentity;
    private String spareContentIdentity;
    private SpareRole spareRole = SpareRole.NONE;
    private ArtworkNavigationCoordinator.Direction spareDirection;
    private ValueAnimator transitionAnimator;
    private GestureListener gestureListener;
    private ArtworkSwipeGesture swipeGesture;
    private float gestureBaseTranslation;
    private ArtworkNavigationCoordinator.Direction gesturePreviewDirection;

    public ArtworkTransitionFrameLayout(Context context) {
        super(context);
    }

    public ArtworkTransitionFrameLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public ArtworkTransitionFrameLayout(
            Context context,
            AttributeSet attributes,
            int defaultStyleAttribute
    ) {
        super(context, attributes, defaultStyleAttribute);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        currentLayer = findViewById(R.id.album_art);
        spareLayer = findViewById(R.id.album_art_incoming);
        if (currentLayer == null || spareLayer == null) {
            throw new IllegalStateException("Artwork transition layers are missing");
        }
        spareLayer.setVisibility(INVISIBLE);
        spareLayer.setAlpha(0f);
    }

    void setGestureListener(GestureListener listener) {
        gestureListener = listener;
    }

    void setInitialContent(Bitmap artwork, String contentIdentity) {
        cancelTransitionAnimator();
        clearSpareLayer();
        setLayerContent(currentLayer, artwork);
        currentContentIdentity = contentIdentity;
        currentLayer.setVisibility(VISIBLE);
        currentLayer.setTranslationX(0f);
        currentLayer.setAlpha(1f);
    }

    boolean hasConfirmedContent(String contentIdentity) {
        return Objects.equals(currentContentIdentity, contentIdentity)
                || spareRole == SpareRole.CONFIRMED
                && Objects.equals(spareContentIdentity, contentIdentity);
    }

    void onNavigationRequested(
            ArtworkNavigationCoordinator.Direction direction,
            Bitmap previewArtwork,
            String previewContentIdentity
    ) {
        prepareForNavigation(direction, previewArtwork, previewContentIdentity);
        if (!animationsEnabled()) {
            clearPreviewLayer();
            setCurrentLayerPosition(0f, 1f);
            return;
        }
        if (spareRole == SpareRole.PREVIEW) {
            ArtworkTransitionGeometry.Frame target = ArtworkTransitionGeometry.carouselFrame(
                    direction,
                    getWidth(),
                    ArtworkTransitionGeometry.cachedPendingOffset(direction, getWidth())
            );
            animateLayersTo(
                    target.outgoingTranslationX,
                    target.outgoingAlpha,
                    target.incomingTranslationX,
                    target.incomingAlpha,
                    PENDING_SETTLE_MILLISECONDS,
                    false
            );
        } else {
            animateCurrentLayerTo(
                    ArtworkTransitionGeometry.pendingOffset(direction, getWidth()),
                    0.94f,
                    PENDING_SETTLE_MILLISECONDS
            );
        }
    }

    void showContent(
            Bitmap artwork,
            String contentIdentity,
            ArtworkNavigationCoordinator.Direction direction,
            boolean animate
    ) {
        cancelTransitionAnimator();
        if (Objects.equals(currentContentIdentity, contentIdentity)) {
            setLayerContent(currentLayer, artwork);
            clearPreviewLayer();
            setCurrentLayerPosition(0f, 1f);
            return;
        }

        boolean matchingPreview = spareRole == SpareRole.PREVIEW
                && Objects.equals(spareContentIdentity, contentIdentity)
                && spareDirection == direction;
        if (matchingPreview) {
            setLayerContent(spareLayer, artwork);
            spareRole = SpareRole.CONFIRMED;
        } else {
            if (spareRole == SpareRole.CONFIRMED) promoteSpareLayer(false);
            else clearSpareLayer();
            setLayerContent(spareLayer, artwork);
            spareContentIdentity = contentIdentity;
            spareDirection = direction;
            spareRole = SpareRole.CONFIRMED;
            spareLayer.setVisibility(VISIBLE);
            if (direction == ArtworkNavigationCoordinator.Direction.NEUTRAL) {
                spareLayer.setTranslationX(0f);
                spareLayer.setAlpha(0f);
            } else {
                applyFrame(ArtworkTransitionGeometry.carouselFrame(
                        direction,
                        getWidth(),
                        currentLayer.getTranslationX()
                ));
            }
        }

        if (!animate || !animationsEnabled() || getWidth() <= 0) {
            promoteSpareLayer(true);
            return;
        }

        float outgoingStartTranslation = currentLayer.getTranslationX();
        float incomingStartTranslation = spareLayer.getTranslationX();
        currentLayer.setAlpha(1f);
        if (direction != ArtworkNavigationCoordinator.Direction.NEUTRAL) {
            spareLayer.setAlpha(1f);
        }
        applyFrame(ArtworkTransitionGeometry.frame(
                direction,
                getWidth(),
                outgoingStartTranslation,
                incomingStartTranslation,
                0f
        ));
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        transitionAnimator = animator;
        animator.setDuration(CONTENT_TRANSITION_MILLISECONDS);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(valueAnimator -> {
            if (transitionAnimator != valueAnimator) return;
            if (!animationsEnabled()) {
                cancelTransitionAnimator();
                promoteSpareLayer(true);
                return;
            }
            applyFrame(ArtworkTransitionGeometry.frame(
                    direction,
                    getWidth(),
                    outgoingStartTranslation,
                    incomingStartTranslation,
                    (float) valueAnimator.getAnimatedValue()
            ));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (transitionAnimator != animation) return;
                transitionAnimator = null;
                promoteSpareLayer(true);
            }
        });
        animator.start();
    }

    void updateContent(Bitmap artwork, String contentIdentity) {
        if (spareRole == SpareRole.CONFIRMED
                && Objects.equals(spareContentIdentity, contentIdentity)) {
            setLayerContent(spareLayer, artwork);
        } else if (Objects.equals(currentContentIdentity, contentIdentity)) {
            setLayerContent(currentLayer, artwork);
        }
    }

    void rejectPreview() {
        cancelTransitionAnimator();
        ArtworkNavigationCoordinator.Direction rejectedDirection = spareRole == SpareRole.PREVIEW
                ? spareDirection : null;
        clearPreviewLayer();
        gesturePreviewDirection = null;
        if (rejectedDirection == null) return;
        if (!animationsEnabled()) {
            setCurrentLayerPosition(0f, 1f);
        } else {
            animateCurrentLayerTo(
                    ArtworkTransitionGeometry.pendingOffset(rejectedDirection, getWidth()),
                    0.94f,
                    PENDING_SETTLE_MILLISECONDS
            );
        }
    }

    void restoreConfirmedContent(boolean animate) {
        cancelTransitionAnimator();
        if (spareRole == SpareRole.CONFIRMED) promoteSpareLayer(false);
        if (spareRole == SpareRole.PREVIEW) {
            if (!animate || !animationsEnabled()) {
                clearPreviewLayer();
                setCurrentLayerPosition(0f, 1f);
                return;
            }
            ArtworkTransitionGeometry.Frame target = ArtworkTransitionGeometry.carouselFrame(
                    spareDirection,
                    getWidth(),
                    0f
            );
            animateLayersTo(
                    target.outgoingTranslationX,
                    target.outgoingAlpha,
                    target.incomingTranslationX,
                    target.incomingAlpha,
                    RETURN_MILLISECONDS,
                    true
            );
            return;
        }
        if (!animate || !animationsEnabled()) {
            setCurrentLayerPosition(0f, 1f);
            return;
        }
        animateCurrentLayerTo(0f, 1f, RETURN_MILLISECONDS);
    }

    void onHostStop() {
        cancelSwipeGesture();
        cancelTransitionAnimator();
        if (spareRole == SpareRole.CONFIRMED) promoteSpareLayer(true);
        else clearPreviewLayer();
        setCurrentLayerPosition(0f, 1f);
    }

    static boolean animationsEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        GestureListener listener = gestureListener;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (listener == null || !listener.isNavigationAvailable()) return false;
                prepareLatestConfirmedLayer();
                gestureBaseTranslation = currentLayer.getTranslationX();
                gesturePreviewDirection = null;
                swipeGesture = new ArtworkSwipeGesture(
                        event.getX(),
                        event.getY(),
                        getWidth(),
                        ViewConfiguration.get(getContext()).getScaledTouchSlop()
                );
                return true;
            case MotionEvent.ACTION_MOVE:
                if (swipeGesture == null) return false;
                ArtworkSwipeGesture.Move move = swipeGesture.move(
                        event.getX(),
                        event.getY(),
                        event.getPointerCount(),
                        listener != null && listener.isNavigationAvailable()
                );
                if (move.performHaptic) performThresholdHaptic();
                if (move.cancelled) {
                    cancelSwipeGesture();
                    restoreConfirmedContent(true);
                } else if (move.trackingHorizontal) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    ArtworkNavigationCoordinator.Direction direction = move.translationX < 0f
                            ? ArtworkNavigationCoordinator.Direction.NEXT
                            : ArtworkNavigationCoordinator.Direction.PREVIOUS;
                    ensureGesturePreview(listener, direction);
                    if (spareRole == SpareRole.PREVIEW
                            && spareDirection == direction) {
                        applyFrame(ArtworkTransitionGeometry.carouselFrame(
                                direction,
                                getWidth(),
                                gestureBaseTranslation + move.translationX
                        ));
                    } else {
                        setCurrentLayerPosition(
                                gestureBaseTranslation
                                        + ArtworkTransitionGeometry.rubberBandTranslation(
                                        move.translationX,
                                        getWidth()
                                ),
                                1f
                        );
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (swipeGesture == null) return false;
                ArtworkSwipeGesture.Finish finish = swipeGesture.finish(
                        event.getX(),
                        event.getY(),
                        event.getPointerCount(),
                        listener != null && listener.isNavigationAvailable()
                );
                swipeGesture = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (finish.performHaptic) performThresholdHaptic();
                String committedPreviewIdentity = spareRole == SpareRole.PREVIEW
                        && spareDirection == finish.direction
                        ? spareContentIdentity : null;
                if (finish.committed && listener != null
                        && listener.onSwipeCommitted(
                        finish.direction,
                        committedPreviewIdentity
                )) {
                    return true;
                }
                restoreConfirmedContent(true);
                performClick();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeGesture == null) return false;
                cancelSwipeGesture();
                restoreConfirmedContent(true);
                return true;
            default:
                return swipeGesture != null;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        onHostStop();
        super.onDetachedFromWindow();
    }

    private void ensureGesturePreview(
            GestureListener listener,
            ArtworkNavigationCoordinator.Direction direction
    ) {
        if (gesturePreviewDirection == direction) return;
        clearPreviewLayer();
        gesturePreviewDirection = direction;
        if (listener == null) return;
        ArtworkPresentationStore.Entry preview = listener.findCachedNeighbor(direction);
        if (preview == null || preview.artwork == null) return;
        installPreview(preview.artwork, preview.contentIdentity, direction);
    }

    private void prepareForNavigation(
            ArtworkNavigationCoordinator.Direction direction,
            Bitmap previewArtwork,
            String previewContentIdentity
    ) {
        cancelTransitionAnimator();
        if (spareRole == SpareRole.CONFIRMED) promoteSpareLayer(false);
        boolean keepGesturePreview = spareRole == SpareRole.PREVIEW
                && spareDirection == direction
                && Objects.equals(spareContentIdentity, previewContentIdentity)
                && previewArtwork != null;
        if (!keepGesturePreview) {
            clearPreviewLayer();
            if (previewArtwork != null && previewContentIdentity != null) {
                installPreview(previewArtwork, previewContentIdentity, direction);
            }
        }
        currentLayer.setVisibility(VISIBLE);
        currentLayer.setAlpha(1f);
    }

    private void prepareLatestConfirmedLayer() {
        cancelTransitionAnimator();
        if (spareRole == SpareRole.CONFIRMED) promoteSpareLayer(false);
        else clearPreviewLayer();
        currentLayer.setVisibility(VISIBLE);
        currentLayer.setAlpha(1f);
    }

    private void installPreview(
            Bitmap artwork,
            String contentIdentity,
            ArtworkNavigationCoordinator.Direction direction
    ) {
        clearSpareLayer();
        setLayerContent(spareLayer, artwork);
        spareContentIdentity = contentIdentity;
        spareDirection = direction;
        spareRole = SpareRole.PREVIEW;
        spareLayer.setVisibility(VISIBLE);
        applyFrame(ArtworkTransitionGeometry.carouselFrame(
                direction,
                getWidth(),
                currentLayer.getTranslationX()
        ));
    }

    private void animateCurrentLayerTo(float targetTranslation, float targetAlpha, long duration) {
        cancelTransitionAnimator();
        float startTranslation = currentLayer.getTranslationX();
        float startAlpha = currentLayer.getAlpha();
        if (Math.abs(startTranslation - targetTranslation) < 0.5f
                && Math.abs(startAlpha - targetAlpha) < 0.01f) {
            setCurrentLayerPosition(targetTranslation, targetAlpha);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        transitionAnimator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(valueAnimator -> {
            if (transitionAnimator != valueAnimator) return;
            float progress = (float) valueAnimator.getAnimatedValue();
            setCurrentLayerPosition(
                    lerp(startTranslation, targetTranslation, progress),
                    lerp(startAlpha, targetAlpha, progress)
            );
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (transitionAnimator != animation) return;
                transitionAnimator = null;
                setCurrentLayerPosition(targetTranslation, targetAlpha);
            }
        });
        animator.start();
    }

    private void animateLayersTo(
            float targetCurrentTranslation,
            float targetCurrentAlpha,
            float targetSpareTranslation,
            float targetSpareAlpha,
            long duration,
            boolean clearPreviewOnEnd
    ) {
        cancelTransitionAnimator();
        float startCurrentTranslation = currentLayer.getTranslationX();
        float startCurrentAlpha = currentLayer.getAlpha();
        float startSpareTranslation = spareLayer.getTranslationX();
        float startSpareAlpha = spareLayer.getAlpha();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        transitionAnimator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(valueAnimator -> {
            if (transitionAnimator != valueAnimator) return;
            float progress = (float) valueAnimator.getAnimatedValue();
            currentLayer.setTranslationX(lerp(
                    startCurrentTranslation,
                    targetCurrentTranslation,
                    progress
            ));
            currentLayer.setAlpha(lerp(startCurrentAlpha, targetCurrentAlpha, progress));
            spareLayer.setTranslationX(lerp(
                    startSpareTranslation,
                    targetSpareTranslation,
                    progress
            ));
            spareLayer.setAlpha(lerp(startSpareAlpha, targetSpareAlpha, progress));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (transitionAnimator != animation) return;
                transitionAnimator = null;
                setCurrentLayerPosition(targetCurrentTranslation, targetCurrentAlpha);
                spareLayer.setTranslationX(targetSpareTranslation);
                spareLayer.setAlpha(targetSpareAlpha);
                if (clearPreviewOnEnd) clearPreviewLayer();
            }
        });
        animator.start();
    }

    private void applyFrame(ArtworkTransitionGeometry.Frame frame) {
        currentLayer.setTranslationX(frame.outgoingTranslationX);
        currentLayer.setAlpha(frame.outgoingAlpha);
        spareLayer.setTranslationX(frame.incomingTranslationX);
        spareLayer.setAlpha(frame.incomingAlpha);
    }

    private void promoteSpareLayer(boolean normalize) {
        if (spareRole != SpareRole.CONFIRMED) return;
        ImageView outgoing = currentLayer;
        currentLayer = spareLayer;
        spareLayer = outgoing;
        currentContentIdentity = spareContentIdentity;
        spareContentIdentity = null;
        spareDirection = null;
        spareRole = SpareRole.NONE;
        clearLayer(spareLayer);
        spareLayer.setVisibility(INVISIBLE);
        spareLayer.setTranslationX(0f);
        spareLayer.setAlpha(0f);
        currentLayer.setVisibility(VISIBLE);
        if (normalize) setCurrentLayerPosition(0f, 1f);
    }

    private void clearPreviewLayer() {
        if (spareRole == SpareRole.PREVIEW) clearSpareLayer();
    }

    private void clearSpareLayer() {
        spareContentIdentity = null;
        spareDirection = null;
        spareRole = SpareRole.NONE;
        clearLayer(spareLayer);
        spareLayer.setVisibility(INVISIBLE);
        spareLayer.setTranslationX(0f);
        spareLayer.setAlpha(0f);
    }

    private void cancelSwipeGesture() {
        if (swipeGesture != null) swipeGesture.cancel();
        swipeGesture = null;
        gesturePreviewDirection = null;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void cancelTransitionAnimator() {
        ValueAnimator animator = transitionAnimator;
        transitionAnimator = null;
        if (animator != null) animator.cancel();
    }

    private void setCurrentLayerPosition(float translationX, float alpha) {
        currentLayer.setTranslationX(translationX);
        currentLayer.setAlpha(alpha);
    }

    private void performThresholdHaptic() {
        performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CLOCK_TICK);
    }

    private void setLayerContent(ImageView layer, Bitmap artwork) {
        if (artwork == null) {
            int padding = getResources().getDimensionPixelSize(
                    R.dimen.album_placeholder_padding
            );
            layer.setPadding(padding, padding, padding, padding);
            layer.setImageResource(R.drawable.ic_album_placeholder);
        } else {
            layer.setPadding(0, 0, 0, 0);
            layer.setImageBitmap(artwork);
        }
    }

    private static void clearLayer(ImageView layer) {
        layer.setImageDrawable(null);
    }

    private static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }
}
