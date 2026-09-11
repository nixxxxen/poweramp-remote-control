package dev.powerampremote.server;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class PagingSessionStoreTest {
    @Test
    public void immutableSnapshotContinuesPastOneThousandWithoutGapsOrDuplicates()
            throws Exception {
        AtomicLong clock = new AtomicLong(100L);
        PagingSessionStore<Integer> store = new PagingSessionStore<>(
                2, 1_000L, clock::get, new SecureRandom()
        );
        List<Integer> snapshot = new ArrayList<>();
        for (int value = 0; value < 1_237; value++) snapshot.add(value);

        List<Integer> delivered = new ArrayList<>();
        PagingSessionStore.Page<Integer> page = store.firstPage(
                "all-tracks", snapshot, 73, "metadata"
        );
        while (true) {
            delivered.addAll(page.items);
            assertEquals("metadata", page.metadata);
            if (page.nextPageToken == null) break;
            page = store.nextPage(page.nextPageToken, "all-tracks", 73);
        }

        assertEquals(snapshot, delivered);
        assertEquals(0, store.activeSessions());
    }

    @Test
    public void expiryEvictionDiscardAndShutdownReleaseSnapshotSessions() throws Exception {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger released = new AtomicInteger();
        PagingSessionStore<Integer> store = new PagingSessionStore<>(
                2, 10L, clock::get, new SecureRandom(), released::incrementAndGet
        );

        String first = store.firstPage("first", List.of(1, 2), 1, null).nextPageToken;
        String second = store.firstPage("second", List.of(3, 4), 1, null).nextPageToken;
        assertNotNull(first);
        assertNotNull(second);
        String third = store.firstPage("third", List.of(5, 6), 1, null).nextPageToken;
        assertEquals(1, released.get());
        expectInvalid(() -> store.nextPage(first, "first", 1));

        store.discard(second);
        assertEquals(2, released.get());
        expectInvalid(() -> store.nextPage(second, "second", 1));

        clock.addAndGet(10L);
        assertEquals(0, store.activeSessions());
        assertEquals(3, released.get());
        expectInvalid(() -> store.nextPage(third, "third", 1));

        String shutdown = store.firstPage(
                "shutdown", List.of(7, 8), 1, null
        ).nextPageToken;
        store.close();
        assertEquals(4, released.get());
        expectInvalid(() -> store.nextPage(shutdown, "shutdown", 1));
    }

    @Test
    public void continuationIsBoundToItsOriginalQueryAndStableOnRetry() throws Exception {
        PagingSessionStore<Integer> store = new PagingSessionStore<>(
                1, 1_000L, () -> 0L, new SecureRandom()
        );
        String firstToken = store.firstPage("query-a", List.of(1, 2, 3), 1, null)
                .nextPageToken;
        expectInvalid(() -> store.nextPage(firstToken, "query-b", 1));

        PagingSessionStore.Page<Integer> firstAttempt = store.nextPage(
                firstToken, "query-a", 1
        );
        PagingSessionStore.Page<Integer> retry = store.nextPage(
                firstToken, "query-a", 1
        );
        assertEquals(firstAttempt.items, retry.items);
        assertEquals(firstAttempt.nextPageToken, retry.nextPageToken);
        assertNull(store.nextPage(
                firstAttempt.nextPageToken, "query-a", 1
        ).nextPageToken);
    }

    @Test
    public void targetedInvalidationClosesOnlyMatchingSnapshots() throws Exception {
        PagingSessionStore<Integer> store = new PagingSessionStore<>(
                3, 1_000L, () -> 0L, new SecureRandom()
        );
        String queue = store.firstPage("queue\n/provider", List.of(1, 2), 1, null)
                .nextPageToken;
        String library = store.firstPage("tracks\n/provider", List.of(3, 4), 1, null)
                .nextPageToken;

        store.discardMatching(key -> key.startsWith("queue\n"));

        expectInvalid(() -> store.nextPage(queue, "queue\n/provider", 1));
        assertEquals(List.of(4), store.nextPage(library, "tracks\n/provider", 1).items);
    }

    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static void expectInvalid(ThrowingOperation operation) throws Exception {
        try {
            operation.run();
            fail("Expected invalid token");
        } catch (PagingSessionStore.InvalidTokenException expected) {
            // Expected.
        }
    }
}
