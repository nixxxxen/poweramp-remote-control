package dev.powerampremote.server;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Request-scoped cancellation bridge that does not depend on Android in JVM tests. */
final class LibraryCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();

    boolean isCancelled() {
        return cancelled.get();
    }

    void attach(Runnable action) {
        if (action == null) {
            return;
        }
        cancelAction.set(action);
        if (cancelled.get() && cancelAction.compareAndSet(action, null)) {
            action.run();
        }
    }

    void detach() {
        cancelAction.set(null);
    }

    void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Runnable action = cancelAction.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }
}
