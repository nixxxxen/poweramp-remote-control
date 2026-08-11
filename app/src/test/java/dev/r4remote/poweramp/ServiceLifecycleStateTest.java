package dev.r4remote.poweramp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ServiceLifecycleStateTest {
    @Test
    public void repeatedStartIsIdempotent() {
        ServiceLifecycleState state = new ServiceLifecycleState();

        int first = state.start();
        int repeated = state.start();

        assertEquals(first, repeated);
        assertTrue(state.isRunning());
        assertTrue(state.isRunning(first));
    }

    @Test
    public void stopInvalidatesQueuedWorkAndAllowsARealRestart() {
        ServiceLifecycleState state = new ServiceLifecycleState();
        int first = state.start();

        assertTrue(state.stop());
        assertFalse(state.stop());
        assertFalse(state.isRunning(first));

        int restarted = state.start();
        assertTrue(restarted > first);
        assertTrue(state.isRunning(restarted));
    }

    @Test
    public void closeIsFinalAndIdempotent() {
        ServiceLifecycleState state = new ServiceLifecycleState();
        int generation = state.start();

        assertTrue(state.close());
        assertFalse(state.close());
        assertFalse(state.isRunning(generation));
        assertEquals(-1, state.runningGeneration());
        assertEquals(-1, state.start());
    }
}
