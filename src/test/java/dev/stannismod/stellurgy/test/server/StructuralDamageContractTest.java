package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;
import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a declared impact does to real blocks in a real world — the damage engine driven through the
 * same service a weapon will call.
 *
 * <p>Four contracts, each one a thing a weapon has to be able to rely on:</p>
 *
 * <ul>
 *   <li>an impact that meets a wall <b>spends into it and says so</b>: something is staged or
 *       destroyed, the report names how deep it reached and where it entered;</li>
 *   <li>an impact whose budget outlasts the wall <b>exits carrying the rest</b> — that is what lets a
 *       shot continue instead of being silently swallowed by the first thing it touches;</li>
 *   <li>the <b>same impact identity applied twice damages once</b>: retries are real on the resolution
 *       path, and double damage is invisible in a diff;</li>
 *   <li>a wall of tougher stuff <b>is not penetrated further</b> than a flimsy one at equal budget —
 *       the ordering the toughness table exists to express, pinned as an ordering rather than as any
 *       particular number, all of which are tunable.</li>
 * </ul>
 *
 * <p>Impacts are declared with {@code /stellurgytest damage impact ...}, which calls the production service
 * on the logical server; the stage of any block is read back through the same unified reader
 * production uses.</p>
 */
public class StructuralDamageContractTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70;

    /**
     * red-witnessed: with {@code Walk#visit} at {@code result.entryPoint = here;} (the walk recording its entry point)
     * removed, this fails with "the report names no entry point, so a continuing shot has nowhere to
     * resume: {...hasEntry:false...}". Only the entry verdict was individually witnessed; the spend,
     * depth and staged-or-destroyed verdicts before and after it were not. 2026-09-30.
     *
     * <p>red-witnessed: with {@code ShipDamageService#toReport} at {@code return new DamageReport(walk.outcome, walk.stopReason, walk.budgetSpent, walk.budgetLeft,} reporting every walk's outcome as
     * NOTHING_STRUCK, this fails at "an impact into a solid wall reported striking nothing:
     * {...outcome:NOTHING_STRUCK...spent:3000...destroyed:3...}" (2026-09-30).</p>
     */
    @Test
    public void anImpactIntoAWallSpendsIntoItAndReportsWhereItReached() throws Exception {
        int x = 1200, z = 1200;
        buildWall("minecraft:stone", x, z, 4);
        clearImpactMemory();

        // Fired from outside the wall's near face, straight along +X into it, with a budget big
        // enough to matter but far too small to walk four blocks of stone.
        String result = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", 9001);
        assertTrue("an impact into a solid wall reported striking nothing:\n" + result,
                !"NOTHING_STRUCK".equals(Reply.of(result).text("outcome")));
        assertTrue("an impact into a wall spent none of its budget:\n" + result,
                readLong(result, "spent") > 0);
        assertTrue("the report names no depth, so no weapon could tell a slug from a pellet:\n" + result,
                readLong(result, "depth") > 0);
        assertTrue("the report names no entry point, so a continuing shot has nowhere to resume:\n"
                + result, Reply.of(result).bool("hasEntry"));
        assertTrue("nothing was staged and nothing destroyed, yet budget was spent:\n" + result,
                readLong(result, "staged") + readLong(result, "destroyed") > 0);
    }

    /**
     * red-witnessed: with {@code Walk#exitedFarSide} at {@code return decide(DamageOutcome.EXITED, StopReason.EXITED_FAR_SIDE, lastSolidExit);}
     * decided as {@code ABSORBED/BUDGET_EXHAUSTED} instead, this fails with "a budget that dwarfs a
     * single pane did not report exiting: {...outcome:ABSORBED...left:399624...}". 2026-09-30, taken on
     * the pre-fix form, where that decision was made inline in {@code Walk#visit}; the fix that same day
     * moved it into its own method without changing it.
     */
    @Test
    public void anImpactThatOutlastsTheWallExitsCarryingTheRest() throws Exception {
        int x = 1200, z = 1220;
        buildWall("minecraft:glass", x, z, 1);
        clearImpactMemory();

        // One pane of glass against a budget sized for a great deal more than one pane.
        String result = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 400000, "KINETIC", 9002);
        assertTrue("a budget that dwarfs a single pane did not report exiting:\n" + result,
                "EXITED".equals(Reply.of(result).text("outcome")));
        assertTrue("an exiting impact must say it left the far side:\n" + result,
                "EXITED_FAR_SIDE".equals(Reply.of(result).text("stopReason")));
        assertTrue("an exiting impact carries no budget onward — the shot was silently swallowed:\n"
                + result, readLong(result, "left") > 0);
        assertTrue("an exiting impact names no exit point, so a continuing shot cannot resume:\n"
                + result, Reply.of(result).bool("hasExit"));
        assertTrue("the pane survived a budget that should have taken it:\n" + result,
                readLong(result, "destroyed") > 0);
    }

    /**
     * A kinetic impact that cannot buy the next stage of the block it has reached is stopped by that
     * block: what it paid for is real damage, and what it has left goes nowhere — it is neither
     * handed back nor carried through the block that held. Maintainer ruling 2026-10-03, verbatim:
     * "По кинетике без порога - такой снаряд не должен делать повреждения. Будем считать, что "броня
     * держит"" (a kinetic round below the threshold does no damage; the armour holds).
     *
     * <p>The budget is one whole block and a stage and a half of the next, priced by the wall itself:
     * the first block goes, and the second takes the one stage that was paid for and holds. That the
     * block behind it is not reached is read as the remainder going nowhere ({@code left == 0}), not
     * off the third block's stage: half a stage cannot stage it under either law, so that read could
     * not fail.</p>
     *
     * <p>red-witnessed: with {@code Walk#armourHeld} at
     * {@code return world.isBlockLoaded(axis) && isStructure(world, axis, world.getBlockState(axis));}
     * answering false, this fails with "the remainder of an impact that could not buy the next stage
     * was handed back or carried on ... {...outcome:EXITED, stopReason:EXITED_FAR_SIDE, spent:1250,
     * left:125, staged:1, destroyed:1, depth:3...} expected:&lt;0&gt; but was:&lt;125&gt;" (2026-10-03).</p>
     *
     * <p>red-witnessed: with {@code Walk#visit} at
     * {@code return decide(DamageOutcome.ABSORBED, StopReason.ARMOUR_HELD, null);} deciding
     * {@code BUDGET_EXHAUSTED} instead, this fails at "an impact stopped by a block reported a different
     * reason: {...outcome:ABSORBED, stopReason:BUDGET_EXHAUSTED, spent:1375, left:0...}" (2026-10-03).</p>
     *
     * <p>red-witnessed: with {@code StructureDamageEngine#spendInto} at
     * {@code if (!mayRemove(world, pos, state))} taken for every block that reaches its last stage (so
     * nothing is ever removed), this fails at "the stages the impact paid for were not all taken (one
     * whole block, then one stage of the next): {...stopReason:ARMOUR_HELD, spent:1375, left:0,
     * staged:2, destroyed:0...}" (2026-10-03).</p>
     *
     * <p>red-witnessed: with {@code Walk#visit} at
     * {@code result.distanceWalked = layer.tEnter * reach;} followed by the held block's stage being set
     * back to 0 before the walk ends, this fails at "the block that held was not left with the one stage
     * that was paid for: {...stopReason:ARMOUR_HELD...staged:1...} ... {...stage:0...}" (2026-10-03).</p>
     */
    @Test
    public void aKineticImpactThatCannotBuyTheNextStageIsHeldByTheBlock() throws Exception {
        int x = 1200, z = 1340;
        buildWall("minecraft:stone", x, z, 3);
        clearImpactMemory();

        String probe = stage(x, z);
        long stageCost = readLong(probe, "stageCost");
        long maxStage = readLong(probe, "maxStage");
        requireArranged("the wall has fewer than two stages, so no budget lands part-way into a block: "
                + probe, maxStage > 1 && stageCost > 1);
        int budget = (int) (stageCost * maxStage + stageCost + stageCost / 2);

        String result = impact(x - 2.5D, z + 0.5D, 1, 0, 0, budget, "KINETIC", 9020);
        assertEquals("the remainder of an impact that could not buy the next stage was handed back"
                + " or carried on, so the block it could not take out did not stop it:\n" + result,
                0L, readLong(result, "left"));
        assertEquals("an impact stopped by a block reported a different reason:\n" + result,
                "ARMOUR_HELD", Reply.of(result).text("stopReason"));
        assertEquals("the stages the impact paid for were not all taken (one whole block, then one"
                + " stage of the next):\n" + result, 1L, readLong(result, "destroyed"));
        assertEquals("the block that held was not left with the one stage that was paid for:\n"
                + result + "\n" + stage(x + 1, z), 1L, readLong(stage(x + 1, z), "stage"));
    }

    /**
     * red-witnessed: with {@code ShipDamageService#apply} at {@code if (isDuplicate(world, request.getImpactId()))}'s duplicate check disabled, this fails with
     * "the same impact identity was applied a second time ... {...stopReason:EXITED_FAR_SIDE,spent:1000
     * ...}"; with the same check widened to also refuse {@code impactId - 1}, the fresh-identity verdict
     * fails with "a fresh impact identity was refused as a duplicate: {...DUPLICATE_IMPACT...}".
     * 2026-09-30.
     */
    @Test
    public void theSameImpactIdentityAppliedTwiceDamagesOnce() throws Exception {
        int x = 1200, z = 1240;
        buildWall("minecraft:stone", x, z, 4);
        clearImpactMemory();

        long id = 9003L;
        String first = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", id);
        long firstSpend = readLong(first, "spent");
        assertTrue("the first application of the impact did nothing, so the second proves nothing:\n"
                + first, firstSpend > 0);

        String second = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", id);
        assertTrue("the same impact identity was applied a second time — a retry on the resolution "
                + "path would therefore damage twice, and no diff would show it:\n" + second,
                "DUPLICATE_IMPACT".equals(Reply.of(second).text("stopReason")));
        assertTrue("a refused duplicate spent budget:\n" + second, readLong(second, "spent") == 0);
        assertTrue("a refused duplicate did not hand the budget back:\n" + second,
                readLong(second, "left") == 3000);

        // A DIFFERENT identity at the same place is a genuinely new impact and must still land —
        // otherwise the dedup would have turned into "one impact per position, ever".
        String third = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", id + 1);
        assertTrue("a fresh impact identity was refused as a duplicate:\n" + third,
                !"DUPLICATE_IMPACT".equals(Reply.of(third).text("stopReason")));
        assertTrue("a fresh impact identity spent nothing:\n" + third, readLong(third, "spent") > 0);
    }

    /**
     * red-witnessed: with {@code StructureDamageEngine#spendInto} at {@code int stageCost = stageCost(world, pos, areaFactor, kind);}'s per-stage price overwritten to 1 in
     * the spend (the probe's quoted price untouched), this fails with "the same budget went as far into
     * iron as into glass (glass destroyed 8, iron destroyed 8)". 2026-09-30.
     */
    @Test
    public void aTougherWallIsNotPenetratedFurtherThanAFlimsyOneAtEqualBudget() throws Exception {
        int thickness = 8;
        int glassX = 1200, glassZ = 1260;
        int ironX = 1200, ironZ = 1280;
        buildWall("minecraft:glass", glassX, glassZ, thickness);
        buildWall("minecraft:iron_block", ironX, ironZ, thickness);
        clearImpactMemory();

        // The budget is derived from what production itself charges, not from a number written here:
        // exactly enough to take the whole glass wall. Every cost in this engine is tunable, so a
        // budget picked by hand would pin the tuning instead of the ordering, and would go red the
        // day someone rebalances armour without breaking anything a player would notice.
        String glassProbe = stage(glassX, glassZ);
        String ironProbe = stage(ironX, ironZ);
        long glassStageCost = readLong(glassProbe, "stageCost");
        long ironStageCost = readLong(ironProbe, "stageCost");
        long maxStage = readLong(glassProbe, "maxStage");
        assertTrue("iron is not costed above glass, so the toughness table orders nothing and the "
                + "comparison below is empty (glass=" + glassProbe + " iron=" + ironProbe + ")",
                ironStageCost > glassStageCost);

        int budget = (int) (glassStageCost * maxStage * thickness);
        String glass = impact(glassX - 2.5D, glassZ + 0.5D, 1, 0, 0, budget, "KINETIC", 9010);
        String iron = impact(ironX - 2.5D, ironZ + 0.5D, 1, 0, 0, budget, "KINETIC", 9011);
        long glassDestroyed = readLong(glass, "destroyed");
        long ironDestroyed = readLong(iron, "destroyed");

        // The control: the cheap wall must actually give way, or "iron resisted" is vacuous.
        assertTrue("a budget sized to take the whole glass wall did not take it (destroyed "
                + glassDestroyed + " of " + thickness + "), so this comparison measures nothing:\n"
                + glass, glassDestroyed == thickness);
        assertTrue("the same budget went as far into iron as into glass (glass destroyed "
                + glassDestroyed + ", iron destroyed " + ironDestroyed + "): a hull's material buys "
                + "its crew nothing.\niron=" + iron, ironDestroyed < glassDestroyed);
    }

    /**
     * Two blocks of ONE material, differing only in how much of their voxel they fill: a wool block
     * and a wool carpet. The carpet must cost far less — and must still cost something.
     *
     * <p>The law is an energy per unit of VOLUME removed, so the material is deliberately held fixed:
     * a comparison across materials would pass on the toughness table alone and say nothing about
     * volume. A carpet fills a sixteenth of its voxel, and for as long as a block was priced as a full
     * cubic metre it cost what a solid block of wool costs to shoot through.</p>
     *
     * <p>The second half is the one that used to be held by a floor under the occupancy, and is now
     * held by the price itself: the base term of the law is material-independent and the price rounds
     * up to at least one. So "almost no material" lands at "almost free", never at "free" — which is
     * what stops a body walking an arbitrarily long run of decoration for nothing.</p>
     *
     * <p>red-witnessed: with {@code StructureDamageEngine#occupancyOf} at {@code if (world == null || pos == null)}'s {@code occupancyOf} answering 1.0
     * for every block, this fails with "a carpet costs what a solid block of the same wool costs
     * (carpet=76 block=76)". The never-free verdict was not separately witnessed. 2026-09-30.</p>
     */
    @Test
    public void aBlockIsPricedByHowMuchOfItsVoxelItFillsAndNeverAtNothing() throws Exception {
        int blockX = 1200, blockZ = 1300;
        int carpetX = 1200, carpetZ = 1320;
        buildWall("minecraft:wool", blockX, blockZ, 1);
        buildWall("minecraft:carpet", carpetX, carpetZ, 1);

        String solid = stage(blockX, blockZ);
        String thin = stage(carpetX, carpetZ);
        long solidCost = readLong(solid, "stageCost");
        long thinCost = readLong(thin, "stageCost");

        assertTrue("a carpet costs what a solid block of the same wool costs (carpet=" + thinCost
                + " block=" + solidCost + "): then a block is priced as a full cubic metre of material"
                + " however little of its voxel it fills, and the law stops being about volume."
                + " carpet=" + thin + " block=" + solid, thinCost < solidCost);
        assertTrue("a carpet costs nothing at all (" + thin + "): then a body crosses any length of"
                + " decoration for free, and the price has no lower end", thinCost >= 1);
    }

    /** The unified stage reader at a wall's first block: stage, max stage, and what a stage costs there. */
    private String stage(int x, int z) throws Exception {
        return exec("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + z);
    }

    private String impact(double x, double z, double dx, double dy, double dz, int budget, String kind,
                          long impactId) throws Exception {
        return exec("stellurgytest damage impact " + DIM + " " + x + " " + (Y + 0.5D) + " " + z
                + " " + dx + " " + dy + " " + dz + " " + budget + " " + kind + " " + impactId);
    }

    /** A run of blocks along +X at the test's own row, the wall an impact is fired into. */
    private void buildWall(String block, int x, int z, int thickness) throws Exception {
        String resp = exec("stellurgytest fill " + DIM + " " + x + " " + Y + " " + z + " "
                + (x + thickness - 1) + " " + Y + " " + z + " " + block);
        assertTrue("failed to build the " + block + " wall at " + x + "," + Y + "," + z + ": " + resp,
                Reply.of(resp).ok());
        assertTrue("the " + block + " wall placed no blocks, so every assertion below would be about "
                + "an empty row of air: " + resp, readLong(resp, "placed") == thickness);
    }

    private void clearImpactMemory() throws Exception {
        // The dedup memory is server-lifetime state on a shared server; a scenario that does not
        // clear it can be refused for an id another scenario happened to use.
        exec("stellurgytest damage clear-impacts");
    }

    private static long readLong(String json, String key) {
        return Reply.of(json).longInteger(key);
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
