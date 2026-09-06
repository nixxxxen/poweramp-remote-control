/*
 * Portions derived from PowerampAPI.java and TableDefs.kt.
 * Copyright (C) 2011-2026 Maksim Petrov.
 * Modified for Poweramp Remote; see THIRD_PARTY_NOTICES.md for the upstream license.
 */
package dev.powerampremote.server;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Allowlisted subset of the public Poweramp ContentProvider and OPEN_TO_PLAY contracts. */
final class PowerampLibraryContract {
    static final String DATA_AUTHORITY = "com.maxmpz.audioplayer.data";
    static final String DATA_ROOT = "content://" + DATA_AUTHORITY;
    static final String ALBUM_ART_ROOT =
            "content://" + PowerampContract.ALBUM_ART_AUTHORITY;
    static final String PARAM_LIMIT = "lim";

    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_CONTINUATION_ROWS = 1_000;
    static final int MAX_SEARCH_QUERY_LENGTH = 160;
    static final String LIBRARY_ARTWORK_PATH_PREFIX =
            "/api/v1/library/artwork/tracks/";

    static final String COLUMN_TRACK_ID = "track_id";
    static final String COLUMN_ENTRY_ID = "entry_id";
    static final String COLUMN_ITEM_ID = "item_id";
    static final String COLUMN_PARENT_ID = "parent_id";
    static final String COLUMN_TITLE = "title";
    static final String COLUMN_FILE_NAME = "file_name";
    static final String COLUMN_ARTIST = "artist";
    static final String COLUMN_ALBUM = "album";
    static final String COLUMN_DURATION_MILLISECONDS = "duration_ms";
    static final String COLUMN_TRACK_COUNT = "track_count";

    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern NON_NEGATIVE_ID = Pattern.compile("(?:0|[1-9][0-9]{0,18})");
    private static final Pattern DATA_QUERY_PATH = Pattern.compile(
            "/(?:files|artists|albums|folders|playlists|queue"
                    + "|artists/[1-9][0-9]{0,18}/files"
                    + "|albums/[1-9][0-9]{0,18}/files"
                    + "|folders/[1-9][0-9]{0,18}/files"
                    + "|folders_hier/(?:0|[1-9][0-9]{0,18})/(?:subfolders|files)"
                    + "|playlists/[1-9][0-9]{0,18}/files"
                    + "|files/[1-9][0-9]{0,18}"
                    + "|playlists/[1-9][0-9]{0,18}/files/[1-9][0-9]{0,18}"
                    + "|queue/[1-9][0-9]{0,18})"
    );
    private static final Pattern PLAY_PATH = Pattern.compile(
            "/(?:files/[1-9][0-9]{0,18}"
                    + "|albums/[1-9][0-9]{0,18}/files"
                    + "|playlists/[1-9][0-9]{0,18}/files"
                    + "|playlists/[1-9][0-9]{0,18}/files/[1-9][0-9]{0,18}"
                    + "|queue/[1-9][0-9]{0,18})"
    );
    private static final Pattern ARTWORK_PATH = Pattern.compile(
            "/files/[1-9][0-9]{0,18}"
    );

    private static final String[] TRACK_PROJECTION = {
            "folder_files._id AS " + COLUMN_TRACK_ID,
            "folder_files.name AS " + COLUMN_FILE_NAME,
            "title_tag AS " + COLUMN_TITLE,
            "artist AS " + COLUMN_ARTIST,
            "album AS " + COLUMN_ALBUM,
            "folder_files.duration AS " + COLUMN_DURATION_MILLISECONDS
    };
    private static final String SEARCH_SELECTION =
            "(title_tag LIKE ? ESCAPE '!'"
                    + " OR folder_files.name LIKE ? ESCAPE '!'"
                    + " OR artist LIKE ? ESCAPE '!'"
                    + " OR album LIKE ? ESCAPE '!')";
    private static final String[] PLAYLIST_ENTRY_PROJECTION = {
            "folder_files._id AS " + COLUMN_TRACK_ID,
            "playlist_entries._id AS " + COLUMN_ENTRY_ID,
            "folder_files.name AS " + COLUMN_FILE_NAME,
            "title_tag AS " + COLUMN_TITLE,
            "artist AS " + COLUMN_ARTIST,
            "album AS " + COLUMN_ALBUM,
            "folder_files.duration AS " + COLUMN_DURATION_MILLISECONDS
    };
    private static final String[] QUEUE_ENTRY_PROJECTION = {
            "folder_files._id AS " + COLUMN_TRACK_ID,
            "queue._id AS " + COLUMN_ENTRY_ID,
            "folder_files.name AS " + COLUMN_FILE_NAME,
            "title_tag AS " + COLUMN_TITLE,
            "artist AS " + COLUMN_ARTIST,
            "album AS " + COLUMN_ALBUM,
            "folder_files.duration AS " + COLUMN_DURATION_MILLISECONDS
    };
    private static final String[] ARTIST_PROJECTION = {
            "artists._id AS " + COLUMN_ITEM_ID,
            "artist AS " + COLUMN_TITLE,
            "artists.num_files AS " + COLUMN_TRACK_COUNT,
            "artists.duration AS " + COLUMN_DURATION_MILLISECONDS
    };
    private static final String[] ALBUM_PROJECTION = {
            "albums._id AS " + COLUMN_ITEM_ID,
            "album AS " + COLUMN_TITLE,
            "albums.num_files AS " + COLUMN_TRACK_COUNT,
            "albums.duration AS " + COLUMN_DURATION_MILLISECONDS
    };
    private static final String[] FOLDER_PROJECTION = {
            "folders._id AS " + COLUMN_ITEM_ID,
            "folders.name AS " + COLUMN_TITLE,
            "folders.parent_id AS " + COLUMN_PARENT_ID,
            "folders.num_files AS " + COLUMN_TRACK_COUNT,
            "folders.duration AS " + COLUMN_DURATION_MILLISECONDS
    };
    private static final String[] HIERARCHY_FOLDER_PROJECTION = {
            "folders._id AS " + COLUMN_ITEM_ID,
            "folders.name AS " + COLUMN_TITLE,
            "folders.parent_id AS " + COLUMN_PARENT_ID,
            "folders.hier_num_files AS " + COLUMN_TRACK_COUNT,
            "folders.hier_duration AS " + COLUMN_DURATION_MILLISECONDS
    };
    private static final String[] PLAYLIST_PROJECTION = {
            "playlists._id AS " + COLUMN_ITEM_ID,
            "playlists.playlist AS " + COLUMN_TITLE,
            "playlists.num_files AS " + COLUMN_TRACK_COUNT,
            "playlists.duration AS " + COLUMN_DURATION_MILLISECONDS
    };

    enum RowKind {
        TRACK,
        SEARCH_TRACK,
        ARTIST,
        ALBUM,
        FOLDER,
        HIERARCHY_FOLDER,
        PLAYLIST,
        PLAYLIST_ENTRY,
        QUEUE_ENTRY
    }

    static final class Query {
        final String category;
        final String path;
        final String filter;
        final RowKind rowKind;
        final Long containerId;

        private Query(
                String category,
                String path,
                String filter,
                RowKind rowKind,
                Long containerId
        ) {
            this.category = Objects.requireNonNull(category);
            this.path = Objects.requireNonNull(path);
            this.filter = filter;
            this.rowKind = Objects.requireNonNull(rowKind);
            this.containerId = containerId;
        }

        String providerUri(int limit) {
            if (limit < 1 || limit > MAX_CONTINUATION_ROWS + 1) {
                throw new IllegalArgumentException("Invalid provider limit");
            }
            StringBuilder uri = new StringBuilder(DATA_ROOT)
                    .append(path)
                    .append('?')
                    .append(PARAM_LIMIT)
                    .append('=')
                    .append(limit);
            String built = uri.toString();
            if (!isAllowedProviderUri(built)) {
                throw new IllegalStateException("Internal Poweramp URI rejected");
            }
            return built;
        }

        String[] projection() {
            switch (rowKind) {
                case SEARCH_TRACK:
                    return TRACK_PROJECTION.clone();
                case ARTIST:
                    return ARTIST_PROJECTION.clone();
                case ALBUM:
                    return ALBUM_PROJECTION.clone();
                case FOLDER:
                    return FOLDER_PROJECTION.clone();
                case HIERARCHY_FOLDER:
                    return HIERARCHY_FOLDER_PROJECTION.clone();
                case PLAYLIST:
                    return PLAYLIST_PROJECTION.clone();
                case PLAYLIST_ENTRY:
                    return PLAYLIST_ENTRY_PROJECTION.clone();
                case QUEUE_ENTRY:
                    return QUEUE_ENTRY_PROJECTION.clone();
                case TRACK:
                default:
                    return TRACK_PROJECTION.clone();
            }
        }

        String[] cursorColumns(String modelColumn) {
            return new String[]{modelColumn};
        }

        String selection() {
            return rowKind == RowKind.SEARCH_TRACK ? SEARCH_SELECTION : null;
        }

        String[] selectionArgs() {
            if (rowKind != RowKind.SEARCH_TRACK) {
                return null;
            }
            String pattern = containsLikePattern(filter);
            return new String[]{pattern, pattern, pattern, pattern};
        }

        String paginationKey() {
            return category + '\n' + path + '\n' + (filter == null ? "" : filter);
        }
    }

    static Query allTracks() {
        return query("tracks", "/files", RowKind.TRACK, null);
    }

    static Query artists() {
        return query("artists", "/artists", RowKind.ARTIST, null);
    }

    static Query artistTracks(long artistId) {
        return query(
                "artist_tracks",
                "/artists/" + positiveId(artistId) + "/files",
                RowKind.TRACK,
                artistId
        );
    }

    static Query albums() {
        return query("albums", "/albums", RowKind.ALBUM, null);
    }

    static Query albumTracks(long albumId) {
        return query(
                "album_tracks",
                "/albums/" + positiveId(albumId) + "/files",
                RowKind.TRACK,
                albumId
        );
    }

    static Query folders() {
        return query("folders", "/folders", RowKind.FOLDER, null);
    }

    static Query folderTracks(long folderId) {
        return query(
                "folder_tracks",
                "/folders/" + positiveId(folderId) + "/files",
                RowKind.TRACK,
                folderId
        );
    }

    static Query childFolders(long folderId) {
        if (folderId < 0L) {
            throw new IllegalArgumentException("Folder ID must not be negative");
        }
        return query(
                "folder_tree",
                "/folders_hier/" + folderId + "/subfolders",
                RowKind.HIERARCHY_FOLDER,
                folderId
        );
    }

    static Query hierarchyFolderTracks(long folderId) {
        return query(
                "folder_tree_tracks",
                "/folders_hier/" + positiveId(folderId) + "/files",
                RowKind.TRACK,
                folderId
        );
    }

    static Query playlists() {
        return query("playlists", "/playlists", RowKind.PLAYLIST, null);
    }

    static Query playlistTracks(long playlistId) {
        return query(
                "playlist_tracks",
                "/playlists/" + positiveId(playlistId) + "/files",
                RowKind.PLAYLIST_ENTRY,
                playlistId
        );
    }

    static Query search(String filter) {
        return new Query(
                "search",
                "/files",
                validSearchQuery(filter),
                RowKind.SEARCH_TRACK,
                null
        );
    }

    static Query queue() {
        return query("queue", "/queue", RowKind.QUEUE_ENTRY, null);
    }

    static Query validationQuery(LibraryItem.PlayTarget target) {
        Objects.requireNonNull(target);
        switch (target.type) {
            case TRACK:
                return query(
                        "play_track",
                        "/files/" + positiveId(target.id),
                        RowKind.TRACK,
                        null
                );
            case ALBUM:
                return albumTracks(target.id);
            case PLAYLIST:
                return playlistTracks(target.id);
            case PLAYLIST_ENTRY:
                if (target.containerId == null) {
                    throw new IllegalArgumentException("Playlist ID is required");
                }
                return query(
                        "play_playlist_entry",
                        "/playlists/" + positiveId(target.containerId)
                                + "/files/" + positiveId(target.id),
                        RowKind.PLAYLIST_ENTRY,
                        target.containerId
                );
            case QUEUE_ENTRY:
                return query(
                        "play_queue_entry",
                        "/queue/" + positiveId(target.id),
                        RowKind.QUEUE_ENTRY,
                        null
                );
            default:
                throw new IllegalArgumentException("Unsupported play target");
        }
    }

    static String playUri(LibraryItem.PlayTarget target) {
        Query query = validationQuery(target);
        String uri = DATA_ROOT + query.path;
        if (!isAllowedPlayUri(uri)) {
            throw new IllegalStateException("Internal OPEN_TO_PLAY URI rejected");
        }
        return uri;
    }

    static String albumArtUri(long trackId) {
        String uri = ALBUM_ART_ROOT + "/files/" + positiveId(trackId);
        if (!isAllowedArtworkUri(uri)) {
            throw new IllegalStateException("Internal album-art URI rejected");
        }
        return uri;
    }

    static String artworkApiPath(long trackId) {
        return LIBRARY_ARTWORK_PATH_PREFIX + positiveId(trackId);
    }

    static boolean isAllowedProviderUri(String value) {
        URI uri = parseContentUri(value, DATA_AUTHORITY);
        if (uri == null || !DATA_QUERY_PATH.matcher(uri.getRawPath()).matches()) {
            return false;
        }
        Map<String, String> parameters = parseQuery(uri.getRawQuery());
        if (parameters == null || !parameters.containsKey(PARAM_LIMIT)) {
            return false;
        }
        String limit = parameters.get(PARAM_LIMIT);
        if (!POSITIVE_ID.matcher(limit).matches()) {
            return false;
        }
        try {
            long parsed = Long.parseLong(limit);
            if (parsed > MAX_CONTINUATION_ROWS + 1L) {
                return false;
            }
        } catch (NumberFormatException exception) {
            return false;
        }
        return parameters.size() == 1;
    }

    static boolean isAllowedPlayUri(String value) {
        URI uri = parseContentUri(value, DATA_AUTHORITY);
        return uri != null
                && uri.getRawQuery() == null
                && PLAY_PATH.matcher(uri.getRawPath()).matches();
    }

    static boolean isAllowedArtworkUri(String value) {
        URI uri = parseContentUri(value, PowerampContract.ALBUM_ART_AUTHORITY);
        return uri != null
                && uri.getRawQuery() == null
                && ARTWORK_PATH.matcher(uri.getRawPath()).matches();
    }

    static long parsePositiveId(String value) {
        if (value == null || !POSITIVE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid ID");
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0L) {
                throw new IllegalArgumentException("Invalid ID");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid ID", exception);
        }
    }

    static long parseNonNegativeId(String value) {
        if (value == null || !NON_NEGATIVE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid ID");
        }
        try {
            long id = Long.parseLong(value);
            if (id < 0L) {
                throw new IllegalArgumentException("Invalid ID");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid ID", exception);
        }
    }

    static String validSearchQuery(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Search query is required");
        }
        String query = value.trim();
        if (query.isEmpty() || query.length() > MAX_SEARCH_QUERY_LENGTH) {
            throw new IllegalArgumentException("Invalid search query");
        }
        for (int index = 0; index < query.length(); index++) {
            char character = query.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("Invalid search query");
            }
        }
        return query;
    }

    private static Query query(
            String category,
            String path,
            RowKind rowKind,
            Long containerId
    ) {
        return new Query(category, path, null, rowKind, containerId);
    }

    private static String positiveId(long id) {
        if (id <= 0L) {
            throw new IllegalArgumentException("Poweramp ID must be positive");
        }
        return Long.toString(id);
    }

    private static String containsLikePattern(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('%');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '!' || character == '%' || character == '_') {
                escaped.append('!');
            }
            escaped.append(character);
        }
        return escaped.append('%').toString();
    }

    private static URI parseContentUri(String value, String expectedAuthority) {
        if (value == null || value.length() > 2_048) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (!"content".equals(uri.getScheme())
                    || !expectedAuthority.equals(uri.getRawAuthority())
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || uri.getRawPath() == null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator != pair.lastIndexOf('=')) {
                return null;
            }
            String key = decode(pair.substring(0, separator));
            String value = decode(pair.substring(separator + 1));
            if (key == null || value == null || values.put(key, value) != null) {
                return null;
            }
        }
        return values;
    }

    private static String decode(String value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '%') {
                    if (index + 2 >= value.length()
                            || Character.digit(value.charAt(index + 1), 16) < 0
                            || Character.digit(value.charAt(index + 2), 16) < 0) {
                        return null;
                    }
                    int high = Character.digit(value.charAt(index + 1), 16);
                    int low = Character.digit(value.charAt(index + 2), 16);
                    bytes.write((high << 4) | low);
                    index += 2;
                } else if (character == '+') {
                    bytes.write(' ');
                } else if (character > 0x7F || Character.isISOControl(character)) {
                    return null;
                } else {
                    bytes.write(character);
                }
            }
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private PowerampLibraryContract() {
    }
}
