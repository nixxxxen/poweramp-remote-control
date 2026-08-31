package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ArtworkNavigationCoordinatorTest {
    @Test
    public void buttonsAndSwipesUseTheSameNavigationCommandPath() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();

        ArtworkNavigationCoordinator.Navigation button = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        ArtworkNavigationCoordinator.Navigation swipe = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );

        assertTrue(button.accepted);
        assertTrue(swipe.accepted);
        assertEquals(ArtworkNavigationCoordinator.Command.NEXT, button.command);
        assertEquals(button.command, swipe.command);
        assertEquals(button.direction, swipe.direction);
    }

    @Test
    public void rapidInputKeepsOneLatestVisualIntentButAcceptsEveryCommand() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation first = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        ArtworkNavigationCoordinator.Navigation second = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );
        ArtworkNavigationCoordinator.Navigation third = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );

        assertTrue(first.accepted);
        assertTrue(second.accepted);
        assertTrue(third.accepted);
        assertTrue(first.generation < second.generation);
        assertTrue(second.generation < third.generation);
        assertEquals(1, coordinator.pendingVisualIntentCountForTesting());
        assertFalse(coordinator.abortNavigation(first.generation));
        assertEquals(1, coordinator.pendingVisualIntentCountForTesting());

        ArtworkNavigationCoordinator.StateUpdate confirmed = coordinator.confirmState(
                "track-final", "art-final", false, true
        );
        assertEquals(
                ArtworkNavigationCoordinator.Direction.NEXT,
                confirmed.artworkRequest.direction
        );
    }

    @Test
    public void oldTrackArtworkGenerationCannotReplaceLatestConfirmedTrack() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        ArtworkNavigationCoordinator.ArtworkRequest oldRequest = coordinator.confirmState(
                "track-b", "art-b", false, true
        ).artworkRequest;
        coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );
        ArtworkNavigationCoordinator.ArtworkRequest latestRequest = coordinator.confirmState(
                "track-c", "art-c", false, true
        ).artworkRequest;

        assertEquals(
                ArtworkNavigationCoordinator.DisplayMode.IGNORE,
                coordinator.onArtworkReady(oldRequest, "art-b", true).mode
        );
        ArtworkNavigationCoordinator.Display latest = coordinator.onArtworkReady(
                latestRequest, "art-c", true
        );
        assertEquals(ArtworkNavigationCoordinator.DisplayMode.TRANSITION, latest.mode);
        assertEquals(ArtworkNavigationCoordinator.Direction.NEXT, latest.direction);
        assertEquals(0, coordinator.pendingVisualIntentCountForTesting());
    }

    @Test
    public void intermediateNullKeepsOutgoingArtworkUntilReadyOrGraceExpiry() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );
        ArtworkNavigationCoordinator.ArtworkRequest request = coordinator.confirmState(
                "track-b", "art-b", false, true
        ).artworkRequest;

        assertEquals(
                ArtworkNavigationCoordinator.DisplayMode.KEEP_CURRENT,
                coordinator.onArtworkCleared(request).mode
        );
        assertEquals(
                ArtworkNavigationCoordinator.DisplayMode.TRANSITION,
                coordinator.onArtworkUnavailable(request, "placeholder-b", true).mode
        );
    }

    @Test
    public void metadataBeforeArtworkRetainsRequestedDirectionForSameConfirmedTrack() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        ArtworkNavigationCoordinator.ArtworkRequest metadataOnly = coordinator.confirmState(
                "track-b", null, false, true
        ).artworkRequest;
        assertEquals(ArtworkNavigationCoordinator.Direction.NEXT, metadataOnly.direction);

        ArtworkNavigationCoordinator.ArtworkRequest artworkReady = coordinator.confirmState(
                "track-b", "art-b", false, true
        ).artworkRequest;
        ArtworkNavigationCoordinator.Display display = coordinator.onArtworkReady(
                artworkReady, "art-b", true
        );

        assertEquals(ArtworkNavigationCoordinator.Direction.NEXT, artworkReady.direction);
        assertEquals(ArtworkNavigationCoordinator.DisplayMode.TRANSITION, display.mode);
        assertEquals(ArtworkNavigationCoordinator.Direction.NEXT, display.direction);
    }

    @Test
    public void binderReplayAppliesCurrentArtworkWithoutFalseTransition() {
        ArtworkNavigationCoordinator coordinator = new ArtworkNavigationCoordinator();
        coordinator.seedDisplayedContent("art-old");
        ArtworkNavigationCoordinator.ArtworkRequest replay = coordinator.confirmState(
                "track-current", "art-current", true, true
        ).artworkRequest;

        ArtworkNavigationCoordinator.Display display = coordinator.onArtworkReady(
                replay, "art-current", true
        );
        assertEquals(ArtworkNavigationCoordinator.DisplayMode.IMMEDIATE, display.mode);
        assertEquals(ArtworkNavigationCoordinator.Direction.NEUTRAL, display.direction);
    }

    @Test
    public void commandErrorOrDisconnectClearsPendingIntent() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        ArtworkNavigationCoordinator.ArtworkRequest confirmed = coordinator.confirmState(
                "track-b", "art-b", false, true
        ).artworkRequest;
        assertTrue(coordinator.hasPendingNavigation());
        assertTrue(coordinator.abortNavigation());
        assertFalse(coordinator.hasPendingNavigation());
        assertEquals(
                ArtworkNavigationCoordinator.Direction.NEUTRAL,
                coordinator.onArtworkReady(confirmed, "art-b", true).direction
        );
        assertFalse(coordinator.abortNavigation());
    }

    @Test
    public void abortBeforeSnapshotDropsCachedPreviewExpectation() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation navigation = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );
        coordinator.attachExpectedPreview(navigation.generation, "art-b");

        assertTrue(coordinator.abortNavigation());
        ArtworkNavigationCoordinator.StateUpdate update = coordinator.confirmState(
                "track-b", "art-b", false, true
        );

        assertEquals(ArtworkNavigationCoordinator.Direction.NEUTRAL,
                update.artworkRequest.direction);
        assertNull(update.artworkRequest.expectedPreviewIdentity);
        assertFalse(update.previewMismatch);
        assertFalse(update.navigationRelationReady);
    }

    @Test
    public void disabledAnimationsStillAcceptCommandAndApplyFinalArtworkImmediately() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation navigation = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        ArtworkNavigationCoordinator.ArtworkRequest request = coordinator.confirmState(
                "track-b", "art-b", false, false
        ).artworkRequest;

        assertTrue(navigation.accepted);
        assertEquals(ArtworkNavigationCoordinator.Command.NEXT, navigation.command);
        assertEquals(
                ArtworkNavigationCoordinator.DisplayMode.IMMEDIATE,
                coordinator.onArtworkReady(request, "art-b", false).mode
        );
    }

    @Test
    public void oneConfirmedNextExposesExactReliableAdjacencyRecord() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation navigation = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );
        coordinator.attachExpectedPreview(navigation.generation, "art-b");

        ArtworkNavigationCoordinator.StateUpdate update = coordinator.confirmState(
                "track-b", "art-b", false, true
        );

        assertTrue(update.navigationRelationReady);
        assertFalse(update.previewMismatch);
        assertEquals("art-a", update.artworkRequest.originArtworkIdentity);
        assertEquals("art-b", update.artworkRequest.expectedPreviewIdentity);
        assertEquals(ArtworkNavigationCoordinator.Direction.NEXT,
                update.artworkRequest.direction);
    }

    @Test
    public void mismatchedConfirmedIdentityRejectsPredictedPreview() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation navigation = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        coordinator.attachExpectedPreview(navigation.generation, "art-predicted");

        ArtworkNavigationCoordinator.StateUpdate update = coordinator.confirmState(
                "track-actual", "art-actual", false, true
        );

        assertTrue(update.previewMismatch);
        assertTrue(update.navigationRelationReady);
        assertEquals("art-actual", update.artworkRequest.artworkIdentity);
    }

    @Test
    public void cachedArtworkCanApplyAtSnapshotAndLateSameBitmapOnlyUpdatesLayer() {
        ArtworkPresentationStore.Cache<String> cache =
                new ArtworkPresentationStore.Cache<>(3);
        cache.activateServer("server-a");
        cache.putArtwork("server-a", "art-b", "cached-bitmap");
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation navigation = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );
        coordinator.attachExpectedPreview(navigation.generation, "art-b");
        ArtworkNavigationCoordinator.ArtworkRequest request = coordinator.confirmState(
                "track-b", "art-b", false, true
        ).artworkRequest;

        assertEquals(
                "cached-bitmap",
                cache.artwork("server-a", request.artworkIdentity).artwork
        );
        assertEquals(
                ArtworkNavigationCoordinator.DisplayMode.TRANSITION,
                coordinator.onArtworkReady(request, "art-b", true).mode
        );
        assertFalse(coordinator.needsArtworkFallback(request));
        assertEquals(
                ArtworkNavigationCoordinator.DisplayMode.UPDATE,
                coordinator.onArtworkReady(request, "art-b", true).mode
        );
    }

    @Test
    public void rapidInputKeepsOnlyLatestPreviewAndDoesNotLearnAmbiguousAdjacency() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation first = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.NEXT,
                ArtworkNavigationCoordinator.Source.SWIPE,
                true
        );
        coordinator.attachExpectedPreview(first.generation, "art-old-preview");
        ArtworkNavigationCoordinator.Navigation latest = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                ArtworkNavigationCoordinator.Source.BUTTON,
                true
        );
        coordinator.attachExpectedPreview(latest.generation, "art-latest-preview");

        ArtworkNavigationCoordinator.StateUpdate update = coordinator.confirmState(
                "track-latest", "art-latest-preview", false, true
        );

        assertEquals("art-latest-preview", update.artworkRequest.expectedPreviewIdentity);
        assertFalse(update.artworkRequest.relationReliable);
        assertFalse(update.navigationRelationReady);
        assertFalse(update.previewMismatch);
    }

    @Test
    public void externalTrackChangeIsNeutralAndCannotCreateAdjacency() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();

        ArtworkNavigationCoordinator.StateUpdate update = coordinator.confirmState(
                "external-track", "external-art", false, true
        );

        assertTrue(update.trackChanged);
        assertFalse(update.navigationRelationReady);
        assertEquals(
                ArtworkNavigationCoordinator.Direction.NEUTRAL,
                update.artworkRequest.direction
        );
    }

    @Test
    public void unavailableControlsRejectNavigationWithoutPendingState() {
        ArtworkNavigationCoordinator coordinator = readyCoordinator();
        ArtworkNavigationCoordinator.Navigation result = coordinator.requestNavigation(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                ArtworkNavigationCoordinator.Source.SWIPE,
                false
        );

        assertFalse(result.accepted);
        assertEquals(ArtworkNavigationCoordinator.Command.NONE, result.command);
        assertEquals(0, coordinator.pendingVisualIntentCountForTesting());
    }

    private static ArtworkNavigationCoordinator readyCoordinator() {
        ArtworkNavigationCoordinator coordinator = new ArtworkNavigationCoordinator();
        ArtworkNavigationCoordinator.ArtworkRequest initial = coordinator.confirmState(
                "track-a", "art-a", true, true
        ).artworkRequest;
        coordinator.onArtworkReady(initial, "art-a", true);
        return coordinator;
    }
}
