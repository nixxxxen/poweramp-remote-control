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
import java.util.List;
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
    /** Internal sentinel for the documented base URI with no optional SQL lim parameter. */
    static final int PROVIDER_ALL_ROWS = 0;
    static final int MAX_RELATED_TRACK_IDS_PER_QUERY = 250;
    static final int MAX_RELATED_ARTIST_IDS_PER_QUERY = 100;
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
    static final String COLUMN_ARTIST_IS_UNSPLIT = "artist_is_unsplit";

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
    private static final String TRACK_TITLE_SEARCH_SELECTION =
            "title_tag LIKE ? ESCAPE '!'";
    private static final String ARTIST_NAME_SEARCH_SELECTION =
            "artist LIKE ? ESCAPE '!'";
    private static final String ALBUM_NAME_SEARCH_SELECTION =
            "album LIKE ? ESCAPE '!'";
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
            "artists.duration AS " + COLUMN_DURATION_MILLISECONDS,
            "artists.is_unsplit AS " + COLUMN_ARTIST_IS_UNSPLIT
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
        private final String selection;
        private final String[] selectionArgs;

        private Query(
                String category,
                String path,
                String filter,
                RowKind rowKind,
                Long containerId,
                String selection,
                String[] selectionArgs
        ) {
            this.category = Objects.requireNonNull(category);
            this.path = Objects.requireNonNull(path);
            this.filter = filter;
            this.rowKind = Objects.requireNonNull(rowKind);
            this.containerId = containerId;
            this.selection = selection;
            this.selectionArgs = selectionArgs == null ? null : selectionArgs.clone();
        }

        String providerUri(int limit) {
            if (limit < PROVIDER_ALL_ROWS || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Invalid provider limit");
            }
            StringBuilder uri = new StringBuilder(DATA_ROOT)
                    .append(path);
            if (limit != PROVIDER_ALL_ROWS) {
                uri.append('?')
                        .append(PARAM_LIMIT)
                        .append('=')
                        .append(limit);
            }
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
            return selection;
        }

        String[] selectionArgs() {
            return selectionArgs == null ? null : selectionArgs.clone();
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

    /**
     * Relation-aware artist browse. The outer /files row remains unique while membership is
     * resolved through the public MultiArtists table, so sole and collaboration tracks are
     * combined without changing the historical /artists/{id}/files category semantics.
     */
    static Query artistMemberTracks(long artistId) {
        String id = positiveId(artistId);
        return new Query(
                "artist_member_tracks",
                "/files",
                id,
                RowKind.TRACK,
                artistId,
                "EXISTS (SELECT 1 FROM multi_artists"
                        + " WHERE multi_artists.file_id=folder_files._id"
                        + " AND multi_artists.artist_id=?)",
                new String[]{id}
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
        String query = validSearchQuery(filter);
        String pattern = containsLikePattern(query);
        return new Query(
                "search",
                "/files",
                query,
                RowKind.SEARCH_TRACK,
                null,
                SEARCH_SELECTION,
                new String[]{pattern, pattern, pattern, pattern}
        );
    }

    static Query categorizedTrackTitles(String filter) {
        String query = validSearchQuery(filter);
        return new Query(
                "categorized_search_tracks",
                "/files",
                query,
                RowKind.SEARCH_TRACK,
                null,
                TRACK_TITLE_SEARCH_SELECTION,
                new String[]{containsLikePattern(query)}
        );
    }

    static Query categorizedExactTrackTitles(String filter) {
        String query = validSearchQuery(filter);
        return new Query(
                "categorized_search_exact_tracks",
                "/files",
                query,
                RowKind.SEARCH_TRACK,
                null,
                TRACK_TITLE_SEARCH_SELECTION,
                new String[]{exactLikePattern(query)}
        );
    }

    static Query categorizedArtists(String filter) {
        String query = validSearchQuery(filter);
        return new Query(
                "categorized_search_artists",
                "/artists",
                query,
                RowKind.ARTIST,
                null,
                ARTIST_NAME_SEARCH_SELECTION,
                new String[]{containsLikePattern(query)}
        );
    }

    static Query categorizedAlbums(String filter) {
        String query = validSearchQuery(filter);
        return new Query(
                "categorized_search_albums",
                "/albums",
                query,
                RowKind.ALBUM,
                null,
                ALBUM_NAME_SEARCH_SELECTION,
                new String[]{containsLikePattern(query)}
        );
    }

    static Query categorizedTrackTitlesForArtists(String filter, List<Long> artistIds) {
        String query = validSearchQuery(filter);
        String relation = artistMembershipRelation(
                "multi_artists.file_id=folder_files._id", artistIds
        );
        return new Query(
                "categorized_search_artist_tracks",
                "/files",
                query + '\n' + idsKey(artistIds),
                RowKind.SEARCH_TRACK,
                null,
                "(" + TRACK_TITLE_SEARCH_SELECTION + ") AND " + relation,
                prepend(containsLikePattern(query), idArguments(artistIds))
        );
    }

    static Query categorizedAlbumsForArtists(String filter, List<Long> artistIds) {
        String query = validSearchQuery(filter);
        String relation = "EXISTS (SELECT 1 FROM folder_files"
                + " INNER JOIN multi_artists"
                + " ON multi_artists.file_id=folder_files._id"
                + " WHERE folder_files.album_id=albums._id"
                + " AND " + artistIdIn(artistIds) + ")";
        return new Query(
                "categorized_search_artist_albums",
                "/albums",
                query + '\n' + idsKey(artistIds),
                RowKind.ALBUM,
                null,
                "(" + ALBUM_NAME_SEARCH_SELECTION + ") AND " + relation,
                prepend(containsLikePattern(query), idArguments(artistIds))
        );
    }

    /**
     * Uses the public TableDefs.MultiArtists one-to-many relation. IDs are generated by Server
     * from already parsed exact-title track rows and remain bound selection arguments.
     */
    static Query relatedArtists(List<Long> trackIds) {
        if (trackIds == null || trackIds.isEmpty()
                || trackIds.size() > MAX_RELATED_TRACK_IDS_PER_QUERY) {
            throw new IllegalArgumentException("Invalid related track ID count");
        }
        StringBuilder placeholders = new StringBuilder();
        String[] arguments = new String[trackIds.size()];
        for (int index = 0; index < trackIds.size(); index++) {
            Long id = trackIds.get(index);
            if (id == null || id <= 0L) {
                throw new IllegalArgumentException("Invalid related track ID");
            }
            if (index > 0) placeholders.append(',');
            placeholders.append('?');
            arguments[index] = Long.toString(id);
        }
        String selection = "EXISTS (SELECT 1 FROM multi_artists"
                + " WHERE multi_artists.artist_id=artists._id"
                + " AND multi_artists.file_id IN (" + placeholders + "))";
        return new Query(
                "categorized_search_related_artists",
                "/artists",
                null,
                RowKind.ARTIST,
                null,
                selection,
                arguments
        );
    }

    /**
     * Finds unique album rows containing at least one track related to any canonical artist ID.
     * Files.ALBUM_ID and MultiArtists are both public TableDefs relations; all IDs stay bound.
     */
    static Query relatedAlbums(List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()
                || artistIds.size() > MAX_RELATED_ARTIST_IDS_PER_QUERY) {
            throw new IllegalArgumentException("Invalid related artist ID count");
        }
        StringBuilder placeholders = new StringBuilder();
        String[] arguments = new String[artistIds.size()];
        for (int index = 0; index < artistIds.size(); index++) {
            Long id = artistIds.get(index);
            if (id == null || id <= 0L) {
                throw new IllegalArgumentException("Invalid related artist ID");
            }
            if (index > 0) placeholders.append(',');
            placeholders.append('?');
            arguments[index] = Long.toString(id);
        }
        String selection = "EXISTS (SELECT 1 FROM folder_files"
                + " INNER JOIN multi_artists"
                + " ON multi_artists.file_id=folder_files._id"
                + " WHERE folder_files.album_id=albums._id"
                + " AND multi_artists.artist_id IN (" + placeholders + "))";
        return new Query(
                "categorized_search_related_albums",
                "/albums",
                null,
                RowKind.ALBUM,
                null,
                selection,
                arguments
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
        if (uri.getRawQuery() == null) return true;
        Map<String, String> parameters = parseQuery(uri.getRawQuery());
        if (parameters == null || !parameters.containsKey(PARAM_LIMIT)) return false;
        String limit = parameters.get(PARAM_LIMIT);
        if (!POSITIVE_ID.matcher(limit).matches()) {
            return false;
        }
        try {
            long parsed = Long.parseLong(limit);
            if (parsed > MAX_PAGE_SIZE) {
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
        return new Query(category, path, null, rowKind, containerId, null, null);
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

    private static String exactLikePattern(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '!' || character == '%' || character == '_') {
                escaped.append('!');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static String artistMembershipRelation(
            String fileJoin,
            List<Long> artistIds
    ) {
        return "EXISTS (SELECT 1 FROM multi_artists WHERE " + fileJoin
                + " AND " + artistIdIn(artistIds) + ")";
    }

    private static String artistIdIn(List<Long> artistIds) {
        validateArtistIds(artistIds);
        StringBuilder placeholders = new StringBuilder("multi_artists.artist_id IN (");
        for (int index = 0; index < artistIds.size(); index++) {
            if (index > 0) placeholders.append(',');
            placeholders.append('?');
        }
        return placeholders.append(')').toString();
    }

    private static void validateArtistIds(List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()
                || artistIds.size() > MAX_RELATED_ARTIST_IDS_PER_QUERY) {
            throw new IllegalArgumentException("Invalid related artist ID count");
        }
        for (Long id : artistIds) {
            if (id == null || id <= 0L) {
                throw new IllegalArgumentException("Invalid related artist ID");
            }
        }
    }

    private static String[] idArguments(List<Long> ids) {
        validateArtistIds(ids);
        String[] result = new String[ids.size()];
        for (int index = 0; index < ids.size(); index++) {
            result[index] = Long.toString(ids.get(index));
        }
        return result;
    }

    private static String[] prepend(String value, String[] tail) {
        String[] result = new String[tail.length + 1];
        result[0] = value;
        System.arraycopy(tail, 0, result, 1, tail.length);
        return result;
    }

    private static String idsKey(List<Long> ids) {
        validateArtistIds(ids);
        StringBuilder key = new StringBuilder();
        for (Long id : ids) key.append(id).append(',');
        return key.toString();
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
