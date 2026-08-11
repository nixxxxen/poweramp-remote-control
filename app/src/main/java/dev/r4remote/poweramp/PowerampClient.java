package dev.r4remote.poweramp;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Lifecycle-aware adapter around Poweramp's public Intent API.
 *
 * <p>The client deliberately does not request library/database or storage access. It listens to
 * Poweramp's sticky track/status broadcasts, asks for one position sync, and reads album art from
 * Poweramp's exported album-art content provider.</p>
 */
final class PowerampClient implements AutoCloseable {
    private static final String TAG = "PowerampClient";
    private static final int MAX_TEXT_LENGTH = 1_000;
    private static final int MAX_TRACK_SECONDS = 7 * 24 * 60 * 60;
    private static final int MAX_SAMPLE_RATE = 100_000_000;
    private static final int MAX_BIT_RATE = 1_000_000_000;
    private static final int MAX_LIST_SIZE = 10_000_000;
    private static final int MAX_CATEGORY = 10_000;
    private static final int ALBUM_ART_TARGET_PX = 720;
    private static final long SKIP_COMMAND_THROTTLE_MS = 200L;
    private static final long TOGGLE_COMMAND_THROTTLE_MS = 350L;
    private static final long SEEK_POSITION_REFRESH_DELAY_MS = 500L;

    interface Listener {
        void onAvailabilityChanged(boolean installed);

        void onTrackChanged(TrackInfo track);

        void onPlaybackStateChanged(int state, int positionSeconds);

        void onPositionChanged(int positionSeconds);

        void onShuffleModeChanged(int shuffleMode);

        void onAlbumArtChanged(long albumArtId, Bitmap bitmap);

        void onPowerampError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService albumArtExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "poweramp-album-art");
        thread.setDaemon(true);
        return thread;
    });

    private boolean started;
    private boolean trackReceiverRegistered;
    private boolean statusReceiverRegistered;
    private boolean positionReceiverRegistered;
    private boolean playingModeReceiverRegistered;
    private long albumArtGeneration;
    private long currentAlbumArtId;
    private long lastPlaybackCommandRealtime;
    private int lastPlaybackCommand = Integer.MIN_VALUE;
    private int lastEnabledShuffleMode = PowerampContract.ShuffleModes.SONGS;
    private Future<?> albumArtTask;

    private final Runnable delayedPlayingModeRefresh = this::refreshPlayingModeSnapshot;
    private final Runnable delayedPositionRefresh = () -> {
        if (started) {
            requestPositionSync();
        }
    };

    private final BroadcastReceiver trackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            processTrackIntent(intent);
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            processStatusIntent(intent);
        }
    };

    private final BroadcastReceiver positionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (!PowerampContract.ACTION_TRACK_POSITION_SYNC.equals(intent.getAction())) {
                return;
            }
            int position = readBoundedInt(
                    safeExtras(intent),
                    PowerampContract.Track.POSITION_SECONDS,
                    0,
                    MAX_TRACK_SECONDS,
                    -1
            );
            if (position >= 0) {
                listener.onPositionChanged(position);
            }
        }
    };

    private final BroadcastReceiver playingModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            processPlayingModeIntent(intent);
        }
    };

    PowerampClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;

        boolean installed = isPowerampInstalled();
        listener.onAvailabilityChanged(installed);
        if (!installed) {
            currentAlbumArtId = 0L;
            started = false;
            return;
        }

        try {
            positionReceiverRegistered = true;
            registerExternalReceiver(
                    positionReceiver,
                    new IntentFilter(PowerampContract.ACTION_TRACK_POSITION_SYNC)
            );

            trackReceiverRegistered = true;
            registerExternalReceiver(
                    trackReceiver,
                    new IntentFilter(PowerampContract.ACTION_TRACK_CHANGED)
            );

            statusReceiverRegistered = true;
            registerExternalReceiver(
                    statusReceiver,
                    new IntentFilter(PowerampContract.ACTION_STATUS_CHANGED)
            );

            playingModeReceiverRegistered = true;
            registerExternalReceiver(
                    playingModeReceiver,
                    new IntentFilter(PowerampContract.ACTION_PLAYING_MODE_CHANGED)
            );

            requestPositionSync();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to register Poweramp receivers", exception);
            unregisterReceivers();
            started = false;
            listener.onPowerampError(context.getString(R.string.error_sync));
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        albumArtGeneration++;
        mainHandler.removeCallbacks(delayedPlayingModeRefresh);
        mainHandler.removeCallbacks(delayedPositionRefresh);
        cancelAlbumArtTask();
        unregisterReceivers();
    }

    void refresh() {
        if (!started || !isPowerampInstalled()) {
            stop();
            start();
            return;
        }
        listener.onAvailabilityChanged(true);

        boolean trackRefreshed = false;
        boolean statusTrackRefreshed = false;
        long previousAlbumArtId = currentAlbumArtId;
        try {
            Intent trackIntent = queryStickyIntent(
                    new IntentFilter(PowerampContract.ACTION_TRACK_CHANGED)
            );
            trackRefreshed = processTrackIntent(trackIntent);

            Intent statusIntent = queryStickyIntent(
                    new IntentFilter(PowerampContract.ACTION_STATUS_CHANGED)
            );
            statusTrackRefreshed = processStatusIntent(statusIntent);

            Intent playingModeIntent = queryStickyIntent(
                    new IntentFilter(PowerampContract.ACTION_PLAYING_MODE_CHANGED)
            );
            processPlayingModeIntent(playingModeIntent);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to refresh Poweramp state", exception);
            listener.onPowerampError(context.getString(R.string.error_sync));
        }

        if (currentAlbumArtId > 0L
                && ((!trackRefreshed && !statusTrackRefreshed)
                || currentAlbumArtId == previousAlbumArtId)) {
            loadAlbumArt(currentAlbumArtId);
        }
        requestPositionSync();
    }

    void requestPositionSync() {
        sendApiCommand(PowerampContract.COMMAND_POSITION_SYNC, R.string.error_sync);
    }

    void skipToPrevious() {
        sendPlaybackCommand(PowerampContract.COMMAND_PREVIOUS);
    }

    void togglePlayPause() {
        sendPlaybackCommand(PowerampContract.COMMAND_TOGGLE_PLAY_PAUSE);
    }

    void play() {
        sendPlaybackCommand(PowerampContract.COMMAND_PLAY);
    }

    void pause() {
        sendPlaybackCommand(PowerampContract.COMMAND_PAUSE);
    }

    void skipToNext() {
        sendPlaybackCommand(PowerampContract.COMMAND_NEXT);
    }

    boolean seekTo(int positionSeconds) {
        if (positionSeconds < 0) {
            return false;
        }
        boolean sent = sendApiCommand(
                PowerampContract.COMMAND_SEEK,
                R.string.error_command,
                PowerampContract.Track.POSITION_SECONDS,
                positionSeconds
        );
        if (sent) {
            // POS_SYNC has an explicit response contract. This one-shot refresh keeps the
            // shared state authoritative without treating command dispatch as confirmation.
            mainHandler.postDelayed(delayedPositionRefresh, SEEK_POSITION_REFRESH_DELAY_MS);
        }
        return sent;
    }

    boolean setRating(int rating) {
        if (rating < 0 || rating > 5) {
            return false;
        }
        return sendApiCommand(
                PowerampContract.COMMAND_SET_RATING,
                R.string.error_command,
                PowerampContract.EXTRA_RATING,
                rating
        );
    }

    boolean setShuffleEnabled(boolean enabled) {
        boolean sent = sendApiCommand(
                PowerampContract.COMMAND_SHUFFLE,
                R.string.error_command,
                PowerampContract.EXTRA_SHUFFLE,
                enabled
                        ? lastEnabledShuffleMode
                        : PowerampContract.ShuffleModes.NONE
        );
        if (sent) {
            schedulePlayingModeRefresh();
        }
        return sent;
    }

    private void sendPlaybackCommand(int command) {
        long now = SystemClock.elapsedRealtime();
        long throttleMilliseconds = command == PowerampContract.COMMAND_TOGGLE_PLAY_PAUSE
                ? TOGGLE_COMMAND_THROTTLE_MS
                : SKIP_COMMAND_THROTTLE_MS;
        if (command == lastPlaybackCommand
                && now - lastPlaybackCommandRealtime < throttleMilliseconds) {
            return;
        }
        if (sendApiCommand(command, R.string.error_command)) {
            lastPlaybackCommand = command;
            lastPlaybackCommandRealtime = now;
        }
    }

    private boolean sendApiCommand(int command, int errorMessageResource) {
        return sendApiCommand(command, errorMessageResource, null, 0);
    }

    private boolean sendApiCommand(
            int command,
            int errorMessageResource,
            String intExtraKey,
            int intExtraValue
    ) {
        if (!started) {
            listener.onPowerampError(context.getString(errorMessageResource));
            return false;
        }
        if (!isPowerampInstalled()) {
            listener.onAvailabilityChanged(false);
            return false;
        }

        try {
            Intent intent = new Intent(PowerampContract.ACTION_API_COMMAND)
                    .setComponent(new ComponentName(
                            PowerampContract.PACKAGE_NAME,
                            PowerampContract.API_RECEIVER_NAME
                    ))
                    .putExtra(PowerampContract.EXTRA_COMMAND, command)
                    .putExtra(PowerampContract.EXTRA_PACKAGE, context.getPackageName());
            if (intExtraKey != null) {
                intent.putExtra(intExtraKey, intExtraValue);
            }
            context.sendBroadcast(intent);
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to send Poweramp command " + command, exception);
            listener.onPowerampError(context.getString(errorMessageResource));
            return false;
        }
    }

    @Override
    public void close() {
        stop();
        albumArtExecutor.shutdownNow();
    }

    @SuppressWarnings("deprecation")
    private boolean isPowerampInstalled() {
        try {
            context.getPackageManager().getApplicationInfo(PowerampContract.PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private Intent registerExternalReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        }
        return context.registerReceiver(receiver, filter);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private Intent queryStickyIntent(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(null, filter, Context.RECEIVER_EXPORTED);
        }
        return context.registerReceiver(null, filter);
    }

    private void unregisterReceivers() {
        if (trackReceiverRegistered) {
            safeUnregister(trackReceiver);
            trackReceiverRegistered = false;
        }
        if (statusReceiverRegistered) {
            safeUnregister(statusReceiver);
            statusReceiverRegistered = false;
        }
        if (positionReceiverRegistered) {
            safeUnregister(positionReceiver);
            positionReceiverRegistered = false;
        }
        if (playingModeReceiverRegistered) {
            safeUnregister(playingModeReceiver);
            playingModeReceiverRegistered = false;
        }
    }

    private void safeUnregister(BroadcastReceiver receiver) {
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already removed by the framework or partial registration failed.
        }
    }

    private boolean processTrackIntent(Intent intent) {
        if (intent == null
                || (!PowerampContract.ACTION_TRACK_CHANGED.equals(intent.getAction())
                && !PowerampContract.ACTION_STATUS_CHANGED.equals(intent.getAction()))) {
            return false;
        }

        Bundle topLevel = safeExtras(intent);
        Bundle trackBundle = readTrackBundle(intent);
        if (trackBundle == null && topLevel == null) {
            return false;
        }

        long id = readLongPrefer(trackBundle, topLevel, PowerampContract.Track.ID, 0L);
        long realId = readLongPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.REAL_ID,
                0L
        );
        String title = readTextPrefer(trackBundle, topLevel, PowerampContract.Track.TITLE);
        String album = readTextPrefer(trackBundle, topLevel, PowerampContract.Track.ALBUM);
        String artist = readTextPrefer(trackBundle, topLevel, PowerampContract.Track.ARTIST);

        int durationMilliseconds = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.DURATION_MILLISECONDS,
                0,
                MAX_TRACK_SECONDS * 1_000,
                -1
        );
        int durationSeconds = durationMilliseconds >= 0
                ? (durationMilliseconds + 999) / 1_000
                : readBoundedIntPrefer(
                        trackBundle,
                        topLevel,
                        PowerampContract.Track.DURATION_SECONDS,
                        0,
                        MAX_TRACK_SECONDS,
                        0
                );
        int positionSeconds = readBoundedInt(
                topLevel,
                PowerampContract.Track.POSITION_SECONDS,
                0,
                MAX_TRACK_SECONDS,
                -1
        );
        int rating = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.RATING,
                0,
                5,
                -1
        );
        int fileType = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.FILE_TYPE,
                PowerampContract.FileTypes.UNKNOWN,
                PowerampContract.FileTypes.MAX,
                PowerampContract.FileTypes.UNKNOWN
        );
        String codec = readTextPrefer(trackBundle, topLevel, PowerampContract.Track.CODEC);
        int sampleRate = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.SAMPLE_RATE,
                1,
                MAX_SAMPLE_RATE,
                -1
        );
        int bitsPerSample = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.BITS_PER_SAMPLE,
                1,
                128,
                -1
        );
        int bitRate = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.BIT_RATE,
                1,
                MAX_BIT_RATE,
                -1
        );
        int category = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.CATEGORY,
                0,
                MAX_CATEGORY,
                -1
        );
        String categoryUri = readUriTextPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.CATEGORY_URI
        );
        int positionInList = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.POSITION_IN_LIST,
                0,
                MAX_LIST_SIZE,
                -1
        );
        int listSize = readBoundedIntPrefer(
                trackBundle,
                topLevel,
                PowerampContract.Track.LIST_SIZE,
                0,
                MAX_LIST_SIZE,
                -1
        );

        // Ignore unrelated/malformed broadcasts with no recognizable track data.
        if (id == 0L && realId == 0L && title == null && artist == null && album == null) {
            return false;
        }

        TrackInfo track = new TrackInfo(
                id,
                realId,
                title,
                album,
                artist,
                durationSeconds,
                positionSeconds,
                rating,
                new TrackInfo.AudioProperties(
                        fileType,
                        codec,
                        sampleRate,
                        bitsPerSample,
                        bitRate
                ),
                new TrackInfo.PlaybackSource(
                        category,
                        categoryUri,
                        positionInList,
                        listSize
                )
        );
        long nextAlbumArtId = track.albumArtId();
        boolean albumArtChanged = nextAlbumArtId != currentAlbumArtId;
        listener.onTrackChanged(track);
        currentAlbumArtId = nextAlbumArtId;
        if (albumArtChanged) {
            loadAlbumArt(currentAlbumArtId);
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private Bundle readTrackBundle(Intent intent) {
        try {
            return intent.getBundleExtra(PowerampContract.EXTRA_TRACK);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Poweramp track bundle could not be read", exception);
            return null;
        }
    }

    private boolean processStatusIntent(Intent intent) {
        if (intent == null || !PowerampContract.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
            return false;
        }
        boolean trackRefreshed = processTrackIntent(intent);
        Bundle extras = safeExtras(intent);
        int state = readBoundedInt(
                extras,
                PowerampContract.EXTRA_STATE,
                PowerampContract.STATE_UNKNOWN,
                PowerampContract.STATE_PAUSED,
                PowerampContract.STATE_UNKNOWN
        );
        int position = readBoundedInt(
                extras,
                PowerampContract.Track.POSITION_SECONDS,
                0,
                MAX_TRACK_SECONDS,
                -1
        );
        listener.onPlaybackStateChanged(state, position);
        return trackRefreshed;
    }

    private void processPlayingModeIntent(Intent intent) {
        if (intent == null
                || !PowerampContract.ACTION_PLAYING_MODE_CHANGED.equals(intent.getAction())) {
            return;
        }
        int shuffleMode = readBoundedInt(
                safeExtras(intent),
                PowerampContract.EXTRA_SHUFFLE,
                PowerampContract.ShuffleModes.NONE,
                PowerampContract.ShuffleModes.MAX,
                -1
        );
        if (shuffleMode >= 0) {
            if (shuffleMode > PowerampContract.ShuffleModes.NONE) {
                lastEnabledShuffleMode = shuffleMode;
            }
            listener.onShuffleModeChanged(shuffleMode);
        }
    }

    private void schedulePlayingModeRefresh() {
        mainHandler.removeCallbacks(delayedPlayingModeRefresh);
        mainHandler.postDelayed(delayedPlayingModeRefresh, 350L);
    }

    private void refreshPlayingModeSnapshot() {
        if (!started || !isPowerampInstalled()) {
            return;
        }
        try {
            processPlayingModeIntent(queryStickyIntent(
                    new IntentFilter(PowerampContract.ACTION_PLAYING_MODE_CHANGED)
            ));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to refresh Poweramp playing mode", exception);
        }
    }

    private void loadAlbumArt(long albumArtId) {
        final long generation = ++albumArtGeneration;
        cancelAlbumArtTask();
        if (albumArtId <= 0L) {
            listener.onAlbumArtChanged(albumArtId, null);
            return;
        }

        albumArtTask = albumArtExecutor.submit(() -> {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            Bitmap bitmap = decodeAlbumArt(albumArtId);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            mainHandler.post(() -> {
                if (started && generation == albumArtGeneration) {
                    listener.onAlbumArtChanged(albumArtId, bitmap);
                }
            });
        });
    }

    private void cancelAlbumArtTask() {
        if (albumArtTask != null) {
            albumArtTask.cancel(true);
            albumArtTask = null;
        }
    }

    private Bitmap decodeAlbumArt(long albumArtId) {
        Uri uri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(PowerampContract.ALBUM_ART_AUTHORITY)
                .appendPath("files")
                .appendPath(Long.toString(albumArtId))
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, sourceInfo) -> {
                    Size size = info.getSize();
                    int width = size.getWidth();
                    int height = size.getHeight();
                    int longestSide = Math.max(width, height);
                    if (longestSide > ALBUM_ART_TARGET_PX) {
                        float scale = (float) ALBUM_ART_TARGET_PX / longestSide;
                        decoder.setTargetSize(
                                Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale))
                        );
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            return decodeAlbumArtLegacy(uri);
        } catch (IOException | RuntimeException exception) {
            Log.d(TAG, "No album art for " + albumArtId, exception);
            return null;
        } catch (OutOfMemoryError error) {
            Log.w(TAG, "Album art is too large to decode", error);
            return null;
        }
    }

    private Bitmap decodeAlbumArtLegacy(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private static int calculateSampleSize(int width, int height) {
        int sampleSize = 1;
        while (width / (sampleSize * 2) >= ALBUM_ART_TARGET_PX
                || height / (sampleSize * 2) >= ALBUM_ART_TARGET_PX) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static String readText(Bundle bundle, String key) {
        if (bundle == null) {
            return null;
        }
        String value;
        try {
            value = bundle.getString(key);
        } catch (RuntimeException exception) {
            return null;
        }
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    private static long readLong(Bundle bundle, String key, long fallback) {
        if (bundle == null) {
            return fallback;
        }
        try {
            return bundle.getLong(key, fallback);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String readTextPrefer(Bundle primary, Bundle secondary, String key) {
        String value = readText(primary, key);
        return value != null ? value : readText(secondary, key);
    }

    private static long readLongPrefer(
            Bundle primary,
            Bundle secondary,
            String key,
            long fallback
    ) {
        long value = readLong(primary, key, Long.MIN_VALUE);
        return value != Long.MIN_VALUE
                ? value
                : readLong(secondary, key, fallback);
    }

    private static int readBoundedIntPrefer(
            Bundle primary,
            Bundle secondary,
            String key,
            int min,
            int max,
            int fallback
    ) {
        int value = readBoundedInt(primary, key, min, max, Integer.MIN_VALUE);
        return value != Integer.MIN_VALUE
                ? value
                : readBoundedInt(secondary, key, min, max, fallback);
    }

    private static String readUriTextPrefer(Bundle primary, Bundle secondary, String key) {
        String value = readUriText(primary, key);
        return value != null ? value : readUriText(secondary, key);
    }

    @SuppressWarnings("deprecation")
    private static String readUriText(Bundle bundle, String key) {
        if (bundle == null) {
            return null;
        }
        try {
            Uri value = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? bundle.getParcelable(key, Uri.class)
                    : bundle.getParcelable(key);
            if (value != null) {
                String text = value.toString();
                return text.length() <= MAX_TEXT_LENGTH
                        ? text
                        : text.substring(0, MAX_TEXT_LENGTH);
            }
        } catch (RuntimeException ignored) {
            // Some Poweramp builds/integrations expose catUri as text instead of Uri.
        }
        return readText(bundle, key);
    }

    private static Bundle safeExtras(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            return intent.getExtras();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Poweramp extras could not be read", exception);
            return null;
        }
    }

    private static int readBoundedInt(Bundle bundle, String key, int min, int max, int fallback) {
        try {
            if (bundle == null || !bundle.containsKey(key)) {
                return fallback;
            }
            int number = bundle.getInt(key);
            return number >= min && number <= max ? number : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
