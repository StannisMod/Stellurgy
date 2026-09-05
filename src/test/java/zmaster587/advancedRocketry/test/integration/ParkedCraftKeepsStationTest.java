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
     * A craft addressed INSIDE a moon's own cell keeps station with the moon — the same contract as
     * the planet leg, one level down, and the half of it that has landed.
     *
     * <p>It did not hold at all before: while a moon shared its parent's cell it could not be that
     * cell's primary, so a moon's neighbourhood was no frame and nothing carried what was parked in
     * it. Measured over this same window, a craft one descent shell out from Luna ended
     * <b>294 996</b> blocks away against a shell of 7 066 — 42 shells, in 0.83 of a day. The craft
     * never moved; its cell was riding Earth while the moon went round.</p>
     *
     * <p>What changed is not a carry and not a velocity: the moon has a CELL OF ITS OWN inside its
     * parent's zone, and that cell rides the moon. Station-keeping therefore costs nothing at all,
     * which is why the substrate's speed ceiling never enters — a craft co-moving with Luna would
     * have had to hold 294.7 blocks/s against a freeze at 223.6, and that arithmetic is what ruled
     * out carrying the craft instead of its cell.</p>
     */
    @Test
    public void aCraftInsideAMoonsOwnCellKeepsStationWithIt() {
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

        // A quarter of the cell out, which is a distance the moon's own cell can hold. One descent
        // shell out cannot be used here and the leg below is why.
        long standOff = luna.name().cellBlocks() / 4L;
        GalacticCoord parked = luna.name().cellCentre().plusLocal(standOff, 0L, 0L);
        assertTrue("arrangement: the craft is inside the moon's own cell", parked.sameCell(luna.name()));

        double at0 = rangeFrom(luna, parked, 0L);
        double atEnd = rangeFrom(luna, parked, ABANDONED_TICKS);

        System.out.println("[parked-craft] moon: travel=" + moonTravel + " standOff=" + standOff
                + " range@0=" + at0 + " range@" + ABANDONED_TICKS + "=" + atEnd
                + " cell=" + luna.name().cellBlocks()
                + " shell=" + DescentShell.radiusAround(luna));

        // The same bound as the planet leg, and deliberately the same one: a craft keeping station
        // has the range it started with, whatever that range was. "Still inside the shell" would
        // pass a craft that had drifted a whole shell inward.
        assertTrue("a craft inside a moon's cell must still be exactly as far from it after "
                        + ABANDONED_TICKS + " ticks: it was " + at0 + " blocks out and is now "
                        + atEnd + " (drift " + Math.abs(atEnd - at0) + "), while the moon itself "
                        + "travelled " + moonTravel,
                Math.abs(atEnd - at0) < 1d);
    }

    /**
     * A craft parked one DESCENT SHELL out from a moon is <b>not</b> addressed inside that moon's
     * cell — so the acceptance for this mechanic, stated in shells, is not met yet.
     *
     * <p><b>This asserts the remaining gap, deliberately</b>, the way the moon leg above used to
     * assert the whole defect. Measured on the real Earth/Luna fixture: a cell of Earth's zone is
     * <b>7 224</b> blocks across — one 1024th of Earth's sphere of influence, which is the lattice
     * that lets a moon be named apart from its planet at all — while Luna's own descent shell is
     * <b>7 066</b>. The shell is nearly two cells wide, so a craft at the shell sits in a
     * NEIGHBOURING cell of Earth's zone, and that cell rides Earth.</p>
     *
     * <p>The design does not answer this with a coarser lattice, which would put Saturn's inner moons
     * back into one address: it answers that the craft is inside LUNA's sphere of influence (264 000
     * blocks, 37 shells) and must therefore be addressed in LUNA's OWN zone, whose cells are ~516
     * blocks. That is the craft-addressing half of the task — membership by sphere rather than by
     * lattice cube — and until it lands, "one descent shell out" is an address in the parent's zone.</p>
     *
     * <p>When it lands, this assertion is the one that must be turned round: a craft at the shell is
     * inside the moon's zone and keeps station. Written so the flip has to be deliberate rather than
     * a quietly widened bound.</p>
     */
    @Test
    public void aCraftOneDescentShellOutFromAMoonIsNotYetAddressedInsideItsZone() {
        StellarBody star = solWithEarthAndLuna();
        List<SystemBody> bodies = SystemContent.bodiesOf(star, GalacticCoord.ORIGIN);
        SystemBody luna = bodyOf(bodies, LUNA_DIM);
        assertNotNull("the fixture must produce the moon", luna);

        long shell = DescentShell.radiusAround(luna);
        long cell = luna.name().cellBlocks();
        GalacticCoord parked = parkedBeside(luna, 0L);

        System.out.println("[parked-craft] moon shell vs cell: shell=" + shell + " cell=" + cell
                + " moon=" + luna.name() + " parked=" + parked);

        assertTrue("KNOWN, the craft-addressing half of this mechanic is unimplemented: a moon's descent "
                        + "shell (" + shell + " blocks) is wider than half a cell of its parent's "
                        + "zone (" + cell + " blocks across), so a craft parked at the shell falls "
                        + "into a neighbouring cell — " + parked + " against the moon's "
                        + luna.name() + " — which rides the PARENT. Membership by sphere of "
                        + "influence is what closes it; a coarser lattice is not.",
                !parked.sameCell(luna.name()));
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
