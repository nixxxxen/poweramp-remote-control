package dev.powerampremote.phone;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.os.Build;
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
    private static final long RESOLUTION_TIMEOUT_MS = 10_000L;
    interface Listener {
        void onServerFound(DiscoveredServer server);
        void onServerLost(String serviceName);
        void onDiscoveryError(int errorCode);
    }

    private final NsdManager nsdManager;
    private final ConnectivityManager connectivityManager;
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
    private int resolutionSequence;
    private Runnable resolutionTimeout;
    private NsdManager.DiscoveryListener discoveryListener;

    NsdDiscoveryClient(Context context, Listener listener) {
        nsdManager = context.getSystemService(NsdManager.class);
        connectivityManager = context.getSystemService(ConnectivityManager.class);
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
        cancelResolutionTimeout();
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
        int resolution = ++resolutionSequence;
        resolutionTimeout = () -> {
            if (!isCurrent(callbackGeneration) || resolution != resolutionSequence) return;
            resolutionSequence++;
            handleResolveFailed(callbackGeneration, serviceInfo);
        };
        mainHandler.postDelayed(resolutionTimeout, RESOLUTION_TIMEOUT_MS);
        try {
            nsdManager.resolveService(serviceInfo, new ResolveCallbacks(callbackGeneration, resolution));
        } catch (RuntimeException exception) {
            cancelResolutionTimeout();
            resolving = false;
            resolveNext(callbackGeneration);
        }
    }

    private void handleResolved(int callbackGeneration, NsdServiceInfo serviceInfo) {
        if (!isCurrent(callbackGeneration)) return;
        resolving = false;
        cancelResolutionTimeout();
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
        Network network = infrastructureNetwork(serviceInfo, host);
        int port = serviceInfo.getPort();
        if (RemoteNsdContract.API_VERSION.equals(apiVersion)
                && PairingCredentials.isValidServerId(serverId)
                && host != null && network != null && port > 0 && port <= 65_535) {
            try {
                listener.onServerFound(new DiscoveredServer(
                        serverId,
                        serviceName,
                        host,
                        port,
                        DiscoveredServer.Transport.LAN,
                        network
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
        cancelResolutionTimeout();
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
        cancelResolutionTimeout();
        ConnectionDiagnostics.record(ConnectionDiagnostics.Reason.DISCOVERY,
                DiscoveredServer.Transport.LAN, ConnectionDiagnostics.Stage.DISCOVERY,
                0L, ConnectionDiagnostics.Result.FAILED);
        listener.onDiscoveryError(errorCode);
    }

    private void cancelResolutionTimeout() {
        if (resolutionTimeout != null) mainHandler.removeCallbacks(resolutionTimeout);
        resolutionTimeout = null;
    }

    private Network infrastructureNetwork(NsdServiceInfo info, InetAddress host) {
        if (connectivityManager == null || host == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && info.getNetwork() != null) {
                Network network = info.getNetwork();
                return isLan(network) ? network : null;
            }
            // API 26–32 has no NSD Network. Require an on-link LAN route to the resolved host;
            // a default route (or Wi-Fi alone) cannot relabel a P2P/tethering advertisement as LAN.
            for (Network network : connectivityManager.getAllNetworks()) {
                if (!isLan(network)) continue;
                LinkProperties properties = connectivityManager.getLinkProperties(network);
                if (properties == null) continue;
                for (RouteInfo route : properties.getRoutes()) {
                    if (route.getDestination().getPrefixLength() > 0
                            && route.getDestination().contains(host)) return network;
                }
            }
        } catch (RuntimeException ignored) {
            // Loss during resolution is recoverable via the existing NSD/network callbacks.
        }
        return null;
    }

    private boolean isLan(Network network) {
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
        return caps != null && TransportRecoveryPolicy.isInfrastructureLan(
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P));
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
        private final int resolution;

        ResolveCallbacks(int callbackGeneration, int resolution) {
            this.callbackGeneration = callbackGeneration;
            this.resolution = resolution;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            mainHandler.post(() -> {
                if (resolution == resolutionSequence) handleResolveFailed(callbackGeneration, serviceInfo);
            });
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            mainHandler.post(() -> {
                if (resolution == resolutionSequence) handleResolved(callbackGeneration, serviceInfo);
            });
        }
    }
}
