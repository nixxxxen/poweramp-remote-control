package dev.r4remote.poweramp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RemoteCommandDispatcherTest {
    @Test
    public void routesEveryCommandToExactlyOneTargetMethod() {
        assertDispatch("{\"action\":\"play\"}", "play", -1);
        assertDispatch("{\"action\":\"pause\"}", "pause", -1);
        assertDispatch("{\"action\":\"previous\"}", "previous", -1);
        assertDispatch("{\"action\":\"next\"}", "next", -1);
        assertDispatch("{\"action\":\"seek\",\"value\":37}", "seek", 37);
        assertDispatch("{\"action\":\"shuffle_on\"}", "shuffle:true", -1);
        assertDispatch("{\"action\":\"shuffle_off\"}", "shuffle:false", -1);
        assertDispatch("{\"action\":\"set_rating\",\"value\":3}", "rating", 3);
    }

    @Test
    public void routesDislikeAndLikeAsExactRatingValues() {
        assertDispatch("{\"action\":\"set_rating\",\"value\":1}", "rating", 1);
        assertDispatch("{\"action\":\"set_rating\",\"value\":5}", "rating", 5);
    }

    @Test
    public void forwardsEverySupportedRatingWithoutConversion() {
        for (int rating = 0; rating <= 5; rating++) {
            assertDispatch(
                    "{\"action\":\"set_rating\",\"value\":" + rating + "}",
                    "rating",
                    rating
            );
        }
    }

    private static void assertDispatch(String json, String expectedCall, int expectedValue) {
        RecordingTarget target = new RecordingTarget();

        RemoteCommandDispatcher.dispatch(RemoteCommand.parse(json), target);

        assertEquals(expectedCall, target.call);
        assertEquals(expectedValue, target.value);
        assertEquals(1, target.callCount);
    }

    private static final class RecordingTarget implements RemoteCommandDispatcher.Target {
        String call;
        int value = -1;
        int callCount;

        @Override
        public void play() {
            record("play");
        }

        @Override
        public void pause() {
            record("pause");
        }

        @Override
        public void previous() {
            record("previous");
        }

        @Override
        public void next() {
            record("next");
        }

        @Override
        public void seekTo(int positionSeconds) {
            value = positionSeconds;
            record("seek");
        }

        @Override
        public void setShuffle(boolean enabled) {
            record("shuffle:" + enabled);
        }

        @Override
        public void setRating(int value) {
            this.value = value;
            record("rating");
        }

        private void record(String value) {
            call = value;
            callCount++;
        }
    }
}
