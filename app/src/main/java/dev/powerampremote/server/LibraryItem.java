package dev.powerampremote.server;

import java.util.Objects;

/** Neutral, path-free representation of one documented Poweramp library row. */
final class LibraryItem {
    private static final int MAX_TEXT_LENGTH = 1_000;
    enum Type {
        TRACK("track"),
        ARTIST("artist"),
        ALBUM("album"),
        FOLDER("folder"),
        PLAYLIST("playlist"),
        PLAYLIST_ENTRY("playlist_entry"),
        QUEUE_ENTRY("queue_entry");

        final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }
    }

    static final class PlayTarget {
        enum Type {
            TRACK("track"),
            ALBUM("album"),
            PLAYLIST("playlist"),
            PLAYLIST_ENTRY("playlist_entry"),
            QUEUE_ENTRY("queue_entry");

            final String wireName;

            Type(String wireName) {
                this.wireName = wireName;
            }

            static Type fromWireName(String wireName) {
                for (Type type : values()) {
                    if (type.wireName.equals(wireName)) {
                        return type;
                    }
                }
                throw new IllegalArgumentException("Unsupported play target");
            }
        }

        final Type type;
        final long id;
        final Long containerId;

        PlayTarget(Type type, long id, Long containerId) {
            this.type = Objects.requireNonNull(type);
            if (id <= 0L || containerId != null && containerId <= 0L) {
                throw new IllegalArgumentException("Poweramp IDs must be positive");
            }
            this.id = id;
            this.containerId = containerId;
        }

        static PlayTarget track(long trackId) {
            return new PlayTarget(Type.TRACK, trackId, null);
        }

        static PlayTarget album(long albumId) {
            return new PlayTarget(Type.ALBUM, albumId, null);
        }

        static PlayTarget playlist(long playlistId) {
            return new PlayTarget(Type.PLAYLIST, playlistId, null);
        }

        static PlayTarget playlistEntry(long playlistId, long entryId) {
            return new PlayTarget(Type.PLAYLIST_ENTRY, entryId, playlistId);
        }

        static PlayTarget queueEntry(long entryId) {
            return new PlayTarget(Type.QUEUE_ENTRY, entryId, null);
        }
    }

    static final class BrowseTarget {
        enum Type {
            ARTIST_MEMBERSHIP("artist_membership");

            final String wireName;

            Type(String wireName) {
                this.wireName = wireName;
            }
        }

        final Type type;
        final long id;

        BrowseTarget(Type type, long id) {
            this.type = Objects.requireNonNull(type);
            if (id <= 0L) throw new IllegalArgumentException("Poweramp ID must be positive");
            this.id = id;
        }

        static BrowseTarget artistMembership(long artistId) {
            return new BrowseTarget(Type.ARTIST_MEMBERSHIP, artistId);
        }
    }

    final Type type;
    final long id;
    final Long entryId;
    final Long parentId;
    final String title;
    final String artist;
    final String album;
    final Long durationMilliseconds;
    final Integer trackCount;
    final String artworkPath;
    final PlayTarget playTarget;
    final Boolean current;
    /** Provider-only Artists.IS_UNSPLIT value; never exposed as presentation metadata. */
    final Boolean artistUnsplit;
    /** Optional additive navigation target used only where legacy category browse is insufficient. */
    final BrowseTarget browseTarget;

    LibraryItem(
            Type type,
            long id,
            Long entryId,
            Long parentId,
            String title,
            String artist,
            String album,
            Long durationMilliseconds,
            Integer trackCount,
            String artworkPath,
            PlayTarget playTarget,
            Boolean current
    ) {
        this(
                type, id, entryId, parentId, title, artist, album,
                durationMilliseconds, trackCount, artworkPath, playTarget, current,
                null, null
        );
    }

    LibraryItem(
            Type type,
            long id,
            Long entryId,
            Long parentId,
            String title,
            String artist,
            String album,
            Long durationMilliseconds,
            Integer trackCount,
            String artworkPath,
            PlayTarget playTarget,
            Boolean current,
            Boolean artistUnsplit,
            BrowseTarget browseTarget
    ) {
        this.type = Objects.requireNonNull(type);
        if (id <= 0L || entryId != null && entryId <= 0L
                || parentId != null && parentId < 0L) {
            throw new IllegalArgumentException("Invalid Poweramp item ID");
        }
        this.id = id;
        this.entryId = entryId;
        this.parentId = parentId;
        this.title = cleanText(title);
        this.artist = cleanText(artist);
        this.album = cleanText(album);
        this.durationMilliseconds = nonNegative(durationMilliseconds);
        this.trackCount = nonNegative(trackCount);
        this.artworkPath = artworkPath;
        this.playTarget = playTarget;
        this.current = current;
        this.artistUnsplit = artistUnsplit;
        this.browseTarget = browseTarget;
    }

    LibraryItem asArtistMembershipTarget() {
        if (type != Type.ARTIST) throw new IllegalStateException("Artist item required");
        return new LibraryItem(
                type, id, entryId, parentId, title, artist, album,
                null, trackCount, artworkPath, playTarget, current, artistUnsplit,
                BrowseTarget.artistMembership(id)
        );
    }

    private static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() <= MAX_TEXT_LENGTH) {
            return clean;
        }
        int end = MAX_TEXT_LENGTH;
        if (Character.isHighSurrogate(clean.charAt(end - 1))
                && Character.isLowSurrogate(clean.charAt(end))) {
            end--;
        }
        return clean.substring(0, end);
    }

    private static Long nonNegative(Long value) {
        return value != null && value >= 0L ? value : null;
    }

    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }
}
