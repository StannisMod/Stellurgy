package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import zmaster587.advancedRocketry.test.EnergyStore;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Orbital Laser Drill multiblock validation.
 *
 * <p>{@link zmaster587.advancedRocketry.tile.multiblock.orbitallaserdrill.TileOrbitalLaserDrill}
 * — a 3-layer 11×9 sparse structure mixing {@code blockAdvStructureBlock},
 * {@code blockStructureBlock}, {@code blockVacuumLaser}, {@code blockLens},
 * the controller and {@code 'O'} item-output / {@code 'P'} power-input
 * hatches.</p>
 *
 * <p>Built through the reflection-backed generic fixture probe
 * {@code /artest fixture multiblock orbital-laser-drill}.</p>
 *
 * <p>Position-isolated at x=8500.</p>
 */
public class OrbitalLaserDrillMultiblockTest extends AbstractSharedServerTest {

    private static final int CX = 8500;
    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 8500;

    @Test
    public void orbitalLaserDrillMultiblockValidatesWhenFixtureIsBuilt() throws Exception {
        String fixture = join(client().execute(
                "artest fixture multiblock orbital-laser-drill 0 " + CX + " " + CY + " " + CZ));
        assertTrue("fixture multiblock orbital-laser-drill failed: " + fixture,
                fixture.contains("\"ok\":true"));
        assertTrue("fixture didn't place any blocks: " + fixture,
                fixture.contains("\"placed\":") && !fixture.contains("\"placed\":0"));

        String info = join(client().execute(
                "artest machine info 0 " + CX + " " + CY + " " + CZ));
        assertTrue("expected TileOrbitalLaserDrill tile at controller pos: " + info,
                info.contains("TileOrbitalLaserDrill"));

        String tryComplete = join(client().execute(
                "artest machine try-complete 0 " + CX + " " + CY + " " + CZ));
        assertTrue("try-complete probe errored: " + tryComplete,
                tryComplete.contains("\"ok\":true"));
        assertTrue("orbital-laser-drill multiblock didn't validate (isComplete=false): " + tryComplete,
                tryComplete.contains("\"isComplete\":true"));
    }

    @Test
    public void orbitalLaserDrillExposesEnergyCapAndTicksSafely() throws Exception {
        // Behavioural depth: after assembly, the multiblock must (a) expose
        // Forge's IEnergyStorage capability on one of its 'P' power-input
        // plugs (energy injection goes through the plug, not the controller),
        // and (b) survive several ITickable ticks without throwing. The full
        // energy-in -> output-produced cycle requires a configured drill
        // target and chunk-survey scaffolding — out of scope for this test.
        // The capability + tick path is the necessary precondition for any
        // future end-to-end laser-drill behavioural test.
        int cx = CX + 80, cy = CY, cz = CZ;
        String fixture = join(client().execute(
                "artest fixture multiblock orbital-laser-drill 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));

        String tryComplete = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("baseline must validate: " + tryComplete,
                tryComplete.contains("\"isComplete\":true"));

        // (a) Energy flows through a 'P' power-input plug. structure[1][2][10]
        // -> for NORTH-facing controller (offset x=1, y=2, z=2) global
        // (cx-9, cy+1, cz). The plug exposes IEnergyStorage; once assembled
        // and bound to controller batteries, its `getMaxEnergyStored` reports
        // the controller's pooled max (134_217_727 RF by default).
        int plugX = cx - 9, plugY = cy + 1, plugZ = cz;
        EnergyStore storedAtPlug = energy(plugX, plugY, plugZ)
                .requireEnergy("plug must expose Forge energy capability");
        long capMax = storedAtPlug.capacity();
        assertTrue("plug must report a non-trivial max storage (got " + capMax + "): "
                + storedAtPlug.raw(), capMax > 0);

        // (b) Force-tick the controller 20x — must not throw. Production
        // update() pulls drill state, checks completeStructure, batteries,
        // mode, target — many code paths exercised.
        String tick = join(client().execute(
                "artest tile force-tick 0 " + cx + " " + cy + " " + cz + " 20"));
        assertTrue("force-tick must not error: " + tick,
                tick.contains("\"ok\":true"));
        assertTrue("force-tick must report 20 ticks completed: " + tick,
                tick.contains("\"ticked\":20"));

        // (c) Plug's energy capability still exposed after 20 ticks (no
        // capability loss from idle ticking).
        EnergyStore storedAfter = energy(plugX, plugY, plugZ);
        assertTrue("plug capability must persist after ticking: " + storedAfter.raw(),
                storedAfter.hasEnergy);
    }

    @Test
    public void orbitalLaserDrillMultiblockInvalidatesWhenLensCellRemoved() throws Exception {
        int cx = CX + 40, cy = CY, cz = CZ;
        String fixture = join(client().execute(
                "artest fixture multiblock orbital-laser-drill 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));

        // No baseline try-complete — break BEFORE validation. A lens cell on
        // the controller layer at structure[2][4][4] (NORTH-facing globalX =
        // cx + 5, globalY = cy, globalZ = cz + 2 relative to controller).
        // Exact offset coordinates depend on the controller offset 'c' at
        // structure[2][2][1]; the lens cell at structure[2][4][4] resolves to
        // global (cx - 3, cy, cz + 2).
        String breakLens = join(client().execute(
                "artest place 0 " + (cx - 3) + " " + cy + " " + (cz + 2) + " minecraft:stone"));
        assertTrue("could not replace lens cell: " + breakLens,
                breakLens.contains("\"ok\":true"));

        String broken = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("orbital-laser-drill validated despite missing lens cell: " + broken,
                broken.contains("\"isComplete\":false"));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** What the Forge energy capability at one block reports — refusing a block that is not there. */
    private EnergyStore energy(int x, int y, int z) throws Exception {
        return EnergyStore.at(cmd -> join(client().execute(cmd)), 0, x, y, z);
    }
}
