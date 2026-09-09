package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SearchComparisonPolicyTest {
    @Test
    public void normalizesAndAmpersandDiacriticsAndDashVariantsDeterministically() {
        assertEquals(
                SearchComparisonPolicy.MatchClass.EXACT,
                SearchComparisonPolicy.match(
                        " Of Mice and Men ", "Of Mice & Men", true
                ).matchClass
        );
        assertEquals(
                SearchComparisonPolicy.MatchClass.EXACT,
                SearchComparisonPolicy.match("san-z", "Sān‐Z", true).matchClass
        );
    }

    @Test
    public void permitsTwoTyposOnlyForLongEnoughQueries() {
        SearchComparisonPolicy.Match typo = SearchComparisonPolicy.match(
                "Nrthlame", "Northlane", true
        );

        assertEquals(SearchComparisonPolicy.MatchClass.FUZZY, typo.matchClass);
        assertEquals(2, typo.distance);
        assertEquals(1, SearchComparisonPolicy.match(
                "Nrothlane", "Northlane", true
        ).distance);
        assertEquals(
                SearchComparisonPolicy.MatchClass.NONE,
                SearchComparisonPolicy.match("cat", "cut", true).matchClass
        );
    }

    @Test
    public void preservesMeaningfulStageNamePunctuation() {
        assertEquals("ac/dc", SearchComparisonPolicy.comparisonKey("AC/DC"));
        assertEquals("p!nk", SearchComparisonPolicy.comparisonKey("P!nk"));
        assertEquals("ke$ha", SearchComparisonPolicy.comparisonKey("Ke$ha"));
        assertEquals(
                SearchComparisonPolicy.MatchClass.SUBSTRING,
                SearchComparisonPolicy.match(
                        "Moe Shop, KMNZ", "KMNZ; Moe Shop", true
                ).matchClass
        );
    }

    @Test
    public void providerProbesCoverSemanticVariantDiacriticAnchorAndTypoAnchor() {
        assertTrue(SearchComparisonPolicy.providerSearchProbes("Of Mice and Men")
                .contains("of mice & men"));
        assertTrue(SearchComparisonPolicy.providerSearchProbes("san-z").contains("n-z"));
        assertTrue(SearchComparisonPolicy.providerSearchProbes("Nrthlame").contains("thl"));
    }
}
