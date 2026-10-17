package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.universe.ConeWalk;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The patch of sky one pointing covers: a cone with its apex at the instrument, walked shell by
 * shell outwards.
 *
 * <p><b>Why this is a unit test.</b> {@link ConeWalk} is geometry over the arguments it is aimed
 * with — it reads no configuration, no galaxy and no registry — so its laws are stated here, where a
 * red names one class. Where the cone's reach and width come FROM (the aperture, the configured
 * opening) is the survey's business and is pinned against a running game.</p>
 *
 * <p>The fixture is an arrangement, not a balance claim: an apex off the origin and an aim off every
 * axis, so the disc basis the walk builds across the axis is exercised in all three components, at a
 * stride of a thousand cells — wide enough that rounding a look to a whole cell moves its angle by
 * less than {@link #ROUNDING_RADIANS} at the nearest shell.</p>
 */
public class ConeWalkTest {

    /**
     * A constant: {@code GalacticCoord} is an immutable value: every field final, nothing mutable reachable.
     */
    private static final GalacticCoord APEX = GalacticCoord.ofSectorLocal(7_000L, -3_000L, 11_000L,
            0L, 0L, 0L);
    /** An aim with a component on every axis; (1, 2, -2) is three long, so its unit vector is exact. */
    private static final double DX = 1d;
    private static final double DY = 2d;
    private static final double DZ = -2d;
    private static final double LENGTH = 3d;
    private static final long STRIDE = 1_000L;

    /**
     * The most a look's direction can move by being rounded to a whole cell, at the NEAREST shell: a
     * look is placed by rounding each of its three components, which moves it at most half a cell per
     * axis, i.e. {@code sqrt(3)/2} cells, and the nearest look stands one stride out.
     *
     * <p>A constant: a final {@code double} computed from constants.</p>
     */
    private static final double ROUNDING_RADIANS = Math.sqrt(3d) / 2d / STRIDE;

    private static ConeWalk aimed(double halfAngleDegrees, long shells) {
        return ConeWalk.aimed(APEX, DX, DY, DZ, Math.toRadians(halfAngleDegrees), shells * STRIDE,
                STRIDE);
    }

    /** How far along the aim a look stands, in cells. */
    private static double axial(GalacticCoord look) {
        return ((look.sectorX() - APEX.sectorX()) * DX + (look.sectorY() - APEX.sectorY()) * DY
                + (look.sectorZ() - APEX.sectorZ()) * DZ) / LENGTH;
    }

    /** The angle between the aim and the sight line to a look, in radians. */
    private static double offAxis(GalacticCoord look) {
        double x = look.sectorX() - APEX.sectorX();
        double y = look.sectorY() - APEX.sectorY();
        double z = look.sectorZ() - APEX.sectorZ();
        double along = axial(look);
        double across = Math.sqrt(Math.max(0d, x * x + y * y + z * z - along * along));
        return Math.atan2(across, along);
    }

    /**
     * Every look of a pointing lies inside its cone: ahead of the instrument, no farther than its
     * reach, and no wider than its half-angle.
     *
     * <p>Fails if {@code ConeWalk#lookAt} stops deciding that a look is placed inside the patch of sky
     * it was aimed at.</p>
     */
    @RedWitness(verdict = "inside the patch", member = "ConeWalk#radiusOfShell",
            at = "double radius = s * Math.tan(halfAngleRadians);", replacedBy = "double radius = 2 * s * Math.tan(halfAngleRadians);",
            fails = "look 2 stands outside the patch:", on = "2026-10-17")
    @RedWitness(verdict = "within reach", member = "ConeWalk#lookAt", at = "double axial = (double) shell * strideCells;",
            replacedBy = "double axial = (double) (shell + 1) * strideCells;",
            fails = "stands past the pointing's reach:", on = "2026-10-17")
    @RedWitness(verdict = "ahead of the instrument", member = "ConeWalk#lookAt",
            at = "double axial = (double) shell * strideCells;", replacedBy = "double axial = (double) -shell * strideCells;",
            fails = "look 0 stands behind or beside the instrument:", on = "2026-10-17")
    @Test
    public void everyLookOfAPointingLiesInsideItsCone() {
        double half = Math.toRadians(10d);
        ConeWalk cone = aimed(10d, 60L);
        assertTrue("arrangement: a pointing worth checking holds more looks than its own axis ("
                + cone.totalLooks() + " looks over " + cone.shells() + " shells)",
                cone.totalLooks() > cone.shells());

        double widest = 0d;
        for (int i = 0; i < cone.totalLooks(); i++) {
            GalacticCoord look = cone.lookAt(i);
            double along = axial(look);
            double angle = offAxis(look);
            assertTrue("look " + i + " stands behind or beside the instrument: " + along + " cells along"
                    + " the aim", along > 0d);
            assertTrue("look " + i + " stands past the pointing's reach: " + along + " > "
                    + cone.reachCells(), along <= cone.reachCells() + Math.sqrt(3d) / 2d);
            assertTrue("look " + i + " stands outside the patch: " + Math.toDegrees(angle)
                    + " degrees off the aim against a half-angle of 10", angle <= half + ROUNDING_RADIANS);
            widest = Math.max(widest, angle);
        }
        // Half the half-angle: far above ROUNDING_RADIANS (the most an on-axis look can stray), so a
        // walk that never left the axis cannot pass it, and well inside the cone a walk that did must.
        assertTrue("arrangement: the looks must reach out across the patch, not only along its axis"
                + " (widest " + Math.toDegrees(widest) + " degrees)", widest > half / 2d);
    }

    /**
     * A patch twice as wide is about four times the survey: the work grows with the square of the
     * opening, which is what the operator is told when he widens it.
     *
     * <p>Fails if {@code ConeWalk#buildShells} stops deciding that a shell's looks fill a disc whose
     * radius grows with the tangent of the half-angle.</p>
     *
     * <p>No tuned bound: both candidates are computed from the two openings. A disc's area goes with
     * the square of its radius, {@code (tan 10deg / tan 5deg)^2 = 4.06}; a walk whose work grew only
     * with the radius would read {@code tan 10deg / tan 5deg = 2.02}. The lattice floors every shell's
     * radius to a whole stride, so the measured ratio is neither exactly (measured 2026-10-02: 58 940
     * looks at five degrees, 250 404 at ten, ratio 4.25); the claim is that it sits nearer the square.</p>
     */
    @RedWitness(member = "ConeWalk#discLooks", at = "count += 2L * rowHalfWidth(radius, i) + 1L;", replacedBy = "count += 1L;",
            fails = "nearer the square of the radii", on = "2026-10-17")
    @Test
    public void aPatchTwiceAsWideIsAboutFourTimesTheSurvey() {
        ConeWalk narrow = aimed(5d, 200L);
        ConeWalk wide = aimed(10d, 200L);
        double ratio = wide.totalLooks() / (double) narrow.totalLooks();
        double radii = Math.tan(Math.toRadians(10d)) / Math.tan(Math.toRadians(5d));
        double areas = radii * radii;
        System.out.println("a 200-shell pointing holds " + narrow.totalLooks() + " looks at 5 degrees and "
                + wide.totalLooks() + " at 10: ratio " + ratio);
        assertTrue("doubling the opening must about quadruple the survey - the ratio must sit nearer the"
                        + " square of the radii (" + areas + ") than the radii (" + radii + "): "
                        + narrow.totalLooks() + " -> " + wide.totalLooks() + " looks, ratio " + ratio,
                Math.abs(ratio - areas) < Math.abs(ratio - radii));
    }

    /**
     * A pointing is walked outwards, so whatever part of it a survey has done when it is stopped is a
     * SHORTER CONE: the first looks of a deep pointing are exactly the looks of a shallower one aimed
     * the same way.
     *
     * <p>Fails if {@code ConeWalk#lookAt} stops deciding the walk's order shell by shell from the
     * instrument outwards.</p>
     */
    @RedWitness(member = "ConeWalk#lookAt", at = "double axial = (double) shell * strideCells;",
            replacedBy = "double axial = (double) (shells() + 1 - shell) * strideCells;",
            fails = "look 0 of the deep pointing must be look 0 of the 1-shell one aimed the same way", on = "2026-10-17")
    @Test
    public void anAbortedPointingIsAShorterCone() {
        ConeWalk deep = aimed(10d, 40L);
        int offAxisCompared = 0;
        for (long shells = 1L; shells < 40L; shells++) {
            ConeWalk shallow = aimed(10d, shells);
            assertTrue("arrangement: a shallower pointing holds fewer looks (" + shells + " shells: "
                    + shallow.totalLooks() + " vs " + deep.totalLooks() + ")",
                    shallow.totalLooks() < deep.totalLooks());
            for (int i = 0; i < shallow.totalLooks(); i++) {
                GalacticCoord look = deep.lookAt(i);
                assertEquals("look " + i + " of the deep pointing must be look " + i + " of the "
                                + shells + "-shell one aimed the same way",
                        shallow.lookAt(i).cellKey(), look.cellKey());
                if (offAxis(look) > ROUNDING_RADIANS) {
                    offAxisCompared++;
                }
            }
        }
        // The order a disc is walked in is what an outward walk could get wrong without moving any
        // look off the axis, so the prefixes compared must contain discs and not only the axis.
        assertTrue("arrangement: the prefixes compared must hold looks off the axis too ("
                + offAxisCompared + " of them)", offAxisCompared > 0);
    }
}
