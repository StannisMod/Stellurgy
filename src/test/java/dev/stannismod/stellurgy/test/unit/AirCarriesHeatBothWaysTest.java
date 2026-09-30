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

    /**
     * Heat put in comes back out as temperature, at the capacity the room actually has.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. ALL ACCEPTED - {@code AirState:361}
     * answering half the energy as accepted: "all of it is accepted: air has no ceiling short of the
     * model's own expected:&lt;24000&gt; but was:&lt;12000&gt;". THIRTY KELVIN - {@code AirState:356}
     * raising the air by half the energy over its capacity: "thirty kelvin of energy is thirty kelvin
     * of temperature expected:&lt;323000&gt; but was:&lt;308000&gt;". The premise before them is an
     * arrangement and is not witnessed.</p>
     */
    @Test
    public void warmingAirRaisesItsTemperatureByTheEnergyOverItsCapacity() {
        AirState air = room();
        int before = air.getTemperatureMilliK();
        long capacity = air.getHeatCapacity(VOLUME);
        assertTrue("a pressurised room must have a heat capacity to warm at all", capacity > 0L);

        long accepted = air.addHeat(capacity * 30L, VOLUME);

        assertEquals("all of it is accepted: air has no ceiling short of the model's own",
                capacity * 30L, accepted);
        // EXACT, in the unit the air stores: `addHeat` adds `amount / capacity` kelvin and rounds to a
        // milli-kelvin, and thirty capacities is thirty whole kelvin — nothing is left to round.
        assertEquals("thirty kelvin of energy is thirty kelvin of temperature",
                before + 30_000, air.getTemperatureMilliK());
    }

    /**
     * The rung a hot ship is supposed to reach. Nothing in production could put a compartment here
     * before, so the two thresholds that read it described a mechanic that never ran.
     *
     * <p>red-witnessed: with {@code AirState:356} raising the air by half the energy over its
     * capacity: "a room the loop has been warming must be able to reach the hostile rung: 308.5 K
     * against 323", 2026-09-30. The premise before it is an arrangement and is not witnessed.</p>
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

    /**
     * What is put in can be taken out again, and no more than that.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. THE BUDGET GROWS -
     * {@code AirState:356} raising the air by half the energy over its capacity: "the removable budget
     * grows by exactly what was added expected:&lt;242400.0&gt; but was:&lt;238400.0&gt;". TAKEN BACK
     * - {@code AirState:316} taking half of what was asked: "and taking it back leaves the room where
     * it started expected:&lt;8000&gt; but was:&lt;4000&gt;".</p>
     */
    @Test
    public void whatWasPutInIsWhatCanBeTakenOut() {
        AirState air = room();
        long capacity = air.getHeatCapacity(VOLUME);
        long budgetAtStart = air.availableHeat(VOLUME);

        air.addHeat(capacity * 10L, VOLUME);
        // One unit, not one kelvin's worth: `availableHeat` is `(long)` of temperature × capacity, a
        // truncation of less than one unit on each of the two readings, so their difference can sit
        // at most one unit from what was added.
        assertEquals("the removable budget grows by exactly what was added",
                budgetAtStart + capacity * 10L, air.availableHeat(VOLUME), 1L);

        long taken = air.removeHeat(capacity * 10L, VOLUME);
        assertEquals("and taking it back leaves the room where it started",
                capacity * 10L, taken);
    }

    /**
     * The reading a chiller has to consult before it charges. Air with nothing left to give answers
     * zero, which is what stops the machine paying full price to move nothing.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NOTHING TO SELL - {@code AirState:320}
     * flooring the air at 1 K instead of 0: "a room already at the floor has no heat to sell
     * expected:&lt;0&gt; but was:&lt;800&gt;". NOTHING TO TAKE - {@code AirState:316} taking what is
     * asked whatever is there: "and none can be taken from it expected:&lt;0&gt; but
     * was:&lt;1000&gt;".</p>
     */
    @Test
    public void airWithNothingLeftToGiveOffersNothingToTake() {
        AirState air = room();
        air.removeHeat(Long.MAX_VALUE / 4, VOLUME);

        assertEquals("a room already at the floor has no heat to sell", 0L, air.availableHeat(VOLUME));
        assertEquals("and none can be taken from it", 0L, air.removeHeat(1000L, VOLUME));
    }

    /**
     * A vacuum is not a reservoir: there is no body there to warm.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NOTHING TO WARM - {@code AirState:354}
     * no longer refusing air with no heat capacity: "nothing to warm expected:&lt;0&gt; but
     * was:&lt;1000000&gt;". NOTHING TO COOL - {@code AirState:334} answering the ambient temperature
     * for air with no heat capacity: "and nothing to cool expected:&lt;0&gt; but
     * was:&lt;293&gt;".</p>
     */
    @Test
    public void vacuumTakesNoHeatAtAll() {
        AirState vacuum = AirState.vacuum();
        assertEquals("nothing to warm", 0L, vacuum.addHeat(1_000_000L, VOLUME));
        assertEquals("and nothing to cool", 0L, vacuum.availableHeat(VOLUME));
    }
}
