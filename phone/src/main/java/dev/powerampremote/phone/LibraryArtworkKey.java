package dev.powerampremote.phone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable thumbnail identity: paired Server ID plus allowlisted Server artwork path. */
final class LibraryArtworkKey {
    private static final Pattern ARTWORK_PATH = Pattern.compile(
            "/api/v1/library/artwork/tracks/[1-9][0-9]{0,18}"
    );

    final String serverId;
    final String artworkPath;
    private final String value;

    private LibraryArtworkKey(String serverId, String artworkPath) {
        this.serverId = serverId;
        this.artworkPath = artworkPath;
        value = serverId + '\u0000' + artworkPath;
    }

    static LibraryArtworkKey create(String serverId, String artworkPath) {
        if (!PairingCredentials.isValidServerId(serverId)
                || artworkPath == null
                || !ARTWORK_PATH.matcher(artworkPath).matches()) {
            throw new IllegalArgumentException("Invalid library artwork identity");
        }
        return new LibraryArtworkKey(serverId, artworkPath);
    }

    String stableValue() {
        return value;
    }

    String diskFileName() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(digest.length * 2 + 6);
            for (byte value : digest) name.append(String.format("%02x", value & 0xff));
            return name.append(".thumb").toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof LibraryArtworkKey)) return false;
        LibraryArtworkKey other = (LibraryArtworkKey) object;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
