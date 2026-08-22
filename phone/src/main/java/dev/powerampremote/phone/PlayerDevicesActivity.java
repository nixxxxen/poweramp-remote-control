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
import android.text.InputFilter;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.UUID;

/** Saved player-device management and QR-pairing surface. */
public final class PlayerDevicesActivity extends LocaleAwareActivity
        implements PhoneConnectionService.Listener {
    private static final String STATE_PERMISSION_REQUEST_ATTEMPTED =
            "permission_request_attempted";
    private static final String STATE_SCANNER_DELIVERY_ID = "scanner_delivery_id";

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
    private Button scanQrButton;
    private Button manualPairingButton;
    private Button repairButton;
    private Button forgetButton;

    private PhoneConnectionService.LocalBinder controller;
    private RemoteClientController.Status status = RemoteClientController.Status.SEARCHING;
    private RemoteClientController.PairingMode pairingMode =
            RemoteClientController.PairingMode.NONE;
    private ConnectionAction connectionAction = ConnectionAction.NONE;
    private boolean bindingRequested;
    private boolean permissionRequestAttempted;
    private boolean permissionRequestInFlight;
    private String scannerDeliveryId;

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
            RemoteClientController.PairingError pendingError = controller.consumePairingError();
            if (pendingError != null) onPairingFailed(pendingError);
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
        SafeDrawingInsets.enableEdgeToEdge(getWindow());
        permissionRequestAttempted = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_PERMISSION_REQUEST_ATTEMPTED, false);
        scannerDeliveryId = savedInstanceState == null
                ? null : savedInstanceState.getString(STATE_SCANNER_DELIVERY_ID);
        setContentView(R.layout.activity_player_devices);
        SafeDrawingInsets.apply(findViewById(R.id.player_devices_root));
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
        if (scannerDeliveryId != null) {
            outState.putString(STATE_SCANNER_DELIVERY_ID, scannerDeliveryId);
        }
        super.onSaveInstanceState(outState);
    }

    private void onDirectPermissionResult() {
        permissionRequestInFlight = false;
        if (controller != null) controller.onDirectPermissionOrSettingsChanged();
        renderConnectionAction();
    }

    private void onScanResult(ScanIntentResult result) {
        if (result.getContents() == null) return;
        if (scannerDeliveryId == null) scannerDeliveryId = UUID.randomUUID().toString();
        try {
            hideError();
            PhoneConnectionService.requestQrPairing(
                    this,
                    result.getContents(),
                    scannerDeliveryId
            );
            pairingMode = RemoteClientController.PairingMode.QR;
            pairingProgressPanel.setVisibility(View.VISIBLE);
            pairingProgressMessage.setText(R.string.pairing_searching_target);
            setActionsEnabled(controller != null);
        } catch (IllegalArgumentException exception) {
            showError(R.string.pairing_invalid_qr);
        } catch (RuntimeException exception) {
            showError(R.string.connection_service_error);
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
        scanQrButton = findViewById(R.id.scan_qr_button);
        manualPairingButton = findViewById(R.id.manual_pairing_button);
        repairButton = findViewById(R.id.repair_player_button);
        forgetButton = findViewById(R.id.forget_device_button);
    }

    private void configureActions() {
        scanQrButton.setOnClickListener(view -> {
            haptic(view);
            launchScanner();
        });
        manualPairingButton.setOnClickListener(view -> {
            haptic(view);
            showManualPairingDialog();
        });
        repairButton.setOnClickListener(view -> {
            haptic(view);
            showPairingMethodDialog();
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
            PhoneConnectionService.start(this);
        } catch (RuntimeException exception) {
            showError(R.string.connection_service_error);
            return;
        }
        try {
            scannerDeliveryId = UUID.randomUUID().toString();
            ScanOptions options = new ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.pairing_scan_prompt))
                    .setBeepEnabled(false)
                    .setBarcodeImageEnabled(false)
                    .setCaptureActivity(QrScannerActivity.class)
                    .setOrientationLocked(false)
                    .addExtra(
                            QrScannerActivity.EXTRA_REQUESTED_ORIENTATION,
                            QrScannerOrientation.fromCallerConfiguration(
                                    getResources().getConfiguration().orientation
                            )
                    );
            barcodeLauncher.launch(options);
        } catch (RuntimeException exception) {
            scannerDeliveryId = null;
            showError(R.string.pairing_scanner_unavailable);
        }
    }

    private void showPairingMethodDialog() {
        CharSequence[] methods = {
                getText(R.string.scan_qr_code),
                getText(R.string.enter_token_manually)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.repair_player)
                .setItems(methods, (dialog, which) -> {
                    if (which == 0) launchScanner();
                    else showManualPairingDialog();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showManualPairingDialog() {
        hideError();
        EditText tokenInput = new EditText(this);
        tokenInput.setHint(R.string.token_hint);
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tokenInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tokenInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        tokenInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});

        int dialogInset = Math.round(20f * getResources().getDisplayMetrics().density);
        FrameLayout inputContainer = new FrameLayout(this);
        inputContainer.setPadding(dialogInset, 0, dialogInset, 0);
        inputContainer.addView(tokenInput, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.manual_pairing_title)
                .setMessage(R.string.manual_pairing_instruction)
                .setView(inputContainer)
                .setPositiveButton(R.string.manual_pairing_submit, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> submitManualPairing(dialog, tokenInput)));
        tokenInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            submitManualPairing(dialog, tokenInput);
            return true;
        });
        dialog.show();
    }

    private void submitManualPairing(AlertDialog dialog, EditText tokenInput) {
        try {
            PhoneConnectionService.requestManualPairing(
                    this,
                    tokenInput.getText().toString()
            );
            pairingMode = RemoteClientController.PairingMode.MANUAL_TOKEN;
            pairingProgressPanel.setVisibility(View.VISIBLE);
            pairingProgressMessage.setText(R.string.pairing_searching_manual);
            setActionsEnabled(controller != null);
            hideError();
            dialog.dismiss();
        } catch (IllegalArgumentException exception) {
            tokenInput.setError(getString(R.string.pairing_invalid_token));
        } catch (RuntimeException exception) {
            showError(R.string.connection_service_error);
            dialog.dismiss();
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
        if (controller != null) pairingMode = controller.pairingMode();
        boolean pairing = pairingMode != RemoteClientController.PairingMode.NONE;
        setActionsEnabled(controller != null);
        if (pairing) {
            pairingProgressPanel.setVisibility(View.VISIBLE);
            if (pairingMode == RemoteClientController.PairingMode.MANUAL_TOKEN) {
                pairingProgressMessage.setText(
                        newStatus == RemoteClientController.Status.VERIFYING
                                ? R.string.pairing_verifying_manual
                                : R.string.pairing_searching_manual
                );
            } else {
                pairingProgressMessage.setText(
                        newStatus == RemoteClientController.Status.VERIFYING
                                ? R.string.pairing_exchanging
                                : R.string.pairing_searching_target
                );
            }
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
        if (controller != null) controller.consumePairingError();
        pairingMode = RemoteClientController.PairingMode.NONE;
        pairingProgressPanel.setVisibility(View.GONE);
        switch (error) {
            case INVALID_QR:
                showError(R.string.pairing_invalid_qr);
                break;
            case INVALID_TOKEN:
                showError(R.string.pairing_invalid_token);
                break;
            case QR_REJECTED:
                showError(R.string.pairing_secret_rejected);
                break;
            case TOKEN_REJECTED:
                showError(R.string.pairing_unauthorized);
                break;
            case MANUAL_NETWORK:
                showError(R.string.pairing_network_error);
                break;
            case STORAGE:
                showError(R.string.pairing_storage_error);
                break;
            case NETWORK:
            default:
                showError(R.string.pairing_network_error_qr);
                break;
        }
        setActionsEnabled(controller != null);
        renderDevice();
    }

    @Override
    public void onPairingSucceeded(String name) {
        pairingMode = RemoteClientController.PairingMode.NONE;
        pairingProgressPanel.setVisibility(View.GONE);
        hideError();
        setActionsEnabled(controller != null);
        renderDevice();
        Toast.makeText(this, getString(R.string.pairing_success_device, name),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStateChanged(RemoteState state, long receivedRealtimeMilliseconds) {
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
                getString(runtimeStatusResource(snapshot.runtimeStatus))
        ));
    }

    private static int runtimeStatusResource(RemoteClientController.Status value) {
        switch (value) {
            case PAIRING:
                return R.string.runtime_status_pairing;
            case VERIFYING:
                return R.string.runtime_status_verifying;
            case CONNECTING:
                return R.string.runtime_status_connecting;
            case CONNECTED:
                return R.string.runtime_status_connected;
            case DIRECT_SEARCHING:
                return R.string.runtime_status_direct_searching;
            case DIRECT_PERMISSION_REQUIRED:
                return R.string.runtime_status_direct_permission;
            case DIRECT_LOCATION_REQUIRED:
                return R.string.runtime_status_direct_location;
            case DIRECT_WIFI_REQUIRED:
                return R.string.runtime_status_direct_wifi;
            case DIRECT_CONNECTING:
                return R.string.runtime_status_direct_connecting;
            case CONNECTED_DIRECT:
                return R.string.runtime_status_connected_direct;
            case DIRECT_UNSUPPORTED:
                return R.string.runtime_status_direct_unsupported;
            case DIRECT_ACTION_REQUIRED:
                return R.string.runtime_status_direct_action;
            case RETRYING:
                return R.string.runtime_status_retrying;
            case AUTH_REQUIRED:
                return R.string.runtime_status_auth_required;
            case ERROR:
                return R.string.runtime_status_error;
            case SEARCHING:
            default:
                return R.string.runtime_status_searching;
        }
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
        boolean pairing = pairingMode != RemoteClientController.PairingMode.NONE;
        scanQrButton.setEnabled(!pairing);
        manualPairingButton.setEnabled(!pairing);
        repairButton.setEnabled(!pairing);
        forgetButton.setEnabled(enabled && !pairing);
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
