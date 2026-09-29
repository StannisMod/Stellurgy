package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.test.ArrangementFailure;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.Plot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The plot allocator's own contract: two scenarios never get overlapping ground, and a site is
 * always in the open-air band.
 *
 * <p><b>Why this test exists at all.</b> Until 2026-09-14 non-overlap was a promise each scenario
 * made by choosing its own coordinates, and the promise was broken in the ordinary way — two
 * scenarios of one class built at one site in one world, each silently levelling the other's
 * leavings with its pre-clear, for as long as nobody asserted it. Replacing that with a shared
 * {@code site()} only moves the trust: the mechanism is now the single thing every scenario relies
 * on, so the mechanism is what has to be checked. Without this file we would have exchanged a
 * hunt for point bugs for one unproven allocator.</p>
 *
 * <p>No harness: a plot is arithmetic, and arithmetic is cheap to interrogate exhaustively.</p>
 */
public class PlotAllocationContractTest {

    /** Enough scenarios to cover more than one class's worth, cheaply. */
    private static final int SCENARIOS = 64;

    private static Plot plot(int index) {
        return Plot.forScenario(index, "scenario#" + index, 0, Plot.Lane.DEFAULT);
    }

    @Test
    public void everyAllocatedSiteStandsInTheOpenAirBand() {
        for (int i = 0; i < SCENARIOS; i++) {
            assertEquals("an allocated site must be in the band, whatever the lane or index — the"
                            + " height is the half of this contract FixtureSite owns",
                    FixtureSite.OPEN_AIR_Y, plot(i).site().y);
        }
    }

    @Test
    public void noTwoScenariosGetOverlappingWorkingVolumes() {
        int[][] envelopes = new int[SCENARIOS][];
        for (int i = 0; i < SCENARIOS; i++) {
            Plot p = plot(i);
            FixtureSite s = p.site();
            int halo = p.maxHalo();
            // The widest volume this plot will ever let a fixture clear. If the WIDEST cannot
            // overlap, nothing narrower can either.
            envelopes[i] = new int[]{s.x - halo, s.z - halo,
                    s.x + fixturePad() + halo, s.z + fixturePad() + halo};
        }
        for (int i = 0; i < SCENARIOS; i++) {
            for (int j = i + 1; j < SCENARIOS; j++) {
                assertTrue("scenarios " + i + " and " + j + " would clear overlapping ground: "
                                + box(envelopes[i]) + " vs " + box(envelopes[j])
                                + " — one of them levels the other's fixture and neither can see it",
                        disjoint(envelopes[i], envelopes[j]));
            }
        }
    }

    @Test
    public void aFixturesWidestWorkingVolumeFitsInsideItsOwnPlot() {
        for (int i = 0; i < SCENARIOS; i++) {
            Plot p = plot(i);
            FixtureSite s = p.site();
            int halo = p.maxHalo();
            assertTrue("the plot must hold the envelope it advertises as its maximum halo: " + p,
                    p.containsBox(s.x - halo, s.z - halo,
                            s.x + fixturePad() + halo, s.z + fixturePad() + halo));
            assertTrue("a plot that cannot hold a fixture at all would be an allocator handing out"
                            + " unusable ground: " + p, halo > 0);
        }
    }

    @Test
    public void clearingPastThePlotIsRefusedBeforeAnyBlockIsTouched() throws Exception {
        Plot p = plot(0);
        FixtureSite s = p.site();
        // A probe that would report a perfectly empty volume. If the refusal depended on what the
        // world said, this would pass and the guard would be worthless.
        final boolean[] probeWasCalled = {false};
        Events.Probe neverAsked = cmd -> {
            probeWasCalled[0] = true;
            return "{\"ok\":true,\"placed\":0,\"volume\":0}";
        };
        try {
            s.requireClear(neverAsked, p.maxHalo() + 1, 10, "a halo one block past the plot");
            fail("clearing past the plot must be refused; it was allowed");
        } catch (ArrangementFailure expected) {
            assertTrue("the refusal must name the plot so a reader knows whose ground was at risk: "
                            + expected.getMessage(),
                    expected.getMessage().contains("leaves this scenario's own plot"));
        }
        assertTrue("the refusal must come BEFORE the fill — a guard that first clears a neighbour's"
                        + " ground and then complains has already done the damage",
                !probeWasCalled[0]);
    }

    /**
     * The same contract one level down, and the level {@link Plot#siteAt} opened. A scenario that
     * stands TWO structures on its own plot can put them through each other exactly as two
     * scenarios used to; the plot refuses the second clear rather than trusting the offsets.
     */
    @Test
    public void twoFixturesOfOneScenarioMayNotClearOverlappingGround() throws Exception {
        Plot p = Plot.forScenario(0, "two-structure scenario", 0, new Plot.Lane(0, 0, 192, 192));
        Events.Probe empty = cmd -> "{\"ok\":true,\"placed\":0,\"volume\":0}";

        // Twelve blocks apart, with a halo of 8 each: the first fixture's envelope reaches to
        // dx=20+5+8=33 and the second's starts at dx=32-8=24, so they share ground.
        p.siteAt(20, 20).requireClear(empty, 8, 10, "the first structure");
        try {
            p.siteAt(32, 32).requireClear(empty, 8, 10, "the second structure, too close");
            fail("two fixtures on one plot cleared overlapping ground; the second must be refused");
        } catch (ArrangementFailure expected) {
            assertTrue("the refusal must name the ground already cleared, so a reader knows WHICH"
                            + " structure is at risk: " + expected.getMessage(),
                    expected.getMessage().contains("already cleared"));
        }

        // Far enough apart, the same two calls are fine — otherwise this test would pass on a plot
        // that simply refuses everything.
        p.siteAt(20, 20).requireClear(empty, 8, 10, "the first structure again, grown");
        p.siteAt(120, 120).requireClear(empty, 8, 10, "a second structure with room of its own");
    }

    /** A fixture re-prepared at its OWN base is one structure and may clear a wider volume. */
    @Test
    public void oneFixtureMayBeClearedTwiceWithADifferentHalo() throws Exception {
        Plot p = plot(0);
        Events.Probe empty = cmd -> "{\"ok\":true,\"placed\":0,\"volume\":0}";
        p.site().requireClear(empty, 2, 10, "the fixture, prepared");
        p.site().requireClear(empty, 12, 10, "the same fixture, prepared wider");
    }

    @Test
    public void aLaneCannotBeDeclaredNarrowerThanItsPlots() {
        try {
            new Plot.Lane(0, 0, 16, 64);
            fail("a stride narrower than a plot makes every plot overlap its neighbour; it must be"
                    + " refused at the lane, which is the one place that can still see both numbers");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("overlap"));
        }
    }

    /**
     * The launchpad edge {@code FixtureSite} lays at a site. Duplicated here as a LOCAL constant on
     * purpose: this test is the independent check on the allocator, and reading the number out of
     * the class under test would make the overlap arithmetic agree with itself by construction.
     * If the fixture's pad changes, this fails and is meant to.
     */
    private static int fixturePad() {
        return 5;
    }

    private static boolean disjoint(int[] a, int[] b) {
        return a[2] < b[0] || b[2] < a[0] || a[3] < b[1] || b[3] < a[1];
    }

    private static String box(int[] e) {
        return "(" + e[0] + "," + e[1] + ")..(" + e[2] + "," + e[3] + ")";
    }
}
