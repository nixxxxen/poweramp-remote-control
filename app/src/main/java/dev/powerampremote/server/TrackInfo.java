package dev.powerampremote.server;

final class TrackInfo {
    final long id;
    final long realId;
    final String title;
    final String album;
    final String artist;
    final int durationSeconds;
    final int positionSeconds;
    final int rating;
    final AudioProperties audio;
    final PlaybackSource source;

    TrackInfo(
            long id,
            long realId,
            String title,
            String album,
            String artist,
            int durationSeconds,
            int positionSeconds
    ) {
        this(
                id,
                realId,
                title,
                album,
                artist,
                durationSeconds,
                positionSeconds,
                -1,
                AudioProperties.UNKNOWN,
                PlaybackSource.UNKNOWN
        );
    }

    TrackInfo(
            long id,
            long realId,
            String title,
            String album,
            String artist,
            int durationSeconds,
            int positionSeconds,
            int rating,
            AudioProperties audio,
            PlaybackSource source
    ) {
        this.id = id;
        this.realId = realId;
        this.title = title;
        this.album = album;
        this.artist = artist;
        this.durationSeconds = durationSeconds;
        this.positionSeconds = positionSeconds;
        this.rating = rating;
        this.audio = audio != null ? audio : AudioProperties.UNKNOWN;
        this.source = source != null ? source : PlaybackSource.UNKNOWN;
    }

    long albumArtId() {
        // The artwork provider is keyed by REAL_ID. ID may identify a playlist entry instead.
        return realId > 0 ? realId : 0L;
    }

    static final class AudioProperties {
        static final AudioProperties UNKNOWN = new AudioProperties(-1, null, -1, -1, -1);

        final int fileType;
        final String codec;
        final int sampleRate;
        final int bitsPerSample;
        final int bitRate;

        AudioProperties(
                int fileType,
                String codec,
                int sampleRate,
                int bitsPerSample,
                int bitRate
        ) {
            this.fileType = fileType;
            this.codec = codec;
            this.sampleRate = sampleRate;
            this.bitsPerSample = bitsPerSample;
            this.bitRate = bitRate;
        }
    }

    static final class PlaybackSource {
        static final PlaybackSource UNKNOWN = new PlaybackSource(-1, null, -1, -1);

        final int category;
        final String categoryUri;
        final int positionInList;
        final int listSize;

        PlaybackSource(int category, String categoryUri, int positionInList, int listSize) {
            this.category = category;
            this.categoryUri = categoryUri;
            this.positionInList = positionInList;
            this.listSize = listSize;
        }
    }
}
