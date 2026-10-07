package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.EmptyGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.RegionScan;
import dev.stannismod.stellurgy.universe.ReportOnce;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A survey's horizon says whether it was measured against the sky being surveyed.
 *
 * <p>A unit test of {@link RegionScan.Tuning#fromConfig(dev.stannismod.stellurgy.universe.IGalaxyGenerator,
 * StellurgyConfiguration, ReportOnce)}, given a configuration and a report memory this test builds and
 * owns. The configuration's numbers do not enter the verdict, which is about WHICH star table the reach
 * was derived from, so its defaults are left as constructed.</p>
 */
public class ASurveyReachSaysWhoseSkyItWasMeasuredAgainstTest {

    /**
     * A sky that states no star table is surveyed against the stock one, and the tuning says so; a sky
     * that states its own is not marked.
     *
     * <p>Fails if {@code RegionScan.Tuning#fromConfig} stops telling its caller that the horizon was
     * derived against a substitute star table (it substituted the stock table silently, so a reach the
     * sky's own stars were never measured against read exactly like one that was).</p>
     * <p>red-witnessed: with {@code Tuning#fromConfig} at {@code !stated.isPresent(),} made {@code false}, fails: "a sky that states no star table must say its horizon was measured against the stock one" (2026-10-07).</p>
     */
    @Test
    public void aSkyWithNoStarTableIsSurveyedAgainstTheStockOneAndSaysSo() {
        StellurgyConfiguration config = new StellurgyConfiguration();

        RegionScan.Tuning described = RegionScan.Tuning.fromConfig(
                new ClusteredGalaxyGenerator(new ReportOnce(), GalaxyGenConfig.defaults()), config,
                new ReportOnce());
        RegionScan.Tuning assumed = RegionScan.Tuning.fromConfig(new EmptyGalaxyGenerator(), config,
                new ReportOnce());

        assertFalse("a sky that states its own star table must not be marked as measured against the "
                + "stock one", described.reachAssumesStockSky());
        assertTrue("a sky that states no star table must say its horizon was measured against the stock "
                + "one", assumed.reachAssumesStockSky());
    }
}
