package dev.powerampremote.phone;

import java.util.Objects;

/** Pure latest-intent policy for remote track navigation and confirmed artwork delivery. */
final class ArtworkNavigationCoordinator {
    enum Direction { PREVIOUS, NEXT, NEUTRAL }

    enum Source { BUTTON, SWIPE, EXTERNAL }

    enum Command { PREVIOUS, NEXT, NONE }

    enum DisplayMode { KEEP_CURRENT, IMMEDIATE, TRANSITION, UPDATE, IGNORE }

    static final class Navigation {
        final boolean accepted;
        final Command command;
        final Direction direction;
        final Source source;
        final long generation;
        final String fromArtworkIdentity;

        private Navigation(
                boolean accepted,
                Command command,
                Direction direction,
                Source source,
                long generation,
                String fromArtworkIdentity
        ) {
            this.accepted = accepted;
            this.command = command;
            this.direction = direction;
            this.source = source;
            this.generation = generation;
            this.fromArtworkIdentity = fromArtworkIdentity;
        }
    }

    static final class ArtworkRequest {
        final long generation;
        final String trackIdentity;
        final String artworkIdentity;
        final Direction direction;
        final Source source;
        final boolean immediate;
        final long navigationGeneration;
        final String originArtworkIdentity;
        final String expectedPreviewIdentity;
        final boolean relationReliable;

        private ArtworkRequest(
                long generation,
                String trackIdentity,
                String artworkIdentity,
                Direction direction,
                Source source,
                boolean immediate,
                long navigationGeneration,
                String originArtworkIdentity,
                String expectedPreviewIdentity,
                boolean relationReliable
        ) {
            this.generation = generation;
            this.trackIdentity = trackIdentity;
            this.artworkIdentity = artworkIdentity;
            this.direction = direction;
            this.source = source;
            this.immediate = immediate;
            this.navigationGeneration = navigationGeneration;
            this.originArtworkIdentity = originArtworkIdentity;
            this.expectedPreviewIdentity = expectedPreviewIdentity;
            this.relationReliable = relationReliable;
        }
    }

    static final class StateUpdate {
        final boolean changed;
        final boolean trackChanged;
        final boolean navigationRelationReady;
        final boolean previewMismatch;
        final ArtworkRequest artworkRequest;

        private StateUpdate(
                boolean changed,
                boolean trackChanged,
                boolean navigationRelationReady,
                boolean previewMismatch,
                ArtworkRequest artworkRequest
        ) {
            this.changed = changed;
            this.trackChanged = trackChanged;
            this.navigationRelationReady = navigationRelationReady;
            this.previewMismatch = previewMismatch;
            this.artworkRequest = artworkRequest;
        }
    }

    static final class Display {
        final DisplayMode mode;
        final Direction direction;
        final Source source;

        private Display(DisplayMode mode, Direction direction, Source source) {
            this.mode = mode;
            this.direction = direction;
            this.source = source;
        }
    }

    private long navigationGeneration;
    private long artworkGeneration;
    private boolean hasConfirmedState;
    private String confirmedTrackIdentity;
    private String confirmedArtworkIdentity;
    private Direction pendingDirection;
    private Source pendingSource;
    private String pendingExpectedPreviewIdentity;
    private boolean pendingRelationReliable;
    private ArtworkRequest currentArtworkRequest;
    private boolean currentRequestContentApplied;
    private boolean currentRequestDirectionSuppressed;
    private boolean hasDisplayedContent;
    private String displayedContentIdentity;

    Navigation requestNavigation(Direction direction, Source source, boolean controlsAvailable) {
        if (!controlsAvailable || direction == null || direction == Direction.NEUTRAL
                || source == null || source == Source.EXTERNAL) {
            return new Navigation(
                    false,
                    Command.NONE,
                    direction,
                    source,
                    navigationGeneration,
                    confirmedArtworkIdentity
            );
        }
        boolean firstPendingCommand = pendingDirection == null;
        navigationGeneration++;
        pendingDirection = direction;
        pendingSource = source;
        pendingExpectedPreviewIdentity = null;
        pendingRelationReliable = firstPendingCommand;
        return new Navigation(
                true,
                direction == Direction.PREVIOUS ? Command.PREVIOUS : Command.NEXT,
                direction,
                source,
                navigationGeneration,
                confirmedArtworkIdentity
        );
    }

    void attachExpectedPreview(long expectedNavigationGeneration, String contentIdentity) {
        if (expectedNavigationGeneration == navigationGeneration && pendingDirection != null) {
            pendingExpectedPreviewIdentity = contentIdentity;
        }
    }

    StateUpdate confirmState(
            String trackIdentity,
            String artworkIdentity,
            boolean binderReplay,
            boolean animationsEnabled
    ) {
        boolean first = !hasConfirmedState;
        boolean trackChanged = !first
                && !Objects.equals(confirmedTrackIdentity, trackIdentity);
        boolean artworkChanged = !first
                && !Objects.equals(confirmedArtworkIdentity, artworkIdentity);
        if (!first && !trackChanged && !artworkChanged) {
            return new StateUpdate(false, false, false, false, currentArtworkRequest);
        }

        String previousArtworkIdentity = confirmedArtworkIdentity;
        boolean pendingTrackAwaitingArtwork = artworkChanged
                && pendingDirection != null
                && currentArtworkRequest != null
                && !currentRequestContentApplied
                && currentArtworkRequest.navigationGeneration == navigationGeneration;
        Direction direction = pendingDirection != null
                && (trackChanged || pendingTrackAwaitingArtwork)
                ? pendingDirection : Direction.NEUTRAL;
        Source source = direction == Direction.NEUTRAL ? Source.EXTERNAL : pendingSource;
        long requestNavigationGeneration = direction == Direction.NEUTRAL
                ? -1L : navigationGeneration;
        String originArtworkIdentity;
        String expectedPreviewIdentity;
        boolean relationReliable;
        if (direction == Direction.NEUTRAL) {
            originArtworkIdentity = null;
            expectedPreviewIdentity = null;
            relationReliable = false;
        } else if (trackChanged) {
            originArtworkIdentity = previousArtworkIdentity;
            expectedPreviewIdentity = pendingExpectedPreviewIdentity;
            relationReliable = pendingRelationReliable;
        } else {
            originArtworkIdentity = currentArtworkRequest == null
                    ? null : currentArtworkRequest.originArtworkIdentity;
            expectedPreviewIdentity = currentArtworkRequest == null
                    ? pendingExpectedPreviewIdentity
                    : currentArtworkRequest.expectedPreviewIdentity;
            relationReliable = currentArtworkRequest != null
                    && currentArtworkRequest.relationReliable;
        }

        hasConfirmedState = true;
        confirmedTrackIdentity = trackIdentity;
        confirmedArtworkIdentity = artworkIdentity;
        currentArtworkRequest = new ArtworkRequest(
                ++artworkGeneration,
                trackIdentity,
                artworkIdentity,
                direction,
                source,
                first || binderReplay || !animationsEnabled,
                requestNavigationGeneration,
                originArtworkIdentity,
                expectedPreviewIdentity,
                relationReliable
        );
        currentRequestContentApplied = false;
        currentRequestDirectionSuppressed = false;
        boolean previewMismatch = expectedPreviewIdentity != null
                && artworkIdentity != null
                && !Objects.equals(expectedPreviewIdentity, artworkIdentity);
        boolean navigationRelationReady = relationReliable
                && isDirectional(direction)
                && originArtworkIdentity != null
                && artworkIdentity != null
                && !Objects.equals(originArtworkIdentity, artworkIdentity);
        return new StateUpdate(
                true,
                trackChanged,
                navigationRelationReady,
                previewMismatch,
                currentArtworkRequest
        );
    }

    Display onArtworkCleared(ArtworkRequest request) {
        return accepts(request)
                ? new Display(DisplayMode.KEEP_CURRENT, request.direction, request.source)
                : ignoredDisplay();
    }

    Display onArtworkReady(
            ArtworkRequest request,
            String deliveredArtworkIdentity,
            boolean animationsEnabled
    ) {
        if (!accepts(request)
                || request.artworkIdentity == null
                || !Objects.equals(request.artworkIdentity, deliveredArtworkIdentity)) {
            return ignoredDisplay();
        }
        return applyContent(request, deliveredArtworkIdentity, animationsEnabled);
    }

    Display onArtworkUnavailable(
            ArtworkRequest request,
            String placeholderIdentity,
            boolean animationsEnabled
    ) {
        if (!accepts(request) || placeholderIdentity == null) return ignoredDisplay();
        return applyContent(request, placeholderIdentity, animationsEnabled);
    }

    void seedDisplayedContent(String contentIdentity) {
        if (contentIdentity == null) return;
        hasDisplayedContent = true;
        displayedContentIdentity = contentIdentity;
    }

    boolean abortNavigation() {
        boolean hadPendingNavigation = pendingDirection != null;
        if (hadPendingNavigation && currentArtworkRequest != null
                && currentArtworkRequest.navigationGeneration == navigationGeneration) {
            currentRequestDirectionSuppressed = true;
        }
        clearPendingNavigation();
        return hadPendingNavigation;
    }

    boolean abortNavigation(long expectedGeneration) {
        return expectedGeneration == navigationGeneration && abortNavigation();
    }

    boolean hasPendingNavigation() {
        return pendingDirection != null;
    }

    boolean needsArtworkFallback(ArtworkRequest request) {
        return accepts(request) && !currentRequestContentApplied;
    }

    int pendingVisualIntentCountForTesting() {
        return pendingDirection == null ? 0 : 1;
    }

    boolean accepts(ArtworkRequest request) {
        return request != null
                && currentArtworkRequest != null
                && request.generation == currentArtworkRequest.generation
                && Objects.equals(request.trackIdentity, confirmedTrackIdentity)
                && Objects.equals(request.artworkIdentity, confirmedArtworkIdentity);
    }

    void reset() {
        navigationGeneration++;
        artworkGeneration++;
        hasConfirmedState = false;
        confirmedTrackIdentity = null;
        confirmedArtworkIdentity = null;
        clearPendingNavigation();
        currentArtworkRequest = null;
        currentRequestContentApplied = false;
        currentRequestDirectionSuppressed = false;
        hasDisplayedContent = false;
        displayedContentIdentity = null;
    }

    private Display applyContent(
            ArtworkRequest request,
            String contentIdentity,
            boolean animationsEnabled
    ) {
        boolean sameContent = hasDisplayedContent
                && Objects.equals(displayedContentIdentity, contentIdentity);
        if (currentRequestContentApplied && sameContent) {
            return new Display(DisplayMode.UPDATE, Direction.NEUTRAL, Source.EXTERNAL);
        }

        Direction direction = currentRequestContentApplied || currentRequestDirectionSuppressed
                ? Direction.NEUTRAL : request.direction;
        Source source = direction == Direction.NEUTRAL ? Source.EXTERNAL : request.source;
        DisplayMode mode = request.immediate || !animationsEnabled
                || !hasDisplayedContent || sameContent
                ? DisplayMode.IMMEDIATE : DisplayMode.TRANSITION;
        hasDisplayedContent = true;
        displayedContentIdentity = contentIdentity;
        currentRequestContentApplied = true;
        if (request.navigationGeneration == navigationGeneration) clearPendingNavigation();
        return new Display(mode, direction, source);
    }

    private void clearPendingNavigation() {
        pendingDirection = null;
        pendingSource = null;
        pendingExpectedPreviewIdentity = null;
        pendingRelationReliable = false;
    }

    private static boolean isDirectional(Direction direction) {
        return direction == Direction.NEXT || direction == Direction.PREVIOUS;
    }

    private static Display ignoredDisplay() {
        return new Display(DisplayMode.IGNORE, Direction.NEUTRAL, Source.EXTERNAL);
    }
}
