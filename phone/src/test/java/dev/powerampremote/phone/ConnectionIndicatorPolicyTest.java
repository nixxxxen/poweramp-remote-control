package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.lang.reflect.Field;

public final class ConnectionIndicatorPolicyTest {
    @Test
    public void everyControllerStatusHasPresentationState() {
        for (RemoteClientController.Status status : RemoteClientController.Status.values()) {
            assertNotNull(status.name(), ConnectionIndicatorPolicy.resolve(status));
        }
    }

    @Test
    public void connectedStatusesExposeTransportLabels() {
        assertEquals(
                ConnectionIndicatorPolicy.PresentationState.LAN,
                ConnectionIndicatorPolicy.resolve(RemoteClientController.Status.CONNECTED)
        );
        assertEquals(
                ConnectionIndicatorPolicy.PresentationState.WI_FI_DIRECT,
                ConnectionIndicatorPolicy.resolve(
                        RemoteClientController.Status.CONNECTED_DIRECT
                )
        );
    }

    @Test
    public void discoveryConnectionAndRetryStatusesAreConnecting() {
        RemoteClientController.Status[] statuses = {
                RemoteClientController.Status.SEARCHING,
                RemoteClientController.Status.PAIRING,
                RemoteClientController.Status.VERIFYING,
                RemoteClientController.Status.CONNECTING,
                RemoteClientController.Status.DIRECT_SEARCHING,
                RemoteClientController.Status.DIRECT_CONNECTING,
                RemoteClientController.Status.RETRYING
        };
        for (RemoteClientController.Status status : statuses) {
            assertEquals(
                    status.name(),
                    ConnectionIndicatorPolicy.PresentationState.CONNECTING,
                    ConnectionIndicatorPolicy.resolve(status)
            );
        }
    }

    @Test
    public void actionAuthUnsupportedAndErrorStatusesAreDisconnected() {
        RemoteClientController.Status[] statuses = {
                RemoteClientController.Status.DIRECT_PERMISSION_REQUIRED,
                RemoteClientController.Status.DIRECT_LOCATION_REQUIRED,
                RemoteClientController.Status.DIRECT_WIFI_REQUIRED,
                RemoteClientController.Status.DIRECT_UNSUPPORTED,
                RemoteClientController.Status.DIRECT_ACTION_REQUIRED,
                RemoteClientController.Status.AUTH_REQUIRED,
                RemoteClientController.Status.ERROR
        };
        for (RemoteClientController.Status status : statuses) {
            assertEquals(
                    status.name(),
                    ConnectionIndicatorPolicy.PresentationState.DISCONNECTED,
                    ConnectionIndicatorPolicy.resolve(status)
            );
        }
        assertEquals(
                ConnectionIndicatorPolicy.PresentationState.DISCONNECTED,
                ConnectionIndicatorPolicy.resolve(null)
        );
    }

    @Test
    public void duplicateOrEquivalentStatusDoesNotRequestAnotherRender() {
        ConnectionIndicatorPolicy.Tracker tracker =
                new ConnectionIndicatorPolicy.Tracker();
        assertEquals(
                ConnectionIndicatorPolicy.PresentationState.CONNECTING,
                tracker.update(RemoteClientController.Status.SEARCHING)
        );
        assertNull(tracker.update(RemoteClientController.Status.SEARCHING));
        assertNull(tracker.update(RemoteClientController.Status.CONNECTING));
        assertEquals(
                ConnectionIndicatorPolicy.PresentationState.LAN,
                tracker.update(RemoteClientController.Status.CONNECTED)
        );
        assertNull(tracker.update(RemoteClientController.Status.CONNECTED));
    }

    @Test
    public void presentationPolicyCarriesNoConnectionDebugText() {
        assertEquals(4, ConnectionIndicatorPolicy.PresentationState.values().length);
        for (ConnectionIndicatorPolicy.PresentationState state
                : ConnectionIndicatorPolicy.PresentationState.values()) {
            String name = state.name();
            assertFalse(name.contains("ENDPOINT"));
            assertFalse(name.contains("SERVER"));
            assertFalse(name.contains("API"));
        }
        for (Field field : ConnectionIndicatorPolicy.class.getDeclaredFields()) {
            assertFalse(field.getType() == String.class);
        }
        for (Field field : ConnectionIndicatorPolicy.Tracker.class.getDeclaredFields()) {
            assertFalse(field.getType() == String.class);
        }
    }
}
