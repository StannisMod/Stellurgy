package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterial;
import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterials;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.*;

/**
 * What a material is worth thermally, and why it is worth that.
 *
 * <p>The contract is that a slug's capacity is DERIVED from the substance it is made of - never
 * authored beside an item - so what these pin is the derivation and its consequences, never a
 * number. Every expected value below is computed from the same three physical properties the table
 * stores, so a test cannot agree with production by restating what production says.</p>
 */
public class ThermalMaterialsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The subject is the SHIPPED table, so each test reads one of its own from a file that does not
     * exist yet: with no file, {@code load} answers the shipped rows. The install's own table is
     * never consulted, so a file some earlier run left in the run directory cannot become the subject.
     */
    private ThermalMaterials table;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void readTheShippedTable() {
        table = ThermalMaterials.load(new File(folder.getRoot(), "thermalMaterials.json").getPath());
    }

    private ThermalMaterial material(String name) {
        ThermalMaterial found = table.byName(name);
        assertNotNull("the shipped table must know " + name, found);
        return found;
    }

    /** The law itself, spelled out here so the test computes it rather than trusting it. */
    private static long expectedJoulesPerCubicMetre(ThermalMaterial m, int ambient, int margin) {
        return (long) m.densityKgPerCubicMetre() * m.specificHeatJoulesPerKgKelvin()
                * (m.ceilingKelvin() - margin - ambient);
    }

    @RedWitness(member = "ThermalMaterial#joulesPerCubicMetre",
            at = "return (long) densityKgPerCubicMetre * specificHeatJoulesPerKgKelvin * usable;",
            replacedBy = "return 2 * (long) densityKgPerCubicMetre * specificHeatJoulesPerKgKelvin * usable;",
            fails = "the energy a lump holds is rho * c * dT and nothing else", on = "2026-10-17")
    @Test
    public void capacityIsDensityTimesSpecificHeatTimesTheUsableSpan() {
        ThermalMaterial iron = material("iron");

        assertEquals("the energy a lump holds is rho * c * dT and nothing else",
                expectedJoulesPerCubicMetre(iron, 293, 100), iron.joulesPerCubicMetre(293, 100));
    }

    @RedWitness(verdict = "costs capacity", member = "ThermalMaterial#joulesPerCubicMetre",
            at = "long usable = (long) ceilingKelvin - Math.max(0, marginKelvin) - startKelvin;",
            replacedBy = "long usable = (long) ceilingKelvin - startKelvin;",
            fails = "charging a slug short of its ceiling must cost capacity", on = "2026-10-17")
    @RedWitness(verdict = "exactly the margin", member = "ThermalMaterial#joulesPerCubicMetre",
            at = "return (long) densityKgPerCubicMetre * specificHeatJoulesPerKgKelvin * usable;",
            replacedBy = "return 2 * (long) densityKgPerCubicMetre * specificHeatJoulesPerKgKelvin * usable;",
            fails = "and exactly the margin's worth of it", on = "2026-10-17")
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

    @RedWitness(member = "ThermalMaterial#joulesPerCubicMetre", at = "if (usable <= 0L)", replacedBy = "if (false)",
            fails = "a slug in a room hotter than the slug melts is not a heat sink", on = "2026-10-17")
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
     */
    @RedWitness(verdict = "lead under twice water", member = "ThermalMaterial#joulesPerCubicMetre",
            at = "return (long) densityKgPerCubicMetre * specificHeatJoulesPerKgKelvin * usable;",
            replacedBy = "return (long) densityKgPerCubicMetre * usable;",
            fails = "and yet a litre of it is worth less than twice a litre of water", on = "2026-10-17")
    @RedWitness(verdict = "graphite beats iron", member = "ThermalMaterial#joulesPerCubicMetre",
            at = "long usable = (long) ceilingKelvin - Math.max(0, marginKelvin) - startKelvin;",
            replacedBy = "long usable = Math.min(100L, (long) ceilingKelvin - Math.max(0, marginKelvin) - startKelvin);",
            fails = "graphite is denser than nothing much and beats iron anyway, on ceiling alone", on = "2026-10-17")
    @RedWitness(verdict = "graphite beats a denser iron", member = "ThermalMaterials#defaults",
            at = "put(m, new ThermalMaterial(\"iron\", 7874, 449, 1811));",
            replacedBy = "put(m, new ThermalMaterial(\"iron\", 20000, 449, 1811));",
            fails = "graphite is denser than nothing much and beats iron anyway, on ceiling alone", on = "2026-10-17")
    @RedWitness(verdict = "only tungsten beats graphite", member = "ThermalMaterial#joulesPerCubicMetre",
            at = "return (long) densityKgPerCubicMetre * specificHeatJoulesPerKgKelvin * usable;",
            replacedBy = "return (long) specificHeatJoulesPerKgKelvin * usable;",
            fails = "and only tungsten beats graphite", on = "2026-10-17")
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
     */
    @RedWitness(verdict = "at least four times", member = "ThermalMaterials#slugCapacity",
            at = "return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit;",
            replacedBy = "return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit + 1000L;",
            fails = "four times the material is at least four times the heat", on = "2026-10-17")
    @RedWitness(verdict = "no more than three over", member = "ThermalMaterials#slugCapacity",
            at = "return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit;",
            replacedBy = "return (millilitres > 1_000L ? 2 : 1) * perCubicMetre * millilitres / 1_000_000L / joulesPerUnit;",
            fails = "and no more than the three units four rounded-down fractions can add", on = "2026-10-17")
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

    @RedWitness(verdict = "absent", member = "ThermalMaterials#byName",
            at = "materials.get(name.toLowerCase(Locale.ROOT));",
            replacedBy = "materials.getOrDefault(name.toLowerCase(Locale.ROOT), materials.get(\"iron\"));",
            fails = "a material nobody described must read as absent", on = "2026-10-17")
    @RedWitness(verdict = "carries nothing", member = "ThermalMaterials#slugCapacity",
            at = "if (material == null || millilitres <= 0L)",
            replacedBy = "if (material == null) { material = INSTANCE.byName(\"iron\"); } if (millilitres <= 0L)",
            fails = "and absent must carry nothing rather than a default", on = "2026-10-17")
    @Test
    public void anUnknownSubstanceIsNotSilentlyGivenACapacity() {
        assertNull("a material nobody described must read as absent",
                table.byName("unobtainium"));
        assertEquals("and absent must carry nothing rather than a default", 0L,
                ThermalMaterials.slugCapacity(null, 1_000));
    }

    /**
     * One row per substance, reached from every shape it comes in. The point of keying by material is
     * that an ingot, a block and a nugget of the same metal are the same substance.
     */
    @RedWitness(verdict = "ingot", member = "ThermalMaterials#byOreName", at = "for (String prefix : PREFIXES)",
            replacedBy = "for (String prefix : java.util.Arrays.asList(\"block\", \"plate\", \"stick\", \"gear\", \"dust\","
                    + " \"nugget\", \"gem\", \"ore\"))",
            fails = "an ore-dictionary ingot name must resolve", on = "2026-10-17")
    @RedWitness(verdict = "block", member = "ThermalMaterials#byOreName", at = "for (String prefix : PREFIXES)",
            replacedBy = "for (String prefix : java.util.Arrays.asList(\"ingot\", \"plate\", \"stick\", \"gear\", \"dust\","
                    + " \"nugget\", \"gem\", \"ore\"))",
            fails = "a block of it is the same substance", on = "2026-10-17")
    @RedWitness(verdict = "dust", member = "ThermalMaterials#byOreName", at = "for (String prefix : PREFIXES)",
            replacedBy = "for (String prefix : java.util.Arrays.asList(\"block\", \"ingot\", \"plate\", \"stick\", \"gear\","
                    + " \"nugget\", \"gem\", \"ore\"))",
            fails = "so is a dust of it", on = "2026-10-17")
    @Test
    public void everyShapeOfOneSubstanceResolvesToTheSameRow() {
        ThermalMaterial fromIngot = table.byOreName("ingotIron");
        ThermalMaterial fromBlock = table.byOreName("blockIron");
        ThermalMaterial fromDust = table.byOreName("dustIron");

        assertNotNull("an ore-dictionary ingot name must resolve", fromIngot);
        // Read null-safely: a shape that resolves to nothing must fail on ITS message, not on a
        // NullPointerException that names no substance at all.
        assertEquals("a block of it is the same substance", fromIngot.name(),
                fromBlock == null ? null : fromBlock.name());
        assertEquals("so is a dust of it", fromIngot.name(),
                fromDust == null ? null : fromDust.name());
    }

    // ─── volume: derived from the shape, never authored beside the item ─────────

    @RedWitness(verdict = "a cubic metre", member = "ThermalMaterials#prefixVolumes",
            at = "volumes.put(\"block\", 1_000_000L);", replacedBy = "volumes.put(\"block\", 2_000_000L);",
            fails = "a block is a cubic metre of the stuff", on = "2026-10-17")
    @RedWitness(verdict = "nine ingots", member = "ThermalMaterials#prefixVolumes",
            at = "volumes.put(\"ingot\", 1_000_000L / 9);", replacedBy = "volumes.put(\"ingot\", 1_000_000L / 8);",
            fails = "nine ingots to a block", on = "2026-10-17")
    @RedWitness(verdict = "nine nuggets", member = "ThermalMaterials#prefixVolumes",
            at = "volumes.put(\"nugget\", 1_000_000L / 81);", replacedBy = "volumes.put(\"nugget\", 1_000_000L / 80);",
            fails = "and nine nuggets to an ingot", on = "2026-10-17")
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

    @LegacyWitness(prose = "with {@code ThermalMaterials#volumeMillilitres} at {@code return 0L;} answering an ingot's volume for a shape it"
            + " does not know: \"an unrecognised shape must not be silently given a size expected:&lt;0&gt; but"
            + " was:&lt;111111&gt;\", 2026-09-30.")
    @Test
    public void aShapeNobodyDescribedIsNoVolumeRatherThanADefaultOne() {
        assertEquals("an unrecognised shape must not be silently given a size", 0L,
                ThermalMaterials.volumeMillilitres("clumpIron"));
    }

    @RedWitness(member = "ThermalMaterials#slugCapacity", at = "return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit;",
            replacedBy = "return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit + 1000L;",
            fails = "the substance is the same, so a litre of it is worth the same either way", on = "2026-10-17")
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

    @RedWitness(member = "ThermalMaterials#byName", at = "materials.get(name.toLowerCase(Locale.ROOT));",
            replacedBy = "materials.getOrDefault(name.toLowerCase(Locale.ROOT), materials.get(\"iron\"));",
            fails = "a prefix is not a licence to invent a material", on = "2026-10-17")
    @Test
    public void anOreNameOfSomethingTheTableDoesNotKnowResolvesToNothing() {
        assertNull("a prefix is not a licence to invent a material",
                table.byOreName("ingotUnobtainium"));
    }
}
