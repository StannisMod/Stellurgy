package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.MassContributor.Kind;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The mass frame is what every flight characteristic is derived from, so these pin the properties
 * the rest of the ship model is entitled to assume — not the arithmetic, which is textbook.
 *
 * <p>Concretely: the categories add up and nothing is counted twice; the centre of mass is the
 * mass-weighted mean, so loading cargo to one side moves it; the inertia tensor is symmetric and
 * remains invertible even for the hull shapes that would degenerate a point-mass model; and mass
 * knows nothing about gravity, which is what keeps a craft the same craft on every world. The
 * particular masses used here are arbitrary — the relationships are what is asserted.</p>
 */
public class ShipMassFrameTest {

    /**
     * Measured 2026-09-30 with every tolerance in this class set to zero: each verdict is EXACT except
     * the order test's tensor, whose residual is under 1e-12 (summation order). So this bar is slack
     * for rounding, far below any error a verdict here exists to catch.
     */
    private static final double EPS = 1.0e-9D;

    /** Two identical blocks either side of the origin, one metre apart. */
    private static ShipMassFrame symmetricPair(double mass) {
        return new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, mass, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, mass, Kind.STRUCTURAL))
                .build();
    }

    /**
     * Translating a frame moves its centre of mass and leaves its inertia alone.
     *
     * <p>This is the property that lets a hull be MEASURED near itself and REPORTED in the address
     * space the physics record keeps — a ship's own subspace, which starts past five million blocks
     * along X. Accumulating second moments about a point that far away spends most of a double's
     * precision on a constant that cancels at the end; measuring locally and translating does not.</p>
     *
     * <p>The inertia leg is the load-bearing half: the tensor is expressed <em>about the centre of
     * mass</em>, so it must be invariant here. If it ever stopped being, every craft's handling would
     * silently depend on where its shipyard happened to be allocated.</p>
     */
    @RedWitness(verdict = "mass", member = "ShipMassFrame#translated", at = "return new ShipMassFrame(structuralMass,",
            replacedBy = "return new ShipMassFrame(2 * structuralMass,", fails = "translation must not invent or lose mass",
            on = "2026-10-17")
    @RedWitness(verdict = "centre", member = "ShipMassFrame#translated",
            at = "new Vector3d(centreOfMass).add(dx, dy, dz), inertia);", replacedBy = "new Vector3d(centreOfMass), inertia);",
            fails = "the centre moves by exactly the offset", on = "2026-10-17")
    @RedWitness(verdict = "inertia", member = "ShipMassFrame#translated",
            at = "new Vector3d(centreOfMass).add(dx, dy, dz), inertia);",
            replacedBy = "new Vector3d(centreOfMass).add(dx, dy, dz), new Matrix3d(inertia).scale(2.0D));",
            fails = "inertia about the centre of mass cannot depend on where the centre IS", on = "2026-10-17")
    @Test
    public void translatingMovesTheCentreAndLeavesTheInertiaAlone() {
        ShipMassFrame local = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-1.5D, 0.0D, 0.0D, 800.0D, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(2.5D, 0.0D, 0.0D, 200.0D, Kind.STRUCTURAL))
                .build();

        double dx = 5120000.0D, dy = 128.0D, dz = 51200.0D;
        ShipMassFrame moved = local.translated(dx, dy, dz);

        assertEquals("translation must not invent or lose mass",
                local.getTotalMass(), moved.getTotalMass(), EPS);
        // 1e-6 of a block at x = 5.12e6. Measured 2026-09-30 with the bound at zero: the translated
        // centre is exact.
        assertSameVector("the centre moves by exactly the offset, at a real shipyard distance",
                new Vector3d(local.getCentreOfMass()).add(dx, dy, dz), moved.getCentreOfMass(), 1.0e-6D);
        assertSameTensor("inertia about the centre of mass cannot depend on where the centre IS",
                local.getInertia(), moved.getInertia());
    }

    @RedWitness(verdict = "structural", member = "ShipMassFrameBuilder#add", at = "contentMass += m;",
            replacedBy = "structuralMass += m;", fails = "expected:<1000.0> but was:<1300.0>", on = "2026-10-17")
    @RedWitness(verdict = "content", member = "ShipMassFrameBuilder#add", at = "contentMass += m;",
            replacedBy = "crewMass += m;", fails = "expected:<300.0> but was:<0.0>", on = "2026-10-17")
    @RedWitness(verdict = "crew", member = "ShipMassFrameBuilder#add", at = "crewMass += m;", replacedBy = "",
            fails = "expected:<80.0> but was:<0.0>", on = "2026-10-17")
    @RedWitness(verdict = "total", member = "ShipMassFrame#getTotalMass", at = "return structuralMass + contentMass + crewMass;",
            replacedBy = "return structuralMass + contentMass;", fails = "expected:<1380.0> but was:<1300.0>", on = "2026-10-17")
    @Test
    public void totalIsExactlyTheThreeCategories() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0, 0, 0, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(1, 0, 0, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(1, 0, 0, 300, Kind.CONTENT))
                .add(MassContributor.of(0, 1, 0, 80, 0.6D, Kind.CREW))
                .build();

        assertEquals(1000.0D, frame.getStructuralMass(), EPS);
        assertEquals(300.0D, frame.getContentMass(), EPS);
        assertEquals(80.0D, frame.getCrewMass(), EPS);
        assertEquals(frame.getStructuralMass() + frame.getContentMass() + frame.getCrewMass(),
                frame.getTotalMass(), EPS);
    }

    @RedWitness(member = "ShipMassFrameBuilder#add", at = "double x = contributor.getX();",
            replacedBy = "double x = contributor.getX() + 0.5D;", fails = "the centre of mass is the mass-weighted mean",
            on = "2026-10-17")
    @Test
    public void centreOfMassIsTheMassWeightedMean() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 300, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(4.0D, 0.0D, 0.0D, 100, Kind.STRUCTURAL))
                .build();

        // 300 kg at 0 and 100 kg at 4 balance at 1.
        assertSameVector("the centre of mass is the mass-weighted mean",
                new Vector3d(1.0D, 0.0D, 0.0D), frame.getCentreOfMass(), EPS);
    }

    @RedWitness(verdict = "balanced", member = "ShipMassFrameBuilder#add", at = "double x = contributor.getX();",
            replacedBy = "double x = contributor.getX() + 0.5D;", fails = "a symmetric hull balances at its middle",
            on = "2026-10-17")
    @RedWitness(verdict = "pulled", member = "ShipMassFrameBuilder#add", at = "sx += m * x;",
            replacedBy = "sx += contributor.getKind().name().equals(\"CONTENT\") ? 0.0D : m * x;",
            fails = "cargo on one side must pull the centre of mass that way", on = "2026-10-17")
    @Test
    public void cargoLoadedToOneSideMovesTheCentreOfMass() {
        ShipMassFrame empty = symmetricPair(500);
        ShipMassFrame loaded = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 400, Kind.CONTENT))
                .build();

        assertEquals("a symmetric hull balances at its middle", 0.0D,
                empty.getCentreOfMass().x(), EPS);
        assertTrue("cargo on one side must pull the centre of mass that way",
                loaded.getCentreOfMass().x() > empty.getCentreOfMass().x() + 1.0e-6D);
    }

    @RedWitness(member = "ShipMassFrameBuilder#add", at = "double x = contributor.getX();",
            replacedBy = "double x = contributor.getX() + 0.5D;",
            fails = "mass added exactly at the centre of mass must not move it", on = "2026-10-17")
    @Test
    public void massAddedAtTheCentreOfMassDoesNotMoveIt() {
        ShipMassFrame before = symmetricPair(500);
        Vector3dc com = before.getCentreOfMass();

        ShipMassFrame after = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.of(com.x(), com.y(), com.z(), 2000, 0.5D, Kind.CONTENT))
                .build();

        assertSameVector("mass added exactly at the centre of mass must not move it",
                com, after.getCentreOfMass(), EPS);
    }

    @RedWitness(verdict = "more mass", member = "ShipMassFrame#getTotalMass",
            at = "return structuralMass + contentMass + crewMass;", replacedBy = "return structuralMass + crewMass;",
            fails = "more cargo must mean more mass", on = "2026-10-17")
    @RedWitness(verdict = "structure untouched", member = "ShipMassFrameBuilder#add", at = "contentMass += m;",
            replacedBy = "structuralMass += m;", fails = "structure is untouched by what is loaded into the ship",
            on = "2026-10-17")
    @Test
    public void loadingCargoStrictlyIncreasesTotalMassAndNeverThrustLikeQuantities() {
        ShipMassFrame light = symmetricPair(500);
        ShipMassFrame heavy = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.0D, 1.0D, 0.0D, 750, Kind.CONTENT))
                .build();

        assertTrue("more cargo must mean more mass, with no ceiling folded into the model",
                heavy.getTotalMass() > light.getTotalMass());
        assertEquals("structure is untouched by what is loaded into the ship",
                light.getStructuralMass(), heavy.getStructuralMass(), EPS);
    }

    @LegacyWitness(prose = "one break per verdict, 2026-09-30 — the negative mass is refused TWICE — clamped"
            + " by {@code MassContributor#ofBlock} at {@code return new MassContributor(x, y, z, Math.max(0.0D, mass), BLOCK_EXTENT, kind);} and dropped by {@code ShipMassFrameBuilder#add} at {@code if (m <= 0.0D)} — and only with BOTH"
            + " removed does \"must not subtract from the hull\" fail (100.0); either alone stays green (the"
            + " builder's guard alone was tried 2026-09-29). {@code ShipMassFrameBuilder#add} at {@code double x = contributor.getX();} reading a block's X"
            + " at its face fails \"nor drag the centre of mass toward it\" (0.5).")
    @RedWitness(verdict = "the centre", member = "ShipMassFrameBuilder#add", at = "double x = contributor.getX();",
            replacedBy = "double x = contributor.getX() + 0.5D;", fails = "nor drag the centre of mass toward it",
            on = "2026-10-17")
    @Test
    public void negativeContributionsCannotCancelPartOfTheShip() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(5.0D, 0.0D, 0.0D, -400, Kind.CONTENT))
                .build();

        assertEquals("a negative mass must not subtract from the hull", 500.0D,
                frame.getTotalMass(), EPS);
        assertEquals("nor drag the centre of mass toward it", 0.0D,
                frame.getCentreOfMass().x(), EPS);
    }

    @RedWitness(member = "ShipMassFrameBuilder#build", at = "dxy, dyy, dyz,", replacedBy = "0.0D, dyy, dyz,",
            fails = "an inertia tensor equals its own transpose", on = "2026-10-17")
    @Test
    public void inertiaIsSymmetric() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(2.0D, 0.0D, 1.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(-1.0D, 3.0D, 0.0D, 700, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.0D, -2.0D, 4.0D, 300, Kind.CONTENT))
                .build();

        assertSameTensor("an inertia tensor equals its own transpose",
                frame.getInertia(), new Matrix3d(frame.getInertia()).transpose());
    }

    @RedWitness(member = "ShipMassFrameBuilder#add", at = "double own = m * (s * s) / 6.0D;", replacedBy = "double own = 0.0D;",
            fails = "the solver inverts this tensor every step", on = "2026-10-17")
    @Test
    public void aSingleBlockHullStillHasAnInvertibleInertia() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .build();

        assertInvertible(frame);
    }

    @RedWitness(member = "ShipMassFrameBuilder#add", at = "double own = m * (s * s) / 6.0D;", replacedBy = "double own = 0.0D;",
            fails = "the solver inverts this tensor every step", on = "2026-10-17")
    @Test
    public void aCollinearHullStillHasAnInvertibleInertia() {
        // A mast: every block on one line. A point-mass model gives this a zero moment about the
        // line, and the solver inverts the tensor every step.
        ShipMassFrameBuilder builder = new ShipMassFrameBuilder();
        for (int y = 0; y < 12; y++) {
            builder.add(MassContributor.ofBlock(0.0D, y, 0.0D, 500, Kind.STRUCTURAL));
        }
        assertInvertible(builder.build());
    }

    @RedWitness(verdict = "weighs nothing", member = "ShipMassFrame#empty", at = "new ShipMassFrame(0.0D, 0.0D, 0.0D,",
            replacedBy = "new ShipMassFrame(1.0D, 0.0D, 0.0D,", fails = "an empty frame weighs nothing", on = "2026-10-17")
    @RedWitness(verdict = "the origin", member = "ShipMassFrameBuilder#build", at = "if (total <= 0.0D)",
            replacedBy = "if (false)", fails = "an empty frame's centre is the origin, not a 0/0", on = "2026-10-17")
    @Test
    public void anEmptyFrameIsWellFormedRatherThanUndefined() {
        ShipMassFrame frame = new ShipMassFrameBuilder().build();

        assertEquals("an empty frame weighs nothing", 0.0D, frame.getTotalMass(), EPS);
        assertSameVector("an empty frame's centre is the origin, not a 0/0",
                new Vector3d(), frame.getCentreOfMass(), EPS);
    }

    @RedWitness(verdict = "the total", member = "ShipMassFrameBuilder#add", at = "contentMass += m;",
            replacedBy = "contentMass += structuralMass > 0.0D ? m : 0.0D;", fails = "the total must not depend on the order",
            on = "2026-10-17")
    @RedWitness(verdict = "the centre", member = "ShipMassFrameBuilder#add", at = "sx += m * x;", replacedBy = "sx = m * x;",
            fails = "the centre must not depend on the order", on = "2026-10-17")
    @RedWitness(verdict = "the tensor", member = "ShipMassFrameBuilder#add", at = "ixx += own + m * (y * y + z * z);",
            replacedBy = "ixx = own + m * (y * y + z * z);", fails = "the tensor must not depend on the order",
            on = "2026-10-17")
    @Test
    public void theOrderContributorsArriveInDoesNotChangeTheShip() {
        // Block iteration order is an accident of how chunks are walked; handling must not depend on it.
        ShipMassFrame forwards = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(3.0D, 1.0D, 0.0D, 200, Kind.CONTENT))
                .add(MassContributor.of(1.0D, 2.0D, 0.0D, 90, 0.6D, Kind.CREW))
                .build();
        ShipMassFrame backwards = new ShipMassFrameBuilder()
                .add(MassContributor.of(1.0D, 2.0D, 0.0D, 90, 0.6D, Kind.CREW))
                .add(MassContributor.ofBlock(3.0D, 1.0D, 0.0D, 200, Kind.CONTENT))
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .build();

        assertEquals("the total must not depend on the order",
                forwards.getTotalMass(), backwards.getTotalMass(), EPS);
        assertSameVector("the centre must not depend on the order",
                forwards.getCentreOfMass(), backwards.getCentreOfMass(), EPS);
        assertSameTensor("the tensor must not depend on the order",
                forwards.getInertia(), backwards.getInertia());
    }

    @RedWitness(member = "ShipMassFrameBuilder#add", at = "iyy += own + m * (x * x + z * z);",
            replacedBy = "iyy += own + m * (y * y + z * z);", fails = "about the long axis must be the cheapest rotation",
            on = "2026-10-17")
    @Test
    public void aLongHullResistsRollingLessThanYawing() {
        // A property a player feels: a needle-shaped ship spins about its long axis far more readily
        // than it swings its nose. If this ever inverts, the tensor axes have been transposed.
        ShipMassFrameBuilder builder = new ShipMassFrameBuilder();
        for (int x = -10; x <= 10; x++) {
            builder.add(MassContributor.ofBlock(x, 0.0D, 0.0D, 500, Kind.STRUCTURAL));
        }
        Matrix3d i = new Matrix3d(builder.build().getInertia());

        assertTrue("about the long axis must be the cheapest rotation: " + i,
                i.m00() < i.m11() && i.m00() < i.m22());
    }

    /**
     * One verdict, not an entry-by-entry sweep of the inverse: a finite matrix with a finite non-zero
     * determinant has a finite inverse (adjugate over determinant), and a NaN or infinite entry makes
     * the determinant NaN or infinite, which this refuses. The bar is the writer's own: it refuses a
     * tensor with {@code |det| <= 1e-9} ({@code ShipInertiaWriter.isInvertible}), so a frame below it
     * never reaches the solver at all.
     */
    private static void assertInvertible(ShipMassFrame frame) {
        double det = new Matrix3d(frame.getInertia()).determinant();
        assertTrue("the solver inverts this tensor every step, so a zero determinant is a NaN torque,"
                + " not a rounding error (got " + det + ")",
                !Double.isNaN(det) && !Double.isInfinite(det) && Math.abs(det) > 1.0e-9D);
    }

    private static void assertSameVector(String what, Vector3dc expected, Vector3dc actual, double eps) {
        assertEquals(what + ": expected " + expected + ", was " + actual, 0.0D, expected.distance(actual), eps);
    }

    private static void assertSameTensor(String what, Matrix3dc expected, Matrix3dc actual) {
        assertTrue(what + ": expected " + expected + ", was " + actual,
                new Matrix3d(expected).equals(actual, EPS));
    }
}
