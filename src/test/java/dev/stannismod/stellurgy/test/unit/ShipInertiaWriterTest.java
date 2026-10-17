package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.integration.vs.ShipInertiaWriter;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.junit.Test;
import org.valkyrienskies.mod.common.ships.physics_data.ShipInertiaData;

import static org.junit.Assert.*;

/**
 * The rules the mass seam owes the physics engine, pinned against the engine's own record — which is a
 * plain data holder, so no server is needed to hold one.
 *
 * <p>These are contracts, not arithmetic: the three fields move together, a tensor the physics tick
 * could not invert is refused before it gets there, and a disagreement between the cheap incremental
 * path and the authoritative recompute is REPORTED rather than quietly corrected.</p>
 */
public class ShipInertiaWriterTest {

    /** Slack for rounding only: measured 2026-09-30 with it at zero, every verdict here is exact. */
    private static final double ROUNDING = 1e-9;

    /** A frame with a genuinely three-dimensional mass distribution, so its tensor is invertible. */
    private static ShipMassFrame hull() {
        return new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0, 0, 0, 1000.0, MassContributor.Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(4, 0, 0, 1000.0, MassContributor.Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0, 3, 0, 1000.0, MassContributor.Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0, 0, 2, 1000.0, MassContributor.Kind.STRUCTURAL))
                .build();
    }

    @RedWitness(verdict = "accepted", member = "ShipInertiaWriter#applyTo", at = "if (!isInvertible(tensor))",
            replacedBy = "if (true)", fails = "a well-formed frame must be accepted", on = "2026-10-17")
    @RedWitness(verdict = "mass", member = "ShipInertiaWriter#applyTo", at = "record.setGameTickMass(frame.getTotalMass());",
            replacedBy = "", fails = "total mass must be the frame's", on = "2026-10-17")
    @RedWitness(verdict = "centre", member = "ShipInertiaWriter#applyTo",
            at = "record.setGameTickCenterOfMass(new Vector3d(frame.getCentreOfMass()));", replacedBy = "",
            fails = "centre of mass must be the frame's", on = "2026-10-17")
    @RedWitness(verdict = "tensor", member = "ShipInertiaWriter#applyTo", at = "record.setGameMoITensor(tensor);",
            replacedBy = "", fails = "the tensor must be the frame's", on = "2026-10-17")
    @Test
    public void aWriteMovesMassCentreAndTensorTogether() {
        // Any consumer that saw one of the three updated and not the others would be integrating a body
        // that never existed: a mass that belongs to one hull about the centre of another.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();

        assertTrue("a well-formed frame must be accepted",
                ShipInertiaWriter.applyTo(record, frame, "unit-hull"));

        assertEquals("total mass must be the frame's", frame.getTotalMass(),
                record.getGameTickMass(), ROUNDING);
        assertEquals("centre of mass must be the frame's", 0.0,
                new Vector3d(frame.getCentreOfMass())
                        .sub(new Vector3d(record.getGameTickCenterOfMass())).length(), ROUNDING);
        assertEquals("the tensor must be the frame's", new Matrix3d(frame.getInertia()),
                new Matrix3d(record.getGameMoITensor()));
    }

    @RedWitness(verdict = "refused", member = "ShipInertiaWriter#isInvertible",
            at = "return !Double.isNaN(det) && !Double.isInfinite(det) && Math.abs(det) > 1.0e-9;",
            replacedBy = "return true;", fails = "must be refused", on = "2026-10-17")
    @RedWitness(verdict = "untouched", member = "ShipInertiaWriter#applyTo",
            at = "Matrix3d tensor = new Matrix3d(frame.getInertia());",
            replacedBy = "record.setGameTickMass(frame.getTotalMass()); Matrix3d tensor = new Matrix3d(frame.getInertia());",
            fails = "must leave the record untouched", on = "2026-10-17")
    @Test
    public void aSingularTensorIsRefusedBeforeItReachesThePhysicsTick() {
        // The physics loop inverts the tensor every step, so a degenerate one is a NaN torque rather
        // than a rounding error. Refusing it here keeps the ship's name attached to the complaint; a
        // crash inside the tick names neither the craft nor the writer.
        ShipInertiaData record = new ShipInertiaData();
        double massBefore = record.getGameTickMass();

        // A single point of mass: no extent in any direction, so the tensor is identically zero -
        // exactly the shape the physics tick cannot invert.
        ShipMassFrame degenerate = new ShipMassFrameBuilder()
                .add(MassContributor.of(0, 0, 0, 7.0, 0.0, MassContributor.Kind.STRUCTURAL))
                .build();

        assertFalse("a tensor the physics tick cannot invert must be refused",
                ShipInertiaWriter.applyTo(record, degenerate, "unit-degenerate"));
        assertEquals("a refused write must leave the record untouched, not half-written",
                massBefore, record.getGameTickMass(), 0.0);
    }

    /**
     * <p>The control's own witness: with {@code ShipInertiaWriter#compare} at {@code return new Drift(shipName, recorded, expected, (recorded - expected) / massScale, centreError);}
     * answering null, the scenario stops at "must report the record once it is 20% light" — the
     * verdict alone would have stayed green — 2026-10-04.</p>
     */
    @RedWitness(member = "ShipInertiaWriter#compare",
            at = "if (Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE)",
            replacedBy = "if (!(Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE))",
            fails = "cannot be in drift", on = "2026-10-17")
    @Test
    public void aRecordThatAgreesWithTheAuthorityReportsNothing() {
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        assertNull("a record just written from the authority cannot be in drift",
                ShipInertiaWriter.compare(record, frame, "unit-hull"));
        // CONTROL, same record and the same comparison: once it is made wrong, it IS reported — or the
        // silence above would be a comparison that never reports anything.
        record.setGameTickMass(frame.getTotalMass() * 0.80);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the same comparison must report the record once it is 20% light",
                ShipInertiaWriter.compare(record, frame, "unit-hull") != null);
    }

    @RedWitness(verdict = "reported", member = "ShipInertiaWriter#compare",
            at = "if (Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE)",
            replacedBy = "if (!(Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE))",
            fails = "must be reported", on = "2026-10-17")
    @RedWitness(verdict = "names the ship", member = "ShipInertiaWriter#compare", at = "return new Drift(shipName,",
            replacedBy = "return new Drift(\"?\",", fails = "must name the ship", on = "2026-10-17")
    @RedWitness(verdict = "sign and magnitude", member = "ShipInertiaWriter#compare",
            at = "(recorded - expected) / massScale, centreError);", replacedBy = "(expected - recorded) / massScale, centreError);",
            fails = "-0.20 of the authority", on = "2026-10-17")
    @RedWitness(verdict = "left alone", member = "ShipInertiaWriter#compare", at = "return new Drift(shipName,",
            replacedBy = "record.setGameTickMass(expected); return new Drift(shipName,",
            fails = "must not repair the record", on = "2026-10-17")
    @Test
    public void driftIsReportedWithItsSignAndMagnitudeAndTheRecordIsLEFTALONE() {
        // The whole design of this reconciliation: it is an instrument, not a repair. Substituting the
        // right number here would turn a safety net into normal operation and destroy the only signal
        // that a trigger is missing — and the repair for drift is the missing trigger.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        double stale = frame.getTotalMass() * 0.80; // 20% light: removals that were never applied
        record.setGameTickMass(stale);

        ShipInertiaWriter.Drift drift = ShipInertiaWriter.compare(record, frame, "unit-hull");
        assertNotNull("a 20% mass disagreement must be reported", drift);
        assertEquals("the report must name the ship: " + drift, "unit-hull", drift.shipName);
        // Sign AND magnitude in one number: -0.20 is "20% light". A light record and a heavy one point
        // at different missing triggers, so a report that loses the sign loses the diagnosis.
        assertEquals("a 20% light record must be reported as -0.20 of the authority: " + drift,
                -0.20, drift.relativeMassError, ROUNDING);

        assertEquals("compare() must not repair the record it is describing",
                stale, record.getGameTickMass(), 0.0);
    }

    /**
     * <p>The control's own witness: with {@code ShipInertiaWriter#compare} at {@code return new Drift(shipName, recorded, expected, (recorded - expected) / massScale, centreError);}
     * answering null, the scenario stops at "must report a record five percent heavy" — 2026-10-04.</p>
     */
    @RedWitness(member = "ShipInertiaWriter#compare",
            at = "if (Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE)",
            replacedBy = "if (!(Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE))",
            fails = "half a percent is accumulation", on = "2026-10-17")
    @Test
    public void aDisagreementSmallerThanTheToleranceIsNotDrift() {
        // The tolerance is relative on mass so it means the same thing for a shuttle and a capital
        // hull. Floating-point accumulation over thousands of block events is not a defect.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        record.setGameTickMass(frame.getTotalMass() * 1.005); // half a percent heavy

        assertNull("half a percent is accumulation, not a missing trigger",
                ShipInertiaWriter.compare(record, frame, "unit-hull"));
        // CONTROL, same record: five percent is past the writer's one-percent MASS_TOLERANCE and IS
        // reported, so the silence
        // above is the tolerance deciding and not a comparison that never reports.
        record.setGameTickMass(frame.getTotalMass() * 1.05);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the same comparison must report a record five percent heavy",
                ShipInertiaWriter.compare(record, frame, "unit-hull") != null);
    }

    @RedWitness(verdict = "reported", member = "ShipInertiaWriter#compare", at = "&& centreError <= CENTRE_TOLERANCE)",
            replacedBy = ")", fails = "must be reported", on = "2026-10-17")
    @RedWitness(verdict = "how far", member = "ShipInertiaWriter#compare", at = "/ massScale, centreError);",
            replacedBy = "/ massScale, 2 * centreError);", fails = "how far the centre moved", on = "2026-10-17")
    @RedWitness(verdict = "not blamed on mass", member = "ShipInertiaWriter#compare",
            at = "(recorded - expected) / massScale, centreError);", replacedBy = "centreError, centreError);",
            fails = "must not blame it", on = "2026-10-17")
    @Test
    public void aCentreThatHasWalkedAwayIsDriftEvenWhenTheMassAgrees() {
        // Mass and centre fail independently: a block moved from bow to stern changes the centre and
        // not the total, and a reconciliation watching only mass would call that hull healthy.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        record.setGameTickCenterOfMass(
                new Vector3d(frame.getCentreOfMass()).add(0.0, 1.5, 0.0));

        ShipInertiaWriter.Drift drift = ShipInertiaWriter.compare(record, frame, "unit-hull");
        assertNotNull("a centre of mass a block and a half out must be reported", drift);
        assertEquals("the report must say how far the centre moved: " + drift,
                1.5, drift.centreOffBlocks, ROUNDING);
        assertEquals("the mass agreed, so the report must not blame it: " + drift,
                0.0, drift.relativeMassError, ROUNDING);
    }
}
