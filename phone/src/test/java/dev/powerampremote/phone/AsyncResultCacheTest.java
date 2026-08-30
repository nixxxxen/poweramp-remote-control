package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncResultCacheTest {
    @Test
    public void cacheIsBoundedAndUsesRecentAccessForEviction() {
        AsyncResultCache<String, Integer, Integer> cache = new AsyncResultCache<>(
                2,
                Runnable::run,
                Runnable::run,
                value -> value * 10
        );

        cache.request("one", 1, (key, value) -> { });
        cache.request("two", 2, (key, value) -> { });
        assertEquals(Integer.valueOf(10), cache.getIfPresent("one"));
        cache.request("three", 3, (key, value) -> { });

        assertEquals(2, cache.sizeForTesting());
        assertEquals(Integer.valueOf(10), cache.getIfPresent("one"));
        assertNull(cache.getIfPresent("two"));
        assertEquals(Integer.valueOf(30), cache.getIfPresent("three"));
    }

    @Test
    public void repeatedInflightAndCachedRequestsAnalyzeKeyOnlyOnce() {
        QueueExecutor analysisExecutor = new QueueExecutor();
        AtomicInteger analysisCount = new AtomicInteger();
        List<Integer> results = new ArrayList<>();
        AsyncResultCache<String, Integer, Integer> cache = new AsyncResultCache<>(
                4,
                analysisExecutor,
                Runnable::run,
                value -> {
                    analysisCount.incrementAndGet();
                    return value * 10;
                }
        );

        cache.request("artwork", 7, (key, value) -> results.add(value));
        cache.request("artwork", 99, (key, value) -> results.add(value));

        assertEquals(1, analysisExecutor.size());
        assertEquals(1, cache.inFlightCountForTesting());
        analysisExecutor.runNext();
        assertEquals(1, analysisCount.get());
        assertEquals(List.of(70, 70), results);

        cache.request("artwork", 123, (key, value) -> results.add(value));
        assertEquals(1, analysisCount.get());
        assertEquals(List.of(70, 70, 70), results);
    }

    @Test
    public void rejectedAnalysisDoesNotLeaveKeyPermanentlyInflight() {
        AsyncResultCache<String, Integer, Integer> cache = new AsyncResultCache<>(
                2,
                command -> {
                    throw new IllegalStateException("queue full");
                },
                Runnable::run,
                value -> value
        );

        assertFalse(cache.request("artwork", 7, (key, value) -> { }));
        assertEquals(0, cache.inFlightCountForTesting());
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
