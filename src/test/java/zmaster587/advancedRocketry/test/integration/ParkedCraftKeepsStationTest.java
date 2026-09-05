package zmaster587.advancedRocketry.test.integration;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.DescentShell;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemContent;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What a craft that stops thrusting beside a body does over the following hours — the question
 * C19 FRAME-5 is about, asked of the model rather than of a prose summary of it.
 *
 * <p><b>A "parked" craft, stated exactly.</b> A ship in the space layer is a cell NAME plus an
 * offset inside that cell, and the offset is re-derived every tick from the ship's pose in the
 * slot world it is flying in. A ship that has stopped thrusting holds that pose, so it holds its
 * in-cell offset: parking is a CONSTANT {@code GalacticCoord}, and that is what these tests hold.
 * Where it actually IS then follows the cell's own frame, which is what the two legs below
 * separate.</p>
 *
 * <p><b>Why an integration test and not an e2e.</b> The subject is arithmetic on the ephemerides —
 * no world, no client, no tick loop — and running it through a real server would measure the
 * harness's ability to hold a ship still for twenty thousand ticks rather than the model's answer.
 * The e2e that flies a real craft is worth writing for the trajectory; it is not the instrument for
 * this question.</p>
 */
public class ParkedCraftKeepsStationTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * How long a craft is left alone before being asked where it ended up.
     *
     * <p>The ephemerides are stated in the day the rest of this codebase counts orbits in —
     * {@code 24 000} ticks — so 20 000 ticks is <b>0.83 of a day</b>, not the 1 000 seconds a
     * 20-ticks-a-second reading would give. That is a single overnight absence, and it is the
     * shortest window in which the numbers below are already unambiguous: Luna covers 3 % of its
     * orbit in it.</p>
     */
    private static final long ABANDONED_TICKS = 20_000L;

    /**
     * A craft parked beside a PLANET is still beside it, because the cell it is parked in rides
     * that planet.
     *
     * <p>This test fails if production breaks the contract that <b>a craft that commands nothing
     * keeps station with the body whose neighbourhood it is in</b> (C19 FRAME-5). The mechanism is
     * not a carry and not a parked state: the ship's address names a cell, the cell's origin is its
     * primary's position at the tick, and a constant address therefore tracks the primary for
     * free.</p>
     */
    @Test
    public void aCraftParkedBesideAPlanetStaysBesideIt() {
        StellarBody star = solWithEarthAndLuna();
        SystemBody earth = bodyOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN), EARTH_DIM);
        assertNotNull("the fixture must produce the planet", earth);

        // ARRANGEMENT: the planet must actually move over the window, or "he stayed with it" is a
        // statement about a stationary universe and holds for the wrong reason.
        double planetTravel = earth.absoluteAt(0L).distanceTo(earth.absoluteAt(ABANDONED_TICKS));
        assertTrue("the planet must travel a long way while the craft is abandoned, or keeping "
                        + "station with it costs nothing (travelled " + planetTravel + " blocks)",
                planetTravel > 10_000d);

        GalacticCoord parked = parkedBeside(earth, 0L);
        double at0 = rangeFrom(earth, parked, 0L);
        double atEnd = rangeFrom(earth, parked, ABANDONED_TICKS);

        System.out.println("[parked-craft] planet: travel=" + planetTravel
                + " range@0=" + at0 + " range@" + ABANDONED_TICKS + "=" + atEnd
                + " shell=" + DescentShell.radiusAround(earth));

        // The bound is on the CHANGE in range, not on the range itself. A craft keeping station has
        // the same range it started with, whatever that range was; a bound of "still inside the
        // shell" would pass a craft that had drifted a shell's width inward and fail one parked
        // exactly on the shell — which is how this leg first went red, against a drift of zero.
        assertTrue("a craft parked beside a planet must still be exactly as far from it after "
                        + ABANDONED_TICKS + " ticks: it was " + at0 + " blocks out and is now "
                        + atEnd + " (drift " + Math.abs(atEnd - at0) + "), while the planet itself "
                        + "moved " + planetTravel,
                Math.abs(atEnd - at0) < 1d);
    }

    /**
     * <b>THE ACCEPTANCE: a craft parked one DESCENT SHELL out from a moon is exactly as far from it
     * a day later.</b>
     *
     * <p>The number to beat was measured before any of this: <b>7 066 blocks became 294 996</b> over
     * this same window — 42 descent shells, in 0.83 of a day — while the identical craft beside a
     * PLANET drifted zero. A moon shared its parent's cell, so it could not be that cell's primary;
     * its neighbourhood was no frame and nothing carried what was parked in it. The craft never
     * moved. Its cell was riding Earth while the moon went round.</p>
     *
     * <p>What closed it is not a carry and not a velocity: the moon has a CELL OF ITS OWN inside its
     * parent's zone, and that cell rides the moon. Station-keeping costs nothing at all, which is
     * why the substrate's speed ceiling never enters — a craft co-moving with Luna would have had to
     * hold 294.7 blocks/s against a freeze at 223.6, and that arithmetic is what ruled out carrying
     * the craft instead of its cell.</p>
     *
     * <p><b>It took two goes, and the second one is worth recording.</b> Giving the moon a cell was
     * not enough: the zone lattice was 1024 cells across, so Earth's cell came out 7 224 blocks
     * against Luna's own 7 066-block shell and the craft fell into the NEXT cell — carried by Earth
     * again, one level down, by the fix itself. This test spent a commit asserting that gap and
     * saying a coarser lattice was not the answer. It was: the count is now derived per body from
     * the room its own shell needs, Earth's cell is 57 791 blocks, and the craft is inside the
     * moon's cell with room to spare.</p>
     */
    @Test
    public void aCraftParkedOneDescentShellOutFromAMoonKeepsStationWithIt() {
        StellarBody star = solWithEarthAndLuna();
        List<SystemBody> bodies = SystemContent.bodiesOf(star, GalacticCoord.ORIGIN);
        SystemBody luna = bodyOf(bodies, LUNA_DIM);
        assertNotNull("the fixture must produce the moon", luna);

        // ARRANGEMENT: the moon must actually TRAVEL over the window, or a craft that stayed with it
        // proves nothing. The reading is absolute, because what the moon must not do any more is
        // move relative to its own cell — that is the thing being asserted, not the arrangement.
        double moonTravel = luna.absoluteAt(0L).minus(luna.absoluteAt(ABANDONED_TICKS)).length();
        assertTrue("the moon must move over the window, or keeping station with it is vacuous "
                        + "(travelled " + moonTravel + " blocks)", moonTravel > 100_000d);

        long shell = DescentShell.radiusAround(luna);
        GalacticCoord parked = parkedBeside(luna, 0L);
        // ARRANGEMENT, and it is the half that was missing for a whole commit: one descent shell out
        // has to be an address INSIDE the moon's own cell. It is a property of the lattice, not of
        // the flight, so a craft can never reach it by flying and the test would be measuring the
        // wrong body's frame without ever saying so.
        assertTrue("a craft one descent shell (" + shell + ") out from the moon must be inside the "
                        + "moon's OWN cell (" + luna.name() + "), or its address rides the PARENT "
                        + "however well the rest of the machinery works — got " + parked,
                parked.sameCell(luna.name()));

        double at0 = rangeFrom(luna, parked, 0L);
        double atEnd = rangeFrom(luna, parked, ABANDONED_TICKS);

        System.out.println("[parked-craft] moon: travel=" + moonTravel + " shell=" + shell
                + " cell=" + luna.name().cellBlocks()
                + " range@0=" + at0 + " range@" + ABANDONED_TICKS + "=" + atEnd);

        // The same bound as the planet leg, and deliberately the same one: a craft keeping station
        // has the range it started with, whatever that range was. "Still inside the shell" would
        // pass a craft that had drifted a whole shell inward and fail one parked exactly on it.
        assertTrue("a craft parked one descent shell out from a moon must still be exactly as far "
                        + "from it after " + ABANDONED_TICKS + " ticks: it was " + at0
                        + " blocks out and is now " + atEnd + " (drift " + Math.abs(atEnd - at0)
                        + "), against a shell of " + shell + ", while the moon itself travelled "
                        + moonTravel,
                Math.abs(atEnd - at0) < 1d);
    }

    // ---- fixture ------------------------------------------------------------------------------

    private static final int EARTH_DIM = 790;
    private static final int LUNA_DIM = 791;

    /**
     * Earth and Luna at their real bulk and separation, around a Sol-mass star.
     *
     * <p>The numbers are the real ones because the question is quantitative: how far a craft is left
     * behind is the moon's own orbital speed, and a fixture moon on an invented orbit would answer
     * about itself rather than about the system every player meets first.</p>
     */
    private static StellarBody solWithEarthAndLuna() {
        StellarBody star = new StellarBody();
        star.setId(4260);
        star.setName("Sol");
        star.setSize(1f);

        DimensionProperties earth = new DimensionProperties(EARTH_DIM);
        // ONE AU, stated as one. It read 100 while a distance unit was a hundredth of an AU; at
        // the 10 000 km that literal now means, Earth sits inside its own star, its sphere of
        // influence collapses and so does the zone cell derived from it (494 blocks, measured).
        earth.orbitalDist =
                zmaster587.advancedRocketry.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        earth.baseOrbitTheta = 0.0;
        earth.orbitTheta = 0.0;
        earth.orbitalPhi = 0;
        earth.setBulk(1d, 1d);

        DimensionProperties luna = new DimensionProperties(LUNA_DIM);
        // The real separation, in the moon-unit the layout measures a moon's orbit in.
        luna.orbitalDist = zmaster587.advancedRocketry.util.AstronomicalBodyHelper.MOON_REFERENCE_UNITS;
        luna.baseOrbitTheta = 0.0;
        luna.orbitTheta = 0.0;
        luna.orbitalPhi = 0;
        luna.setBulk(0.0123d, 0.2727d);

        DimensionManager.getInstance().setDimProperties(EARTH_DIM, earth);
        DimensionManager.getInstance().setDimProperties(LUNA_DIM, luna);
        earth.setStar(star);
        luna.setParentPlanet(earth);
        return star;
    }

    /** A craft holding station one descent shell out from {@code body} at {@code tick}, as an address. */
    private static GalacticCoord parkedBeside(SystemBody body, long tick) {
        AbsolutePos at = body.absoluteAt(tick);
        AbsolutePos origin = body.frame().originAt(tick);
        // The craft's address is its CELL plus an offset inside it; the offset it holds is wherever
        // it was when it stopped, expressed in that cell.
        return body.name().cellCentre().plusLocal(
                at.localX() - origin.localX() + DescentShell.radiusAround(body),
                at.localY() - origin.localY(),
                at.localZ() - origin.localZ());
    }

    /** How far {@code parked} is from {@code body} at {@code tick}, both resolved at that tick. */
    private static double rangeFrom(SystemBody body, GalacticCoord parked, long tick) {
        AbsolutePos craft = body.frame().originAt(tick)
                .plus(parked.localX(), parked.localY(), parked.localZ());
        return craft.distanceTo(body.absoluteAt(tick));
    }

    private static SystemBody bodyOf(List<SystemBody> bodies, int dimId) {
        for (SystemBody b : bodies) {
            if (b.dimId() == dimId) {
                return b;
            }
        }
        return null;
    }
}
