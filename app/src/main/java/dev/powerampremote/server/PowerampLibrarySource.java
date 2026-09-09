package dev.powerampremote.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Bounded, lazy Poweramp library reader shared by the existing Server runtime. */
final class PowerampLibrarySource implements AutoCloseable {
    interface Listener {
        void onLibraryAccessChanged(LibraryAccessState state);
    }

    static final class Page {
        final String category;
        final int limit;
        final int offset;
        final List<LibraryItem> items;
        final String nextPageToken;
        final boolean truncated;

        Page(
                String category,
                int limit,
                int offset,
                List<LibraryItem> items,
                String nextPageToken,
                boolean truncated
        ) {
            this.category = category;
            this.limit = limit;
            this.offset = offset;
            this.items = items;
            this.nextPageToken = nextPageToken;
            this.truncated = truncated;
        }
    }

    static final class Result {
        final LibraryAccessState.Status status;
        final Page page;

        private Result(LibraryAccessState.Status status, Page page) {
            this.status = status;
            this.page = page;
        }

        static Result success(Page page) {
            return new Result(LibraryAccessState.Status.AVAILABLE, page);
        }

        static Result failure(LibraryAccessState.Status status) {
            return new Result(status, null);
        }

        boolean isSuccess() {
            return page != null;
        }
    }

    static final class CategorizedResult {
        final LibraryAccessState.Status status;
        final CategorizedSearch search;

        private CategorizedResult(LibraryAccessState.Status status, CategorizedSearch search) {
            this.status = status;
            this.search = search;
        }

        static CategorizedResult success(CategorizedSearch search) {
            return new CategorizedResult(LibraryAccessState.Status.AVAILABLE, search);
        }

        static CategorizedResult failure(LibraryAccessState.Status status) {
            return new CategorizedResult(status, null);
        }

        boolean isSuccess() {
            return search != null;
        }
    }

    private static final class Candidates {
        final List<LibraryItem> items;
        final boolean truncated;

        Candidates(List<LibraryItem> items, boolean truncated) {
            this.items = items;
            this.truncated = truncated;
        }
    }

    static final class SelectionResult {
        final LibraryAccessState.Status status;
        final boolean exists;

        SelectionResult(LibraryAccessState.Status status, boolean exists) {
            this.status = status;
            this.exists = exists;
        }
    }

    static final class InvalidPageTokenException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidPageTokenException() {
            super("Invalid page token");
        }
    }

    private static final long PAGE_TOKEN_TTL_MILLISECONDS = 5L * 60L * 1_000L;
    private static final int MAX_PAGE_TOKENS = 128;
    private static final Pattern PAGE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{24}");

    private final PowerampLibraryProvider provider;
    private final Listener listener;
    private final PageTokens pageTokens;
    private final AtomicLong accessSequence = new AtomicLong();
    private final AtomicReference<LibraryAccessState> accessState = new AtomicReference<>(
            new LibraryAccessState(LibraryAccessState.Status.UNKNOWN, 0L)
    );

    PowerampLibrarySource(
            PowerampLibraryProvider provider,
            Listener listener,
            LongSupplier monotonicClock
    ) {
        this(provider, listener, monotonicClock, new SecureRandom());
    }

    PowerampLibrarySource(
            PowerampLibraryProvider provider,
            Listener listener,
            LongSupplier monotonicClock,
            SecureRandom random
    ) {
        this.provider = Objects.requireNonNull(provider);
        this.listener = listener;
        pageTokens = new PageTokens(monotonicClock, random);
    }

    LibraryAccessState accessState() {
        return accessState.get();
    }

    Result probe(LibraryCancellation cancellation) {
        if (!provider.isPowerampInstalled()) {
            return failure(LibraryAccessState.Status.POWERAMP_MISSING);
        }
        try (PowerampLibraryProvider.Rows ignored = provider.query(
                PowerampLibraryContract.allTracks(),
                1,
                cancellation
        )) {
            updateAccess(LibraryAccessState.Status.AVAILABLE);
            return Result.success(new Page(
                    "probe",
                    1,
                    0,
                    Collections.emptyList(),
                    null,
                    false
            ));
        } catch (PowerampLibraryProvider.ProviderException exception) {
            return providerFailure(exception.failure);
        }
    }

    Result query(
            PowerampLibraryContract.Query query,
            int limit,
            String pageToken,
            Long currentQueueEntryId,
            LibraryCancellation cancellation
    ) throws InvalidPageTokenException {
        Objects.requireNonNull(query);
        Objects.requireNonNull(cancellation);
        if (limit < 1 || limit > PowerampLibraryContract.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid page size");
        }
        int offset = pageToken == null
                ? 0
                : pageTokens.resolve(pageToken, query.paginationKey());
        if (!provider.isPowerampInstalled()) {
            return failure(LibraryAccessState.Status.POWERAMP_MISSING);
        }

        int pageEnd = Math.min(
                PowerampLibraryContract.MAX_CONTINUATION_ROWS,
                offset + limit
        );
        // The extra provider row detects truncation; it must never become a returned item.
        int requestedRows = pageEnd + 1;
        List<LibraryItem> items = new ArrayList<>(limit);
        boolean hasMore = false;
        int rowIndex = 0;
        try (PowerampLibraryProvider.Rows rows = provider.query(
                query,
                requestedRows,
                cancellation
        )) {
            while (rows.moveToNext()) {
                if (cancellation.isCancelled()) {
                    return Result.failure(LibraryAccessState.Status.PROVIDER_ERROR);
                }
                if (rowIndex >= pageEnd) {
                    hasMore = true;
                    break;
                }
                if (rowIndex >= offset) {
                    LibraryItem item = parseItem(query, rows, currentQueueEntryId);
                    if (item != null) {
                        items.add(item);
                    }
                }
                rowIndex++;
            }
            updateAccess(LibraryAccessState.Status.AVAILABLE);
        } catch (PowerampLibraryProvider.ProviderException exception) {
            return providerFailure(exception.failure);
        }

        int nextOffset = pageEnd;
        boolean truncated = hasMore
                && nextOffset >= PowerampLibraryContract.MAX_CONTINUATION_ROWS;
        String nextToken = hasMore && !truncated
                ? pageTokens.issue(query.paginationKey(), nextOffset)
                : null;
        return Result.success(new Page(
                query.category,
                limit,
                offset,
                Collections.unmodifiableList(new ArrayList<>(items)),
                nextToken,
                truncated
        ));
    }

    CategorizedResult searchCategorized(
            String rawQuery,
            int limit,
            LibraryCancellation cancellation
    ) {
        String query = PowerampLibraryContract.validSearchQuery(rawQuery);
        if (limit < 1 || limit > PowerampLibraryContract.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid categorized Search limit");
        }
        Objects.requireNonNull(cancellation);
        if (!provider.isPowerampInstalled()) {
            return categorizedFailure(LibraryAccessState.Status.POWERAMP_MISSING);
        }

        try {
            Candidates exactTracks = readCandidates(
                    PowerampLibraryContract.categorizedExactTrackTitles(query), cancellation
            );
            Candidates partialTracks = readCandidates(
                    PowerampLibraryContract.categorizedTrackTitles(query), cancellation
            );
            List<LibraryItem> trackItems = new ArrayList<>(
                    exactTracks.items.size() + partialTracks.items.size()
            );
            trackItems.addAll(exactTracks.items);
            trackItems.addAll(partialTracks.items);
            CategorizedSearchPolicy.TrackSelection trackSelection =
                    CategorizedSearchPolicy.selectTracks(query, trackItems);
            Candidates directArtists = readCandidates(
                    PowerampLibraryContract.categorizedArtists(query), cancellation
            );

            List<LibraryItem> relatedArtistItems = new ArrayList<>();
            boolean relatedArtistsTruncated = false;
            if (trackSelection.exact) {
                LinkedHashMap<Long, Long> exactTrackIds = new LinkedHashMap<>();
                for (LibraryItem item : trackSelection.matches) {
                    exactTrackIds.putIfAbsent(item.id, item.id);
                }
                List<Long> ids = new ArrayList<>(exactTrackIds.values());
                int batchSize = PowerampLibraryContract.MAX_RELATED_TRACK_IDS_PER_QUERY;
                for (int start = 0; start < ids.size(); start += batchSize) {
                    int end = Math.min(ids.size(), start + batchSize);
                    Candidates related = readCandidates(
                            PowerampLibraryContract.relatedArtists(ids.subList(start, end)),
                            cancellation
                    );
                    relatedArtistItems.addAll(related.items);
                    relatedArtistsTruncated |= related.truncated;
                }
            }

            Candidates albums = readCandidates(
                    PowerampLibraryContract.categorizedAlbums(query), cancellation
            );
            CategorizedSearch result = CategorizedSearchPolicy.compose(
                    query,
                    limit,
                    trackSelection,
                    exactTracks.truncated || partialTracks.truncated,
                    directArtists.items,
                    directArtists.truncated,
                    relatedArtistItems,
                    relatedArtistsTruncated,
                    albums.items,
                    albums.truncated
            );
            updateAccess(LibraryAccessState.Status.AVAILABLE);
            return CategorizedResult.success(result);
        } catch (PowerampLibraryProvider.ProviderException exception) {
            LibraryAccessState.Status status = statusFor(exception.failure);
            if (exception.failure != PowerampLibraryProvider.Failure.CANCELLED) {
                updateAccess(status);
            }
            return CategorizedResult.failure(status);
        }
    }

    private Candidates readCandidates(
            PowerampLibraryContract.Query query,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        int maximum = PowerampLibraryContract.MAX_CATEGORIZED_SEARCH_CANDIDATES;
        List<LibraryItem> items = new ArrayList<>();
        boolean truncated = false;
        int rowIndex = 0;
        try (PowerampLibraryProvider.Rows rows = provider.query(
                query,
                maximum + 1,
                cancellation
        )) {
            while (rows.moveToNext()) {
                if (cancellation.isCancelled()) {
                    throw new PowerampLibraryProvider.ProviderException(
                            PowerampLibraryProvider.Failure.CANCELLED
                    );
                }
                if (rowIndex >= maximum) {
                    truncated = true;
                    break;
                }
                LibraryItem item = parseItem(query, rows, null);
                if (item != null) {
                    items.add(item);
                }
                rowIndex++;
            }
        }
        return new Candidates(
                Collections.unmodifiableList(items),
                truncated
        );
    }

    SelectionResult validateSelection(
            LibraryItem.PlayTarget target,
            LibraryCancellation cancellation
    ) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(cancellation);
        if (!provider.isPowerampInstalled()) {
            updateAccess(LibraryAccessState.Status.POWERAMP_MISSING);
            return new SelectionResult(LibraryAccessState.Status.POWERAMP_MISSING, false);
        }
        PowerampLibraryContract.Query query = PowerampLibraryContract.validationQuery(target);
        try (PowerampLibraryProvider.Rows rows = provider.query(query, 1, cancellation)) {
            boolean exists = rows.moveToNext() && matchesTarget(query, rows, target);
            updateAccess(LibraryAccessState.Status.AVAILABLE);
            return new SelectionResult(LibraryAccessState.Status.AVAILABLE, exists);
        } catch (PowerampLibraryProvider.ProviderException exception) {
            LibraryAccessState.Status status = statusFor(exception.failure);
            if (exception.failure != PowerampLibraryProvider.Failure.CANCELLED) {
                updateAccess(status);
            }
            return new SelectionResult(status, false);
        }
    }

    static boolean isValidPageTokenSyntax(String token) {
        return token != null && PAGE_TOKEN_PATTERN.matcher(token).matches();
    }

    private boolean matchesTarget(
            PowerampLibraryContract.Query query,
            PowerampLibraryProvider.Rows rows,
            LibraryItem.PlayTarget target
    ) {
        LibraryItem item = parseItem(query, rows, null);
        if (item == null) {
            return false;
        }
        switch (target.type) {
            case TRACK:
                return item.id == target.id;
            case PLAYLIST_ENTRY:
            case QUEUE_ENTRY:
                return item.entryId != null && item.entryId == target.id;
            case ALBUM:
            case PLAYLIST:
                return true;
            default:
                return false;
        }
    }

    private static LibraryItem parseItem(
            PowerampLibraryContract.Query query,
            PowerampLibraryProvider.Rows rows,
            Long currentQueueEntryId
    ) {
        switch (query.rowKind) {
            case ARTIST:
                return categoryItem(rows, LibraryItem.Type.ARTIST, null);
            case ALBUM:
                return categoryItem(rows, LibraryItem.Type.ALBUM, LibraryItem.PlayTarget.Type.ALBUM);
            case FOLDER:
            case HIERARCHY_FOLDER:
                return folderItem(rows);
            case PLAYLIST:
                return categoryItem(
                        rows,
                        LibraryItem.Type.PLAYLIST,
                        LibraryItem.PlayTarget.Type.PLAYLIST
                );
            case PLAYLIST_ENTRY:
                return trackItem(query, rows, LibraryItem.Type.PLAYLIST_ENTRY, null);
            case QUEUE_ENTRY:
                return trackItem(query, rows, LibraryItem.Type.QUEUE_ENTRY, currentQueueEntryId);
            case SEARCH_TRACK:
            case TRACK:
            default:
                return trackItem(query, rows, LibraryItem.Type.TRACK, null);
        }
    }

    private static LibraryItem categoryItem(
            PowerampLibraryProvider.Rows rows,
            LibraryItem.Type type,
            LibraryItem.PlayTarget.Type playType
    ) {
        Long id = positive(rows.longValue(PowerampLibraryContract.COLUMN_ITEM_ID));
        if (id == null) {
            return null;
        }
        LibraryItem.PlayTarget playTarget = null;
        if (playType == LibraryItem.PlayTarget.Type.ALBUM) {
            playTarget = LibraryItem.PlayTarget.album(id);
        } else if (playType == LibraryItem.PlayTarget.Type.PLAYLIST) {
            playTarget = LibraryItem.PlayTarget.playlist(id);
        }
        return new LibraryItem(
                type,
                id,
                null,
                null,
                rows.textValue(PowerampLibraryContract.COLUMN_TITLE),
                null,
                null,
                rows.longValue(PowerampLibraryContract.COLUMN_DURATION_MILLISECONDS),
                toInteger(rows.longValue(PowerampLibraryContract.COLUMN_TRACK_COUNT)),
                null,
                playTarget,
                null
        );
    }

    private static LibraryItem folderItem(PowerampLibraryProvider.Rows rows) {
        Long id = positive(rows.longValue(PowerampLibraryContract.COLUMN_ITEM_ID));
        if (id == null) {
            return null;
        }
        Long parentId = nonNegative(rows.longValue(PowerampLibraryContract.COLUMN_PARENT_ID));
        return new LibraryItem(
                LibraryItem.Type.FOLDER,
                id,
                null,
                parentId,
                rows.textValue(PowerampLibraryContract.COLUMN_TITLE),
                null,
                null,
                rows.longValue(PowerampLibraryContract.COLUMN_DURATION_MILLISECONDS),
                toInteger(rows.longValue(PowerampLibraryContract.COLUMN_TRACK_COUNT)),
                null,
                null,
                null
        );
    }

    private static LibraryItem trackItem(
            PowerampLibraryContract.Query query,
            PowerampLibraryProvider.Rows rows,
            LibraryItem.Type type,
            Long currentQueueEntryId
    ) {
        Long trackId = positive(rows.longValue(PowerampLibraryContract.COLUMN_TRACK_ID));
        if (trackId == null) {
            return null;
        }
        Long entryId = type == LibraryItem.Type.PLAYLIST_ENTRY
                || type == LibraryItem.Type.QUEUE_ENTRY
                ? positive(rows.longValue(PowerampLibraryContract.COLUMN_ENTRY_ID))
                : null;
        if ((type == LibraryItem.Type.PLAYLIST_ENTRY
                || type == LibraryItem.Type.QUEUE_ENTRY) && entryId == null) {
            return null;
        }
        LibraryItem.PlayTarget playTarget;
        if (type == LibraryItem.Type.PLAYLIST_ENTRY) {
            if (query.containerId == null) {
                return null;
            }
            playTarget = LibraryItem.PlayTarget.playlistEntry(query.containerId, entryId);
        } else if (type == LibraryItem.Type.QUEUE_ENTRY) {
            playTarget = LibraryItem.PlayTarget.queueEntry(entryId);
        } else {
            playTarget = LibraryItem.PlayTarget.track(trackId);
        }
        String title = rows.textValue(PowerampLibraryContract.COLUMN_TITLE);
        if (title == null || title.trim().isEmpty()) {
            title = rows.textValue(PowerampLibraryContract.COLUMN_FILE_NAME);
        }
        Boolean current = type == LibraryItem.Type.QUEUE_ENTRY
                ? currentQueueEntryId == null
                        ? null
                        : currentQueueEntryId.equals(entryId)
                : null;
        return new LibraryItem(
                type,
                trackId,
                entryId,
                query.containerId,
                title,
                rows.textValue(PowerampLibraryContract.COLUMN_ARTIST),
                rows.textValue(PowerampLibraryContract.COLUMN_ALBUM),
                rows.longValue(PowerampLibraryContract.COLUMN_DURATION_MILLISECONDS),
                null,
                PowerampLibraryContract.artworkApiPath(trackId),
                playTarget,
                current
        );
    }

    private Result failure(LibraryAccessState.Status status) {
        updateAccess(status);
        return Result.failure(status);
    }

    private CategorizedResult categorizedFailure(LibraryAccessState.Status status) {
        updateAccess(status);
        return CategorizedResult.failure(status);
    }

    private Result providerFailure(PowerampLibraryProvider.Failure failure) {
        LibraryAccessState.Status status = statusFor(failure);
        if (failure != PowerampLibraryProvider.Failure.CANCELLED) {
            updateAccess(status);
        }
        return Result.failure(status);
    }

    private static LibraryAccessState.Status statusFor(
            PowerampLibraryProvider.Failure failure
    ) {
        switch (failure) {
            case PERMISSION_REQUIRED:
                return LibraryAccessState.Status.PERMISSION_REQUIRED;
            case UNAVAILABLE:
                return LibraryAccessState.Status.PROVIDER_UNAVAILABLE;
            case CANCELLED:
            case ERROR:
            default:
                return LibraryAccessState.Status.PROVIDER_ERROR;
        }
    }

    private void updateAccess(LibraryAccessState.Status status) {
        while (true) {
            LibraryAccessState previous = accessState.get();
            if (previous.status == status) {
                return;
            }
            LibraryAccessState next = new LibraryAccessState(
                    status,
                    accessSequence.incrementAndGet()
            );
            if (accessState.compareAndSet(previous, next)) {
                if (listener != null) {
                    listener.onLibraryAccessChanged(next);
                }
                return;
            }
        }
    }

    private static Long positive(Long value) {
        return value != null && value > 0L ? value : null;
    }

    private static Long nonNegative(Long value) {
        return value != null && value >= 0L ? value : null;
    }

    private static Integer toInteger(Long value) {
        return value != null && value >= 0L && value <= Integer.MAX_VALUE
                ? value.intValue()
                : null;
    }

    @Override
    public void close() {
        pageTokens.clear();
    }

    private static final class PageTokens {
        private final LongSupplier clock;
        private final SecureRandom random;
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

        PageTokens(LongSupplier clock, SecureRandom random) {
            this.clock = Objects.requireNonNull(clock);
            this.random = Objects.requireNonNull(random);
        }

        synchronized String issue(String queryKey, int offset) {
            prune();
            while (entries.size() >= MAX_PAGE_TOKENS) {
                Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
            byte[] bytes = new byte[18];
            String token;
            do {
                random.nextBytes(bytes);
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            } while (entries.containsKey(token));
            entries.put(token, new Entry(
                    queryKey,
                    offset,
                    clock.getAsLong() + PAGE_TOKEN_TTL_MILLISECONDS
            ));
            return token;
        }

        synchronized int resolve(String token, String queryKey)
                throws InvalidPageTokenException {
            prune();
            if (!isValidPageTokenSyntax(token)) {
                throw new InvalidPageTokenException();
            }
            Entry entry = entries.get(token);
            if (entry == null || !entry.queryKey.equals(queryKey)) {
                throw new InvalidPageTokenException();
            }
            return entry.offset;
        }

        synchronized void clear() {
            entries.clear();
        }

        private void prune() {
            long now = clock.getAsLong();
            entries.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        }

        private static final class Entry {
            final String queryKey;
            final int offset;
            final long expiresAt;

            Entry(String queryKey, int offset, long expiresAt) {
                this.queryKey = queryKey;
                this.offset = offset;
                this.expiresAt = expiresAt;
            }
        }
    }
}
