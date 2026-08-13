package dev.powerampremote.server;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

/** Event-driven access to the player device's Android music-stream volume. */
final class SystemMediaVolumeController implements AutoCloseable {
    private static final String TAG = "SystemMediaVolume";

    interface Listener {
        void onVolumeChanged(State state);
    }

    static final class State {
        final int volume;
        final int volumeMax;
        final boolean controlAvailable;

        State(int volume, int volumeMax, boolean controlAvailable) {
            this.volume = volume;
            this.volumeMax = volumeMax;
            this.controlAvailable = controlAvailable;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof State)) return false;
            State state = (State) other;
            return volume == state.volume
                    && volumeMax == state.volumeMax
                    && controlAvailable == state.controlAvailable;
        }

        @Override
        public int hashCode() {
            int result = volume;
            result = 31 * result + volumeMax;
            return 31 * result + (controlAvailable ? 1 : 0);
        }
    }

    private final ContentResolver contentResolver;
    private final AudioManager audioManager;
    private final Listener listener;
    private final ContentObserver volumeObserver;

    private State state = new State(-1, -1, false);
    private boolean started;
    private boolean closed;

    SystemMediaVolumeController(Context context, Handler mainHandler, Listener listener) {
        Context applicationContext = context.getApplicationContext();
        contentResolver = applicationContext.getContentResolver();
        audioManager = applicationContext.getSystemService(AudioManager.class);
        this.listener = listener;
        volumeObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refresh(false);
            }
        };
    }

    void start() {
        if (closed) return;
        if (!started) {
            started = true;
            try {
                contentResolver.registerContentObserver(
                        Settings.System.CONTENT_URI,
                        true,
                        volumeObserver
                );
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to observe system media volume", exception);
            }
        }
        refresh(true);
    }

    void stop() {
        if (!started) return;
        started = false;
        try {
            contentResolver.unregisterContentObserver(volumeObserver);
        } catch (RuntimeException ignored) {
            // Process teardown can race a resolver registration failure.
        }
    }

    boolean setVolume(int volume) {
        State current = readState();
        if (!isRequestedVolumeValid(volume, current.volumeMax, current.controlAvailable)
                || audioManager == null) {
            Log.w(TAG, "Rejected media volume=" + volume + ", max=" + current.volumeMax
                    + ", controllable=" + current.controlAvailable);
            refresh(false);
            return false;
        }
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            Log.i(TAG, "Player media volume requested=" + volume);
            refresh(false);
            return true;
        } catch (SecurityException | IllegalArgumentException exception) {
            Log.w(TAG, "Unable to set player media volume", exception);
            refresh(false);
            return false;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Android audio service rejected player media volume", exception);
            refresh(false);
            return false;
        }
    }

    static boolean isRequestedVolumeValid(int volume, int volumeMax, boolean controlAvailable) {
        return controlAvailable && volume >= 0 && volumeMax >= 0 && volume <= volumeMax;
    }

    private void refresh(boolean force) {
        State next = readState();
        if (!force && next.equals(state)) return;
        state = next;
        Log.d(TAG, "Player media volume=" + next.volume + '/' + next.volumeMax
                + ", controllable=" + next.controlAvailable);
        listener.onVolumeChanged(next);
    }

    private State readState() {
        if (audioManager == null) return new State(-1, -1, false);
        try {
            int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean available = !audioManager.isVolumeFixed() && maximum > 0;
            return new State(current, maximum, available);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read player media volume", exception);
            return new State(-1, -1, false);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
    }
}
