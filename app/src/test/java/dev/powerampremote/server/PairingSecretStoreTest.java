package dev.powerampremote.server;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class PairingSecretStoreTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";

    @Test
    public void offerIsAddressFreeShortLivedAndOneTime() {
        AtomicLong clock = new AtomicLong(1_000L);
        PairingSecretStore store = new PairingSecretStore(
                SERVER_ID,
                "HiBy R4",
                new CountingSecureRandom(),
                clock::get
        );

        PairingOffer offer = store.issue();
        assertTrue(store.isActive(offer));
        assertTrue(offer.qrPayload().startsWith("powerampremote://pair?api=1&id="));
        assertFalse(offer.qrPayload().contains("192.168."));
        assertFalse(offer.qrPayload().contains("8765"));

        PairingRequest request = PairingRequest.parse("{\"apiVersion\":1,"
                + "\"serverId\":\"" + SERVER_ID + "\","
                + "\"secret\":\"" + offer.secret + "\"}");
        assertTrue(store.consume(request));
        assertFalse(store.consume(request));
        assertFalse(store.isActive(offer));
    }

    @Test
    public void wrongIdentityDoesNotConsumeButExpiryAndReplacementDo() {
        AtomicLong clock = new AtomicLong(5_000L);
        PairingSecretStore store = new PairingSecretStore(
                SERVER_ID,
                "Player",
                new CountingSecureRandom(),
                clock::get
        );
        PairingOffer first = store.issue();
        PairingOffer second = store.issue();
        assertNotEquals(first.secret, second.secret);
        assertFalse(store.consume(new PairingRequest(1, SERVER_ID, first.secret)));
        assertFalse(store.consume(new PairingRequest(
                1,
                "AQEBAQEBAQEBAQEBAQEBAQ",
                second.secret
        )));
        assertTrue(store.consume(new PairingRequest(1, SERVER_ID, second.secret)));

        PairingOffer expiring = store.issue();
        clock.set(expiring.expiresAtMilliseconds);
        assertFalse(store.consume(new PairingRequest(1, SERVER_ID, expiring.secret)));
    }

    private static final class CountingSecureRandom extends SecureRandom {
        private int generation;

        @Override
        public void nextBytes(byte[] bytes) {
            generation++;
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (generation + index);
            }
        }
    }
}
