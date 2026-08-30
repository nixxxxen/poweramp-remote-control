package dev.powerampremote.phone;

/** Stable process-cache key across revisions and endpoint changes for one paired Server. */
final class ArtworkPaletteCacheKey {
    private ArtworkPaletteCacheKey() {
    }

    static String create(String serverId, String artworkKey) {
        if (artworkKey == null) return null;
        return (serverId == null ? "unknown-server" : serverId) + '\u0000' + artworkKey;
    }
}
