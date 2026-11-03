package dev.powerampremote.phone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Process memory only. Its caller supplies already-safe single-line diagnostic events. */
final class ConnectionLogBuffer {
    private static final int MAX_ENTRIES = 100;
    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private boolean enabled;

    synchronized void setEnabled(boolean next) {
        enabled = next;
        if (!next) entries.clear();
    }

    synchronized void add(String line) {
        if (!enabled) return;
        if (entries.size() == MAX_ENTRIES) entries.removeFirst();
        entries.addLast(line.replace('\n', ' ').replace('\r', ' '));
    }

    synchronized List<String> snapshot() { return new ArrayList<>(entries); }
    synchronized void clear() { entries.clear(); }
}
