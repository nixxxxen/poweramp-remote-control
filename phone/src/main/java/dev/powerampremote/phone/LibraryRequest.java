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
    final LibrarySortView sortView;
    final LibrarySort sort;

    private LibraryRequest(
            String basePath,
            String query,
            int pageSize,
            LibrarySortView sortView,
            LibrarySort sort
    ) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Invalid page size");
        }
        this.basePath = basePath;
        this.query = query;
        this.pageSize = pageSize;
        this.sortView = sortView;
        this.sort = sort == null ? LibrarySort.POWERAMP : sort;
    }

    static LibraryRequest tracks() {
        return fixedTrack("/api/v1/library/tracks", LibrarySortView.ALL_TRACKS);
    }
    static LibraryRequest artists() { return fixed("/api/v1/library/artists"); }
    static LibraryRequest albums() { return fixed("/api/v1/library/albums"); }
    static LibraryRequest playlists() { return fixed("/api/v1/library/playlists"); }

    static LibraryRequest artistTracks(long id) {
        return trackContainer(
                "/api/v1/library/artists/", id, "/tracks", LibrarySortView.ARTIST
        );
    }

    static LibraryRequest artistMemberTracks(long id) {
        return trackContainer(
                "/api/v1/library/artists/", id, "/member-tracks", LibrarySortView.ARTIST
        );
    }

    static LibraryRequest albumTracks(long id) {
        return trackContainer(
                "/api/v1/library/albums/", id, "/tracks", LibrarySortView.ALBUM
        );
    }

    static LibraryRequest playlistTracks(long id) {
        return trackContainer(
                "/api/v1/library/playlists/", id, "/tracks", LibrarySortView.PLAYLIST
        );
    }

    static LibraryRequest folderTracks(long id) {
        return trackContainer(
                "/api/v1/library/folder-tree/", id, "/tracks", LibrarySortView.FOLDER
        );
    }

    static LibraryRequest subfolders(long id) {
        if (id < 0L) throw new IllegalArgumentException("Invalid folder ID");
        return new LibraryRequest(
                "/api/v1/library/folder-tree/" + id + "/folders",
                null,
                PAGE_SIZE,
                null,
                LibrarySort.POWERAMP
        );
    }

    static LibraryRequest search(String query) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty() || value.length() > 160) {
            throw new IllegalArgumentException("Invalid search query");
        }
        return new LibraryRequest(
                "/api/v1/search", value, PAGE_SIZE, null, LibrarySort.POWERAMP
        );
    }

    LibraryRequest withPageSize(int pageSize) {
        return new LibraryRequest(basePath, query, pageSize, sortView, sort);
    }

    LibraryRequest withSort(LibrarySort selectedSort) {
        if (sortView == null || selectedSort == null) {
            throw new IllegalArgumentException("Request is not a Library track list");
        }
        return new LibraryRequest(basePath, query, pageSize, sortView, selectedSort);
    }

    boolean isTrackList() {
        return sortView != null;
    }

    String path(String pageToken) {
        StringBuilder path = new StringBuilder(basePath).append('?');
        if (query != null) {
            path.append("q=").append(encode(query)).append('&');
        }
        path.append("limit=").append(pageSize);
        if (isTrackList() && !sort.isPowerampOrder()) {
            path.append("&sort=").append(sort.criterion.wireName);
            path.append("&direction=").append(sort.direction.wireName);
        }
        if (pageToken != null) {
            if (!pageToken.matches("[A-Za-z0-9_-]{24}")) {
                throw new IllegalArgumentException("Invalid page token");
            }
            path.append("&pageToken=").append(pageToken);
        }
        return path.toString();
    }

    private static LibraryRequest fixed(String path) {
        return new LibraryRequest(path, null, PAGE_SIZE, null, LibrarySort.POWERAMP);
    }

    private static LibraryRequest fixedTrack(String path, LibrarySortView view) {
        return new LibraryRequest(path, null, PAGE_SIZE, view, LibrarySort.POWERAMP);
    }

    private static LibraryRequest trackContainer(
            String prefix,
            long id,
            String suffix,
            LibrarySortView view
    ) {
        if (id <= 0L) throw new IllegalArgumentException("Invalid container ID");
        return new LibraryRequest(
                prefix + id + suffix, null, PAGE_SIZE, view, LibrarySort.POWERAMP
        );
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 unavailable", impossible);
        }
    }
}
