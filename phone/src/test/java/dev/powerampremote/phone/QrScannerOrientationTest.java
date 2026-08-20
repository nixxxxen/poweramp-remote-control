package dev.powerampremote.phone;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class QrScannerOrientationTest {
    @Test
    public void portraitIsDefaultAndLandscapeRequiresLandscapeCaller() {
        assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                QrScannerOrientation.fromCallerConfiguration(Configuration.ORIENTATION_UNDEFINED)
        );
        assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                QrScannerOrientation.fromCallerConfiguration(Configuration.ORIENTATION_PORTRAIT)
        );
        assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                QrScannerOrientation.fromCallerConfiguration(Configuration.ORIENTATION_LANDSCAPE)
        );
    }
}
