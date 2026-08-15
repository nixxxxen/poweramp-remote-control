package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PlayerDeviceNameTest {
    @Test
    public void producesGenericReadableDeviceNames() {
        assertEquals("HiBy R4", PlayerDeviceName.format("HiBy", "R4"));
        assertEquals("Google Pixel 9", PlayerDeviceName.format("Google", "Google Pixel 9"));
        assertEquals("Android player", PlayerDeviceName.format(" ", null));
    }
}
