package dev.stannismod.stellurgy.test.server;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.WarShip;

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
 * <p>What is pinned here is the CONTRACT (C20 REPAIR-1, 3, 4, 5, 6, 7, 10, 13), not the price list.
 * The assertions say that material or energy left, never how much, and that one size is faster than
 * another, never by how much: the prices are tuned numbers, and a test that pinned them would fail the
 * first time somebody balanced the game rather than the first time somebody broke it.</p>
 *
 * <p>What the bay scenarios do NOT see: the bay's screen (they read the bay's answer where production
 * keeps it, not where a player reads it), and a bay that unloads and resumes (REPAIR-8) or two bays
 * draining two blocks faster than one (REPAIR-14), which nothing here measures.</p>
 */
public class RepairWelderE2ETest extends AbstractSharedServerTest {

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
     * red-witnessed: with {@code ItemRepairWelder#weld} at {@code cost.consume(player, false);} (the real material withdrawal) removed,
     * this fails with "the repair took no material — nothing may be created from nothing:
     * {...materialBefore:64,materialAfter:64...}". The stage and charge verdicts were not separately
     * witnessed. 2026-09-30, taken on the pre-change form {@code RepairCost.consume(player, cost, false);},
     * which is now the instance call named above.
     */
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
     * red-witnessed: with a charge withdrawal inserted before {@code ItemRepairWelder#weld} at {@code return RepairOutcome.NO_MATERIALS;}'s
     * NO_MATERIALS return, this fails with "a refused repair spent charge: {...outcome:NO_MATERIALS,
     * energyBefore:100000,energyAfter:98000...}". The later refusals' verdicts were not separately
     * witnessed. 2026-09-30, taken on the pre-change form {@code return Outcome.NO_MATERIALS;}, which is
     * now the shared-vocabulary return named above.
     */
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
     *
     * <p>red-witnessed: with {@code RepairCost#consume} at {@code !wanted.accepts.apply(inSlot)}
     * replaced by a match against the slot's first variant only
     * ({@code !OreDictionary.itemMatches(wanted.accepts.getMatchingStacks()[0], inSlot, false)} — the
     * shape it shipped with), this fails at "a player carrying spruce planks could not pay for a
     * crafting table, whose slots accept every plank: {...outcome:NO_MATERIALS,...materialBefore:64,
     * materialAfter:64...} expected:&lt;[REPAIRED]&gt; but was:&lt;[NO_MATERIALS]&gt;" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code ItemRepairWelder#weld} at {@code cost.consume(player, false);}
     * removed, this fails at "the repair was granted but no spruce left the inventory:
     * {...outcome:REPAIRED,...materialBefore:64,materialAfter:64...}" (2026-10-06).</p>
     */
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

    private final Events events = new Events(this::exec,
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** A hull a bay scenario built goes on ticking in the world the next scenario runs in. */
    @Before
    public void disposeOfCraftLeftByAnEarlierScenario() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, 0);
    }

    /**
     * REPAIR-10: building a bay bigger makes it strictly faster, each added size buys less than the
     * one before, and no buildable size reaches the ceiling. Asserted as ORDERINGS of the rate
     * production computes for the bay as built — its own walk of the frames, its own law — never as
     * amounts.
     *
     * <p>red-witnessed: with {@code RepairBaySize#powerPerTick} at
     * {@code return ceiling() * size / (double) (size + config.repairBayHalfRateSize);} given
     * {@code config.repairBayHalfRateSize} as its numerator in place of {@code size} (a law that
     * falls with size), this fails at "a bigger bay was not faster: sizes 1, 2, 3 worked at
     * 355.55555555555554, 320.0, 290.90909090909093" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code RepairBaySize#powerPerTick} at
     * {@code return ceiling() * size / (double) (size + config.repairBayHalfRateSize);} made linear,
     * {@code ceiling() * size / (double) config.repairBayHalfRateSize}, this fails at "the gain from
     * size 2 to 3 (50.0) did not shrink below the gain from 1 to 2 (50.0)" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code RepairBaySize#powerPerTick} at
     * {@code return ceiling() * size / (double) (size + config.repairBayHalfRateSize);} doubled, this
     * fails at "the largest buildable bay reached its ceiling:
     * {...size:257,rate:775.8490566037735,ceiling:400.0...}" (2026-10-06).</p>
     */
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
     *
     * <p>red-witnessed: with {@code TileRepairBay#look} at {@code settle(RepairOutcome.NO_STRUCTURE);}
     * and its return replaced by serving the box eight blocks round the bay, this fails at "a bay
     * standing in the world never said it has no structure to serve — no `repair_bay_outcome` carrying
     * bay = 4212,151,4020 and to = NO_STRUCTURE was recorded within 40 ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#look} at {@code settle(RepairOutcome.NO_STRUCTURE);}
     * kept but its return replaced by serving the same box, this
     * fails at "a bay that is on no ship repaired a world block: before {...stage:1...}, after
     * {...stage:0...} expected:&lt;1&gt; but was:&lt;0&gt;" (2026-10-06).</p>
     */
    @Test
    public void aBayThatIsNotOnAShipAnswersNoStructureAndLeavesTheWorldAlone() throws Exception {
        FixtureSite site = clearedSite(2, 6, "a bay in the open world beside a damaged world block");
        int[] wall = {site.x + 3, site.y + 1, site.z};
        arrange(place(wall, HULL_BLOCK));
        Reply fresh = stage(wall);
        arrange("stellurgytest damage impact 0 " + (wall[0] + 0.5D) + " " + (wall[1] + 3.5D) + " "
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
        // WINDOW: two of the bay's looks, each one a chance to serve the wall, between the two reads.
        GameTicks.advance(client(), GameTicks.server(), 2 * able.integer("lookIntervalTicks"));
        Reply after = stage(wall);
        assertEquals("a bay that is on no ship repaired a world block: before " + damaged + ", after "
                + after, damaged.integer("stage"), after.integer("stage"));
    }

    /**
     * REPAIR-13: two bays on one hull and one damaged block — one of them works it, and the other
     * neither puts energy into it nor pays for it.
     *
     * <p>red-witnessed: with {@code TileRepairBay#look} at {@code : RepairOutcome.UNDAMAGED);} answering
     * NO_RECIPE instead, this fails at "both bays must be looking at their hull and finding it whole
     * before it is damaged — no `repair_bay_outcome` carrying to = UNDAMAGED for both bays was recorded
     * within 40 ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#restage} at
     * {@code DamageState.setStage(world, work.pos, work.stage - 1);} removed, this fails at "neither bay
     * repaired the damaged block — no `block_stage_set` carrying pos = 19200001,129,102396 and to = 0
     * was recorded within 130 ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code BlockDamageSavedData#claim} at {@code return false;} removed (every
     * claim granted), this fails at "both bays put energy into the one damaged stage: left
     * {...energy:4000...} -> {...energy:2000,reserve:3...}, right {...energy:4000...} ->
     * {...energy:3645,reserve:4...}" (2026-10-06).</p>
     *
     * <p>red-witnessed: with a reserve draw inserted into {@code TileRepairBay#look}'s branch at
     * {@code if (!data.claim(candidate, this, now))}, this fails at "the bay that did not work the stage
     * paid for it anyway: {...reserve:4...} -> {...reserve:2...} expected:&lt;4&gt; but was:&lt;2&gt;"
     * (2026-10-06).</p>
     */
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
     *
     * <p>red-witnessed: with {@code TileRepairBay#rebuild} answering false before
     * {@code if (slot < 0 || !world.setBlockState(work.pos, work.place, 3))} whenever the reserve holds
     * the block, this fails at "the bay never rebuilt the hole in its own hull — no `repair_bay_rebuilt`
     * carrying pos = 19200001,129,51196 and placed = true was recorded within 400 ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#rebuild} at
     * {@code world.setBlockState(work.pos, work.place, 3)} given {@code Blocks.STONE} in place of
     * {@code work.place},
     * this fails at "the hole was filled with something other than the block its record names:
     * {...block:minecraft:stone...} expected:&lt;minecraft:[iron_block]&gt; but was:&lt;minecraft:[stone]&gt;"
     * (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#rebuild} at
     * {@code BlockDamageSavedData.get(world).clear(work.pos);} removed, this fails at "the hole's record
     * survived its own rebuild — a second free block on the next pass: {...stage:4,...
     * block:minecraft:iron_block,wasDestroyed:true...}" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#rebuild} at {@code decrStackSize(slot, 1);} removed,
     * this fails at "the hole was rebuilt and nothing left the reserve: {...placed:true,...reserve:4}"
     * (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#look} at {@code : RepairOutcome.UNDAMAGED);} answering
     * NO_RECIPE instead, this fails at "after the rebuild the bay never looked again and found its hull
     * whole — no `repair_bay_outcome` carrying to = UNDAMAGED after the rebuild was recorded within 40
     * ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with a reserve draw inserted before {@code TileRepairBay#look}'s
     * {@code settle(lacked ? RepairOutcome.NO_MATERIALS} when it is about to answer UNDAMAGED, this fails
     * at "the bay's look after the rebuild spent from the reserve: {...reserve:2} then {...reserve:1...}
     * expected:&lt;2&gt; but was:&lt;1&gt;" (2026-10-06).</p>
     */
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
     *
     * <p>red-witnessed: with {@code Work#at} at
     * {@code if (block == null || block == net.minecraft.init.Blocks.AIR)} reduced to the null test
     * (the bay then prices the hole as an item-less block and answers NO_RECIPE), this fails at "the bay
     * never said the hole is one it may not fill — no `repair_bay_outcome` carrying bay =
     * 19199999,129,153598 and to = UNFILLABLE was recorded within 40 ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code data.clear(pos);} inserted in {@code Work#at}'s branch at
     * {@code if (block == null || block == net.minecraft.init.Blocks.AIR)}, this fails at "the
     * refused hole was filled or its record dropped: {...block:minecraft:air,wasDestroyed:false,
     * destroyedBlock:...}" (2026-10-06).</p>
     *
     * <p>red-witnessed: with a reserve draw inserted into {@code TileRepairBay#look}'s branch at
     * {@code unfillable = true;}, this fails at "refusing the hole took from the reserve:
     * {...outcome:UNFILLABLE,energy:100000,reserve:1...} expected:&lt;4&gt; but was:&lt;1&gt;"
     * (2026-10-06).</p>
     */
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
        // WINDOW: two looks of a POWERED, stocked bay at the hole, between power and the reads below.
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
     *
     * <p>red-witnessed: with {@code TileRepairBay#look} at {@code : RepairOutcome.UNDAMAGED);} answering
     * NO_RECIPE instead, this fails at "a bay on a whole hull never said there is nothing to repair — no
     * `repair_bay_outcome` carrying bay = 19199999,129,204798 and to = UNDAMAGED was recorded within 40
     * ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#look} at {@code settle(lacked ? RepairOutcome.NO_MATERIALS}
     * answering UNDAMAGED instead, this fails at "a bay with an empty reserve never said it has nothing
     * to pay with — no `repair_bay_outcome` carrying bay = 19199999,129,204798 and to = NO_MATERIALS was
     * recorded within 40 ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#look} at {@code settle(RepairOutcome.NO_CHARGE);}
     * answering UNDAMAGED instead, this fails at "a stocked bay with no energy never said it has no
     * energy — no `repair_bay_outcome` carrying bay = 19199999,129,204798 and to = NO_CHARGE was recorded
     * within 40 ticks" (2026-10-06).</p>
     *
     * <p>red-witnessed: with a reserve draw inserted before {@code TileRepairBay#look} at
     * {@code settle(RepairOutcome.NO_CHARGE);}, this fails at "a
     * refusal took from the reserve or mended the block anyway: {...outcome:NO_CHARGE,energy:0,
     * reserve:3...}, {...stage:1...}" (2026-10-06). Its stage half was not separately witnessed.</p>
     *
     * <p>red-witnessed: with {@code TileRepairBay#restage} at
     * {@code DamageState.setStage(world, work.pos, work.stage - 1);} removed, this fails at "the same
     * bay, given energy, never took the stage off — no `block_stage_set` carrying pos =
     * 19200001,129,204796 and to = 0 was recorded within 130 ticks" (2026-10-06).</p>
     */
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
        WarShip ship = WarShip.build(events, this::exec, site, null, what);
        ship.parkAt(site.x, site.y + PARK_RISE, site.z, PARK_TOLERANCE);
        return new Hull(ship, ship.seat());
    }

    /** Put {@code block} at {@code p} and refuse unless the hull's own registry then owns {@code p}. */
    private void placeAboard(Hull hull, int[] p, String block) throws Exception {
        Reply placed = arrange(place(p, block));
        requireArranged(block + " was not placed at " + xyz(p) + ": " + placed, placed.bool("placed"));
        Reply owner = arrange("stellurgytest vs managed-by 0 " + xyz(p, " "));
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
        Reply shot = arrange("stellurgytest damage impact 0 " + from[0] + " " + from[1] + " " + from[2] + " "
                + dx / length + " " + dy / length + " " + dz / length + " " + perStage * stages
                + " KINETIC " + impactId);
        requireArranged("the shot did not land on hull " + hull.ship.vsShip + ": " + shot,
                shot.bool("onShip") && shot.integer("spent") > 0);
        return stage(p);
    }

    private double[] toWorld(Hull hull, double x, double y, double z) throws Exception {
        Reply mapped = arrange("stellurgytest vs to-world 0 id " + hull.ship.vsShip + " " + x + " " + y + " " + z);
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
        return arrange("stellurgytest repairbay read 0 " + xyz(bay, " "));
    }

    private void stock(int[] bay) throws Exception {
        Reply stocked = arrange("stellurgytest repairbay stock 0 " + xyz(bay, " ") + " " + HULL_BLOCK + " " + STOCK);
        requireArranged("the reserve did not take the blocks it was handed: " + stocked,
                stocked.integer("inserted") == STOCK);
    }

    private Reply stage(int[] p) throws Exception {
        return arrange("stellurgytest damage stage 0 " + xyz(p, " "));
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
        int stageCost = extractInt(exec("stellurgytest damage stage 0 " + x + " " + y + " " + z), "stageCost");
        assertTrue("no stage cost for " + block, stageCost > 0);
        String shot = exec("stellurgytest damage impact 0 " + (x + 0.5) + " " + (y + 4) + " " + (z + 0.5)
                + " 0 -1 0 " + (stageCost * stages) + " KINETIC " + impactId);
        assertTrue("the shot missed the subject block: " + shot, readLong(shot, "spent") > 0);
        return extractInt(exec("stellurgytest damage stage 0 " + x + " " + y + " " + z), "stage");
    }

    private void place(int x, int y, int z, String block) throws Exception {
        Reply.of(exec("stellurgytest chunk warmup 0 " + ((x - 2) >> 4) + " " + ((z - 2) >> 4) + " "
                + ((x + 20) >> 4) + " " + ((z + 2) >> 4))).requireOk("warm the site's chunks");
        Reply.of(exec("stellurgytest fill 0 " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 1) + " "
                + (y + 6) + " " + (z + 1) + " minecraft:air")).requireOk("clear the site");
        Reply.of(exec("stellurgytest fill 0 " + x + " " + y + " " + z + " " + x + " " + y + " " + z + " "
                + block)).requireOk("place " + block);
    }

    private String weld(int x, int y, int z, int charge, String material, int count) throws Exception {
        String reply = exec("stellurgytest damage weld 0 " + x + " " + y + " " + z + " " + charge
                + " " + material + " " + count);
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
}
