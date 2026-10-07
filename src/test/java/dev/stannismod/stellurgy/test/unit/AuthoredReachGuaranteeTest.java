package dev.stannismod.stellurgy.test.unit;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.UniverseScale;

import static org.junit.Assert.assertTrue;

/**
 * What a generator promises authored content about staying inside its galaxy.
 *
 * <p>A unit test of {@link ClusteredGalaxyGenerator#guaranteedAuthoredReachLy}, on generators this test
 * builds and owns from the shipped galaxy table and from that table's own dwarf types.</p>
 */
public class AuthoredReachGuaranteeTest {

    /**
     * A pack whose galaxies are all dwarfs is promised no more than its smallest dwarf can hold.
     *
     * <p>Fails if {@code GalaxyField#guaranteedAuthoredReachLy} stops deciding the promise from the
     * table it draws from (it stated the shipped figure, sized for a galaxy of
     * {@code MIN_AUTHORED_GALAXY_RADIUS_LY}, while a table with no such type drew an authored galaxy
     * from the whole table — a dwarf the promise did not fit).</p>
     *
     * <p>A home galaxy sits with its declaration origin {@code HOME_GALAXY_ORIGIN_FRACTION} of its
     * radius from its centre, so the reach a galaxy of radius {@code R} keeps is
     * {@code (1 - fraction) * R}; the promise must fit inside it for the smallest radius the table can
     * draw. The control is the shipped table, which holds a type large enough and is promised more
     * than that dwarf holds — so a promise of nothing at all does not pass.</p>
     * <p>red-witnessed: with {@code GalaxyField#guaranteedAuthoredReachLy} at {@code if (hostsAuthoredContent())} made {@code if (true)}, fails: "a table of dwarfs is promised 6749.999999999999 ly while its smallest dwarf holds 224.99999999999997 ly" (2026-10-07).</p>
     */
    @Test
    public void aTableOfDwarfsIsPromisedNoMoreThanItsSmallestDwarfHolds() {
        List<GalaxyGenConfig.GalaxyType> dwarfs = new ArrayList<>();
        double smallestRadiusLy = Double.MAX_VALUE;
        for (GalaxyGenConfig.GalaxyType t : GalaxyGenConfig.defaults().galaxyTypes) {
            if (t.minRadiusLy < UniverseScale.MIN_AUTHORED_GALAXY_RADIUS_LY) {
                dwarfs.add(t);
                smallestRadiusLy = Math.min(smallestRadiusLy, t.minRadiusLy);
            }
        }
        ArrangementFailure.requireArranged("the shipped table must hold types too small to host authored "
                + "content, or there is no dwarf-only table to build", !dwarfs.isEmpty());
        GalaxyGenConfig dwarfOnly = new GalaxyGenConfig(GalaxyGenConfig.DEFAULT_MIN_SPACING,
                UniverseScale.DEFAULT_STAR_OCCUPANCY, GalaxyGenConfig.DEFAULT_GALAXY_SPACING,
                GalaxyGenConfig.DEFAULT_GALAXY_DENSITY, null, dwarfs);

        double shipped = new ClusteredGalaxyGenerator(new ReportOnce(), GalaxyGenConfig.defaults())
                .guaranteedAuthoredReachLy();
        double promised = new ClusteredGalaxyGenerator(new ReportOnce(), dwarfOnly)
                .guaranteedAuthoredReachLy();
        double held = (1d - UniverseScale.HOME_GALAXY_ORIGIN_FRACTION) * smallestRadiusLy;

        assertTrue("the shipped table, which holds galaxies large enough, must be promised more than a "
                + "dwarf holds (" + held + " ly): " + shipped, shipped > held);
        assertTrue("a table of dwarfs is promised " + promised + " ly while its smallest dwarf holds "
                + held + " ly", promised <= held);
    }
}
