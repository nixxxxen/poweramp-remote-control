package dev.powerampremote.phone;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/** Phone-owned allowlist for the fixed additive API v1 browsing routes. */
final class LibraryRequest {
    private static final int PAGE_SIZE = 25;

    final String basePath;
    final String query;
    final int pageSize;

    private LibraryRequest(String basePath, String query, int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Invalid page size");
        }
        this.basePath = basePath;
        this.query = query;
        this.pageSize = pageSize;
    }

    static LibraryRequest tracks() { return fixed("/api/v1/library/tracks"); }
    static LibraryRequest artists() { return fixed("/api/v1/library/artists"); }
    static LibraryRequest albums() { return fixed("/api/v1/library/albums"); }
    static LibraryRequest playlists() { return fixed("/api/v1/library/playlists"); }

    static LibraryRequest artistTracks(long id) {
        return container("/api/v1/library/artists/", id, "/tracks");
    }

    static LibraryRequest artistMemberTracks(long id) {
        return container("/api/v1/library/artists/", id, "/member-tracks");
    }

    static LibraryRequest albumTracks(long id) {
        return container("/api/v1/library/albums/", id, "/tracks");
    }

    static LibraryRequest playlistTracks(long id) {
        return container("/api/v1/library/playlists/", id, "/tracks");
    }

    static LibraryRequest folderTracks(long id) {
        return container("/api/v1/library/folder-tree/", id, "/tracks");
    }

    static LibraryRequest subfolders(long id) {
        if (id < 0L) throw new IllegalArgumentException("Invalid folder ID");
        return new LibraryRequest(
                "/api/v1/library/folder-tree/" + id + "/folders", null, PAGE_SIZE
        );
    }

    static LibraryRequest search(String query) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty() || value.length() > 160) {
            throw new IllegalArgumentException("Invalid search query");
        }
        return new LibraryRequest("/api/v1/search", value, PAGE_SIZE);
    }

    LibraryRequest withPageSize(int pageSize) {
        return new LibraryRequest(basePath, query, pageSize);
    }

    String path(String pageToken) {
        StringBuilder path = new StringBuilder(basePath).append('?');
        if (query != null) {
            path.append("q=").append(encode(query)).append('&');
        }
        path.append("limit=").append(pageSize);
        if (pageToken != null) {
            if (!pageToken.matches("[A-Za-z0-9_-]{24}")) {
                throw new IllegalArgumentException("Invalid page token");
            }
            path.append("&pageToken=").append(pageToken);
        }
        return path.toString();
    }

    private static LibraryRequest fixed(String path) {
        return new LibraryRequest(path, null, PAGE_SIZE);
    }

    private static LibraryRequest container(String prefix, long id, String suffix) {
        if (id <= 0L) throw new IllegalArgumentException("Invalid container ID");
        return new LibraryRequest(prefix + id + suffix, null, PAGE_SIZE);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 unavailable", impossible);
        }
    }
}
