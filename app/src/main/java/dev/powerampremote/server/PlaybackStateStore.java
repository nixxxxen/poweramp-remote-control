package dev.powerampremote.server;

import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe owner of the latest Poweramp state consumed by UI and network clients. */
final class PlaybackStateStore {
    interface Listener {
        void onStateChanged(RemotePlaybackState state);
    }

    private interface Mutation {
        RemotePlaybackState apply(RemotePlaybackState current);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private RemotePlaybackState state;

    PlaybackStateStore(long nowMilliseconds) {
        state = RemotePlaybackState.initial(nowMilliseconds);
    }

    synchronized RemotePlaybackState snapshot() {
        return state;
    }

    void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void setPowerampAvailable(boolean available, long nowMilliseconds) {
        update(current -> {
            if (available) {
                return copy(
                        current,
                        true,
                        current.track,
                        current.playbackState,
                        current.positionAt(nowMilliseconds),
                        nowMilliseconds,
                        current.shuffleMode,
                        current.artworkId,
                        current.artworkAvailable,
                        current.positionAvailable
                );
            }
            return copy(
                    current,
                    false,
                    null,
                    PowerampContract.STATE_UNKNOWN,
                    0,
                    nowMilliseconds,
                    -1,
                    0L,
                    false,
                    false
            );
        });
    }

    void setTrack(TrackInfo track, long nowMilliseconds) {
        update(current -> {
            boolean sameItem = current.track != null
                    && current.track.id == track.id
                    && current.track.realId == track.realId;
            TrackInfo nextTrack = track;
            if (sameItem && track.durationSeconds <= 0 && current.track.durationSeconds > 0) {
                nextTrack = new TrackInfo(
                        track.id,
                        track.realId,
                        track.title,
                        track.album,
                        track.artist,
                        current.track.durationSeconds,
                        track.positionSeconds,
                        track.rating,
                        track.audio,
                        track.source
                );
            }
            long nextArtworkId = track.albumArtId();
            boolean keepArtwork = nextArtworkId == current.artworkId;
            int position = sameItem && current.positionAvailable
                    ? current.positionAt(nowMilliseconds)
                    : Math.max(track.positionSeconds, 0);
            return copy(
                    current,
                    true,
                    nextTrack,
                    current.playbackState,
                    position,
                    nowMilliseconds,
                    current.shuffleMode,
                    nextArtworkId,
                    keepArtwork && current.artworkAvailable,
                    sameItem
                            ? current.positionAvailable || track.positionSeconds >= 0
                            : track.positionSeconds >= 0
            );
        });
    }

    void setPlaybackState(int playbackState, int positionSeconds, long nowMilliseconds) {
        update(current -> {
            int position;
            boolean positionAvailable;
            if (positionSeconds >= 0) {
                position = positionSeconds;
                positionAvailable = true;
            } else if (playbackState == PowerampContract.STATE_STOPPED) {
                position = 0;
                positionAvailable = false;
            } else {
                position = current.positionAt(nowMilliseconds);
                positionAvailable = current.positionAvailable;
            }
            return copy(
                    current,
                    current.powerampAvailable,
                    current.track,
                    playbackState,
                    position,
                    nowMilliseconds,
                    current.shuffleMode,
                    current.artworkId,
                    current.artworkAvailable,
                    positionAvailable
            );
        });
    }

    void setPosition(int positionSeconds, long nowMilliseconds) {
        update(current -> copy(
                current,
                current.powerampAvailable,
                current.track,
                current.playbackState,
                positionSeconds,
                nowMilliseconds,
                current.shuffleMode,
                current.artworkId,
                current.artworkAvailable,
                positionSeconds >= 0
        ));
    }

    void setShuffleMode(int shuffleMode, long nowMilliseconds) {
        update(current -> copy(
                current,
                current.powerampAvailable,
                current.track,
                current.playbackState,
                current.positionAt(nowMilliseconds),
                nowMilliseconds,
                shuffleMode,
                current.artworkId,
                current.artworkAvailable,
                current.positionAvailable
        ));
    }

    void setRating(int rating, long nowMilliseconds) {
        update(current -> {
            TrackInfo track = current.track;
            if (track != null) {
                track = new TrackInfo(
                        track.id,
                        track.realId,
                        track.title,
                        track.album,
                        track.artist,
                        track.durationSeconds,
                        track.positionSeconds,
                        rating,
                        track.audio,
                        track.source
                );
            }
            return copy(
                    current,
                    current.powerampAvailable,
                    track,
                    current.playbackState,
                    current.positionAt(nowMilliseconds),
                    nowMilliseconds,
                    current.shuffleMode,
                    current.artworkId,
                    current.artworkAvailable,
                    current.positionAvailable
            );
        });
    }

    void setArtworkAvailable(long artworkId, boolean available, long nowMilliseconds) {
        update(current -> {
            if (artworkId != current.artworkId) {
                return current;
            }
            return copy(
                    current,
                    current.powerampAvailable,
                    current.track,
                    current.playbackState,
                    current.positionAt(nowMilliseconds),
                    nowMilliseconds,
                    current.shuffleMode,
                    current.artworkId,
                    available,
                    current.positionAvailable
            );
        });
    }

    private void update(Mutation mutation) {
        synchronized (this) {
            RemotePlaybackState current = state;
            RemotePlaybackState next = mutation.apply(current);
            if (next == current) {
                return;
            }
            state = next;
            // Notifications stay serialized with revisions. Listeners must only enqueue work.
            for (Listener listener : listeners) {
                listener.onStateChanged(next);
            }
        }
    }

    private static RemotePlaybackState copy(
            RemotePlaybackState current,
            boolean powerampAvailable,
            TrackInfo track,
            int playbackState,
            int positionSeconds,
            long anchorMilliseconds,
            int shuffleMode,
            long artworkId,
            boolean artworkAvailable,
            boolean positionAvailable
    ) {
        return new RemotePlaybackState(
                current.revision + 1L,
                powerampAvailable,
                track,
                playbackState,
                positionSeconds,
                anchorMilliseconds,
                shuffleMode,
                artworkId,
                artworkAvailable,
                positionAvailable
        );
    }
}
