package dev.stannismod.stellurgy.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.subsystem.heat.HotSlugPhysics;
import dev.stannismod.stellurgy.subsystem.heat.ThermalBody;
import dev.stannismod.stellurgy.subsystem.heat.ThermalSignature;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.tile.heat.TileHeatDump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The signature is TWO terms, and this is the test that says they cannot be collapsed into one.
 *
 * <p>C12 HEAT-15 splits what a passive sensor learns into a detection term (total radiated power,
 * which sets the range a ship is FOUND from) and a lock term (radiance, which depends on temperature
 * alone). The whole build trade lives in their difference: a compact hot array and a large cool one
 * shed the SAME power and are not remotely the same target. So the first scenario builds exactly
 * that pair and asserts that one number is identical while the other is not - a model that reported
 * a single "signature" fails it whichever number it chose.</p>
 *
 * <p>Nothing here names a temperature the game has to agree with. Both arrays are computed FROM the
 * running config's own reference point, so the pair stays a matched pair under any rebalance.</p>
 */
public class ThermalSignatureTest {

    /** The intended steady state: an 8x8 plate at the temperature the config quotes a cell at. */
    private static final int COMPACT_CELLS = 8 * 8;

    private int prevCellPower;
    private int prevReferenceKelvin;
    private int prevAmbient;
    private int prevSkinFraction;
    private int prevMargin;
    private int prevJoulesPerUnit;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void shippedDefaults() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        prevCellPower = config.shipHeatRadiatorCellPower;
        prevReferenceKelvin = config.shipHeatRadiatorReferenceKelvin;
        prevAmbient = config.shipHeatAmbientKelvin;
        prevSkinFraction = config.shipHeatHullSkinFraction;
        prevMargin = config.shipHeatSlugMarginKelvin;
        prevJoulesPerUnit = config.shipHeatSlugJoulesPerUnit;
        config.shipHeatRadiatorCellPower = 6000;
        config.shipHeatRadiatorReferenceKelvin = 500;
        config.shipHeatAmbientKelvin = 293;
        config.shipHeatHullSkinFraction = 350;
        config.shipHeatSlugMarginKelvin = 100;
        config.shipHeatSlugJoulesPerUnit = 1000;
    }

    @After
    public void restoreDefaults() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeatRadiatorCellPower = prevCellPower;
        config.shipHeatRadiatorReferenceKelvin = prevReferenceKelvin;
        config.shipHeatAmbientKelvin = prevAmbient;
        config.shipHeatHullSkinFraction = prevSkinFraction;
        config.shipHeatSlugMarginKelvin = prevMargin;
        config.shipHeatSlugJoulesPerUnit = prevJoulesPerUnit;
    }

    /** The temperature at which a cell sheds exactly a quarter of what it sheds at the reference. */
    private static double coolerByFour() {
        // Quartic, so a quarter of the power is the fourth root of a quarter of the temperature.
        return StellurgyConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin
                * Math.pow(0.25D, 0.25D);
    }

    /**
     * Two ships shedding the same total power, one compact and hot, one large and cool. They must be
     * found at the same distance and be nothing alike to lock onto.
     */
    @Test
    public void theSamePowerAtTwoTemperaturesIsOneRangeAndTwoDifferentLocks() {
        ThermalSignature compact = ThermalSignature.surface(COMPACT_CELLS,
                StellurgyConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin);
        ThermalSignature sprawling = ThermalSignature.surface(COMPACT_CELLS * 4, coolerByFour());

        assertEquals("premise: the pair is only a pair if both shed the same total power",
                compact.radiatedPower(), sprawling.radiatedPower(), compact.radiatedPower() * 1e-9D);
        assertTrue("premise: they must actually be shedding something: " + compact,
                compact.radiatedPower() > 0.0D);

        assertEquals("the DETECTION term is total power, so the same power must be the same range"
                        + " however that power is arranged: " + compact + " | " + sprawling,
                compact.detectionRangeBlocks(2000.0D), sprawling.detectionRangeBlocks(2000.0D),
                compact.detectionRangeBlocks(2000.0D) * 1e-9D);

        assertTrue("but the LOCK term is radiance, a function of temperature alone, so the compact"
                        + " hot array must be far the brighter target: " + compact + " | " + sprawling,
                compact.radiance() > 3.5D * sprawling.radiance());
    }

    /**
     * Range goes as the square root of power, which is what makes shedding twice as much cost so
     * little in stealth - and what makes hiding by shedding less such a poor deal.
     */
    @Test
    public void rangeGoesAsTheSquareRootOfPower() {
        double reference = StellurgyConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin;
        double once = ThermalSignature.surface(COMPACT_CELLS, reference).detectionRangeBlocks(2000.0D);
        double twice = ThermalSignature.surface(COMPACT_CELLS * 2, reference)
                .detectionRangeBlocks(2000.0D);

        assertTrue("premise: a reference array must be detectable at all", once > 0.0D);
        assertEquals("twice the power must extend the range by the square root of two and no more:"
                        + " once=" + once + " twice=" + twice,
                Math.sqrt(2.0D), twice / once, 1e-9D);
    }

    /**
     * Two surfaces seen as one object: the powers add and the radiance is the hottest of them.
     *
     * <p>The maximum, not a mean, is the clause a seeker depends on - a glowing drive housing behind
     * a hull's worth of cold plating is still a lock.</p>
     */
    @Test
    public void oneObjectIsLockedOnItsBrightestPart() {
        double reference = StellurgyConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin;
        ThermalSignature hot = ThermalSignature.surface(1, reference);
        ThermalSignature cold = ThermalSignature.surface(1000, reference / 4.0D);
        ThermalSignature together = cold.plus(hot);

        assertEquals("the powers of two surfaces add", hot.radiatedPower() + cold.radiatedPower(),
                together.radiatedPower(), together.radiatedPower() * 1e-9D);
        assertEquals("and the object is locked on its hottest part, however little of it there is",
                hot.radiance(), together.radiance(), hot.radiance() * 1e-9D);
    }

    /**
     * A config that asks for a perfectly insulated hull is refused, exactly as one asking for a
     * perfect shield is.
     *
     * <p>HEAT-16 is that silence reduces the range a ship is found from and never drives the
     * signature to zero. A fraction clamped where it is READ is what makes that true of every
     * configuration rather than of the shipped one.</p>
     */
    @Test
    public void aPerfectlyColdSkinIsRefused() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        double shipped = ThermalBody.skinFraction();

        config.shipHeatHullSkinFraction = 0;
        double asked = ThermalBody.skinFraction();

        assertTrue("premise: the shipped setting must let most of the warmth stay INSIDE, or"
                + " silence would be worth nothing: " + shipped, shipped > 0.0D && shipped < 1.0D);
        assertTrue("a hull that lets nothing out is not a setting: asked for 0, got " + asked,
                asked > 0.0D);
        assertTrue("and a hull at that fraction must still be radiating: "
                        + ThermalSignature.surface(1.0D, asked * config.shipHeatAmbientKelvin),
                ThermalSignature.surface(1.0D, asked * config.shipHeatAmbientKelvin)
                        .radiatedPower() > 0.0D);
    }

    /**
     * Why a thrown slug is a decoy: one lump outshines the whole array it was thrown from.
     *
     * <p>It is charged to just under its material's melting point, which is a thousand kelvin past
     * anything a radiator runs at, and the law is quartic - so a SINGLE cell of white-hot iron beats
     * sixty-four cells of working radiator on both terms at once. A seeker homing on radiance takes
     * it by a wide margin, and a detector counting power sees it too.</p>
     *
     * <p>None of that is written down: it falls out of one temperature being far above the other on a
     * fourth-power curve. What is asserted is the RELATION, so a rebalance that moves either
     * temperature moves the decoy's worth with it.</p>
     */
    @Test
    public void aThrownSlugOutshinesTheArrayItLeft() {
        ItemStack slug = new ItemStack(Blocks.IRON_BLOCK);
        slug.setTagCompound(new NBTTagCompound());
        slug.getTagCompound().setLong(TileHeatDump.NBT_CHARGE, Long.MAX_VALUE / 4L);

        ThermalSignature thrown = ThermalSignature.ofSlug(slug);
        ThermalSignature array = ThermalSignature.surface(COMPACT_CELLS,
                StellurgyConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin);

        assertTrue("premise: a fully charged slug must be hotter than the array it left ("
                        + thrown.peakKelvin() + " K against " + array.peakKelvin() + " K)",
                thrown.peakKelvin() > array.peakKelvin());
        assertTrue("a seeker homing on radiance must prefer the slug by a wide margin, or it is not"
                        + " worth throwing: " + thrown + " | " + array,
                thrown.radiance() > 10.0D * array.radiance());
        assertTrue("and one white-hot lump must outshine the whole array it left, which is what the"
                        + " fourth power buys it: " + thrown + " | " + array,
                thrown.radiatedPower() > array.radiatedPower());
        assertEquals("and it must show exactly the surface it cools through, or what it shows and"
                        + " what it spends are two different objects",
                HotSlugPhysics.RADIATING_CELLS * thrown.radiance(), thrown.radiatedPower(),
                thrown.radiatedPower() * 1e-9D);
    }
}
