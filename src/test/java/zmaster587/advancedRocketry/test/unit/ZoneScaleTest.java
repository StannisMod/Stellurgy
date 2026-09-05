package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.DescentShell;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ZoneScale;
import zmaster587.advancedRocketry.universe.BodyEphemeris;
import zmaster587.advancedRocketry.universe.CellFrame;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A zone's lattice has to do TWO things at once, and they pull in opposite directions.
 *
 * <ul>
 *   <li><b>Give two bodies two names.</b> A body is named by the cell it occupies in its parent's
 *       zone, so a lattice too COARSE for the gap between a planet and its innermost moon names
 *       them both by the same cell, and "a moon shares its parent's name" returns through the back
 *       door.</li>
 *   <li><b>Leave room to fly.</b> A cell is also the region a craft moves in before a seam carries
 *       it to the next one, so a lattice too FINE puts a craft at a body's own descent shell in a
 *       NEIGHBOURING cell — which rides that body's PARENT. The craft is then carried by the wrong
 *       thing while sitting right beside the body.</li>
 * </ul>
 *
 * <p><b>The second half is the one that was missing, and its absence shipped.</b> The count was a
 * flat 1024 derived from the naming bound alone, and finer than the flying bound in every zone —
 * in the home system by a factor of two, so a craft parked one shell off Luna fell into the next
 * cell and was carried by Earth. That is the defect the whole moon-zone change exists to remove,
 * surviving inside the fix for it. Both halves are asserted here, on real bodies.</p>
 *
 * <p>Real bodies rather than bare numbers, and that is a change: the lattice used to be a function
 * of ONE number (the sphere in blocks) and is now a function of the BODY, because its own radius
 * decides how much room it needs.</p>
 */
public class ZoneScaleTest {

    /** Metres per chart block — the metric, restated here only to convert the reference values. */
    private static final double D = 250d;
    private static final double SOL_MASS_EARTHS = 332_946d;

    private static long blocks(double km) {
        return Math.round(km * 1000d / D);
    }

    /**
     * Every real moon lands in a cell that is not its planet's own — the NAMING half.
     *
     * <p>Pan is the tightest case in the system; it is asserted alongside the others rather than
     * alone, because a lattice sized for Pan and wrong for Luna would pass a single-case test.</p>
     */
    @Test
    public void everyRealMoonGetsACellOfItsOwn() {
        for (Planet p : SOLAR_SYSTEM) {
            if (p.innermostMoonOrbitKm <= 0d) {
                continue;
            }
            long cell = ZoneScale.cellBlocks(p.body(), sol(), 0L);
            long index = ZoneScale.cellIndex(blocks(p.innermostMoonOrbitKm), cell);
            assertTrue(p.name + "'s innermost moon orbits " + (long) p.innermostMoonOrbitKm
                            + " km out and shares its planet's cell: lattice cell = " + cell
                            + " blocks, index = " + index + ". The planet sits at index 0, so this "
                            + "moon has no name of its own, and it is sharing its planet's.",
                    index != 0L);
        }
    }

    /**
     * Every body's own descent shell fits inside its own cell — the FLYING half.
     *
     * <p>This is the assertion whose absence let a flat count ship. A craft one descent shell out
     * from a body is, physically, right beside it; if that puts it in the next cell, the frame that
     * carries it is the body's PARENT, and it drifts away from the body it is parked at. Measured
     * before the fix: Earth's zone cell was 7 235 blocks against Luna's 7 059-block shell, so the
     * craft was outside Luna's cell by construction and no amount of flying could get it in.</p>
     */
    @Test
    public void everyBodysOwnDescentShellFitsInsideItsOwnCell() {
        for (Planet p : SOLAR_SYSTEM) {
            SystemBody body = p.body();
            long cell = ZoneScale.cellBlocks(body, sol(), 0L);
            long shell = DescentShell.radiusAround(body);
            assertTrue(p.name + " has a descent shell of " + shell + " blocks against a zone cell "
                            + "of " + cell + " (half " + (cell / 2) + "): a craft parked at that "
                            + "shell is in a NEIGHBOURING cell, which rides this body's parent, so "
                            + "it is carried away from the body it is parked at",
                    shell <= cell / 2L);
        }
    }

    /**
     * <b>The two bounds leave less than one power of two of room, and this states the margin
     * instead of trusting it.</b>
     *
     * <p>The window is a factor of <b>2.461</b>: a body's descent shell stands at 1.0157 of its
     * radius (the Kármán fraction) and the closest a moon may be authored is 2.5 radii (the floor
     * {@code SystemContent} lifts an authored moon to). A power-of-two lattice steps by 2.000, so
     * the usable slack is <b>1.23&times;</b> — the choice of where the count lands inside that
     * window is not free, and a change to either constant can close it entirely.</p>
     *
     * <p>The count is taken as the FINEST lattice the flying bound allows, which puts the slack on
     * the naming side. That is the deliberate direction: a moon sharing its parent's cell is a
     * DEFECT — two destinations at one address — while a tight flying margin is a degradation, a
     * craft at 1.1 shells being carried by the parent rather than the body. The margins are
     * measured here so an erosion of either shows up as a red rather than as a shipped surprise.</p>
     */
    @Test
    public void theTwoBoundsLeaveLessThanOnePowerOfTwoOfRoomAndBothAreMet() {
        double moonFloorOverShell =
                zmaster587.advancedRocketry.universe.SystemContent.MOON_MIN_PARENT_RADII
                        / (1d + DescentShell.ATMOSPHERE_FRACTION);
        assertTrue("the window between the two bounds has closed: a moon may be authored at "
                        + zmaster587.advancedRocketry.universe.SystemContent.MOON_MIN_PARENT_RADII
                        + " radii while a shell reaches " + (1d + DescentShell.ATMOSPHERE_FRACTION)
                        + ", a factor of " + moonFloorOverShell + ". Below 2.0 no power-of-two "
                        + "lattice can satisfy both, and one of them has to give.",
                moonFloorOverShell > 2d);

        for (Planet p : SOLAR_SYSTEM) {
            SystemBody body = p.body();
            long cell = ZoneScale.cellBlocks(body, sol(), 0L);
            long shell = DescentShell.radiusAround(body);
            // The naming half, stated against the CLOSEST a moon could ever be rather than against
            // the moon this body happens to have: it is the bound the construction guarantee rests
            // on, and the real moons are all further out than it.
            long closestPossibleMoon = Math.round(
                    p.radiusEarths * (6_371_000d / D)
                            * zmaster587.advancedRocketry.universe.SystemContent.MOON_MIN_PARENT_RADII);
            assertTrue(p.name + ": a moon at the authored floor (" + closestPossibleMoon
                            + " blocks) would share its cell of " + cell,
                    ZoneScale.cellIndex(closestPossibleMoon, cell) != 0L);
            assertTrue(p.name + ": a craft at the body's own shell (" + shell + ") is outside its "
                            + "cell of " + cell, shell <= cell / 2L);
        }
    }

    /**
     * The lattice is uniform across zero — the body's own cell is not twice its neighbours'.
     *
     * <p>Truncating division would map both −0.5 and +0.5 of a cell onto index 0. That is not a
     * rounding nicety: a double-width central cell is exactly where a planet sits, so it would widen
     * the one cell that must not swallow its moons.</p>
     */
    @Test
    public void theCentralCellIsNotWiderThanTheRest() {
        long cell = 1_000L;
        assertEquals("just inside the central cell, positive side", 0L, ZoneScale.cellIndex(499L, cell));
        assertEquals("just inside the central cell, negative side", 0L, ZoneScale.cellIndex(-500L, cell));
        assertEquals("one block further out is the next cell", 1L, ZoneScale.cellIndex(500L, cell));
        assertEquals("and symmetrically on the other side", -1L, ZoneScale.cellIndex(-501L, cell));
    }

    /** The count is a power of two, so a body on a cell boundary does not depend on a rounding mode. */
    @Test
    public void theCountIsAPowerOfTwo() {
        for (Planet p : SOLAR_SYSTEM) {
            int count = ZoneScale.cellsAcrossZone(p.body(), sol(), 0L);
            assertTrue(p.name + " has a lattice of " + count + " cells, which is not a power of two",
                    count > 0 && (count & (count - 1)) == 0);
        }
    }

    /**
     * A zone that does not exist has no lattice, and says so with a zero rather than a stand-in.
     *
     * <p>A body with no mass defines no sphere of influence, and a small-but-nonzero
     * cell handed back here would be indistinguishable from a real lattice at every call site — the
     * naming would succeed and produce an address for a zone nobody owns.</p>
     */
    @Test
    public void noZoneMeansNoLatticeAndTheZeroIsTheAnswer() {
        assertEquals("a body with no sphere of influence has no lattice",
                0L, ZoneScale.cellBlocks(null, null, 0L));
        assertEquals("and an index against no lattice is not invented", 0L,
                ZoneScale.cellIndex(1_234_567L, 0L));
    }

    // ---- fixture: the real solar system, at the shipped metric ---------------------------------

    /**
     * One reference body: what it weighs and how big it is decide its zone, and its innermost moon
     * is what that zone has to be able to name apart.
     */
    private static final class Planet {
        final String name;
        final double massEarths;
        final double radiusEarths;
        final double orbitKm;
        final double innermostMoonOrbitKm; // 0 = no moons

        Planet(String name, double massEarths, double radiusEarths, double orbitKm,
               double innermostMoonOrbitKm) {
            this.name = name;
            this.massEarths = massEarths;
            this.radiusEarths = radiusEarths;
            this.orbitKm = orbitKm;
            this.innermostMoonOrbitKm = innermostMoonOrbitKm;
        }

        /** The body, standing at its real distance from Sol so its sphere is the real one. */
        SystemBody body() {
            BodyEphemeris orbit = BodyEphemeris.fixed(blocks(orbitKm), 0L, 0L);
            return new SystemBody(GalacticCoord.ORIGIN,
                    CellFrame.of(AbsolutePos.ofCellName(GalacticCoord.ORIGIN), orbit),
                    BodyEphemeris.STATIC, SystemBodyKind.PLANET, Constants.INVALID_PLANET, 1,
                    100, radiusEarths, massEarths);
        }
    }

    private static final Planet EARTH =
            new Planet("Earth", 1d, 1d, 149_600_000d, 384_400d);
    private static final Planet LUNA =
            new Planet("Luna", 0.0123d, 0.2727d, 384_400d, 0d);

    private static final Planet[] SOLAR_SYSTEM = {
            new Planet("Mars", 0.107d, 0.532d, 227_900_000d, 9_376d),          // Phobos
            EARTH,                                                              // Luna
            new Planet("Jupiter", 317.8d, 11.209d, 778_500_000d, 128_000d),    // Metis
            new Planet("Saturn", 95.16d, 9.449d, 1_432_000_000d, 133_580d),    // Pan
            new Planet("Uranus", 14.54d, 4.007d, 2_867_000_000d, 129_900d),    // Miranda
            new Planet("Neptune", 17.15d, 3.883d, 4_515_000_000d, 48_227d),    // Naiad
    };

    private static SystemBody sol() {
        return SystemBody.fixedAt(GalacticCoord.ORIGIN, SystemBodyKind.STAR,
                        Constants.INVALID_PLANET, 1)
                .withBulk(SOL_MASS_EARTHS, 109.17d);
    }
}
