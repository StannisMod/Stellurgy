package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.space.DescentShell;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ZoneScale;
import dev.stannismod.stellurgy.universe.BodyEphemeris;
import dev.stannismod.stellurgy.universe.CellFrame;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
     *
     * <p>red-witnessed: 2026-09-29, with `ZoneScale.cellsAcrossZone:102` leaving a zone with children
     * undivided (one cell), this fails with "Mars's innermost moon orbits 9376 km out and shares its
     * planet's cell: lattice cell = 4609334 blocks, index = 0 …".</p>
     */
    @Test
    public void everyRealMoonGetsACellOfItsOwn() {
        for (Planet p : SOLAR_SYSTEM) {
            if (p.innermostMoonOrbitKm <= 0d) {
                continue;
            }
            long cell = ZoneScale.cellBlocks(p.body(), sol(), p.tightestMoonBlocks(), 0L);
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
     *
     * <p>red-witnessed: 2026-09-29, with `ZoneScale.cellsAcrossZone:102` put back to the flat 1024 it
     * shipped as (for any zone with a child), this fails with "Mars has a descent shell of 13786
     * blocks against a zone cell of 4502 (half 2251) …".</p>
     */
    @Test
    public void everyBodysOwnDescentShellFitsInsideItsOwnCell() {
        for (Planet p : SOLAR_SYSTEM) {
            SystemBody body = p.body();
            long cell = ZoneScale.cellBlocks(body, sol(), p.tightestMoonBlocks(), 0L);
            long shell = DescentShell.radiusAround(body);
            assertTrue(p.name + " has a descent shell of " + shell + " blocks against a zone cell "
                            + "of " + cell + " (half " + (cell / 2) + "): a craft parked at that "
                            + "shell is in a NEIGHBOURING cell, which rides this body's parent, so "
                            + "it is carried away from the body it is parked at",
                    shell <= cell / 2L);
        }
    }

    /**
     * <b>A cell CONTAINS the sphere of influence of the body it names — the bound that makes
     * membership by geometry possible at all.</b>
     *
     * <p>A cell is not only a name: it is the region a craft flies in before a seam carries it out,
     * and it is concentric on its body, because a cell's frame origin IS that body. So if a body's
     * sphere reaches past its own cell, the cube face fires before the sphere test ever can — and
     * membership can then never be decided by the sphere, however the crossing is written. This is
     * the bound the whole reference-frame clause rests on and it is the one that had to be found
     * before the lattice could be sized at all.</p>
     *
     * <p>It is satisfied by the naming bound automatically, and the reason is worth stating rather
     * than trusting: {@code r_SOI = a * (m/M)^(2/5)} with {@code m < M} is always LESS than the
     * orbit {@code a}, while the naming bound makes a cell about twice that orbit. So a lattice
     * fine enough to name a moon apart is coarse enough to hold its sphere, for every mass ratio
     * that can exist. Asserted on the real system anyway, because "always" is a claim about the
     * arithmetic and this is a claim about the code.</p>
     *
     * <p>red-witnessed: 2026-09-29, with the same inversion as the descent-shell test —
     * `ZoneScale.cellsAcrossZone:102` returning the flat 1024 — this fails with "Mars's innermost moon
     * has a sphere of influence 7843 blocks in radius against a cell of 4502 (half 2251) …".</p>
     */
    @Test
    public void everyCellContainsTheSphereOfTheBodyItNames() {
        boolean checkedAMoon = false;
        for (Planet p : SOLAR_SYSTEM) {
            if (p.innermostMoonOrbitKm <= 0d) {
                continue;
            }
            long cell = ZoneScale.cellBlocks(p.body(), sol(), p.tightestMoonBlocks(), 0L);
            // The innermost moon's own sphere, measured against its parent the way production does.
            SystemBody moon = p.innermostMoon();
            long sphere = ZoneScale.realizedRadiusBlocks(moon, p.body(), 0L);
            assertTrue("arrangement: the moon must have a sphere at all", sphere > 0L);
            checkedAMoon = true;
            assertTrue(p.name + "'s innermost moon has a sphere of influence " + sphere
                            + " blocks in radius against a cell of " + cell + " (half " + (cell / 2)
                            + "): a craft inside that sphere is carried out of the moon's cell by "
                            + "the cube face before the sphere is ever reached, so no crossing rule "
                            + "written against the sphere can take effect",
                    sphere <= cell / 2L);
        }
        assertTrue("the sweep must actually check a moon", checkedAMoon);
    }

    /**
     * A body with NO children gets ONE cell, and its lattice becomes its sphere of influence.
     *
     * <p>This is the case almost every body in the game is in, and it is where the design pays off:
     * "which body carries this craft" and "which cell is it in" become the same question, because
     * the cell and the sphere are the same region. Nothing needs naming apart inside it, so nothing
     * asks for the lattice to be divided.</p>
     *
     * <p>red-witnessed: 2026-09-29, twice. With `ZoneScale.cellsAcrossZone:100` answering 2 for a
     * childless body, this fails with "a childless body's zone is not divided expected:&lt;1&gt; but
     * was:&lt;2&gt;". With `ZoneScale.cellBlocks` sizing a one-cell zone from the realization cap
     * instead of the sphere, it fails with "...so its one cell spans exactly its sphere
     * expected:&lt;529462.0&gt; but was:&lt;3.2E7&gt;" — which it could NOT do before that day: its moon
     * was built on a frame of its own, its sphere was the cap itself, and the two readings coincided.</p>
     */
    @Test
    public void aBodyWithNoChildrenGetsOneCellSpanningItsWholeSphere() {
        // Luna NESTED in Earth's frame. Built on a frame of its own (`LUNA.body()`, as this test first
        // was), it stands 384 400 km from the ORIGIN rather than from Earth, its sphere comes out
        // near 1e8 blocks and is clamped to the realization cap — measured 2026-09-29: 16 000 000.
        SystemBody luna = EARTH.innermostMoon(LUNA.massEarths, LUNA.radiusEarths);
        assertEquals("a childless body's zone is not divided", 1,
                ZoneScale.cellsAcrossZone(luna, EARTH.body(), 0L, 0L));
        long cell = ZoneScale.cellBlocks(luna, EARTH.body(), 0L, 0L);
        long sphere = ZoneScale.realizedRadiusBlocks(luna, EARTH.body(), 0L);
        // ARRANGEMENT: the sphere must be the body's OWN, below the realization cap. At the cap,
        // "the cell spans the sphere" and "the cell spans the cap" are one number and this measures
        // nothing about which the lattice was sized from.
        assertTrue("arrangement: the moon's sphere must be its own and not the realization cap ("
                + sphere + " against a cap of " + GalacticCoord.HALF_CELL + ")",
                sphere < GalacticCoord.HALF_CELL);
        assertEquals("...so its one cell spans exactly its sphere", 2L * sphere, cell, 1d);
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

    /**
     * The count is a power of two, so a body on a cell boundary does not depend on a rounding mode.
     *
     * <p>red-witnessed: 2026-09-29, with `ZoneScale.cellsAcrossZone:103` returning the raw
     * {@code needed} count instead of rounding it up to a power of two, this fails with "Mars has a
     * lattice of 62 cells, which is not a power of two".</p>
     */
    @Test
    public void theCountIsAPowerOfTwo() {
        for (Planet p : SOLAR_SYSTEM) {
            int count = ZoneScale.cellsAcrossZone(p.body(), sol(), p.tightestMoonBlocks(), 0L);
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
     *
     * <p>red-witnessed: 2026-09-29, with `ZoneScale.cellBlocks:127` answering 1 instead of 0 for a
     * body with no zone, this fails with "a body with no sphere of influence has no lattice
     * expected:&lt;0&gt; but was:&lt;1&gt;".</p>
     */
    @Test
    public void noZoneMeansNoLatticeAndTheZeroIsTheAnswer() {
        assertEquals("a body with no sphere of influence has no lattice",
                0L, ZoneScale.cellBlocks(null, null, 0L, 0L));
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
        /** How far this body's INNERMOST moon sits from it, in blocks; 0 when it has none. */
        long tightestMoonBlocks() {
            return innermostMoonOrbitKm <= 0d ? 0L : blocks(innermostMoonOrbitKm);
        }

        /**
         * That moon as a body, at a MASS ratio typical of a real inner moon.
         *
         * <p>The mass is what its sphere is computed from, and it is stated as a RATIO to the parent
         * rather than as a table of real values: the point of the case is that a lattice sized to
         * name a moon apart is coarse enough to hold that moon's sphere <b>for any mass ratio</b>,
         * so the ratio is the variable and a generous one is the honest choice. Real inner moons are
         * far lighter than this.</p>
         */
        SystemBody innermostMoon() {
            return innermostMoon(massEarths / 50d, radiusEarths / 4d);
        }

        /** The innermost moon with a stated bulk — for a case about one particular moon. */
        SystemBody innermostMoon(double moonMassEarths, double moonRadiusEarths) {
            // NESTED in its parent's frame, the way production builds one — so the separation the
            // sphere is computed from is the moon's orbit about its PARENT. Built on a frame of its
            // own it would be that distance from the SUN instead, and the sphere would come back as
            // the no-primary fallback (a whole half-cell), which is how this fixture first read.
            BodyEphemeris parentOrbit = BodyEphemeris.fixed(blocks(orbitKm), 0L, 0L);
            BodyEphemeris moonOrbit = BodyEphemeris.fixed(tightestMoonBlocks(), 0L, 0L);
            CellFrame parentFrame =
                    CellFrame.of(AbsolutePos.ofCellName(GalacticCoord.ORIGIN), parentOrbit);
            return new SystemBody(GalacticCoord.ORIGIN, CellFrame.within(parentFrame, moonOrbit),
                    BodyEphemeris.STATIC, SystemBodyKind.MOON, Constants.INVALID_PLANET, 1,
                    100, moonRadiusEarths, moonMassEarths);
        }

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

    /**
     * <b>A re-address keeps the craft where it is.</b> The cell is only half the answer.
     *
     * <p>{@code cellWithin} names a BODY, which sits at its own cell's centre by construction. A
     * craft does not, and an address that dropped the in-cell remainder would move it to the nearest
     * cell centre at the instant it crossed a sphere — up to half a cell, which in Earth's zone is
     * 924 647 blocks of teleport nobody asked for. This is the assertion that separates the two.</p>
     *
     * <p>red-witnessed: 2026-09-29, with `ZoneScale.addressOnLattice:236` dropping the in-cell
     * remainder (locals 0), this fails with "the address must denote where the craft actually is
     * expected:&lt;4315080&gt; but was:&lt;3698640&gt;".</p>
     */
    @Test
    public void anAddressInsideAZoneKeepsTheOffsetAndDoesNotSnapToACellCentre() {
        SystemBody earth = EARTH.body();
        long width = ZoneScale.cellBlocks(earth, sol(), EARTH.tightestMoonBlocks(), 0L);
        assertTrue("arrangement: Earth's zone must be divided for this to mean anything", width > 0L);

        // Deliberately NOT on a cell boundary: two and a bit cells out, plus a remainder that must
        // survive. If the remainder were dropped the craft would move by exactly that much.
        long offset = 2L * width + width / 3L;
        dev.stannismod.stellurgy.space.BlockDelta at =
                dev.stannismod.stellurgy.space.BlockDelta.of(offset, 0L, 0L);

        GalacticCoord address = ZoneScale.addressWithin(earth, sol(), at,
                EARTH.tightestMoonBlocks(), 0L);
        assertNotNull("a body with a zone must be able to address a craft in it", address);

        // The address must denote the SAME displacement from the body it was built from.
        long recovered = address.sectorX() * width + address.localX();
        assertEquals("the address must denote where the craft actually is", offset, recovered);
        assertTrue("...and it must not have been snapped to a cell centre",
                address.localX() != 0L);

        // The cell-only form is the other answer and is right for a BODY, which is why both exist.
        GalacticCoord centre = ZoneScale.cellWithin(earth, sol(), at, EARTH.tightestMoonBlocks(), 0L);
        assertEquals("the cell form snaps, and that is its job", 0, centre.localX());
        assertTrue("but the two must agree on WHICH cell", centre.sameCell(address));
    }

    /**
     * <b>Addressed on the width a moon's name records, a craft where the moon stands is named by the
     * moon's cell</b> — the ADDRESSING half of "a zone has one lattice".
     *
     * <p>A zone's lattice is sized by the naming path, and a craft is addressed on it by
     * {@link ZoneScale#addressOnLattice} with a width handed in. This pins that the addressing half
     * honours the width it is handed: given the width the naming path recorded (which is what a
     * moon's own name carries), a craft sitting exactly where the moon sits lands in the MOON's cell,
     * not in cell 0, the planet's own.</p>
     *
     * <p><b>What it does NOT pin</b>, stated because this test used to claim it: WHICH width the
     * crossing reads. The width here is supplied by the test, so a crossing that sized a second
     * lattice of its own would not be seen. That choice is pinned on a real registry by
     * {@code ZoneCrossingAimsAtTheRightCellTest#aCraftLeavingAMoonsSphereIsNamedByTheMoonsOwnCell},
     * whose red-witness inverts {@code SpaceSubsystem.latticeOf}.</p>
     *
     * <p>red-witnessed: 2026-09-29, with `ZoneScale.addressOnLattice:233` made to ignore the width it
     * is handed and use the undivided one (7 397 280), this fails with *"a craft standing exactly
     * where the moon stands is named by cell 0_0_0.0_0_0 while the moon itself is named by
     * 0_0_0.1_0_0"* — the defect verbatim.</p>
     */
    @Test
    public void aCraftIsReAddressedOnTheLatticeItsZonesBodiesAreNamedIn() {
        SystemBody earth = EARTH.body();
        long lunaOut = EARTH.tightestMoonBlocks();
        dev.stannismod.stellurgy.space.BlockDelta whereLunaIs =
                dev.stannismod.stellurgy.space.BlockDelta.of(lunaOut, 0L, 0L);

        // How the NAMING path builds Luna's cell: the lattice knows what it has to name apart.
        GalacticCoord lunasCell = ZoneScale.cellWithin(earth, sol(), whereLunaIs,
                EARTH.tightestMoonBlocks(), 0L);
        assertNotNull("arrangement: Earth's zone must name its moon at all", lunasCell);
        assertTrue("arrangement: Luna must not already share Earth's own cell",
                lunasCell.sectorX() != 0L);

        // Addressed on the width the naming pass RECORDED, which is what a moon's own name carries.
        GalacticCoord craft = ZoneScale.addressOnLattice(earth.name().cellKey(),
                lunasCell.cellBlocks(), whereLunaIs);
        assertNotNull("a craft in a zone must be addressable in it", craft);

        assertTrue("a craft standing exactly where the moon stands is named by cell "
                        + craft.cellKey() + " while the moon itself is named by " + lunasCell.cellKey()
                        + ": it was addressed on a lattice of " + craft.cellBlocks()
                        + " blocks and the moon was named on one of " + lunasCell.cellBlocks()
                        + ". The width is not part of a cell key, so this does not fail anywhere — it "
                        + "renames the cell, and everything keyed on that name then answers about a "
                        + "different place.",
                craft.sameCell(lunasCell));
    }

    private static SystemBody sol() {
        return SystemBody.fixedAt(GalacticCoord.ORIGIN, SystemBodyKind.STAR,
                        Constants.INVALID_PLANET, 1)
                .withBulk(SOL_MASS_EARTHS, 109.17d);
    }
}
