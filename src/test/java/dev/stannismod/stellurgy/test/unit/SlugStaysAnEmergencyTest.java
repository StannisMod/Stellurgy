package dev.stannismod.stellurgy.test.unit;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterial;
import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterials;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertTrue;

/**
 * A slug is SPENT: what it carries away leaves the ship with it.
 *
 * <p>The other half of the dump's bargain — sustained dump throughput must stay under the cheapest
 * radiator — used to live here too, computed from numbers this class set itself in {@code @Before}
 * while its javadoc said "the config the game is actually running". A copy of the defaults cannot
 * notice the defaults changing, so that check now reads the running server's config instead:
 * {@code server/HeatDumpBuysSecondsTest.atTheShippedDefaultsOneDumpOutshedsOneRadiatingCell}.</p>
 *
 * <p>The {@code @Before} below still copies the defaults, and the seconds threshold here has no
 * derivation yet: how many seconds "seconds" is has not been decided.</p>
 */
public class SlugStaysAnEmergencyTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void shippedDefaults() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeatAmbientKelvin = 293;
        config.shipHeatSlugMarginKelvin = 100;
        config.shipHeatSlugJoulesPerUnit = 1000;
        config.shipHeatDumpThroughput = 40000;
        config.shipHeatRadiatorCellPower = 6000;
        config.shipHeatRadiatorReferenceKelvin = 500;
    }

    /** What one dump sustains, in heat units per second, for the best slug material in the table. */
    private static double sustainedDumpRate() {
        // The dump's throughput IS the sustained rate: it is what the machine moves per second, and a
        // bigger slug only means it runs longer before the port fires. That is the whole point of
        // expressing the clause per second rather than per slug.
        return StellurgyConfiguration.getCurrentConfig().shipHeatDumpThroughput;
    }

    /**
     * The other half of the same bargain: a slug is SPENT. Whatever it carries away leaves the ship
     * with it, so the material is a consumable and not a heat exchanger that keeps working.
     */
    @Test
    public void whatTheSlugCarriesLeavesWithIt() {
        ThermalMaterial iron = ThermalMaterials.INSTANCE.byName("iron");
        long capacity = ThermalMaterials.slugCapacity(iron,
                ThermalMaterials.volumeMillilitres("blockIron"));

        assertTrue("premise: a block of iron must be worth carrying at all", capacity > 0);

        double secondsOfCooling = capacity / sustainedDumpRate();
        assertTrue("one whole block of iron must buy SECONDS rather than a steady state - it is an"
                + " emergency measure, and a value that bought minutes would make it a cooling"
                + " system: " + secondsOfCooling + " s", secondsOfCooling < 600);
    }
}
