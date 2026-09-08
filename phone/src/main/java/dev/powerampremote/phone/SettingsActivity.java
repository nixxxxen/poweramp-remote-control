package dev.powerampremote.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.Toast;

/** Presentation-only settings surface; the connection service remains its independent owner. */
public final class SettingsActivity extends LocaleAwareActivity {
    private RadioGroup languageGroup;
    private AppLanguage selectedLanguage;
    private boolean updatingSelection;
    private MiniPlayerController miniPlayer;
    private boolean bindingRequested;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof PhoneConnectionService.LocalBinder) {
                miniPlayer.attach((PhoneConnectionService.LocalBinder) service);
            } else {
                miniPlayer.onServiceDisconnected();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            miniPlayer.onServiceDisconnected();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            miniPlayer.onServiceDisconnected();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SafeDrawingInsets.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_settings);
        SafeDrawingInsets.apply(findViewById(R.id.settings_root));
        miniPlayer = new MiniPlayerController(this);
        BottomNavigation.bind(
                this,
                BottomNavigation.Tab.SETTINGS,
                findViewById(R.id.tab_content)
        );

        View backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(view -> {
            haptic(view);
            finish();
        });
        findViewById(R.id.open_about_button).setOnClickListener(view -> {
            haptic(view);
            startActivity(new Intent(this, AboutActivity.class));
        });

        languageGroup = findViewById(R.id.language_group);
        selectedLanguage = PhoneLocale.selected(this);
        setCheckedLanguage(selectedLanguage);
        languageGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingSelection) return;
            AppLanguage requested = languageForId(checkedId);
            if (requested == selectedLanguage) return;
            View checked = group.findViewById(checkedId);
            if (checked != null) haptic(checked);
            if (!PhoneLocale.apply(this, requested)) {
                setCheckedLanguage(selectedLanguage);
                Toast.makeText(this, R.string.language_storage_error, Toast.LENGTH_SHORT).show();
                return;
            }
            selectedLanguage = requested;
            PhoneConnectionService.refreshLanguage(this);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate();
        });
        try {
            PhoneConnectionService.start(this);
        } catch (RuntimeException ignored) {
            miniPlayer.onServiceDisconnected();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindingRequested = bindService(
                PhoneConnectionService.bindingIntent(this),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
        if (!bindingRequested) miniPlayer.onServiceDisconnected();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        BottomNavigation.bind(
                this,
                BottomNavigation.Tab.SETTINGS,
                findViewById(R.id.tab_content)
        );
    }

    @Override
    protected void onStop() {
        BottomNavigation.cancel(this);
        miniPlayer.detach();
        if (bindingRequested) {
            unbindService(serviceConnection);
            bindingRequested = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        BottomNavigation.release(this);
        super.onDestroy();
    }

    private void setCheckedLanguage(AppLanguage language) {
        updatingSelection = true;
        languageGroup.check(idForLanguage(language));
        updatingSelection = false;
    }

    private static AppLanguage languageForId(int id) {
        if (id == R.id.language_russian) return AppLanguage.RUSSIAN;
        if (id == R.id.language_english) return AppLanguage.ENGLISH;
        return AppLanguage.SYSTEM;
    }

    private static int idForLanguage(AppLanguage language) {
        if (language == AppLanguage.RUSSIAN) return R.id.language_russian;
        if (language == AppLanguage.ENGLISH) return R.id.language_english;
        return R.id.language_system;
    }

    private static void haptic(View view) {
        view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CLOCK_TICK);
    }
}
