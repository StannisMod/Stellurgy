package dev.stannismod.stellurgy.test.unit;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.network.PacketSystemBodiesSync.RenderBody;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.SpaceManager;
import dev.stannismod.stellurgy.space.SystemBodiesProducer;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.PlanetarySystem;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * The sky feed says whose moon a moon is.
 *
 * <p>A unit test of {@link SystemBodiesProducer#buildByDim}, fed the bodies a stock
 * {@link ClusteredGalaxyGenerator} derives — an instance this test builds and owns.</p>
 */
public class MoonParentInTheSkyFeedTest {

    /** The test's own world seed; any seed derives moons, and this one is fixed so a red reproduces. */
    private static final long SEED = 0x5EED_0693L;

    /**
     * The slot dimension the one live cell is bound to. Any id but {@link SpaceManager#UNBOUND_SLOT}
     * names a world whose sky this is; the number itself carries nothing.
     */
    private static final int SLOT_DIM = SpaceManager.UNBOUND_SLOT + 1;

    /** Moons the sweep must link before its verdicts mean anything — the test's own sample bar. */
    private static final int MIN_MOONS = 20;

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
     * Every moon in the feed points at the body it orbits.
     *
     * <p>Fails if {@code SystemBodiesProducer#linkMoonsToTheirParents} stops deciding that a moon's
     * parent is the body whose cell its ZONE names (it matched the moon's own cell, which a zoned moon
     * never shares, and sent every such moon with {@code NO_PARENT}).</p>
     *
     * <p>The parent is checked against an identity the producer does not consult: a moon's frame is
     * nested in its parent's ({@code CellFrame#within}), so the body at {@code parentIndex} must be the
     * one whose frame the moon's frame is nested in. The sample must hold moons with a zone — the case
     * the defect lived in — or it says nothing about it.</p>
     * <p>red-witnessed: with {@code SystemBodiesProducer#parentCellKeyOf} at {@code return moonName.zone() != null ? moonName.zone() : moonName.cellKey();} made to answer the moon's own cell, fails: "the moon at -6133342_3859345_-6187298.1_0_0 in system -6133342_3859345_-6187298 was sent with no parent. Actual: -1" (2026-10-07).</p>
     */
    @Test
    public void everyMoonInTheFeedPointsAtTheBodyItOrbits() {
        ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(new ReportOnce(), GalaxyGenConfig.defaults());
        long reach = 2L * g.minSpacingCells();
        Map<GalacticCoord, PlanetarySystem> systems = g.systemsInRegion(SEED,
                GalacticCoord.ofSectorLocal(-reach, -reach, -reach, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(reach, reach, reach, 0L, 0L, 0L));

        int moons = 0;
        int zonedMoons = 0;
        for (GalacticCoord anchor : systems.keySet()) {
            List<SystemBody> bodies = g.bodiesFor(SEED, anchor);
            List<RenderBody> feed = SystemBodiesProducer.buildByDim(
                    Collections.singletonMap(anchor, SLOT_DIM), null, cell -> bodies).get(SLOT_DIM);
            assertEquals("the feed must carry every body of the system, in order", bodies.size(),
                    feed.size());
            for (int i = 0; i < bodies.size(); i++) {
                SystemBody moon = bodies.get(i);
                if (moon.kind() != SystemBodyKind.MOON) {
                    continue;
                }
                moons++;
                if (moon.name().zone() != null) {
                    zonedMoons++;
                }
                int parent = feed.get(i).parentIndex;
                assertNotEquals("the moon at " + moon.name().cellKey() + " in system " + anchor.cellKey()
                        + " was sent with no parent", RenderBody.NO_PARENT, parent);
                assertEquals("the moon at " + moon.name().cellKey() + " must point at the body its frame "
                        + "is nested in, not at " + bodies.get(parent).name().cellKey(),
                        moon.frame().parent(), bodies.get(parent).frame());
            }
        }
        ArrangementFailure.requireArranged("the sweep must derive at least " + MIN_MOONS + " moons, saw "
                + moons + " in " + systems.size() + " systems", moons >= MIN_MOONS);
        ArrangementFailure.requireArranged("the sweep must hold moons named in a zone, or it never reached "
                + "the case the parent link was broken for", zonedMoons > 0);
    }
}
