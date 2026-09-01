package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ControlMotionPolicyTest {
    @Test
    public void initialPlayPauseStateIsImmediateAndConfirmedChangesAnimateBothWays() {
        ControlMotionPolicy.Binary policy = new ControlMotionPolicy.Binary();

        assertEquals(ControlMotionPolicy.Mode.IMMEDIATE,
                policy.update(false, false, true, true, 0f).mode);
        ControlMotionPolicy.Update toPause = policy.update(true, false, true, true, 0f);
        assertEquals(ControlMotionPolicy.Mode.ANIMATE, toPause.mode);
        assertEquals(0f, toPause.startProgress, 0f);
        assertEquals(1f, toPause.targetProgress, 0f);

        ControlMotionPolicy.Update toPlay = policy.update(false, false, true, true, 0.63f);
        assertEquals(ControlMotionPolicy.Mode.ANIMATE, toPlay.mode);
        assertEquals(0.63f, toPlay.startProgress, 0f);
        assertEquals(0f, toPlay.targetProgress, 0f);
    }

    @Test
    public void duplicateAndRapidReverseDoNotCreateAnotherMotionDecision() {
        ControlMotionPolicy.Binary policy = new ControlMotionPolicy.Binary();
        policy.update(false, false, true, true, 0f);
        policy.update(true, false, true, true, 0f);

        assertEquals(ControlMotionPolicy.Mode.NONE,
                policy.update(true, false, true, true, 0.4f).mode);
        ControlMotionPolicy.Update reverse = policy.update(false, false, true, true, 0.4f);
        assertEquals(ControlMotionPolicy.Mode.ANIMATE, reverse.mode);
        assertEquals(0.4f, reverse.startProgress, 0f);
        assertEquals(0f, reverse.targetProgress, 0f);
    }

    @Test
    public void replayOrDisabledAnimationsApplyBinaryEndpointImmediately() {
        ControlMotionPolicy.Binary replay = new ControlMotionPolicy.Binary();
        replay.update(false, false, true, true, 0f);
        assertEquals(ControlMotionPolicy.Mode.IMMEDIATE,
                replay.update(true, true, true, true, 0.2f).mode);

        ControlMotionPolicy.Binary disabled = new ControlMotionPolicy.Binary();
        disabled.update(false, false, true, true, 0f);
        assertEquals(ControlMotionPolicy.Mode.IMMEDIATE,
                disabled.update(true, false, true, false, 0.2f).mode);
    }

    @Test
    public void likePulsesOnlyOnceForVisibleConfirmedFalseToTrue() {
        ControlMotionPolicy.Like policy = new ControlMotionPolicy.Like();

        assertEquals(ControlMotionPolicy.Mode.RESET,
                policy.update(true, true, true, true));
        assertEquals(ControlMotionPolicy.Mode.NONE,
                policy.update(true, false, true, true));
        assertEquals(ControlMotionPolicy.Mode.RESET,
                policy.update(false, false, true, true));
        assertEquals(ControlMotionPolicy.Mode.PULSE,
                policy.update(true, false, true, true));
        assertEquals(ControlMotionPolicy.Mode.NONE,
                policy.update(true, false, true, true));
        assertEquals(ControlMotionPolicy.Mode.RESET,
                policy.update(false, false, true, true));
        assertEquals(ControlMotionPolicy.Mode.RESET,
                policy.update(true, false, true, false));
    }

    @Test
    public void shuffleUsesOffAndOnEndpointsWithoutQueueingDuplicates() {
        ControlMotionPolicy.Binary policy = new ControlMotionPolicy.Binary();

        assertEquals(0f, policy.update(false, false, true, true, 0f).targetProgress, 0f);
        assertEquals(1f, policy.update(true, false, true, true, 0f).targetProgress, 0f);
        assertEquals(ControlMotionPolicy.Mode.NONE,
                policy.update(true, false, true, true, 0.55f).mode);
        ControlMotionPolicy.Update reverse = policy.update(
                false, false, true, true, 0.55f
        );
        assertEquals(ControlMotionPolicy.Mode.ANIMATE, reverse.mode);
        assertEquals(0.55f, reverse.startProgress, 0f);
        assertEquals(0f, reverse.targetProgress, 0f);
    }
}
