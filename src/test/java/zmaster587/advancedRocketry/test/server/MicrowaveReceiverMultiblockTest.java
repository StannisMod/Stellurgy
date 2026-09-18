package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Microwave Receiver multiblock validation.
 *
 * <p>{@link zmaster587.advancedRocketry.tile.multiblock.energy.TileMicrowaveReciever}
 * — single layer 5×5 with {@code blockSolarPanel} ring (and wildcards that
 * accept the same panel) around the controller centre.</p>
 *
 * <p>Promotes the existing smoke-level Microwave coverage in
 * {@code SpecialInfrastructureSmokeTest} to behavioural-depth.</p>
 *
 * <p>Position-isolated at x=7000.</p>
 */
public class MicrowaveReceiverMultiblockTest extends AbstractSharedServerTest {

    private static final int CX = 7000;
    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 7000;

    @Test
    public void microwaveReceiverMultiblockValidatesWhenFixtureIsBuilt() throws Exception {
        String fixture = join(client().execute(
                "artest fixture multiblock microwave-receiver 0 " + CX + " " + CY + " " + CZ));
        assertTrue("fixture multiblock microwave-receiver failed: " + fixture,
                Reply.of(fixture).ok());

        String info = join(client().execute(
                "artest machine info 0 " + CX + " " + CY + " " + CZ));
        assertTrue("expected TileMicrowaveReciever tile at controller pos: " + info,
                info.contains("TileMicrowaveReciever"));

        String tryComplete = join(client().execute(
                "artest machine try-complete 0 " + CX + " " + CY + " " + CZ));
        assertTrue("try-complete probe errored: " + tryComplete,
                Reply.of(tryComplete).ok());
        assertTrue("microwave-receiver multiblock didn't validate (isComplete=false): " + tryComplete,
                Reply.of(tryComplete).bool("isComplete", false));
    }

    @Test
    public void microwaveReceiverMultiblockInvalidatesWhenCornerPanelRemoved() throws Exception {
        int cx = CX + 30, cy = CY, cz = CZ;
        String fixture = join(client().execute(
                "artest fixture multiblock microwave-receiver 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());

        // Break BEFORE first try-complete (no-baseline pattern — solar panels
        // are TE-aware in hidden-multiblock state).
        // NW corner — globalY = cy, globalX = cx + 2, globalZ = cz - 2.
        String breakCorner = join(client().execute(
                "artest place 0 " + (cx + 2) + " " + cy + " " + (cz - 2) + " minecraft:stone"));
        assertTrue("could not replace corner: " + breakCorner,
                Reply.of(breakCorner).ok());

        String broken = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("structure validated despite missing corner panel: " + broken,
                (!Reply.of(broken).bool("isComplete", true)));
    }

    @Test
    public void microwaveReceiverMultiblockInvalidatesWhenAdjacentPanelRemoved() throws Exception {
        int cx = CX + 60, cy = CY, cz = CZ;
        String fixture = join(client().execute(
                "artest fixture multiblock microwave-receiver 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());

        // Cell immediately east of controller — globalY = cy, globalX = cx + 1, globalZ = cz.
        String breakAdj = join(client().execute(
                "artest place 0 " + (cx + 1) + " " + cy + " " + cz + " minecraft:stone"));
        assertTrue("could not replace adjacent panel: " + breakAdj,
                Reply.of(breakAdj).ok());

        String broken = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("structure validated despite missing adjacent panel: " + broken,
                (!Reply.of(broken).bool("isComplete", true)));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
