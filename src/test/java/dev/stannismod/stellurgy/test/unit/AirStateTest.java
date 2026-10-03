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

    /**
     * <p>red-witnessed: with {@code AirState#getPressureCentiAtm} at {@code return (int) Math.min(Integer.MAX_VALUE, getTotalPressure() / (ONE_ATM / 100L));} dividing by a fiftieth of an atmosphere:
     * "expected:&lt;100&gt; but was:&lt;50&gt;", 2026-09-30.</p>
     */
    @Test
    public void breathableAirReadsAsOneAtmosphere() {
        // The pressure a sealed zone reports is what the analyser turns into "1.00 atm"; it read
        // a flat 100 before zones had contents and must keep reading 100 for untouched air.
        assertEquals(100, AirState.earthLike().getPressureCentiAtm());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. OXYGEN FALLS - {@code AirState#draw} at {@code set(gas, partialPressure(gas) - taken);}
     * removing half of what was drawn: "oxygen must fall by exactly what was breathed
     * expected:&lt;200000000&gt; but was:&lt;205000000&gt;". SAME AMOUNT AS CO2 -
     * {@code AirState#respire} at {@code set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);} adding twice the carbon dioxide: "the same amount must appear as CO2
     * expected:&lt;10000000&gt; but was:&lt;20000000&gt;". PRESSURE UNCHANGED - {@code AirState#respire} at {@code set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);}
     * followed by taking the converted amount out of the nitrogen too: "respiration rearranges air, it
     * does not consume it expected:&lt;1000000000&gt; but was:&lt;990000000&gt;".</p>
     */
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
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. ONLY WHAT IS PRESENT -
     * {@code AirState#draw} at {@code long taken = Math.min(Math.max(0L, amount), partialPressure(gas));} no longer clamping a draw to what is there: "only the oxygen present may be
     * converted expected:&lt;5000000&gt; but was:&lt;50000000&gt;". NONE LEFT - {@code AirState#draw} at {@code set(gas, partialPressure(gas) - taken);}
     * removing half of what was drawn: "expected:&lt;0&gt; but was:&lt;2500000&gt;". AS CO2 -
     * {@code AirState#respire} at {@code set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);} adding twice the carbon dioxide: "expected:&lt;5000000&gt; but
     * was:&lt;10000000&gt;".</p>
     */
    @Test
    public void breathingCannotTakeOxygenThatIsNotThere() {
        AirState air = new AirState(ppm(790_000), ppm(5_000), 0L);

        long taken = air.respire(ppm(50_000));

        assertEquals("only the oxygen present may be converted", ppm(5_000), taken);
        assertEquals(0L, air.getOxygen());
        assertEquals(ppm(5_000), air.getCarbonDioxide());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. ALL BACK AS OXYGEN -
     * {@code AirState#regenerate} at {@code set(GasRegistry.OXYGEN, getOxygen() + converted);} returning half of it: "all of it must come back as oxygen
     * expected:&lt;210000000&gt; but was:&lt;195000000&gt;". NO CO2 LEFT - {@code AirState#respire} at {@code set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);}
     * making twice the carbon dioxide while breathing, so regeneration leaves half behind:
     * "expected:&lt;0&gt; but was:&lt;30000000&gt;". THE CARBON - {@code AirState#regenerate} at {@code return converted;} reporting half
     * of what was converted: "the carbon that left the air is what the machine must now handle
     * expected:&lt;30000000&gt; but was:&lt;15000000&gt;". PRESSURE UNCHANGED - {@code AirState#regenerate} at {@code set(GasRegistry.OXYGEN, getOxygen() + converted);}
     * followed by taking the converted amount out of the nitrogen too: "pressure is unchanged: the
     * solid carbon never held any expected:&lt;1000000000&gt; but was:&lt;970000000&gt;".</p>
     */
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
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. ONLY WHAT IS PRESENT -
     * {@code AirState#draw} at {@code long taken = Math.min(Math.max(0L, amount), partialPressure(gas));} no longer clamping a draw to what is there: "only the CO2 present may be
     * processed expected:&lt;10000000&gt; but was:&lt;50000000&gt;". NONE LEFT - {@code AirState#draw} at {@code set(gas, partialPressure(gas) - taken);}
     * removing half of what was drawn: "expected:&lt;0&gt; but was:&lt;5000000&gt;". AS OXYGEN -
     * {@code AirState#regenerate} at {@code set(GasRegistry.OXYGEN, getOxygen() + converted);} returning half of it: "expected:&lt;210000000&gt; but
     * was:&lt;205000000&gt;".</p>
     */
    @Test
    public void regenerationCannotInventCarbonDioxide() {
        AirState air = new AirState(ppm(790_000), ppm(200_000), ppm(10_000));

        long carbon = air.regenerate(ppm(50_000));

        assertEquals("only the CO2 present may be processed", ppm(10_000), carbon);
        assertEquals(0L, air.getCarbonDioxide());
        assertEquals(ppm(210_000), air.getOxygen());
    }

    /**
     * <p>red-witnessed: with {@code AirState#regenerate} at {@code set(GasRegistry.OXYGEN, getOxygen() + converted);} putting no oxygen back: "and regeneration must be able
     * to undo that, not merely stop it", 2026-09-30. The premise before it is an arrangement and is not
     * witnessed.</p>
     */
    @Test
    public void aRecirculatorCanBringAStaleRoomBackIntoTheBand() {
        AirState air = AirState.earthLike();
        air.respire(ppm(60_000));
        assertSame("premise: the room has gone stale", Atmosphere.LOWOXYGEN, air.deriveAtmosphere());

        air.regenerate(ppm(60_000));

        assertTrue("and regeneration must be able to undo that, not merely stop it",
                air.deriveAtmosphere().isBreathable());
    }

    /**
     * <p>red-witnessed: with {@code AirState#oxygenRung} at {@code if (oxidiser < config.lifeSupportMinPartialO2)} doubling the lower edge of the band (taken on the pre-move form, when this statement stood verbatim in {@code deriveAtmosphere}): an
     * AssertionError with no message at the assertion, 2026-09-30.</p>
     */
    @Test
    public void airInsideTheSafeBandIsBreathable() {
        assertTrue(AirState.earthLike().deriveAtmosphere().isBreathable());
    }

    /**
     * <p>red-witnessed: with {@code AirState#oxygenRung} at {@code if (oxidiser < config.lifeSupportMinPartialO2)} moving the lower edge of the band down by 10 (taken on the pre-move form, when this statement stood verbatim in {@code deriveAtmosphere}): "expected
     * same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void oxygenBelowTheBandSuffocates() {
        AirState air = new AirState(ppm(790_000), SAFE_MIN - 1, ppm(10_000));
        assertSame(Atmosphere.LOWOXYGEN, air.deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#oxygenRung} at {@code return oxidiser <= 0L ? Atmosphere.NOO2 : Atmosphere.LOWOXYGEN;} calling air with no oxygen at all merely low on it (taken on the pre-move form, when this statement stood verbatim in {@code deriveAtmosphere}):
     * "expected same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void airWithNoOxygenLeftIsNotMerelyLowOnIt() {
        AirState air = new AirState(ppm(790_000), 0L, ppm(210_000));
        assertSame(Atmosphere.NOO2, air.deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. HIGH OXYGEN - {@code AirState#oxygenRung} at {@code if (oxidiser > config.lifeSupportMaxPartialO2)}
     * moving the upper edge of the band up by 10 (taken on the pre-move form, when this statement stood
     * verbatim in {@code deriveAtmosphere}): "expected same:&lt;Atmosphere@...&gt; was
     * not:&lt;Atmosphere@...&gt;". STILL FEEDS FIRE - the {@code Atmosphere#HIGHOXYGEN} at {@code new Atmosphere(true, false, true, "highO2")}
     * constant declaring the label non-flammable: "an oxygen-rich room being flammable is the hazard,
     * not a bug". NOT BREATHABLE - the same constant declaring the label breathable: an AssertionError with no message. The last
     * two read the label's hand-assigned flags, not the air.</p>
     */
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

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code if (config.shipHeatCrewVeryHotKelvin > 0 && kelvin >= config.shipHeatCrewVeryHotKelvin)} comparing strictly above the rung: "a breathable room
     * can still be a room that cooks you expected same:&lt;Atmosphere@...&gt; was
     * not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void airHotEnoughToHurtIsTheSameHostileAtmosphereAScorchingPlanetPresents() {
        assertSame("a breathable room can still be a room that cooks you",
                Atmosphere.VERYHOT, earthLikeAt(VERY_HOT).deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code if (config.shipHeatCrewVeryHotKelvin > 0 && kelvin >= config.shipHeatCrewVeryHotKelvin)} starting the rung one kelvin early: "the rung is a
     * threshold, not a slope: below it the gases decide alone expected same:&lt;Atmosphere@...&gt; was
     * not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void airJustBelowTheRungIsUnaffectedByHowWarmItIs() {
        assertSame("the rung is a threshold, not a slope: below it the gases decide alone",
                Atmosphere.PRESSURIZEDAIR, earthLikeAt(VERY_HOT - 1).deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code && kelvin >= config.shipHeatCrewSuperheatedKelvin)} comparing strictly above the harsher rung: "expected
     * same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void lethallyHotAirIsTheHarsherOfTheTwoRungs() {
        assertSame(Atmosphere.SUPERHEATED, earthLikeAt(SUPERHEATED).deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code return breathableGas ? Atmosphere.SUPERHEATED : Atmosphere.SUPERHEATEDNOO2;} answering the breathable variant for unbreathable air:
     * "the NoO2 variants exist precisely so neither hazard hides the other expected
     * same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void hotAirWithNothingToBreatheSaysBothThingsAtOnce() {
        AirState suffocatingAndHot = new AirState(ppm(1_000_000), 0L, 0L, SUPERHEATED * 1000);

        assertSame("the NoO2 variants exist precisely so neither hazard hides the other",
                Atmosphere.SUPERHEATEDNOO2, suffocatingAndHot.deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code if (config.shipHeat)} asking about heat only while the oxygen is under its
     * ceiling: "a room that is burning its crew is not made safe by its gas mix expected
     * same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void heatOutranksAnOxygenSurplus() {
        AirState enrichedAndHot = new AirState(ppm(400_000), SAFE_MAX + 1, 0L, VERY_HOT * 1000);

        assertSame("a room that is burning its crew is not made safe by its gas mix",
                Atmosphere.VERYHOT, enrichedAndHot.deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with BOTH {@code AirState#deriveAtmosphere} at {@code if (getTotalPressure() <= VACUUM_CEILING)} no longer calling an empty zone a vacuum AND
     * {@code AirState#getTemperatureKelvin} at {@code if (getTotalPressure() <= 0L)} no longer reporting an empty zone at ambient: "there is no body left in the
     * room to be hot expected same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.
     * With {@code AirState#deriveAtmosphere} at {@code if (getTotalPressure() <= VACUUM_CEILING)} alone it also goes red, but on the no-oxygen reading
     * {@code aZoneWithNoGasInItIsVacuumWhateverItsComposition} already pins, not on heat.</p>
     */
    @Test
    public void aVacuumIsNotHotHoweverHotTheGasThatLeftItWas() {
        AirState breached = new AirState(0L, 0L, 0L, SUPERHEATED * 1000);

        assertSame("there is no body left in the room to be hot",
                Atmosphere.VACUUM, breached.deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code if (config.shipHeatCrewVeryHotKelvin > 0 && kelvin >= config.shipHeatCrewVeryHotKelvin)} no longer treating a zero threshold as no rung: "an
     * unloaded or switched-off threshold must not make every room lethal expected
     * same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void aThresholdOfZeroIsNoRungRatherThanARungEveryRoomTrips() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeatCrewVeryHotKelvin = 0;
        config.shipHeatCrewSuperheatedKelvin = 0;

        assertSame("an unloaded or switched-off threshold must not make every room lethal",
                Atmosphere.PRESSURIZEDAIR, earthLikeAt(1_000).deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code if (config.shipHeat)} running the heat rungs whatever the flag says: "the
     * flag that removes the mechanic removes its hazard too expected same:&lt;Atmosphere@...&gt; was
     * not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void withShipHeatOffARoomNeverCooksItsCrew() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeat = false;

        assertSame("the flag that removes the mechanic removes its hazard too",
                Atmosphere.PRESSURIZEDAIR, earthLikeAt(1_000).deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#deriveAtmosphere} at {@code if (getTotalPressure() <= VACUUM_CEILING)} no longer calling an empty zone a vacuum: "expected
     * same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;", 2026-09-30.</p>
     */
    @Test
    public void aZoneWithNoGasInItIsVacuumWhateverItsComposition() {
        assertSame(Atmosphere.VACUUM, AirState.vacuum().deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with the guard at {@code AirState#oxygenRung} at {@code if (config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2)} disabled (taken on the pre-move form, when this statement stood verbatim in {@code deriveAtmosphere}): "no usable band means no
     * governor, not a hazard expected same:&lt;Atmosphere@...&gt; was not:&lt;Atmosphere@...&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void anUnconfiguredSafeBandGovernsNothing() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = 0L;
        config.lifeSupportMaxPartialO2 = 0L;

        assertSame("no usable band means no governor, not a hazard",
                Atmosphere.PRESSURIZEDAIR, AirState.earthLike().deriveAtmosphere());
    }

    /**
     * <p>red-witnessed: with {@code AirState#oxygenHeadroom} at {@code return Math.max(0L, config.lifeSupportMaxPartialO2 - getOxygen());} measuring to twice the ceiling: "headroom is the
     * distance to the ceiling, not to infinity expected:&lt;90000000&gt; but was:&lt;390000000&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void theGovernorLeavesRoomOnlyUpToTheToxicityCeiling() {
        AirState air = AirState.earthLike();

        assertEquals("headroom is the distance to the ceiling, not to infinity",
                SAFE_MAX - ppm(210_000), air.oxygenHeadroom());
    }

    /**
     * <p>red-witnessed: with {@code AirState#oxygenHeadroom} at {@code return Math.max(0L, config.lifeSupportMaxPartialO2 - getOxygen());} no longer flooring the headroom at zero: "a combiner
     * must be unable to make a fire hazard worse expected:&lt;0&gt; but was:&lt;-50000000&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void anAlreadyEnrichedRoomGetsNoMoreOxygen() {
        AirState air = new AirState(ppm(400_000), SAFE_MAX + ppm(50_000), 0L);

        assertEquals("a combiner must be unable to make a fire hazard worse", 0L, air.oxygenHeadroom());
    }

    /**
     * <p>red-witnessed: with the guard at {@code AirState#oxygenHeadroom} at {@code if (config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2)} disabled: "with no band there is no
     * governor, in both directions expected:&lt;9223372036854775807&gt; but was:&lt;0&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void anUnconfiguredBandImposesNoCeilingEither() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = 0L;
        config.lifeSupportMaxPartialO2 = 0L;

        assertEquals("with no band there is no governor, in both directions",
                Long.MAX_VALUE, AirState.earthLike().oxygenHeadroom());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. CO2 TAKEN - {@code AirState#drawCarbonDioxide} at {@code long taken = draw(GasRegistry.CARBON_DIOXIDE, amount);} drawing
     * half of what was asked: "expected:&lt;50000000&gt; but was:&lt;25000000&gt;". N2 TAKEN -
     * {@code AirState#drawNitrogen} at {@code long taken = draw(GasRegistry.NITROGEN, amount);} drawing half of what was asked: "expected:&lt;40000000&gt; but
     * was:&lt;20000000&gt;". OXYGEN UNTOUCHED - {@code AirState#drawNitrogen} at {@code long taken = draw(GasRegistry.NITROGEN, amount);} drawing the same amount of oxygen
     * too: "splitting must not touch the oxygen the crew are breathing expected:&lt;210000000&gt; but
     * was:&lt;170000000&gt;". CO2 LEFT - {@code AirState#draw} at {@code set(gas, partialPressure(gas) - taken);} removing half of what was drawn:
     * "expected:&lt;40000000&gt; but was:&lt;65000000&gt;". N2 LEFT - {@code AirState#drawNitrogen} at {@code long taken = draw(GasRegistry.NITROGEN, amount);} drawing half
     * and reporting twice that: "expected:&lt;750000000&gt; but was:&lt;770000000&gt;".</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. 21% - {@code AirState#earthLike} at {@code return new AirState(790_000 * PER_PPM, 210_000 * PER_PPM, 0L);} making
     * sea-level air 20% oxygen: "sea-level air is 21% oxygen expected:&lt;0.21&gt; but
     * was:&lt;0.20202020202020202&gt;". ONE ATMOSPHERE - {@code AirState#getPressureCentiAtm} at {@code return (int) Math.min(Integer.MAX_VALUE, getTotalPressure() / (ONE_ATM / 100L));} dividing by a fiftieth of
     * an atmosphere: "and one whole atmosphere of it expected:&lt;100&gt; but was:&lt;50&gt;".</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. THE FRACTION SURVIVES -
     * {@code AirState#set} at {@code long clamped = Math.max(0L, amount);} storing whole parts per million only: "the oxygen fraction survives being
     * stored expected:&lt;0.0013&gt; but was:&lt;0.0011824324324324325&gt;". THINNER READS THINNER -
     * {@code AirState#set} at {@code long clamped = Math.max(0L, amount);} rounding every amount to 150 units: "a trace 1% thinner must read as
     * thinner, not as the same integer: 7650 vs 7650".</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NOT SATURATED - {@code AirState#set} at {@code long clamped = Math.max(0L, amount);}
     * capping every amount at a thousand atmospheres: "five thousand atmospheres is a number, not a
     * saturated ceiling expected:&lt;5000000000000&gt; but was:&lt;1000000000000&gt;". THE TRACE -
     * {@code AirState#set} at {@code long clamped = Math.max(0L, amount);} dropping anything under ten units: "and three parts per billion of ammonia
     * is still there beside it expected:&lt;3&gt; but was:&lt;0&gt;". THE TOTAL - {@code AirState#getTotalPressure} at {@code total += amount;}
     * summing through an int: "expected:&lt;5000000000003&gt; but was:&lt;658067459&gt;".</p>
     */
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
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NOTHING LEFT -
     * {@code AirState#draw} at {@code set(gas, partialPressure(gas) - taken);} removing half of what was drawn: "nothing is left of it expected:&lt;0&gt;
     * but was:&lt;500&gt;". NOT IN THE COMPOSITION - {@code AirState#set} at {@code if (clamped == 0L)} keeping an entry that reached
     * zero: "and it is not in the composition either: {... carbondioxide [WASTE]]=0}".</p>
     *
     * <p>Not asserted: that the gone gas is not counted in the pressure, because the pressure is the
     * sum of the entries' amounts, so once "nothing is left of it" holds a gone gas adds nothing - the
     * check could not fail without the first one failing first.</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, each at {@code AirState#writeToNBT} at {@code gases.setLong(entry.getKey().name(), entry.getValue());} writing one
     * gas at half its amount. NITROGEN: "expected:&lt;700000000&gt; but was:&lt;350000000&gt;". OXYGEN:
     * "expected:&lt;180000000&gt; but was:&lt;90000000&gt;". CARBON DIOXIDE: "expected:&lt;40000000&gt;
     * but was:&lt;20000000&gt;".</p>
     */
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
