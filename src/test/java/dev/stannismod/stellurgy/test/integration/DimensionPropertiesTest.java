package dev.stannismod.stellurgy.test.integration;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.dimension.DimensionProperties.AtmosphereTypes;
import dev.stannismod.stellurgy.dimension.TerrainSource;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * DimensionProperties domain logic — defaults, NBT round-trip, hierarchy.
 *
 * Tests stay clear of biome / ocean-block / filler-block round-trip because those
 * pull from {@code Block.REGISTRY} / {@code StellurgyBiomes.instance} which
 * require the Stellurgy registry pipeline. Those branches are exercised in scenario tests.
 */
public class DimensionPropertiesTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** starId / parentPlanet are package-private; tests use reflection. */
    private static void setIntField(Object target, String name, int value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (Exception e) {
            throw new AssertionError("Reflection failed setting " + name, e);
        }
    }

    private static int getIntField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(target);
        } catch (Exception e) {
            throw new AssertionError("Reflection failed reading " + name, e);
        }
    }

    // ---- terrainSource -------------------------------------------------------

    @Test
    public void terrainSourceSettersNullGuardToDefaults() {
        DimensionProperties props = new DimensionProperties(9101);
        props.setTerrainSource(null);
        props.setTerrainWorldType(null);
        props.setTerrainTemplate(null);
        assertSame(TerrainSource.NATIVE, props.getTerrainSource());
        assertEquals("", props.getTerrainWorldType());
        assertEquals("", props.getTerrainTemplate());
    }

    @Test
    public void nativeTerrainSourceEmitsNoNbtKeys() {
        // A default (NATIVE) planet must serialise byte-identically to pre-terrainSource saves.
        DimensionProperties props = new DimensionProperties(9102, "PlainWorld");
        NBTTagCompound nbt = new NBTTagCompound();
        props.writeToNBT(nbt);
        assertFalse("NATIVE must not write terrainSource", nbt.hasKey("terrainSource"));
        assertFalse("empty worldType must not be written", nbt.hasKey("terrainWorldType"));
        assertFalse("empty template must not be written", nbt.hasKey("terrainTemplate"));
    }

    @Test
    public void setAtmosphereDensityDirectDoesNotCorruptIdOrHierarchy() {
        DimensionProperties props = new DimensionProperties(123, "Mars");
        setIntField(props, "starId", 5);
        setIntField(props, "parentPlanet", -1);

        props.setAtmosphereDensityDirect(42);

        // Identity invariants survive density mutation.
        assertEquals(123, props.getId());
        assertEquals("Mars", props.getName());
        assertEquals(5, props.getStarId());
        assertEquals(-1, getIntField(props, "parentPlanet"));
        assertEquals(42, props.getAtmosphereDensity());
    }

    /**
     * Derive AtmosphereTypes from density value (boundary-checked).
     *
     * Production callers (oxygen handler, sealable-block detection, oregen) read
     * the type via {@link AtmosphereTypes#getAtmosphereTypeFromValue(int)} — the
     * mapping is the contract that downstream gameplay depends on.
     */
    @Test
    public void atmosphereTypeFromDensityAndTemperature() {
        // Boundary rule: value > type.value -> that type, walked top-down.
        // SUPERHIGHPRESSURE(800), HIGHPRESSURE(200), NORMAL(75), LOW(25), NONE(0).

        assertEquals(AtmosphereTypes.SUPERHIGHPRESSURE,
                AtmosphereTypes.getAtmosphereTypeFromValue(801));
        assertEquals(AtmosphereTypes.SUPERHIGHPRESSURE,
                AtmosphereTypes.getAtmosphereTypeFromValue(10_000));

        // value == 800 is NOT super-high (strict >); falls into HIGHPRESSURE.
        assertEquals(AtmosphereTypes.HIGHPRESSURE,
                AtmosphereTypes.getAtmosphereTypeFromValue(800));
        assertEquals(AtmosphereTypes.HIGHPRESSURE,
                AtmosphereTypes.getAtmosphereTypeFromValue(201));

        assertEquals(AtmosphereTypes.NORMAL,
                AtmosphereTypes.getAtmosphereTypeFromValue(200));
        assertEquals(AtmosphereTypes.NORMAL,
                AtmosphereTypes.getAtmosphereTypeFromValue(100));
        assertEquals(AtmosphereTypes.NORMAL,
                AtmosphereTypes.getAtmosphereTypeFromValue(76));

        assertEquals(AtmosphereTypes.LOW,
                AtmosphereTypes.getAtmosphereTypeFromValue(75));
        assertEquals(AtmosphereTypes.LOW,
                AtmosphereTypes.getAtmosphereTypeFromValue(26));

        assertEquals(AtmosphereTypes.NONE,
                AtmosphereTypes.getAtmosphereTypeFromValue(25));
        assertEquals(AtmosphereTypes.NONE,
                AtmosphereTypes.getAtmosphereTypeFromValue(0));
        assertEquals(AtmosphereTypes.NONE,
                AtmosphereTypes.getAtmosphereTypeFromValue(-100));

        // DimensionProperties.hasAtmosphere() flips at NORMAL/LOW boundary.
        DimensionProperties earth = new DimensionProperties(7771, "Earth");
        earth.setAtmosphereDensityDirect(100);
        assertTrue("density=100 should have atmosphere", earth.hasAtmosphere());

        DimensionProperties vacuum = new DimensionProperties(7772, "Vac");
        vacuum.setAtmosphereDensityDirect(0);
        assertFalse("density=0 should be no atmosphere", vacuum.hasAtmosphere());
    }

    /**
     * The free-flight map lays a planet out 10 000 map units per AU from its star, along its orbital
     * angle. That is 100 per distance unit as it was laid out before the unit became a length — then
     * a unit was a hundredth of an AU — and the rocket's free-flight capture, gravity and landing
     * radii, and the solar view, are all measured on this map.
     *
     * <p>Acceptance, stated before the code: a planet two AU out (not the default one AU a body takes
     * when nothing states its orbit) stands at (20 000, 0) at angle 0 and at (0, 20 000) at a quarter
     * turn, within the rounding of {@code cos(PI / 2)}. Read raw, it stands 14 960 times further.</p>
     *
     * <p>What this does not see: a MOON's placement, which has a law of its own — see
     * {@link #theFreeFlightMapLaysAMoonOutAsItDidWhenLunaStoodAt150}.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code DimensionProperties#getSpacePosition} at {@code : orbitalDist / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU} — the planet
     * radius — back on {@code 100f * orbitalDist}: "(x, z) at angle 0, then at a quarter turn:
     * [2.99195808E8, 0.0, 1.8320459429275304E-8, 2.99195808E8]: arrays first differed at element [0];
     * expected:&lt;20000.0&gt; but was:&lt;2.99195808E8&gt;" — both placements wrong, one verdict.</p>
     */
    @Test
    public void theFreeFlightMapLaysAPlanetOutTenThousandUnitsPerAu() {
        DimensionProperties planet = new DimensionProperties(9002, "TwoAuOut");
        planet.setStar(new dev.stannismod.stellurgy.api.dimension.solar.StellarBody());
        planet.orbitalDist = 2L * dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;

        planet.orbitTheta = 0d;
        dev.stannismod.stellurgy.util.SpacePosition atZero = planet.getSpacePosition();
        planet.orbitTheta = Math.PI / 2;
        dev.stannismod.stellurgy.util.SpacePosition atQuarter = planet.getSpacePosition();

        // One verdict over both placements. The radius is exact (2.0 AU x 10 000); the only inexact
        // term is Math.cos(PI / 2) = 6.1e-17, which puts x at 1.2e-12 — so 1e-9 is slack for that
        // rounding and for nothing else.
        double[] expected = {20_000d, 0d, 0d, 20_000d};
        double[] actual = {atZero.x, atZero.z, atQuarter.x, atQuarter.z};
        org.junit.Assert.assertArrayEquals("(x, z) at angle 0, then at a quarter turn: "
                + java.util.Arrays.toString(actual), expected, actual, 1e-9);
    }

}
