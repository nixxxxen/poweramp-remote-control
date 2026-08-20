package dev.powerampremote.phone;

import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Arrays;

/** Media3 player facade whose only playback target is the paired remote Server. */
@OptIn(markerClass = UnstableApi.class)
final class RemoteSessionPlayer extends SimpleBasePlayer {
    interface CommandSink {
        void play();
        void pause();
        void previous();
        void next();
        void seek(int positionSeconds);
        void setVolume(int volume);
    }

    private static final int CURRENT_ITEM_INDEX = 1;

    private final CommandSink commandSink;
    private RemoteState remoteState;
    private boolean connected;
    private byte[] artworkData;
    private String artworkMediaId;
    private PlaybackUiSnapshot playbackSnapshot;

    RemoteSessionPlayer(Looper looper, CommandSink commandSink) {
        super(looper);
        this.commandSink = commandSink;
    }

    void updateRemoteState(RemoteState state, PlaybackUiSnapshot playbackSnapshot) {
        String nextMediaId = RemoteSessionSnapshot.from(state, connected).mediaId;
        if (!nextMediaId.equals(artworkMediaId)) {
            artworkData = null;
            artworkMediaId = nextMediaId;
        }
        remoteState = state;
        this.playbackSnapshot = playbackSnapshot;
        invalidateState();
    }

    void updateConnection(boolean connected) {
        if (this.connected == connected) return;
        this.connected = connected;
        invalidateState();
    }

    void updateArtwork(byte[] artwork) {
        RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.from(remoteState, connected);
        artworkMediaId = snapshot.mediaId;
        artworkData = artwork == null ? null : artwork.clone();
        invalidateState();
    }

    @Override
    protected State getState() {
        RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.from(remoteState, connected);
        Player.Commands commands = buildCommands(snapshot);
        State.Builder builder = new State.Builder()
                .setAvailableCommands(commands)
                .setDeviceInfo(new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                        .setMinVolume(0)
                        .setMaxVolume(snapshot.volumeMax)
                        .build())
                .setDeviceVolume(snapshot.volume)
                .setPlayWhenReady(
                        snapshot.playing,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
                );
        if (!snapshot.hasTrack) {
            return builder.setPlaybackState(Player.STATE_IDLE).build();
        }

        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setTitle(snapshot.title)
                .setArtist(snapshot.artist)
                .setAlbumTitle(snapshot.album)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC);
        if (artworkData != null && snapshot.mediaId.equals(artworkMediaId)) {
            metadataBuilder.setArtworkData(
                    artworkData,
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER
            );
        }
        MediaMetadata metadata = metadataBuilder.build();
        MediaItem currentItem = new MediaItem.Builder()
                .setMediaId(snapshot.mediaId)
                .setMediaMetadata(metadata)
                .build();
        MediaItem previousItem = new MediaItem.Builder()
                .setMediaId(snapshot.mediaId + ":previous")
                .build();
        MediaItem nextItem = new MediaItem.Builder()
                .setMediaId(snapshot.mediaId + ":next")
                .build();

        MediaItemData previousData = new MediaItemData.Builder("previous:" + snapshot.mediaId)
                .setMediaItem(previousItem)
                .setIsPlaceholder(true)
                .setIsSeekable(false)
                .build();
        MediaItemData currentData = new MediaItemData.Builder("current:" + snapshot.mediaId)
                .setMediaItem(currentItem)
                .setMediaMetadata(metadata)
                .setDurationUs(snapshot.durationMilliseconds > 0L
                        ? snapshot.durationMilliseconds * 1_000L : C.TIME_UNSET)
                .setIsSeekable(snapshot.seekAvailable)
                .build();
        MediaItemData nextData = new MediaItemData.Builder("next:" + snapshot.mediaId)
                .setMediaItem(nextItem)
                .setIsPlaceholder(true)
                .setIsSeekable(false)
                .build();

        return builder
                .setPlaylist(Arrays.asList(previousData, currentData, nextData))
                .setCurrentMediaItemIndex(CURRENT_ITEM_INDEX)
                .setContentPositionMs(positionSupplier(
                        snapshot,
                        playbackSnapshot
                ))
                .setPlaybackState(Player.STATE_READY)
                .build();
    }

    private static PositionSupplier positionSupplier(
            RemoteSessionSnapshot snapshot,
            PlaybackUiSnapshot playbackSnapshot
    ) {
        if (playbackSnapshot == null) {
            return PositionSupplier.getConstant(snapshot.positionMilliseconds);
        }
        if (!snapshot.playing || !playbackSnapshot.isAdvancing()) {
            return PositionSupplier.getConstant(
                    playbackSnapshot.positionMillisecondsAt(SystemClock.elapsedRealtime())
            );
        }
        return () -> playbackSnapshot.positionMillisecondsAt(SystemClock.elapsedRealtime());
    }

    @SuppressWarnings("deprecation")
    private static Player.Commands buildCommands(RemoteSessionSnapshot snapshot) {
        Player.Commands.Builder commands = new Player.Commands.Builder()
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA);
        if (snapshot.controlsAvailable) {
            commands.add(Player.COMMAND_PLAY_PAUSE);
        }
        if (snapshot.controlsAvailable && snapshot.hasTrack) {
            commands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT);
        }
        if (snapshot.seekAvailable) {
            commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        }
        if (snapshot.volumeAvailable) {
            commands.add(Player.COMMAND_GET_DEVICE_VOLUME);
        }
        if (snapshot.volumeControlAvailable) {
            commands.add(Player.COMMAND_SET_DEVICE_VOLUME)
                    .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                    .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                    .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS);
        }
        return commands.build();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        if (playWhenReady) commandSink.play();
        else commandSink.pause();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(
            int mediaItemIndex,
            long positionMs,
            @Player.Command int seekCommand
    ) {
        if (seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                || seekCommand == Player.COMMAND_SEEK_TO_NEXT
                || mediaItemIndex > CURRENT_ITEM_INDEX) {
            commandSink.next();
        } else if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS
                || mediaItemIndex < CURRENT_ITEM_INDEX) {
            commandSink.previous();
        } else {
            long resolvedPosition = positionMs == C.TIME_UNSET ? 0L : Math.max(positionMs, 0L);
            long seconds = (resolvedPosition + 500L) / 1_000L;
            commandSink.seek(seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetDeviceVolume(int volume, int flags) {
        RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.from(remoteState, connected);
        if (snapshot.volumeControlAvailable) {
            commandSink.setVolume(Math.max(0, Math.min(volume, snapshot.volumeMax)));
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleIncreaseDeviceVolume(int flags) {
        RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.from(remoteState, connected);
        if (snapshot.volumeControlAvailable && snapshot.volume < snapshot.volumeMax) {
            commandSink.setVolume(snapshot.volume + 1);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleDecreaseDeviceVolume(int flags) {
        RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.from(remoteState, connected);
        if (snapshot.volumeControlAvailable && snapshot.volume > 0) {
            commandSink.setVolume(snapshot.volume - 1);
        }
        return Futures.immediateVoidFuture();
    }
}
