package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ReferenceFrames;
import zmaster587.advancedRocketry.universe.BodyEphemeris;
import zmaster587.advancedRocketry.universe.CellFrame;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Which body a craft's motion is measured against (C19 FRAME-2/FRAME-3), on real Sol numbers.
 *
 * <p>Pure arithmetic — no world, no dimension properties, no bootstrap. The bodies are built by hand
 * so the masses and separations under test are the real ones and not whatever a fixture generator
 * happened to produce.</p>
 */
public class ReferenceFramesTest {

    /** Earth masses in a solar mass, and Luna's and Earth's bulk — the real values. */
    private static final double SOL_MASS_EARTHS = AstronomicalBodyHelper.EARTH_MASSES_PER_SOLAR_MASS;
    private static final double EARTH_MASS_EARTHS = 1d;
    private static final double LUNA_MASS_EARTHS = 0.0123d;

    /** One AU and Luna's orbit, in chart blocks. */
    private static final double AU_BLOCKS =
            (double) AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT
                    * AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
    private static final double LUNA_ORBIT_BLOCKS =
            (double) AstronomicalBodyHelper.MOON_REFERENCE_UNITS * 200L;

    /** How far a metre-stated reference value is in chart blocks. */
    private static double kmInBlocks(double km) {
        return km * 1000d / AstronomicalBodyHelper.METRES_PER_CHART_BLOCK;
    }

    /**
     * The Laplace radius this code uses reproduces the published spheres of influence.
     *
     * <p>This test fails if production breaks the contract that <b>a body's sphere of influence is
     * computed from a mass RATIO and an orbital radius</b> (C19 FRAME-3). The reference values are
     * the ones C19 tabulates — Earth 926 000 km, Luna 66 000 km — and they are quoted from outside
     * this codebase, so a formula that drifted to read a radius or a surface gravity would miss them
     * however self-consistent it stayed.</p>
     */
    @Test
    public void aSpheresRadiusMatchesThePublishedValueForEarthAndForLuna() {
        SystemBody sol = sol();
        SystemBody earth = earth();
        SystemBody luna = luna();

        double earthSoi = ReferenceFrames.soiRadiusBlocks(earth, sol, 0L);
        double lunaSoi = ReferenceFrames.soiRadiusBlocks(luna, earth, 0L);

        System.out.println("[soi] earth=" + earthSoi + " blocks (" + earthSoi * 250d / 1000d
                + " km), luna=" + lunaSoi + " blocks (" + lunaSoi * 250d / 1000d + " km)");

        assertEquals("Earth's sphere of influence, against the published 926 000 km",
                kmInBlocks(926_000d), earthSoi, kmInBlocks(30_000d));
        assertEquals("Luna's sphere of influence, against the published 66 000 km",
                kmInBlocks(66_000d), lunaSoi, kmInBlocks(3_000d));
    }

    /**
     * Both candidate formulae satisfy the nesting criterion, which is why the choice between them
     * had to be made on other grounds — recorded here so the measurement is not repeated.
     *
     * <p><b>The criterion was stated before either was computed</b>: a formula is admissible if a
     * body's sphere contains all of its own moons and none of its siblings. Luna sits at 384 400 km;
     * Earth's sphere is 926 000 km under Laplace and 1 496 000 km under Hill, and the nearest
     * sibling planet is tens of millions of kilometres away in both cases. So both pass, the
     * criterion does not separate them, and this test's job is to say so rather than to pretend the
     * measurement decided it.</p>
     */
    @Test
    public void bothCandidateFormulaeNestCorrectlySoTheCriterionDoesNotSeparateThem() {
        SystemBody sol = sol();
        SystemBody earth = earth();

        double a = ReferenceFrames.orbitalRadiusBlocks(earth, sol, 0L);
        double laplace = a * Math.pow(EARTH_MASS_EARTHS / SOL_MASS_EARTHS, 0.4d);
        double hill = a * Math.cbrt(EARTH_MASS_EARTHS / (3d * SOL_MASS_EARTHS));

        System.out.println("[soi-choice] a=" + a + " laplace=" + laplace + " hill=" + hill
                + " lunaOrbit=" + LUNA_ORBIT_BLOCKS);

        assertTrue("Laplace must contain Luna's orbit, or it is inadmissible",
                laplace > LUNA_ORBIT_BLOCKS);
        assertTrue("Hill must contain Luna's orbit too — both are admissible, which is the finding",
                hill > LUNA_ORBIT_BLOCKS);
        assertTrue("and Hill must be the larger of the two, which is why Laplace is the "
                        + "conservative choice: a craft is handed to a small body's frame only well "
                        + "inside where that body dominates",
                hill > laplace);
    }

    /**
     * A craft close to a moon is in the MOON's frame, not its planet's — the nesting C19 FRAME-2
     * asks for and the defect measured in {@code ParkedCraftKeepsStationTest}.
     */
    @Test
    public void aCraftInsideAMoonsSphereIsInTheMoonsFrame() {
        SystemBody earth = earth();
        SystemBody luna = luna();
        List<SystemBody> cell = Arrays.asList(earth, luna);

        // A craft one Luna-radius out from Luna, along the axis it is displaced on.
        AbsolutePos nearLuna = luna.absoluteAt(0L).plus(20_000L, 0L, 0L);
        SystemBody frame = ReferenceFrames.frameOf(nearLuna, cell, earth, 0L);

        assertNotNull(frame);
        assertSame("a craft 20 000 blocks from Luna is inside Luna's 264 000-block sphere and must "
                + "be in its frame, not in Earth's", luna, frame);
    }

    /** A craft far from every moon falls back to the planet, which is the right answer and not a fallback. */
    @Test
    public void aCraftOutsideEveryMoonsSphereIsInThePlanetsFrame() {
        SystemBody earth = earth();
        SystemBody luna = luna();
        List<SystemBody> cell = Arrays.asList(earth, luna);

        AbsolutePos nearEarth = earth.absoluteAt(0L).plus(30_000L, 0L, 0L);
        assertSame("a craft beside Earth and far from Luna is in Earth's frame",
                earth, ReferenceFrames.frameOf(nearEarth, cell, earth, 0L));
    }

    /**
     * NEITHER a planet NOR a moon moves inside its own cell — and for a moon that is the fix, not a
     * loss of information.
     *
     * <p>The two used to be the halves of the measured defect: zero for a planet, the moon's orbital
     * speed for a moon, and that speed was the number a craft had to MATCH to hold station beside
     * it. It could not match it — the game's Luna moves 14.75 blocks per tick and the physics
     * substrate freezes a ship above 11.18 — so the answer was to move the CELL instead, which is
     * what a moon owning its own cell does. A body that sits at its own frame's origin has nothing
     * to match, and the speed a craft would have needed is nobody's problem.</p>
     *
     * <p>What still moves is read off the FRAME, and this pins that too: the moon's cell goes round
     * its planet at the moon's own orbital speed — the same number, now describing the cell rather
     * than the body inside it.</p>
     */
    @Test
    public void neitherAPlanetNorAMoonMovesInsideItsOwnCellWhileTheMoonsCellCarriesTheOrbit() {
        SystemBody earth = earth();
        SystemBody luna = luna();
        long quarterPeriod = (long) (LUNA_PERIOD_TICKS / 4d);

        assertTrue("a planet does not move inside its own cell",
                earth.inCellOffsetAt(0L).isZero() && earth.inCellOffsetAt(quarterPeriod).isZero());
        assertTrue("and neither does a moon, because its cell rides it",
                luna.inCellOffsetAt(0L).isZero() && luna.inCellOffsetAt(quarterPeriod).isZero());

        double[] cellV = luna.frame().law().velocityBlocksPerTickAt(0L);
        double moonSpeed = Math.sqrt(cellV[0] * cellV[0] + cellV[1] * cellV[1] + cellV[2] * cellV[2]);

        System.out.println("[frame-velocity] moon cell=" + Arrays.toString(cellV)
                + " speed=" + moonSpeed);

        // The expected speed is the orbit's own: circumference over period, both of which the
        // ephemeris states. Computed rather than quoted, so the bar cannot drift away from the
        // fixture it is judging.
        //
        // The bar is a TENTH of a per cent, and it is tight on purpose: this rate used to be taken
        // by differencing two of the ephemeris's positions, which are rounded to whole blocks, and
        // that returned exactly 15.0 against a true 14.7344. A one-per-cent bar would have passed
        // that reading — the quantisation is only 1.8 % at Luna's speed — while the same half-block
        // step is the entire quantity for a body moving a fraction of a block per tick.
        double expected = 2d * Math.PI * LUNA_ORBIT_BLOCKS / LUNA_PERIOD_TICKS;
        assertEquals("a moon's CELL travels at the moon's orbital speed about its parent",
                expected, moonSpeed, expected * 0.001d);
    }

    // ---- fixture ------------------------------------------------------------------------------

    private static final GalacticCoord ANCHOR = GalacticCoord.ORIGIN;
    private static final double EARTH_PERIOD_TICKS = 365.25d * 24_000d;
    private static final double LUNA_PERIOD_TICKS =
            AstronomicalBodyHelper.DAYS_PER_LUNAR_MONTH * 24_000d;

    private static SystemBody sol() {
        return SystemBody.fixedAt(ANCHOR, SystemBodyKind.STAR, -1, 1)
                .withBulk(SOL_MASS_EARTHS, 109.17d);
    }

    /** Earth: one AU out, its cell riding its own orbit, standing still inside that cell. */
    private static SystemBody earth() {
        BodyEphemeris orbit = BodyEphemeris.orbit(
                AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU, 0d, 0d, false, EARTH_PERIOD_TICKS,
                AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT);
        return new SystemBody(ANCHOR, CellFrame.of(AbsolutePos.ofCellName(ANCHOR), orbit),
                BodyEphemeris.STATIC, SystemBodyKind.PLANET, 0, 1, 100, 1d, EARTH_MASS_EARTHS);
    }

    /**
     * Luna: a cell of its own, NESTED in Earth's frame, standing still inside that cell.
     *
     * <p>Built the way production builds one, and the shape is load-bearing rather than tidy. It
     * used to be Earth's cell, Earth's frame and a live offset inside it — the pre-zone shape — and
     * a fixture that kept it would go on answering questions about a moon the game no longer makes:
     * `frameOf` would still find the right sphere, so the legs would stay green while measuring a
     * body whose in-cell offset production has since fixed at zero.</p>
     *
     * <p>The NAME is Earth's cell for the same reason it is not the point here: this class tests
     * sphere-of-influence arithmetic, which reads `absoluteAt` and mass, and never a cell key. What
     * matters is that the two agree on where the moon IS — Earth's position plus the moon's orbit —
     * and the nested frame is what makes that true with a zero offset.</p>
     */
    private static SystemBody luna() {
        BodyEphemeris earthOrbit = BodyEphemeris.orbit(
                AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU, 0d, 0d, false, EARTH_PERIOD_TICKS,
                AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT);
        BodyEphemeris moonOrbit = BodyEphemeris.orbit(
                AstronomicalBodyHelper.MOON_REFERENCE_UNITS, 0d, 0d, false, LUNA_PERIOD_TICKS, 200L);
        CellFrame earthFrame = CellFrame.of(AbsolutePos.ofCellName(ANCHOR), earthOrbit);
        return new SystemBody(ANCHOR, CellFrame.within(earthFrame, moonOrbit), BodyEphemeris.STATIC,
                SystemBodyKind.MOON, 1, 1, 100, 0.2727d, LUNA_MASS_EARTHS);
    }
}
