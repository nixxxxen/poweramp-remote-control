package dev.powerampremote.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private static final class IndexedItem {
        final LibraryItem item;
        final String comparisonKey;

        IndexedItem(LibraryItem item) {
            this.item = item;
            comparisonKey = SearchComparisonPolicy.comparisonKey(item.title);
        }
    }

    private static final class FuzzyIndex {
        final long generation;
        final long builtAt;
        final List<IndexedItem> artists;
        final List<IndexedItem> albums;

        FuzzyIndex(
                long generation,
                long builtAt,
                List<IndexedItem> artists,
                List<IndexedItem> albums
        ) {
            this.generation = generation;
            this.builtAt = builtAt;
            this.artists = Collections.unmodifiableList(new ArrayList<>(artists));
            this.albums = Collections.unmodifiableList(new ArrayList<>(albums));
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
    private static final int MAX_PAGING_SESSIONS = 8;
    private static final long FUZZY_INDEX_TTL_MILLISECONDS = 2L * 60L * 1_000L;
    private static final Pattern PAGE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{24}");

    private final PowerampLibraryProvider provider;
    private final Listener listener;
    private final LongSupplier monotonicClock;
    private final PagingSessionStore<LibraryItem> pagingSessions;
    private final ExecutorService fuzzyIndexExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "poweramp-search-index");
        thread.setDaemon(true);
        return thread;
    });
    private final Object fuzzyIndexLock = new Object();
    private final AtomicLong accessSequence = new AtomicLong();
    private final AtomicLong fuzzyIndexGeneration = new AtomicLong();
    private final AtomicReference<LibraryAccessState> accessState = new AtomicReference<>(
            new LibraryAccessState(LibraryAccessState.Status.UNKNOWN, 0L)
    );
    private volatile FuzzyIndex fuzzyIndex;
    private Future<FuzzyIndex> fuzzyIndexBuild;
    private LibraryCancellation fuzzyIndexCancellation;

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
        this.monotonicClock = Objects.requireNonNull(monotonicClock);
        pagingSessions = new PagingSessionStore<>(
                MAX_PAGING_SESSIONS,
                PAGE_TOKEN_TTL_MILLISECONDS,
                monotonicClock,
                random
        );
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
        if (!provider.isPowerampInstalled()) {
            return failure(LibraryAccessState.Status.POWERAMP_MISSING);
        }
        try {
            PagingSessionStore.Page<LibraryItem> page;
            if (pageToken == null) {
                List<LibraryItem> snapshot = readAll(query, currentQueueEntryId, cancellation);
                if (cancellation.isCancelled()) {
                    return Result.failure(LibraryAccessState.Status.PROVIDER_ERROR);
                }
                page = pagingSessions.firstPage(query.paginationKey(), snapshot, limit, null);
            } else {
                page = pagingSessions.nextPage(pageToken, query.paginationKey(), limit);
                if (cancellation.isCancelled()) {
                    pagingSessions.discard(pageToken);
                    return Result.failure(LibraryAccessState.Status.PROVIDER_ERROR);
                }
            }
            updateAccess(LibraryAccessState.Status.AVAILABLE);
            return Result.success(new Page(
                    query.category,
                    limit,
                    page.offset,
                    page.items,
                    page.nextPageToken,
                    false
            ));
        } catch (PagingSessionStore.InvalidTokenException exception) {
            throw new InvalidPageTokenException();
        } catch (PowerampLibraryProvider.ProviderException exception) {
            return providerFailure(exception.failure);
        }
    }

    CategorizedResult searchCategorized(
            String rawQuery,
            int limit,
            LibraryCancellation cancellation
    ) {
        try {
            return searchCategorized(rawQuery, limit, null, null, cancellation);
        } catch (InvalidPageTokenException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    CategorizedResult searchCategorized(
            String rawQuery,
            int limit,
            String sectionName,
            String pageToken,
            LibraryCancellation cancellation
    ) throws InvalidPageTokenException {
        String query = PowerampLibraryContract.validSearchQuery(rawQuery);
        if (limit < 1 || limit > PowerampLibraryContract.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid categorized Search limit");
        }
        Objects.requireNonNull(cancellation);
        CategorizedSearch.SectionType requestedSection = sectionName == null
                ? null : CategorizedSearch.SectionType.fromWireName(sectionName);
        if (pageToken != null && requestedSection == null) {
            throw new IllegalArgumentException("Search continuation requires a section");
        }
        if (!provider.isPowerampInstalled()) {
            return categorizedFailure(LibraryAccessState.Status.POWERAMP_MISSING);
        }

        try {
            if (pageToken != null) {
                PagingSessionStore.Page<LibraryItem> page = pagingSessions.nextPage(
                        pageToken, searchPagingKey(query, requestedSection), limit
                );
                if (cancellation.isCancelled()) {
                    pagingSessions.discard(pageToken);
                    return CategorizedResult.failure(LibraryAccessState.Status.PROVIDER_ERROR);
                }
                CategorizedSearch.TrackMatch trackMatch =
                        (CategorizedSearch.TrackMatch) page.metadata;
                CategorizedSearch.Section section = new CategorizedSearch.Section(
                        requestedSection,
                        page.items,
                        page.nextPageToken != null,
                        page.nextPageToken
                );
                updateAccess(LibraryAccessState.Status.AVAILABLE);
                return CategorizedResult.success(new CategorizedSearch(
                        query, limit, trackMatch, Collections.singletonList(section)
                ));
            }

            CategorizedSearch complete = completeCategorizedSearch(query, limit, cancellation);
            CategorizedSearch result = pageCategorizedSearch(
                    complete, requestedSection, limit
            );
            updateAccess(LibraryAccessState.Status.AVAILABLE);
            return CategorizedResult.success(result);
        } catch (PagingSessionStore.InvalidTokenException exception) {
            throw new InvalidPageTokenException();
        } catch (PowerampLibraryProvider.ProviderException exception) {
            LibraryAccessState.Status status = statusFor(exception.failure);
            if (exception.failure != PowerampLibraryProvider.Failure.CANCELLED) {
                updateAccess(status);
            }
            return CategorizedResult.failure(status);
        }
    }

    private static Candidates mergeCandidates(List<Candidates> sources) {
        LinkedHashMap<Long, LibraryItem> byId = new LinkedHashMap<>();
        boolean truncated = false;
        for (Candidates source : sources) {
            if (source == null) continue;
            truncated |= source.truncated;
            for (LibraryItem item : source.items) {
                if (item == null || byId.containsKey(item.id)) continue;
                byId.put(item.id, item);
            }
        }
        return new Candidates(
                Collections.unmodifiableList(new ArrayList<>(byId.values())), truncated
        );
    }

    private Candidates readCandidates(
            PowerampLibraryContract.Query query,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        return new Candidates(readAll(query, null, cancellation), false);
    }

    private List<LibraryItem> readAll(
            PowerampLibraryContract.Query query,
            Long currentQueueEntryId,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        List<LibraryItem> items = new ArrayList<>();
        try (PowerampLibraryProvider.Rows rows = provider.query(
                query,
                PowerampLibraryContract.PROVIDER_ALL_ROWS,
                cancellation
        )) {
            while (rows.moveToNext()) {
                if (cancellation.isCancelled()) {
                    throw new PowerampLibraryProvider.ProviderException(
                            PowerampLibraryProvider.Failure.CANCELLED
                    );
                }
                LibraryItem item = parseItem(query, rows, currentQueueEntryId);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return Collections.unmodifiableList(items);
    }

    private CategorizedSearch completeCategorizedSearch(
            String query,
            int limit,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        StructuredSearchQuery structured = StructuredSearchQuery.parse(query);
        if (structured != null) {
            CategorizedSearch structuredResult = completeStructuredSearch(
                    structured, limit, cancellation
            );
            if (structuredResult != null) return structuredResult;
        }
        return completeOrdinarySearch(query, limit, cancellation);
    }

    private CategorizedSearch completeOrdinarySearch(
            String query,
            int limit,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        Candidates trackCandidates = trackCandidates(query, null, cancellation);
        CategorizedSearchPolicy.TrackSelection trackSelection =
                CategorizedSearchPolicy.selectTracks(query, trackCandidates.items);

        List<LibraryItem> relatedArtistItems = new ArrayList<>();
        List<Long> exactTrackIds = new ArrayList<>();
        for (LibraryItem item : trackSelection.matches) {
            if (SearchComparisonPolicy.match(query, item.title, false).matchClass
                    == SearchComparisonPolicy.MatchClass.EXACT) {
                exactTrackIds.add(item.id);
            }
        }
        for (List<Long> batch : batches(
                exactTrackIds, PowerampLibraryContract.MAX_RELATED_TRACK_IDS_PER_QUERY
        )) {
            relatedArtistItems.addAll(readCandidates(
                    PowerampLibraryContract.relatedArtists(batch), cancellation
            ).items);
        }

        Candidates directArtists = entityCandidates(
                query, LibraryItem.Type.ARTIST, cancellation
        );
        CategorizedSearchPolicy.ArtistSelection artistSelection =
                CategorizedSearchPolicy.selectArtists(
                        query, directArtists.items, relatedArtistItems
                );

        List<LibraryItem> relatedAlbums = new ArrayList<>();
        for (List<Long> batch : batches(
                artistSelection.ids(), PowerampLibraryContract.MAX_RELATED_ARTIST_IDS_PER_QUERY
        )) {
            relatedAlbums.addAll(readCandidates(
                    PowerampLibraryContract.relatedAlbums(batch), cancellation
            ).items);
        }
        Candidates directAlbums = directEntityCandidates(
                query, LibraryItem.Type.ALBUM, cancellation
        );
        if (directAlbums.items.isEmpty() && relatedAlbums.isEmpty()) {
            directAlbums = new Candidates(
                    indexedMatches(query, LibraryItem.Type.ALBUM, cancellation), false
            );
        }
        return CategorizedSearchPolicy.compose(
                query,
                limit,
                trackSelection,
                false,
                artistSelection,
                false,
                directAlbums.items,
                false,
                relatedAlbums,
                false
        );
    }

    private CategorizedSearch completeStructuredSearch(
            StructuredSearchQuery query,
            int limit,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        Candidates artistCandidates = entityCandidates(
                query.artist, LibraryItem.Type.ARTIST, cancellation
        );
        CategorizedSearchPolicy.ArtistSelection artists =
                CategorizedSearchPolicy.selectArtists(
                        query.artist, artistCandidates.items, Collections.emptyList()
                );
        if (artists.matches.isEmpty()) return null;

        Candidates tracks = trackCandidates(query.title, artists.ids(), cancellation);
        CategorizedSearchPolicy.TrackSelection trackSelection =
                CategorizedSearchPolicy.selectTracks(query.title, tracks.items);
        Candidates albums = structuredAlbumCandidates(
                query.title, artists.ids(), cancellation
        );
        return CategorizedSearchPolicy.compose(
                query.original,
                query.title,
                limit,
                trackSelection,
                false,
                artists,
                false,
                albums.items,
                false,
                Collections.emptyList(),
                false
        );
    }

    private Candidates trackCandidates(
            String query,
            List<Long> artistIds,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        List<Candidates> sources = new ArrayList<>();
        List<List<Long>> artistBatches = artistIds == null
                ? Collections.singletonList(null)
                : batches(artistIds, PowerampLibraryContract.MAX_RELATED_ARTIST_IDS_PER_QUERY);
        for (List<Long> batch : artistBatches) {
            sources.add(readCandidates(batch == null
                    ? PowerampLibraryContract.categorizedTrackTitles(query)
                    : PowerampLibraryContract.categorizedTrackTitlesForArtists(query, batch),
                    cancellation));
            for (String probe : SearchComparisonPolicy.providerSearchProbes(query)) {
                sources.add(readCandidates(batch == null
                        ? PowerampLibraryContract.categorizedTrackTitles(probe)
                        : PowerampLibraryContract.categorizedTrackTitlesForArtists(probe, batch),
                        cancellation));
            }
        }
        return mergeCandidates(sources);
    }

    private Candidates entityCandidates(
            String query,
            LibraryItem.Type type,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        Candidates direct = directEntityCandidates(query, type, cancellation);
        if (!direct.items.isEmpty()) return direct;
        return new Candidates(indexedMatches(query, type, cancellation), false);
    }

    private Candidates directEntityCandidates(
            String query,
            LibraryItem.Type type,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        List<Candidates> sources = new ArrayList<>();
        sources.add(readCandidates(entityQuery(type, query), cancellation));
        String key = SearchComparisonPolicy.comparisonKey(query);
        if (!key.isEmpty() && !key.equalsIgnoreCase(query.trim())) {
            sources.add(readCandidates(entityQuery(type, key), cancellation));
        }
        return mergeCandidates(sources);
    }

    private Candidates structuredAlbumCandidates(
            String query,
            List<Long> artistIds,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        List<Candidates> sources = new ArrayList<>();
        for (List<Long> batch : batches(
                artistIds, PowerampLibraryContract.MAX_RELATED_ARTIST_IDS_PER_QUERY
        )) {
            sources.add(readCandidates(
                    PowerampLibraryContract.categorizedAlbumsForArtists(query, batch),
                    cancellation
            ));
            for (String probe : SearchComparisonPolicy.providerSearchProbes(query)) {
                sources.add(readCandidates(
                        PowerampLibraryContract.categorizedAlbumsForArtists(probe, batch),
                        cancellation
                ));
            }
        }
        return mergeCandidates(sources);
    }

    private static PowerampLibraryContract.Query entityQuery(
            LibraryItem.Type type,
            String query
    ) {
        if (type == LibraryItem.Type.ARTIST) {
            return PowerampLibraryContract.categorizedArtists(query);
        }
        if (type == LibraryItem.Type.ALBUM) {
            return PowerampLibraryContract.categorizedAlbums(query);
        }
        throw new IllegalArgumentException("Unsupported indexed entity type");
    }

    private List<LibraryItem> indexedMatches(
            String query,
            LibraryItem.Type type,
            LibraryCancellation cancellation
    ) throws PowerampLibraryProvider.ProviderException {
        FuzzyIndex index = awaitFuzzyIndex(cancellation);
        List<IndexedItem> source = type == LibraryItem.Type.ARTIST
                ? index.artists : index.albums;
        String queryKey = SearchComparisonPolicy.comparisonKey(query);
        List<LibraryItem> matches = new ArrayList<>();
        for (IndexedItem item : source) {
            if (cancellation.isCancelled()) {
                throw new PowerampLibraryProvider.ProviderException(
                        PowerampLibraryProvider.Failure.CANCELLED
                );
            }
            if (SearchComparisonPolicy.matchComparisonKeys(
                    queryKey, item.comparisonKey, true
            ).matchClass != SearchComparisonPolicy.MatchClass.NONE) {
                matches.add(item.item);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    private FuzzyIndex awaitFuzzyIndex(LibraryCancellation cancellation)
            throws PowerampLibraryProvider.ProviderException {
        FuzzyIndex current = fuzzyIndex;
        long now = monotonicClock.getAsLong();
        if (current != null && now - current.builtAt < FUZZY_INDEX_TTL_MILLISECONDS) {
            return current;
        }
        Future<FuzzyIndex> future;
        synchronized (fuzzyIndexLock) {
            current = fuzzyIndex;
            now = monotonicClock.getAsLong();
            if (current != null && now - current.builtAt < FUZZY_INDEX_TTL_MILLISECONDS) {
                return current;
            }
            if (fuzzyIndexBuild == null) {
                LibraryCancellation buildCancellation = new LibraryCancellation();
                fuzzyIndexCancellation = buildCancellation;
                long generation = fuzzyIndexGeneration.incrementAndGet();
                fuzzyIndexBuild = fuzzyIndexExecutor.submit(
                        () -> buildFuzzyIndex(generation, buildCancellation)
                );
            }
            future = fuzzyIndexBuild;
        }
        try {
            while (true) {
                if (cancellation.isCancelled()) {
                    throw new PowerampLibraryProvider.ProviderException(
                            PowerampLibraryProvider.Failure.CANCELLED
                    );
                }
                try {
                    FuzzyIndex built = future.get(50L, TimeUnit.MILLISECONDS);
                    fuzzyIndex = built;
                    synchronized (fuzzyIndexLock) {
                        if (fuzzyIndexBuild == future) {
                            fuzzyIndexBuild = null;
                            fuzzyIndexCancellation = null;
                        }
                    }
                    return built;
                } catch (TimeoutException ignored) {
                    // Keep cancellation responsive while the service-owned index is built.
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PowerampLibraryProvider.ProviderException(
                    PowerampLibraryProvider.Failure.CANCELLED
            );
        } catch (CancellationException exception) {
            throw new PowerampLibraryProvider.ProviderException(
                    PowerampLibraryProvider.Failure.CANCELLED
            );
        } catch (ExecutionException exception) {
            synchronized (fuzzyIndexLock) {
                if (fuzzyIndexBuild == future) {
                    fuzzyIndexBuild = null;
                    fuzzyIndexCancellation = null;
                }
            }
            Throwable cause = exception.getCause();
            if (cause instanceof PowerampLibraryProvider.ProviderException) {
                throw (PowerampLibraryProvider.ProviderException) cause;
            }
            throw new PowerampLibraryProvider.ProviderException(
                    PowerampLibraryProvider.Failure.ERROR
            );
        }
    }

    private FuzzyIndex buildFuzzyIndex(long generation, LibraryCancellation cancellation)
            throws PowerampLibraryProvider.ProviderException {
        List<IndexedItem> artists = index(readAll(
                PowerampLibraryContract.artists(), null, cancellation
        ));
        List<IndexedItem> albums = index(readAll(
                PowerampLibraryContract.albums(), null, cancellation
        ));
        return new FuzzyIndex(
                generation, monotonicClock.getAsLong(), artists, albums
        );
    }

    private static List<IndexedItem> index(List<LibraryItem> items) {
        List<IndexedItem> result = new ArrayList<>(items.size());
        for (LibraryItem item : items) {
            if (item.title != null) result.add(new IndexedItem(item));
        }
        return result;
    }

    private CategorizedSearch pageCategorizedSearch(
            CategorizedSearch complete,
            CategorizedSearch.SectionType requestedSection,
            int limit
    ) {
        List<CategorizedSearch.Section> sections = new ArrayList<>();
        for (CategorizedSearch.Section section : complete.sections) {
            if (requestedSection != null && section.type != requestedSection) continue;
            PagingSessionStore.Page<LibraryItem> page = pagingSessions.firstPage(
                    searchPagingKey(complete.query, section.type),
                    section.items,
                    limit,
                    complete.trackMatch
            );
            if (!page.items.isEmpty()) {
                sections.add(new CategorizedSearch.Section(
                        section.type,
                        page.items,
                        page.nextPageToken != null,
                        page.nextPageToken
                ));
            }
        }
        return new CategorizedSearch(
                complete.query, limit, complete.trackMatch, sections
        );
    }

    private static String searchPagingKey(
            String query,
            CategorizedSearch.SectionType section
    ) {
        return "categorized_search\n" + query + '\n' + section.wireName;
    }

    private static List<List<Long>> batches(List<Long> values, int maximumBatchSize) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<List<Long>> result = new ArrayList<>();
        for (int start = 0; start < values.size(); start += maximumBatchSize) {
            result.add(values.subList(start, Math.min(values.size(), start + maximumBatchSize)));
        }
        return result;
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

    void cancelPagingSessions() {
        pagingSessions.close();
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
                null,
                type == LibraryItem.Type.ARTIST
                        ? booleanValue(rows.longValue(
                                PowerampLibraryContract.COLUMN_ARTIST_IS_UNSPLIT
                        ))
                        : null,
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

    private static Boolean booleanValue(Long value) {
        return value == null ? null : value != 0L;
    }

    @Override
    public void close() {
        pagingSessions.close();
        synchronized (fuzzyIndexLock) {
            if (fuzzyIndexCancellation != null) fuzzyIndexCancellation.cancel();
            if (fuzzyIndexBuild != null) fuzzyIndexBuild.cancel(true);
            fuzzyIndexBuild = null;
            fuzzyIndexCancellation = null;
            fuzzyIndex = null;
        }
        fuzzyIndexExecutor.shutdownNow();
    }
}
