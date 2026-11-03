package dev.powerampremote.phone;

import android.app.AlertDialog;
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
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Presentation-only settings surface; the connection service remains its independent owner. */
public final class SettingsActivity extends LocaleAwareActivity {
    private RadioGroup languageGroup;
    private AppLanguage selectedLanguage;
    private boolean updatingSelection;
    private MiniPlayerController miniPlayer;
    private boolean bindingRequested;
    private ConnectionPreferences connectionPreferences;
    private Switch allowDirect;
    private Switch preferDirect;
    private Switch developerMode;
    private View viewLogs;
    private boolean updatingConnectionSettings;
    private AlertDialog logsDialog;

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
        connectionPreferences = new ConnectionPreferences(this);
        allowDirect = findViewById(R.id.allow_direct_switch);
        preferDirect = findViewById(R.id.prefer_direct_switch);
        developerMode = findViewById(R.id.developer_mode_switch);
        viewLogs = findViewById(R.id.view_connection_logs);
        renderConnectionSettings();
        allowDirect.setOnCheckedChangeListener((button, checked) -> saveConnectionPolicy());
        preferDirect.setOnCheckedChangeListener((button, checked) -> saveConnectionPolicy());
        developerMode.setOnCheckedChangeListener((button, checked) -> {
            if (updatingConnectionSettings) return;
            if (!connectionPreferences.saveDeveloperMode(checked)) {
                Toast.makeText(this, R.string.connection_settings_storage_error, Toast.LENGTH_SHORT).show();
            } else {
                ConnectionDiagnostics.setEnabled(checked);
                refreshConnectionSettings();
            }
            renderConnectionSettings();
        });
        viewLogs.setOnClickListener(view -> showConnectionLogs());
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
        if (logsDialog != null) logsDialog.dismiss();
        BottomNavigation.release(this);
        super.onDestroy();
    }

    private void setCheckedLanguage(AppLanguage language) {
        updatingSelection = true;
        languageGroup.check(idForLanguage(language));
        updatingSelection = false;
    }

    private void renderConnectionSettings() {
        updatingConnectionSettings = true;
        ConnectionPolicy policy = connectionPreferences.policy();
        allowDirect.setChecked(policy.allowsDirect());
        preferDirect.setChecked(policy == ConnectionPolicy.DIRECT_PREFERRED);
        preferDirect.setEnabled(policy.allowsDirect());
        preferDirect.setAlpha(policy.allowsDirect() ? 1f : 0.38f);
        boolean developer = connectionPreferences.developerMode();
        developerMode.setChecked(developer);
        viewLogs.setVisibility(developer ? View.VISIBLE : View.GONE);
        if (!developer && logsDialog != null) logsDialog.dismiss();
        updatingConnectionSettings = false;
    }

    private void saveConnectionPolicy() {
        if (updatingConnectionSettings) return;
        ConnectionPolicy policy = ConnectionPolicy.fromSwitches(
                allowDirect.isChecked(), preferDirect.isChecked());
        if (!connectionPreferences.savePolicy(policy)) {
            Toast.makeText(this, R.string.connection_settings_storage_error, Toast.LENGTH_SHORT).show();
        } else refreshConnectionSettings();
        renderConnectionSettings();
    }

    private void refreshConnectionSettings() {
        try {
            PhoneConnectionService.refreshConnectionSettings(this);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, R.string.connection_service_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void showConnectionLogs() {
        if (!connectionPreferences.developerMode()) return;
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        int inset = Math.round(16f * getResources().getDisplayMetrics().density);
        text.setPadding(inset, inset, inset, inset);
        text.setTextIsSelectable(true);
        text.setTextSize(12f);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll.addView(text);
        text.setText(logSnapshot());
        logsDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.connection_logs_title)
                .setView(scroll)
                .setPositiveButton(R.string.connection_logs_refresh, null)
                .setNeutralButton(R.string.connection_logs_clear, null)
                .setNegativeButton(R.string.connection_logs_close, null)
                .create();
        logsDialog.setOnShowListener(ignored -> {
            logsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int oldPosition = scroll.getScrollY();
                boolean atEnd = text.getBottom() - (oldPosition + scroll.getHeight()) <= inset;
                text.setText(logSnapshot());
                scroll.post(() -> {
                    if (atEnd) scroll.fullScroll(View.FOCUS_DOWN);
                    else scroll.scrollTo(0, oldPosition);
                });
            });
            logsDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                ConnectionDiagnostics.clear();
                text.setText(logSnapshot());
                scroll.scrollTo(0, 0);
            });
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        logsDialog.show();
    }

    private String logSnapshot() {
        java.util.List<String> events = ConnectionDiagnostics.snapshot();
        return events.isEmpty() ? getString(R.string.connection_logs_empty)
                : String.join("\n", events);
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
