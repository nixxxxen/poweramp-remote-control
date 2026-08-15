package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PairingRequestStateTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String TOKEN =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String QR = "powerampremote://pair?api=1&id=" + SERVER_ID
            + "&secret=" + TOKEN + "&name=Cold+start";

    @Test
    public void requestSurvivesUntilServiceRuntimeBecomesAvailable() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        state.submit(PairingRequest.qr(QR));

        assertTrue(state.hasPendingRequest());
        assertFalse(state.dispatchTo(null));
        assertTrue(state.dispatchTo(target));
        assertFalse(state.hasPendingRequest());
        assertEquals(SERVER_ID, target.serverId);
        assertFalse(state.dispatchTo(target));
    }

    @Test
    public void manualBearerRequestUsesTheSameDurableHandoff() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        state.submit(PairingRequest.manualToken("  " + TOKEN + "  "));
        assertTrue(state.dispatchTo(target));

        assertEquals(TOKEN, target.token);
    }

    private static final class RecordingTarget implements PairingRequest.Target {
        String serverId;
        String token;

        @Override
        public void pairQr(PairingQrPayload payload) {
            serverId = payload.serverId;
        }

        @Override
        public void pairManually(String value) {
            token = value;
        }
    }
}
