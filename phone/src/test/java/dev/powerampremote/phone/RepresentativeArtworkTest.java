package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class RepresentativeArtworkTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String OTHER_SERVER_ID = "AQIDBAUGBwgJCgsMDQ4PEA";

    @Test
    public void categoryRequestsUseExistingDirectTrackRoutesAndSixItemPage() {
        assertEquals(
                "/api/v1/library/artists/1/tracks?limit=6",
                representative(RepresentativeArtworkKey.TYPE_ARTIST, 1L)
                        .tracksRequest(6).path(null)
        );
        assertEquals(
                "/api/v1/library/albums/2/tracks?limit=6",
                representative(RepresentativeArtworkKey.TYPE_ALBUM, 2L)
                        .tracksRequest(6).path(null)
        );
        assertEquals(
                "/api/v1/library/playlists/3/tracks?limit=6",
                representative(RepresentativeArtworkKey.TYPE_PLAYLIST, 3L)
                        .tracksRequest(6).path(null)
        );
        assertEquals(
                "/api/v1/library/folder-tree/4/tracks?limit=6",
                representative(RepresentativeArtworkKey.TYPE_FOLDER, 4L)
                        .tracksRequest(6).path(null)
        );

        assertEquals(
                "/api/v1/library/albums?limit=25",
                LibraryRequest.albums().path(null)
        );
        assertEquals(
                "/api/v1/library/albums/2/tracks?limit=6&pageToken=abcdefghijklmnopqrstuvwx",
                representative(RepresentativeArtworkKey.TYPE_ALBUM, 2L)
                        .tracksRequest(6).path("abcdefghijklmnopqrstuvwx")
        );
    }

    @Test
    public void probeChecksAtMostSixTracksAndSelectsFirstAvailableArtwork() {
        List<LibraryItem> items = new ArrayList<>();
        for (long id = 1L; id <= 8L; id++) items.add(track(id));

        List<LibraryArtworkKey> candidates =
                RepresentativeArtworkProbe.candidates(SERVER_ID, items);

        assertEquals(RepresentativeArtworkProbe.MAXIMUM_TRACKS, candidates.size());
        RepresentativeArtworkProbe probe = new RepresentativeArtworkProbe(candidates);
        LibraryArtworkKey first = candidates.get(0);
        LibraryArtworkKey second = candidates.get(1);
        assertSame(first, probe.current());
        assertEquals(
                RepresentativeArtworkProbe.Decision.TRY_NEXT,
                probe.apply(first, RepresentativeArtworkProbe.Outcome.MISSING)
        );
        assertSame(second, probe.current());
        assertEquals(
                RepresentativeArtworkProbe.Decision.SELECTED,
                probe.apply(second, RepresentativeArtworkProbe.Outcome.AVAILABLE)
        );
    }

    @Test
    public void probeRejectsResultForAStaleCandidate() {
        LibraryArtworkKey first = artwork(1L);
        LibraryArtworkKey second = artwork(2L);
        RepresentativeArtworkProbe probe = new RepresentativeArtworkProbe(
                Arrays.asList(first, second)
        );

        assertEquals(
                RepresentativeArtworkProbe.Decision.STALE,
                probe.apply(second, RepresentativeArtworkProbe.Outcome.AVAILABLE)
        );
        assertSame(first, probe.current());
    }

    @Test
    public void mappingIsServerScopedExpiresAndRefreshesWhenCandidatesChange() {
        RepresentativeArtworkCache cache = new RepresentativeArtworkCache();
        RepresentativeArtworkKey key = representative(
                RepresentativeArtworkKey.TYPE_ARTIST, 7L
        );
        LibraryArtworkKey selected = artwork(11L);
        long now = 1_000L;
        cache.selected(key, selected, now);

        RepresentativeArtworkCache.Mapping mapping = cache.get(key, now);
        assertEquals(RepresentativeArtworkCache.State.SELECTED, mapping.state);
        assertEquals(selected, mapping.artworkKey);
        assertNull(cache.get(
                representative(OTHER_SERVER_ID, RepresentativeArtworkKey.TYPE_ARTIST, 7L),
                now
        ));

        cache.rememberCandidates(key, Collections.singletonList(artwork(12L)), now + 1L);
        assertNull(cache.get(key, now + 1L));

        cache.selected(key, selected, now);
        assertNull(cache.get(
                key, now + RepresentativeArtworkCache.SELECTED_MAXIMUM_AGE_MILLISECONDS
        ));
    }

    @Test
    public void absenceAndMappingsHaveBoundedRetryAndEntryCount() {
        RepresentativeArtworkCache cache = new RepresentativeArtworkCache();
        RepresentativeArtworkKey missing = representative(
                RepresentativeArtworkKey.TYPE_FOLDER, 1L
        );
        long now = 2_000L;
        cache.missing(missing, now);
        assertEquals(RepresentativeArtworkCache.State.MISSING, cache.get(missing, now).state);
        assertNull(cache.get(
                missing, now + RepresentativeArtworkCache.MISSING_RETRY_MILLISECONDS
        ));

        for (int index = 1; index <= RepresentativeArtworkCache.MAXIMUM_ENTRIES + 1; index++) {
            RepresentativeArtworkKey key = representative(
                    RepresentativeArtworkKey.TYPE_ALBUM, index
            );
            cache.selected(key, artwork(index), now);
        }
        assertEquals(RepresentativeArtworkCache.MAXIMUM_ENTRIES, cache.mappingCount());
        assertNull(cache.get(
                representative(RepresentativeArtworkKey.TYPE_ALBUM, 1L), now
        ));
    }

    private static RepresentativeArtworkKey representative(String type, long id) {
        return representative(SERVER_ID, type, id);
    }

    private static RepresentativeArtworkKey representative(
            String serverId,
            String type,
            long id
    ) {
        return RepresentativeArtworkKey.create(serverId, type, id);
    }

    private static LibraryArtworkKey artwork(long trackId) {
        return LibraryArtworkKey.create(
                SERVER_ID, "/api/v1/library/artwork/tracks/" + trackId
        );
    }

    private static LibraryItem track(long id) {
        return new LibraryItem(
                "track",
                id,
                null,
                null,
                "Track " + id,
                null,
                null,
                null,
                null,
                "/api/v1/library/artwork/tracks/" + id,
                null,
                null
        );
    }
}
