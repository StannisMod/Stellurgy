package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The gas contents of a life-support zone: what a room's air is made of, what pressure that
 * amounts to, and which atmosphere the rest of the mod therefore sees.
 */
public class AirStateTest {

    private static final long SAFE_MIN = ppm(160_000);
    private static final long SAFE_MAX = ppm(300_000);

    /**
     * Parts per million of an atmosphere. Every quantity below is a FRACTION of an atmosphere and
     * says so, rather than a count of whatever the model happens to store internally — which is what
     * lets the unit underneath change without a single scenario changing what it means.
     */
    private static long ppm(long partsPerMillion) {
        return partsPerMillion * AirState.PER_PPM;
    }

    /** Where the crew rungs sit for these tests, so no assertion depends on the shipped defaults. */
    private static final int VERY_HOT = 323;
    private static final int SUPERHEATED = 373;

    private long prevMin;
    private long prevMax;
    private boolean prevShipHeat;
    private int prevVeryHot;
    private int prevSuperheated;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void setSafeBand() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        prevMin = config.lifeSupportMinPartialO2;
        prevMax = config.lifeSupportMaxPartialO2;
        prevShipHeat = config.shipHeat;
        prevVeryHot = config.shipHeatCrewVeryHotKelvin;
        prevSuperheated = config.shipHeatCrewSuperheatedKelvin;
        config.lifeSupportMinPartialO2 = SAFE_MIN;
        config.lifeSupportMaxPartialO2 = SAFE_MAX;
        config.shipHeat = true;
        config.shipHeatCrewVeryHotKelvin = VERY_HOT;
        config.shipHeatCrewSuperheatedKelvin = SUPERHEATED;
    }

    @After
    public void restoreSafeBand() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = prevMin;
        config.lifeSupportMaxPartialO2 = prevMax;
        config.shipHeat = prevShipHeat;
        config.shipHeatCrewVeryHotKelvin = prevVeryHot;
        config.shipHeatCrewSuperheatedKelvin = prevSuperheated;
    }

    /** Breathable sea-level air at a stated temperature, in kelvin. */
    private static AirState earthLikeAt(int kelvin) {
        return new AirState(ppm(790_000), ppm(210_000), 0L, kelvin * 1000);
    }

    @Test
    public void breathableAirReadsAsOneAtmosphere() {
        // The pressure a sealed zone reports is what the analyser turns into "1.00 atm"; it read
        // a flat 100 before zones had contents and must keep reading 100 for untouched air.
        assertEquals(100, AirState.earthLike().getPressureCentiAtm());
    }

    @Test
    public void breathingConvertsOxygenIntoCarbonDioxideWithoutChangingPressure() {
        AirState air = AirState.earthLike();
        long before = air.getTotalPressure();

        air.respire(ppm(10_000));

        assertEquals("oxygen must fall by exactly what was breathed", ppm(200_000), air.getOxygen());
        assertEquals("the same amount must appear as CO2", ppm(10_000), air.getCarbonDioxide());
        assertEquals("respiration rearranges air, it does not consume it", before, air.getTotalPressure());
    }

    @Test
    public void breathingCannotTakeOxygenThatIsNotThere() {
        AirState air = new AirState(ppm(790_000), ppm(5_000), 0L);

        long taken = air.respire(ppm(50_000));

        assertEquals("only the oxygen present may be converted", ppm(5_000), taken);
        assertEquals(0L, air.getOxygen());
        assertEquals(ppm(5_000), air.getCarbonDioxide());
    }

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

    @Test
    public void regenerationCannotInventCarbonDioxide() {
        AirState air = new AirState(ppm(790_000), ppm(200_000), ppm(10_000));

        long carbon = air.regenerate(ppm(50_000));

        assertEquals("only the CO2 present may be processed", ppm(10_000), carbon);
        assertEquals(0L, air.getCarbonDioxide());
        assertEquals(ppm(210_000), air.getOxygen());
    }

    @Test
    public void aRecirculatorCanBringAStaleRoomBackIntoTheBand() {
        AirState air = AirState.earthLike();
        air.respire(ppm(60_000));
        assertSame("premise: the room has gone stale", Atmosphere.LOWOXYGEN, air.deriveAtmosphere());

        air.regenerate(ppm(60_000));

        assertTrue("and regeneration must be able to undo that, not merely stop it",
                air.deriveAtmosphere().isBreathable());
    }

    @Test
    public void airInsideTheSafeBandIsBreathable() {
        assertTrue(AirState.earthLike().deriveAtmosphere().isBreathable());
    }

    @Test
    public void oxygenBelowTheBandSuffocates() {
        AirState air = new AirState(ppm(790_000), SAFE_MIN - 1, ppm(10_000));
        assertSame(Atmosphere.LOWOXYGEN, air.deriveAtmosphere());
    }

    @Test
    public void airWithNoOxygenLeftIsNotMerelyLowOnIt() {
        AirState air = new AirState(ppm(790_000), 0L, ppm(210_000));
        assertSame(Atmosphere.NOO2, air.deriveAtmosphere());
    }

    @Test
    public void oxygenAboveTheBandIsToxicAndStillFeedsFire() {
        AirState air = new AirState(ppm(400_000), SAFE_MAX + 1, 0L);

        assertSame(Atmosphere.HIGHOXYGEN, air.deriveAtmosphere());
        assertTrue("an oxygen-rich room being flammable is the hazard, not a bug",
                Atmosphere.HIGHOXYGEN.allowsCombustion());
        assertTrue(!Atmosphere.HIGHOXYGEN.isBreathable());
    }

    // ─── The first rung of the failure ladder: hot air is what hurts the crew ───────────────────
    //
    // The subject is the zone's AIR and the consequence is one of the hostile atmospheres a scorching
    // planet already presents, so the suit that protects there protects here. What these pin is that
    // the room's temperature decides the rung and its gases only decide which variant of it - never
    // that a particular number is dangerous, which is config.

    @Test
    public void airHotEnoughToHurtIsTheSameHostileAtmosphereAScorchingPlanetPresents() {
        assertSame("a breathable room can still be a room that cooks you",
                Atmosphere.VERYHOT, earthLikeAt(VERY_HOT).deriveAtmosphere());
    }

    @Test
    public void airJustBelowTheRungIsUnaffectedByHowWarmItIs() {
        assertSame("the rung is a threshold, not a slope: below it the gases decide alone",
                Atmosphere.PRESSURIZEDAIR, earthLikeAt(VERY_HOT - 1).deriveAtmosphere());
    }

    @Test
    public void lethallyHotAirIsTheHarsherOfTheTwoRungs() {
        assertSame(Atmosphere.SUPERHEATED, earthLikeAt(SUPERHEATED).deriveAtmosphere());
    }

    @Test
    public void hotAirWithNothingToBreatheSaysBothThingsAtOnce() {
        AirState suffocatingAndHot = new AirState(ppm(1_000_000), 0L, 0L, SUPERHEATED * 1000);

        assertSame("the NoO2 variants exist precisely so neither hazard hides the other",
                Atmosphere.SUPERHEATEDNOO2, suffocatingAndHot.deriveAtmosphere());
    }

    @Test
    public void heatOutranksAnOxygenSurplus() {
        AirState enrichedAndHot = new AirState(ppm(400_000), SAFE_MAX + 1, 0L, VERY_HOT * 1000);

        assertSame("a room that is burning its crew is not made safe by its gas mix",
                Atmosphere.VERYHOT, enrichedAndHot.deriveAtmosphere());
    }

    @Test
    public void aVacuumIsNotHotHoweverHotTheGasThatLeftItWas() {
        AirState breached = new AirState(0L, 0L, 0L, SUPERHEATED * 1000);

        assertSame("there is no body left in the room to be hot",
                Atmosphere.VACUUM, breached.deriveAtmosphere());
    }

    @Test
    public void aThresholdOfZeroIsNoRungRatherThanARungEveryRoomTrips() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeatCrewVeryHotKelvin = 0;
        config.shipHeatCrewSuperheatedKelvin = 0;

        assertSame("an unloaded or switched-off threshold must not make every room lethal",
                Atmosphere.PRESSURIZEDAIR, earthLikeAt(1_000).deriveAtmosphere());
    }

    @Test
    public void withShipHeatOffARoomNeverCooksItsCrew() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeat = false;

        assertSame("the flag that removes the mechanic removes its hazard too",
                Atmosphere.PRESSURIZEDAIR, earthLikeAt(1_000).deriveAtmosphere());
    }

    @Test
    public void aZoneWithNoGasInItIsVacuumWhateverItsComposition() {
        assertSame(Atmosphere.VACUUM, AirState.vacuum().deriveAtmosphere());
    }

    @Test
    public void anUnconfiguredSafeBandGovernsNothing() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = 0L;
        config.lifeSupportMaxPartialO2 = 0L;

        assertSame("no usable band means no governor, not a hazard",
                Atmosphere.PRESSURIZEDAIR, AirState.earthLike().deriveAtmosphere());
    }

    @Test
    public void theGovernorLeavesRoomOnlyUpToTheToxicityCeiling() {
        AirState air = AirState.earthLike();

        assertEquals("headroom is the distance to the ceiling, not to infinity",
                SAFE_MAX - ppm(210_000), air.oxygenHeadroom());
    }

    @Test
    public void anAlreadyEnrichedRoomGetsNoMoreOxygen() {
        AirState air = new AirState(ppm(400_000), SAFE_MAX + ppm(50_000), 0L);

        assertEquals("a combiner must be unable to make a fire hazard worse", 0L, air.oxygenHeadroom());
    }

    @Test
    public void anUnconfiguredBandImposesNoCeilingEither() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = 0L;
        config.lifeSupportMaxPartialO2 = 0L;

        assertEquals("with no band there is no governor, in both directions",
                Long.MAX_VALUE, AirState.earthLike().oxygenHeadroom());
    }

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
        assertEquals("nor counted in the pressure", ppm(1_000_000), air.getTotalPressure());
    }

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
