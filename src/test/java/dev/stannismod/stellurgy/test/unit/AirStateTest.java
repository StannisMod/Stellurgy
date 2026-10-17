package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The gas contents of a life-support zone: what a room's air is made of, what pressure that
 * amounts to, and which atmosphere the rest of the mod therefore sees.
 */
public class AirStateTest {

    /** Room volume, in blocks, for the heat-capacity scenario. */
    private static final int VOLUME = 20;

    /**
     * Parts per million of an atmosphere. Every quantity below is a FRACTION of an atmosphere and
     * says so, rather than a count of whatever the model happens to store internally — which is what
     * lets the unit underneath change without a single scenario changing what it means.
     */
    private static long ppm(long partsPerMillion) {
        return partsPerMillion * AirState.PER_PPM;
    }

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * A vacuum is not a reservoir: there is no body there to warm.
     */
    @RedWitness(member = "AirState#addHeat", at = "if (amount <= 0L || capacity <= 0L)", replacedBy = "if (amount <= 0L)",
            fails = "nothing to warm expected:<0> but was:<1000000>", on = "2026-10-17")
    @Test
    public void aVacuumCannotBeWarmed() {
        assertEquals("nothing to warm", 0L, AirState.vacuum().addHeat(1_000_000L, VOLUME));
    }

    /**
     * Pins INV-ATM-17 (an untouched zone reports 100 centi-atmospheres).
     */
    @Pins("INV-ATM-17")
    @RedWitness(member = "AirState#getPressureCentiAtm", at = "(ONE_ATM / 100L)", replacedBy = "(ONE_ATM / 50L)",
            fails = "expected:<100> but was:<50>", on = "2026-10-17")
    @Test
    public void breathableAirReadsAsOneAtmosphere() {
        // The pressure a sealed zone reports is what the analyser turns into "1.00 atm"; it read
        // a flat 100 before zones had contents and must keep reading 100 for untouched air.
        assertEquals(100, AirState.earthLike().getPressureCentiAtm());
    }

    /**
     * Pins INV-ATM-16 (breathing converts oxygen into carbon dioxide at constant pressure and never below zero).
     */
    @Pins("INV-ATM-16")
    @RedWitness(verdict = "oxygen falls", member = "AirState#draw", at = "set(gas, partialPressure(gas) - taken);",
            replacedBy = "set(gas, partialPressure(gas) - taken / 2);",
            fails = "oxygen must fall by exactly what was breathed", on = "2026-10-17")
    @RedWitness(verdict = "as CO2", member = "AirState#respire",
            at = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);",
            replacedBy = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + 2 * converted);",
            fails = "the same amount must appear as CO2", on = "2026-10-17")
    @RedWitness(verdict = "pressure unchanged", member = "AirState#respire",
            at = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);",
            replacedBy = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);"
                    + " set(GasRegistry.NITROGEN, getNitrogen() - converted);",
            fails = "respiration rearranges air, it does not consume it", on = "2026-10-17")
    @Test
    public void breathingConvertsOxygenIntoCarbonDioxideWithoutChangingPressure() {
        AirState air = AirState.earthLike();
        long before = air.getTotalPressure();

        air.respire(ppm(10_000));

        assertEquals("oxygen must fall by exactly what was breathed", ppm(200_000), air.getOxygen());
        assertEquals("the same amount must appear as CO2", ppm(10_000), air.getCarbonDioxide());
        assertEquals("respiration rearranges air, it does not consume it", before, air.getTotalPressure());
    }

    /**
     * Pins INV-ATM-16 (breathing converts oxygen into carbon dioxide at constant pressure and never below zero).
     */
    @Pins("INV-ATM-16")
    @RedWitness(verdict = "only what is present", member = "AirState#draw",
            at = "long taken = Math.min(Math.max(0L, amount), partialPressure(gas));",
            replacedBy = "long taken = Math.max(0L, amount);",
            fails = "only the oxygen present may be converted", on = "2026-10-17")
    @RedWitness(verdict = "none left", member = "AirState#draw", at = "set(gas, partialPressure(gas) - taken);",
            replacedBy = "set(gas, partialPressure(gas) - taken / 2);",
            fails = "expected:<0> but was:<2500000>", on = "2026-10-17")
    @RedWitness(verdict = "as CO2", member = "AirState#respire",
            at = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);",
            replacedBy = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + 2 * converted);",
            fails = "expected:<5000000> but was:<10000000>", on = "2026-10-17")
    @Test
    public void breathingCannotTakeOxygenThatIsNotThere() {
        AirState air = new AirState(ppm(790_000), ppm(5_000), 0L);

        long taken = air.respire(ppm(50_000));

        assertEquals("only the oxygen present may be converted", ppm(5_000), taken);
        assertEquals(0L, air.getOxygen());
        assertEquals(ppm(5_000), air.getCarbonDioxide());
    }

    /**
     * Pins INV-ATM-20 (regeneration is respiration run backwards and cannot process absent carbon dioxide).
     */
    @Pins("INV-ATM-20")
    @RedWitness(verdict = "all back as oxygen", member = "AirState#regenerate",
            at = "set(GasRegistry.OXYGEN, getOxygen() + converted);",
            replacedBy = "set(GasRegistry.OXYGEN, getOxygen() + converted / 2);",
            fails = "all of it must come back as oxygen", on = "2026-10-17")
    @RedWitness(verdict = "no CO2 left", member = "AirState#respire",
            at = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);",
            replacedBy = "set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + 2 * converted);",
            fails = "expected:<0> but was:<30000000>", on = "2026-10-17")
    @RedWitness(verdict = "the carbon", member = "AirState#regenerate", at = "return converted;",
            replacedBy = "return converted / 2;",
            fails = "the carbon that left the air is what the machine must now handle", on = "2026-10-17")
    @RedWitness(verdict = "pressure unchanged", member = "AirState#regenerate",
            at = "set(GasRegistry.OXYGEN, getOxygen() + converted);",
            replacedBy = "set(GasRegistry.OXYGEN, getOxygen() + converted); set(GasRegistry.NITROGEN, getNitrogen() - converted);",
            fails = "pressure is unchanged: the solid carbon never held any", on = "2026-10-17")
    @Test
    public void regenerationIsBreathingRunBackwards() {
        AirState air = AirState.earthLike();
        air.respire(ppm(30_000));
        long pressureWithCrewAboard = air.getTotalPressure();

        long carbon = air.regenerate(ppm(30_000));

        assertEquals("all of it must come back as oxygen", ppm(210_000), air.getOxygen());
        assertEquals(0L, air.getCarbonDioxide());
        assertEquals("the carbon that left the air is what the machine must now handle", ppm(30_000), carbon);
        assertEquals("pressure is unchanged: the solid carbon never held any", pressureWithCrewAboard, air.getTotalPressure());
    }

    /**
     * Pins INV-ATM-20 (regeneration is respiration run backwards and cannot process absent carbon dioxide).
     */
    @Pins("INV-ATM-20")
    @RedWitness(verdict = "only what is present", member = "AirState#draw",
            at = "long taken = Math.min(Math.max(0L, amount), partialPressure(gas));",
            replacedBy = "long taken = Math.max(0L, amount);",
            fails = "only the CO2 present may be processed", on = "2026-10-17")
    @RedWitness(verdict = "none left", member = "AirState#draw", at = "set(gas, partialPressure(gas) - taken);",
            replacedBy = "set(gas, partialPressure(gas) - taken / 2);",
            fails = "expected:<0> but was:<5000000>", on = "2026-10-17")
    @RedWitness(verdict = "as oxygen", member = "AirState#regenerate",
            at = "set(GasRegistry.OXYGEN, getOxygen() + converted);",
            replacedBy = "set(GasRegistry.OXYGEN, getOxygen() + converted / 2);",
            fails = "expected:<210000000> but was:<205000000>", on = "2026-10-17")
    @Test
    public void regenerationCannotInventCarbonDioxide() {
        AirState air = new AirState(ppm(790_000), ppm(200_000), ppm(10_000));

        long carbon = air.regenerate(ppm(50_000));

        assertEquals("only the CO2 present may be processed", ppm(10_000), carbon);
        assertEquals(0L, air.getCarbonDioxide());
        assertEquals(ppm(210_000), air.getOxygen());
    }

    @RedWitness(member = "AirState#deriveAtmosphere", at = "if (getTotalPressure() <= VACUUM_CEILING)",
            replacedBy = "if (false)", fails = "expected same:", on = "2026-10-17")
    @Test
    public void aZoneWithNoGasInItIsVacuumWhateverItsComposition() {
        assertSame(Atmosphere.VACUUM, AirState.vacuum().deriveAtmosphere());
    }

    @RedWitness(verdict = "CO2 taken", member = "AirState#drawCarbonDioxide",
            at = "long taken = draw(GasRegistry.CARBON_DIOXIDE, amount);",
            replacedBy = "long taken = draw(GasRegistry.CARBON_DIOXIDE, amount / 2);",
            fails = "expected:<50000000> but was:<25000000>", on = "2026-10-17")
    @RedWitness(verdict = "N2 taken", member = "AirState#drawNitrogen", at = "long taken = draw(GasRegistry.NITROGEN, amount);",
            replacedBy = "long taken = draw(GasRegistry.NITROGEN, amount / 2);",
            fails = "expected:<40000000> but was:<20000000>", on = "2026-10-17")
    @RedWitness(verdict = "oxygen untouched", member = "AirState#drawNitrogen",
            at = "long taken = draw(GasRegistry.NITROGEN, amount);",
            replacedBy = "long taken = draw(GasRegistry.NITROGEN, amount); draw(GasRegistry.OXYGEN, amount);",
            fails = "splitting must not touch the oxygen the crew are breathing", on = "2026-10-17")
    @RedWitness(verdict = "CO2 left", member = "AirState#draw", at = "set(gas, partialPressure(gas) - taken);",
            replacedBy = "set(gas, partialPressure(gas) - taken / 2);",
            fails = "expected:<40000000> but was:<65000000>", on = "2026-10-17")
    @RedWitness(verdict = "N2 left", member = "AirState#drawNitrogen", at = "long taken = draw(GasRegistry.NITROGEN, amount);",
            replacedBy = "long taken = draw(GasRegistry.NITROGEN, amount / 2) * 2;",
            fails = "expected:<750000000> but was:<770000000>", on = "2026-10-17")
    @Test
    public void aSeparatorTakesTheStaleGasAndLeavesTheBreathableOne() {
        AirState air = new AirState(ppm(790_000), ppm(210_000), ppm(90_000));

        long co2 = air.drawCarbonDioxide(ppm(50_000));
        long n2 = air.drawNitrogen(ppm(40_000));

        assertEquals(ppm(50_000), co2);
        assertEquals(ppm(40_000), n2);
        assertEquals("splitting must not touch the oxygen the crew are breathing", ppm(210_000), air.getOxygen());
        assertEquals(ppm(40_000), air.getCarbonDioxide());
        assertEquals(ppm(750_000), air.getNitrogen());
    }

    // ─── What the unit can express ─────────────────────────────────────────────────────────────
    //
    // A room is its FRACTIONS: how much of the air is oxygen, and how much air there is. The number
    // the model stores those with is an implementation choice, and these say what that choice has to
    // be good enough FOR — the two ends of the solar system at once, and an honest zero underneath.

    @RedWitness(verdict = "21%", member = "AirState#earthLike", at = "210_000 * PER_PPM", replacedBy = "200_000 * PER_PPM",
            fails = "sea-level air is 21% oxygen", on = "2026-10-17")
    @RedWitness(verdict = "one atmosphere", member = "AirState#getPressureCentiAtm", at = "(ONE_ATM / 100L)",
            replacedBy = "(ONE_ATM / 50L)", fails = "and one whole atmosphere of it expected:<100> but was:<50>",
            on = "2026-10-17")
    @Test
    public void aRoomIsItsFractionsAndNotTheNumbersUnderneathThem() {
        AirState air = AirState.earthLike();

        // Computed from the state rather than restated: whatever the storage does, sea-level air is
        // a fifth oxygen at one atmosphere, which is what every threshold in this system is a
        // fraction OF.
        assertEquals("sea-level air is 21% oxygen",
                0.21D, (double) air.getOxygen() / air.getTotalPressure(), 0.0005D);
        assertEquals("and one whole atmosphere of it", 100, air.getPressureCentiAtm());
    }

    @RedWitness(verdict = "the fraction survives", member = "AirState#set", at = "long clamped = Math.max(0L, amount);",
            replacedBy = "long clamped = Math.max(0L, amount) / PER_PPM * PER_PPM;",
            fails = "the oxygen fraction survives being stored", on = "2026-10-17")
    @RedWitness(verdict = "thinner reads thinner", member = "AirState#set", at = "long clamped = Math.max(0L, amount);",
            replacedBy = "long clamped = Math.round(Math.max(0L, amount) / 160.0D) * 160L;",
            fails = "a trace 1% thinner must read as thinner, not as the same integer", on = "2026-10-17")
    @Test
    public void aTraceOnAThinWorldKeepsItsDigits() {
        // Mars: six millibars of air, of which 0.13% is oxygen. That trace is what decides whether a
        // base there can CONCENTRATE oxygen or has to make it, so a model that cannot tell 0.13%
        // from 0.12% has thrown the mechanic away before it is written. In millionths of an
        // atmosphere the whole trace is eight units, and eight units cannot carry that difference.
        long martianAir = ppm(5_921);
        long oxygenFraction = martianAir * 13 / 10_000;
        AirState mars = new AirState(martianAir - oxygenFraction, oxygenFraction, 0L);

        assertEquals("the oxygen fraction survives being stored",
                0.0013D, (double) mars.getOxygen() / mars.getTotalPressure(), 0.000013D);

        // And a world one part in a hundred poorer is a DIFFERENT world, not the same rounded number.
        AirState poorer = new AirState(martianAir - oxygenFraction, oxygenFraction * 99 / 100, 0L);
        assertTrue("a trace 1% thinner must read as thinner, not as the same integer: "
                        + mars.getOxygen() + " vs " + poorer.getOxygen(),
                poorer.getOxygen() < mars.getOxygen());
    }

    @RedWitness(verdict = "not saturated", member = "AirState#set", at = "long clamped = Math.max(0L, amount);",
            replacedBy = "long clamped = Math.min(Math.max(0L, amount), 1_000L * ONE_ATM);",
            fails = "five thousand atmospheres is a number, not a saturated ceiling", on = "2026-10-17")
    @RedWitness(verdict = "the trace", member = "AirState#set", at = "long clamped = Math.max(0L, amount);",
            replacedBy = "long clamped = amount < 10L ? 0L : amount;",
            fails = "and three parts per billion of ammonia is still there beside it", on = "2026-10-17")
    @RedWitness(verdict = "the total", member = "AirState#getTotalPressure", at = "total += amount;",
            replacedBy = "total = (int) (total + amount);", fails = "expected:<5000000000003> but was:<658067459>",
            on = "2026-10-17")
    @Test
    public void oneCompositionHoldsAGasGiantAndATraceAtTheSameTime() {
        // The two ends the model has to span at once: a giant's depths in one gas and a few parts per
        // billion in another. Both read back exactly, from the same composition — a range that only
        // reaches one end is a model that quietly picks which worlds may exist.
        long deep = 5_000L * AirState.ONE_ATM;
        AirState giant = new AirState(deep, 0L, 0L);
        giant.add(dev.stannismod.stellurgy.atmosphere.gas.GasRegistry.AMMONIA, 3L, 293.0D);

        assertEquals("five thousand atmospheres is a number, not a saturated ceiling",
                deep, giant.getNitrogen());
        assertEquals("and three parts per billion of ammonia is still there beside it",
                3L, giant.partialPressure(dev.stannismod.stellurgy.atmosphere.gas.GasRegistry.AMMONIA));
        assertEquals(deep + 3L, giant.getTotalPressure());
    }

    /**
     * <p>Not asserted: that the gone gas is not counted in the pressure, because the pressure is the
     * sum of the entries' amounts, so once "nothing is left of it" holds a gone gas adds nothing - the
     * check could not fail without the first one failing first.</p>
     */
    @RedWitness(verdict = "nothing left", member = "AirState#draw", at = "set(gas, partialPressure(gas) - taken);",
            replacedBy = "set(gas, partialPressure(gas) - taken / 2);", fails = "nothing is left of it", on = "2026-10-17")
    @RedWitness(verdict = "not in the composition", member = "AirState#set", at = "if (clamped == 0L)",
            replacedBy = "if (false)", fails = "and it is not in the composition either", on = "2026-10-17")
    @Test
    public void whatIsGoneIsAbsentEverywhereRatherThanKeptAsAZero() {
        AirState air = new AirState(ppm(790_000), ppm(210_000), ppm(1));

        air.drawCarbonDioxide(ppm(1));

        // The floor has to answer the same way to every question. A share that has run out must not
        // survive as an entry worth nothing: something that can be listed but never drawn is exactly
        // the inconsistency the model promises not to have.
        assertEquals("nothing is left of it", 0L, air.getCarbonDioxide());
        assertTrue("and it is not in the composition either: " + air.composition(),
                !air.composition().containsKey(
                        dev.stannismod.stellurgy.atmosphere.gas.GasRegistry.CARBON_DIOXIDE));
    }

    @RedWitness(verdict = "nitrogen", member = "AirState#writeToNBT", at = "entry.getValue());",
            replacedBy = "entry.getKey() == GasRegistry.NITROGEN ? entry.getValue() / 2 : entry.getValue());",
            fails = "expected:<700000000> but was:<350000000>", on = "2026-10-17")
    @RedWitness(verdict = "oxygen", member = "AirState#writeToNBT", at = "entry.getValue());",
            replacedBy = "entry.getKey() == GasRegistry.OXYGEN ? entry.getValue() / 2 : entry.getValue());",
            fails = "expected:<180000000> but was:<90000000>", on = "2026-10-17")
    @RedWitness(verdict = "carbon dioxide", member = "AirState#writeToNBT", at = "entry.getValue());",
            replacedBy = "entry.getKey() == GasRegistry.CARBON_DIOXIDE ? entry.getValue() / 2 : entry.getValue());",
            fails = "expected:<40000000> but was:<20000000>", on = "2026-10-17")
    @Test
    public void gasesSurviveASaveAndReload() {
        AirState air = new AirState(ppm(700_000), ppm(180_000), ppm(40_000));
        NBTTagCompound nbt = new NBTTagCompound();

        air.writeToNBT(nbt);
        AirState reloaded = AirState.readFromNBT(nbt);

        assertEquals(air.getNitrogen(), reloaded.getNitrogen());
        assertEquals(air.getOxygen(), reloaded.getOxygen());
        assertEquals(air.getCarbonDioxide(), reloaded.getCarbonDioxide());
    }
}
