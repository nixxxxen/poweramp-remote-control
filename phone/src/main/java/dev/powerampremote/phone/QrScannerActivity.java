package dev.powerampremote.phone;

import android.content.pm.ActivityInfo;
import android.os.Bundle;

import com.journeyapps.barcodescanner.CaptureActivity;

/** Scanner-only Activity that avoids the library's sensor-landscape manifest default. */
public final class QrScannerActivity extends CaptureActivity {
    static final String EXTRA_REQUESTED_ORIENTATION =
            "dev.powerampremote.phone.extra.SCANNER_ORIENTATION";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int requested = getIntent().getIntExtra(
                EXTRA_REQUESTED_ORIENTATION,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        );
        setRequestedOrientation(requested == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        super.onCreate(savedInstanceState);
    }
}
