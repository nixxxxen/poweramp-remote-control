package dev.powerampremote.phone;

/** Presentation-neutral view of the single saved slot, ready for a future device collection. */
final class PlayerDeviceSnapshot {
    enum Connection { CONNECTED, DISCONNECTED }
    enum Transport { LAN, WIFI_DIRECT, NONE }

    final boolean saved;
    final String deviceName;
    final String serviceName;
    final String serverId;
    final Connection connection;
    final Transport transport;
    final String endpoint;
    final RemoteClientController.Status runtimeStatus;
    final int apiVersion;

    PlayerDeviceSnapshot(
            boolean saved,
            String deviceName,
            String serviceName,
            String serverId,
            Connection connection,
            Transport transport,
            String endpoint,
            RemoteClientController.Status runtimeStatus,
            int apiVersion
    ) {
        this.saved = saved;
        this.deviceName = deviceName;
        this.serviceName = serviceName;
        this.serverId = serverId;
        this.connection = connection;
        this.transport = transport;
        this.endpoint = endpoint;
        this.runtimeStatus = runtimeStatus;
        this.apiVersion = apiVersion;
    }
}
