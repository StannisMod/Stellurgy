package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.api.satellite.InstrumentSubject;
import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.satellite.SatelliteAttitude;
import dev.stannismod.stellurgy.satellite.SatelliteComposition;
import dev.stannismod.stellurgy.satellite.SatelliteMassScanner;
import dev.stannismod.stellurgy.satellite.SatelliteOptical;
import dev.stannismod.stellurgy.satellite.SatellitePlacement;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The laws of a satellite on its orbit (`satellite`): INV-SAT-11..15.
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
}
