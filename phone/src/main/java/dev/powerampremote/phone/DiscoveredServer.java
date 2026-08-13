package dev.powerampremote.phone;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

/** One resolved LAN or Wi-Fi Direct endpoint. Its address is intentionally not persisted. */
final class DiscoveredServer {
    enum Transport { LAN, WIFI_DIRECT }

    final String serverId;
    final String serviceName;
    final InetAddress address;
    final int port;
    final Transport transport;

    DiscoveredServer(String serverId, String serviceName, InetAddress address, int port) {
        this(serverId, serviceName, address, port, Transport.LAN);
    }

    DiscoveredServer(
            String serverId,
            String serviceName,
            InetAddress address,
            int port,
            Transport transport
    ) {
        if (!PairingCredentials.isValidServerId(serverId)) {
            throw new IllegalArgumentException("invalid server id");
        }
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("missing service name");
        }
        if (address == null || port < 1 || port > 65_535 || transport == null) {
            throw new IllegalArgumentException("invalid endpoint");
        }
        this.serverId = serverId;
        this.serviceName = serviceName;
        this.address = address;
        this.port = port;
        this.transport = transport;
    }

    URL httpUrl(String path) throws MalformedURLException {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new MalformedURLException("invalid API path");
        }
        return new URL("http://" + hostHeader() + ':' + port + path);
    }

    String hostHeader() {
        String host = address.getHostAddress();
        if (host.indexOf(':') >= 0) {
            return '[' + host.replace("%", "%25") + ']';
        }
        return host;
    }

    String addressLabel() {
        return hostHeader() + ':' + port;
    }

    boolean sameEndpoint(DiscoveredServer other) {
        return other != null
                && port == other.port
                && serverId.equals(other.serverId)
                && address.equals(other.address)
                && transport == other.transport;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DiscoveredServer)) {
            return false;
        }
        DiscoveredServer other = (DiscoveredServer) object;
        return port == other.port
                && serverId.equals(other.serverId)
                && serviceName.equals(other.serviceName)
                && address.equals(other.address)
                && transport == other.transport;
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, serviceName, address, port, transport);
    }
}
