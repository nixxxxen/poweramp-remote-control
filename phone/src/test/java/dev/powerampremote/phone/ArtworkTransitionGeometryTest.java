package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ArtworkTransitionGeometryTest {
    @Test
    public void nextExitsLeftAndEntersFromRight() {
        ArtworkTransitionGeometry.Frame start = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.NEXT,
                100f,
                -16f,
                0f
        );
        ArtworkTransitionGeometry.Frame end = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.NEXT,
                100f,
                -16f,
                1f
        );

        assertEquals(-16f, start.outgoingTranslationX, 0.001f);
        assertEquals(100f, start.incomingTranslationX, 0.001f);
        assertEquals(-100f, end.outgoingTranslationX, 0.001f);
        assertEquals(0f, end.incomingTranslationX, 0.001f);
    }

    @Test
    public void previousExitsRightAndEntersFromLeft() {
        ArtworkTransitionGeometry.Frame start = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                100f,
                16f,
                0f
        );
        ArtworkTransitionGeometry.Frame end = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                100f,
                16f,
                1f
        );

        assertEquals(16f, start.outgoingTranslationX, 0.001f);
        assertEquals(-100f, start.incomingTranslationX, 0.001f);
        assertEquals(100f, end.outgoingTranslationX, 0.001f);
        assertEquals(0f, end.incomingTranslationX, 0.001f);
    }

    @Test
    public void rapidRetargetStartsAtCurrentVisualTranslation() {
        ArtworkTransitionGeometry.Frame interrupted = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.NEXT,
                100f,
                -16f,
                0.4f
        );
        ArtworkTransitionGeometry.Frame retargeted = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                100f,
                interrupted.incomingTranslationX,
                0f
        );

        assertEquals(
                interrupted.incomingTranslationX,
                retargeted.outgoingTranslationX,
                0.001f
        );
    }

    @Test
    public void cachedNeighborMovesBesideCurrentArtworkAsOneCarousel() {
        ArtworkTransitionGeometry.Frame next = ArtworkTransitionGeometry.carouselFrame(
                ArtworkNavigationCoordinator.Direction.NEXT,
                100f,
                -30f
        );
        ArtworkTransitionGeometry.Frame previous = ArtworkTransitionGeometry.carouselFrame(
                ArtworkNavigationCoordinator.Direction.PREVIOUS,
                100f,
                25f
        );

        assertEquals(-30f, next.outgoingTranslationX, 0.001f);
        assertEquals(70f, next.incomingTranslationX, 0.001f);
        assertEquals(25f, previous.outgoingTranslationX, 0.001f);
        assertEquals(-75f, previous.incomingTranslationX, 0.001f);
    }

    @Test
    public void noCacheRubberBandKeepsMostOfCurrentArtworkVisible() {
        float shortDrag = ArtworkTransitionGeometry.rubberBandTranslation(24f, 100f);
        float veryLongDrag = ArtworkTransitionGeometry.rubberBandTranslation(500f, 100f);
        float negativeDrag = ArtworkTransitionGeometry.rubberBandTranslation(-500f, 100f);

        assertTrue(shortDrag > 0f);
        assertTrue(veryLongDrag >= shortDrag);
        assertTrue(veryLongDrag <= 20f);
        assertEquals(-veryLongDrag, negativeDrag, 0.001f);
        assertEquals(
                -16f,
                ArtworkTransitionGeometry.pendingOffset(
                        ArtworkNavigationCoordinator.Direction.NEXT,
                        100f
                ),
                0.001f
        );
    }

    @Test
    public void confirmedPreviewTransitionContinuesFromCurrentCarouselPositions() {
        ArtworkTransitionGeometry.Frame start = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.NEXT,
                100f,
                -28f,
                72f,
                0f
        );
        ArtworkTransitionGeometry.Frame end = ArtworkTransitionGeometry.frame(
                ArtworkNavigationCoordinator.Direction.NEXT,
                100f,
                -28f,
                72f,
                1f
        );

        assertEquals(-28f, start.outgoingTranslationX, 0.001f);
        assertEquals(72f, start.incomingTranslationX, 0.001f);
        assertEquals(-100f, end.outgoingTranslationX, 0.001f);
        assertEquals(0f, end.incomingTranslationX, 0.001f);
    }
}
