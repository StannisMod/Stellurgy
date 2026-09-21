package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.server.TestClient;
import zmaster587.advancedRocketry.test.GameTicks;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * shared protocol for industrial-machine recipe end-to-end tests.
 *
 * <p>All 9 AR multiblock industrial machines share the broad recipe
 * pipeline shape, but specific machines vary on:</p>
 *
 * <ul>
 *   <li><b>Item ingredients</b> — some recipes have item inputs (RollingMachine,
 *       Lathe, Crystallizer, PrecisionLaserEtcher), some are fluid-only
 *       (ChemicalReactor rocketfuel = oxygen + hydrogen &rarr; rocketfuel).</li>
 *   <li><b>Fluid ingredients</b> — many require fluid in the liquid input
 *       hatch (RollingMachine pressuretank needs 100mB water).</li>
 *   <li><b>Item outputs vs fluid outputs</b> — most produce items, some
 *       produce fluids (ChemicalReactor rocketfuel).</li>
 *   <li><b>Hatch presence</b> — fluid-only machines (Electrolyser, Centrifuge,
 *       ChemicalReactor) have no 'I' / 'O' chars in their structure — only
 *       'L' / 'l' / 'P'. Tests must handle missing item-hatch positions.</li>
 * </ul>
 *
 * <p>Recipe selection: always the first registered recipe — discovered
 * via {@code RecipesMachine.getInstance().getRecipes(MachineClass)}.
 * No hardcoded item/fluid identities anywhere; tests stay valid as long
 * as the machine has at least one recipe and the recipe-info probe
 * reports it.</p>
 *
 * <p>Out of scope: wildcard-based machines (ArcFurnace, PrecisionAssembler)
 * place hatches via {@code '*'} wildcards rather than explicit
 * 'I' / 'O' / 'P' chars. The kit's generic fixture handler can't compute
 * hatch positions for them.</p>
 */
final class MachineRecipeEndToEndKit {

    /**
     * World between retries of a multiblock completion - the old 500 ms. The retry exists because
     * the machine's own tick is what completes it, so the gap between asks is measured in those.
     */
    private static final int TICKS_BETWEEN_ATTEMPTS = 10;



    private MachineRecipeEndToEndKit() {}

    // ---- Position discovery -------------------------------------------------

    /** Positions reported by the fixture probe. Each list contains all
     *  positions of the corresponding hatch char (some machines have
     *  multiples — ChemicalReactor has two 'L' liquid inputs). Empty
     *  list = no hatch of that type in the machine's structure. */
    static final class FixturePositions {
        final List<String> inputPositions;        // 'I'
        final List<String> outputPositions;       // 'O'
        final List<String> powerPositions;        // 'P'  (required: non-empty)
        final List<String> liquidInputPositions;  // 'L'
        final List<String> liquidOutputPositions; // 'l'
        final String fullResp;
        FixturePositions(List<String> in, List<String> out, List<String> pwr,
                         List<String> lin, List<String> lout, String resp) {
            this.inputPositions = in; this.outputPositions = out;
            this.powerPositions = pwr; this.liquidInputPositions = lin;
            this.liquidOutputPositions = lout; this.fullResp = resp;
        }
        String firstInput()       { return inputPositions.isEmpty()        ? null : inputPositions.get(0); }
        String firstOutput()      { return outputPositions.isEmpty()       ? null : outputPositions.get(0); }
        String firstPower()       { return powerPositions.get(0); }
        String firstLiquidInput() { return liquidInputPositions.isEmpty()  ? null : liquidInputPositions.get(0); }
        String firstLiquidOutput(){ return liquidOutputPositions.isEmpty() ? null : liquidOutputPositions.get(0); }
    }

    static FixturePositions placeFixture(TestClient c, String fixtureKey,
                                         int cx, int cy, int cz) throws Exception {
        String resp = String.join("\n", c.execute(
                "artest fixture machine " + fixtureKey + " 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture machine " + fixtureKey + " failed: " + resp,
                Reply.of(resp).ok());
        List<String> in   = matchAllPos(resp, "inputPositions");
        List<String> out  = matchAllPos(resp, "outputPositions");
        List<String> pwr  = matchAllPos(resp, "powerPositions");
        List<String> lin  = matchAllPos(resp, "liquidInputPositions");
        List<String> lout = matchAllPos(resp, "liquidOutputPositions");
        assertTrue("fixture machine " + fixtureKey
                        + " did not report any powerPositions (required): " + resp,
                !pwr.isEmpty());
        return new FixturePositions(in, out, pwr, lin, lout, resp);
    }

    /** Extract a list of "x y z" strings from a JSON field like
     *  {@code "<key>":[[x,y,z],[x,y,z]]}. */
    private static List<String> matchAllPos(String resp, String key) {
        List<String> all = new ArrayList<>();
        // absence is the answer, and it is the PRODUCER's doing: the fixture reply writes a hatch
        // list only for a kind of hatch the machine actually has — `appendHatchPositions` returns
        // before writing anything for an empty one — so a machine with no liquid hatches carries no
        // `liquidInputPositions` key at all. Measured over a green server tier, this is the only
        // site in it where an array field is genuinely absent: 24 times, across the four optional
        // kinds. The one list that must always be there is `powerPositions`, and `placeFixture`
        // asserts on it directly rather than leaving that claim to this reader.
        for (int[] at : Reply.of("a machine probe reply", resp).blockPosArrayOrEmpty(key)) {
            all.add(at[0] + " " + at[1] + " " + at[2]);
        }
        return all;
    }

    /**
     * Drives {@code /artest machine try-complete} with a retry
     * shim. Returns the response from the last attempt that produced
     * {@code attempted:true}, or the response from the final retry on
     * timeout. Callers must assert their own {@code isComplete} expectation
     * — this helper only guarantees that the validator actually ran.
     *
     * <p>The race: {@code attemptCompleteStructure} occasionally returns
     * {@code false} on the immediate first call after the fixture is built
     * (chunk-load + finalization race). Re-invoking it across the natural
     * tick gap between two probe round-trips lets the finalization settle.
     * Budget: 8 attempts × 500 ms gap (~4 s ceiling on the non-happy path;
     * ~0 ms cost when the first call succeeds — which is the common case).
     * Earlier 5×200ms budget proved insufficient under parallel-3-fork
     * pressure on multiple multiblocks
     * (ArcFurnace, PrecisionLaserEtcher, Beacon).</p>
     */
    static String tryCompleteWithRetry(TestClient c, int dim, int cx, int cy, int cz) throws Exception {
        String resp = null;
        // STAYS A LOOP, and the reason is that its ITERATIONS are the stimulus: each one re-issues
        // `try-complete`, which is production being ASKED to validate the multiblock. A link would
        // have to be a record of the validation succeeding, and the thing that makes it succeed is
        // the next ask — so waiting longer on one ask cannot produce what re-asking does. What this
        // cannot see: which of the eight asks was the one that took.
        for (int attempt = 0; attempt < 8; attempt++) {
            resp = String.join("\n",
                    c.execute("artest machine try-complete " + dim + " " + cx + " " + cy + " " + cz));
            // absence is the answer: this is the wait, and "the flag is not there yet" is
            // the state it exists to sit through.
            if (Reply.of(resp).boolOr("attempted", false)) return resp;
            GameTicks.advance(c, GameTicks.server(), TICKS_BETWEEN_ATTEMPTS);
        }
        return resp;
    }

    static void assertFixtureValidates(TestClient c, int cx, int cy, int cz,
                                       String tag, String fixtureResp) throws Exception {
        // Retry mitigation — see tryCompleteWithRetry above.
        StringBuilder attempts = new StringBuilder();
        String resp = null;
        // Same shape as tryCompleteWithRetry: the ask IS the stimulus, so this is a loop on
        // purpose. It differs in keeping every reply, because a red here wants to show which asks
        // were refused and how — the count alone would not say whether the answer ever changed.
        for (int attempt = 0; attempt < 8; attempt++) {
            resp = String.join("\n",
                    c.execute("artest machine try-complete 0 " + cx + " " + cy + " " + cz));
            // absence is the answer: this is the wait, and "the flag is not there yet" is
            // the state it exists to sit through.
            if (Reply.of(resp).boolOr("isComplete", false)) return;
            attempts.append("\n  attempt ").append(attempt + 1).append(": ").append(resp);
            GameTicks.advance(c, GameTicks.server(), TICKS_BETWEEN_ATTEMPTS);
        }
        throw new AssertionError(tag + " — multiblock not complete after 8 attempts"
                + attempts + "\n  fixture: " + fixtureResp);
    }

    // ---- Recipe discovery --------------------------------------------------

    /** Full first-recipe info from the recipe-info probe. */
    static final class FirstRecipe {
        final List<String[]> itemIngredients;  // {slot, item, count, meta}
        final List<String[]> itemOutputs;      // {slot, item}
        final List<String[]> fluidIngredients; // {fluid, amount}
        final List<String[]> fluidOutputs;     // {fluid, amount}
        final int time;                        // recipe.getTime() — ticks needed
        final String raw;
        FirstRecipe(List<String[]> ii, List<String[]> io,
                    List<String[]> fi, List<String[]> fo, int time, String raw) {
            this.itemIngredients = ii; this.itemOutputs = io;
            this.fluidIngredients = fi; this.fluidOutputs = fo;
            this.time = time; this.raw = raw;
        }
    }

    private static final String TIME_FIELD = "time";

    static FirstRecipe resolveFirstRecipe(TestClient c, String tileShortName) throws Exception {
        String resp = String.join("\n",
                c.execute("artest machine recipe-info " + tileShortName + " 0"));
        assertTrue("recipe-info errored for " + tileShortName + ": " + resp,
                !Reply.of(resp).has("error"));
        int time = 0;
        Reply tmReply = Reply.of(resp);
        if (tmReply.has(TIME_FIELD)) time = Integer.parseInt(tmReply.text(TIME_FIELD));
        return new FirstRecipe(
                parseSection(resp, "ingredients", "slot", "item", "count", "meta"),
                parseSection(resp, "outputs", "slot", "item"),
                parseSection(resp, "fluidIngredients", "fluid", "amount"),
                parseSection(resp, "fluidOutputs", "fluid", "amount"),
                time, resp);
    }

    /**
     * One array of the recipe reply, projected onto {@code fields} in order.
     *
     * <p>It used to find the key's text, cut the substring up to the next {@code ]}, and run a regex
     * with one capture group per field over it — so a recipe whose item id contained a bracket, or a
     * producer that reordered two fields, silently yielded an EMPTY recipe and every assertion below
     * then described a machine that had been fed nothing.</p>
     */
    private static List<String[]> parseSection(String resp, String field, String... fields) {
        List<String[]> out = new ArrayList<>();
        for (String element : Reply.of("artest machine recipe-info", resp).objectArray(field)) {
            Reply one = Reply.of(element);
            String[] values = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                // absence is the answer: the caller asks for a SET of fields over a
                // heterogeneous list, and an element that carries none of one of them is a
                // fact about that element rather than about the reply.
                values[i] = one.textOr(fields[i], null);
            }
            out.add(values);
        }
        return out;
    }

    // ---- Sub-test #1: fixture validates -----------------------------------

    static void runFixtureValidates(TestClient c, String fixtureKey,
                                    int cx, int cy, int cz) throws Exception {
        FixturePositions p = placeFixture(c, fixtureKey, cx, cy, cz);
        assertFixtureValidates(c, cx, cy, cz, fixtureKey, p.fullResp);
    }

    // ---- Sub-test #2: machine runs first recipe end-to-end -----------------

    /**
     * same as {@link #runFirstRecipeEndToEnd} except output
     * identity is NOT asserted. Returns the final output-hatch read so the
     * caller can apply a permissive assertion (e.g. "any item present").
     * Use for machines whose recipe set shares input keys and whose
     * runtime recipe-selection order differs from
     * {@code recipe-info 0} (Centrifuge).
     */
    static String runFirstRecipeEndToEndPermissive(TestClient c, String fixtureKey,
                                                   String tileShortName,
                                                   int cx, int cy, int cz) throws Exception {
        FixturePositions p = placeFixture(c, fixtureKey, cx, cy, cz);
        assertFixtureValidates(c, cx, cy, cz, fixtureKey, p.fullResp);
        FirstRecipe r = resolveFirstRecipe(c, tileShortName);
        fillItemIngredients(c, fixtureKey, p, r.itemIngredients);
        fillFluidIngredients(c, fixtureKey, p, r.fluidIngredients);
        String inject = String.join("\n", c.execute(
                "artest energy inject 0 " + p.firstPower() + " 10000000"));
        assertTrue("power inject failed: " + inject, Reply.of(inject).ok());
        String enable = String.join("\n", c.execute(
                "artest machine set-enabled 0 " + cx + " " + cy + " " + cz + " true"));
        assertTrue("machine set-enabled failed: " + enable,
                Reply.of(enable).ok() && Reply.of(enable).bool("enabled"));
        int tickBudget = Math.max(2000, r.time + 1000);
        String tick = String.join("\n", c.execute(
                "artest tile force-tick 0 " + cx + " " + cy + " " + cz + " " + tickBudget));
        assertTrue("force-tick failed: " + tick, Reply.of(tick).ok());
        return String.join("\n", c.execute("artest hatch read 0 " + p.firstOutput()));
    }

    static void runFirstRecipeEndToEnd(TestClient c, String fixtureKey,
                                       String tileShortName,
                                       int cx, int cy, int cz) throws Exception {
        FixturePositions p = placeFixture(c, fixtureKey, cx, cy, cz);
        assertFixtureValidates(c, cx, cy, cz, fixtureKey, p.fullResp);
        FirstRecipe r = resolveFirstRecipe(c, tileShortName);
        assertTrue("recipe-info has no outputs (item or fluid) for "
                        + tileShortName + " — can't end-to-end test: " + r.raw,
                !r.itemOutputs.isEmpty() || !r.fluidOutputs.isEmpty());

        fillItemIngredients(c, fixtureKey, p, r.itemIngredients);
        fillFluidIngredients(c, fixtureKey, p, r.fluidIngredients);

        String inject = String.join("\n", c.execute(
                "artest energy inject 0 " + p.firstPower() + " 10000000"));
        assertTrue("power inject failed for " + fixtureKey + ": " + inject,
                Reply.of(inject).ok());

        String enable = String.join("\n", c.execute(
                "artest machine set-enabled 0 " + cx + " " + cy + " " + cz + " true"));
        assertTrue("machine set-enabled failed for " + fixtureKey + ": " + enable,
                Reply.of(enable).ok() && Reply.of(enable).bool("enabled"));

        // Force-tick budget adapts to the recipe's declared completion time.
        // Most AR machine recipes are <500 ticks; the wildcard-structure
        // machines push higher (ArcFurnace=6000, PrecisionAssembler=4000).
        // Floor of 2000 keeps the 7 machines on their original budget;
        // ceiling extends to `time + 1000` for the long ones.
        int tickBudget = Math.max(2000, r.time + 1000);
        String tick = String.join("\n", c.execute(
                "artest tile force-tick 0 " + cx + " " + cy + " " + cz + " " + tickBudget));
        assertTrue("force-tick failed for " + fixtureKey + ": " + tick,
                Reply.of(tick).ok());

        // Input-drain check — pins the "recipe consumed its ingredients"
        // contract. Without this, a regression where the machine generates
        // output items without consuming inputs (free-output exploit) would
        // slip through — the output assertion below would still pass.
        //
        // Soft form: at least ONE ingredient slot must have changed from its
        // initial state. Some recipes legitimately use catalysts that stay
        // (e.g. PrecisionLaserEtcher's lens) — requiring every slot to drain
        // would false-positive on those. But if ALL slots remain at initial
        // count after recipe-time × N cycles, the recipe did not actually run.
        if (!r.itemIngredients.isEmpty()) {
            String inputRead = String.join("\n", c.execute("artest hatch read 0 " + p.firstInput()));
            boolean anyDrained = false;
            // ASKED of the list, not addressed in it: an element carrying all three of slot,
            // item and count is the slot still holding its initial stack, and NO such element is
            // exactly what "it drained" looks like — a consumed slot leaves the array entirely
            // (`{"size":4,"slots":[]}`). Addressing it would refuse on the one state this check
            // exists to detect.
            //
            // As one needle the three fields had to be adjacent and in the producer's order — a
            // field inserted between them makes every slot look drained and the whole claim
            // vacuous — and the count was matched as a PREFIX, so a slot still holding 16
            // answered for one holding 1 whenever the expected count was 1.
            Reply slots = Reply.of("artest hatch read", inputRead);
            for (String[] ing : r.itemIngredients) {
                boolean untouched = slots.holdsElementWithAll("slots",
                        "slot", ing[0], "item", ing[1], "count", ing[2]);
                if (!untouched) { anyDrained = true; break; }
            }
            assertTrue("no input items consumed for " + fixtureKey
                            + " — recipe appears to run but every ingredient slot still "
                            + "holds the full initial count (potential free-output regression; "
                            + "expected at least one slot drained, catalysts aside): " + inputRead,
                    anyDrained);
        }

        // Output check — item output OR fluid output depending on the recipe.
        if (!r.itemOutputs.isEmpty()) {
            String expectedItem = r.itemOutputs.get(0)[1];
            assertTrue(fixtureKey + " produces item " + expectedItem
                            + " but has no outputPos ('O' in structure)",
                    p.firstOutput() != null);
            String read = String.join("\n", c.execute("artest hatch read 0 " + p.firstOutput()));
            assertTrue("hatch read errored for " + fixtureKey + ": " + read,
                    !Reply.of(read).has("error"));
            // Addressed by the FETCH — the machine's own output hatch at `firstOutput()` — so the
            // slot the recipe filled is the machine's choice and not the test's to name.
            assertTrue("expected output " + expectedItem + " not in the output hatch for "
                            + fixtureKey + ": " + read,
                    Reply.of("artest hatch read", read)
                            .holdsElement("slots", "item", String.valueOf(expectedItem)));
        }
        if (!r.fluidOutputs.isEmpty()) {
            String expectedFluid = r.fluidOutputs.get(0)[0];
            assertTrue(fixtureKey + " produces fluid " + expectedFluid
                            + " but has no liquidOutputPos ('l' in structure)",
                    p.firstLiquidOutput() != null);
            // Scan ALL liquid output hatches — output may land in any of
            // them (controller picks the first hatch that can accept).
            boolean found = false;
            StringBuilder seen = new StringBuilder();
            for (String pos : p.liquidOutputPositions) {
                String read = String.join("\n", c.execute("artest fluid stored 0 " + pos));
                seen.append(pos).append(" -> ").append(read).append('\n');
                // A SEARCH over candidate positions: this position may legitimately hold nothing,
                // so the question is existence and `element`'s refusal would end the loop.
                // absence is the answer: the claim is whether the tank holds that fluid AT ALL,
            // and a list with no such element is the "not yet" this loop waits out.
            if (Reply.of("artest fluid stored", read)
                        .holdsElement("tanks", "fluid", String.valueOf(expectedFluid))) {
                    found = true; break;
                }
            }
            assertTrue("expected output fluid " + expectedFluid
                            + " not in any liquid output hatch for " + fixtureKey
                            + " (item-inputs=" + r.itemIngredients.size()
                            + ", fluid-inputs=" + r.fluidIngredients.size()
                            + "):\n" + seen,
                    found);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static final String INV_SIZE = "size";

    private static void fillItemIngredients(TestClient c, String fixtureKey,
                                            FixturePositions p,
                                            List<String[]> items) throws Exception {
        if (items.isEmpty()) return;
        assertTrue("recipe needs item inputs but fixture " + fixtureKey
                        + " has no inputPos ('I' in structure): " + p.fullResp,
                p.firstInput() != null);
        // The recipe ingredient-list index is NOT a fixed inventory slot — the
        // controller matches ingredients against the combined contents of all
        // input hatches regardless of slot. So place each ingredient into the
        // next free slot, spilling into the next input hatch once one fills.
        // (Machines like the precision assembler declare more ingredients than
        // a single 4-slot hatch can hold; the fixture supplies extra hatches.)
        int hatchSize = readInventorySize(c, p.firstInput());
        int globalSlot = 0;
        for (String[] ing : items) {
            int hatchIdx = globalSlot / hatchSize;
            int localSlot = globalSlot % hatchSize;
            assertTrue("recipe needs " + items.size() + " item input slot(s) but fixture "
                            + fixtureKey + " supplies only " + p.inputPositions.size()
                            + " input hatch(es) × " + hatchSize + " slots: " + p.fullResp,
                    hatchIdx < p.inputPositions.size());
            String pos = p.inputPositions.get(hatchIdx);
            // hatch fill <dim> <pos> <slot> <itemId> [count] [meta]
            String fill = String.join("\n", c.execute(
                    "artest hatch fill 0 " + pos + " " + localSlot + " "
                            + ing[1] + " " + ing[2] + " " + ing[3]));
            assertTrue("hatch fill (hatch " + hatchIdx + " slot " + localSlot + " " + ing[1]
                            + ":" + ing[3] + " ×" + ing[2] + ") failed for "
                            + fixtureKey + ": " + fill,
                    Reply.of(fill).ok());
            globalSlot++;
        }
    }

    /** Reads an input hatch's inventory size from a {@code hatch read}. */
    private static int readInventorySize(TestClient c, String pos) throws Exception {
        String resp = String.join("\n", c.execute("artest hatch read 0 " + pos));
        Reply mReply = Reply.of(resp);
        assertTrue("could not read input-hatch size at " + pos + ": " + resp, mReply.has(INV_SIZE));
        return Integer.parseInt(mReply.text(INV_SIZE));
    }

    private static void fillFluidIngredients(TestClient c, String fixtureKey,
                                             FixturePositions p,
                                             List<String[]> fluids) throws Exception {
        if (fluids.isEmpty()) return;
        assertTrue("recipe needs " + fluids.size() + " fluid input(s) but fixture "
                        + fixtureKey + " has " + p.liquidInputPositions.size()
                        + " liquid input hatch(es) ('L' in structure): " + p.fullResp,
                fluids.size() <= p.liquidInputPositions.size());
        // Each fluid goes into a SEPARATE hatch — TileFluidHatch tanks hold
        // exactly one fluid type at a time, so ChemicalReactor's two-fluid
        // recipes need two distinct 'L' positions.
        for (int i = 0; i < fluids.size(); i++) {
            String[] f = fluids.get(i);
            String pos = p.liquidInputPositions.get(i);
            // ×10 safety margin — some impls drain slightly more than declared.
            int amount = Integer.parseInt(f[1]) * 10;
            String fluidResp = String.join("\n", c.execute(
                    "artest fluid inject 0 " + pos + " " + f[0] + " " + amount));
            assertTrue("fluid inject (" + f[0] + " ×" + amount + " into "
                            + pos + ") failed for " + fixtureKey + ": " + fluidResp,
                    Reply.of(fluidResp).ok());
        }
    }
}
