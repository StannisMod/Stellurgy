package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.contract.RedWitness;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.Wait;
import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.WarShip;
import dev.stannismod.stellurgy.damage.DamageState;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The repair ladder's group: the welder, its bottom rung, and the repair bay, the rung above it.
 *
 * <p>The welder: one use takes one stage of damage off a block, paid for in that block's own
 * materials and in charge — and every way it can refuse is a different, visible answer that costs
 * the player nothing.</p>
 *
 * <p>The bay: a machine on a ship that works that ship's damage on its own, out of a reserve of
 * finished blocks, at a speed its size buys — and that shares the hull with any other bay without
 * two of them paying for one repair.</p>
 *
 * <p>What is pinned here is the CONTRACT (C20 REPAIR-1, 3, 4, 5, 6, 7, 8, 10, 12, 13, 14), not the price list.
 * The assertions say that material or energy left, never how much, and that one size is faster than
 * another, never by how much: the prices are tuned numbers, and a test that pinned them would fail the
 * first time somebody balanced the game rather than the first time somebody broke it.</p>
 *
 * <p>What the bay scenarios do NOT see: the bay's screen (they read the bay's answer where production
 * keeps it, not where a player reads it); a bay's hull unloading as a player leaving unloads it, or a
 * server restart under a working bay (REPAIR-8 is pinned across the bay's own chunk saved, dropped and
 * read back, which saves and re-reads the bay the same way, with no time passing between); and how
 * much faster two bays are (REPAIR-14 is pinned as two jobs that overlap, never as a rate).</p>
 */
public class RepairWelderTest extends AbstractSharedServerTest {

    /** A site of this class's own, clear of the ship scenarios. */
    private static final int X = 8000, Y = 80, Z = 7200;

    /** Crafted from nine ingots, so it has a recipe to be priced against. */
    private static final String SUBJECT = "minecraft:iron_block";
    private static final String MATERIAL = "minecraft:iron_ingot";
    /** Smelted, never crafted — so nothing can price a repair of it. */
    private static final String UNPRICEABLE = "minecraft:stone";
    /** Crafted from four planks of any wood: each slot accepts six items. */
    private static final String CRAFTED_FROM_ANY_PLANK = "minecraft:crafting_table";
    /** Spruce — the slot's second variant, never its first. */
    private static final String SECOND_PLANK = "minecraft:planks#1";

    private static final int PLENTY_OF_CHARGE = 100000;
    private static final int PLENTY_OF_MATERIAL = 64;

    /**
     * Pins REPAIR-1 (a repair lowers one stage or fills a hole and does nothing else).
     * Pins REPAIR-3 (material and charge leave on a repair and neither leaves on a refusal).
     */
    @Pins({"REPAIR-1", "REPAIR-3"})
    @LegacyWitness(prose = "with {@code ItemRepairWelder#weld} at {@code cost.consume(player, false);} (the real material withdrawal) removed,"
            + " this fails with \"the repair took no material — nothing may be created from nothing:"
            + " {...materialBefore:64,materialAfter:64...}\". The stage and charge verdicts were not separately"
            + " witnessed. 2026-09-30, taken on the pre-change form {@code RepairCost.consume(player, cost, false);},"
            + " which is now the instance call named above.")
    @Test
    public void oneUseTakesOneStageAndIsPaidForTwice() throws Exception {
        int x = X, y = Y, z = Z;
        int damaged = placeAndDamage(x, y, z, SUBJECT, 2, 78001);
        assertTrue("the subject must be damaged but not destroyed, or the welder has nothing to do "
                + "or nothing to do it to (stage " + damaged + ")", damaged >= 1);

        String weld = weld(x, y, z, PLENTY_OF_CHARGE, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("the welder refused a damaged, priceable block: " + weld,
                "REPAIRED", extractString(weld, "outcome"));
        assertEquals("one use must remove exactly one stage: " + weld,
                damaged - 1, extractInt(weld, "stageAfter"));
        assertTrue("the repair took no material — nothing may be created from nothing: " + weld,
                extractInt(weld, "materialAfter") < extractInt(weld, "materialBefore"));
        assertTrue("the repair took no charge: " + weld,
                extractInt(weld, "energyAfter") < extractInt(weld, "energyBefore"));
    }

    /**
     * Pins REPAIR-3 (material and charge leave on a repair and neither leaves on a refusal).
     * Pins REPAIR-7 (a bay that cannot work says why: every refusal is its own answer).
     */
    @Pins({"REPAIR-3", "REPAIR-7"})
    @RedWitness(verdict = "a refused repair spends no charge",
            member = "ItemRepairWelder#weld",
            at = "return RepairOutcome.NO_MATERIALS;",
            replacedBy = "setStoredEnergy(tool, storedEnergy(tool) - energyCost); return RepairOutcome.NO_MATERIALS;",
            fails = "a refused repair spent charge",
            on = "2026-10-15")
    @Test
    public void everyRefusalIsItsOwnAnswerAndCostsNothing() throws Exception {
        // Each case gets its own block: a refusal that quietly consumed something would otherwise be
        // hidden by the next case's fresh inventory.
        int damaged = placeAndDamage(X + 4, Y, Z, SUBJECT, 2, 78002);
        String noMaterials = weld(X + 4, Y, Z, PLENTY_OF_CHARGE, "none", 0);
        assertEquals("an empty inventory must be told apart from every other refusal: " + noMaterials,
                "NO_MATERIALS", extractString(noMaterials, "outcome"));
        assertEquals("a refused repair changed the block anyway: " + noMaterials,
                damaged, extractInt(noMaterials, "stageAfter"));
        assertEquals("a refused repair spent charge: " + noMaterials,
                extractInt(noMaterials, "energyBefore"), extractInt(noMaterials, "energyAfter"));

        int stillDamaged = placeAndDamage(X + 8, Y, Z, SUBJECT, 2, 78003);
        String noCharge = weld(X + 8, Y, Z, 0, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("a flat tool must be told apart from an empty inventory: " + noCharge,
                "NO_CHARGE", extractString(noCharge, "outcome"));
        assertEquals("a refused repair changed the block anyway: " + noCharge,
                stillDamaged, extractInt(noCharge, "stageAfter"));
        assertEquals("a refused repair took materials: " + noCharge,
                extractInt(noCharge, "materialBefore"), extractInt(noCharge, "materialAfter"));

        place(X + 12, Y, Z, SUBJECT);
        String undamaged = weld(X + 12, Y, Z, PLENTY_OF_CHARGE, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("an undamaged block must not read as a failed repair: " + undamaged,
                "UNDAMAGED", extractString(undamaged, "outcome"));
        assertEquals("welding an undamaged block took materials: " + undamaged,
                extractInt(undamaged, "materialBefore"), extractInt(undamaged, "materialAfter"));

        placeAndDamage(X + 16, Y, Z, UNPRICEABLE, 2, 78004);
        String noRecipe = weld(X + 16, Y, Z, PLENTY_OF_CHARGE, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("a block nothing crafts must say so rather than be repaired for free or refused "
                + "as if the player were empty-handed: " + noRecipe,
                "NO_RECIPE", extractString(noRecipe, "outcome"));
    }

    /**
     * A recipe slot that accepts several items is paid with ANY of them, not only the one listed
     * first: the crafting table's slots accept all six planks (vanilla's {@code crafting_table.json}
     * lists {@code minecraft:planks} data 0..5, oak first), and a player carrying only spruce
     * (data 1) can weld one.
     */
    @LegacyWitness(prose = "with {@code RepairCost#consume} at {@code !wanted.accepts.apply(inSlot)}"
            + " replaced by a match against the slot's first variant only"
            + " ({@code !OreDictionary.itemMatches(wanted.accepts.getMatchingStacks()[0], inSlot, false)} — the"
            + " shape it shipped with), this fails at \"a player carrying spruce planks could not pay for a"
            + " crafting table, whose slots accept every plank: {...outcome:NO_MATERIALS,...materialBefore:64,"
            + " materialAfter:64...} expected:&lt;[REPAIRED]&gt; but was:&lt;[NO_MATERIALS]&gt;\" (2026-10-06).")
    @LegacyWitness(prose = "with {@code ItemRepairWelder#weld} at {@code cost.consume(player, false);}"
            + " removed, this fails at \"the repair was granted but no spruce left the inventory:"
            + " {...outcome:REPAIRED,...materialBefore:64,materialAfter:64...}\" (2026-10-06).")
    @Test
    public void aSlotAcceptingSeveralItemsIsPaidWithAnyOfThem() throws Exception {
        int x = X + 20;
        int damaged = placeAndDamage(x, Y, Z, CRAFTED_FROM_ANY_PLANK, 2, 78005);
        requireArranged("the crafting table must be damaged but standing, or there is nothing to"
                + " weld (stage " + damaged + ")", damaged >= 1);

        String weld = weld(x, Y, Z, PLENTY_OF_CHARGE, SECOND_PLANK, PLENTY_OF_MATERIAL);
        assertEquals("a player carrying spruce planks could not pay for a crafting table, whose"
                + " slots accept every plank: " + weld, "REPAIRED", extractString(weld, "outcome"));
        assertTrue("the repair was granted but no spruce left the inventory: " + weld,
                extractInt(weld, "materialAfter") < extractInt(weld, "materialBefore"));
    }

    // ---- the repair bay ------------------------------------------------------------------------

    private static final String BAY = "stellurgy:repairBay";
    private static final String FRAME = "stellurgy:repairBayPart";
    /** libVulpes' creative plug: pushes energy into every neighbour that takes it, every tick. */
    private static final String POWER = "libvulpes:creativePowerBattery";
    /** The hull block the bay scenarios damage: crafted, so it has a block item to pay with. */
    private static final String HULL_BLOCK = "minecraft:iron_block";
    /** A registry name nothing registers — the provenance a save keeps after its mod is gone. */
    private static final String GONE_BLOCK = "stellurgytest:no_such_block";
    /**
     * Blocks a bay is stocked with. Any repair below draws at most one, so any count above one shows
     * a draw as a fall; four keeps the reserve far from empty, where an empty one would answer
     * NO_MATERIALS and read as a different refusal.
     */
    private static final int STOCK = 4;
    /**
     * How far above its site a bay hull is parked. The fixture's own scaffolding — launchpad, tower,
     * builder — stays at the site and reaches six blocks up (`stellurgytest fixture rocket`); the hull
     * is under ten tall, so twenty clears the scaffolding by the hull's whole height.
     */
    private static final int PARK_RISE = 20;
    /**
     * How far from the requested pose a parked hull may stand. A rigid teleport WRITES the pose, so a
     * hull more than a block off it is a teleport that did not land, not an imprecise one.
     */
    private static final double PARK_TOLERANCE = 1.0D;

    private final Events events = new Events(c -> read(c).raw(),
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** The hulls this scenario built, each disposed of by its own id when the scenario ends. */
    private final List<WarShip> builtHulls = new ArrayList<>();
    /** The rockets this scenario assembled, by entity id, removed when the scenario ends. */
    private final List<Integer> builtRockets = new ArrayList<>();

    /** A craft a bay scenario built goes on ticking in the world the next scenario runs in. */
    @After
    public void disposeOfThisScenariosCraft() throws Exception {
        for (WarShip hull : builtHulls) {
            hull.dispose();
        }
        for (int rocket : builtRockets) {
            Disposal.rocket(c -> arrange(c).raw(), rocket);
        }
    }

    /**
     * REPAIR-10: building a bay bigger makes it strictly faster, each added size buys less than the
     * one before, and no buildable size reaches the ceiling. Asserted as ORDERINGS of the rate
     * production computes for the bay as built — its own walk of the frames, its own law — never as
     * amounts.
     */
    @RedWitness(verdict = "a bigger bay was not faster",
            member = "RepairBaySize#powerPerTick",
            at = "return ceiling() * size / (double) (size + config.repairBayHalfRateSize);",
            replacedBy = "return ceiling() * config.repairBayHalfRateSize / (double) (size + "
                + "config.repairBayHalfRateSize);",
            fails = "a bigger bay was not faster: sizes 1, 2, 3 worked at",
            on = "2026-10-06")
    @RedWitness(verdict = "the gain from a size did not shrink",
            member = "RepairBaySize#powerPerTick",
            at = "return ceiling() * size / (double) (size + config.repairBayHalfRateSize);",
            replacedBy = "return ceiling() * size / (double) config.repairBayHalfRateSize;",
            fails = "the gain from size 2 to 3",
            on = "2026-10-06")
    @RedWitness(verdict = "the largest buildable bay reached its ceiling",
            member = "RepairBaySize#powerPerTick",
            at = "return ceiling() * size / (double) (size + config.repairBayHalfRateSize);",
            replacedBy = "return 2 * ceiling() * size / (double) (size + config.repairBayHalfRateSize);",
            fails = "the largest buildable bay reached its ceiling",
            on = "2026-10-06")
    @Pins("REPAIR-10")
    @Test
    public void aBiggerBayIsFasterWithShrinkingGainsUnderACeiling() throws Exception {
        FixtureSite site = clearedSite(4, 8, "a repair bay and the frames that make it bigger");
        int[] bay = {site.x, site.y + 1, site.z};
        arrange(place(bay, BAY));
        double one = rateAtSize(bay, 1);
        arrange(place(offset(bay, 1, 0, 0), FRAME));
        double two = rateAtSize(bay, 2);
        arrange(place(offset(bay, 2, 0, 0), FRAME));
        double three = rateAtSize(bay, 3);
        // A block of frames larger than any bay is credited for: 7 x 7 x 7, touching the controller.
        arrange("stellurgytest fill 0 " + (bay[0] + 1) + " " + bay[1] + " " + (bay[2] - 3) + " "
                + (bay[0] + 7) + " " + (bay[1] + 6) + " " + (bay[2] + 3) + " " + FRAME);
        Reply largest = readBay(bay);
        requireArranged("the frame block did not build the largest bay production credits: " + largest,
                largest.integer("size") == largest.integer("maxSize"));

        assertTrue("a bigger bay was not faster: sizes 1, 2, 3 worked at " + one + ", " + two + ", " + three,
                one < two && two < three);
        assertTrue("the gain from size 2 to 3 (" + (three - two) + ") did not shrink below the gain from"
                + " 1 to 2 (" + (two - one) + ")", three - two < two - one);
        assertTrue("the largest buildable bay reached its ceiling: " + largest,
                largest.number("rate") < largest.number("ceiling"));
    }

    /**
     * F3 / C20 Scope: the bay serves only the ship it stands on. One standing in the world, powered,
     * stocked, beside a damaged world block, answers NO_STRUCTURE and leaves the block as it is.
     */
    @RedWitness(verdict = "NO_STRUCTURE",
            member = "TileRepairBay#look",
            at = "settle(RepairOutcome.NO_STRUCTURE);",
            replacedBy = "settle(RepairOutcome.UNDAMAGED);",
            fails = "a bay standing in the world never said it has no structure to serve",
            on = "2026-10-15")
    @RedWitness(verdict = "the world block left alone",
            member = "TileRepairBay#look",
            at = "settle(RepairOutcome.NO_STRUCTURE); return;",
            replacedBy = "settle(RepairOutcome.NO_STRUCTURE); yard = new AxisAlignedBB(pos).grow(8);",
            fails = "a bay that is on no ship repaired a world block",
            on = "2026-10-15")
    @Pins("REPAIR-7")
    @Wait(shape = Wait.Shape.WINDOW,
            at = "GameTicks.advance(client(), GameTicks.server(), workBudget(able, able.integer(\"energyPerStage\")));",
            why = "as long as a bay serving the wall would need to take one stage off it — its look plus one"
                    + " stage of work at its own rate, doubled (workBudget) — between the two reads; the verdict"
                    + " names both. Two looks alone were shorter than one stage of work, so a bay that served"
                    + " the wall still left it as it was.")
    @Test
    public void aBayThatIsNotOnAShipAnswersNoStructureAndLeavesTheWorldAlone() throws Exception {
        FixtureSite site = clearedSite(2, 6, "a bay in the open world beside a damaged world block");
        int[] wall = {site.x + 3, site.y + 1, site.z};
        arrange(place(wall, HULL_BLOCK));
        Reply fresh = stage(wall);
        act("stellurgytest damage impact 0 " + (wall[0] + 0.5D) + " " + (wall[1] + 3.5D) + " "
                + (wall[2] + 0.5D) + " 0 -1 0 " + fresh.integer("stageCost") + " KINETIC 323001");
        Reply damaged = stage(wall);
        requireArranged("the world block beside the bay must be damaged and standing, or the bay has"
                + " nothing it could wrongly serve: " + damaged,
                damaged.integer("stage") >= 1 && HULL_BLOCK.equals(damaged.text("block")));

        long mark = events.markInstrumented();
        int[] bay = {site.x, site.y + 1, site.z};
        arrange(place(bay, BAY));
        arrange(place(offset(bay, 0, 1, 0), POWER));
        stock(bay);
        Reply first = readBay(bay);
        events.awaitRecordWithFields(mark, "repair_bay_outcome",
                "a bay standing in the world never said it has no structure to serve",
                2 * first.integer("lookIntervalTicks"), "bay", xyz(bay), "to", "NO_STRUCTURE");

        Reply able = readBay(bay);
        requireArranged("the bay must hold energy and blocks, or leaving the wall alone proves nothing: "
                + able, able.integer("energy") > 0 && able.integer("reserve") == STOCK);
        GameTicks.advance(client(), GameTicks.server(), workBudget(able, able.integer("energyPerStage")));
        Reply after = stage(wall);
        assertEquals("a bay that is on no ship repaired a world block: before " + damaged + ", after "
                + after, damaged.integer("stage"), after.integer("stage"));
    }

    /**
     * REPAIR-13: two bays on one hull and one damaged block — one of them works it, and the other
     * neither puts energy into it nor pays for it.
     */
    @RedWitness(verdict = "both bays see a whole hull first",
            member = "TileRepairBay#look",
            at = ": RepairOutcome.UNDAMAGED);",
            replacedBy = ": RepairOutcome.NO_RECIPE);",
            fails = "both bays must be looking at their hull and finding it whole before it is damaged",
            on = "2026-10-06")
    @RedWitness(verdict = "the block is repaired",
            member = "TileRepairBay#restage",
            at = "DamageState.setStage(world, work.pos, work.stage - 1);",
            replacedBy = "",
            fails = "neither bay repaired the damaged block",
            on = "2026-10-06")
    @RedWitness(verdict = "only one bay pays",
            member = "BlockDamageSavedData#claim",
            at = "return false;",
            replacedBy = "",
            fails = "both bays put energy into the one damaged stage",
            on = "2026-10-06")
    @RedWitness(verdict = "the idle bay pays nothing",
            member = "TileRepairBay#look",
            at = "if (!data.claim(candidate, this, now)) {",
            replacedBy = "if (!data.claim(candidate, this, now)) { decrStackSize(0, 1);",
            fails = "the bay that did not work the stage paid for it anyway",
            on = "2026-10-15")
    @Pins("REPAIR-13")
    @Test
    public void twoBaysOnOneHullRepairItsOneDamagedBlockOnce() throws Exception {
        Hull hull = hull("the hull two repair bays share");
        int[] target = hull.at(0, 0, -4);
        int[] left = hull.at(-2, 0, -2);
        int[] right = hull.at(2, 0, -2);
        placeAboard(hull, target, HULL_BLOCK);

        long mark = events.markInstrumented();
        placeAboard(hull, left, BAY);
        placeAboard(hull, right, BAY);
        stock(left);
        stock(right);
        Reply spec = readBay(left);
        // Two stages' worth each: enough for the one stage below with room for the rate's carry.
        int charge = 2 * spec.integer("energyPerStage");
        arrange("stellurgytest energy inject 0 " + xyz(left, " ") + " " + charge);
        arrange("stellurgytest energy inject 0 " + xyz(right, " ") + " " + charge);
        int look = 2 * spec.integer("lookIntervalTicks");
        events.awaitMatching(mark, "repair_bay_outcome",
                reply -> Events.anyRecordHasAll(reply, "bay", xyz(left), "to", "UNDAMAGED")
                        && Events.anyRecordHasAll(reply, "bay", xyz(right), "to", "UNDAMAGED"),
                "carrying to = UNDAMAGED for both bays",
                "both bays must be looking at their hull and finding it whole before it is damaged", look);
        Reply leftBefore = readBay(left);
        Reply rightBefore = readBay(right);

        long hit = events.markInstrumented();
        Reply struck = shoot(hull, target, 1, 323002);
        requireArranged("the target must carry exactly one stage, or 'one repair' means something else: "
                + struck, struck.integer("stage") == 1);
        events.awaitRecordWithFields(hit, "block_stage_set", "neither bay repaired the damaged block",
                workBudget(spec, spec.integer("energyPerStage")), "pos", xyz(target), "to", "0");

        Reply leftAfter = readBay(left);
        Reply rightAfter = readBay(right);
        boolean leftWorked = leftAfter.integer("energy") < leftBefore.integer("energy");
        boolean rightWorked = rightAfter.integer("energy") < rightBefore.integer("energy");
        assertFalse("both bays put energy into the one damaged stage: left " + leftBefore + " -> " + leftAfter
                + ", right " + rightBefore + " -> " + rightAfter, leftWorked && rightWorked);
        Reply idleBefore = leftWorked ? rightBefore : leftBefore;
        Reply idleAfter = leftWorked ? rightAfter : leftAfter;
        assertEquals("the bay that did not work the stage paid for it anyway: " + idleBefore + " -> "
                + idleAfter, idleBefore.integer("reserve"), idleAfter.integer("reserve"));
    }

    /**
     * REPAIR-4 and REPAIR-5: a hole is filled with the block its record names, paid for with one
     * block from the reserve, and the record is spent in the same step — so the bay's next look
     * finds nothing there and spends nothing.
     */
    @RedWitness(verdict = "the hole was filled with the block its record names",
            member = "TileRepairBay#rebuild",
            at = "world.setBlockState(work.pos, work.place, 3)",
            replacedBy = "world.setBlockState(work.pos, net.minecraft.init.Blocks.STONE.getDefaultState(), 3)",
            fails = "the hole was filled with something other than the block its record names",
            on = "2026-10-06")
    @RedWitness(verdict = "the record was spent",
            member = "TileRepairBay#rebuild",
            at = "BlockDamageSavedData.get(world).clear(work.pos);",
            replacedBy = "",
            fails = "the hole's record survived its own rebuild",
            on = "2026-10-06")
    @RedWitness(verdict = "one block left the reserve",
            member = "TileRepairBay#rebuild",
            at = "decrStackSize(slot, 1);",
            replacedBy = "",
            fails = "the hole was rebuilt and nothing left the reserve",
            on = "2026-10-06")
    @RedWitness(verdict = "the next look found the hull whole",
            member = "TileRepairBay#look",
            at = ": RepairOutcome.UNDAMAGED);",
            replacedBy = ": RepairOutcome.NO_RECIPE);",
            fails = "after the rebuild the bay never looked again and found its hull whole",
            on = "2026-10-06")
    @RedWitness(verdict = "the hole is rebuilt",
            member = "TileRepairBay#rebuild",
            at = "int slot = BayEngine.slotOf(work.step.price(), reserveHandler);",
            replacedBy = "if (work != null) { return false; } int slot = BayEngine.slotOf(work.step.price(), reserveHandler);",
            fails = "the bay never rebuilt the hole in its own hull",
            on = "2026-10-15")
    @RedWitness(verdict = "the next look spends nothing",
            member = "TileRepairBay#look",
            at = "settle(lacked ? RepairOutcome.NO_MATERIALS",
            replacedBy = "if (!lacked && !unfillable && !unpriced) { decrStackSize(0, 1); }"
                + " settle(lacked ? RepairOutcome.NO_MATERIALS",
            fails = "the bay's look after the rebuild spent from the reserve",
            on = "2026-10-15")
    @Pins({"REPAIR-4", "REPAIR-5"})
    @Test
    public void aHoleIsRebuiltWithTheBlockItsRecordNamesAndTheRecordIsSpent() throws Exception {
        Hull hull = hull("the hull a bay rebuilds a hole in");
        int[] target = hull.at(0, 0, -4);
        int[] bay = hull.at(-2, 0, -2);
        placeAboard(hull, target, HULL_BLOCK);
        placeAboard(hull, bay, BAY);
        placeAboard(hull, offset(bay, 0, 1, 0), POWER);
        stock(bay);
        Reply spec = readBay(bay);

        long mark = events.markInstrumented();
        Reply hole = shoot(hull, target, stage(target).integer("maxStage"), 323003);
        requireArranged("the target must be a hole that remembers it was " + HULL_BLOCK + ": " + hole,
                "minecraft:air".equals(hole.text("block")) && hole.bool("wasDestroyed")
                        && HULL_BLOCK.equals(hole.text("destroyedBlock")));
        String rebuilt = events.awaitRecordWithFields(mark, "repair_bay_rebuilt",
                "the bay never rebuilt the hole in its own hull",
                workBudget(spec, spec.integer("energyPerStage") * hole.integer("maxStage")),
                "pos", xyz(target), "placed", "true");

        Reply filled = stage(target);
        assertEquals("the hole was filled with something other than the block its record names: " + filled,
                HULL_BLOCK, filled.text("block"));
        assertFalse("the hole's record survived its own rebuild — a second free block on the next pass: "
                + filled, filled.bool("wasDestroyed"));
        int reserveAtRebuild = (int) Events.number(rebuilt, "reserve");
        assertTrue("the hole was rebuilt and nothing left the reserve: " + rebuilt, reserveAtRebuild < STOCK);

        long rebuiltSeq = (long) Events.number(rebuilt, "seq");
        int look = 2 * spec.integer("lookIntervalTicks");
        events.awaitMatching(mark, "repair_bay_outcome",
                reply -> anyLaterOutcome(reply, rebuiltSeq, bay, "UNDAMAGED"),
                "carrying to = UNDAMAGED after the rebuild",
                "after the rebuild the bay never looked again and found its hull whole", look);
        Reply after = readBay(bay);
        assertEquals("the bay's look after the rebuild spent from the reserve: " + rebuilt + " then " + after,
                reserveAtRebuild, after.integer("reserve"));
    }

    /**
     * REPAIR-6: a hole whose record names a block that no longer exists is refused, said to be, and
     * its record kept — by a bay that has the energy and the blocks to fill any other hole.
     */
    @RedWitness(verdict = "the record kept",
            member = "Work#at",
            at = "if (block == null) {",
            replacedBy = "if (block == null) { data.clear(pos);",
            fails = "the refused hole was filled or its record dropped",
            on = "2026-10-15")
    @RedWitness(verdict = "the reserve untouched",
            member = "TileRepairBay#look",
            at = "unfillable = true;",
            replacedBy = "unfillable = true; decrStackSize(0, 1);",
            fails = "refusing the hole took from the reserve",
            on = "2026-10-15")
    @RedWitness(verdict = "UNFILLABLE",
            member = "BlockDamageSavedData#blockFromName",
            at = "return Block.REGISTRY.containsKey(key) ? Block.REGISTRY.getObject(key) : null;",
            replacedBy = "return Block.REGISTRY.getObject(key);",
            fails = "the bay never said the hole is one it may not fill",
            on = "2026-10-06")
    @Pins("REPAIR-6")
    @Wait(shape = Wait.Shape.WINDOW,
            at = "GameTicks.advance(client(), GameTicks.server(), 2 * spec.integer(\"lookIntervalTicks\"));",
            why = "two looks of a POWERED, stocked bay at the hole, between power and the reads below; no record"
                    + " is written while its answer stays UNFILLABLE, so the reads ARE the observation.")
    @Test
    public void aHoleWhoseBlockNoLongerExistsIsRefusedAndItsRecordKept() throws Exception {
        Hull hull = hull("the hull with a hole nothing can fill");
        int[] target = hull.at(0, 0, -4);
        int[] bay = hull.at(-2, 0, -2);
        placeAboard(hull, target, HULL_BLOCK);
        // Stocked but not yet powered: while the hole is still an ordinary one the bay cannot fill
        // it, so the record it is about to be told about is the one it actually meets.
        placeAboard(hull, bay, BAY);
        stock(bay);
        Reply spec = readBay(bay);
        Reply hole = shoot(hull, target, stage(target).integer("maxStage"), 323004);
        requireArranged("the target must be a hole with a record: " + hole,
                "minecraft:air".equals(hole.text("block")) && hole.bool("wasDestroyed"));

        long mark = events.markInstrumented();
        arrange("stellurgytest damage forge-provenance 0 " + xyz(target, " ") + " " + GONE_BLOCK);
        events.awaitRecordWithFields(mark, "repair_bay_outcome",
                "the bay never said the hole is one it may not fill",
                2 * spec.integer("lookIntervalTicks"), "bay", xyz(bay), "to", "UNFILLABLE");

        placeAboard(hull, offset(bay, 0, 1, 0), POWER);
        GameTicks.advance(client(), GameTicks.server(), 2 * spec.integer("lookIntervalTicks"));
        Reply after = readBay(bay);
        requireArranged("the bay must hold energy, or the refusal could be a flat bay's: " + after,
                after.integer("energy") > 0);
        Reply kept = stage(target);
        assertTrue("the refused hole was filled or its record dropped: " + kept,
                "minecraft:air".equals(kept.text("block")) && kept.bool("wasDestroyed")
                        && GONE_BLOCK.equals(kept.text("destroyedBlock")));
        assertEquals("refusing the hole took from the reserve: " + after, STOCK, after.integer("reserve"));
    }

    /**
     * REPAIR-7: nothing to do, nothing to pay with and no energy are three answers the bay gives
     * one after another as its hull changes — each its own, none of them costing anything — and the
     * same bay, given what it lacked, does the repair.
     */
    @RedWitness(verdict = "UNDAMAGED",
            member = "TileRepairBay#look",
            at = ": RepairOutcome.UNDAMAGED);",
            replacedBy = ": RepairOutcome.NO_RECIPE);",
            fails = "a bay on a whole hull never said there is nothing to repair",
            on = "2026-10-06")
    @RedWitness(verdict = "NO_MATERIALS",
            member = "TileRepairBay#look",
            at = "settle(lacked ? RepairOutcome.NO_MATERIALS",
            replacedBy = "settle(lacked ? RepairOutcome.UNDAMAGED",
            fails = "a bay with an empty reserve never said it has nothing to pay with",
            on = "2026-10-06")
    @RedWitness(verdict = "NO_CHARGE",
            member = "TileRepairBay#look",
            at = "settle(RepairOutcome.NO_CHARGE);",
            replacedBy = "settle(RepairOutcome.UNDAMAGED);",
            fails = "a stocked bay with no energy never said it has no energy",
            on = "2026-10-06")
    @RedWitness(verdict = "the same bay then repairs",
            member = "TileRepairBay#restage",
            at = "DamageState.setStage(world, work.pos, work.stage - 1);",
            replacedBy = "",
            fails = "the same bay, given energy, never took the stage off",
            on = "2026-10-06")
    @RedWitness(verdict = "a refusal costs nothing",
            member = "TileRepairBay#look",
            at = "settle(RepairOutcome.NO_CHARGE);",
            replacedBy = "decrStackSize(0, 1); settle(RepairOutcome.NO_CHARGE);",
            fails = "a refusal took from the reserve or mended the block anyway",
            on = "2026-10-15")
    @Pins("REPAIR-7")
    @Test
    public void everyRefusalOfTheBayIsItsOwnAnswerAndCostsNothing() throws Exception {
        Hull hull = hull("the hull a starved bay stands on");
        int[] target = hull.at(0, 0, -4);
        int[] bay = hull.at(-2, 0, -2);
        placeAboard(hull, target, HULL_BLOCK);

        long mark = events.markInstrumented();
        placeAboard(hull, bay, BAY);
        Reply spec = readBay(bay);
        int look = 2 * spec.integer("lookIntervalTicks");
        events.awaitRecordWithFields(mark, "repair_bay_outcome",
                "a bay on a whole hull never said there is nothing to repair", look,
                "bay", xyz(bay), "to", "UNDAMAGED");

        long hit = events.markInstrumented();
        Reply struck = shoot(hull, target, 1, 323005);
        requireArranged("the target must be damaged and standing: " + struck,
                struck.integer("stage") >= 1 && HULL_BLOCK.equals(struck.text("block")));
        events.awaitRecordWithFields(hit, "repair_bay_outcome",
                "a bay with an empty reserve never said it has nothing to pay with", look,
                "bay", xyz(bay), "to", "NO_MATERIALS");

        long stocked = events.markInstrumented();
        stock(bay);
        events.awaitRecordWithFields(stocked, "repair_bay_outcome",
                "a stocked bay with no energy never said it has no energy", look,
                "bay", xyz(bay), "to", "NO_CHARGE");
        Reply starved = readBay(bay);
        Reply untouched = stage(target);
        assertTrue("a refusal took from the reserve or mended the block anyway: " + starved + ", " + untouched,
                starved.integer("reserve") == STOCK && untouched.integer("stage") == struck.integer("stage"));

        long powered = events.markInstrumented();
        arrange("stellurgytest energy inject 0 " + xyz(bay, " ") + " " + 2 * spec.integer("energyPerStage"));
        events.awaitRecordWithFields(powered, "block_stage_set",
                "the same bay, given energy, never took the stage off", workBudget(spec,
                        spec.integer("energyPerStage") * struck.integer("stage")),
                "pos", xyz(target), "to", "0");
    }

    /**
     * How long the bay that was unloaded is given to say so. A chunk drop marks its tiles for
     * removal and the world tells each one at its next tile pass, so the record lands on the next
     * world tick; five ticks is the one read step a wait takes anyway. A deadline, not a dose.
     */
    private static final int NEXT_TILE_PASS_BUDGET_TICKS = 5;
    /**
     * How long a hull nobody holds is given to unload, or a held one to load again, with its bay: the
     * substrate queues the unload of a ship no player is near on its next tick and its claimed chunks go
     * on the provider's sweeps after it; the load is a background load. A deadline, not an estimate.
     */
    private static final int SHIP_UNLOAD_LINK_TICKS = 400;
    private static final String SPARE_ENGINE = "stellurgy:rocketmotor";
    /** Its item is a skull, not a block — so no reserve of finished blocks can ever pay for one. */
    private static final String NO_FINISHED_BLOCK = "minecraft:skull";

    /**
     * REPAIR-8: a bay unloaded in the middle of a job resumes it where it stopped — the energy it
     * had already put in comes back with it, and nothing is added or charged for its being away —
     * so the whole job costs exactly one job's energy, unload and all.
     *
     * <p>The subject is a hole, the longest single step a bay takes. The middle of it is reached by
     * ENERGY, not by time: the bay is given half the job, puts it all in and says {@code NO_CHARGE}
     * with the hole still open and the half banked. The unload is the bay's chunk saved, dropped and
     * read back from disk — once its hull has been let go, because a loaded ship holds its chunks
     * (C11 PORT-15). The bay that resumes is a NEW object built from what the old one wrote, which is
     * the whole of what an unload asks of it; then the rest of the energy is handed in.</p>
     *
     * <p>What it does NOT see: time spent away (nothing of the bay's runs while unloaded and it keeps
     * no clock, so there is no decision of ours in that interval to observe). Which of the two takes
     * the bay's chunk away — its hull's unload, or the cycle call that follows it — is not the subject
     * either: measured 2026-10-15 on this tree the hull's unload already took it (the cycle found it
     * {@code wasLoaded:false} and read it back), where on the earlier merge it stayed loaded until the
     * call.</p>
     */
    @RedWitness(verdict = "the bay came back with the progress it left with",
            member = "BayEngine#writeTo",
            at = "nbt.setInteger(NBT_BANKED, banked);",
            replacedBy = "",
            fails = "the bay came back with different progress than it left with",
            on = "2026-10-15")
    @RedWitness(verdict = "the bay started on the hole",
            member = "TileRepairBay#look",
            at = "settle(RepairOutcome.REPAIRING);",
            replacedBy = "settle(RepairOutcome.UNDAMAGED);",
            fails = "the bay never started on the hole",
            on = "2026-10-06")
    @RedWitness(verdict = "the resumed hole is finished",
            member = "TileRepairBay#rebuild",
            at = "int slot = BayEngine.slotOf(work.step.price(), reserveHandler);",
            replacedBy = "if (work != null) { return false; } int slot = BayEngine.slotOf(work.step.price(), reserveHandler);",
            fails = "the bay never finished the hole it resumed",
            on = "2026-10-15")
    @RedWitness(verdict = "one job's energy",
            member = "TileRepairBay#readFromNBT",
            at = "charge.stored = nbt.getInteger(NBT_ENERGY);",
            replacedBy = "charge.stored = nbt.getInteger(NBT_ENERGY) - 1000;",
            fails = "the job cost other than one job's energy across the unload",
            on = "2026-10-15")
    @Pins("REPAIR-8")
    @Test
    public void aBayUnloadedMidJobResumesItAndIsBilledForOneJob() throws Exception {
        Hull hull = hull("the hull with a working bay that unloads");
        int[] target = hull.at(0, 0, -4);
        int[] bay = hull.at(-2, 0, -2);
        placeAboard(hull, target, HULL_BLOCK);
        placeAboard(hull, bay, BAY);
        stock(bay);
        Reply spec = readBay(bay);
        int holeMaxStage = stage(target).integer("maxStage");
        // A hole is a block's whole run of stages, each at production's price per stage.
        int job = spec.integer("energyPerStage") * holeMaxStage;
        // HALF the job first: the bay puts it all in and stops on NO_CHARGE with the hole still a
        // hole — the middle of the job, reached on the bay's own record rather than on a clock.
        int firstHalf = job / 2;
        Reply fed = arrange("stellurgytest energy inject 0 " + xyz(bay, " ") + " " + firstHalf);
        requireArranged("the bay must take the whole charge it was handed: " + fed,
                fed.integer("accepted") == firstHalf);
        Reply before = readBay(bay);

        long mark = events.markInstrumented();
        Reply hole = shoot(hull, target, holeMaxStage, 323006);
        requireArranged("the target must be a hole that remembers it was " + HULL_BLOCK + ": " + hole,
                "minecraft:air".equals(hole.text("block")) && HULL_BLOCK.equals(hole.text("destroyedBlock")));
        events.awaitRecordWithFields(mark, "repair_bay_outcome", "the bay never started on the hole",
                2 * spec.integer("lookIntervalTicks"), "bay", xyz(bay), "to", "REPAIRING");
        // The middle of the job is arrangement: the subject is what the bay does after it.
        dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() -> events.awaitRecordWithFields(mark,
                "repair_bay_outcome", "the bay never ran out of the half it was given",
                workBudget(spec, job), "bay", xyz(bay), "to", "NO_CHARGE"));

        // The unload: a loaded ship holds its chunks (C11 PORT-15), so the hull is let go first, and
        // its bay's chunk is then saved, dropped and read back from disk in one call.
        long cycled = events.markInstrumented();
        Reply cycle;
        ShipReadiness.letShipsUnload(c -> arrange(c).raw(),
                "the subject is a bay whose chunk unloads in the middle of a job; its hull must not hold it");
        try {
            dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() -> events.awaitRecordWithField(cycled,
                    "ship_unloaded", "vsShip", hull.ship.vsShip,
                    "the hull nobody holds never unloaded", SHIP_UNLOAD_LINK_TICKS));
            cycle = arrange("stellurgytest chunk cycle 0 " + (bay[0] >> 4) + " " + (bay[2] >> 4));
        } finally {
            ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "the hull must load again for the bay to resume");
        }
        requireArranged("the bay's chunk must be gone — with its hull or by the cycle — and read back as a"
                        + " new chunk: " + cycle,
                (cycle.bool("dropped") || !cycle.bool("wasLoaded")) && cycle.bool("reloaded")
                        && !cycle.bool("sameInstance"));
        // The world tells a tile it unloaded and that it loaded, whatever the tile is: both are links,
        // and the banked energy they carry is the verdict.
        String unloaded = dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() ->
                events.awaitRecordWithFields(cycled, "repair_bay_unloaded",
                        "the bay whose chunk was dropped was never told it unloaded", NEXT_TILE_PASS_BUDGET_TICKS,
                        "bay", xyz(bay)));
        requireArranged("the bay must unload with energy put into the hole, or nothing here is resumed: "
                + unloaded, Events.number(unloaded, "banked") > 0);
        // The reload is inside the cycle call, so its record is already written.
        String loaded = dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() ->
                events.awaitRecordWithFields(cycled, "repair_bay_loaded",
                        "the bay read back from its chunk never entered the world", NEXT_TILE_PASS_BUDGET_TICKS,
                        "bay", xyz(bay)));
        assertEquals("the bay came back with different progress than it left with — its unload said "
                + unloaded + ", its return said " + loaded,
                (long) Events.number(unloaded, "banked"), (long) Events.number(loaded, "banked"));

        // The rest, and more than the rest: a restart must show as a larger bill, not as a bay that
        // stalls for want of energy.
        int secondPart = 2 * job - firstHalf;
        Reply refed = arrange("stellurgytest energy inject 0 " + xyz(bay, " ") + " " + secondPart);
        requireArranged("the bay must take the rest of its charge: " + refed, refed.integer("accepted") == secondPart);
        events.awaitRecordWithFields(cycled, "repair_bay_rebuilt", "the bay never finished the hole it resumed",
                workBudget(spec, job), "pos", xyz(target), "placed", "true");
        Reply after = readBay(bay);
        assertEquals("the job cost other than one job's energy across the unload: " + before + " + "
                        + secondPart + " -> " + after,
                job, before.integer("energy") + secondPart - after.integer("energy"));
    }

    /**
     * REPAIR-14 (and REPAIR-12, which it presupposes): two bays on one hull with two holes each
     * take one, and both work at once — each started on its own before the other had finished, so
     * the hull mends twice as fast rather than one hole after the other.
     *
     * <p>Asserted as an ORDER of production's own records, never as a duration: each bay's
     * {@code REPAIRING} came before the OTHER bay's rebuild. Holes, the longest single step, so two
     * bays starting a look apart still overlap by most of a job.</p>
     */
    @RedWitness(verdict = "both holes are rebuilt",
            member = "TileRepairBay#rebuild",
            at = "int slot = BayEngine.slotOf(work.step.price(), reserveHandler);",
            replacedBy = "if (work != null) { return false; } int slot = BayEngine.slotOf(work.step.price(), reserveHandler);",
            fails = "the bays never rebuilt both holes",
            on = "2026-10-06")
    @RedWitness(verdict = "both holes worked at once",
            member = "BlockDamageSavedData#claim",
            at = "if (held != null && held.holder != holder && now - held.renewedAt <= 1) {",
            replacedBy = "if (claims.values().stream().anyMatch(c -> c.holder != holder && now - c.renewedAt <= 1)) {",
            fails = "the two holes were not worked at once by two bays",
            on = "2026-10-15")
    @Pins({"REPAIR-12", "REPAIR-14"})
    @Test
    public void twoBaysOnOneHullWorkTwoHolesAtOnce() throws Exception {
        Hull hull = hull("the hull two repair bays mend together");
        int[] firstHole = hull.at(0, 0, -4);
        int[] secondHole = hull.at(2, 0, -4);
        int[] left = hull.at(-2, 0, -2);
        int[] right = hull.at(2, 0, -2);
        placeAboard(hull, firstHole, HULL_BLOCK);
        placeAboard(hull, secondHole, HULL_BLOCK);
        placeAboard(hull, left, BAY);
        placeAboard(hull, right, BAY);
        stock(left);
        stock(right);
        Reply spec = readBay(left);
        int holeMaxStage = stage(firstHole).integer("maxStage");
        // Unpowered while the holes are made, so neither bay can start before both exist.
        Reply first = shoot(hull, firstHole, holeMaxStage, 323007);
        Reply second = shoot(hull, secondHole, holeMaxStage, 323008);
        requireArranged("both targets must be holes with a record: " + first + ", " + second,
                first.bool("wasDestroyed") && second.bool("wasDestroyed")
                        && "minecraft:air".equals(first.text("block")) && "minecraft:air".equals(second.text("block")));

        int job = spec.integer("energyPerStage") * holeMaxStage;
        long mark = events.markInstrumented();
        arrange("stellurgytest energy inject 0 " + xyz(left, " ") + " " + 2 * job);
        arrange("stellurgytest energy inject 0 " + xyz(right, " ") + " " + 2 * job);
        String rebuilt = events.awaitMatching(mark, "repair_bay_rebuilt",
                reply -> Events.anyRecordHasAll(reply, "pos", xyz(firstHole), "placed", "true")
                        && Events.anyRecordHasAll(reply, "pos", xyz(secondHole), "placed", "true"),
                "carrying placed = true for both holes", "the bays never rebuilt both holes",
                2 * workBudget(spec, job));
        String one = Events.recordsWhereAll(rebuilt, "pos", xyz(firstHole), "placed", "true").get(0);
        String two = Events.recordsWhereAll(rebuilt, "pos", xyz(secondHole), "placed", "true").get(0);
        String outcomes = events.since(mark, "repair_bay_outcome");
        long oneStarted = firstRepairingTick(outcomes, Events.text(one, "bay"));
        long twoStarted = firstRepairingTick(outcomes, Events.text(two, "bay"));
        assertTrue("the two holes were not worked at once by two bays: rebuilt " + one + " and " + two
                        + " after " + outcomes,
                !Events.text(one, "bay").equals(Events.text(two, "bay"))
                        && oneStarted < (long) Events.number(two, "tick")
                        && twoStarted < (long) Events.number(one, "tick"));
    }

    /**
     * Wear kept in a TILE is in the bay's work, and is paid for like any block: a worn engine aboard
     * is restaged in place, and one spare engine buys as many stages as an engine has — more than a
     * plain block's four, so a credit counted in the wrong home's stages shows as a second engine
     * drawn.
     */
    @RedWitness(verdict = "one spare pays an engine's stages",
            member = "BayEngine#holdCredit",
            at = "credits.put(step.creditKey(), step.creditPerSpare);",
            replacedBy = "credits.put(step.creditKey(), DamageState.DEFAULT_MAX_STAGE);",
            fails = "one spare engine did not pay for every stage an engine has",
            on = "2026-10-06")
    @RedWitness(verdict = "the engine is restaged",
            member = "TileRepairBay#damagedIn",
            at = "found.add(at);",
            replacedBy = "",
            fails = "the bay never restaged the worn engine",
            on = "2026-10-15")
    @Pins("REPAIR-1")
    @Test
    public void aWornEngineAboardIsRestagedByTheBayOutOfOneSpare() throws Exception {
        Hull hull = hull("the hull with a worn engine");
        int[] engine = hull.at(0, 0, -4);
        int[] bay = hull.at(-2, 0, -2);
        placeAboard(hull, engine, SPARE_ENGINE);
        placeAboard(hull, bay, BAY);
        placeAboard(hull, offset(bay, 0, 1, 0), POWER);
        Reply stocked = arrange("stellurgytest repairbay stock 0 " + xyz(bay, " ") + " " + SPARE_ENGINE + " " + STOCK);
        requireArranged("the reserve did not take the spare engines it was handed: " + stocked,
                stocked.integer("inserted") == STOCK);
        Reply spec = readBay(bay);
        int engineStages = stage(engine).integer("maxStage");
        // More stages than a plain block has, and fewer than the engine has: all of them must come
        // out of ONE spare, which a credit of a plain block's stages could not cover.
        int worn = DamageState.DEFAULT_MAX_STAGE + 2;
        requireArranged("the engine must have more stages than a plain block plus two, or one spare"
                + " covering them all says nothing (it has " + engineStages + ")", engineStages > worn);

        long mark = events.markInstrumented();
        Reply struck = shoot(hull, engine, worn, 323009);
        Reply records = read("stellurgytest damage records 0 " + xyz(engine, " ") + " " + xyz(engine, " "));
        requireArranged("the engine must be worn, standing, and carry its wear in its own tile — not in"
                + " the damage map: " + struck + ", " + records,
                struck.integer("stage") == worn && SPARE_ENGINE.equals(struck.text("block"))
                        && records.integer("count") == 0);
        events.awaitRecordWithFields(mark, "block_stage_set", "the bay never restaged the worn engine",
                workBudget(spec, spec.integer("energyPerStage") * worn), "pos", xyz(engine), "to", "0");
        Reply after = readBay(bay);
        assertEquals("one spare engine did not pay for every stage an engine has: " + after,
                STOCK - 1, after.integer("reserve"));
    }

    /**
     * REPAIR-7 on the bay: a damaged block that no finished block stands for — its item is a skull,
     * not a block — is refused as having no price at all, which is not the same answer as a price
     * that is merely not on hand. Asked of a bay that is powered and stocked, so neither of the other
     * two refusals can be the answer.
     */
    @RedWitness(member = "BayEngine#restage",
            at = "return isFinishedBlock(price) ? Optional.of(new Step(price, maxStage)) : Optional.empty();",
            replacedBy = "return !price.isEmpty() ? Optional.of(new Step(price, maxStage)) : Optional.empty();",
            fails = "a bay meeting a block no finished block stands for never said it has no price",
            on = "2026-10-06")
    @Pins("REPAIR-7")
    @Test
    public void aDamagedBlockNoFinishedBlockStandsForIsRefusedAsUnpriced() throws Exception {
        Hull hull = hull("the hull with a damaged skull aboard");
        int[] target = hull.at(0, 0, -4);
        int[] bay = hull.at(-2, 0, -2);
        placeAboard(hull, target, NO_FINISHED_BLOCK);
        placeAboard(hull, bay, BAY);
        placeAboard(hull, offset(bay, 0, 1, 0), POWER);
        stock(bay);
        Reply spec = readBay(bay);

        long mark = events.markInstrumented();
        Reply struck = shoot(hull, target, 1, 323010);
        requireArranged("the skull must be damaged and standing: " + struck,
                struck.integer("stage") >= 1 && NO_FINISHED_BLOCK.equals(struck.text("block")));
        events.awaitRecordWithFields(mark, "repair_bay_outcome",
                "a bay meeting a block no finished block stands for never said it has no price",
                2 * spec.integer("lookIntervalTicks"), "bay", xyz(bay), "to", "NO_RECIPE");
    }

    /**
     * The first tick {@code bay} said REPAIRING in {@code outcomes}; {@code Long.MAX_VALUE} when it
     * never did, which no rebuild's tick can follow.
     */
    private static long firstRepairingTick(String outcomes, String bay) {
        long first = Long.MAX_VALUE;
        for (String record : Events.recordsWhereAll(outcomes, "bay", bay, "to", "REPAIRING")) {
            first = Math.min(first, (long) Events.number(record, "tick"));
        }
        return first;
    }

    /** One scenario's hull, parked in its own plot, and the subspace address of its pilot seat. */
    private static final class Hull {
        private final WarShip ship;
        private final int[] seat;

        private Hull(WarShip ship, int[] seat) {
            this.ship = ship;
            this.seat = seat;
        }

        /** A subspace position of this hull, given relative to its pilot seat. */
        private int[] at(int dx, int dy, int dz) {
            return offset(seat, dx, dy, dz);
        }
    }

    private Hull hull(String what) throws Exception {
        FixtureSite site = site();
        WarShip ship = WarShip.build(events, commands(), site, null, what);
        builtHulls.add(ship);
        ship.parkAt(site.x, site.y + PARK_RISE, site.z, PARK_TOLERANCE);
        return new Hull(ship, ship.seat());
    }

    /** Put {@code block} at {@code p} and refuse unless the hull's own registry then owns {@code p}. */
    private void placeAboard(Hull hull, int[] p, String block) throws Exception {
        Reply placed = arrange(place(p, block));
        requireArranged(block + " was not placed at " + xyz(p) + ": " + placed, placed.bool("placed"));
        Reply owner = read("stellurgytest vs managed-by 0 " + xyz(p, " "));
        requireArranged(block + " at " + xyz(p) + " is not aboard hull " + hull.ship.vsShip + ": " + owner,
                owner.bool("managed") && hull.ship.vsShip.equals(owner.text("shipId")));
    }

    /**
     * Shoot {@code p}, a block of {@code hull}, for {@code stages} stages, and read it back. Aimed
     * straight down through the hull's OWN frame — from three blocks above the block to its centre,
     * both mapped into the world — so it lands on the block whatever the hull's pose. The budget is
     * what production itself charges per stage of this block, times {@code stages}: one impact
     * charges every stage it buys at the price it found on entry.
     */
    private Reply shoot(Hull hull, int[] p, int stages, long impactId) throws Exception {
        int perStage = stage(p).integer("stageCost");
        double[] from = toWorld(hull, p[0] + 0.5D, p[1] + 3.5D, p[2] + 0.5D);
        double[] to = toWorld(hull, p[0] + 0.5D, p[1] + 0.5D, p[2] + 0.5D);
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Reply shot = act("stellurgytest damage impact 0 " + from[0] + " " + from[1] + " " + from[2] + " "
                + dx / length + " " + dy / length + " " + dz / length + " " + perStage * stages
                + " KINETIC " + impactId);
        requireArranged("the shot did not land on hull " + hull.ship.vsShip + ": " + shot,
                shot.bool("onShip") && shot.integer("spent") > 0);
        return stage(p);
    }

    private double[] toWorld(Hull hull, double x, double y, double z) throws Exception {
        Reply mapped = read("stellurgytest vs to-world 0 id " + hull.ship.vsShip + " " + x + " " + y + " " + z);
        return new double[]{mapped.number("worldX"), mapped.number("worldY"), mapped.number("worldZ")};
    }

    /**
     * Ticks a bay at its own rate needs for {@code energy}, plus the look that starts it — doubled,
     * for the one-tick lag of a fed buffer and the five-tick step a wait reads at. Production's
     * numbers, as {@code repairbay read} reports them.
     */
    private static int workBudget(Reply bay, int energy) {
        return 2 * ((int) Math.ceil(energy / bay.number("rate")) + bay.integer("lookIntervalTicks"));
    }

    /** Whether a {@code repair_bay_outcome} for {@code bay} saying {@code to} came after {@code seq}. */
    private static boolean anyLaterOutcome(String reply, long seq, int[] bay, String to) {
        for (String record : Events.recordsWhereAll(reply, "bay", xyz(bay), "to", to)) {
            if ((long) Events.number(record, "seq") > seq) {
                return true;
            }
        }
        return false;
    }

    private double rateAtSize(int[] bay, int size) throws Exception {
        Reply read = readBay(bay);
        requireArranged("the bay was built to size " + size + " and production walked it as " + read,
                read.integer("size") == size);
        return read.number("rate");
    }

    private Reply readBay(int[] bay) throws Exception {
        return read("stellurgytest repairbay read 0 " + xyz(bay, " "));
    }

    private void stock(int[] bay) throws Exception {
        Reply stocked = arrange("stellurgytest repairbay stock 0 " + xyz(bay, " ") + " " + HULL_BLOCK + " " + STOCK);
        requireArranged("the reserve did not take the blocks it was handed: " + stocked,
                stocked.integer("inserted") == STOCK);
    }

    private Reply stage(int[] p) throws Exception {
        return read("stellurgytest damage stage 0 " + xyz(p, " "));
    }

    private static String place(int[] p, String block) {
        return "stellurgytest place 0 " + xyz(p, " ") + " " + block;
    }

    private static int[] offset(int[] p, int dx, int dy, int dz) {
        return new int[]{p[0] + dx, p[1] + dy, p[2] + dz};
    }

    /** A position as the bay's records write one. */
    private static String xyz(int[] p) {
        return xyz(p, ",");
    }

    private static String xyz(int[] p, String separator) {
        return p[0] + separator + p[1] + separator + p[2];
    }

    /**
     * Put {@code block} down and shoot it for {@code stages} stages, returning the stage it ended at.
     * The budget comes from what production itself charges per stage, so this survives retuning
     * instead of pinning today's number.
     */
    private int placeAndDamage(int x, int y, int z, String block, int stages, int impactId) throws Exception {
        place(x, y, z, block);
        int stageCost = extractInt(read("stellurgytest damage stage 0 " + x + " " + y + " " + z).raw(), "stageCost");
        assertTrue("no stage cost for " + block, stageCost > 0);
        String shot = act("stellurgytest damage impact 0 " + (x + 0.5) + " " + (y + 4) + " " + (z + 0.5)
                + " 0 -1 0 " + (stageCost * stages) + " KINETIC " + impactId).raw();
        assertTrue("the shot missed the subject block: " + shot, readLong(shot, "spent") > 0);
        return extractInt(read("stellurgytest damage stage 0 " + x + " " + y + " " + z).raw(), "stage");
    }

    private void place(int x, int y, int z, String block) throws Exception {
        Reply.of(arrange("stellurgytest chunk warmup 0 " + ((x - 2) >> 4) + " " + ((z - 2) >> 4) + " "
                + ((x + 20) >> 4) + " " + ((z + 2) >> 4)).raw()).requireOk("warm the site's chunks");
        Reply.of(arrange("stellurgytest fill 0 " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 1) + " "
                + (y + 6) + " " + (z + 1) + " minecraft:air").raw()).requireOk("clear the site");
        Reply.of(arrange("stellurgytest fill 0 " + x + " " + y + " " + z + " " + x + " " + y + " " + z + " "
                + block).raw()).requireOk("place " + block);
    }

    private String weld(int x, int y, int z, int charge, String material, int count) throws Exception {
        String reply = act("stellurgytest damage weld 0 " + x + " " + y + " " + z + " " + charge
                + " " + material + " " + count).raw();
        Reply.of(reply).requireOk("weld the block");
        return reply;
    }


    private static long readLong(String json, String key) {
        return Reply.of(json).longInteger(key);
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }

    private static String extractString(String json, String key) {
        return Reply.of(json).text(key);
    }

    // --- the rocket service station, repairing on its own: the bay's law, reached through a rocket ---

    /** The block a station is, by its registry id. */
    private static final String SERVICE_STATION = "stellurgy:serviceStation";
    /** What switches a station on: production runs its repair only under redstone power. */
    private static final String SWITCH = "minecraft:redstone_block";
    /**
     * How worn the motor is made. Three stages: more than one, so "a stage per step" and "to pristine
     * in one go" give different records, and fewer than a motor's stage count (asserted against
     * production's own {@code maxStage}), so one spare's credit covers the whole repair.
     */
    private static final int MOTOR_WEAR = 3;
    /**
     * Spares stocked when one should pay for everything. Two: a station that drew a spare per stage
     * would empty this before its third stage, and a station that drew none would leave it full.
     */
    private static final int SPARES = 2;
    /** The item damage a WORN spare is stocked at: any wear but none, read as a spare that is not pristine. */
    private static final int WORN_SPARE = 5;
    /** How far out from the launchpad the scenario reaches: the station stands three blocks off its west edge. */
    private static final int STATION_HALO = 3;
    /** How far above the site the subject reaches: the rocket's seat, its topmost part, at site + 5. */
    private static final int STATION_REACH = 5;

    /**
     * C20 REPAIR-11: a rocket service station with no assembler beside it repairs as the repair bay
     * does — a worn motor is taken down ONE stage per step, never to pristine in one go; the first
     * step draws one spare of the motor's own kind into credit, and that one spare pays for every
     * stage of the repair; every step takes energy. Then the station says the rocket is whole.
     *
     * <p>NOT seen: the station's screen (its answer is read where production keeps it), and a frame
     * built against the station (it stands alone, size 1). The rocket never leaves its pad, so
     * nothing here moves into another scenario's world.</p>
     */
    @RedWitness(verdict = "one stage per step",
            member = "TileRocketServiceStation#restage",
            at = "wear.setStage(wear.getStage() - 1);",
            replacedBy = "wear.setStage(0);",
            fails = "the station must take the motor down one stage per step",
            on = "2026-10-06")
    @RedWitness(verdict = "one spare drawn into credit",
            member = "BayEngine#holdCredit",
            at = "reserve.extractItem(slotOf(step.price, reserve), 1, false);",
            replacedBy = "",
            fails = "the first stage must draw exactly one spare into credit",
            on = "2026-10-06")
    @RedWitness(verdict = "each stage takes energy",
            member = "BayEngine#work",
            at = "int got = draw.applyAsInt(want);",
            replacedBy = "int got = want;",
            fails = "a stage was taken off without taking energy",
            on = "2026-10-06")
    @RedWitness(verdict = "the rocket found whole after the last stage",
            member = "TileRocketServiceStation#look",
            at = ": RepairOutcome.UNDAMAGED);",
            replacedBy = ": RepairOutcome.NO_RECIPE);",
            fails = "after the last stage the station never looked again and found the rocket whole",
            on = "2026-10-06")
    @RedWitness(verdict = "the motor reaches pristine",
            member = "TileRocketServiceStation#restage",
            at = "wear.setStage(wear.getStage() - 1);",
            replacedBy = "if (wear != null) { return false; } wear.setStage(wear.getStage() - 1);",
            fails = "the station never brought the worn motor back to pristine",
            on = "2026-10-15")
    @RedWitness(verdict = "one spare pays every stage",
            member = "BayEngine#holdCredit",
            at = "if (heldCredit(step) > 0) {",
            replacedBy = "if (heldCredit(step) > 0 && slotOf(step.price, reserve) < 0) {",
            fails = "one spare must pay for every stage of this motor's wear",
            on = "2026-10-15")
    @Pins("REPAIR-11")
    @Test
    public void aServiceStationRestagesAWornMotorAStageAtATimeOutOfOneSpare() throws Exception {
        Station station = station("a worn motor on its pad, and the service station that restages it");
        stockStation(station, 0, station.spare, 0, SPARES);
        chargeStation(station);
        Reply before = readStation(station);
        requireArranged("the station must stand alone with its reserve stocked and energy for every stage: "
                + before, before.integer("assemblersCount") == 0 && before.integer("reserve") == SPARES
                && before.integer("energy") >= MOTOR_WEAR * before.integer("energyPerStage"));

        long mark = events.markInstrumented();
        switchOn(station);
        events.awaitRecordWithFields(mark, "service_station_restaged",
                "the station never brought the worn motor back to pristine", stationDeadline(before, MOTOR_WEAR),
                "station", xyz(station.at), "part", xyz(station.part), "stage", "0");
        java.util.List<String> steps = Events.recordsWhere(events.since(mark, "service_station_restaged"),
                "station", xyz(station.at));

        java.util.List<Integer> stages = new java.util.ArrayList<>();
        for (String step : steps) {
            stages.add((int) Events.number(step, "stage"));
        }
        assertEquals("the station must take the motor down one stage per step, never to pristine in one go: "
                + steps, java.util.Arrays.asList(2, 1, 0), stages);
        assertEquals("the first stage must draw exactly one spare into credit: " + steps,
                SPARES - 1, (int) Events.number(steps.get(0), "reserve"));
        for (String step : steps) {
            assertEquals("one spare must pay for every stage of this motor's wear — credit, not a spare a stage: "
                    + steps, SPARES - 1, (int) Events.number(step, "reserve"));
        }
        double energy = before.integer("energy");
        for (String step : steps) {
            assertTrue("a stage was taken off without taking energy: " + before + " then " + steps,
                    Events.number(step, "energy") < energy);
            energy = Events.number(step, "energy");
        }

        long lastStep = (long) Events.number(steps.get(steps.size() - 1), "seq");
        int look = before.integer("lookIntervalTicks");
        events.awaitMatching(mark, "service_station_outcome",
                reply -> stationSaidAfter(reply, lastStep, station, "UNDAMAGED"),
                "carrying to = UNDAMAGED after the last stage",
                "after the last stage the station never looked again and found the rocket whole", 2 * look);
    }

    /**
     * C20 REPAIR-7 on the station: out of energy, and holding no spare that pays, are two different
     * answers, and neither spends anything; then the same station, given a spare that pays, restages
     * the motor out of it — leaving the spare that did not pay where it was — and once the rocket is
     * whole says so and spends nothing more.
     *
     * <p>The spare that does not pay is a WORN motor of the motor's own kind: a spare equals the
     * price only at the same item damage, so the rule that refuses it is the rule under test, not an
     * empty slot.</p>
     */
    @RedWitness(verdict = "NO_CHARGE",
            member = "TileRocketServiceStation#look",
            at = "settle(RepairOutcome.NO_CHARGE);",
            replacedBy = "settle(RepairOutcome.NO_MATERIALS);",
            fails = "a station with a spare that pays and no energy never said it had no energy",
            on = "2026-10-06")
    @RedWitness(verdict = "NO_MATERIALS for a worn spare",
            member = "BayEngine#slotOf",
            at = "ItemStack.areItemsEqual(held, wanted)",
            replacedBy = "ItemStack.areItemsEqualIgnoreDurability(held, wanted)",
            fails = "a station holding only a worn spare never said it had nothing to pay with",
            on = "2026-10-06")
    @RedWitness(verdict = "a refusal spends nothing",
            member = "TileRocketServiceStation#getPowerPerOperation",
            at = "return hasValidAssembler() ? 10 : 0;",
            replacedBy = "return 10;",
            fails = "a station refusing for want of a spare that pays spent something",
            on = "2026-10-06")
    @RedWitness(verdict = "one pristine spare pays",
            member = "BayEngine#holdCredit",
            at = "reserve.extractItem(slotOf(step.price, reserve), 1, false);",
            replacedBy = "reserve.extractItem(slotOf(step.price, reserve), 2, false);",
            fails = "one pristine spare must pay for the whole repair",
            on = "2026-10-06")
    @RedWitness(verdict = "refusing for want of energy costs nothing",
            member = "TileRocketServiceStation#look",
            at = "settle(RepairOutcome.NO_CHARGE);",
            replacedBy = "repairInventory.extractItem(0, 1, false); settle(RepairOutcome.NO_CHARGE);",
            fails = "a station refusing for want of energy took from its reserve or touched the motor",
            on = "2026-10-15")
    @RedWitness(verdict = "spares that pay restage the motor",
            member = "TileRocketServiceStation#restage",
            at = "wear.setStage(wear.getStage() - 1);",
            replacedBy = "if (wear != null) { return false; } wear.setStage(wear.getStage() - 1);",
            fails = "given spares that pay, the station never brought the motor back to pristine",
            on = "2026-10-15")
    @RedWitness(verdict = "whole after restaging",
            member = "TileRocketServiceStation#look",
            at = ": RepairOutcome.UNDAMAGED);",
            replacedBy = ": RepairOutcome.NO_RECIPE);",
            fails = "after restaging the motor the station never said the rocket was whole",
            on = "2026-10-15")
    @RedWitness(verdict = "nothing to repair costs nothing",
            member = "TileRocketServiceStation#look",
            at = "settle(lacked ? RepairOutcome.NO_MATERIALS",
            replacedBy = "if (!lacked && !unpriced) { draw(1); } settle(lacked ? RepairOutcome.NO_MATERIALS",
            fails = "a station with nothing to repair spent something",
            on = "2026-10-15")
    @Pins({"REPAIR-11", "INV-INFRA-09"})
    @Wait(shape = Wait.Shape.WINDOW, at = "2 * look); Reply lackingAfter = readStation(station);",
            why = "two looks of a station that keeps refusing — no record is written while its answer stays the"
                    + " same, so the two reads ARE the observation.")
    @Wait(shape = Wait.Shape.WINDOW, at = "2 * look); Reply undamagedAfter = readStation(station);",
            why = "two looks of a station with nothing to do: no record is written while its answer stays"
                    + " UNDAMAGED, so the two reads ARE the observation.")
    @Test
    public void everyRefusalOfTheServiceStationIsItsOwnAnswerAndCostsNothing() throws Exception {
        Station station = station("a worn motor on its pad, and a service station that will not always repair it");
        stockStation(station, 0, station.spare, 0, 1);
        Reply arranged = readStation(station);
        requireArranged("the station must stand alone, a spare that pays in its reserve and its battery empty: "
                + arranged, arranged.integer("assemblersCount") == 0 && arranged.integer("reserve") == 1
                && arranged.integer("energy") == 0);
        int look = arranged.integer("lookIntervalTicks");

        long starving = events.markInstrumented();
        switchOn(station);
        events.awaitRecordWithFields(starving, "service_station_outcome",
                "a station with a spare that pays and no energy never said it had no energy",
                2 * look, "station", xyz(station.at), "to", "NO_CHARGE");
        Reply starved = readStation(station);
        Reply motorStarved = readPart(station);
        assertTrue("a station refusing for want of energy took from its reserve or touched the motor: "
                + starved + " / " + motorStarved,
                starved.integer("reserve") == 1 && motorStarved.integer("stage") == MOTOR_WEAR);

        long lacking = events.markInstrumented();
        stockStation(station, 0, station.spare, WORN_SPARE, 1);
        chargeStation(station);
        events.awaitRecordWithFields(lacking, "service_station_outcome",
                "a station holding only a worn spare never said it had nothing to pay with",
                2 * look, "station", xyz(station.at), "to", "NO_MATERIALS");
        Reply lackingAt = readStation(station);
        requireArranged("the station must hold energy while it lacks a spare, or 'it spent none' means nothing: "
                + lackingAt, lackingAt.integer("energy") > 0);
        GameTicks.advance(client(), GameTicks.server(), 2 * look); Reply lackingAfter = readStation(station);
        Reply motorLacking = readPart(station);
        assertTrue("a station refusing for want of a spare that pays spent something, or touched the motor: "
                + lackingAt + " then " + lackingAfter + " / " + motorLacking,
                lackingAfter.integer("energy") == lackingAt.integer("energy")
                        && lackingAfter.integer("reserve") == 1 && motorLacking.integer("stage") == MOTOR_WEAR);

        long paying = events.markInstrumented();
        stockStation(station, 1, station.spare, 0, SPARES);
        String whole = events.awaitRecordWithFields(paying, "service_station_restaged",
                "given spares that pay, the station never brought the motor back to pristine",
                stationDeadline(lackingAt, MOTOR_WEAR), "station", xyz(station.at), "part", xyz(station.part),
                "stage", "0");
        assertEquals("one pristine spare must pay for the whole repair, beside the worn one that paid nothing: "
                + whole, SPARES, (int) Events.number(whole, "reserve"));
        long wholeSeq = (long) Events.number(whole, "seq");
        events.awaitMatching(paying, "service_station_outcome",
                reply -> stationSaidAfter(reply, wholeSeq, station, "UNDAMAGED"),
                "carrying to = UNDAMAGED after the motor was restaged",
                "after restaging the motor the station never said the rocket was whole", 2 * look);
        Reply undamaged = readStation(station);
        GameTicks.advance(client(), GameTicks.server(), 2 * look); Reply undamagedAfter = readStation(station);
        assertTrue("a station with nothing to repair spent something: " + undamaged + " then " + undamagedAfter,
                undamagedAfter.integer("energy") == undamaged.integer("energy")
                        && undamagedAfter.integer("reserve") == undamaged.integer("reserve"));
    }

    /** A service station linked to a freshly built rocket one of whose motors is worn; switched off. */
    private static final class Station {
        private final int rocket;
        /** The worn motor's position in the rocket's own storage. */
        private final int[] part;
        /** The block that motor is, which is also the item a spare of it is. */
        private final String spare;
        private final int[] at;

        private Station(int rocket, int[] part, String spare, int[] at) {
            this.rocket = rocket;
            this.part = part;
            this.spare = spare;
            this.at = at;
        }
    }

    private Station station(String what) throws Exception {
        FixtureSite site = site();
        int[] builder = RocketFixture.placeAt(site, c -> arrange(c).raw(), "simple", STATION_HALO,
                STATION_REACH, what);
        long pressed = events.mark();
        String assembled = RocketFixture.assembleBuilt(site, c -> act(c).raw(), builder);
        requireArranged("the station's rocket must assemble: " + assembled, Reply.of(assembled).ok());
        int rocket = RocketFixture.rocketBornAt(events, pressed, builder);
        builtRockets.add(rocket);
        Reply worn = arrange("stellurgytest infra inject-broken-part " + rocket + " " + MOTOR_WEAR);
        requireArranged("a motor of the rocket must be worn to " + MOTOR_WEAR + ": " + worn,
                worn.ok() && worn.integer("stage") == MOTOR_WEAR);
        int[] part = worn.blockPos("partPos");
        Reply motor = read("stellurgytest infra part-wear " + rocket + " " + xyz(part, " "));
        requireArranged("the worn motor must have more stages than its wear, so one spare can pay for all: "
                + motor, motor.ok() && motor.integer("maxStage") > MOTOR_WEAR);
        int[] at = {site.x - STATION_HALO, site.y + 1, site.z};
        arrange("stellurgytest place 0 " + xyz(at, " ") + " " + SERVICE_STATION).requireOk("place the station");
        Reply link = arrange("stellurgytest infra link 0 " + xyz(at, " ") + " " + rocket);
        requireArranged("the station must link to the rocket: " + link, link.ok() && link.bool("linked"));
        return new Station(rocket, part, motor.text("block"), at);
    }

    private void switchOn(Station station) throws Exception {
        arrange("stellurgytest place 0 " + station.at[0] + " " + (station.at[1] + 1) + " " + station.at[2]
                + " " + SWITCH).requireOk("switch the station on");
    }

    private void stockStation(Station station, int slot, String item, int damage, int count) throws Exception {
        Reply stocked = arrange("stellurgytest wear station-load 0 " + xyz(station.at, " ") + " " + slot
                + " " + item + "#" + damage + " " + count);
        requireArranged("the station's reserve must take " + count + " of " + item + "#" + damage + ": " + stocked,
                stocked.ok() && stocked.integer("damage") == damage && stocked.integer("count") == count);
    }

    /** Fill the station's battery through its energy capability, the way a cable would. */
    private void chargeStation(Station station) throws Exception {
        Reply charged = arrange("stellurgytest energy inject 0 " + xyz(station.at, " ") + " "
                + Integer.MAX_VALUE);
        requireArranged("the station must take energy: " + charged, charged.ok() && charged.integer("stored") > 0);
    }

    private Reply readStation(Station station) throws Exception {
        return read("stellurgytest infra service-state 0 " + xyz(station.at, " "));
    }

    private Reply readPart(Station station) throws Exception {
        return read("stellurgytest infra part-wear " + station.rocket + " " + xyz(station.part, " "))
                .requireOk("read the worn motor");
    }

    /**
     * A deadline for {@code stages} stages of the station's work, from production's own numbers: one
     * look, then each stage's energy at the station's rate. Doubled because it is a DEADLINE, not a
     * measurement — the wait closes the moment its record arrives, so the margin is spent only by a
     * red.
     */
    private static int stationDeadline(Reply station, int stages) {
        int perStage = (int) Math.ceil(station.integer("energyPerStage") / station.number("rate"));
        return 2 * (station.integer("lookIntervalTicks") + stages * perStage);
    }

    /** Whether a {@code service_station_outcome} of {@code station} saying {@code to} came after {@code seq}. */
    private static boolean stationSaidAfter(String reply, long seq, Station station, String to) {
        for (String record : Events.recordsWhereAll(reply, "station", xyz(station.at), "to", to)) {
            if ((long) Events.number(record, "seq") > seq) {
                return true;
            }
        }
        return false;
    }
}
