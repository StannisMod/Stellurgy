package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.MachineInfo;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Area Gravity Controller multiblock validation.
 *
 * <p>{@link zmaster587.advancedRocketry.tile.multiblock.TileAreaGravityController}
 * — smallest AR multiblock: 2×3×3 with only 6 non-null cells (controller +
 * 4 advStructure cross + 1 power-input plug below the controller).</p>
 *
 * <p>Position-isolated at x=5500.</p>
 */
public class AreaGravityControllerMultiblockTest extends AbstractSharedServerTest {

    private static final int CX = 5500;
    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 5500;

    @Test
    public void gravityControllerMultiblockValidatesWhenFixtureIsBuilt() throws Exception {
        String fixture = join(client().execute(
                "artest fixture multiblock gravity-controller 0 " + CX + " " + CY + " " + CZ));
        assertTrue("fixture multiblock gravity-controller failed: " + fixture,
                Reply.of(fixture).ok());

        String info = join(client().execute(
                "artest machine info 0 " + CX + " " + CY + " " + CZ));
        assertEquals("expected TileAreaGravityController tile at controller pos: " + info,
                "TileAreaGravityController", MachineInfo.of(info).tileSimpleName());

        String tryComplete = join(client().execute(
                "artest machine try-complete 0 " + CX + " " + CY + " " + CZ));
        assertTrue("try-complete probe errored: " + tryComplete,
                Reply.of(tryComplete).ok());
        assertTrue("gravity-controller multiblock didn't validate (isComplete=false): " + tryComplete,
                Reply.of(tryComplete).bool("isComplete"));
    }

    @Test
    public void gravityControllerMultiblockInvalidatesWhenPlugRemoved() throws Exception {
        int cx = CX + 30, cy = CY, cz = CZ;
        String fixture = join(client().execute(
                "artest fixture multiblock gravity-controller 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());

        String first = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("baseline must validate: " + first,
                Reply.of(first).bool("isComplete"));

        // Power-input plug directly under controller -> globalY = cy - 1, globalX = cx, globalZ = cz.
        String breakPlug = join(client().execute(
                "artest place 0 " + cx + " " + (cy - 1) + " " + cz + " minecraft:stone"));
        assertTrue("could not replace plug: " + breakPlug,
                Reply.of(breakPlug).ok());

        String broken = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("structure stayed complete after plug removal — "
                        + "validator broken: " + broken,
                (!Reply.of(broken).bool("isComplete")));
    }

    @Test
    public void gravityControllerMultiblockInvalidatesWhenAdvStructureRemoved() throws Exception {
        int cx = CX + 60, cy = CY, cz = CZ;
        String fixture = join(client().execute(
                "artest fixture multiblock gravity-controller 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());

        String first = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("baseline must validate: " + first,
                Reply.of(first).bool("isComplete"));

        // advStructure at (cx+1, cy-1, cz) — east arm of the cross.
        String breakArm = join(client().execute(
                "artest place 0 " + (cx + 1) + " " + (cy - 1) + " " + cz + " minecraft:stone"));
        assertTrue("could not break arm: " + breakArm,
                Reply.of(breakArm).ok());

        String broken = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("structure stayed complete after arm removal — "
                        + "validator broken: " + broken,
                (!Reply.of(broken).bool("isComplete")));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
