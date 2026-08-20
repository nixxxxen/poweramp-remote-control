package dev.powerampremote.phone;

import org.junit.Test;

import static dev.powerampremote.phone.PairingRequestState.SubmissionStatus.ACCEPTED;
import static dev.powerampremote.phone.PairingRequestState.SubmissionStatus.DUPLICATE_ACTIVE;
import static dev.powerampremote.phone.PairingRequestState.SubmissionStatus.REPLAY_FAILURE;
import static dev.powerampremote.phone.PairingRequestState.SubmissionStatus.REPLAY_SUCCESS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PairingRequestStateTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String TOKEN =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String SECOND_SECRET =
            "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA";
    private static final String QR = "powerampremote://pair?api=1&id=" + SERVER_ID
            + "&secret=" + TOKEN + "&name=Cold+start";
    private static final String SECOND_QR = "powerampremote://pair?api=1&id=" + SERVER_ID
            + "&secret=" + SECOND_SECRET + "&name=Cold+start";
    private static final String FIRST_DELIVERY_ID =
            "00000000-0000-0000-0000-000000000001";
    private static final String SECOND_DELIVERY_ID =
            "00000000-0000-0000-0000-000000000002";

    @Test
    public void scannerResultSurvivesWithoutActivityBinderUntilServiceRuntimeIsReady() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);

        assertTrue(state.hasPendingRequest());
        assertFalse(state.dispatchTo(null));
        assertTrue(state.dispatchTo(target));
        assertFalse(state.hasPendingRequest());
        assertEquals(SERVER_ID, target.serverId);
        assertEquals(1, target.qrCount);
        assertFalse(state.dispatchTo(target));
    }

    @Test
    public void manualBearerRequestUsesTheSameDurableHandoff() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitManualToken("  " + TOKEN + "  ").status);
        assertTrue(state.dispatchTo(target));

        assertEquals(TOKEN, target.token);
    }

    @Test
    public void duplicateScannerDeliveryStartsOnlyOneQrExchange() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);
        assertEquals(
                DUPLICATE_ACTIVE,
                state.submitQr(SECOND_QR, FIRST_DELIVERY_ID).status
        );
        assertTrue(state.dispatchTo(target));
        assertEquals(
                DUPLICATE_ACTIVE,
                state.submitQr(SECOND_QR, FIRST_DELIVERY_ID).status
        );
        assertFalse(state.dispatchTo(target));

        assertEquals(1, target.qrCount);
        assertEquals(TOKEN, target.secret);
    }

    @Test
    public void completedScannerDeliveryReplaysItsExactSuccessOrFailure() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);
        assertTrue(state.dispatchTo(target));
        state.onPairingSucceeded("Living room player");
        PairingRequestState.Submission success = state.submitQr(QR, FIRST_DELIVERY_ID);
        assertEquals(REPLAY_SUCCESS, success.status);
        assertEquals("Living room player", success.successfulDeviceName);
        assertFalse(state.dispatchTo(target));

        state.reset();
        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);
        assertTrue(state.dispatchTo(target));
        state.onPairingFailed(RemoteClientController.PairingError.QR_REJECTED);
        PairingRequestState.Submission failure = state.submitQr(QR, FIRST_DELIVERY_ID);
        assertEquals(REPLAY_FAILURE, failure.status);
        assertEquals(RemoteClientController.PairingError.QR_REJECTED, failure.failure);
        assertFalse(state.dispatchTo(target));
    }

    @Test
    public void newScannerLaunchProcessesTheSameQrAgainForServerSideStaleFeedback() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);
        assertTrue(state.dispatchTo(target));
        state.onPairingSucceeded("Living room player");

        assertEquals(ACCEPTED, state.submitQr(QR, SECOND_DELIVERY_ID).status);
        assertTrue(state.dispatchTo(target));
        assertEquals(2, target.qrCount);
    }

    @Test
    public void manualBearerAttemptsAreNotDeduplicated() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitManualToken(TOKEN).status);
        assertTrue(state.dispatchTo(target));
        state.onPairingSucceeded("LAN-discovered player");
        assertEquals(ACCEPTED, state.submitManualToken(TOKEN).status);
        assertTrue(state.dispatchTo(target));

        assertEquals(2, target.manualCount);
    }

    @Test
    public void oldQrRedeliveryDoesNotOverrideANewerManualAttempt() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);
        assertTrue(state.dispatchTo(target));
        state.onPairingSucceeded("Living room player");
        assertEquals(ACCEPTED, state.submitManualToken(TOKEN).status);
        assertTrue(state.dispatchTo(target));

        assertEquals(
                DUPLICATE_ACTIVE,
                state.submitQr(QR, FIRST_DELIVERY_ID).status
        );
        assertFalse(state.dispatchTo(target));
        assertEquals(1, target.qrCount);
        assertEquals(1, target.manualCount);

        state.onPairingSucceeded("LAN-discovered player");
        assertEquals(
                DUPLICATE_ACTIVE,
                state.submitQr(QR, FIRST_DELIVERY_ID).status
        );
        assertFalse(state.dispatchTo(target));
    }

    @Test
    public void invalidQrNeverEntersServiceOwnedRequestState() {
        PairingRequestState state = new PairingRequestState();

        assertThrows(
                IllegalArgumentException.class,
                () -> state.submitQr("not-a-pairing-qr", FIRST_DELIVERY_ID)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> state.submitQr(QR, "not-a-delivery-id")
        );
        assertFalse(state.hasPendingRequest());
    }

    @Test
    public void resetAfterForgetOrAuthenticationFailureClearsDeliveryHistory() {
        PairingRequestState state = new PairingRequestState();
        RecordingTarget target = new RecordingTarget();

        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);
        assertTrue(state.dispatchTo(target));
        state.onPairingSucceeded("Living room player");
        assertEquals(REPLAY_SUCCESS, state.submitQr(QR, FIRST_DELIVERY_ID).status);

        state.reset();

        assertEquals(ACCEPTED, state.submitQr(QR, FIRST_DELIVERY_ID).status);
        assertTrue(state.dispatchTo(target));
        assertEquals(2, target.qrCount);
    }

    private static final class RecordingTarget implements PairingRequest.Target {
        String serverId;
        String secret;
        String token;
        int qrCount;
        int manualCount;

        @Override
        public void pairQr(PairingQrPayload payload) {
            serverId = payload.serverId;
            secret = payload.secret;
            qrCount++;
        }

        @Override
        public void pairManually(String value) {
            token = value;
            manualCount++;
        }
    }
}
