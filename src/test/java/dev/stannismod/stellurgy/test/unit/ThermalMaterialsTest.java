package dev.stannismod.stellurgy.test.unit;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterial;
import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterials;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a material is worth thermally, and why it is worth that.
 *
 * <p>The contract is that a slug's capacity is DERIVED from the substance it is made of - never
 * authored beside an item - so what these pin is the derivation and its consequences, never a
 * number. Every expected value below is computed from the same three physical properties the table
 * stores, so a test cannot agree with production by restating what production says.</p>
 */
public class ThermalMaterialsTest {

    private int prevMargin;
    private int prevJoulesPerUnit;
    private int prevAmbient;

    /** The table file as it stood before this class, or null when there was none. */
    private static byte[] tableFileBefore;

    /**
     * The subject is the SHIPPED table, so the class puts it in place itself.
     *
     * <p>{@code ThermalMaterials.load()} reads a table file from the run directory whenever one
     * exists, and a file left there by any earlier run - a dev world, another test - would otherwise be
     * what every verdict below reads. With no file, {@code load()} answers the shipped rows. The file's
     * path is read off the table rather than written here, so the arrangement follows production if
     * the path moves.</p>
     */
    @BeforeClass
    public static void bootstrap() throws Exception {
        MinecraftBootstrap.ensure();
        File table = tableFile();
        tableFileBefore = table.exists() ? Files.readAllBytes(table.toPath()) : null;
        Files.deleteIfExists(table.toPath());
        ThermalMaterials.INSTANCE.load();
    }

    /**
     * Puts back what was there. {@code load()} with no file writes the shipped table out, so when
     * there was no file before this class, the one it wrote is removed again afterwards.
     */
    @AfterClass
    public static void restoreTheTableFile() throws Exception {
        File table = tableFile();
        if (tableFileBefore != null) {
            Files.write(table.toPath(), tableFileBefore);
            ThermalMaterials.INSTANCE.load();
        } else {
            Files.deleteIfExists(table.toPath());
            ThermalMaterials.INSTANCE.load();
            Files.deleteIfExists(table.toPath());
        }
    }

    private static File tableFile() throws Exception {
        Field path = ThermalMaterials.class.getDeclaredField("file");
        path.setAccessible(true);
        return new File((String) path.get(ThermalMaterials.INSTANCE));
    }

    @Before
    public void fixTheScale() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        prevMargin = config.shipHeatSlugMarginKelvin;
        prevJoulesPerUnit = config.shipHeatSlugJoulesPerUnit;
        prevAmbient = config.shipHeatAmbientKelvin;
        config.shipHeatSlugMarginKelvin = 100;
        config.shipHeatSlugJoulesPerUnit = 1000;
        config.shipHeatAmbientKelvin = 293;
    }

    @After
    public void restoreTheScale() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeatSlugMarginKelvin = prevMargin;
        config.shipHeatSlugJoulesPerUnit = prevJoulesPerUnit;
        config.shipHeatAmbientKelvin = prevAmbient;
    }

    private static ThermalMaterial material(String name) {
        ThermalMaterial found = ThermalMaterials.INSTANCE.byName(name);
        assertNotNull("the shipped table must know " + name, found);
        return found;
    }

    /** The law itself, spelled out here so the test computes it rather than trusting it. */
    private static long expectedJoulesPerCubicMetre(ThermalMaterial m, int ambient, int margin) {
        return (long) m.densityKgPerCubicMetre() * m.specificHeatJoulesPerKgKelvin()
                * (m.ceilingKelvin() - margin - ambient);
    }

    /**
     * <p>red-witnessed: with {@code ThermalMaterial:71} doubling the product: "the energy a lump holds
     * is rho * c * dT and nothing else expected:&lt;5013234068&gt; but was:&lt;10026468136&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void capacityIsDensityTimesSpecificHeatTimesTheUsableSpan() {
        ThermalMaterial iron = material("iron");

        assertEquals("the energy a lump holds is rho * c * dT and nothing else",
                expectedJoulesPerCubicMetre(iron, 293, 100), iron.joulesPerCubicMetre(293, 100));
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. COSTS CAPACITY - {@code ThermalMaterial:67}
     * ignoring the margin: "charging a slug short of its ceiling must cost capacity ...: 5366776668 -&gt;
     * 5366776668". EXACTLY THE MARGIN - {@code ThermalMaterial:71} doubling the product: "and exactly
     * the margin's worth of it expected:&lt;353542600&gt; but was:&lt;707085200&gt;".</p>
     */
    @Test
    public void theMarginIsSubtractedFromTheSpanRatherThanIgnored() {
        ThermalMaterial iron = material("iron");

        long withoutMargin = iron.joulesPerCubicMetre(293, 0);
        long withMargin = iron.joulesPerCubicMetre(293, 100);

        assertTrue("charging a slug short of its ceiling must cost capacity - that is what buys back"
                + " a solid object: " + withoutMargin + " -> " + withMargin, withMargin < withoutMargin);
        assertEquals("and exactly the margin's worth of it",
                (long) iron.densityKgPerCubicMetre() * iron.specificHeatJoulesPerKgKelvin() * 100,
                withoutMargin - withMargin);
    }

    /**
     * <p>red-witnessed: with the guard at {@code ThermalMaterial:68} disabled: "a slug in a room hotter
     * than the slug melts is not a heat sink expected:&lt;0&gt; but was:&lt;-1462860&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void aMaterialAlreadyPastItsCeilingCarriesNothing() {
        ThermalMaterial lead = material("lead");

        assertEquals("a slug in a room hotter than the slug melts is not a heat sink", 0L,
                lead.joulesPerCubicMetre(lead.ceilingKelvin() + 1, 0));
    }

    /**
     * The progression's whole lesson, and it must fall out of the physics rather than be arranged:
     * lead is eleven times denser than water and barely better as a slug, because it gives up at
     * 600 K.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. LEAD UNDER TWICE WATER -
     * {@code ThermalMaterial:71} forgetting the specific heat: "and yet a litre of it is worth less
     * than twice a litre of water ...: water=80000 lead=3481380". GRAPHITE BEATS IRON -
     * {@code ThermalMaterial:67} capping the usable span at 100 K: "graphite is denser than nothing
     * much and beats iron anyway, on ceiling alone: carbon=160234000". ONLY TUNGSTEN BEATS GRAPHITE -
     * {@code ThermalMaterial:71} forgetting the density: "and only tungsten beats graphite:
     * tungsten=455868". The premise before them is an arrangement and is not witnessed.</p>
     *
     * <p>red-witnessed, the class arrangement, 2026-09-30: with the shipped iron at
     * {@code ThermalMaterials:439} made 20000 kg/m3: "graphite is denser than nothing much and beats
     * iron anyway, on ceiling alone: carbon=5779640380". Before the class removed the run directory's
     * table file, the same change left every method here green.</p>
     */
    @Test
    public void aHigherCeilingBeatsAHigherDensity() {
        long water = material("water").joulesPerCubicMetre(293, 0);
        long lead = material("lead").joulesPerCubicMetre(293, 0);
        long tungsten = material("tungsten").joulesPerCubicMetre(293, 0);
        long carbon = material("carbon").joulesPerCubicMetre(293, 0);

        assertTrue("premise: lead really is far denser than water",
                material("lead").densityKgPerCubicMetre()
                        > 10 * material("water").densityKgPerCubicMetre());
        assertTrue("and yet a litre of it is worth less than twice a litre of water, because it melts"
                + " at 600 K: water=" + water + " lead=" + lead, lead < 2 * water);
        assertTrue("graphite is denser than nothing much and beats iron anyway, on ceiling alone:"
                + " carbon=" + carbon, carbon > material("iron").joulesPerCubicMetre(293, 0));
        assertTrue("and only tungsten beats graphite: tungsten=" + tungsten, tungsten > carbon);
    }

    /**
     * Four times the material is four times the heat - up to what whole heat units can say.
     *
     * <p>The capacity is a whole number of heat units, so it is the true value x rounded DOWN. For any
     * x, 4·floor(x) ≤ floor(4x) ≤ 4·floor(x) + 3: the four litres carry four copies of the litre's
     * units plus whatever the four discarded fractions add up to, and four fractions each under one
     * make at most three whole units. So linearity reads as "at least four times, and no more than
     * three units over" - asserting exact equality would be asserting that iron's fraction happens to
     * be under a quarter, which any rebalance can change without the slug stopping being linear.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. AT LEAST FOUR TIMES -
     * {@code ThermalMaterials:192} adding a flat 1000 units to every slug: "four times the material is
     * at least four times the heat ...: one=13733 four=51934" (run with the shipped iron made denser
     * for another verdict; the flat term is what breaks it). NO MORE THAN THREE OVER -
     * {@code ThermalMaterials:192} doubling every slug bigger than a litre: "and no more than the three
     * units four rounded-down fractions can add: one=5013 four=40104". A change that stays linear is
     * not a red: with {@code ThermalMaterial:71} doubling every capacity this stays green. The premise
     * is an arrangement and is not witnessed.</p>
     */
    @Test
    public void volumeScalesTheSlugLinearly() {
        ThermalMaterial iron = material("iron");

        long oneLitre = ThermalMaterials.slugCapacity(iron, 1_000);
        long fourLitres = ThermalMaterials.slugCapacity(iron, 4_000);

        assertTrue("premise: a litre of iron must be worth something at all", oneLitre > 0);
        assertTrue("four times the material is at least four times the heat - the slug is a quantity"
                + " of a substance, not a magic item: one=" + oneLitre + " four=" + fourLitres,
                fourLitres >= 4 * oneLitre);
        assertTrue("and no more than the three units four rounded-down fractions can add: one="
                + oneLitre + " four=" + fourLitres, fourLitres <= 4 * oneLitre + 3);
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. HALVES - {@code ThermalMaterials:186}
     * ignoring the configured conversion: "halving what a heat unit is worth must halve the slug
     * expected:&lt;2506&gt; but was:&lt;5013&gt;". REORDERS NONE - {@code ThermalMaterials:186}
     * converting materials denser than 8000 kg/m3 at a quarter of the rate once the conversion moves:
     * "and it must not change which material is the better slug".</p>
     */
    @Test
    public void theConversionScalesEveryMaterialAndReordersNone() {
        ThermalMaterial iron = material("iron");
        ThermalMaterial copper = material("copper");
        long ironBefore = ThermalMaterials.slugCapacity(iron, 1_000);
        long copperBefore = ThermalMaterials.slugCapacity(copper, 1_000);

        StellurgyConfiguration.getCurrentConfig().shipHeatSlugJoulesPerUnit = 2000;

        long ironAfter = ThermalMaterials.slugCapacity(iron, 1_000);
        long copperAfter = ThermalMaterials.slugCapacity(copper, 1_000);

        assertEquals("halving what a heat unit is worth must halve the slug", ironBefore / 2, ironAfter);
        assertTrue("and it must not change which material is the better slug",
                (ironBefore > copperBefore) == (ironAfter > copperAfter));
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. ABSENT - {@code ThermalMaterials:167}
     * answering iron for a name it does not know: "a material nobody described must read as absent
     * expected null, but was:&lt;ThermalMaterial[iron ...]&gt;". CARRIES NOTHING -
     * {@code ThermalMaterials:182} substituting iron for a missing material: "and absent must carry
     * nothing rather than a default expected:&lt;0&gt; but was:&lt;5013&gt;".</p>
     */
    @Test
    public void anUnknownSubstanceIsNotSilentlyGivenACapacity() {
        assertNull("a material nobody described must read as absent",
                ThermalMaterials.INSTANCE.byName("unobtainium"));
        assertEquals("and absent must carry nothing rather than a default", 0L,
                ThermalMaterials.slugCapacity(null, 1_000));
    }

    /**
     * One row per substance, reached from every shape it comes in. The point of keying by material is
     * that an ingot, a block and a nugget of the same metal are the same substance.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, each at {@code ThermalMaterials:60}
     * forgetting one prefix. INGOT: "an ore-dictionary ingot name must resolve". BLOCK: "a block of it
     * is the same substance expected:&lt;iron&gt; but was:&lt;null&gt;". DUST: "so is a dust of it
     * expected:&lt;iron&gt; but was:&lt;null&gt;".</p>
     */
    @Test
    public void everyShapeOfOneSubstanceResolvesToTheSameRow() {
        ThermalMaterial fromIngot = ThermalMaterials.INSTANCE.byOreName("ingotIron");
        ThermalMaterial fromBlock = ThermalMaterials.INSTANCE.byOreName("blockIron");
        ThermalMaterial fromDust = ThermalMaterials.INSTANCE.byOreName("dustIron");

        assertNotNull("an ore-dictionary ingot name must resolve", fromIngot);
        // Read null-safely: a shape that resolves to nothing must fail on ITS message, not on a
        // NullPointerException that names no substance at all.
        assertEquals("a block of it is the same substance", fromIngot.name(),
                fromBlock == null ? null : fromBlock.name());
        assertEquals("so is a dust of it", fromIngot.name(),
                fromDust == null ? null : fromDust.name());
    }

    // ─── volume: derived from the shape, never authored beside the item ─────────

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. A CUBIC METRE -
     * {@code ThermalMaterials:71} making a block two: "a block is a cubic metre of the stuff
     * expected:&lt;1000000&gt; but was:&lt;2000000&gt;". NINE INGOTS - {@code ThermalMaterials:72}
     * making an ingot an eighth: "nine ingots to a block ... expected:&lt;111111&gt; but
     * was:&lt;125000&gt;". NINE NUGGETS - {@code ThermalMaterials:79} making a nugget an eightieth:
     * "and nine nuggets to an ingot expected:&lt;12345&gt; but was:&lt;12500&gt;".</p>
     */
    @Test
    public void theShapeOfAnItemIsWhatSaysHowMuchSubstanceItIs() {
        long block = ThermalMaterials.volumeMillilitres("blockIron");
        long ingot = ThermalMaterials.volumeMillilitres("ingotIron");
        long nugget = ThermalMaterials.volumeMillilitres("nuggetIron");

        assertEquals("a block is a cubic metre of the stuff", 1_000_000L, block);
        assertEquals("nine ingots to a block, which is the arithmetic the whole ecosystem uses",
                block / 9, ingot);
        assertEquals("and nine nuggets to an ingot", ingot / 9, nugget);
    }

    /**
     * <p>red-witnessed: with {@code ThermalMaterials:331} answering an ingot's volume for a shape it
     * does not know: "an unrecognised shape must not be silently given a size expected:&lt;0&gt; but
     * was:&lt;111111&gt;", 2026-09-30.</p>
     */
    @Test
    public void aShapeNobodyDescribedIsNoVolumeRatherThanADefaultOne() {
        assertEquals("an unrecognised shape must not be silently given a size", 0L,
                ThermalMaterials.volumeMillilitres("clumpIron"));
    }

    /**
     * <p>red-witnessed: with {@code ThermalMaterials:192} adding a flat 1000 units to every slug: "the
     * substance is the same, so a litre of it is worth the same either way expected:&lt;5014&gt; but
     * was:&lt;5022&gt;", 2026-09-30. The premise before it is an arrangement and is not
     * witnessed.</p>
     */
    @Test
    public void twoShapesOfOneMetalDifferOnlyByHowMuchOfItThereIs() {
        ThermalMaterial iron = material("iron");
        long fromBlock = ThermalMaterials.slugCapacity(iron,
                ThermalMaterials.volumeMillilitres("blockIron"));
        long fromIngot = ThermalMaterials.slugCapacity(iron,
                ThermalMaterials.volumeMillilitres("ingotIron"));

        assertTrue("premise: an iron block must be worth something", fromBlock > 0);
        // Per LITRE rather than as a ratio of the two totals: nine ingots are 999 999 ml against a
        // block's 1 000 000, because the ecosystem's own ninth does not divide evenly. That one
        // millilitre is the convention's rounding, not a difference in the substance - and asserting
        // the ratio of the totals would be asserting the rounding.
        long blockPerLitre = fromBlock * 1_000L / ThermalMaterials.volumeMillilitres("blockIron");
        long ingotPerLitre = fromIngot * 1_000L / ThermalMaterials.volumeMillilitres("ingotIron");
        assertEquals("the substance is the same, so a litre of it is worth the same either way",
                blockPerLitre, ingotPerLitre);
    }

    /**
     * <p>red-witnessed: with {@code ThermalMaterials:167} answering iron for a name it does not know:
     * "a prefix is not a licence to invent a material expected null, but was:&lt;ThermalMaterial[iron
     * ...]&gt;", 2026-09-30.</p>
     */
    @Test
    public void anOreNameOfSomethingTheTableDoesNotKnowResolvesToNothing() {
        assertNull("a prefix is not a licence to invent a material",
                ThermalMaterials.INSTANCE.byOreName("ingotUnobtainium"));
    }
}
