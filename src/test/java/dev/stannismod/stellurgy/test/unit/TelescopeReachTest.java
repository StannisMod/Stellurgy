package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.universe.ConeWalk;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.RegionScan;
import dev.stannismod.stellurgy.universe.UniverseLawsV0;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What an instrument does with a reach it cannot afford to walk.
 *
 * <p><b>Why this is a unit test.</b> {@link RegionScan.Tuning} and the pointing it fits are built
 * from the numbers they are handed — the running game hands them the configured aperture through
 * {@code Tuning.fromConfig}, which is the only part that reads mod state and is not the subject
 * here. The tuning below is built through the same public constructor production builds it with.</p>
 */
public class TelescopeReachTest {

    /**
     * A constant: {@code GalacticCoord} is an immutable value: every field final, nothing mutable reachable.
     */
    private static final GalacticCoord HOME = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);

    /**
     * An aperture whose full-depth pointing holds more looks than the survey may walk is SHORTENED to
     * a pointing that fits — the operator sees the near sky and can point again — rather than refused.
     *
     * <p>Fails if {@code RegionScan.Tuning#fit} stops deciding to shorten an unaffordable pointing
     * (and refuses it, or walks it past the ceiling).</p>
     *
     * <p>The tuning is the arrangement and its numbers are chosen, not measured: an aperture of 25
     * against the stock star table, a five-degree opening, and a ceiling of 5 000 looks. The first
     * assertion MEASURES what they buy — that the full-depth pointing really would not fit — so the
     * shortening below is one the tuning forced and not one it merely permitted.</p>
     *
     * <p>red-witnessed: with {@code Tuning#fit} at {@code depth = Math.max(1, depth / 2);}
     * reading {@code break;}, this fails with "an aperture too good for the budget must be shortened,
     * not refused: a pointing of half-angle 5,000 degrees holds more than 5000 looks …"; at
     * {@code if (aimed.totalLooks() <= maxCells)} reading {@code if (true)}, with "the survey must fit
     * under its ceiling: 1572443916 looks"; at {@code int depth = Math.max(1, steps);} reading
     * {@code int depth = 1;}, with "and it must still be a survey past the instrument's own territory:
     * 3525313 cells at a stride of 3525313" — one inversion per run, 2026-10-02.</p>
     */
    @Test
    public void anApertureTooGoodForTheLookBudgetIsShortenedRatherThanRefused() {
        long stride = GalaxyGenConfig.DEFAULT_MIN_SPACING;
        int ceiling = 5_000;
        RegionScan.Tuning greedy = new RegionScan.Tuning(25d, GalaxyGenConfig.defaults().starTypes,
                Math.toRadians(5d), ceiling, 20, 100, stride, UniverseLawsV0.INSTANCE);
        int fullDepth = greedy.maxRangeSteps();

        boolean fullDepthFits;
        try {
            fullDepthFits = ConeWalk.aimed(HOME, 1d, 0d, 0d, greedy.halfAngleRadians(),
                    fullDepth * stride, stride).totalLooks() <= ceiling;
        } catch (IllegalArgumentException tooLargeToCount) {
            fullDepthFits = false;
        }
        assertTrue("arrangement: the full-depth pointing (" + fullDepth + " territories) must hold more"
                + " looks than the ceiling of " + ceiling + ", or nothing below was forced", !fullDepthFits);

        RegionScan scan;
        try {
            scan = RegionScan.directed(HOME, 1, 0, 0, fullDepth, 0L, greedy);
        } catch (IllegalArgumentException refused) {
            fail("an aperture too good for the budget must be shortened, not refused: "
                    + refused.getMessage());
            return;
        }
        // Under the ceiling, while the full depth was not: so the pointing it got IS a shortened one.
        assertTrue("the survey must fit under its ceiling: " + scan.totalCells() + " looks",
                scan.totalCells() <= ceiling);
        assertTrue("and it must still be a survey past the instrument's own territory: "
                + scan.distanceCells() + " cells at a stride of " + stride,
                scan.distanceCells() > stride);
    }
}
