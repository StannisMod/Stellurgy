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
     * A craft parked beside a MOON is left behind by it, because a moon has no frame of its own —
     * it shares its parent's cell and moves INSIDE it.
     *
     * <p>This is the same contract clause as the leg above and the opposite answer, and the pair is
     * the point: today's "frame" is one level deep — the primary of the cell — while C19 FRAME-2
     * asks for the INNERMOST containing sphere of influence. A planet is a cell's primary and a moon
     * never is (`SystemBody.definesFrame`), so a moon's neighbourhood is not a frame and nothing
     * parked in it keeps station.</p>
     *
     * <p><b>This test asserts the defect, deliberately.</b> It is the documents-known-bug half of
     * the pin: when FRAME-2 is implemented this assertion FLIPS, and it is written so that the flip
     * has to be deliberate rather than a silently loosened bound.</p>
     */
    @Test
    public void aCraftParkedBesideAMoonIsLeftBehindByIt() {
        StellarBody star = solWithEarthAndLuna();
        List<SystemBody> bodies = SystemContent.bodiesOf(star, GalacticCoord.ORIGIN);
        SystemBody luna = bodyOf(bodies, LUNA_DIM);
        assertNotNull("the fixture must produce the moon", luna);

        // ARRANGEMENT: the moon must move INSIDE its cell, which is the motion nothing carries a
        // craft through. A moon standing still in-cell would make this leg vacuous.
        zmaster587.advancedRocketry.space.BlockDelta was = luna.inCellOffsetAt(0L);
        zmaster587.advancedRocketry.space.BlockDelta now = luna.inCellOffsetAt(ABANDONED_TICKS);
        double inCellTravel = Math.sqrt(
                Math.pow((double) was.dx() - now.dx(), 2)
                        + Math.pow((double) was.dy() - now.dy(), 2)
                        + Math.pow((double) was.dz() - now.dz(), 2));
        assertTrue("the moon must move inside its cell over the window, or there is nothing for a "
                        + "craft to be left behind by (moved " + inCellTravel + " blocks)",
                inCellTravel > 1_000d);

        GalacticCoord parked = parkedBeside(luna, 0L);
        double at0 = rangeFrom(luna, parked, 0L);
        double atEnd = rangeFrom(luna, parked, ABANDONED_TICKS);
        long shell = DescentShell.radiusAround(luna);

        System.out.println("[parked-craft] moon: inCellTravel=" + inCellTravel
                + " range@0=" + at0 + " range@" + ABANDONED_TICKS + "=" + atEnd
                + " shell=" + shell);

        assertTrue("KNOWN, C19 FRAME-2 unimplemented: a craft parked beside a moon is carried by "
                        + "the moon's PARENT and not by the moon, so it is left behind. It was "
                        + at0 + " blocks out and is now " + atEnd + " after " + ABANDONED_TICKS
                        + " ticks, against a descent shell of " + shell + ". When the innermost "
                        + "containing sphere of influence becomes the frame, this assertion is the "
                        + "one that must be turned round — deliberately, not by widening a bound.",
                atEnd > shell * 2d);
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
        earth.orbitalDist = 100;
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
