package dev.powerampremote.phone;

import java.util.Objects;

/** Stable identity for a derived category-to-track-artwork selection. */
final class RepresentativeArtworkKey {
    static final String TYPE_ARTIST = "artist";
    static final String TYPE_ARTIST_MEMBERSHIP = "artist_membership";
    static final String TYPE_ALBUM = "album";
    static final String TYPE_PLAYLIST = "playlist";
    static final String TYPE_FOLDER = "folder";

    final String serverId;
    final String categoryType;
    final long categoryId;
    private final String value;

    private RepresentativeArtworkKey(String serverId, String categoryType, long categoryId) {
        this.serverId = serverId;
        this.categoryType = categoryType;
        this.categoryId = categoryId;
        value = serverId + '\u0000' + categoryType + '\u0000' + categoryId;
    }

    static RepresentativeArtworkKey create(
            String serverId,
            String categoryType,
            long categoryId
    ) {
        if (!PairingCredentials.isValidServerId(serverId)
                || !isSupportedType(categoryType)
                || categoryId <= 0L) {
            throw new IllegalArgumentException("Invalid representative artwork identity");
        }
        return new RepresentativeArtworkKey(serverId, categoryType, categoryId);
    }

    static boolean isSupportedType(String categoryType) {
        return TYPE_ARTIST.equals(categoryType)
                || TYPE_ARTIST_MEMBERSHIP.equals(categoryType)
                || TYPE_ALBUM.equals(categoryType)
                || TYPE_PLAYLIST.equals(categoryType)
                || TYPE_FOLDER.equals(categoryType);
    }

    LibraryRequest tracksRequest(int pageSize) {
        LibraryRequest request;
        switch (categoryType) {
            case TYPE_ARTIST:
                request = LibraryRequest.artistTracks(categoryId);
                break;
            case TYPE_ARTIST_MEMBERSHIP:
                request = LibraryRequest.artistMemberTracks(categoryId);
                break;
            case TYPE_ALBUM:
                request = LibraryRequest.albumTracks(categoryId);
                break;
            case TYPE_PLAYLIST:
                request = LibraryRequest.playlistTracks(categoryId);
                break;
            case TYPE_FOLDER:
                request = LibraryRequest.folderTracks(categoryId);
                break;
            default:
                throw new IllegalStateException("Unsupported representative artwork type");
        }
        return request.withPageSize(pageSize);
    }

    String stableValue() {
        return value;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof RepresentativeArtworkKey)) return false;
        RepresentativeArtworkKey other = (RepresentativeArtworkKey) object;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
