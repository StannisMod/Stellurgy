package dev.stannismod.stellurgy.test.unit;

import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.PlanetarySystem;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;

/**
 * A companion star is drawn where its body stands.
 *
 * <p>A unit test of a stock {@link ClusteredGalaxyGenerator} — an instance this test builds and owns.
 * Every sky, chart and selector draws a companion at its star's {@code getBaseTheta()}; the body that
 * stands for it carries its angle in its frame's law. The two must be one angle.</p>
 *
 * <p><b>Where it looks.</b> A companion's angle can only be moved when its first-choice cell is taken,
 * which happens when two companions land in one cell — and that happens where a system's lattice cell
 * is small enough to clamp its companions' orbits onto its faces. The shipped galaxy's NUCLEUS divides
 * its lattice finest, so the sweep is taken around the home galaxy's centre. The arrangement asserts
 * only that it found companions to compare; that the sample holds a moved one is what this test's red
 * witness shows, not a check it makes.</p>
 */
public class ACompanionIsDrawnWhereItsBodyStandsTest {

    /** The test's own world seed; fixed so a red reproduces. */
    private static final long SEED = 0x5EED_0700L;

    /**
     * Half the edge of the swept box around the home galaxy's centre, in cells — the test's own reach,
     * sized to hold thousands of lattice cells of a nucleus; the arrangement check says whether it held
     * systems with companions.
     */
    private static final long SWEEP_HALF_EDGE_CELLS = 40_000L;

    /** Companions the sweep must compare before its verdict means anything — the test's own bar. */
    private static final int MIN_COMPANIONS = 50;

    /**
     * A body's orbit is measured against the configuration, whose class initializer touches vanilla's
     * block registry; without the vanilla bootstrap that throws and poisons the class for every later
     * test in this JVM.
     */
    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * Every companion's star carries the angle its body was seated at.
     *
     * <p>Fails if {@code ClusteredGalaxyGenerator#seatCompanions} stops handing a companion the angle
     * of the seat its body ends up in (the body was walked round its ring past a taken cell while the
     * star kept the drawn angle, so the star was drawn where its body was not).</p>
     * <p>red-witnessed: with {@code ClusteredGalaxyGenerator#fabricate} at {@code seatCompanions(seatIn(seed, lattice), lattice, star);} removed, fails: "companion PGS-16293795877.9065387003.-15012441288-C of 16293799595_9065390722_-15012437570 is drawn at one angle and its body stands at another expected:<3.4803475759663702> but was:<1.080384346237717 …" (2026-10-07).</p>
     */
    @Test
    public void everyCompanionStarCarriesTheAngleItsBodyStandsAt() {
        ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(new ReportOnce(), GalaxyGenConfig.defaults());
        GalacticCoord centre = g.galaxies().home(SEED).centre();
        Map<GalacticCoord, PlanetarySystem> systems = g.systemsInRegion(SEED,
                GalacticCoord.ofSectorLocal(centre.sectorX() - SWEEP_HALF_EDGE_CELLS,
                        centre.sectorY() - SWEEP_HALF_EDGE_CELLS, centre.sectorZ() - SWEEP_HALF_EDGE_CELLS,
                        0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(centre.sectorX() + SWEEP_HALF_EDGE_CELLS,
                        centre.sectorY() + SWEEP_HALF_EDGE_CELLS, centre.sectorZ() + SWEEP_HALF_EDGE_CELLS,
                        0L, 0L, 0L));

        int compared = 0;
        for (Map.Entry<GalacticCoord, PlanetarySystem> system : systems.entrySet()) {
            if (!system.getValue().star().isPresent()) {
                continue;
            }
            List<StellarBody> companions = system.getValue().star().get().getSubStars();
            if (companions.isEmpty()) {
                continue;
            }
            for (SystemBody body : g.bodiesFor(SEED, system.getKey())) {
                if (body.kind() != SystemBodyKind.STAR) {
                    continue;
                }
                for (StellarBody companion : companions) {
                    if (companion.getId() != body.starId()) {
                        continue;
                    }
                    assertEquals("companion " + companion.getName() + " of " + system.getKey().cellKey()
                                    + " is drawn at one angle and its body stands at another",
                            body.frame().law().baseTheta(), companion.getBaseTheta(), 0d);
                    compared++;
                }
            }
        }
        ArrangementFailure.requireArranged("the sweep must compare at least " + MIN_COMPANIONS
                + " companions, saw " + compared + " in " + systems.size() + " systems",
                compared >= MIN_COMPANIONS);
    }
}
