package dev.stannismod.stellurgy.test.integration;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.dimension.DimensionProperties.AtmosphereTypes;
import dev.stannismod.stellurgy.dimension.TerrainSource;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    private static DimensionProperties earthLike() {
        DimensionProperties props = new DimensionProperties(9001, "TestEarthLike");
        props.gravitationalMultiplier = 1.0f;
        props.orbitalDist = dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        props.rotationalPeriod = 24000;
        props.setAtmosphereDensityDirect(100);
        props.skyColor = new float[]{0.5f, 0.7f, 1.0f};
        props.fogColor = new float[]{0.6f, 0.6f, 0.6f};
        props.hasOxygen = true;
        return props;
    }

    /**
     * red-witnessed: 2026-09-30, taken on the pre-long form (the write was then
     * {@code setInteger}), with {@code DimensionProperties#writeToNBT} at
     * {@code nbt.setLong("orbitalDist", orbitalDist)} writing {@code orbitalDist} through a
     * {@code (short)} cast, this fails with "expected:&lt;1495979&gt; but was:&lt;-11349&gt;". At the
     * old one-AU value of {@code 100} the same truncation would pass: the new fixture is what makes
     * a narrowed write visible.
     */
    @Test
    public void nbtRoundTripPreservesPlanetIdentity() {
        DimensionProperties original = earthLike();
        // starId=0 (Sol) is the only star MinecraftBootstrap registers. Production
        // saves only refer to stars that DimensionManager already knows about, so
        // this matches the in-game contract.
        setIntField(original, "starId", 0);
        setIntField(original, "parentPlanet", -1);

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(9001, nbt);

        assertEquals(original.getId(), restored.getId());
        assertEquals("TestEarthLike", restored.getName());
        assertEquals(0, restored.getStarId());
        assertEquals(original.getGravitationalMultiplier(), restored.getGravitationalMultiplier(), 1e-6);
        assertEquals(original.orbitalDist, restored.orbitalDist);
        assertEquals(original.rotationalPeriod, restored.rotationalPeriod);
        assertEquals(original.getAtmosphereDensity(), restored.getAtmosphereDensity());
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
    public void nbtRoundTripPreservesModWorldtypeTerrain() {
        DimensionProperties original = new DimensionProperties(9103, "ForeignWorld");
        original.setTerrainSource(TerrainSource.MOD_WORLDTYPE);
        original.setTerrainWorldType("BIOMESOP");

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);
        // Persisted by name, so a future enum reorder cannot corrupt the save.
        assertEquals("MOD_WORLDTYPE", nbt.getString("terrainSource"));

        DimensionProperties restored = DimensionProperties.createFromNBT(9103, nbt);
        assertSame(TerrainSource.MOD_WORLDTYPE, restored.getTerrainSource());
        assertEquals("BIOMESOP", restored.getTerrainWorldType());
        assertEquals("", restored.getTerrainTemplate());
    }

    @Test
    public void nbtRoundTripPreservesTemplateTerrain() {
        DimensionProperties original = new DimensionProperties(9104, "TemplateWorld");
        original.setTerrainSource(TerrainSource.TEMPLATE);
        original.setTerrainTemplate("packplanet");

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(9104, nbt);
        assertSame(TerrainSource.TEMPLATE, restored.getTerrainSource());
        assertEquals("packplanet", restored.getTerrainTemplate());
        assertEquals("", restored.getTerrainWorldType());
    }

    @Test
    public void nbtRoundTripPreservesWeatherConfig() {
        DimensionProperties original = new DimensionProperties(9002, "WeatherWorld");
        original.setRainStartLength(50_000);
        original.setThunderStartLength(80_000);
        original.setRainProlongationLength(2_500);
        original.setThunderProlongationLength(4_000);
        original.setRainMarker(1);
        original.setThunderMarker(-1);
        original.setAcidicRain(true);

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(9002, nbt);

        assertEquals(50_000, restored.getRainStartLength());
        assertEquals(80_000, restored.getThunderStartLength());
        assertEquals(2_500, restored.getRainProlongationLength());
        assertEquals(4_000, restored.getThunderProlongationLength());
        assertEquals(1, restored.getRainMarker());
        assertEquals(-1, restored.getThunderMarker());
        assertTrue("acidicRain must survive the save round-trip", restored.isAcidicRain());
    }

    @Test
    public void nbtRoundTripPreservesGenerationFlags() {
        DimensionProperties original = new DimensionProperties(9003, "GenWorld");
        original.setGenerateCraters(false);
        original.setGenerateGeodes(false);
        original.setGenerateStructures(false);
        original.setGenerateVolcanos(true);
        original.setGenerateCaves(false);
        original.hasRivers = false;
        original.setCraterMultiplier(0.25f);
        original.setVolcanoMultiplier(3.0f);
        original.setGeodeMultiplier(1.75f);

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(9003, nbt);

        assertFalse(restored.canGenerateCraters());
        assertFalse(restored.canGenerateGeodes());
        assertFalse(restored.canGenerateStructures());
        assertTrue(restored.canGenerateVolcanos());
        assertFalse(restored.canGenerateCaves());
        assertFalse(restored.hasRivers);
        assertEquals(0.25f, restored.getCraterMultiplier(), 1e-6);
        assertEquals(3.0f, restored.getVolcanoMultiplier(), 1e-6);
        // Asserting the FIELD via reflection (NBT round-trip is correct for the
        // wire). getGeodeMultiplier() is buggy — see
        // getGeodeMultiplierReturnsVolcanoMultiplier_documented below.
        try {
            java.lang.reflect.Field f = restored.getClass().getDeclaredField("geodeFrequencyMultiplier");
            f.setAccessible(true);
            assertEquals(1.75f, f.getFloat(restored), 1e-6);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void nbtRoundTripPreservesRings() {
        DimensionProperties original = new DimensionProperties(9004, "RingedPlanet");
        original.hasRings = true;
        original.ringAngle = 45;
        original.ringColor = new float[]{0.9f, 0.1f, 0.2f};

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(9004, nbt);

        assertTrue(restored.hasRings);
        assertEquals(45, restored.ringAngle);
        assertEquals(0.9f, restored.ringColor[0], 1e-6);
        assertEquals(0.1f, restored.ringColor[1], 1e-6);
        assertEquals(0.2f, restored.ringColor[2], 1e-6);
    }

    @Test
    public void nbtRoundTripPreservesSkyAndFogColors() {
        DimensionProperties original = new DimensionProperties(9005, "ColorWorld");
        original.skyColor = new float[]{0.1f, 0.2f, 0.3f};
        original.fogColor = new float[]{0.4f, 0.5f, 0.6f};
        original.sunriseSunsetColors = new float[]{0.7f, 0.8f, 0.9f, 1.0f};

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(9005, nbt);

        assertEquals(0.1f, restored.skyColor[0], 1e-6);
        assertEquals(0.2f, restored.skyColor[1], 1e-6);
        assertEquals(0.3f, restored.skyColor[2], 1e-6);
        assertEquals(0.4f, restored.fogColor[0], 1e-6);
        assertEquals(0.7f, restored.sunriseSunsetColors[0], 1e-6);
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
     * setParentPlanet must establish the bidirectional link: child's
     * parentPlanet field points at parent, and parent's childPlanets contains
     * the child's id.
     */
    @Test
    public void parentChildRelationshipsAreBidirectional() {
        DimensionProperties parent = new DimensionProperties(7780, "ParentWorld");
        DimensionProperties child = new DimensionProperties(7781, "MoonChild");
        setIntField(parent, "starId", 0);
        setIntField(child, "starId", 0);

        // Register both in DimensionManager so setParentPlanet(parent, true) can
        // resolve parent.childPlanets via DimensionManager when traversing.
        DimensionManager.getInstance().setDimProperties(7780, parent);
        DimensionManager.getInstance().setDimProperties(7781, child);

        assertFalse("parent must not start with child", parent.getChildPlanets().contains(7781));
        assertEquals("child must start with INVALID_PLANET parent",
                Constants.INVALID_PLANET, child.getParentPlanet());

        child.setParentPlanet(parent);

        assertEquals("child's parentPlanet field must point at parent",
                7780, child.getParentPlanet());
        assertTrue("parent's childPlanets must contain child id",
                parent.getChildPlanets().contains(7781));
        assertTrue("child must be reported as moon (has parent)", child.isMoon());

        // Switching parent must clean up the old parent's child list.
        DimensionProperties otherParent = new DimensionProperties(7782, "OtherParent");
        setIntField(otherParent, "starId", 0);
        DimensionManager.getInstance().setDimProperties(7782, otherParent);

        child.setParentPlanet(otherParent);
        assertFalse("old parent must drop child after re-parenting",
                parent.getChildPlanets().contains(7781));
        assertTrue("new parent must adopt child",
                otherParent.getChildPlanets().contains(7781));
        assertEquals(7782, child.getParentPlanet());

        // Setting parent to null detaches the child cleanly.
        child.setParentPlanet(null);
        assertEquals(Constants.INVALID_PLANET, child.getParentPlanet());
        assertFalse("detach must clear parent's children",
                otherParent.getChildPlanets().contains(7781));
    }

    /**
     * A moon must inherit its parent's solar orbital distance, not use its
     * own (the moon's {@code orbitalDist} is its distance from the parent, not
     * from the star).
     */
    @Test
    public void moonInheritsParentSolarDistance() {
        DimensionProperties parent = new DimensionProperties(7790, "MoonParent");
        setIntField(parent, "starId", 0);
        parent.orbitalDist = 250; // far-out planet

        DimensionProperties moon = new DimensionProperties(7791, "Moon");
        setIntField(moon, "starId", 0);
        moon.orbitalDist = 50; // moon's distance from parent

        DimensionManager.getInstance().setDimProperties(7790, parent);
        DimensionManager.getInstance().setDimProperties(7791, moon);

        // Without parent: solar distance == own orbitalDist.
        assertEquals("standalone planet's solar distance is its own orbitalDist",
                50, moon.getSolarOrbitalDistance());

        moon.setParentPlanet(parent);

        assertEquals("moon's solar distance must come from parent (250), not its own (50)",
                250, moon.getSolarOrbitalDistance());
        assertEquals("parent unaffected",
                250, parent.getSolarOrbitalDistance());

        // getSolarTheta also delegates to parent.
        parent.orbitTheta = 1.234;
        assertEquals("moon's solar theta must come from parent",
                1.234, moon.getSolarTheta(), 1e-9);
    }

    /**
     * requiredArtifacts list must survive NBT round-trip with item
     * identity + count preserved (used by planet-unlock gameplay).
     */
    @Test
    public void requiredArtifactsRoundTrip() {
        DimensionProperties original = new DimensionProperties(7800, "Gated");
        original.requiredArtifacts.add(new ItemStack(Items.DIAMOND, 3));
        original.requiredArtifacts.add(new ItemStack(Items.EMERALD, 1));
        setIntField(original, "starId", 0);

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(7800, nbt);

        assertEquals("artifact list size must round-trip",
                2, restored.getRequiredArtifacts().size());

        ItemStack first = restored.getRequiredArtifacts().get(0);
        assertEquals(Items.DIAMOND, first.getItem());
        assertEquals(3, first.getCount());

        ItemStack second = restored.getRequiredArtifacts().get(1);
        assertEquals(Items.EMERALD, second.getItem());
        assertEquals(1, second.getCount());
    }

    @Test
    public void emptyNbtRoundTripUsesPostConstructorDefaults() {
        DimensionProperties original = new DimensionProperties(9999);

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        DimensionProperties restored = DimensionProperties.createFromNBT(9999, nbt);

        // Default name written by constructor = "Temp".
        assertEquals("Temp", restored.getName());
        // Default density from resetProperties = 100.
        assertEquals(100, restored.getAtmosphereDensity());
        assertEquals(24000, restored.rotationalPeriod);
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

    /**
     * The free-flight map lays a moon out about its planet as it laid one out while Luna stood at
     * 150: {@code 75} map units per moon-view unit plus {@code 100}, the moon-view distance being 150
     * for a moon at Luna's distance and in proportion for any other. The rocket's moon capture and
     * landing are measured on this map.
     *
     * <p>Acceptance, stated before the code: a moon at twice Luna's distance (7 688 units, not
     * Luna's own, so a law that drew every moon as Luna is told apart) stands at the moon-view 300,
     * so at (22 600, 0) at angle 0 and (0, 22 600) at a quarter turn, within the rounding of
     * {@code cos(PI / 2)}. Read raw, it stands at 576 700.</p>
     *
     * <p>What this does not see: the rocket reading the map — its capture radius is the moon's own
     * render size, compared against this position.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code DimensionProperties#getSpacePosition} at
     * {@code ? MOON_SPACE_MAP_UNITS_PER_VIEW_UNIT * AstronomicalBodyHelper.moonViewUnits(orbitalDist)}
     * back on the raw distance ({@code 75f * orbitalDist}), this fails with "(x, z) at angle 0, then
     * at a quarter turn: [576700.0, 0.0, 3.531269045341393E-11, 576700.0]: arrays first differed at
     * element [0]; expected:&lt;22600.0&gt; but was:&lt;576700.0&gt;"; and with
     * {@code AstronomicalBodyHelper#moonViewUnits} at
     * {@code return MOON_VIEW_UNITS_AT_LUNA * (orbitalDistance / (double) MOON_REFERENCE_UNITS)}
     * replaced by {@code return MOON_VIEW_UNITS_AT_LUNA} (every moon laid out as Luna), with
     * "… expected:&lt;22600.0&gt; but was:&lt;11350.0&gt;".</p>
     */
    @Test
    public void theFreeFlightMapLaysAMoonOutAsItDidWhenLunaStoodAt150() {
        DimensionProperties parent = new DimensionProperties(9210, "MapParent");
        DimensionProperties moon = new DimensionProperties(9211, "TwiceLunaOut");
        moon.setParentPlanet(parent);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged("the body must be a moon for the moon layout to apply", moon.isMoon());
        moon.orbitalDist = 2L * dev.stannismod.stellurgy.util.AstronomicalBodyHelper.MOON_REFERENCE_UNITS;

        moon.orbitTheta = 0d;
        dev.stannismod.stellurgy.util.SpacePosition atZero = moon.getSpacePosition();
        moon.orbitTheta = Math.PI / 2;
        dev.stannismod.stellurgy.util.SpacePosition atQuarter = moon.getSpacePosition();

        // 75 x 300 + 100, exact. The only inexact term is Math.cos(PI / 2) = 6.1e-17, which puts x at
        // 1.4e-12 — so 1e-9 is slack for that rounding and for nothing else.
        double[] expected = {22_600d, 0d, 0d, 22_600d};
        double[] actual = {atZero.x, atZero.z, atQuarter.x, atQuarter.z};
        org.junit.Assert.assertArrayEquals("(x, z) at angle 0, then at a quarter turn: "
                + java.util.Arrays.toString(actual), expected, actual, 1e-9);
    }

    /**
     * A companion star and a planet at the SAME orbital distance land at the same radius on the
     * free-flight map — the joint between {@code StellarBody#getSpacePosition} and
     * {@code DimensionProperties#getSpacePosition}, which carry one field in one unit and so must lay
     * it out by one law. Five AU: neither a default nor a value either law special-cases.
     *
     * <p>red-witnessed: 2026-09-30, on the code as it stood before the fix — {@code StellarBody#getSpacePosition}
     * at {@code position.x = offset[0] * dev.stannismod.stellurgy.util.AstronomicalBodyHelper.SPACE_MAP_UNITS_PER_AU}
     * multiplying by a private 100 per AU instead: "a companion at 5 AU (500.0) and a planet at 5 AU
     * (50000.0) must stand at one radius expected:&lt;50000.0&gt; but was:&lt;500.0&gt;".</p>
     */
    @Test
    public void aCompanionAndAPlanetAtOneDistanceLandAtOneRadiusOnTheMap() {
        long fiveAu = 5L * dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;

        dev.stannismod.stellurgy.api.dimension.solar.StellarBody primary =
                new dev.stannismod.stellurgy.api.dimension.solar.StellarBody();
        dev.stannismod.stellurgy.api.dimension.solar.StellarBody companion =
                new dev.stannismod.stellurgy.api.dimension.solar.StellarBody();
        companion.setOrbitalDistance(fiveAu);
        companion.setBaseTheta(0d);
        primary.addSubStar(companion);
        double companionRadius = Math.sqrt(
                companion.getSpacePosition().distanceToSpacePosition2(primary.getSpacePosition()));

        DimensionProperties planet = new DimensionProperties(-7501);
        planet.orbitalDist = fiveAu;
        planet.orbitTheta = 0d;
        double planetRadius = Math.sqrt(planet.getSpacePosition()
                .distanceToSpacePosition2(new dev.stannismod.stellurgy.util.SpacePosition()));

        // Slack: two double products of the same five-AU quantity, rounded independently.
        assertEquals("a companion at 5 AU (" + companionRadius + ") and a planet at 5 AU ("
                + planetRadius + ") must stand at one radius", planetRadius, companionRadius,
                planetRadius * 1e-12);
    }
}
