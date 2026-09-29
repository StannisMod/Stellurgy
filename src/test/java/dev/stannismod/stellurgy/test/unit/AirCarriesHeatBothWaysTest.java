package dev.stannismod.stellurgy.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A compartment's air is a heat reservoir, and a reservoir works in both directions.
 *
 * <p>The contract these pin is that air can be WARMED, not merely cooled, and that a machine wanting
 * to cool it can find out how much there is to take before it charges for taking it. Both were
 * missing: the class exposed removal alone, so a room could only ever get colder, the two crew
 * hazard thresholds keyed on its temperature could never be reached, and a chiller billed its loop
 * for throughput against air that had nothing left to give.
 */
public class AirCarriesHeatBothWaysTest {

    private static final int VOLUME = 20;
    /**
     * Pinned here so no assertion depends on the shipped defaults moving -- and because a unit test
     * runs against a config nobody loaded, where every one of these is 0 until it is said out loud.
     * A room with no heat capacity and an ambient of 1 K is not a smaller version of this scenario,
     * it is a different one.
     */
    private static final int VERY_HOT = 323;
    private static final int AMBIENT_K = 293;
    private static final int AIR_HEAT_CAPACITY = 40;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private boolean prevShipHeat;
    private int prevVeryHot;
    private int prevAmbient;
    private int prevAirCapacity;

    @Before
    public void armTheThermalSystem() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        prevShipHeat = config.shipHeat;
        prevVeryHot = config.shipHeatCrewVeryHotKelvin;
        prevAmbient = config.shipHeatAmbientKelvin;
        prevAirCapacity = config.lifeSupportAirHeatCapacity;
        config.shipHeat = true;
        config.shipHeatCrewVeryHotKelvin = VERY_HOT;
        config.shipHeatAmbientKelvin = AMBIENT_K;
        config.lifeSupportAirHeatCapacity = AIR_HEAT_CAPACITY;
    }

    @After
    public void restoreConfig() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeat = prevShipHeat;
        config.shipHeatCrewVeryHotKelvin = prevVeryHot;
        config.shipHeatAmbientKelvin = prevAmbient;
        config.lifeSupportAirHeatCapacity = prevAirCapacity;
    }

    private static AirState room() {
        return AirState.earthLike();
    }

    /** Heat put in comes back out as temperature, at the capacity the room actually has. */
    @Test
    public void warmingAirRaisesItsTemperatureByTheEnergyOverItsCapacity() {
        AirState air = room();
        double before = air.getTemperatureKelvin();
        long capacity = air.getHeatCapacity(VOLUME);
        assertTrue("a pressurised room must have a heat capacity to warm at all", capacity > 0L);

        long accepted = air.addHeat(capacity * 30L, VOLUME);

        assertEquals("all of it is accepted: air has no ceiling short of the model's own",
                capacity * 30L, accepted);
        assertEquals("thirty kelvin of energy is thirty kelvin of temperature",
                before + 30.0D, air.getTemperatureKelvin(), 0.5D);
    }

    /**
     * The rung a hot ship is supposed to reach. Nothing in production could put a compartment here
     * before, so the two thresholds that read it described a mechanic that never ran.
     */
    @Test
    public void aCompartmentCanBeDrivenPastTheCrewHazardThresholds() {
        AirState air = room();
        int veryHot = StellurgyConfiguration.getCurrentConfig().shipHeatCrewVeryHotKelvin;
        assertTrue("this test is meaningless if the rung is switched off", veryHot > 0);

        long capacity = air.getHeatCapacity(VOLUME);
        double gap = veryHot - air.getTemperatureKelvin();
        air.addHeat(Math.round(gap * capacity) + capacity, VOLUME);

        assertTrue("a room the loop has been warming must be able to reach the hostile rung: "
                        + air.getTemperatureKelvin() + " K against " + veryHot,
                air.getTemperatureKelvin() >= veryHot);
    }

    /** What is put in can be taken out again, and no more than that. */
    @Test
    public void whatWasPutInIsWhatCanBeTakenOut() {
        AirState air = room();
        long capacity = air.getHeatCapacity(VOLUME);
        long budgetAtStart = air.availableHeat(VOLUME);

        air.addHeat(capacity * 10L, VOLUME);
        assertEquals("the removable budget grows by exactly what was added",
                budgetAtStart + capacity * 10L, air.availableHeat(VOLUME), capacity);

        long taken = air.removeHeat(capacity * 10L, VOLUME);
        assertEquals("and taking it back leaves the room where it started",
                capacity * 10L, taken);
    }

    /**
     * The reading a chiller has to consult before it charges. Air with nothing left to give answers
     * zero, which is what stops the machine paying full price to move nothing.
     */
    @Test
    public void airWithNothingLeftToGiveOffersNothingToTake() {
        AirState air = room();
        air.removeHeat(Long.MAX_VALUE / 4, VOLUME);

        assertEquals("a room already at the floor has no heat to sell", 0L, air.availableHeat(VOLUME));
        assertEquals("and none can be taken from it", 0L, air.removeHeat(1000L, VOLUME));
    }

    /** A vacuum is not a reservoir: there is no body there to warm. */
    @Test
    public void vacuumTakesNoHeatAtAll() {
        AirState vacuum = AirState.vacuum();
        assertEquals("nothing to warm", 0L, vacuum.addHeat(1_000_000L, VOLUME));
        assertEquals("and nothing to cool", 0L, vacuum.availableHeat(VOLUME));
    }
}
