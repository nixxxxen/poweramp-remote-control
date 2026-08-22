package dev.powerampremote.phone;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** Presentation-only product, version, license, and attribution surface. */
public final class AboutActivity extends LocaleAwareActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SafeDrawingInsets.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_about);
        SafeDrawingInsets.apply(findViewById(R.id.about_root));

        View backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(view -> {
            haptic(view);
            finish();
        });
        View repositoryButton = findViewById(R.id.open_repository_button);
        repositoryButton.setOnClickListener(view -> {
            haptic(view);
            openLink(R.string.repository_url);
        });
        View noticesButton = findViewById(R.id.open_third_party_button);
        noticesButton.setOnClickListener(view -> {
            haptic(view);
            openLink(R.string.third_party_notices_url);
        });
        renderVersion();
    }

    @SuppressWarnings("deprecation")
    private void renderVersion() {
        TextView versionNameView = findViewById(R.id.version_name);
        TextView versionCodeView = findViewById(R.id.version_code);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            String versionName = info.versionName == null
                    ? getString(R.string.about_version_unavailable) : info.versionName;
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            versionNameView.setText(getString(R.string.about_version_name, versionName));
            versionCodeView.setText(getString(R.string.about_version_code, versionCode));
        } catch (PackageManager.NameNotFoundException exception) {
            String unavailable = getString(R.string.about_version_unavailable);
            versionNameView.setText(getString(R.string.about_version_name, unavailable));
            versionCodeView.setText(getString(R.string.about_version_code, 0L));
        }
    }

    private void openLink(int urlResource) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlResource))));
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.link_open_error, Toast.LENGTH_SHORT).show();
        }
    }

    private static void haptic(View view) {
        view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CLOCK_TICK);
    }
}
