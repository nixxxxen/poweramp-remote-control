package dev.r4remote.poweramp;

/** Small synchronized state machine used to invalidate work queued across service restarts. */
final class ServiceLifecycleState {
    private int generation;
    private boolean running;
    private boolean closed;

    synchronized int start() {
        if (closed) {
            return -1;
        }
        if (!running) {
            running = true;
            generation++;
        }
        return generation;
    }

    synchronized boolean stop() {
        if (closed || !running) {
            return false;
        }
        running = false;
        generation++;
        return true;
    }

    synchronized boolean close() {
        if (closed) {
            return false;
        }
        closed = true;
        running = false;
        generation++;
        return true;
    }

    synchronized int runningGeneration() {
        return running && !closed ? generation : -1;
    }

    synchronized boolean isRunning() {
        return running && !closed;
    }

    synchronized boolean isRunning(int expectedGeneration) {
        return running && !closed && generation == expectedGeneration;
    }
}
