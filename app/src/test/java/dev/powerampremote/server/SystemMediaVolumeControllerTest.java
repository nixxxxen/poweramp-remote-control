package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SystemMediaVolumeControllerTest {
    @Test
    public void acceptsOnlyControllableValuesWithinTheReportedRange() {
        assertTrue(SystemMediaVolumeController.isRequestedVolumeValid(0, 15, true));
        assertTrue(SystemMediaVolumeController.isRequestedVolumeValid(15, 15, true));
        assertFalse(SystemMediaVolumeController.isRequestedVolumeValid(-1, 15, true));
        assertFalse(SystemMediaVolumeController.isRequestedVolumeValid(16, 15, true));
        assertFalse(SystemMediaVolumeController.isRequestedVolumeValid(5, 15, false));
        assertFalse(SystemMediaVolumeController.isRequestedVolumeValid(0, -1, true));
    }
}
