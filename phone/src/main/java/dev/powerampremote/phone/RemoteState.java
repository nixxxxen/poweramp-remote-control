package dev.powerampremote.phone;

/** Immutable complete state snapshot received from REST or the event WebSocket. */
final class RemoteState {
    final long revision;
    final boolean powerampAvailable;
    final boolean hasTrack;
    final String title;
    final String artist;
    final String album;
    final String artwork;
    final Integer fileType;
    final String fileTypeName;
    final String codec;
    final Integer bitsPerSample;
    final Integer sampleRate;
    final Integer bitRate;
    final Integer sourceCategory;
    final String sourceCategoryName;
    final String sourceCategoryUri;
    final Integer positionInList;
    final Integer listSize;
    final Integer durationSeconds;
    final Integer positionSeconds;
    final String playbackState;
    final Integer rating;
    final Boolean liked;
    final Boolean disliked;
    final Boolean shuffle;
    final Integer shuffleMode;

    RemoteState(long revision, boolean powerampAvailable, boolean hasTrack,
            String title, String artist, String album, String artwork,
            Integer fileType, String fileTypeName, String codec,
            Integer bitsPerSample, Integer sampleRate, Integer bitRate,
            Integer sourceCategory, String sourceCategoryName, String sourceCategoryUri,
            Integer positionInList, Integer listSize, Integer durationSeconds,
            Integer positionSeconds, String playbackState, Integer rating,
            Boolean liked, Boolean disliked, Boolean shuffle, Integer shuffleMode) {
        this.revision = revision;
        this.powerampAvailable = powerampAvailable;
        this.hasTrack = hasTrack;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.artwork = artwork;
        this.fileType = fileType;
        this.fileTypeName = fileTypeName;
        this.codec = codec;
        this.bitsPerSample = bitsPerSample;
        this.sampleRate = sampleRate;
        this.bitRate = bitRate;
        this.sourceCategory = sourceCategory;
        this.sourceCategoryName = sourceCategoryName;
        this.sourceCategoryUri = sourceCategoryUri;
        this.positionInList = positionInList;
        this.listSize = listSize;
        this.durationSeconds = durationSeconds;
        this.positionSeconds = positionSeconds;
        this.playbackState = playbackState;
        this.rating = rating;
        this.liked = liked;
        this.disliked = disliked;
        this.shuffle = shuffle;
        this.shuffleMode = shuffleMode;
    }

    String artworkKey() {
        return artwork == null ? null : trackIdentity();
    }

    String trackIdentity() {
        return safe(title) + '\u0000'
                + safe(artist) + '\u0000'
                + safe(album) + '\u0000'
                + String.valueOf(durationSeconds) + '\u0000'
                + safe(sourceCategoryUri) + '\u0000'
                + String.valueOf(positionInList);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
