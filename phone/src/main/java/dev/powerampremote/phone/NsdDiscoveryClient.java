package dev.powerampremote.phone;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Finds and resolves Poweramp Remote servers without accepting a manually entered address. */
// The legacy resolve/getHost path is required for the module's Android 8 (API 26) minimum.
@SuppressWarnings("deprecation")
final class NsdDiscoveryClient implements AutoCloseable {
    private static final int MAX_RESOLUTION_ATTEMPTS = 3;
    private static final long RESOLUTION_RETRY_DELAY_MILLISECONDS = 1_000L;
    interface Listener {
        void onServerFound(DiscoveredServer server);
        void onServerLost(String serviceName);
        void onDiscoveryError(int errorCode);
    }

    private final NsdManager nsdManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final LinkedHashMap<String, NsdServiceInfo> pendingResolutions =
            new LinkedHashMap<>();
    private final Set<String> visibleServices = new HashSet<>();
    private final Map<String, Integer> resolutionFailures = new HashMap<>();

    private boolean active;
    private boolean discoveryStarted;
    private boolean resolving;
    private boolean closed;
    private int generation;
    private NsdManager.DiscoveryListener discoveryListener;

    NsdDiscoveryClient(Context context, Listener listener) {
        nsdManager = context.getSystemService(NsdManager.class);
        this.listener = listener;
    }

    void start() {
        if (active || closed) return;
        if (nsdManager == null) {
            listener.onDiscoveryError(NsdManager.FAILURE_INTERNAL_ERROR);
            return;
        }
        active = true;
        discoveryStarted = false;
        int currentGeneration = ++generation;
        NsdManager.DiscoveryListener callbacks = new DiscoveryCallbacks(currentGeneration);
        discoveryListener = callbacks;
        try {
            nsdManager.discoverServices(
                    RemoteNsdContract.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    callbacks
            );
        } catch (RuntimeException exception) {
            active = false;
            discoveryListener = null;
            listener.onDiscoveryError(NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    void stop() {
        if (!active && discoveryListener == null) return;
        active = false;
        generation++;
        pendingResolutions.clear();
        visibleServices.clear();
        resolutionFailures.clear();
        resolving = false;
        NsdManager.DiscoveryListener callbacks = discoveryListener;
        discoveryListener = null;
        discoveryStarted = false;
        if (callbacks != null && nsdManager != null) {
            try {
                nsdManager.stopServiceDiscovery(callbacks);
            } catch (RuntimeException ignored) {
                // The platform may already have stopped a failed discovery generation.
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
    }

    private void handleFound(int callbackGeneration, NsdServiceInfo serviceInfo) {
        if (!isCurrent(callbackGeneration)
                || !RemoteNsdContract.serviceTypeMatches(serviceInfo.getServiceType())) {
            return;
        }
        String name = serviceInfo.getServiceName();
        if (name == null || name.isEmpty()) return;
        visibleServices.add(name);
        resolutionFailures.remove(name);
        pendingResolutions.put(name, serviceInfo);
        resolveNext(callbackGeneration);
    }

    private void resolveNext(int callbackGeneration) {
        if (!isCurrent(callbackGeneration) || resolving || pendingResolutions.isEmpty()) return;
        Iterator<Map.Entry<String, NsdServiceInfo>> iterator =
                pendingResolutions.entrySet().iterator();
        NsdServiceInfo serviceInfo = iterator.next().getValue();
        iterator.remove();
        resolving = true;
        try {
            nsdManager.resolveService(serviceInfo, new ResolveCallbacks(callbackGeneration));
        } catch (RuntimeException exception) {
            resolving = false;
            resolveNext(callbackGeneration);
        }
    }

    private void handleResolved(int callbackGeneration, NsdServiceInfo serviceInfo) {
        if (!isCurrent(callbackGeneration)) return;
        resolving = false;
        String serviceName = serviceInfo.getServiceName();
        if (serviceName == null || !visibleServices.contains(serviceName)) {
            resolveNext(callbackGeneration);
            return;
        }
        resolutionFailures.remove(serviceName);
        String apiVersion = RemoteNsdContract.attribute(
                serviceInfo.getAttributes(),
                RemoteNsdContract.ATTRIBUTE_API_VERSION
        );
        String serverId = RemoteNsdContract.attribute(
                serviceInfo.getAttributes(),
                RemoteNsdContract.ATTRIBUTE_SERVER_ID
        );
        InetAddress host = serviceInfo.getHost();
        int port = serviceInfo.getPort();
        if (RemoteNsdContract.API_VERSION.equals(apiVersion)
                && PairingCredentials.isValidServerId(serverId)
                && host != null && port > 0 && port <= 65_535) {
            try {
                listener.onServerFound(new DiscoveredServer(
                        serverId,
                        serviceName,
                        host,
                        port
                ));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed third-party DNS-SD records using the same service type.
            }
        }
        resolveNext(callbackGeneration);
    }

    private void handleResolveFailed(
            int callbackGeneration,
            NsdServiceInfo serviceInfo
    ) {
        if (!isCurrent(callbackGeneration)) return;
        resolving = false;
        String serviceName = serviceInfo.getServiceName();
        if (serviceName != null && visibleServices.contains(serviceName)) {
            int failures = resolutionFailures.getOrDefault(serviceName, 0) + 1;
            if (failures < MAX_RESOLUTION_ATTEMPTS) {
                resolutionFailures.put(serviceName, failures);
                mainHandler.postDelayed(() -> {
                    if (!isCurrent(callbackGeneration)
                            || !visibleServices.contains(serviceName)
                            || resolutionFailures.getOrDefault(serviceName, 0) != failures) {
                        return;
                    }
                    pendingResolutions.putIfAbsent(serviceName, serviceInfo);
                    resolveNext(callbackGeneration);
                }, RESOLUTION_RETRY_DELAY_MILLISECONDS);
            } else {
                resolutionFailures.remove(serviceName);
            }
        }
        resolveNext(callbackGeneration);
    }

    private void handleDiscoveryFailure(int callbackGeneration, int errorCode) {
        if (!isCurrent(callbackGeneration)) return;
        active = false;
        discoveryStarted = false;
        discoveryListener = null;
        pendingResolutions.clear();
        visibleServices.clear();
        resolutionFailures.clear();
        resolving = false;
        listener.onDiscoveryError(errorCode);
    }

    private boolean isCurrent(int callbackGeneration) {
        return active && !closed && callbackGeneration == generation;
    }

    private final class DiscoveryCallbacks implements NsdManager.DiscoveryListener {
        private final int callbackGeneration;

        DiscoveryCallbacks(int callbackGeneration) {
            this.callbackGeneration = callbackGeneration;
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            mainHandler.post(() -> {
                if (isCurrent(callbackGeneration)) {
                    discoveryStarted = true;
                    return;
                }
                // stop() can race the asynchronous start callback. Ensure that stale
                // generations do not keep a platform discovery registration alive.
                if (nsdManager != null) {
                    try {
                        nsdManager.stopServiceDiscovery(DiscoveryCallbacks.this);
                    } catch (RuntimeException ignored) {
                        // The platform may already have completed the earlier stop request.
                    }
                }
            });
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            mainHandler.post(() -> handleFound(callbackGeneration, serviceInfo));
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            mainHandler.post(() -> {
                if (!isCurrent(callbackGeneration)) return;
                String name = serviceInfo.getServiceName();
                if (name != null) {
                    pendingResolutions.remove(name);
                    visibleServices.remove(name);
                    resolutionFailures.remove(name);
                    listener.onServerLost(name);
                }
            });
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            mainHandler.post(() -> handleDiscoveryFailure(callbackGeneration, errorCode));
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            // stop() invalidates the generation before asking the platform to stop.
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            mainHandler.post(() -> {
                // Explicit stop() invalidates the generation first. A current callback means
                // the platform stopped discovery underneath us, so let the controller restart it.
                if (isCurrent(callbackGeneration)) {
                    handleDiscoveryFailure(callbackGeneration, NsdManager.FAILURE_INTERNAL_ERROR);
                }
            });
        }
    }

    private final class ResolveCallbacks implements NsdManager.ResolveListener {
        private final int callbackGeneration;

        ResolveCallbacks(int callbackGeneration) {
            this.callbackGeneration = callbackGeneration;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            mainHandler.post(() -> handleResolveFailed(callbackGeneration, serviceInfo));
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            mainHandler.post(() -> handleResolved(callbackGeneration, serviceInfo));
        }
    }
}
