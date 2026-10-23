package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.api.satellite.InstrumentSubject;
import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.MagnetosphereDerivation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.satellite.DoseReadings;
import dev.stannismod.stellurgy.satellite.Downlink;
import dev.stannismod.stellurgy.satellite.FailureJournal;
import dev.stannismod.stellurgy.satellite.RadiationWear;
import dev.stannismod.stellurgy.satellite.SatelliteAccrual;
import dev.stannismod.stellurgy.satellite.SatelliteAttitude;
import dev.stannismod.stellurgy.satellite.SatelliteComposition;
import dev.stannismod.stellurgy.satellite.SatelliteData;
import dev.stannismod.stellurgy.satellite.SatelliteDensity;
import dev.stannismod.stellurgy.satellite.SatelliteLife;
import dev.stannismod.stellurgy.satellite.SatelliteMassScanner;
import dev.stannismod.stellurgy.satellite.SatelliteOptical;
import dev.stannismod.stellurgy.satellite.SatellitePlacement;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import dev.stannismod.stellurgy.tile.satellite.TerminalReadout;
import dev.stannismod.stellurgy.util.WeightEngine;
import net.minecraft.nbt.NBTTagCompound;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The laws of a satellite on its orbit (`satellite`): INV-SAT-11..15, of the dose on its parts:
 * INV-SAT-21, -23..-25, -27..-31, of the quantity a data satellite collects, INV-SAT-34, of the
 * downlink: INV-SAT-35..37, and of the readout's pages: INV-SAT-40.
 *
 * <p>Every orbit here is released by production ({@link SatelliteOrbit#releasedAt}) about a body the test
 * states — a gravitational parameter, a radius, a rotation — and every expected value is the test's own
 * Kepler and vector arithmetic on those inputs. What it does NOT see: where production takes a body's
 * mass, radius and rotation from, the universe lookup that finds the body and its stars, and the entity
 * that carries the attitude to a client — those are the wiring, held in the harness.</p>
 */
public class SatelliteLawsTest {

    /** Earth's gravitational parameter, m³/s² — an input of the experiment. */
    private static final double EARTH_GM = 3.986004418e14d;
    /** A 400 km orbit about Earth's 6 371 km, m — an input of the experiment. */
    private static final double LOW_ORBIT_RADIUS_M = 6.771e6d;
    /** Seconds per tick, one game day standing for one Earth day — MECH-SAT-14's convention. */
    private static final double SECONDS_PER_TICK = 3.6d;
    /** One turn of the body, ticks — an input of the experiment: an Earth day. */
    private static final long ROTATION_TICKS = 24_000L;
    /** Metres per chart block: the scale MECH-SAT-15 carries a place onto the cell's axes at. */
    private static final double METRES_PER_BLOCK = 250d;
    /** Ticks at which satellites are released: the start of a turn, and three points inside it. A fresh array each call. */
    private static long[] releaseTicks() {
        return new long[]{0L, 7_000L, 13_333L, 23_999L};
    }

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static BodyRotation earthDay() {
        return BodyRotation.of(ROTATION_TICKS, 0d);
    }

    /** The orbit's period in ticks by Kepler's third law, unrounded. */
    private static double keplerPeriodTicks(double radiusM) {
        return 2d * Math.PI * Math.sqrt(radiusM * radiusM * radiusM / EARTH_GM) / SECONDS_PER_TICK;
    }

    /** Meridian 0's angle at {@code tick}, the body turning once a day from angle zero at tick zero. */
    private static double meridianAt(long tick) {
        return 2d * Math.PI * Math.floorMod(tick, ROTATION_TICKS) / ROTATION_TICKS;
    }

    /**
     * INV-SAT-11: at every tick the position lies at the orbit's radius; one Kepler period on, it is where it
     * was; at release it lies at the node under meridian 0; a quarter period after the node, at the
     * latitude of its inclination.
     *
     * <p>A tick is the clock's quantum, and a period is not a whole number of ticks: comparing across one
     * rounded period or quarter leaves up to half a tick of motion, which bounds those two checks.</p>
     */
    @Pins("INV-SAT-11")
    @Regions(of = "INV-SAT-11", value = {"equatorial", "inclined", "polar", "retrograde"})
    @RedWitness(verdict = "latitude", member = "SatelliteOrbit#positionAt", at = "sinU * Math.sin(inclinationRad)",
            replacedBy = "-sinU * Math.sin(inclinationRad)", fails = "INV-SAT-11: a quarter", on = "2026-10-21")
    @RedWitness(verdict = "node", member = "SatelliteOrbit#releasedAt", at = "rotation.angleAt(tick)",
            replacedBy = "0d", fails = "INV-SAT-11: at release", on = "2026-10-21")
    @RedWitness(verdict = "period", member = "SatelliteOrbit#meanMotionPerTick",
            at = "muM3S2 / (radiusM * radiusM * radiusM)", replacedBy = "muM3S2 / (radiusM * radiusM)",
            fails = "INV-SAT-11: one period", on = "2026-10-21")
    @Test
    public void thePhaseIsTheClocks() {
        double r = LOW_ORBIT_RADIUS_M;
        double period = keplerPeriodTicks(r);
        // Half a tick of motion along the orbit, as a distance: what rounding a period to ticks can leave.
        double halfTickArc = r * Math.PI / period;
        double[] inclinationsDeg = {0d, 51.6d, 90d, 120d};
        int[] visited = new int[inclinationsDeg.length];
        long[] releases = releaseTicks();
        for (int k = 0; k < inclinationsDeg.length; k++) {
            double inclination = Math.toRadians(inclinationsDeg[k]);
            for (long release : releases) {
                SatelliteOrbit orbit = SatelliteOrbit.releasedAt(r, inclination, EARTH_GM, earthDay(), release);
                String c = " (i=" + inclinationsDeg[k] + "°, released at " + release + ")";

                for (long t = release; t < release + 2L * Math.round(period); t += 97L) {
                    assertEquals("INV-SAT-11: off its radius at " + t + c, r, orbit.positionAt(t).length(), r * 1e-12d);
                }

                Vector3dc atRelease = orbit.positionAt(release);
                double node = meridianAt(release);
                Vector3d expectedNode = new Vector3d(Math.cos(node), Math.sin(node), 0d).mul(r);
                assertTrue("INV-SAT-11: at release it is not at the node under meridian 0" + c + ": " + atRelease
                        + " vs " + expectedNode, atRelease.distance(expectedNode) <= r * 1e-12d);

                Vector3dc later = orbit.positionAt(release + Math.round(period));
                assertTrue("INV-SAT-11: one period on, it is " + later.distance(atRelease) + " m from where it was" + c,
                        later.distance(atRelease) <= halfTickArc);

                Vector3dc quarter = orbit.positionAt(release + Math.round(period / 4d));
                assertEquals("INV-SAT-11: a quarter period after the node it is not at its inclination's latitude" + c,
                        r * Math.sin(inclination), quarter.z(), halfTickArc);
                visited[k]++;
            }
        }
        for (int k = 0; k < visited.length; k++) {
            assertEquals("arrangement: every inclination region visited at every release tick",
                    releases.length, visited[k]);
        }
    }

    /**
     * INV-SAT-12: the standing point lies at the orbit's radius along its place at release, and carrying it
     * onto the cell's axes keeps every length and the handedness of the frame: the body's x, y and z
     * become cell vectors whose cross product of the first two is the third.
     */
    @Pins("INV-SAT-12")
    @RedWitness(member = "SatellitePlacement#cellOffsetBlocks", at = "Math.round(-metres.y() / perBlock)",
            replacedBy = "Math.round(metres.y() / perBlock)", fails = "INV-SAT-12: the cell's axes", on = "2026-10-21")
    @Test
    public void standingStillIsOrbiting() {
        for (long release : releaseTicks()) {
            SatelliteOrbit orbit = SatelliteOrbit.releasedAt(LOW_ORBIT_RADIUS_M, Math.toRadians(51.6d), EARTH_GM,
                    earthDay(), release);
            Vector3dc standing = orbit.standingOffset();
            assertTrue("INV-SAT-12: the standing point is not its place at release (released at " + release + ")",
                    standing.distance(orbit.positionAt(release)) <= LOW_ORBIT_RADIUS_M * 1e-12d);
            long[] blocks = SatellitePlacement.cellOffsetBlocks(standing);
            double length = Math.sqrt((double) blocks[0] * blocks[0] + (double) blocks[1] * blocks[1]
                    + (double) blocks[2] * blocks[2]);
            // Rounding each axis to a whole block moves the point by at most half a block's diagonal.
            assertEquals("INV-SAT-12: the cell's axes changed its distance from the body (released at " + release + ")",
                    LOW_ORBIT_RADIUS_M / METRES_PER_BLOCK, length, Math.sqrt(3d) / 2d);
        }

        Vector3d x = cell(new Vector3d(1e6d, 0d, 0d));
        Vector3d y = cell(new Vector3d(0d, 1e6d, 0d));
        Vector3d z = cell(new Vector3d(0d, 0d, 1e6d));
        Vector3d xCrossY = new Vector3d(x).cross(y).normalize();
        assertTrue("INV-SAT-12: the cell's axes do not keep the frame's handedness: x × y = " + xCrossY + ", z = " + z,
                xCrossY.distance(new Vector3d(z).normalize()) < 1e-9d);
        assertEquals("INV-SAT-12: the cell's axes changed a length", 1e6d / METRES_PER_BLOCK, x.length(), 0.5d);
        assertEquals("INV-SAT-12: the cell's axes changed a length", 1e6d / METRES_PER_BLOCK, y.length(), 0.5d);
        assertEquals("INV-SAT-12: the cell's axes changed a length", 1e6d / METRES_PER_BLOCK, z.length(), 0.5d);
        assertEquals("INV-SAT-12: the spin axis is not the cell's up", 1d, new Vector3d(z).normalize().y, 1e-12d);
    }

    private static Vector3d cell(Vector3dc metres) {
        long[] b = SatellitePlacement.cellOffsetBlocks(metres);
        return new Vector3d(b[0], b[1], b[2]);
    }

    /**
     * INV-SAT-13: released onto one orbit at two ticks of one turn, two satellites stand apart, by the angle
     * the body turned between the two releases; at one tick, at one point.
     */
    @Pins("INV-SAT-13")
    @RedWitness(member = "SatelliteOrbit#releasedAt", at = "rotation.angleAt(tick)", replacedBy = "0d",
            fails = "INV-SAT-13: released", on = "2026-10-21")
    @Test
    public void releasesAtDifferentTicksStandApart() {
        double inclination = Math.toRadians(51.6d);
        long[] releases = releaseTicks();
        for (int a = 0; a < releases.length; a++) {
            for (int b = 0; b < releases.length; b++) {
                Vector3dc first = SatelliteOrbit.releasedAt(LOW_ORBIT_RADIUS_M, inclination, EARTH_GM, earthDay(),
                        releases[a]).standingOffset();
                Vector3dc second = SatelliteOrbit.releasedAt(LOW_ORBIT_RADIUS_M, inclination, EARTH_GM, earthDay(),
                        releases[b]).standingOffset();
                double turned = meridianAt(releases[b]) - meridianAt(releases[a]);
                double expectedApart = 2d * LOW_ORBIT_RADIUS_M * Math.abs(Math.sin(turned / 2d));
                assertEquals("INV-SAT-13: released at " + releases[a] + " and " + releases[b]
                                + ", they stand " + first.distance(second) + " m apart",
                        expectedApart, first.distance(second), LOW_ORBIT_RADIUS_M * 1e-9d);
            }
        }
    }

    /**
     * INV-SAT-14: the attitude points from the satellite's point to the NEAREST star and to its body, and
     * follows a star that has moved; with no star there is none.
     */
    @Pins("INV-SAT-14")
    @Regions(of = "INV-SAT-14", value = {"one star", "two stars", "moved star", "no star"})
    @RedWitness(verdict = "nearest", member = "SatelliteAttitude#facing", at = "toStar.length() < nearest.length()",
            replacedBy = "toStar.length() > nearest.length()", fails = "INV-SAT-14: two stars", on = "2026-10-21")
    @RedWitness(verdict = "from its point", member = "SatelliteAttitude#facing",
            at = "body.plus(offsetBlocks[0], offsetBlocks[1], offsetBlocks[2])", replacedBy = "body",
            fails = "INV-SAT-14: one star", on = "2026-10-21")
    @Test
    public void theAttitudeIsComputedFromTheTick() {
        AbsolutePos body = AbsolutePos.of(5_000_000L, 0L, 0L);
        long[] offset = {0L, 0L, 27_084L};
        AbsolutePos at = AbsolutePos.of(5_000_000L, 0L, 27_084L);
        AbsolutePos star = AbsolutePos.of(0L, 0L, 0L);

        Optional<SatelliteAttitude> oneStar = SatelliteAttitude.facing(body, Collections.singletonList(star), offset);
        assertTrue("INV-SAT-14: one star — no attitude was given for a satellite off its body's centre",
                oneStar.isPresent());
        SatelliteAttitude one = oneStar.get();
        assertTrue("INV-SAT-14: one star — the panels do not face it from the satellite's point: " + one.toStar(),
                one.toStar().distance(direction(at, star)) < 1e-12d);
        assertTrue("INV-SAT-14: one star — the dish does not face the body: " + one.toBody(),
                one.toBody().distance(direction(at, body)) < 1e-12d);

        // Kept inside one sector, so the test's own arithmetic on local offsets is the whole distance.
        AbsolutePos far = AbsolutePos.of(15_000_000L, 0L, 0L);
        List<AbsolutePos> both = Arrays.asList(far, star);
        SatelliteAttitude two = SatelliteAttitude.facing(body, both, offset).get();
        assertTrue("INV-SAT-14: two stars — the panels do not face the nearest: " + two.toStar(),
                two.toStar().distance(direction(at, star)) < 1e-12d);

        AbsolutePos movedStar = AbsolutePos.of(5_000_000L, 0L, -4_000_000L);
        SatelliteAttitude moved = SatelliteAttitude.facing(body, Collections.singletonList(movedStar), offset).get();
        assertTrue("INV-SAT-14: moved star — the panels did not follow it: " + moved.toStar(),
                moved.toStar().distance(direction(at, movedStar)) < 1e-12d);
        assertTrue("arrangement: the star moved enough for the attitude to change",
                moved.toStar().distance(one.toStar()) > 0.5d);

        assertFalse("INV-SAT-14: no star — an attitude was given", SatelliteAttitude.facing(body,
                Collections.<AbsolutePos>emptyList(), offset).isPresent());
    }

    private static Vector3d direction(AbsolutePos from, AbsolutePos to) {
        return new Vector3d((double) to.localX() - from.localX(), (double) to.localY() - from.localY(),
                (double) to.localZ() - from.localZ()).normalize();
    }

    /**
     * INV-SAT-15: distance faces space, every property of the body faces the body, an undefined quantity
     * neither — and a data satellite faces as its quantity does.
     */
    @Pins("INV-SAT-15")
    @Regions(of = "INV-SAT-15", value = {"space survey", "body survey", "undefined"})
    @RedWitness(verdict = "space survey", member = "DataType#subject",
            at = "case DISTANCE: return java.util.Optional.of(dev.stannismod.stellurgy.api.satellite.InstrumentSubject.SPACE);",
            replacedBy = "case DISTANCE:", fails = "INV-SAT-15: space survey", on = "2026-10-21")
    @Test
    public void theQuantityDecidesTheInstruments() {
        for (DataStorage.DataType type : DataStorage.DataType.values()) {
            Optional<InstrumentSubject> subject = type.subject();
            if (type == DataStorage.DataType.DISTANCE) {
                assertEquals("INV-SAT-15: space survey — distance does not face space", Optional.of(InstrumentSubject.SPACE), subject);
            } else if (type == DataStorage.DataType.UNDEFINED) {
                assertEquals("INV-SAT-15: undefined — an undefined quantity faces something", Optional.empty(), subject);
            } else {
                assertEquals("INV-SAT-15: body survey — " + type.name() + " does not face the body",
                        Optional.of(InstrumentSubject.BODY), subject);
            }
        }
        assertEquals("INV-SAT-15: space survey — the optical satellite does not face space",
                Optional.of(InstrumentSubject.SPACE), new SatelliteOptical().instrumentSubject());
        assertEquals("INV-SAT-15: body survey — the composition satellite does not face the body",
                Optional.of(InstrumentSubject.BODY), new SatelliteComposition().instrumentSubject());
        assertEquals("INV-SAT-15: body survey — the mass scanner does not face the body",
                Optional.of(InstrumentSubject.BODY), new SatelliteMassScanner().instrumentSubject());
    }

    /**
     * INV-SAT-34: each data satellite's buffer holds the one quantity it collects, refuses a point of any
     * other and takes one of its own. The buffer is given room first: with none, a refusal would be the
     * capacity's, not the quantity's. Does not see how a point reaches the buffer — that is the accrual's.
     */
    @Pins("INV-SAT-34")
    @Regions(of = "INV-SAT-34", value = {"distance", "atmosphere density", "mass", "composition", "one quantity"})
    @RedWitness(verdict = "distance", member = "SatelliteOptical#SatelliteOptical",
            at = "data.lockDataType(DataType.DISTANCE);", replacedBy = "data.lockDataType(DataType.MASS);",
            fails = "INV-SAT-34: distance", on = "2026-10-21")
    @RedWitness(verdict = "atmosphere density", member = "SatelliteDensity#SatelliteDensity",
            at = "data.lockDataType(DataStorage.DataType.ATMOSPHEREDENSITY);",
            replacedBy = "data.lockDataType(DataStorage.DataType.MASS);",
            fails = "INV-SAT-34: atmosphere density", on = "2026-10-21")
    @RedWitness(verdict = "mass", member = "SatelliteMassScanner#SatelliteMassScanner",
            at = "data.lockDataType(DataStorage.DataType.MASS);",
            replacedBy = "data.lockDataType(DataStorage.DataType.COMPOSITION);",
            fails = "INV-SAT-34: mass", on = "2026-10-21")
    @RedWitness(verdict = "composition", member = "SatelliteComposition#SatelliteComposition",
            at = "data.lockDataType(DataStorage.DataType.COMPOSITION);",
            replacedBy = "data.lockDataType(DataStorage.DataType.MASS);",
            fails = "INV-SAT-34: composition", on = "2026-10-21")
    @RedWitness(verdict = "one quantity", member = "SatelliteDensity#SatelliteDensity",
            at = "data.lockDataType(DataStorage.DataType.ATMOSPHEREDENSITY);", replacedBy = "",
            fails = "INV-SAT-34: one quantity", on = "2026-10-21")
    @Test
    public void eachDataSatelliteCollectsItsOneQuantity() {
        Object[][] satellites = {
                {"distance", new SatelliteOptical(), DataStorage.DataType.DISTANCE},
                {"atmosphere density", new SatelliteDensity(), DataStorage.DataType.ATMOSPHEREDENSITY},
                {"mass", new SatelliteMassScanner(), DataStorage.DataType.MASS},
                {"composition", new SatelliteComposition(), DataStorage.DataType.COMPOSITION},
        };
        for (Object[] row : satellites) {
            SatelliteData satellite = (SatelliteData) row[1];
            assertEquals("INV-SAT-34: " + row[0] + " — " + satellite.getClass().getSimpleName()
                    + " collects another quantity", row[2], satellite.data.getDataType());
        }
        for (Object[] row : satellites) {
            SatelliteData satellite = (SatelliteData) row[1];
            DataStorage.DataType own = (DataStorage.DataType) row[2];
            satellite.data.setMaxData(10);
            assertEquals("INV-SAT-34: one quantity — " + satellite.getClass().getSimpleName()
                    + " refuses a point of its own quantity", 1, satellite.data.addData(1, own, false));
            assertEquals("INV-SAT-34: one quantity — " + satellite.getClass().getSimpleName()
                    + " takes a point of temperature", 0,
                    satellite.data.addData(1, DataStorage.DataType.TEMPERATURE, false));
        }
    }

    // ---- the dose on its parts: INV-SAT-21, -23..-25, -27..-30 -------------------------------------------
    //
    // Every satellite below is a list of parts, a payload and a state built here, advanced by production's
    // own accrual (SatelliteAccrual#advance) about a body whose field production derives from inputs the
    // test states (MagnetosphereDerivation#derive). Where a verdict needs the field's dose, it reads the
    // satellite's parts, never the field. What these laws do NOT see: where production takes a body's
    // inputs and its star from, the parts as items on a chassis, and who calls the accrual — the wiring,
    // held in the harness.

    /** One Earth radius, m — an input of the experiment: the radius the body is stated with. */
    private static final double BODY_RADIUS_M = 6.371e6d;
    /** A large solar panel's power per tick — an input of the experiment. */
    private static final double PANEL_POWER = 40d;
    /** A data instrument's ticks of work per point — an input of the experiment. */
    private static final double COLLECTION_TICKS = 100d;
    /** A data instrument's draw per tick — an input of the experiment. */
    private static final double INSTRUMENT_DRAW = 2d;
    /** A dosimeter's draw per tick — an input of the experiment. */
    private static final double DOSIMETER_DRAW = 1d;
    /** A data unit's capacity, points — an input of the experiment. */
    private static final int DATA_UNIT_POINTS = 1000;
    /** A battery's capacity — an input of the experiment, large enough that no eclipse empties it. */
    private static final double BIG_BATTERY = 100_000d;
    /** The seed of the experiment's field. Any seed gives an offset dipole and so an anomaly. */
    private static final long FIELD_SEED = 20_261_021L;
    /** One game day, ticks (MECH-SAT-14: one tick stands for 3.6 s). */
    private static final long DAY_TICKS = 24_000L;

    private static SatelliteAccrual.Rules wearOn() {
        return new SatelliteAccrual.Rules(true, 1d);
    }

    /** An Earth-like body's environment: one Earth mass and radius, a day's spin, rocky, with air, at 1 AU. */
    private static PlanetEnvironment earthLikeField() {
        return PlanetEnvironment.of(MagnetosphereDerivation.derive(1d, 1d, ROTATION_TICKS, false, true, 1d, FIELD_SEED),
                ROTATION_TICKS, 0d);
    }

    /** A body that keeps no field. */
    private static PlanetEnvironment noField() {
        return PlanetEnvironment.of(Optional.empty(), ROTATION_TICKS, 0d);
    }

    /** A star fixed along +x of the orbit's frame. */
    private static Optional<Vector3dc> starAlongX(long tick) {
        return Optional.of(new Vector3d(1d, 0d, 0d));
    }

    private static SatelliteOrbit orbitAt(double radiusM, double inclinationDeg) {
        return SatelliteOrbit.releasedAt(radiusM, Math.toRadians(inclinationDeg), EARTH_GM, earthDay(), 0L);
    }

    private static SatelliteAccrual.Body about(SatelliteOrbit orbit, PlanetEnvironment field) {
        return new SatelliteAccrual.Body(orbit, Optional.of(field), SatelliteLawsTest::starAlongX, BODY_RADIUS_M);
    }

    /** A data satellite: its primary function, one large panel, one data unit and a battery; a shield when asked. */
    private static List<SatelliteAccrual.Part> dataSatellite(boolean shielded, double primaryDoseGy) {
        List<SatelliteAccrual.Part> parts = new ArrayList<>();
        parts.add(new SatelliteAccrual.Part(0, SatelliteAccrual.PartKind.ELECTRONICS, true, 0d, 0, primaryDoseGy));
        parts.add(new SatelliteAccrual.Part(1, SatelliteAccrual.PartKind.POWER_GENERATION, false, PANEL_POWER, 0, 0d));
        parts.add(new SatelliteAccrual.Part(4, SatelliteAccrual.PartKind.ELECTRONICS, false, 0d, DATA_UNIT_POINTS, 0d));
        parts.add(new SatelliteAccrual.Part(5, SatelliteAccrual.PartKind.INERT, false, 0d, 0, 0d));
        if (shielded) {
            parts.add(new SatelliteAccrual.Part(6, SatelliteAccrual.PartKind.SHIELD, false, 0d, 0, 0d));
        }
        return parts;
    }

    private static SatelliteAccrual.Payload collector() {
        return new SatelliteAccrual.Payload(true, false, COLLECTION_TICKS, INSTRUMENT_DRAW);
    }

    private static SatelliteAccrual.Payload dosimeter() {
        return new SatelliteAccrual.Payload(false, true, 0d, DOSIMETER_DRAW);
    }

    private static SatelliteAccrual.State freshState(double energyCap, boolean withReadings) {
        return new SatelliteAccrual.State(0L, 0d, energyCap, 0L, 0d, new FailureJournal(),
                withReadings ? new DoseReadings() : null);
    }

    private static SatelliteAccrual.Part slot(List<SatelliteAccrual.Part> parts, int slot) {
        for (SatelliteAccrual.Part part : parts) {
            if (part.slot() == slot) {
                return part;
            }
        }
        throw new AssertionError("no part in slot " + slot);
    }

    private static int lostPoints(FailureJournal journal) {
        int lost = 0;
        for (FailureJournal.Entry e : journal.entries()) {
            if (e.kind == FailureJournal.Kind.DATA_LOST) {
                lost += e.count;
            }
        }
        return lost;
    }

    /**
     * The relative difference INV-SAT-21 allows between two values the arithmetic rounds: a transcendental
     * function's result may differ by one unit in the last place (1.1e-16 of it) between an interpreted and
     * a compiled call, and a dose sums a few thousand segments, so honest rounding stays below 1e-12. A
     * grid that moved with the call would shift every sample point and change a dose by percent.
     */
    private static final double ROUNDING = 1e-9d;

    /** What INV-SAT-21 holds exactly: the data, the progress, the journal, the readings' ticks and phases. */
    private static String exactPartOf(SatelliteAccrual.State state) {
        StringBuilder out = new StringBuilder("data=").append(state.data()).append(" segments=").append(state.segmentsDone())
                .append(" journal=");
        for (FailureJournal.Entry e : state.journal().entries()) {
            out.append(e.tick).append('/').append(e.phaseRad).append('/').append(e.kind).append('/').append(e.count)
                    .append('/').append(e.slot).append(';');
        }
        out.append("dropped=").append(state.journal().dropped());
        if (state.readings() != null) {
            out.append(" readings=");
            for (DoseReadings.Reading r : state.readings().readings()) {
                out.append(r.tick).append('/').append(r.phaseRad).append('/').append(r.gyPerS().isPresent()).append(';');
            }
        }
        return out.toString();
    }

    /** What INV-SAT-21 holds to rounding: the energy, the carry, every part's dose, the rates read. */
    private static List<Double> roundedPartOf(SatelliteAccrual.State state, List<SatelliteAccrual.Part> parts) {
        List<Double> out = new ArrayList<>();
        out.add(state.energy());
        out.add(state.collectionCarry());
        for (SatelliteAccrual.Part part : parts) {
            out.add(part.doseGy());
        }
        if (state.readings() != null) {
            for (DoseReadings.Reading r : state.readings().readings()) {
                out.add(r.gyPerS().orElse(0d));
            }
        }
        return out;
    }

    private static void assertRounded(String message, List<Double> expected, List<Double> actual) {
        assertEquals(message + " — not the same values", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            double e = expected.get(i);
            assertEquals(message + " — value " + i, e, actual.get(i), Math.abs(e) * ROUNDING);
        }
    }

    private static String readingsOf(DoseReadings readings) {
        StringBuilder out = new StringBuilder();
        for (DoseReadings.Reading r : readings.readings()) {
            out.append(r.tick).append('/').append(r.phaseRad).append('/').append(r.gyPerS()).append(';');
        }
        return out.toString();
    }

    /**
     * INV-SAT-21: one satellite brought to the same tick in one catch-up, segment by segment, and in
     * irregular pieces ends in one state — energy, data, every part's dose, the journal and the readings.
     * The run crosses the anomaly of a low inclined orbit for three days, so the journal is not empty and
     * the equality is between states that lost points, not between three empty ones.
     */
    @Pins("INV-SAT-21")
    @Regions(of = "INV-SAT-21", value = {"one catch-up", "per segment", "irregular pieces"})
    @RedWitness(verdict = "exact", member = "SatelliteAccrual#advance",
            at = "new SplittableRandom(seedOf(satelliteId, state.segmentsDone))",
            replacedBy = "new SplittableRandom(seedOf(satelliteId, now))",
            fails = "INV-SAT-21: per segment — data, journal or readings differ", on = "2026-10-21")
    @RedWitness(verdict = "rounded", member = "SatelliteAccrual#advance",
            at = "protonGy = dose.get().protonGyPerS() * seconds * rules.wearScale;",
            replacedBy = "protonGy = dose.get().protonGyPerS() * seconds * rules.wearScale * (1d + 1e-6d * (now % 3L));",
            fails = "INV-SAT-21: irregular pieces — energy, doses or rates differ", on = "2026-10-21")
    @Test
    public void askingDoesNotChangeTheAnswer() {
        SatelliteOrbit orbit = orbitAt(LOW_ORBIT_RADIUS_M, 51.6d);
        SatelliteAccrual.Body body = about(orbit, earthLikeField());
        long step = SatelliteAccrual.segmentTicks(orbit);
        long end = 3L * DAY_TICKS;
        SatelliteAccrual.Payload[] payloads = {collector(), dosimeter()};
        int[] calls = new int[3];
        for (SatelliteAccrual.Payload payload : payloads) {
            boolean reads = payload == payloads[1];
            String[] exact = new String[3];
            List<List<Double>> rounded = new ArrayList<>();
            for (int mode = 0; mode < 3; mode++) {
                List<SatelliteAccrual.Part> parts = dataSatellite(false, 0d);
                SatelliteAccrual.State state = freshState(BIG_BATTERY, reads);
                SplittableRandom pieces = new SplittableRandom(mode);
                long now = 0L;
                while (now < end) {
                    now = mode == 0 ? end : mode == 1 ? Math.min(end, now + step) : Math.min(end, now + 1L + pieces.nextInt(5_000));
                    SatelliteAccrual.advance(body, parts, payload, state, wearOn(), 42L, now);
                    calls[mode]++;
                }
                exact[mode] = exactPartOf(state);
                rounded.add(roundedPartOf(state, parts));
                if (mode == 0) {
                    assertTrue("arrangement: the run crossed the anomaly and lost points (" + exact[0] + ")",
                            reads || lostPoints(state.journal()) > 0);
                    assertTrue("arrangement: the dosimeter read something", !reads || !state.readings().readings().isEmpty());
                }
            }
            for (int mode = 1; mode < 3; mode++) {
                String name = mode == 1 ? "per segment" : "irregular pieces";
                assertEquals("INV-SAT-21: " + name + " — data, journal or readings differ from one catch-up",
                        exact[0], exact[mode]);
                assertRounded("INV-SAT-21: " + name + " — energy, doses or rates differ from one catch-up",
                        rounded.get(0), rounded.get(mode));
            }
        }
        assertTrue("arrangement: one catch-up per run", calls[0] == payloads.length);
        assertTrue("arrangement: per segment took every segment as its own call", calls[1] >= payloads.length * end / step);
        assertTrue("arrangement: irregular pieces were many and uneven", calls[2] >= payloads.length * 20);
    }

    /**
     * INV-SAT-23: the electronics arm is an unscreened data satellite on a low inclined orbit for a day —
     * the anomaly destroys points while its panel's dose is a thousandth of a gray; the panels arm is a
     * shielded one on an equatorial orbit at the outer belt's peak shell, which holds electrons and no
     * protons, for a hundred days — its panel fades and no point is lost.
     */
    @Pins("INV-SAT-23")
    @Regions(of = "INV-SAT-23", value = {"electronics exposed", "panels exposed"})
    @RedWitness(verdict = "electronics exposed", member = "RadiationWear#pointLossProbability",
            at = "return protonGy > 0d ? -Math.expm1(-UPSETS_PER_POINT_PER_GY * protonGy) : 0d;", replacedBy = "return 0d;",
            fails = "INV-SAT-23: electronics exposed — no point was lost", on = "2026-10-21")
    @RedWitness(verdict = "panels fade", member = "SatelliteAccrual#advance",
            at = "part.doseGy += protonGy + electronGy;", replacedBy = "part.doseGy += inside;",
            fails = "INV-SAT-23: panels exposed — the panel did not fade", on = "2026-10-21")
    @RedWitness(verdict = "nothing lost", member = "SatelliteAccrual#advance",
            at = "double memoryProtons = RadiationWear.insideProtons(protonGy, shielded);",
            replacedBy = "double memoryProtons = protonGy + electronGy;",
            fails = "INV-SAT-23: panels exposed — points were lost behind the shield", on = "2026-10-21")
    @Test
    public void eachPartAnswersToItsOwnDose() {
        List<SatelliteAccrual.Part> exposedElectronics = dataSatellite(false, 0d);
        SatelliteAccrual.State electronics = new SatelliteAccrual.State(0L, BIG_BATTERY, BIG_BATTERY, DATA_UNIT_POINTS, 0d,
                new FailureJournal(), null);
        SatelliteAccrual.advance(about(orbitAt(LOW_ORBIT_RADIUS_M, 51.6d), earthLikeField()), exposedElectronics,
                collector(), electronics, wearOn(), 1L, DAY_TICKS);
        double electronicsArmPower = slot(exposedElectronics, 1).powerPerTick(wearOn()) / PANEL_POWER;
        assertTrue("INV-SAT-23: electronics exposed — no point was lost on the anomaly's orbit",
                lostPoints(electronics.journal()) > 0);
        assertTrue("INV-SAT-23: electronics exposed — the panel fell below 0.999 of its power: " + electronicsArmPower,
                electronicsArmPower >= 0.999d);

        List<SatelliteAccrual.Part> exposedPanels = dataSatellite(true, 0d);
        SatelliteAccrual.State panels = freshState(BIG_BATTERY, false);
        SatelliteAccrual.advance(about(orbitAt(4.5d * BODY_RADIUS_M, 0d), earthLikeField()), exposedPanels,
                collector(), panels, wearOn(), 2L, 100L * DAY_TICKS);
        double panelsArmPower = slot(exposedPanels, 1).powerPerTick(wearOn()) / PANEL_POWER;
        assertTrue("arrangement: the shielded satellite collected points to lose",
                panels.data() + lostPoints(panels.journal()) > 0L);
        assertTrue("INV-SAT-23: panels exposed — the panel did not fade below 0.99: " + panelsArmPower,
                panelsArmPower < 0.99d);
        assertEquals("INV-SAT-23: panels exposed — points were lost behind the shield", 0, lostPoints(panels.journal()));
    }

    /**
     * INV-SAT-24: one satellite with and without a shield part, on the same orbit for the same day. On an
     * equatorial orbit at the inner belt's peak shell the dose is protons, so what is inside takes it
     * thinned by the proton factor (117/64.7, MECH-SAT-25); at the outer belt's peak it is electrons,
     * thinned by the electron factor (1266/1.5). The panels take the same dose either way.
     */
    @Pins("INV-SAT-24")
    @Regions(of = "INV-SAT-24", value = {"inner belt", "outer belt"})
    @RedWitness(verdict = "inner belt", member = "RadiationWear#SHIELD_PROTON_FACTOR", at = "117d / 64.7d",
            replacedBy = "1266d / 1.5d", fails = "INV-SAT-24: inner belt — the primary function is not thinned", on = "2026-10-21")
    @RedWitness(verdict = "outer belt", member = "RadiationWear#SHIELD_ELECTRON_FACTOR", at = "1266d / 1.5d",
            replacedBy = "117d / 64.7d", fails = "INV-SAT-24: outer belt — the primary function is not thinned", on = "2026-10-21")
    @RedWitness(verdict = "data unit", member = "SatelliteAccrual#advance", at = "part.doseGy += inside;",
            replacedBy = "part.doseGy += part.primary ? inside : protonGy + electronGy;",
            fails = "INV-SAT-24: inner belt — the data unit is not thinned like the primary function", on = "2026-10-21")
    @RedWitness(verdict = "panels", member = "SatelliteAccrual#advance", at = "part.doseGy += protonGy + electronGy;",
            replacedBy = "part.doseGy += inside;",
            fails = "INV-SAT-24: inner belt — the shield changed the panel's dose", on = "2026-10-21")
    @Test
    public void aShieldThinsWhatIsInsideNotThePanels() {
        double[][] cases = {{1.5d * BODY_RADIUS_M, 117d / 64.7d}, {4.5d * BODY_RADIUS_M, 1266d / 1.5d}};
        String[] names = {"inner belt", "outer belt"};
        for (int i = 0; i < cases.length; i++) {
            SatelliteAccrual.Body body = about(orbitAt(cases[i][0], 0d), earthLikeField());
            List<SatelliteAccrual.Part> bare = dataSatellite(false, 0d);
            List<SatelliteAccrual.Part> shielded = dataSatellite(true, 0d);
            SatelliteAccrual.advance(body, bare, collector(), freshState(BIG_BATTERY, false), wearOn(), 3L, DAY_TICKS);
            SatelliteAccrual.advance(body, shielded, collector(), freshState(BIG_BATTERY, false), wearOn(), 3L, DAY_TICKS);
            double bareInside = slot(bare, 0).doseGy();
            assertTrue("arrangement: " + names[i] + " — the orbit took a dose", bareInside > 0d);
            double ratio = bareInside / slot(shielded, 0).doseGy();
            assertEquals("INV-SAT-24: " + names[i] + " — the primary function is not thinned by the belt's factor",
                    cases[i][1], ratio, cases[i][1] * 0.01d);
            assertEquals("INV-SAT-24: " + names[i] + " — the data unit is not thinned like the primary function",
                    slot(bare, 4).doseGy() / slot(shielded, 4).doseGy(), ratio, ratio * 1e-9d);
            assertEquals("INV-SAT-24: " + names[i] + " — the shield changed the panel's dose",
                    slot(bare, 1).doseGy(), slot(shielded, 1).doseGy(), 0d);
        }
    }

    /**
     * INV-SAT-25: on an equatorial orbit at the outer belt's peak (electrons only, so no upsets blur the
     * data), a dosimeter whose electronics start just under the budget reads while under it, stops at it
     * and journals that once; renewed, it reads again. A data satellite whose first data unit starts just
     * under the budget loses that unit's capacity, and the points over what is left are journalled.
     */
    @Pins("INV-SAT-25")
    @Regions(of = "INV-SAT-25", value = {"below budget", "at budget", "renewed", "data unit"})
    @RedWitness(verdict = "below budget", member = "SatelliteAccrual#advance",
            at = "if (works && payload.readsDose && here.isPresent()) {", replacedBy = "if (false) {",
            fails = "INV-SAT-25: below budget — a working dosimeter did not read", on = "2026-10-22")
    @RedWitness(verdict = "at budget", member = "RadiationWear#electronicsWork",
            at = "return doseGy < ELECTRONICS_BUDGET_GY;", replacedBy = "return true;",
            fails = "INV-SAT-25: at budget — the primary function works past its budget", on = "2026-10-21")
    @RedWitness(verdict = "journalled once", member = "SatelliteAccrual#advance",
            at = "state.journal.record(mid, phase, FailureJournal.Kind.PART_FAILED, 1, part.slot);", replacedBy = "",
            fails = "INV-SAT-25: at budget — the stop is not journalled exactly once", on = "2026-10-21")
    @RedWitness(verdict = "renewed", member = "SatelliteAccrual#advance",
            at = "boolean functioning = primary.isPresent() && primary.get().works(rules);",
            replacedBy = "boolean functioning = primary.isPresent() && primary.get().works(rules) && state.journal.entries().isEmpty();",
            fails = "INV-SAT-25: renewed — a renewed primary function did not read again", on = "2026-10-21")
    @RedWitness(verdict = "data unit cut", member = "SatelliteAccrual#advance", at = "state.data = newCap;", replacedBy = "",
            fails = "INV-SAT-25: data unit — the buffer is not cut", on = "2026-10-21")
    @RedWitness(verdict = "overflow journalled", member = "SatelliteAccrual#advance",
            at = "state.journal.record(mid, phase, FailureJournal.Kind.DATA_OVERFLOWED, (int) (state.data - newCap), -1);",
            replacedBy = "", fails = "INV-SAT-25: data unit — the points over the new capacity are not journalled", on = "2026-10-21")
    @Test
    public void pastItsBudgetAPartStops() {
        SatelliteOrbit orbit = orbitAt(4.5d * BODY_RADIUS_M, 0d);
        SatelliteAccrual.Body body = about(orbit, earthLikeField());
        long step = SatelliteAccrual.segmentTicks(orbit);
        double budget = 50d;
        List<SatelliteAccrual.Part> parts = dataSatellite(false, budget - 1d);
        SatelliteAccrual.State state = freshState(BIG_BATTERY, true);
        int below = 0;
        int stoppedCalls = 0;
        for (int k = 1; k <= 400; k++) {
            int readingsBefore = state.readings().readings().size();
            boolean worked = slot(parts, 0).works(wearOn());
            SatelliteAccrual.advance(body, parts, dosimeter(), state, wearOn(), 4L, k * step);
            int read = state.readings().readings().size() - readingsBefore;
            if (worked) {
                below++;
                assertEquals("INV-SAT-25: below budget — a working dosimeter did not read at segment " + k, 1, read);
            } else {
                stoppedCalls++;
                assertEquals("INV-SAT-25: at budget — a stopped dosimeter read at segment " + k, 0, read);
            }
            assertEquals("INV-SAT-25: at budget — the primary function works past its budget at segment " + k
                    + " (dose " + slot(parts, 0).doseGy() + ")", slot(parts, 0).doseGy() < budget, slot(parts, 0).works(wearOn()));
        }
        assertTrue("arrangement: it read below the budget", below > 0);
        assertTrue("arrangement: it reached the budget", stoppedCalls > 0);
        int stopEntries = 0;
        for (FailureJournal.Entry e : state.journal().entries()) {
            if (e.kind == FailureJournal.Kind.PART_FAILED) {
                stopEntries++;
                assertEquals("INV-SAT-25: at budget — the stop names the wrong slot", 0, e.slot);
            }
        }
        assertEquals("INV-SAT-25: at budget — the stop is not journalled exactly once", 1, stopEntries);

        List<SatelliteAccrual.Part> renewed = new ArrayList<>(parts);
        renewed.set(0, new SatelliteAccrual.Part(0, SatelliteAccrual.PartKind.ELECTRONICS, true, 0d, 0, 0d));
        int readingsBefore = state.readings().readings().size();
        SatelliteAccrual.advance(body, renewed, dosimeter(), state, wearOn(), 4L, 410L * step);
        assertEquals("INV-SAT-25: renewed — a renewed primary function did not read again", 10,
                state.readings().readings().size() - readingsBefore);

        List<SatelliteAccrual.Part> twoUnits = dataSatellite(false, 0d);
        twoUnits.set(2, new SatelliteAccrual.Part(4, SatelliteAccrual.PartKind.ELECTRONICS, false, 0d, DATA_UNIT_POINTS, budget - 0.05d));
        twoUnits.add(new SatelliteAccrual.Part(3, SatelliteAccrual.PartKind.ELECTRONICS, false, 0d, DATA_UNIT_POINTS / 2, 0d));
        SatelliteAccrual.State data = new SatelliteAccrual.State(0L, BIG_BATTERY, BIG_BATTERY, 1200L, 0d, new FailureJournal(), null);
        long dataBefore = 0L;
        for (int k = 1; k <= 400 && slot(twoUnits, 4).works(wearOn()); k++) {
            dataBefore = data.data();
            SatelliteAccrual.advance(body, twoUnits, collector(), data, wearOn(), 5L, k * step);
        }
        assertTrue("arrangement: data unit — the first unit reached its budget", !slot(twoUnits, 4).works(wearOn()));
        assertEquals("INV-SAT-25: data unit — the buffer is not cut to the unit that is left",
                DATA_UNIT_POINTS / 2, data.data());
        int overflowed = 0;
        for (FailureJournal.Entry e : data.journal().entries()) {
            if (e.kind == FailureJournal.Kind.DATA_OVERFLOWED) {
                overflowed += e.count;
            }
        }
        long mostCollected = (long) Math.ceil(step / COLLECTION_TICKS);
        assertTrue("INV-SAT-25: data unit — the points over the new capacity are not journalled: " + overflowed
                        + " journalled, " + dataBefore + " held before the stop",
                overflowed >= dataBefore - DATA_UNIT_POINTS / 2 && overflowed <= dataBefore + mostCollected - DATA_UNIT_POINTS / 2);
    }

    /**
     * INV-SAT-27: a satellite without an instrument on an equatorial low orbit under a star along +x,
     * segment by segment — in the body's shadow (the test's own cylinder on the segment's middle) its
     * battery only pays the upkeep, in light it gains the panel's power less the upkeep, never past its
     * capacity or below empty. A data satellite with its buffer full does not run its instrument. Then, on a
     * polar orbit square to the star, so always lit: two satellites whose panels give 4 and 40 — both over
     * the draw — collect the same points; one with a 40 panel collects a point per its instrument's time; one
     * whose panel only pays its upkeep and whose battery holds less than a segment's draw collects nothing
     * and keeps its battery; and a dosimeter so powered records no reading where one with a 40 panel records
     * one a segment.
     */
    @Pins("INV-SAT-27")
    @Regions(of = "INV-SAT-27", value = {"light", "shadow", "full buffer", "larger panels", "short of power",
            "unpowered dosimeter"})
    @RedWitness(verdict = "light", member = "SatelliteAccrual#advance",
            at = "double generation = sunlit(position, star, body.radiusM) ? generation(parts, rules) : 0d;",
            replacedBy = "double generation = sunlit(position, star, body.radiusM) ? generation(parts, rules) / 2d : 0d;",
            fails = "INV-SAT-27: light — energy at segment", on = "2026-10-21")
    @RedWitness(verdict = "shadow", member = "SatelliteAccrual#sunlit", at = "return across.length() >= radiusM;",
            replacedBy = "return true;", fails = "INV-SAT-27: shadow — energy at segment", on = "2026-10-21")
    @RedWitness(verdict = "capacity", member = "SatelliteAccrual#advance",
            at = "state.data = Math.min(dataCap, state.data + points);", replacedBy = "state.data = state.data + points;",
            fails = "INV-SAT-27: full buffer — the buffer went past its capacity", on = "2026-10-21")
    @RedWitness(verdict = "full buffer idle", member = "SatelliteAccrual#advance",
            at = "boolean collecting = payload.collectsData && state.data < dataCap;",
            replacedBy = "boolean collecting = payload.collectsData;",
            fails = "INV-SAT-27: full buffer — a full buffer still spent power collecting", on = "2026-10-22")
    @RedWitness(verdict = "larger panels", member = "SatelliteAccrual#advance",
            at = "state.collectionCarry += step;", replacedBy = "state.collectionCarry += step * generation / 4d;",
            fails = "INV-SAT-27: larger panels", on = "2026-10-22")
    @RedWitness(verdict = "collection time", member = "SatelliteAccrual#advance",
            at = "state.collectionCarry += step;", replacedBy = "state.collectionCarry += 2d * step;",
            fails = "INV-SAT-27: light — not one point per collection time", on = "2026-10-22")
    @RedWitness(verdict = "short of power", member = "SatelliteAccrual#advance",
            at = "boolean works = functioning && (collecting || payload.readsDose) && available >= need;",
            replacedBy = "boolean works = functioning && (collecting || payload.readsDose);",
            fails = "INV-SAT-27: short of power", on = "2026-10-22")
    @RedWitness(verdict = "unpowered dosimeter", member = "SatelliteAccrual#advance",
            at = "if (works && payload.readsDose && here.isPresent()) {",
            replacedBy = "if (functioning && payload.readsDose && here.isPresent()) {",
            fails = "INV-SAT-27: unpowered dosimeter", on = "2026-10-22")
    @Test
    public void powerBoundsWhatIsCollected() {
        SatelliteOrbit orbit = orbitAt(LOW_ORBIT_RADIUS_M, 0d);
        SatelliteAccrual.Body body = about(orbit, noField());
        long step = SatelliteAccrual.segmentTicks(orbit);
        double cap = 30_000d;
        List<SatelliteAccrual.Part> parts = dataSatellite(false, 0d);
        SatelliteAccrual.State bare = new SatelliteAccrual.State(0L, cap / 2d, cap, 0L, 0d, new FailureJournal(), null);
        SatelliteAccrual.Payload none = new SatelliteAccrual.Payload(false, false, 0d, 0d);
        int lit = 0;
        int dark = 0;
        for (int k = 1; k <= 3 * 64; k++) {
            long mid = (k - 1) * step + step / 2L;
            Vector3dc at = orbit.positionAt(mid);
            boolean shadow = at.x() < 0d && Math.hypot(at.y(), at.z()) < BODY_RADIUS_M;
            double before = bare.energy();
            SatelliteAccrual.advance(body, parts, none, bare, wearOn(), 6L, k * step);
            double expected = Math.min(cap, Math.max(0d, before + ((shadow ? 0d : PANEL_POWER) - 1d) * step));
            assertEquals("INV-SAT-27: " + (shadow ? "shadow" : "light") + " — energy at segment " + k,
                    expected, bare.energy(), 1e-6d);
            if (shadow) {
                dark++;
            } else {
                lit++;
            }
        }
        assertTrue("arrangement: segments in light and in shadow (" + lit + ", " + dark + ")", lit > 64 && dark > 16);

        // A buffer one point short fills and stops at its capacity — on a high orbit, whose segments are
        // long enough to bring several points at once; a full one does not run the instrument, so its
        // battery ends where that of a satellite with no instrument does.
        SatelliteOrbit high = orbitAt(6.6d * BODY_RADIUS_M, 0d);
        assertTrue("arrangement: a segment of the high orbit brings several points",
                SatelliteAccrual.segmentTicks(high) >= 2d * COLLECTION_TICKS);
        SatelliteAccrual.State nearlyFull = new SatelliteAccrual.State(0L, cap, cap, DATA_UNIT_POINTS - 1, 0d, new FailureJournal(), null);
        SatelliteAccrual.advance(about(high, noField()), dataSatellite(false, 0d), collector(), nearlyFull, wearOn(), 7L, DAY_TICKS);
        assertEquals("INV-SAT-27: full buffer — the buffer went past its capacity", DATA_UNIT_POINTS, nearlyFull.data());
        SatelliteAccrual.State full = new SatelliteAccrual.State(0L, cap / 2d, cap, DATA_UNIT_POINTS, 0d, new FailureJournal(), null);
        SatelliteAccrual.State idle = new SatelliteAccrual.State(0L, cap / 2d, cap, 0L, 0d, new FailureJournal(), null);
        SatelliteAccrual.advance(body, dataSatellite(false, 0d), collector(), full, wearOn(), 7L, DAY_TICKS);
        SatelliteAccrual.advance(body, dataSatellite(false, 0d), none, idle, wearOn(), 7L, DAY_TICKS);
        assertEquals("INV-SAT-27: full buffer — a full buffer still spent power collecting", idle.energy(), full.energy(), 1e-6d);

        // A polar orbit whose node is along x, under a star along y: the star is square to the orbit's
        // plane, so no point of it is ever in the shadow.
        SatelliteAccrual.Body litAlways = new SatelliteAccrual.Body(orbitAt(LOW_ORBIT_RADIUS_M, 90d), Optional.of(noField()),
                tick -> Optional.of(new Vector3d(0d, 1d, 0d)), BODY_RADIUS_M);
        long ticks = 200L * step;
        assertTrue("arrangement: a 4-unit panel covers the instrument's draw after upkeep",
                4d - 1d >= INSTRUMENT_DRAW);
        SatelliteAccrual.State small = new SatelliteAccrual.State(0L, 0d, cap, 0L, 0d, new FailureJournal(), null);
        SatelliteAccrual.State large = new SatelliteAccrual.State(0L, 0d, cap, 0L, 0d, new FailureJournal(), null);
        SatelliteAccrual.advance(litAlways, withPanel(4d), collector(), small, wearOn(), 8L, ticks);
        SatelliteAccrual.advance(litAlways, withPanel(PANEL_POWER), collector(), large, wearOn(), 8L, ticks);
        assertEquals("INV-SAT-27: larger panels — a 40 panel's instrument collected unlike a 4 panel's",
                small.data(), large.data());
        assertEquals("INV-SAT-27: light — not one point per collection time in full power",
                Math.floor(ticks / COLLECTION_TICKS), large.data(), 1d);

        double held = 10d;
        assertTrue("arrangement: the battery holds less than one segment's draw", held < INSTRUMENT_DRAW * step);
        SatelliteAccrual.State starved = new SatelliteAccrual.State(0L, held, cap, 0L, 0d, new FailureJournal(), null);
        SatelliteAccrual.advance(litAlways, withPanel(1d), collector(), starved, wearOn(), 9L, ticks);
        assertEquals("INV-SAT-27: short of power — an instrument its power cannot cover collected", 0L, starved.data());
        assertEquals("INV-SAT-27: short of power — an idle instrument spent the battery", held, starved.energy(), 1e-9d);

        SatelliteAccrual.State unpowered = new SatelliteAccrual.State(0L, 0d, cap, 0L, 0d, new FailureJournal(), new DoseReadings());
        SatelliteAccrual.State powered = new SatelliteAccrual.State(0L, 0d, cap, 0L, 0d, new FailureJournal(), new DoseReadings());
        SatelliteAccrual.advance(litAlways, withPanel(1d), dosimeter(), unpowered, wearOn(), 10L, ticks);
        SatelliteAccrual.advance(litAlways, withPanel(PANEL_POWER), dosimeter(), powered, wearOn(), 10L, ticks);
        assertEquals("arrangement: a powered dosimeter reads once a segment", ticks / step,
                powered.readings().readings().size());
        assertEquals("INV-SAT-27: unpowered dosimeter — a dosimeter its power cannot cover read", 0,
                unpowered.readings().readings().size());
    }

    /** {@link #dataSatellite} with its one panel giving {@code power} per tick. */
    private static List<SatelliteAccrual.Part> withPanel(double power) {
        List<SatelliteAccrual.Part> parts = dataSatellite(false, 0d);
        parts.set(1, new SatelliteAccrual.Part(1, SatelliteAccrual.PartKind.POWER_GENERATION, false, power, 0, 0d));
        return parts;
    }

    /**
     * INV-SAT-28: after a run that lost points, read a dosimeter and journalled them, what the writers put
     * in NBT is the progress, the carries, the journal's entries and the readings — the key sets the clause
     * names, and no other.
     */
    @Pins("INV-SAT-28")
    @RedWitness(verdict = "life record", member = "SatelliteLife.Progress#writeToNBT",
            at = "nbt.setLong(\"segments\", segmentsDone);",
            replacedBy = "nbt.setLong(\"segments\", segmentsDone); nbt.setBoolean(\"wasInRegion\", true);",
            fails = "INV-SAT-28: the life record holds more than its progress and journal", on = "2026-10-21")
    @RedWitness(verdict = "journal", member = "FailureJournal#writeToNBT", at = "nbt.setLong(\"dropped\", dropped);",
            replacedBy = "nbt.setLong(\"dropped\", dropped); nbt.setBoolean(\"wasInRegion\", true);",
            fails = "INV-SAT-28: the journal holds more than its entries", on = "2026-10-21")
    @RedWitness(verdict = "journal entry", member = "FailureJournal#writeToNBT",
            at = "tag.setByte(\"slot\", (byte) e.slot);",
            replacedBy = "tag.setByte(\"slot\", (byte) e.slot); tag.setBoolean(\"inRegion\", true);",
            fails = "INV-SAT-28: a journal entry holds more than a dated failure", on = "2026-10-21")
    @RedWitness(verdict = "readings", member = "DoseReadings#writeToNBT", at = "nbt.setLong(\"dropped\", dropped);",
            replacedBy = "nbt.setLong(\"dropped\", dropped); nbt.setBoolean(\"wasInRegion\", true);",
            fails = "INV-SAT-28: the readings hold more than their entries", on = "2026-10-21")
    @RedWitness(verdict = "reading", member = "DoseReadings#writeToNBT", at = "tag.setDouble(\"phase\", r.phaseRad);",
            replacedBy = "tag.setDouble(\"phase\", r.phaseRad); tag.setBoolean(\"inRegion\", true);",
            fails = "INV-SAT-28: a reading holds more than a dated rate", on = "2026-10-21")
    @Test
    public void nothingRecordsWhereItHasBeen() {
        SatelliteAccrual.Body body = about(orbitAt(LOW_ORBIT_RADIUS_M, 51.6d), earthLikeField());
        SatelliteAccrual.State collected = freshState(BIG_BATTERY, false);
        SatelliteAccrual.advance(body, dataSatellite(false, 0d), collector(), collected, wearOn(), 10L, 2L * DAY_TICKS);
        SatelliteAccrual.State read = freshState(BIG_BATTERY, true);
        SatelliteAccrual.advance(body, dataSatellite(false, 0d), dosimeter(), read, wearOn(), 10L, DAY_TICKS);
        assertTrue("arrangement: something was journalled and read",
                !collected.journal().entries().isEmpty() && !read.readings().readings().isEmpty());

        NBTTagCompound progress = new NBTTagCompound();
        new SatelliteLife.Progress().writeToNBT(progress);
        assertEquals("INV-SAT-28: the life record holds more than its progress and journal",
                new TreeSet<>(Arrays.asList("segments", "energyCarry", "collectionCarry", "journal")),
                new TreeSet<>(progress.getKeySet()));
        NBTTagCompound journal = new NBTTagCompound();
        collected.journal().writeToNBT(journal);
        assertEquals("INV-SAT-28: the journal holds more than its entries",
                new TreeSet<>(Arrays.asList("entries", "dropped")), new TreeSet<>(journal.getKeySet()));
        assertEquals("INV-SAT-28: a journal entry holds more than a dated failure",
                new TreeSet<>(Arrays.asList("tick", "phase", "kind", "count", "slot")),
                new TreeSet<>(journal.getTagList("entries", 10).getCompoundTagAt(0).getKeySet()));
        NBTTagCompound readings = new NBTTagCompound();
        read.readings().writeToNBT(readings);
        assertEquals("INV-SAT-28: the readings hold more than their entries",
                new TreeSet<>(Arrays.asList("readings", "dropped")), new TreeSet<>(readings.getKeySet()));
        assertEquals("INV-SAT-28: a reading holds more than a dated rate",
                new TreeSet<>(Arrays.asList("tick", "phase", "rate")),
                new TreeSet<>(readings.getTagList("readings", 10).getCompoundTagAt(0).getKeySet()));
    }

    /**
     * INV-SAT-29: the same data satellite and dosimeter on the anomaly's orbit for a day, the wear on and
     * off. Off, no part took dose, no point was lost, nothing was journalled and the panel gives its full
     * power; the dosimeter read the same readings both ways. On, the parts took dose and points were lost.
     */
    @Pins("INV-SAT-29")
    @Regions(of = "INV-SAT-29", value = {"wear on", "wear off"})
    @RedWitness(verdict = "on: dose", member = "SatelliteAccrual#advance", at = "if (rules.wearEnabled && dose.isPresent()) {",
            replacedBy = "if (!rules.wearEnabled && dose.isPresent()) {",
            fails = "INV-SAT-29: wear on — the parts took no dose", on = "2026-10-21")
    @RedWitness(verdict = "on: loss", member = "RadiationWear#pointLossProbability",
            at = "return protonGy > 0d ? -Math.expm1(-UPSETS_PER_POINT_PER_GY * protonGy) : 0d;", replacedBy = "return 0d;",
            fails = "INV-SAT-29: wear on — no point was lost", on = "2026-10-21")
    @RedWitness(verdict = "off: dose", member = "SatelliteAccrual#advance", at = "if (rules.wearEnabled && dose.isPresent()) {",
            replacedBy = "if (dose.isPresent()) {", fails = "INV-SAT-29: wear off — slot 0 took dose", on = "2026-10-21")
    @RedWitness(verdict = "off: journal", member = "SatelliteAccrual#advance",
            at = "double memoryProtons = RadiationWear.insideProtons(protonGy, shielded);",
            replacedBy = "double memoryProtons = RadiationWear.insideProtons(dose.isPresent() ? dose.get().protonGyPerS() * seconds : 0d, shielded);",
            fails = "INV-SAT-29: wear off — something was journalled", on = "2026-10-21")
    @RedWitness(verdict = "off: power", member = "SatelliteAccrual.Part#powerPerTick",
            at = "return rules.wearEnabled ? nominalPowerPerTick * RadiationWear.panelPowerFraction(doseGy) : nominalPowerPerTick;",
            replacedBy = "return nominalPowerPerTick * RadiationWear.panelPowerFraction(doseGy + 1e5d);",
            fails = "INV-SAT-29: wear off — the panel does not give its full power", on = "2026-10-21")
    @RedWitness(verdict = "off: readings", member = "SatelliteAccrual#advance",
            at = "if (works && payload.readsDose && here.isPresent()) {",
            replacedBy = "if (works && payload.readsDose && here.isPresent() && rules.wearEnabled) {",
            fails = "INV-SAT-29: wear off — the dosimeter read differently", on = "2026-10-22")
    @Test
    public void switchedOffTheDoseDoesNothing() {
        SatelliteAccrual.Body body = about(orbitAt(LOW_ORBIT_RADIUS_M, 51.6d), earthLikeField());
        SatelliteAccrual.Rules off = new SatelliteAccrual.Rules(false, 1d);
        String[] readings = new String[2];
        for (int arm = 0; arm < 2; arm++) {
            SatelliteAccrual.Rules rules = arm == 0 ? wearOn() : off;
            String name = arm == 0 ? "wear on" : "wear off";
            List<SatelliteAccrual.Part> parts = dataSatellite(false, 0d);
            SatelliteAccrual.State collected = freshState(BIG_BATTERY, false);
            SatelliteAccrual.advance(body, parts, collector(), collected, rules, 11L, 2L * DAY_TICKS);
            SatelliteAccrual.State read = freshState(BIG_BATTERY, true);
            SatelliteAccrual.advance(body, dataSatellite(false, 0d), dosimeter(), read, rules, 11L, DAY_TICKS);
            readings[arm] = readingsOf(read.readings());
            if (arm == 0) {
                assertTrue("INV-SAT-29: wear on — the parts took no dose", slot(parts, 0).doseGy() > 0d && slot(parts, 1).doseGy() > 0d);
                assertTrue("INV-SAT-29: wear on — no point was lost", lostPoints(collected.journal()) > 0);
            } else {
                for (SatelliteAccrual.Part part : parts) {
                    assertEquals("INV-SAT-29: " + name + " — slot " + part.slot() + " took dose", 0d, part.doseGy(), 0d);
                }
                assertTrue("INV-SAT-29: " + name + " — something was journalled", collected.journal().entries().isEmpty());
                assertEquals("INV-SAT-29: " + name + " — the panel does not give its full power",
                        PANEL_POWER, slot(parts, 1).powerPerTick(rules), 0d);
            }
        }
        assertTrue("arrangement: the dosimeter read something", !readings[0].isEmpty());
        assertEquals("INV-SAT-29: wear off — the dosimeter read differently", readings[0], readings[1]);
    }

    /**
     * INV-SAT-31: the shield part's mass, read through a weight engine this test owns (seeded with no
     * tables, so nothing a pack wrote stands in front of the component's own default) for an item this
     * test owns under the shield's registry name, is 2.465 g/cm² × 10 kg/m² per g/cm² × 6 m²; and it is
     * a mass, so the part adds weight to whatever it is built into.
     */
    @Pins("INV-SAT-31")
    @RedWitness(member = "WeightEngine#componentOrMaterialWeight", at = "if (key.equals(\"stellurgy:satelliteshield\")) {",
            replacedBy = "if (false) {", fails = "INV-SAT-31: the shield part does not weigh its aluminium", on = "2026-10-21")
    @Test
    public void aShieldPartWeighsItsAluminium() {
        WeightEngine engine = WeightEngine.fromJson("{\"formatVersion\":" + WeightEngine.FORMAT_VERSION + "}");
        net.minecraft.item.Item shield = new net.minecraft.item.Item().setRegistryName("stellurgy", "satelliteShield");
        double expected = (3.00d - 0.535d) * 10d * 6d;
        assertEquals("INV-SAT-31: the shield part does not weigh its aluminium", expected,
                engine.getWeight(new net.minecraft.item.ItemStack(shield)), 0.01d);
    }

    /**
     * INV-SAT-30: the panel curve against the datasheet's three points (0.91, 0.87, 0.73 at 5e14, 1e15 and
     * 5e15 e/cm²), each fluence carried onto our dose by the clause's design point — 1e15 e/cm² is fifteen
     * years of a geostationary 0.2 Gy/day — within the clause's 0.02; and electronics against 5 krad(Si).
     */
    @Pins("INV-SAT-30")
    @RedWitness(verdict = "panel", member = "RadiationWear#PANEL_DECADE_LOSS", at = "0.2d", replacedBy = "0.3d",
            fails = "INV-SAT-30: the panel at 5.0E14", on = "2026-10-21")
    @RedWitness(verdict = "electronics", member = "RadiationWear#electronicsWork", at = "return doseGy < ELECTRONICS_BUDGET_GY;",
            replacedBy = "return doseGy <= ELECTRONICS_BUDGET_GY;", fails = "INV-SAT-30: electronics work at 5 krad", on = "2026-10-21")
    @Test
    public void theBudgetsAreTheMeasuredOnes() {
        double designPointGy = 15d * 365.25d * 0.2d;
        double[][] datasheet = {{5e14d, 0.91d}, {1e15d, 0.87d}, {5e15d, 0.73d}};
        for (double[] point : datasheet) {
            double dose = designPointGy * point[0] / 1e15d;
            assertEquals("INV-SAT-30: the panel at " + point[0] + " e/cm² (" + dose + " Gy)", point[1],
                    RadiationWear.panelPowerFraction(dose), 0.02d);
        }
        double fiveKrad = 5_000d / 100d;
        assertTrue("INV-SAT-30: electronics stop below 5 krad", RadiationWear.electronicsWork(Math.nextDown(fiveKrad)));
        assertFalse("INV-SAT-30: electronics work at 5 krad", RadiationWear.electronicsWork(fiveKrad));
    }

    // ---- the downlink: INV-SAT-35..37 ---------------------------------------------------------------------
    //
    // The terminal's place and the line of sight are pure geometry, entered here with a body the test states;
    // the readout is fed journals and readings read from the NBT production writes them as. What these laws
    // do NOT see: where the terminal takes its body, its rotation and the satellite's orbit from, and the
    // terminal's tries — the wiring, held in the harness (SatelliteTerminalChipRecognitionTest).

    /** A body's radius for the downlink's geometry, m — an input of the experiment. */
    private static final double DOWNLINK_BODY_RADIUS_M = 1.0e6d;
    /** The geometry's tolerance, m: far below any distance the laws tell apart, far above double rounding of a megametre. */
    private static final double PLACE_TOLERANCE_M = 1e-3d;

    /**
     * INV-SAT-35: the origin on the equator under meridian 0; a quarter of the circumference east, a quarter
     * turn round; an eighth north, 45° up the meridian; past the pole by 0.3 rad, 0.3 rad down the far
     * side; and a quarter of the rotational period later, the origin a quarter turn round. Each expected
     * point is the test's own spherical arithmetic on a body of radius {@link #DOWNLINK_BODY_RADIUS_M} turning once
     * in 400 ticks.
     */
    @Pins("INV-SAT-35")
    @Regions(of = "INV-SAT-35", value = {"origin", "east", "north", "over the pole", "turning"})
    @RedWitness(verdict = "origin", member = "Downlink#surfacePoint", at = "double cosNorth = Math.cos(north);",
            replacedBy = "double cosNorth = Math.sin(north);", fails = "INV-SAT-35: origin", on = "2026-10-21")
    @RedWitness(verdict = "east", member = "Downlink#surfacePoint", at = "rotation.angleAt(tick) + x / radiusM",
            replacedBy = "rotation.angleAt(tick) - x / radiusM", fails = "INV-SAT-35: east", on = "2026-10-21")
    @RedWitness(verdict = "north", member = "Downlink#surfacePoint", at = "double north = -z / radiusM;",
            replacedBy = "double north = z / radiusM;", fails = "INV-SAT-35: north", on = "2026-10-21")
    @RedWitness(verdict = "over the pole", member = "Downlink#surfacePoint", at = "double north = -z / radiusM;",
            replacedBy = "double north = Math.max(-Math.PI / 2d, Math.min(Math.PI / 2d, -z / radiusM));",
            fails = "INV-SAT-35: over the pole", on = "2026-10-21")
    @RedWitness(verdict = "turning", member = "Downlink#surfacePoint", at = "rotation.angleAt(tick) + x / radiusM",
            replacedBy = "x / radiusM", fails = "INV-SAT-35: turning", on = "2026-10-21")
    @Test
    public void aTerminalStandsWhereItsBlockFallsOnItsTurningBody() {
        BodyRotation day = BodyRotation.of(400L, 0d);
        double r = DOWNLINK_BODY_RADIUS_M;
        assertPlace("origin", new Vector3d(r, 0d, 0d), Downlink.surfacePoint(0d, 0d, r, day, 0L));
        assertPlace("east", new Vector3d(0d, r, 0d), Downlink.surfacePoint(r * Math.PI / 2d, 0d, r, day, 0L));
        double half = Math.sqrt(0.5d) * r;
        assertPlace("north", new Vector3d(half, 0d, half), Downlink.surfacePoint(0d, -r * Math.PI / 4d, r, day, 0L));
        assertPlace("over the pole", new Vector3d(-r * Math.sin(0.3d), 0d, r * Math.cos(0.3d)),
                Downlink.surfacePoint(0d, -r * (Math.PI / 2d + 0.3d), r, day, 0L));
        assertPlace("turning", new Vector3d(0d, r, 0d), Downlink.surfacePoint(0d, 0d, r, day, 100L));
    }

    private static void assertPlace(String region, Vector3dc expected, Vector3dc actual) {
        assertTrue("INV-SAT-35: " + region + " — the terminal stands at " + actual + ", not " + expected,
                new Vector3d(expected).sub(actual).length() < PLACE_TOLERANCE_M);
    }

    /**
     * INV-SAT-36: a terminal on the equator of a body of radius R at the origin, and satellites placed
     * against it by the test: three R out overhead; on its horizon; a little below it; eight R out with a
     * body of radius R standing at five R between them; and three R off that body's side, where the line
     * passes it at 2.4 R.
     */
    @Pins("INV-SAT-36")
    @Regions(of = "INV-SAT-36", value = {"overhead", "on the horizon", "below the horizon", "behind a body", "beside a body"})
    @RedWitness(verdict = "overhead", member = "Downlink#sees",
            at = "return hidingRadiusM <= 0d || !passesThrough(terminal, line, hidingCentre, hidingRadiusM);",
            replacedBy = "return false;", fails = "INV-SAT-36: overhead", on = "2026-10-21")
    @RedWitness(verdict = "on the horizon", member = "Downlink#sees", at = "line.dot(terminal) < 0d",
            replacedBy = "line.dot(terminal) <= 0d", fails = "INV-SAT-36: on the horizon", on = "2026-10-21")
    @RedWitness(verdict = "below the horizon", member = "Downlink#sees", at = "line.dot(terminal) < 0d",
            replacedBy = "false", fails = "INV-SAT-36: below the horizon", on = "2026-10-21")
    @RedWitness(verdict = "behind a body", member = "Downlink#sees",
            at = "return hidingRadiusM <= 0d || !passesThrough(terminal, line, hidingCentre, hidingRadiusM);",
            replacedBy = "return true;", fails = "INV-SAT-36: behind a body", on = "2026-10-21")
    @RedWitness(verdict = "beside a body", member = "Downlink#passesThrough",
            at = "return nearest.lengthSquared() < radiusM * radiusM;",
            replacedBy = "return nearest.lengthSquared() < 9d * radiusM * radiusM;", fails = "INV-SAT-36: beside a body",
            on = "2026-10-21")
    @Test
    public void theDishSeesTheTerminalOnlyAboveItsHorizonAndPastEveryBody() {
        double r = DOWNLINK_BODY_RADIUS_M;
        Vector3d terminal = new Vector3d(r, 0d, 0d);
        Vector3d none = new Vector3d();
        assertTrue("INV-SAT-36: overhead — a satellite straight above is not seen",
                Downlink.sees(terminal, new Vector3d(3d * r, 0d, 0d), none, 0d));
        assertTrue("INV-SAT-36: on the horizon — a satellite on the horizon is not seen",
                Downlink.sees(terminal, new Vector3d(r, 2d * r, 0d), none, 0d));
        assertFalse("INV-SAT-36: below the horizon — a satellite below the horizon is seen",
                Downlink.sees(terminal, new Vector3d(0.5d * r, 2d * r, 0d), none, 0d));
        Vector3d between = new Vector3d(5d * r, 0d, 0d);
        assertFalse("INV-SAT-36: behind a body — a satellite behind a body is seen",
                Downlink.sees(terminal, new Vector3d(8d * r, 0d, 0d), between, r));
        assertTrue("INV-SAT-36: beside a body — a satellite whose line passes a body at 2.4 R is not seen",
                Downlink.sees(terminal, new Vector3d(5d * r, 3d * r, 0d), between, r));
    }

    /**
     * INV-SAT-37: one terminal's readout over five contacts — the first with one loss and two readings; the
     * same again; then a part failure and an overflow added; then a journal that dropped its oldest seven
     * while the terminal had read three; then another satellite. Every expected row and count is read off
     * the journals the test wrote.
     */
    @Pins("INV-SAT-37")
    @Regions(of = "INV-SAT-37", value = {"first contact", "nothing new", "new failures", "journal overran", "another satellite"})
    @RedWitness(verdict = "first contact", member = "TerminalReadout#take",
            at = "fresh.add(new Row(e.tick, e.phaseRad, kindOf(e.kind), e.count, e.slot, Double.NaN));", replacedBy = "",
            fails = "INV-SAT-37: first contact", on = "2026-10-21")
    @RedWitness(verdict = "nothing new", member = "TerminalReadout#take", at = "journalNext = journal.recorded();",
            replacedBy = "", fails = "INV-SAT-37: nothing new", on = "2026-10-21")
    @RedWitness(verdict = "new failures", member = "TerminalReadout#take", at = "parts++;", replacedBy = "",
            fails = "INV-SAT-37: new failures", on = "2026-10-21")
    @RedWitness(verdict = "journal overran", member = "TerminalReadout#take",
            at = "long missed = Math.max(0L, journal.dropped() - journalNext);", replacedBy = "long missed = 0L;",
            fails = "INV-SAT-37: journal overran", on = "2026-10-21")
    @RedWitness(verdict = "another satellite", member = "TerminalReadout#take", at = "rows.clear();", replacedBy = "",
            fails = "INV-SAT-37: another satellite", on = "2026-10-21")
    @Test
    public void eachContactBringsWhatWasRecordedSinceTheOneBefore() {
        TerminalReadout readout = new TerminalReadout();
        long[][] lossAt10 = {{10L, FailureJournal.Kind.DATA_LOST.ordinal(), 3, -1}};
        DoseReadings readings = readingsOf(new long[]{15L, 25L}, new double[]{1e-5d, Double.NaN});

        TerminalReadout.Contact first = readout.take(7L, journalOf(0L, lossAt10), readings, 5, 0, 0, 0);
        assertEquals("INV-SAT-37: first contact — the rows, by tick",
                "10 DATA_LOST 3 | 15 DOSE_RATE 1.0E-5 | 25 DOSE_RATE absent", rowsOf(readout));
        assertEquals("INV-SAT-37: first contact — received, lost aboard, parts, readings, missed",
                "5 3 0 2 0", countsOf(first));

        TerminalReadout.Contact again = readout.take(7L, journalOf(0L, lossAt10), readings, 0, 0, 0, 0);
        assertEquals("INV-SAT-37: nothing new — the rows", "10 DATA_LOST 3 | 15 DOSE_RATE 1.0E-5 | 25 DOSE_RATE absent",
                rowsOf(readout));
        assertEquals("INV-SAT-37: nothing new — the counts", "0 0 0 0 0", countsOf(again));
        assertFalse("INV-SAT-37: nothing new — a contact that brought nothing says it brought something",
                again.broughtAnything());

        long[][] withTwoMore = {lossAt10[0], {30L, FailureJournal.Kind.PART_FAILED.ordinal(), 1, 2},
                {40L, FailureJournal.Kind.DATA_OVERFLOWED.ordinal(), 4, -1}};
        TerminalReadout.Contact more = readout.take(7L, journalOf(0L, withTwoMore), readings, 0, 0, 0, 0);
        assertEquals("INV-SAT-37: new failures — received, lost aboard, parts, readings, missed",
                "0 4 1 0 0", countsOf(more));
        assertEquals("INV-SAT-37: new failures — the rows",
                "10 DATA_LOST 3 | 15 DOSE_RATE 1.0E-5 | 25 DOSE_RATE absent | 30 PART_FAILED slot 2 | 40 DATA_OVERFLOWED 4",
                rowsOf(readout));

        long[][] afterSevenDropped = {{90L, FailureJournal.Kind.DATA_LOST.ordinal(), 1, -1}};
        TerminalReadout.Contact overran = readout.take(7L, journalOf(7L, afterSevenDropped), readings, 0, 0, 0, 0);
        assertEquals("INV-SAT-37: journal overran — received, lost aboard, parts, readings, missed (entries 3..6 gone)",
                "0 1 0 0 4", countsOf(overran));

        long[][] otherPart = {{5L, FailureJournal.Kind.PART_FAILED.ordinal(), 1, 1}};
        readout.take(8L, journalOf(0L, otherPart), null, 0, 0, 0, 0);
        assertEquals("INV-SAT-37: another satellite — the rows", "5 PART_FAILED slot 1", rowsOf(readout));
    }

    /** A journal as production reads one back: {@code dropped} entries gone, then each {tick, kind, count, slot}. */
    private static FailureJournal journalOf(long dropped, long[][] entries) {
        NBTTagCompound nbt = new NBTTagCompound();
        net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
        for (long[] e : entries) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("tick", e[0]);
            tag.setDouble("phase", 0d);
            tag.setByte("kind", (byte) e[1]);
            tag.setInteger("count", (int) e[2]);
            tag.setByte("slot", (byte) e[3]);
            list.appendTag(tag);
        }
        nbt.setTag("entries", list);
        nbt.setLong("dropped", dropped);
        FailureJournal journal = new FailureJournal();
        journal.readFromNBT(nbt);
        return journal;
    }

    /** A dosimeter's readings as production reads them back; a NaN rate is "absent here". */
    private static DoseReadings readingsOf(long[] ticks, double[] rates) {
        NBTTagCompound nbt = new NBTTagCompound();
        net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < ticks.length; i++) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("tick", ticks[i]);
            tag.setDouble("phase", 0d);
            if (!Double.isNaN(rates[i])) {
                tag.setDouble("rate", rates[i]);
            }
            list.appendTag(tag);
        }
        nbt.setTag("readings", list);
        nbt.setLong("dropped", 0L);
        DoseReadings readings = new DoseReadings();
        readings.readFromNBT(nbt);
        return readings;
    }

    private static String rowsOf(TerminalReadout readout) {
        StringBuilder out = new StringBuilder();
        for (TerminalReadout.Row row : readout.rows()) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(row.tick).append(' ').append(row.kind.name()).append(' ');
            if (row.kind == TerminalReadout.Kind.DOSE_RATE) {
                out.append(row.gyPerS().isPresent() ? String.valueOf(row.gyPerS().getAsDouble()) : "absent");
            } else if (row.kind == TerminalReadout.Kind.PART_FAILED) {
                out.append("slot ").append(row.slot);
            } else {
                out.append(row.count);
            }
        }
        return out.toString();
    }

    /**
     * INV-SAT-40: with N rows to a page, page p holds the rows from the newest minus pN back N rows, newest
     * first; the pages hold every row once; their number is the rows over N rounded up, at least one; a page
     * past the last is empty. Swept over table sizes on every side of a page boundary — empty, one row, one
     * short of a page, a page, one over, two pages and one over, the capacity — and page sizes from one row to
     * more than the capacity. Each row's tick is its place in the table, so the expected page is the test's own
     * arithmetic on that place.
     *
     * <p>The rows are put in through the terminal's own reader ({@code TerminalReadout#readFromNBT}); a
     * contact's ordering of its rows by time is INV-SAT-37's, not this law's.</p>
     */
    @Pins("INV-SAT-40")
    @RedWitness(verdict = "newest first", member = "TerminalReadout#page", at = "out.add(held.get(i));",
            replacedBy = "out.add(0, held.get(i));", fails = "INV-SAT-40: page", on = "2026-10-23")
    @RedWitness(verdict = "page size", member = "TerminalReadout#page",
            at = "i > newest - rowsPerPage && i >= 0", replacedBy = "i > newest - rowsPerPage - 1 && i >= 0",
            fails = "INV-SAT-40: page", on = "2026-10-23")
    @RedWitness(verdict = "page count", member = "TerminalReadout#pages",
            at = "return Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);",
            replacedBy = "return Math.max(1, rows.size() / rowsPerPage);", fails = "INV-SAT-40: pages", on = "2026-10-23")
    @Test
    public void theReadoutsPagesHoldEveryRowOnceNewestFirst() {
        int cap = TerminalReadout.CAPACITY;
        int[] perPages = {1, 3, 10, cap, cap + 44};
        int empty = 0, partial = 0, exact = 0, multi = 0;
        for (int perPage : perPages) {
            int[] sizes = {0, 1, perPage - 1, perPage, perPage + 1, 2 * perPage + 1, cap};
            for (int n : sizes) {
                if (n < 0 || n > cap) {
                    continue;
                }
                TerminalReadout readout = readoutOf(n);
                int expectedPages = n == 0 ? 1 : (n + perPage - 1) / perPage;
                assertEquals("INV-SAT-40: pages — " + n + " rows, " + perPage + " to a page", expectedPages,
                        readout.pages(perPage));
                for (int p = 0; p <= expectedPages; p++) {
                    List<Long> expected = new ArrayList<>();
                    for (int place = n - 1 - p * perPage; place > n - 1 - (p + 1) * perPage && place >= 0; place--) {
                        expected.add((long) place);
                    }
                    List<Long> got = new ArrayList<>();
                    for (TerminalReadout.Row row : readout.page(p, perPage)) {
                        got.add(row.tick);
                    }
                    assertEquals("INV-SAT-40: page " + p + " — " + n + " rows, " + perPage + " to a page", expected, got);
                }
                if (n == 0) {
                    empty++;
                } else if (n % perPage == 0) {
                    exact++;
                } else {
                    partial++;
                }
                if (expectedPages > 1) {
                    multi++;
                }
            }
        }
        // Each region the law turns on was visited, counted over the sweep: an empty table (one per page size),
        // a last page cut short, a last page exactly full, a table spread over several pages. The minimums are
        // what the sizes above produce when every case is reached.
        assertTrue("the sweep must visit the empty table: " + empty, empty >= perPages.length);
        assertTrue("the sweep must visit a last page cut short: " + partial, partial >= perPages.length);
        assertTrue("the sweep must visit a last page exactly full: " + exact, exact >= perPages.length - 1);
        assertTrue("the sweep must visit tables of several pages: " + multi, multi >= perPages.length - 1);
    }

    /** A readout holding {@code n} rows whose ticks are their places in the table, oldest first. */
    private static TerminalReadout readoutOf(int n) {
        net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
        for (int place = 0; place < n; place++) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("tick", place);
            tag.setDouble("phase", 0d);
            tag.setByte("kind", (byte) TerminalReadout.Kind.DOSE_RATE.ordinal());
            tag.setInteger("count", 0);
            tag.setByte("slot", (byte) -1);
            list.appendTag(tag);
        }
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag("rows", list);
        TerminalReadout readout = new TerminalReadout();
        readout.readFromNBT(nbt);
        return readout;
    }

    private static String countsOf(TerminalReadout.Contact c) {
        return c.received + " " + c.lostAboard + " " + c.partsFailed + " " + c.readings + " " + c.missed;
    }
}
