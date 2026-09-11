package dev.powerampremote.phone;

/** One neutral item from an additive API v1 Library/Search page. */
final class LibraryItem {
    final String type;
    final long id;
    /** API v1 row ID interpreted as folder_files._id only for track-capable wire types. */
    final Long underlyingId;
    final Long entryId;
    final Long parentId;
    final String title;
    final String artist;
    final String album;
    final Long durationMilliseconds;
    final Long dateAddedEpochSeconds;
    final Long playCount;
    final Integer trackCount;
    final String artworkPath;
    final LibraryPlayTarget playTarget;
    final LibraryBrowseTarget browseTarget;
    final Boolean current;

    LibraryItem(
            String type,
            long id,
            Long entryId,
            Long parentId,
            String title,
            String artist,
            String album,
            Long durationMilliseconds,
            Integer trackCount,
            String artworkPath,
            LibraryPlayTarget playTarget,
            Boolean current
    ) {
        this(
                type, id, entryId, parentId, title, artist, album,
                durationMilliseconds, null, null, trackCount, artworkPath, playTarget, null, current
        );
    }

    LibraryItem(
            String type,
            long id,
            Long entryId,
            Long parentId,
            String title,
            String artist,
            String album,
            Long durationMilliseconds,
            Integer trackCount,
            String artworkPath,
            LibraryPlayTarget playTarget,
            LibraryBrowseTarget browseTarget,
            Boolean current
    ) {
        this(
                type, id, entryId, parentId, title, artist, album,
                durationMilliseconds, null, null, trackCount, artworkPath,
                playTarget, browseTarget, current
        );
    }

    LibraryItem(
            String type,
            long id,
            Long entryId,
            Long parentId,
            String title,
            String artist,
            String album,
            Long durationMilliseconds,
            Long dateAddedEpochSeconds,
            Long playCount,
            Integer trackCount,
            String artworkPath,
            LibraryPlayTarget playTarget,
            LibraryBrowseTarget browseTarget,
            Boolean current
    ) {
        this.type = type;
        this.id = id;
        this.underlyingId = underlyingId(type, id);
        this.entryId = entryId;
        this.parentId = parentId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMilliseconds = durationMilliseconds;
        this.dateAddedEpochSeconds = dateAddedEpochSeconds;
        this.playCount = playCount;
        this.trackCount = trackCount;
        this.artworkPath = artworkPath;
        this.playTarget = playTarget;
        this.browseTarget = browseTarget;
        this.current = current;
    }

    String representativeType() {
        return browseTarget != null
                && LibraryBrowseTarget.TYPE_ARTIST_MEMBERSHIP.equals(browseTarget.type)
                ? RepresentativeArtworkKey.TYPE_ARTIST_MEMBERSHIP : type;
    }

    private static Long underlyingId(String type, long id) {
        return "track".equals(type)
                || "playlist_entry".equals(type)
                || "queue_entry".equals(type)
                ? id : null;
    }
}
