package dev.stannismod.stellurgy.test.integration;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A planet's outdoor air is judged by the breathing band a room is judged by: an oxygen world dense
 * enough that its oxygen alone is past the band's ceiling is too much oxygen, not air.
 *
 * <p>The band is the TEST's, set here and restored after, so the verdict follows the configured
 * numbers rather than whatever the shipped defaults happen to be. The oxygen share is the one an
 * oxygen world is given — Earth's — and the densities are chosen either side of the ceiling it
 * implies.</p>
 */
public class PlanetOutdoorsObeysTheBreathingBandTest {

    /** The band this test configures, in the composition's unit. */
    private static final long BAND_FLOOR = 160_000L * AirState.PER_PPM;
    private static final long BAND_CEILING = 300_000L * AirState.PER_PPM;

    private long savedFloor;
    private long savedCeiling;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void configureTheBand() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        savedFloor = config.lifeSupportMinPartialO2;
        savedCeiling = config.lifeSupportMaxPartialO2;
        config.lifeSupportMinPartialO2 = BAND_FLOOR;
        config.lifeSupportMaxPartialO2 = BAND_CEILING;
    }

    @After
    public void restoreTheBand() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = savedFloor;
        config.lifeSupportMaxPartialO2 = savedCeiling;
    }

    /** An Earth-sized oxygen world at a temperate 288 K, given air at {@code density} (100 = 1 atm). */
    private static DimensionProperties oxygenWorld(int id, int density) {
        DimensionProperties world = new DimensionProperties(id, "Oxygen" + density);
        world.setBulk(1d, 1d);
        world.setAverageTemp(288);
        world.realizeAtmosphere(true, density);
        return world;
    }

    /**
     * red-witnessed: with {@code DimensionProperties#getAtmosphere} at
     * {@code return air.oxygenRung(Atmosphere.AIR);} replaced by {@code return Atmosphere.AIR;}, this
     * fails on the 1.5 atm world with {@code expected:<[highO2]> but was:<[air]>}, the control passing
     * (2026-10-01).
     */
    @Test
    public void anOxygenWorldPastTheCeilingReadsAsTooMuchOxygenAndOneInsideItAsAir() {
        // CONTROL: the same kind of world, at sea level, is inside the band — so the verdict below is
        // the density, not a world that would read as too much oxygen whatever its pressure.
        assertEquals("an oxygen world at 1 atm sits inside the band and is air",
                Atmosphere.AIR.getUnlocalizedName(), oxygenWorld(9861, 100).getAtmosphere().getUnlocalizedName());

        assertEquals("an oxygen world at 1.5 atm holds more oxygen than the band's ceiling",
                Atmosphere.HIGHOXYGEN.getUnlocalizedName(),
                oxygenWorld(9862, 150).getAtmosphere().getUnlocalizedName());
    }
}
