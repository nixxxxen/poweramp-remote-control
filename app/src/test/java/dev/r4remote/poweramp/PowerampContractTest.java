package dev.r4remote.poweramp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PowerampContractTest {
    @Test
    public void playbackCommandsMatchOfficialIntentApi() {
        assertEquals(1, PowerampContract.COMMAND_TOGGLE_PLAY_PAUSE);
        assertEquals(2, PowerampContract.COMMAND_PAUSE);
        assertEquals(3, PowerampContract.COMMAND_PLAY);
        assertEquals(4, PowerampContract.COMMAND_NEXT);
        assertEquals(5, PowerampContract.COMMAND_PREVIOUS);
        assertEquals(9, PowerampContract.COMMAND_SHUFFLE);
        assertEquals(15, PowerampContract.COMMAND_SEEK);
        assertEquals(16, PowerampContract.COMMAND_POSITION_SYNC);
        assertEquals(18, PowerampContract.COMMAND_LIKE);
        assertEquals(19, PowerampContract.COMMAND_UNLIKE);
        assertEquals(24, PowerampContract.COMMAND_SET_RATING);
        assertEquals("pos", PowerampContract.Track.POSITION_SECONDS);
    }

    @Test
    public void playingModeContractMatchesOfficialIntentApi() {
        assertEquals(
                "com.maxmpz.audioplayer.PLAYING_MODE_CHANGED",
                PowerampContract.ACTION_PLAYING_MODE_CHANGED
        );
        assertEquals("shuffle", PowerampContract.EXTRA_SHUFFLE);
        assertEquals(0, PowerampContract.ShuffleModes.NONE);
        assertEquals(2, PowerampContract.ShuffleModes.SONGS);
    }
}
