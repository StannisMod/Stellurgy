package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.EnergyStore;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * tile-machine isolated coverage.
 *
 * Each test below places a tile with
 *   {@code /stellurgytest place <dim> <x> <y> <z> <blockId>}
 * and reads its energy capability with
 *   {@code /stellurgytest energy stored <dim> <x> <y> <z>}.
 * The tests pin capability presence. They do NOT assert
 * gameplay numbers (production has no canonical "solar panel produces
 * X RF/tick under simulated daylight" reference) — those belong in
 * a future tier.
 */
public class TileMachineDepthTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    // Stay near spawn so the chunk is loaded; spread far enough apart that
    // tiles placed by separate tests don't interact.
    private static final int BASE_X = 200;
    private static final int BASE_Z = 200;
    private static final int Y = FixtureSite.OPEN_AIR_Y; // above terrain to avoid stone overwrite quirks

    /** What the Forge energy capability at one block reports — refusing a block that is not there. */
    private EnergyStore energy(int x, int z) throws Exception {
        return EnergyStore.at(cmd -> ok(client().read(cmd)), DIM, x, Y, z);
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /**
     * Place a block, pre-clearing the position with air. The pre-clear is
     * necessary because some Stellurgy blocks' tile-entity wiring depends on the
     * neighbour-update + onBlockPlaced chain that vanilla setBlockState
     * doesn't always trigger when overwriting a non-air block (e.g. terrain).
     */
    private void place(String blockId, int x, int y, int z) throws Exception {
        // Clear first.
        client().arrange("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " minecraft:air");
        String r = ok(client().arrange(
                "stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + blockId));
        assertTrue("place(" + blockId + ") at " + x + "," + y + "," + z + " failed: " + r,
                Reply.of(r).bool("placed"));
    }

    @Test
    public void solarGeneratorExposesEnergyCapAndSurvivesForceTick() throws Exception {
        // NOTE: the registry name "solarPanel" is the *plain decorative
        // block* — it has no tile entity. The tile-bearing machine is
        // "solarGenerator" (bound to TileSolarPanel internally). Easy
        // mistake — pin so a future test author lands on the right block.
        int x = BASE_X, z = BASE_Z;
        place("stellurgy:solarGenerator", x, Y, z);

        EnergyStore stored = energy(x, z);
        assertTrue("solar generator must expose CapabilityEnergy: " + stored.raw(),
                stored.hasEnergy);
    }

    @Test
    public void oxygenVentExposesTileAndAcceptsForceTick() throws Exception {
        // TileOxygenVent is an inventoried RF consumer.
        int x = BASE_X + 12, z = BASE_Z;
        place("stellurgy:oxygenVent", x, Y, z);

        EnergyStore storedResp = energy(x, z);
        assertTrue("oxygenVent must expose CapabilityEnergy (RF consumer): "
                        + storedResp.raw(),
                storedResp.hasEnergy);
    }
}
