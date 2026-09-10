package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class StructuredSearchQueryTest {
    @Test
    public void splitsOnlyExplicitSpacedAsciiOrTypographicDash() {
        StructuredSearchQuery ascii = StructuredSearchQuery.parse(
                "Moe Shop - Notice"
        );
        assertEquals("Moe Shop", ascii.artist);
        assertEquals("Notice", ascii.title);

        StructuredSearchQuery typographic = StructuredSearchQuery.parse(
                "Northlane — Obsidian"
        );
        assertEquals("Northlane", typographic.artist);
        assertEquals("Obsidian", typographic.title);

        assertNull(StructuredSearchQuery.parse("Sān-Z"));
        assertNull(StructuredSearchQuery.parse("artist-track"));
        assertNull(StructuredSearchQuery.parse("artist -track"));
        assertNull(StructuredSearchQuery.parse("artist- track"));
    }
}
