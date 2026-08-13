package dev.r4remote.poweramp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.util.Log;

import java.util.Map;

/** Idempotent DNS-SD registration tied to the actual HTTP listener lifecycle. */
final class RemoteNsdPublisher implements AutoCloseable {
    private static final String TAG = "RemoteNsdPublisher";
    private static final long RETRY_DELAY_MILLISECONDS = 5_000L;

    private final NsdManager nsdManager;
    private final Handler mainHandler;
    private final String serverId;
    private final Runnable retry = this::retryCurrentState;

    private boolean desired;
    private boolean registered;
    private boolean unregistering;
    private boolean closed;
    private NsdManager.RegistrationListener registrationListener;

    RemoteNsdPublisher(Context context, Handler mainHandler, String serverId) {
        this.nsdManager = context.getSystemService(NsdManager.class);
        this.mainHandler = mainHandler;
        this.serverId = serverId;
    }

    void start() {
        desired = true;
        registerIfNeeded();
    }

    void stop() {
        desired = false;
        mainHandler.removeCallbacks(retry);
        unregisterIfRegistered();
    }

    private void registerIfNeeded() {
        if (!desired || closed || nsdManager == null || registrationListener != null) {
            return;
        }
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(RemoteNsdContract.SERVICE_NAME);
        serviceInfo.setServiceType(RemoteNsdContract.SERVICE_TYPE);
        serviceInfo.setPort(RemoteApiServer.PORT);
        for (Map.Entry<String, String> attribute
                : RemoteNsdContract.attributes(serverId).entrySet()) {
            serviceInfo.setAttribute(attribute.getKey(), attribute.getValue());
        }

        NsdManager.RegistrationListener listener = new RegistrationCallbacks();
        registrationListener = listener;
        try {
            nsdManager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
            );
        } catch (RuntimeException exception) {
            registrationListener = null;
            Log.w(TAG, "Unable to start NSD registration", exception);
            scheduleRetry();
        }
    }

    private void unregisterIfRegistered() {
        NsdManager.RegistrationListener listener = registrationListener;
        if (!registered || unregistering || listener == null || nsdManager == null) {
            return;
        }
        unregistering = true;
        try {
            nsdManager.unregisterService(listener);
        } catch (RuntimeException exception) {
            unregistering = false;
            Log.w(TAG, "Unable to unregister NSD service", exception);
            scheduleRetry();
        }
    }

    private void retryCurrentState() {
        if (desired) {
            registerIfNeeded();
        } else {
            unregisterIfRegistered();
        }
    }

    private void scheduleRetry() {
        mainHandler.removeCallbacks(retry);
        if (!closed) {
            mainHandler.postDelayed(retry, RETRY_DELAY_MILLISECONDS);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        stop();
        closed = true;
        // If registration is still in flight, its callback observes closed and unregisters it.
    }

    private final class RegistrationCallbacks implements NsdManager.RegistrationListener {
        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            if (registrationListener != this) {
                return;
            }
            registered = true;
            unregistering = false;
            Log.i(TAG, "Published " + serviceInfo.getServiceName());
            if (!desired || closed) {
                unregisterIfRegistered();
            }
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (registrationListener != this) {
                return;
            }
            registered = false;
            unregistering = false;
            registrationListener = null;
            Log.w(TAG, "NSD registration failed: " + errorCode);
            scheduleRetry();
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            if (registrationListener != this) {
                return;
            }
            registered = false;
            unregistering = false;
            registrationListener = null;
            if (desired && !closed) {
                registerIfNeeded();
            }
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (registrationListener != this) {
                return;
            }
            registered = true;
            unregistering = false;
            Log.w(TAG, "NSD unregistration failed: " + errorCode);
            scheduleRetry();
        }
    }
}
