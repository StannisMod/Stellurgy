package dev.stannismod.stellurgy.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.space.DescentShell;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;

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

    /**
     * How far the body must travel while the craft is abandoned, in blocks.
     *
     * <p>Both are the test's own sensitivity bars on the ARRANGEMENT: keeping station with a body
     * that did not move costs nothing, so a leg run against a stationary body proves nothing about
     * station-keeping.</p>
     *
     * <p><b>What they have to clear, measured 2026-09-30</b>: the drift verdict's tolerance is one
     * block, and over this window the planet travels 6.52E7 blocks about its star and the moon
     * about 2.9E5 about its planet (both printed by the legs). A craft carried by the wrong frame
     * drifts by that relative travel — the moon leg's pre-fix red read 287 930 — so any bar far
     * above one block and far below the measured travel discriminates; these sit two to four orders
     * of magnitude inside that span on each side. They are sensitivity bars, not tuned values.</p>
     */
    private static final double PLANET_TRAVELLED_BLOCKS = 10_000d;
    /** @see #PLANET_TRAVELLED_BLOCKS */
    private static final double MOON_TRAVELLED_BLOCKS = 100_000d;

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
     *
     * <p>red-witnessed: 2026-09-29, with `SystemBody.definesFrame:324` no longer counting a PLANET,
     * this fails with "…it was 25913.0 blocks out and is now 6.524345703726182E7 (drift
     * 6.521754403726182E7), while the planet itself moved 6.524203927239168E7" — the planet left and
     * the address stayed.</p>
     */
    @Test
    public void aCraftParkedBesideAPlanetStaysBesideIt() {
        UniverseRegistry reg = registryWithEarthAndLuna();
        SystemBody earth = bodyOf(reg.systemBodiesAt(GalacticCoord.ORIGIN), EARTH_DIM);
        assertNotNull("the fixture must produce the planet", earth);

        // ARRANGEMENT: the planet must actually move over the window, or "he stayed with it" is a
        // statement about a stationary universe and holds for the wrong reason.
        double planetTravel = earth.absoluteAt(0L).distanceTo(earth.absoluteAt(ABANDONED_TICKS));
        assertTrue("the planet must travel a long way while the craft is abandoned, or keeping "
                        + "station with it costs nothing (travelled " + planetTravel + " blocks)",
                planetTravel > PLANET_TRAVELLED_BLOCKS);

        GalacticCoord parked = parkedBeside(reg, earth, 0L);
        double at0 = rangeFrom(reg, earth, parked, 0L);
        double atEnd = rangeFrom(reg, earth, parked, ABANDONED_TICKS);

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
     * saying a coarser lattice was not the answer. It was: the count is now derived per body, and
     * Earth's zone cell measured 1 849 294 blocks on 2026-09-29 (this test prints it), so the craft
     * is inside the moon's cell with room to spare.</p>
     *
     * <p>red-witnessed: 2026-09-29, with `SystemBody.definesFrame:326` no longer counting a MOON — the
     * fix this acceptance is named for — this fails with "…it was 7066.0 blocks out and is now
     * 294995.9100682584 (drift 287929.9100682584), against a shell of 7066": the pre-fix number,
     * verbatim. <b>The version of this test before that date stayed GREEN on the same inversion</b>
     * (both methods PASSED, run the same day): it resolved the parked address through
     * {@code body.frame()}, a frame the test chose, instead of through {@code UniverseRegistry.originAt}
     * as production does. Re-run 2026-09-30 after the arrangement began measuring the moon's travel
     * RELATIVE to its planet: same verdict and drift, "while the moon itself travelled
     * 294235.96…" — the drift is that relative travel, less the start-to-end chord's curvature.</p>
     */
    @Test
    public void aCraftParkedOneDescentShellOutFromAMoonKeepsStationWithIt() {
        UniverseRegistry reg = registryWithEarthAndLuna();
        SystemBody luna = bodyOf(reg.systemBodiesAt(GalacticCoord.ORIGIN), LUNA_DIM);
        assertNotNull("the fixture must produce the moon", luna);

        SystemBody earth = bodyOf(reg.systemBodiesAt(GalacticCoord.ORIGIN), EARTH_DIM);
        assertNotNull("the fixture must produce the planet", earth);

        // ARRANGEMENT: the moon must travel ROUND ITS PLANET over the window — measured relative to
        // the planet, because the planet's frame is the one a mis-carried craft would ride. It was
        // the moon's ABSOLUTE travel, 6.55E7 blocks, of which 6.52E7 is Earth's own orbit: a moon
        // that never went round its planet would have passed that bar on Earth's motion alone, and a
        // craft riding Earth's frame beside it would then keep station for the wrong reason.
        BlockDelta at0FromEarth = luna.absoluteAt(0L).minus(earth.absoluteAt(0L));
        BlockDelta atEndFromEarth = luna.absoluteAt(ABANDONED_TICKS)
                .minus(earth.absoluteAt(ABANDONED_TICKS));
        double moonTravel = Math.sqrt(sq(atEndFromEarth.dx() - at0FromEarth.dx())
                + sq(atEndFromEarth.dy() - at0FromEarth.dy())
                + sq(atEndFromEarth.dz() - at0FromEarth.dz()));
        assertTrue("the moon must move round its planet over the window, or keeping station with it "
                        + "is vacuous (travelled " + moonTravel + " blocks relative to the planet)",
                moonTravel > MOON_TRAVELLED_BLOCKS);

        long shell = DescentShell.radiusAround(luna);
        GalacticCoord parked = parkedBeside(reg, luna, 0L);
        // ARRANGEMENT, and it is the half that was missing for a whole commit: one descent shell out
        // has to be an address INSIDE the moon's own cell. It is a property of the lattice, not of
        // the flight, so a craft can never reach it by flying and the test would be measuring the
        // wrong body's frame without ever saying so.
        assertTrue("a craft one descent shell (" + shell + ") out from the moon must be inside the "
                        + "moon's OWN cell (" + luna.name() + "), or its address rides the PARENT "
                        + "however well the rest of the machinery works — got " + parked,
                parked.sameCell(luna.name()));

        double at0 = rangeFrom(reg, luna, parked, 0L);
        double atEnd = rangeFrom(reg, luna, parked, ABANDONED_TICKS);

        System.out.println("[parked-craft] moon: travelRoundPlanet=" + moonTravel + " shell=" + shell
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
    private static final int STAR_ID = 4260;

    /**
     * Earth and Luna at their real bulk and separation, around a Sol-mass star.
     *
     * <p>The numbers are the real ones because the question is quantitative: how far a craft is left
     * behind is the moon's own orbital speed, and a fixture moon on an invented orbit would answer
     * about itself rather than about the system every player meets first.</p>
     */
    private static UniverseRegistry registryWithEarthAndLuna() {
        StellarBody star = new StellarBody();
        star.setId(STAR_ID);
        star.setName("Sol");
        star.setSize(1f);

        DimensionProperties earth = new DimensionProperties(EARTH_DIM);
        // ONE AU, stated as one. It read 100 while a distance unit was a hundredth of an AU; at
        // the 10 000 km that literal now means, Earth sits inside its own star, its sphere of
        // influence collapses and so does the zone cell derived from it (494 blocks, measured).
        earth.orbitalDist =
                dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        earth.baseOrbitTheta = 0.0;
        earth.orbitTheta = 0.0;
        earth.orbitalPhi = 0;
        earth.setBulk(1d, 1d);

        DimensionProperties luna = new DimensionProperties(LUNA_DIM);
        // The real separation, in the moon-unit the layout measures a moon's orbit in.
        luna.orbitalDist = dev.stannismod.stellurgy.util.AstronomicalBodyHelper.MOON_REFERENCE_UNITS;
        luna.baseOrbitTheta = 0.0;
        luna.orbitTheta = 0.0;
        luna.orbitalPhi = 0;
        luna.setBulk(0.0123d, 0.2727d);

        DimensionManager.getInstance().setDimProperties(EARTH_DIM, earth);
        DimensionManager.getInstance().setDimProperties(LUNA_DIM, luna);
        earth.setStar(star);
        luna.setParentPlanet(earth);

        // The registry that NAMES these bodies and says which frame each cell rides — the same
        // resolution production does, rather than a list of bodies whose frames the test picks.
        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, STAR_ID);
        UniverseRegistry.setStarLookup(id -> id == STAR_ID ? star : null);
        return reg;
    }

    @After
    public void unwireTheStarLookup() {
        UniverseRegistry.setStarLookup(null);
    }

    /**
     * A craft holding station one descent shell out from {@code body} at {@code tick}, as an address:
     * the body's own cell, with the offset measured from the origin production says that cell has.
     *
     * <p>Through {@code UniverseRegistry.originAt} and not through {@code body.frame()}: which frame
     * a cell rides is exactly what is under test, and a test that picks the frame itself measures
     * its own choice.</p>
     */
    private static GalacticCoord parkedBeside(UniverseRegistry reg, SystemBody body, long tick) {
        GalacticCoord cell = body.name().cellCentre();
        dev.stannismod.stellurgy.space.BlockDelta off = body.absoluteAt(tick)
                .plus(DescentShell.radiusAround(body), 0L, 0L)
                .minus(reg.originAt(cell, tick));
        return cell.plusLocal(off.dx(), off.dy(), off.dz());
    }

    /** How far {@code parked} is from {@code body} at {@code tick}, the address resolved as production does. */
    private static double rangeFrom(UniverseRegistry reg, SystemBody body, GalacticCoord parked,
                                    long tick) {
        AbsolutePos craft = reg.originAt(parked.cellCentre(), tick)
                .plus(parked.localX(), parked.localY(), parked.localZ());
        return craft.distanceTo(body.absoluteAt(tick));
    }

    private static double sq(long v) {
        return (double) v * v;
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
