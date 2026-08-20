package dev.powerampremote.phone;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

/** Selects scanner-only orientation from the caller's explicit app configuration. */
final class QrScannerOrientation {
    private QrScannerOrientation() {
    }

    static int fromCallerConfiguration(int configurationOrientation) {
        return configurationOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }
}
