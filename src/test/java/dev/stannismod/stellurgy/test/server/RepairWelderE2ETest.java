package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The bottom rung of the repair ladder: one use of the welder takes one stage of damage off a block,
 * paid for in that block's own materials and in charge — and every way it can refuse is a different,
 * visible answer that costs the player nothing.
 *
 * <p>What is pinned here is the CONTRACT (C20 REPAIR-1, REPAIR-3, REPAIR-7), not the price list. The
 * assertions say that material left the inventory, never how much: the fraction charged per stage is
 * a tuned number, and a test that pinned it would fail the first time somebody balanced the game
 * rather than the first time somebody broke it.</p>
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
     * Pins REPAIR-1 (a repair lowers one stage or fills a hole and does nothing else).
     * Pins REPAIR-3 (material and charge leave on a repair and neither leaves on a refusal).
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
     * red-witnessed: with a charge withdrawal inserted before {@code ItemRepairWelder#weld} at {@code return Outcome.NO_MATERIALS;}'s
     * NO_MATERIALS return, this fails with "a refused repair spent charge: {...outcome:NO_MATERIALS,
     * energyBefore:100000,energyAfter:98000...}". The later refusals' verdicts were not separately
     * witnessed. 2026-09-30.
     * Pins REPAIR-3 (material and charge leave on a repair and neither leaves on a refusal).
     * Pins REPAIR-7 (a bay that cannot work says why: every refusal is its own answer).
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
