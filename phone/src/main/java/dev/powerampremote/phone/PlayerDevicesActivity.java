package dev.powerampremote.phone;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

/** Saved player-device management and QR-pairing surface. */
public final class PlayerDevicesActivity extends ComponentActivity
        implements PhoneConnectionService.Listener {
    private static final String STATE_PERMISSION_REQUEST_ATTEMPTED =
            "permission_request_attempted";

    private enum ConnectionAction {
        NONE, PERMISSION, LOCATION_SETTINGS, WIFI_SETTINGS, RETRY_DIRECT, RETRY_LAN
    }

    private View deviceCard;
    private TextView emptyState;
    private TextView deviceName;
    private TextView deviceConnectionStatus;
    private TextView deviceTransport;
    private TextView deviceDiagnostics;
    private View pairingProgressPanel;
    private TextView pairingProgressMessage;
    private Button connectionActionButton;
    private TextView errorMessage;
    private Button pairNewButton;
    private Button repairButton;
    private Button forgetButton;

    private PhoneConnectionService.LocalBinder controller;
    private RemoteClientController.Status status = RemoteClientController.Status.SEARCHING;
    private ConnectionAction connectionAction = ConnectionAction.NONE;
    private boolean bindingRequested;
    private boolean permissionRequestAttempted;
    private boolean permissionRequestInFlight;

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), this::onScanResult);
    private final ActivityResultLauncher<String[]> directPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> onDirectPermissionResult()
            );

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof PhoneConnectionService.LocalBinder)) {
                showError(R.string.connection_service_error);
                return;
            }
            controller = (PhoneConnectionService.LocalBinder) service;
            controller.addListener(PlayerDevicesActivity.this);
            renderDevice();
            setActionsEnabled(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            controller = null;
            setActionsEnabled(false);
            showError(R.string.connection_service_error);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            controller = null;
            setActionsEnabled(false);
            showError(R.string.connection_service_error);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionRequestAttempted = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_PERMISSION_REQUEST_ATTEMPTED, false);
        setContentView(R.layout.activity_player_devices);
        bindViews();
        configureActions();
        setActionsEnabled(false);
        try {
            PhoneConnectionService.start(this);
        } catch (RuntimeException exception) {
            showError(R.string.connection_service_error);
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
        if (!bindingRequested) showError(R.string.connection_service_error);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null && isDirectRecoveryStatus(status)) {
            controller.onDirectPermissionOrSettingsChanged();
        }
    }

    @Override
    protected void onStop() {
        if (controller != null) {
            controller.removeListener(this);
            controller = null;
        }
        if (bindingRequested) {
            unbindService(serviceConnection);
            bindingRequested = false;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PERMISSION_REQUEST_ATTEMPTED, permissionRequestAttempted);
        super.onSaveInstanceState(outState);
    }

    private void onDirectPermissionResult() {
        permissionRequestInFlight = false;
        if (controller != null) controller.onDirectPermissionOrSettingsChanged();
        renderConnectionAction();
    }

    private void onScanResult(ScanIntentResult result) {
        if (result.getContents() == null) return;
        try {
            PairingQrPayload payload = PairingQrPayload.parse(result.getContents());
            hideError();
            if (controller == null) {
                showError(R.string.connection_service_error);
                return;
            }
            controller.pair(payload);
            pairingProgressPanel.setVisibility(View.VISIBLE);
            pairingProgressMessage.setText(R.string.pairing_searching_target);
        } catch (IllegalArgumentException exception) {
            showError(R.string.pairing_invalid_qr);
        }
    }

    private void bindViews() {
        ImageButton backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(view -> {
            haptic(view);
            finish();
        });
        deviceCard = findViewById(R.id.device_card);
        emptyState = findViewById(R.id.empty_state);
        deviceName = findViewById(R.id.device_name);
        deviceConnectionStatus = findViewById(R.id.device_connection_status);
        deviceTransport = findViewById(R.id.device_transport);
        deviceDiagnostics = findViewById(R.id.device_diagnostics);
        pairingProgressPanel = findViewById(R.id.pairing_progress_panel);
        pairingProgressMessage = findViewById(R.id.pairing_progress_message);
        connectionActionButton = findViewById(R.id.connection_action);
        errorMessage = findViewById(R.id.devices_error_message);
        pairNewButton = findViewById(R.id.pair_new_player_button);
        repairButton = findViewById(R.id.repair_player_button);
        forgetButton = findViewById(R.id.forget_device_button);
    }

    private void configureActions() {
        pairNewButton.setOnClickListener(view -> {
            haptic(view);
            launchScanner();
        });
        repairButton.setOnClickListener(view -> {
            haptic(view);
            launchScanner();
        });
        forgetButton.setOnClickListener(view -> {
            haptic(view);
            showForgetDialog();
        });
        connectionActionButton.setOnClickListener(view -> {
            haptic(view);
            performConnectionAction();
        });
    }

    private void launchScanner() {
        hideError();
        try {
            ScanOptions options = new ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.pairing_scan_prompt))
                    .setBeepEnabled(false)
                    .setBarcodeImageEnabled(false)
                    .setOrientationLocked(false);
            barcodeLauncher.launch(options);
        } catch (RuntimeException exception) {
            showError(R.string.pairing_invalid_qr);
        }
    }

    private void showForgetDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_device_title)
                .setMessage(R.string.forget_device_message)
                .setPositiveButton(R.string.forget_device_confirm, (dialog, which) -> {
                    if (controller == null || !controller.forgetPairing()) {
                        showError(R.string.pairing_storage_error);
                        return;
                    }
                    pairingProgressPanel.setVisibility(View.GONE);
                    hideError();
                    renderDevice();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onStatusChanged(
            RemoteClientController.Status newStatus,
            long retryDelayMilliseconds
    ) {
        status = newStatus;
        boolean pairing = controller != null && controller.isPairingInProgress();
        setActionsEnabled(controller != null && !pairing);
        if (pairing) {
            pairingProgressPanel.setVisibility(View.VISIBLE);
            pairingProgressMessage.setText(newStatus == RemoteClientController.Status.VERIFYING
                    ? R.string.pairing_exchanging : R.string.pairing_searching_target);
        } else if (newStatus != RemoteClientController.Status.DIRECT_PERMISSION_REQUIRED
                && newStatus != RemoteClientController.Status.DIRECT_LOCATION_REQUIRED
                && newStatus != RemoteClientController.Status.DIRECT_WIFI_REQUIRED
                && newStatus != RemoteClientController.Status.DIRECT_ACTION_REQUIRED) {
            pairingProgressPanel.setVisibility(View.GONE);
        }
        renderConnectionAction();
        renderDevice();
        if (newStatus == RemoteClientController.Status.AUTH_REQUIRED) {
            showError(R.string.status_auth_required);
        }
        if (newStatus == RemoteClientController.Status.DIRECT_PERMISSION_REQUIRED
                && !permissionRequestInFlight) {
            requestDirectPermission();
        }
    }

    @Override
    public void onPairingFailed(RemoteClientController.PairingError error) {
        pairingProgressPanel.setVisibility(View.GONE);
        switch (error) {
            case INVALID_QR:
                showError(R.string.pairing_invalid_qr);
                break;
            case REJECTED:
                showError(R.string.pairing_secret_rejected);
                break;
            case STORAGE:
                showError(R.string.pairing_storage_error);
                break;
            case NETWORK:
            default:
                showError(R.string.pairing_network_error_qr);
                break;
        }
        renderDevice();
    }

    @Override
    public void onPairingSucceeded(String name) {
        pairingProgressPanel.setVisibility(View.GONE);
        hideError();
        renderDevice();
        Toast.makeText(this, getString(R.string.pairing_success_device, name),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStateChanged(RemoteState state) {
        renderDevice();
    }

    @Override
    public void onPlaybackSnapshot(PlaybackUiSnapshot snapshot) {
        // Player devices has no playback presentation.
    }

    @Override
    public void onArtworkChanged(Bitmap artwork) {
        // Player devices has no artwork presentation.
    }

    @Override
    public void onCommandError(boolean authenticationError) {
        if (authenticationError) showError(R.string.status_auth_required);
    }

    private void renderDevice() {
        if (controller == null) return;
        PlayerDeviceSnapshot snapshot = controller.playerDeviceSnapshot();
        emptyState.setVisibility(snapshot.saved ? View.GONE : View.VISIBLE);
        deviceCard.setVisibility(snapshot.saved ? View.VISIBLE : View.GONE);
        repairButton.setVisibility(snapshot.saved ? View.VISIBLE : View.GONE);
        forgetButton.setVisibility(snapshot.saved ? View.VISIBLE : View.GONE);
        if (!snapshot.saved) return;

        deviceName.setText(snapshot.deviceName);
        boolean connected = snapshot.connection == PlayerDeviceSnapshot.Connection.CONNECTED;
        deviceConnectionStatus.setText(connected
                ? R.string.device_connected : R.string.device_disconnected);
        deviceConnectionStatus.setTextColor(getColor(connected ? R.color.accent : R.color.warning));
        int transportText;
        switch (snapshot.transport) {
            case LAN:
                transportText = R.string.transport_lan;
                break;
            case WIFI_DIRECT:
                transportText = R.string.transport_wifi_direct;
                break;
            case NONE:
            default:
                transportText = R.string.transport_none;
                break;
        }
        deviceTransport.setText(transportText);
        deviceTransport.setAlpha(snapshot.transport == PlayerDeviceSnapshot.Transport.NONE
                ? 0.58f : 1f);
        deviceDiagnostics.setText(getString(
                R.string.device_diagnostics,
                snapshot.serviceName,
                snapshot.apiVersion,
                snapshot.serverId,
                snapshot.endpoint == null
                        ? getString(R.string.endpoint_not_connected) : snapshot.endpoint,
                snapshot.runtimeStatus.name()
        ));
    }

    private void renderConnectionAction() {
        int textResource;
        switch (status) {
            case DIRECT_PERMISSION_REQUIRED:
                connectionAction = ConnectionAction.PERMISSION;
                textResource = R.string.direct_action_permission;
                break;
            case DIRECT_LOCATION_REQUIRED:
                connectionAction = ConnectionAction.LOCATION_SETTINGS;
                textResource = R.string.direct_action_location;
                break;
            case DIRECT_WIFI_REQUIRED:
                connectionAction = ConnectionAction.WIFI_SETTINGS;
                textResource = R.string.direct_action_wifi;
                break;
            case DIRECT_ACTION_REQUIRED:
                connectionAction = ConnectionAction.RETRY_DIRECT;
                textResource = R.string.direct_action_retry;
                break;
            case DIRECT_UNSUPPORTED:
                connectionAction = ConnectionAction.RETRY_LAN;
                textResource = R.string.direct_action_retry_lan;
                break;
            default:
                connectionAction = ConnectionAction.NONE;
                connectionActionButton.setVisibility(View.GONE);
                return;
        }
        connectionActionButton.setText(textResource);
        connectionActionButton.setEnabled(!permissionRequestInFlight);
        connectionActionButton.setVisibility(View.VISIBLE);
    }

    private void performConnectionAction() {
        switch (connectionAction) {
            case PERMISSION:
                requestDirectPermission();
                break;
            case LOCATION_SETTINGS:
                openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                break;
            case WIFI_SETTINGS:
                openSystemSettings(Settings.ACTION_WIFI_SETTINGS);
                break;
            case RETRY_DIRECT:
                if (controller != null) controller.retryDirectConnection();
                break;
            case RETRY_LAN:
                if (controller != null) controller.retryLanDiscovery();
                break;
            case NONE:
            default:
                break;
        }
    }

    private void requestDirectPermission() {
        if (permissionRequestInFlight) return;
        String permission = WifiDirectConnectionClient.requiredRuntimePermission();
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            if (controller != null) controller.onDirectPermissionOrSettingsChanged();
            return;
        }
        if (permissionRequestAttempted && !shouldShowRequestPermissionRationale(permission)) {
            openApplicationSettings();
            return;
        }
        permissionRequestAttempted = true;
        permissionRequestInFlight = true;
        directPermissionLauncher.launch(WifiDirectConnectionClient.requiredRuntimePermissions());
    }

    private void openSystemSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (RuntimeException exception) {
            openApplicationSettings();
        }
    }

    private void openApplicationSettings() {
        startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
        ));
    }

    private static boolean isDirectRecoveryStatus(RemoteClientController.Status value) {
        return value == RemoteClientController.Status.DIRECT_PERMISSION_REQUIRED
                || value == RemoteClientController.Status.DIRECT_LOCATION_REQUIRED
                || value == RemoteClientController.Status.DIRECT_WIFI_REQUIRED
                || value == RemoteClientController.Status.DIRECT_ACTION_REQUIRED;
    }

    private void setActionsEnabled(boolean enabled) {
        pairNewButton.setEnabled(enabled);
        repairButton.setEnabled(enabled);
        forgetButton.setEnabled(enabled);
    }

    private void showError(int stringResource) {
        errorMessage.setText(stringResource);
        errorMessage.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorMessage.setText(null);
        errorMessage.setVisibility(View.GONE);
    }

    private static void haptic(View view) {
        view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CLOCK_TICK);
    }
}
