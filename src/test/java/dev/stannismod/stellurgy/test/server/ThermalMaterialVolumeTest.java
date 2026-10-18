package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * How much substance a block IS, read off the block itself.
 *
 * <p>A slug's capacity and, later, a block's melting point are both {@code rho * c * dT * V}, and
 * this is where the V comes from: the block's own collision boxes, in the world, as the player built
 * it. Nothing about the volume is authored - which is why these scenarios are worth a real server
 * rather than a table lookup. The shapes are chosen to separate the two things that could stand in
 * for each other:</p>
 *
 * <ul>
 *   <li>a <b>slab</b> is half a block, and its bounding box says so too - so it alone cannot tell
 *       the collision read from the outline read;</li>
 *   <li><b>stairs</b> are three quarters, and their bounding box says a FULL cube
 *       ({@code Block.getBoundingBox} defaults to it). This is the scenario that separates them, and
 *       a build that measured the outline reports a whole block here.</li>
 * </ul>
 */
public class ThermalMaterialVolumeTest extends AbstractSharedServerTest {

    /** One cubic metre in the millilitres the probe reports. */
    private static final long WHOLE_BLOCK = 1_000_000L;

    /**
     * Place one block, one above this scenario's own site once the site is proved empty, then read
     * what stands there. The read names the block it found, and that is what proves the placement
     * took — not {@code placed}, which is false for air placed into air.
     */
    private Reply placeAndRead(String block) throws Exception {
        FixtureSite site = clearedSite(1, 2, block + " standing alone in open air");
        String at = site.dim + " " + site.x + " " + (site.y + 1) + " " + site.z;
        arrange("stellurgytest place " + at + " " + block).requireOk();
        Reply read = read("stellurgytest heat material " + at).requireOk();
        requireArranged("premise: the position must hold " + block + ": " + read,
                block.equals(read.text("block")));
        return read;
    }

    @RedWitness(verdict = "a cubic metre", member = "ThermalMaterials#volumeMillilitres",
            at = "return (long) (cubicMetres * 1_000_000L);",
            replacedBy = "return (long) (cubicMetres * 2_000_000L);",
            fails = "a full block is a cubic metre", on = "2026-10-18")
    @RedWitness(verdict = "a capacity", member = "ThermalMaterials#slugCapacity",
            at = "return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit;",
            replacedBy = "return 0L;",
            fails = "and iron is a substance the table knows, so it has a capacity", on = "2026-10-18")
    @Test
    public void aWholeBlockIsAWholeCubicMetreOfItsSubstance() throws Exception {
        Reply iron = placeAndRead("minecraft:iron_block");

        assertEquals("a full block is a cubic metre: " + iron, WHOLE_BLOCK,
                iron.longInteger("volumeMilliLitres"));
        assertTrue("and iron is a substance the table knows, so it has a capacity: " + iron,
                iron.longInteger("capacity") > 0);
    }

    @RedWitness(member = "ThermalMaterials#volumeMillilitres",
            at = "return (long) (cubicMetres * 1_000_000L);",
            replacedBy = "return (long) (cubicMetres * 2_000_000L);",
            fails = "half the shape is half the substance", on = "2026-10-18")
    @Test
    public void aSlabIsHalfABlockOfIt() throws Exception {
        Reply slab = placeAndRead("minecraft:stone_slab");

        assertEquals("half the shape is half the substance: " + slab, WHOLE_BLOCK / 2,
                slab.longInteger("volumeMilliLitres"));
    }

    /**
     * The discriminator: the outline of a staircase is a full cube, and its substance is not.
     */
    @RedWitness(verdict = "not a whole block", member = "ThermalMaterials#volumeMillilitres",
            at = "return (long) (cubicMetres * 1_000_000L);",
            replacedBy = "return (long) (cubicMetres * 2_000_000L);",
            fails = "a staircase must not read as a whole block", on = "2026-10-18")
    @RedWitness(verdict = "three quarters", member = "ThermalMaterials#volumeMillilitres",
            at = "cubicMetres += (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);",
            replacedBy = "cubicMetres += (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ); break;",
            fails = "it is the half slab plus the quarter step", on = "2026-10-18")
    @Test
    public void stairsAreThreeQuartersBecauseTheirCOLLISIONSaysSoAndTheirOutlineDoesNot()
            throws Exception {
        Reply stairs = placeAndRead("minecraft:stone_stairs");

        long volume = stairs.longInteger("volumeMilliLitres");
        assertTrue("a staircase must not read as a whole block - that is what its bounding box says,"
                + " and the bounding box is not what it is made of: " + stairs, volume < WHOLE_BLOCK);
        assertEquals("it is the half slab plus the quarter step: " + stairs,
                3 * WHOLE_BLOCK / 4, volume);
    }

    /**
     * The same half-block, held rather than placed. Nothing in the ore dictionary describes a stone
     * slab, so without this the thing in your hand is nothing at all - while the identical block on
     * the ground is half a cubic metre.
     */
    @LegacyWitness(prose = "with {@code ThermalMaterials#volumeMillilitres} at {@code return fromBlock * stack.getCount();} doubling the volume an item's block"
            + " answers for: \"an item the ore dictionary cannot name still has the shape of what it places: …"
            + " expected:&lt;500000&gt; but was:&lt;1000000&gt;\", 2026-09-30.")
    @Test
    public void aSlabInTheHandIsTheSameHalfBlockAsASlabOnTheGround() throws Exception {
        Reply held = read("stellurgytest heat item minecraft:stone_slab").requireOk();

        assertEquals("an item the ore dictionary cannot name still has the shape of what it places: "
                + held, WHOLE_BLOCK / 2, held.longInteger("volumeMilliLitres"));
    }

    /**
     * The substance chain's second link. Vanilla names no stone in the ore dictionary, so without the
     * block's own {@code Material} a stone slab has a size and no identity - and a size alone answers
     * nothing, because capacity is the two multiplied.
     */
    @RedWitness(verdict = "stone", member = "ThermalMaterials#byVanillaMaterial",
            at = "return byName(VANILLA_MATERIAL_NAMES.get(vanilla));",
            replacedBy = "return null;",
            fails = "stone must resolve through the block's own vanilla material", on = "2026-10-18")
    @RedWitness(verdict = "a capacity", member = "ThermalMaterials#slugCapacity",
            at = "return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit;",
            replacedBy = "return 0L;",
            fails = "and having both halves, it must have a capacity", on = "2026-10-18")
    @Test
    public void aBlockTheOreDictionaryNeverNamedStillKnowsWhatItIsMadeOf() throws Exception {
        Reply slab = placeAndRead("minecraft:stone_slab");

        assertEquals("stone must resolve through the block's own vanilla material: " + slab,
                "stone", slab.text("material"));
        assertTrue("and having both halves, it must have a capacity: " + slab,
                slab.longInteger("capacity") > 0);
    }

    /**
     * Precedence, on a block where the two sources DISAGREE - which is the only kind that can measure
     * it. A gold block is {@code blockGold} in the ore dictionary and {@code Material.IRON} to vanilla,
     * because that material means "metal-looking" and nothing finer. Asked on an IRON block this
     * assertion would pass whichever source won, which is a test that cannot fail.
     */
    @RedWitness(member = "ThermalMaterials#of",
            at = "if (found != null)",
            replacedBy = "if (found != null && found == null)",
            fails = "the specific name must win over the coarse one", on = "2026-10-18")
    @Test
    public void theOreDictionaryOutranksTheBlocksCoarseVanillaMaterial() throws Exception {
        Reply gold = placeAndRead("minecraft:gold_block");

        assertEquals("the specific name must win over the coarse one: " + gold,
                "gold", gold.text("material"));
    }

    /**
     * <p>Its capacity is not asserted: a capacity is material times volume, and with no volume it is
     * zero whatever the material — so "it can hold no heat" is this verdict again.</p>
     */
    @RedWitness(member = "ThermalMaterials#volumeMillilitres",
            at = "double cubicMetres = 0.0D;",
            replacedBy = "double cubicMetres = boxes.isEmpty() ? 1.0D : 0.0D;",
            fails = "air is not a small lump of something", on = "2026-10-18")
    @Test
    public void thereIsNoSubstanceInEmptySpace() throws Exception {
        Reply air = placeAndRead("minecraft:air");

        assertEquals("air is not a small lump of something: " + air, 0L,
                air.longInteger("volumeMilliLitres"));
    }
}
