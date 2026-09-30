package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;

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
        arrange("stellurgytest place " + at + " " + block);
        Reply read = arrange("stellurgytest heat material " + at);
        requireArranged("premise: the position must hold " + block + ": " + read,
                block.equals(read.text("block")));
        return read;
    }

    /**
     * red-witnessed: one inversion per verdict, 2026-09-30. A CUBIC METRE — {@code
     * ThermalMaterials:225} measuring every block at twice its collision volume: "a full block is a
     * cubic metre: … expected:&lt;1000000&gt; but was:&lt;2000000&gt;". A CAPACITY — {@code
     * ThermalMaterials:190} answering zero for every slug: "and iron is a substance the table knows,
     * so it has a capacity: … \"capacity\":0". The placement premise is an arrangement and is not
     * witnessed.
     */
    @Test
    public void aWholeBlockIsAWholeCubicMetreOfItsSubstance() throws Exception {
        Reply iron = placeAndRead("minecraft:iron_block");

        assertEquals("a full block is a cubic metre: " + iron, WHOLE_BLOCK,
                iron.longInteger("volumeMilliLitres"));
        assertTrue("and iron is a substance the table knows, so it has a capacity: " + iron,
                iron.longInteger("capacity") > 0);
    }

    /**
     * red-witnessed: with {@code ThermalMaterials:225} measuring every block at twice its collision
     * volume: "half the shape is half the substance: … expected:&lt;500000&gt; but
     * was:&lt;1000000&gt;", 2026-09-30. The placement premise is an arrangement and is not witnessed.
     */
    @Test
    public void aSlabIsHalfABlockOfIt() throws Exception {
        Reply slab = placeAndRead("minecraft:stone_slab");

        assertEquals("half the shape is half the substance: " + slab, WHOLE_BLOCK / 2,
                slab.longInteger("volumeMilliLitres"));
    }

    /**
     * The discriminator: the outline of a staircase is a full cube, and its substance is not.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NOT A WHOLE BLOCK — {@code
     * ThermalMaterials:225} measuring every block at twice its collision volume: "a staircase must
     * not read as a whole block … \"volumeMilliLitres\":1500000". THREE QUARTERS — {@code
     * ThermalMaterials:223} summing only the first collision box: "it is the half slab plus the
     * quarter step: … expected:&lt;750000&gt; but was:&lt;500000&gt;". The placement premise is an
     * arrangement and is not witnessed.</p>
     */
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
     *
     * <p>red-witnessed: with {@code ThermalMaterials:262} doubling the volume an item's block
     * answers for: "an item the ore dictionary cannot name still has the shape of what it places: …
     * expected:&lt;500000&gt; but was:&lt;1000000&gt;", 2026-09-30.</p>
     */
    @Test
    public void aSlabInTheHandIsTheSameHalfBlockAsASlabOnTheGround() throws Exception {
        Reply held = arrange("stellurgytest heat item minecraft:stone_slab");

        assertEquals("an item the ore dictionary cannot name still has the shape of what it places: "
                + held, WHOLE_BLOCK / 2, held.longInteger("volumeMilliLitres"));
    }

    /**
     * The substance chain's second link. Vanilla names no stone in the ore dictionary, so without the
     * block's own {@code Material} a stone slab has a size and no identity - and a size alone answers
     * nothing, because capacity is the two multiplied.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. STONE — {@code ThermalMaterials:144}
     * answering no material for any vanilla {@code Material}: "stone must resolve through the block's
     * own vanilla material: … expected:&lt;[stone]&gt; but was:&lt;[]&gt;". A CAPACITY — {@code
     * ThermalMaterials:190} answering zero for every slug: "and having both halves, it must have a
     * capacity: … \"capacity\":0". The placement premise is an arrangement and is not witnessed.</p>
     */
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
     *
     * <p>red-witnessed: with {@code ThermalMaterials:130} passing over every ore-dictionary match:
     * "the specific name must win over the coarse one: … expected:&lt;[gold]&gt; but
     * was:&lt;[iron]&gt;", 2026-09-30. The placement premise is an arrangement and is not
     * witnessed.</p>
     */
    @Test
    public void theOreDictionaryOutranksTheBlocksCoarseVanillaMaterial() throws Exception {
        Reply gold = placeAndRead("minecraft:gold_block");

        assertEquals("the specific name must win over the coarse one: " + gold,
                "gold", gold.text("material"));
    }

    /**
     * red-witnessed: with {@code ThermalMaterials:221} measuring a block with no collision boxes as
     * a whole cube: "air is not a small lump of something: … expected:&lt;0&gt; but
     * was:&lt;1000000&gt;", 2026-09-30. The capacity verdict is not witnessed: the same run read
     * {@code capacity:0} for air a cubic metre in size, because air resolves to no material; zero
     * volume already forces a zero capacity, so no single fault in production reaches it past the
     * volume verdict above it. The placement premise is an arrangement and is not witnessed.
     */
    @Test
    public void thereIsNoSubstanceInEmptySpace() throws Exception {
        Reply air = placeAndRead("minecraft:air");

        assertEquals("air is not a small lump of something: " + air, 0L,
                air.longInteger("volumeMilliLitres"));
        assertEquals("and it can hold no heat: " + air, 0L, air.longInteger("capacity"));
    }
}
