package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ArtworkPaletteTransitionTest {
    private static final ArtworkPalette FIRST = new ArtworkPalette(
            3, 0xff203040, 0xff402030, 0xff204030
    );
    private static final ArtworkPalette SECOND = new ArtworkPalette(
            3, 0xff503020, 0xff204050, 0xff403f20
    );
    private static final ArtworkPalette THIRD = new ArtworkPalette(
            2, 0xff304820, 0xff482048, 0xff482048
    );

    @Test
    public void interpolationKeepsExactStartAndEndPoints() {
        assertEquals(FIRST, ArtworkPalette.interpolate(FIRST, SECOND, 0f));
        assertEquals(SECOND, ArtworkPalette.interpolate(FIRST, SECOND, 1f));
    }

    @Test
    public void rapidRetargetContinuesFromCurrentVisualPalette() {
        ArtworkPaletteTransition.Plan firstChange = ArtworkPaletteTransition.retarget(
                FIRST, FIRST, 1f, SECOND, true
        );
        assertTrue(firstChange.animate);

        ArtworkPalette expectedCurrent = ArtworkPalette.interpolate(FIRST, SECOND, 0.4f);
        ArtworkPaletteTransition.Plan secondChange = ArtworkPaletteTransition.retarget(
                firstChange.start,
                firstChange.end,
                0.4f,
                THIRD,
                true
        );

        assertEquals(expectedCurrent, secondChange.start);
        assertEquals(THIRD, secondChange.end);
        assertTrue(secondChange.animate);
    }

    @Test
    public void disabledAnimationsApplyFinalPaletteImmediately() {
        ArtworkPaletteTransition.Plan plan = ArtworkPaletteTransition.retarget(
                FIRST, SECOND, 0.35f, THIRD, false
        );

        assertFalse(plan.animate);
        assertEquals(THIRD, plan.start);
        assertEquals(THIRD, plan.end);
    }
}
