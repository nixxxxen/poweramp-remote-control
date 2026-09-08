package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MiniPlayerPresentationTest {
    @Test
    public void playingAndPausedFollowOnlyConfirmedState() {
        MiniPlayerPresentation presentation = new MiniPlayerPresentation();
        presentation.updateConnection(true);

        MiniPlayerPresentation.Model playing = presentation.updateState(state(
                41L, "First", "Artist", "playing", true, true
        ));
        assertTrue(playing.visible);
        assertTrue(playing.controlsEnabled);
        assertTrue(playing.playing);

        MiniPlayerPresentation.Model paused = presentation.updateState(state(
                41L, "First", "Artist", "paused", true, true
        ));
        assertTrue(paused.visible);
        assertTrue(paused.controlsEnabled);
        assertFalse(paused.playing);
    }

    @Test
    public void disconnectedSnapshotStaysVisibleButDisablesControls() {
        MiniPlayerPresentation presentation = new MiniPlayerPresentation();
        presentation.updateConnection(true);
        presentation.updateState(state(41L, "First", "Artist", "playing", true, true));

        MiniPlayerPresentation.Model disconnected = presentation.updateConnection(false);

        assertTrue(disconnected.visible);
        assertFalse(disconnected.controlsEnabled);
        assertTrue(disconnected.playing);
        assertEquals("First", disconnected.title);
    }

    @Test
    public void noTrackAndResetHideMiniPlayer() {
        MiniPlayerPresentation presentation = new MiniPlayerPresentation();
        presentation.updateConnection(true);
        presentation.updateState(state(41L, "First", "Artist", "paused", true, true));

        assertFalse(presentation.updateState(
                state(null, null, null, null, false, false)
        ).visible);
        assertFalse(presentation.reset().visible);
    }

    @Test
    public void identityChangeClearsOldArtworkUntilNewDelivery() {
        MiniPlayerPresentation presentation = new MiniPlayerPresentation();
        presentation.updateConnection(true);
        presentation.updateState(state(41L, "First", "Artist", "playing", true, true));
        assertTrue(presentation.updateArtwork(true).artworkVisible);

        MiniPlayerPresentation.Model changed = presentation.updateState(state(
                42L, "Second", "Artist", "playing", true, true
        ));

        assertEquals("Second", changed.title);
        assertFalse(changed.artworkVisible);
        assertTrue(presentation.updateArtwork(true).artworkVisible);
    }

    @Test
    public void artworkRemovalForSameIdentityClearsPresentation() {
        MiniPlayerPresentation presentation = new MiniPlayerPresentation();
        presentation.updateState(state(41L, "First", "Artist", "paused", true, true));
        assertTrue(presentation.updateArtwork(true).artworkVisible);

        assertFalse(presentation.updateState(
                state(41L, "First", "Artist", "paused", true, false)
        ).artworkVisible);
    }

    private static RemoteState state(
            Long realId,
            String title,
            String artist,
            String playback,
            boolean hasTrack,
            boolean hasArtwork
    ) {
        StringBuilder json = new StringBuilder("{")
                .append("\"apiVersion\":1,\"revision\":1,")
                .append("\"powerampAvailable\":true,\"hasTrack\":")
                .append(hasTrack);
        if (realId != null) {
            json.append(",\"trackId\":").append(realId)
                    .append(",\"trackRealId\":").append(realId);
        }
        if (title != null) json.append(",\"title\":\"").append(title).append('"');
        if (artist != null) json.append(",\"artist\":\"").append(artist).append('"');
        if (playback != null) {
            json.append(",\"playbackState\":\"").append(playback).append('"');
        }
        if (hasArtwork) json.append(",\"artwork\":\"/api/v1/artwork\"");
        return RemoteStateParser.parse(json.append('}').toString());
    }
}
