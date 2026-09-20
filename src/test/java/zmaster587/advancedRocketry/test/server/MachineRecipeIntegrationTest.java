package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;


import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * machine + recipe integration: full end-to-end recipe run.
 *
 * <ol>
 *   <li>{@code /artest fixture machine cutting} builds the multiblock fixture.</li>
 *   <li>{@code /artest machine try-complete} &rarr; asserts {@code isComplete=true}.</li>
 *   <li>{@code /artest machine recipe-info TileCuttingMachine 0} returns the
 *       first registered recipe's first ingredient + first output.</li>
 *   <li>{@code /artest hatch fill <inputPos> 0 <ingredient.item> <count>} —
 *       inserts the ingredient into input hatch slot 0.</li>
 *   <li>{@code /artest energy inject <powerPos> 1000000} — fills power hatch.</li>
 *   <li>{@code /artest tile force-tick <controllerPos> 200} — drives recipe
 *       cycle. The recipe time is ≤ 100 ticks for default cutting recipes; 200
 *       gives generous headroom.</li>
 *   <li>{@code /artest hatch read <outputPos>} — asserts the expected output
 *       item appeared in the output hatch.</li>
 * </ol>
 *
 * <p>Keeps probe-wiring smoke (empty pos / non-multiblock tile rejection)
 * because regressions there would mask gameplay failures.</p>
 */
public class MachineRecipeIntegrationTest extends AbstractHeadlessServerTest {

    private static final String INPUT_POS = "inputPos";
    private static final String OUTPUT_POS = "outputPos";
    private static final String POWER_POS = "powerPos";

    @Test
    public void probeWiringStillHealthy() throws Exception {
        // tick-until on empty pos -> controlled error.
        String empty = String.join("\n",
                client().execute("artest machine tick-until 0 100 64 100 complete 5"));
        assertTrue("tick-until on empty pos didn't error: " + empty,
                "no tile entity".equals(Reply.of(empty).text("error")));

        client().execute("artest place 0 100 64 100 minecraft:chest");
        String chest = String.join("\n",
                client().execute("artest machine tick-until 0 100 64 100 complete 5"));
        // Read OF THE FIELD, both halves. This verb builds its message out of the reflection
        // failure's own text — `"tile lacks " + e.getMessage()` — so the method name is inside
        // the error and a substring of THAT is the reading. What it replaces was a pair of
        // needles over the whole rendering, which two DIFFERENT parts of the reply could satisfy
        // between them: the phrase in `error` and `isComplete` in some other field entirely.
        Reply rejection = Reply.of("artest machine tick-until", chest);
        assertTrue("tick-until didn't gracefully reject TileEntityChest: " + chest,
                rejection.refused() && rejection.error().startsWith("tile lacks ")
                        && rejection.error().contains("isComplete"));
    }

    @Test
    public void recipesSummaryReportsNonZeroCounts() throws Exception {
        String summary = String.join("\n", client().execute("artest machine recipes-summary"));
        assertTrue("recipes-summary errored: " + summary, !Reply.of(summary).has("error"));
        String[] requiredMachines = {
                "TileCuttingMachine", "TileElectricArcFurnace", "TileLathe",
                "TileRollingMachine", "TileChemicalReactor",
        };
        StringBuilder failures = new StringBuilder();
        for (String name : requiredMachines) {
            Reply counts = Reply.of("artest machine recipes-summary", summary);
            if (!counts.has(name)) { failures.append(name).append("=NOT_REPORTED;"); continue; }
            if (counts.integer(name) <= 0) failures.append(name).append("=0;");
        }
        assertTrue("machine recipe counts: " + failures + " full=" + summary,
                failures.length() == 0);
    }

    @Test
    public void cuttingMachineRunsFirstRegisteredRecipe() throws Exception {
        // 1. Build the cutting-machine multiblock.
        int cx = 400, cy = FixtureSite.OPEN_AIR_Y, cz = 400;
        String fixture = String.join("\n", client().execute(
                "artest fixture machine cutting 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture machine cutting failed: " + fixture,
                Reply.of(fixture).ok());

        int[] ipm = Reply.of(fixture).blockPos(INPUT_POS);
        int[] opm = Reply.of(fixture).blockPos(OUTPUT_POS);
        int[] ppm = Reply.of(fixture).blockPos(POWER_POS);
        assertTrue("fixture didn't return input/output/power positions: " + fixture,
                ipm != null && opm != null && ppm != null);
        String inPos = ipm[0] + " " + ipm[1] + " " + ipm[2];
        String outPos = opm[0] + " " + opm[1] + " " + opm[2];
        String pwrPos = ppm[0] + " " + ppm[1] + " " + ppm[2];

        // 2. Validate multiblock. Use the kit's retry helper — under
        //    parallel-fork pressure `attemptCompleteStructure` rarely loses
        //    the chunk-load + finalization race on the immediate first call
        //.
        String complete = MachineRecipeEndToEndKit.tryCompleteWithRetry(
                client(), 0, cx, cy, cz);
        assertTrue("multiblock not complete: " + complete,
                Reply.of(complete).bool("isComplete"));

        // 3. Resolve first recipe ingredient + expected output.
        String recipe = String.join("\n",
                client().execute("artest machine recipe-info TileCuttingMachine 0"));
        assertTrue("recipe-info errored: " + recipe, !Reply.of(recipe).has("error"));
        // The FIRST entry of each list, read as an object. The regex this replaces pinned the whole
        // prefix — `"ingredients":[{"slot":0,"item":…` — so it matched only while slot 0 came first
        // AND the three fields stayed in that order, and answered "no ingredient" otherwise.
        Reply info = Reply.of("artest machine recipe-info", recipe);
        String[] ingredients = info.objectArray("ingredients");
        String[] outputs = info.objectArray("outputs");
        assertTrue("recipe-info missing first ingredient: " + recipe, ingredients.length > 0);
        assertTrue("recipe-info missing first output: " + recipe, outputs.length > 0);
        Reply firstIngredient = Reply.of(ingredients[0]);
        String ingredientItem = firstIngredient.text("item");
        int ingredientCount = firstIngredient.integer("count");
        // Meta matters: oredict ingredients like `bouleSilicon` resolve to a
        // libVulpes meta-item (productboule) at the material-specific meta, not
        // meta 0. Filling without the meta inserts the wrong variant and the
        // recipe never matches.
        int ingredientMeta = firstIngredient.integer("meta");
        String expectedOutput = Reply.of(outputs[0]).text("item");

        // 4. Stuff input hatch.
        String hatchFill = String.join("\n", client().execute(
                "artest hatch fill 0 " + inPos + " 0 " + ingredientItem + " "
                        + ingredientCount + " " + ingredientMeta));
        assertTrue("hatch fill failed: " + hatchFill, Reply.of(hatchFill).ok());

        // 5. Charge power hatch.
        String inject = String.join("\n", client().execute(
                "artest energy inject 0 " + pwrPos + " 10000000"));
        assertTrue("power inject failed: " + inject, Reply.of(inject).ok());

        // 5b. Flip the machine's enable toggle. libVulpes machines default to
        // disabled until a player flips the GUI switch; tests have to toggle
        // it via reflection.
        String enable = String.join("\n", client().execute(
                "artest machine set-enabled 0 " + cx + " " + cy + " " + cz + " true"));
        assertTrue("machine set-enabled failed: " + enable,
                Reply.of(enable).ok() && Reply.of(enable).bool("enabled"));

        // 6. Drive ticks in batches and poll the output hatch each batch.
        //    Default cutting recipes take ~100 ticks; serial budget 300 was
        //    enough but parallel-3-fork pressure stretches effective tick
        //    rate (server thread shared across forks). Budget 12×100=1200
        //    ticks (4× the recipe length) absorbs the worst case observed
        //    in the 10× testServer rerun under load. Early-exit keeps the
        //    happy-path cost at ~1 batch.
        String out = "n/a";
        boolean found = false;
        for (int batch = 0; batch < 12; batch++) {
            String tick = String.join("\n", client().execute(
                    "artest tile force-tick 0 " + cx + " " + cy + " " + cz + " 100"));
            assertTrue("force-tick failed: " + tick, Reply.of(tick).ok());
            out = String.join("\n", client().execute("artest hatch read 0 " + outPos));
            assertTrue("hatch read errored: " + out, !Reply.of(out).has("error"));
            // A SEARCH across ticks: the recipe may not have completed yet, so the question is
            // existence and `element`'s refusal would end the retry loop on the first pass.
            // absence is the answer: the claim is whether the hatch holds that item AT ALL,
            // and a list with no such element is the "not yet" this loop waits out.
            if (Reply.of("artest hatch read", out)
                    .holdsElement("slots", "item", String.valueOf(expectedOutput))) {
                found = true;
                break;
            }
        }
        assertTrue("expected output " + expectedOutput
                        + " not in output hatch — recipe didn't complete: ingredient=" + ingredientItem
                        + " last response=" + out,
                found);
    }
}
