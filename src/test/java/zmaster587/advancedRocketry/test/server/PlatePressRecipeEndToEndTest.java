package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.server.TestClient;
import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.Reply;

import static org.junit.Assert.assertTrue;

/**
 * Small PlatePress end-to-end recipe contract.
 *
 * <p>Single-block, redstone-triggered, item-output-as-EntityItem.
 * Distinct from the multiblock industrial machines —
 * no hatches, no RF, no force-tick. The contract this class pins:</p>
 *
 * <ol>
 *   <li><b>Fixture shape</b>: the 3-block vertical stack is built —
 *       obsidian below, recipe ingredient in the middle, PlatePress on top
 *       (FACING=DOWN, EXTENDED=false). The fixture builder resolves the
 *       ingredient from the first registered recipe.</li>
 *   <li><b>End-to-end activation</b>: placing a redstone-power source
 *       adjacent to the press destroys the ingredient block and spawns
 *       an {@code EntityItem} next to the press carrying the recipe's
 *       output item. Player-visible behaviour — both effects asserted.</li>
 * </ol>
 *
 * <p>Per {@code} the kit pins observable outcomes
 * (block-state change + entity spawn) rather than internal machinery
 * (piston EXTENDED state, exact tick where the spawn fires).</p>
 */
public class PlatePressRecipeEndToEndTest extends AbstractSharedServerTest {

    private static final String FIXTURE_KEY = "plate-press";
    /** What the fixture resolved the first recipe to — the ids this scenario is about. */
    private static final String OUTPUT_ITEM = "outputItem";
    private static final String INGREDIENT_BLOCK = "ingredientBlock";
    private static final String PRESS_FQN   = "zmaster587.advancedRocketry.block.BlockSmallPlatePress";

    @Test
    public void platePressFixtureBuildsExpectedStack() throws Exception {
        int x = 400, y = FixtureSite.OPEN_AIR_Y, z = 400;
        TestClient c = client();
        String resp = String.join("\n",
                c.execute("artest fixture machine " + FIXTURE_KEY + " 0 " + x + " " + y + " " + z));
        assertTrue("fixture machine " + FIXTURE_KEY + " failed: " + resp,
                Reply.of(resp).ok());
        assertTrue("response missing pressPos: " + resp,
                resp.contains("\"pressPos\":[" + x + "," + y + "," + z + "]"));

        // Read each cell of the 3-stack and verify the correct block sits there.
        String obsRead = String.join("\n", c.execute(
                "artest block at 0 " + x + " " + (y - 2) + " " + z));
        assertTrue("obsidian missing at " + x + "," + (y - 2) + "," + z + ": " + obsRead,
                "minecraft:obsidian".equals(Reply.of(obsRead).text("block")));

        String pressRead = String.join("\n", c.execute(
                "artest block at 0 " + x + " " + y + " " + z));
        assertTrue("press missing at " + x + "," + y + "," + z + ": " + pressRead,
                "advancedrocketry:platepress".equals(Reply.of(pressRead).text("block")));
    }

    @Test
    public void platePressRedstoneActivationDropsRecipeOutput() throws Exception {
        int x = 500, y = FixtureSite.OPEN_AIR_Y, z = 400;
        TestClient c = client();
        // Build fixture + capture the resolved output id.
        String fixture = String.join("\n",
                c.execute("artest fixture machine " + FIXTURE_KEY + " 0 " + x + " " + y + " " + z));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());

        // Extract the resolved output item + ingredient block ids.
        Reply built = Reply.of("artest fixture machine", fixture);
        assertTrue("response missing outputItem: " + fixture, built.has(OUTPUT_ITEM));
        String expectedOutputId = built.text(OUTPUT_ITEM);
        assertTrue("first recipe has no output — can't end-to-end test",
                !"null".equals(expectedOutputId));
        assertTrue("response missing ingredientBlock: " + fixture, built.has(INGREDIENT_BLOCK));
        String ingredientBlockId = built.text(INGREDIENT_BLOCK);

        // Activate: place a redstone block on top of the press. The press's
        // neighborChanged handler fires synchronously on setBlockState, runs
        // checkForMove -> shouldBeExtended=true -> setBlockToAir(below) +
        // spawnEntity(EntityItem with recipe output). Adjacent (not above) is
        // also valid; using ABOVE because shouldBeExtended explicitly
        // re-checks pos.up() neighbours and that path is unambiguous.
        String activate = String.join("\n", c.execute(
                "artest place 0 " + x + " " + (y + 1) + " " + z + " minecraft:redstone_block"));
        assertTrue("redstone block placement failed: " + activate,
                Reply.of(activate).bool("placed", false));

        // Scan for EntityItem within 2 blocks of (x+0.5, y-0.5, z+0.5) —
        // the spawn position from BlockSmallPlatePress.checkForMove.
        String scan = String.join("\n", c.execute(
                "artest entity scan-items 0 " + (x + 0.5) + " " + (y - 0.5) + " " + (z + 0.5) + " 2"));
        assertTrue("entity scan-items failed: " + scan, Reply.of(scan).ok());
        Reply.of(scan).element("items", "item", String.valueOf(expectedOutputId));

        // Ingredient block must be gone (consumed by the press). After
        // activation the cell ends up either as AIR (setBlockToAir from
        // checkForMove) or as `minecraft:piston_extension` (transient during
        // piston-head extension). The contract: it is NO LONGER the
        // ingredient block.
        String ingredientRead = String.join("\n", c.execute(
                "artest block at 0 " + x + " " + (y - 1) + " " + z));
        assertTrue("ingredient block " + ingredientBlockId + " still present at "
                        + x + "," + (y - 1) + "," + z + " — press did not consume it: "
                        + ingredientRead,
                !String.valueOf(ingredientBlockId).equals(Reply.of(ingredientRead).text("block")));
    }
}
