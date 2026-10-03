package dev.stannismod.stellurgy.integration.vs;

import javax.annotation.Nullable;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.common.ships.physics_data.ShipInertiaData;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;

/**
 * The one place that writes a mass frame into the physics engine's own record, and the only place in
 * the mass model that holds physics-engine types.
 *
 * <h2>Why a writer and not just a provider</h2>
 *
 * <p>The engine keeps mass, centre of mass and the inertia tensor in one record that it re-reads
 * every physics tick, and it maintains that record as an <b>accumulator</b>: the paste path feeds it
 * one block at a time, a per-block hook applies deltas for the rest of the craft's life, and a world
 * load restores whatever number was serialized. The hull is never rescanned. So authoritative writes
 * are picked up for free — the incremental deltas are merely one way to produce those three numbers,
 * not a channel to fight.</p>
 *
 * <p>Writing a fresh centre of mass mid-flight is safe: the physics loop shifts the body's origin by
 * the same vector rotated into world space, so the craft does not jump when the centre moves.</p>
 *
 * <h2>Delta by default, recompute as the authority, disagreement REPORTED</h2>
 *
 * <p>{@link #applyTo} is the authority — a whole frame, computed from the hull, written as three fields
 * together. {@link #compare} is the instrument that says whether the cheap incremental path has
 * drifted from it, and it <b>does not correct anything</b>. That is deliberate and it is the point:
 * a reconciliation that silently substitutes the right number turns a safety net into normal
 * operation and destroys the only signal that a trigger is missing. The repair for drift is the
 * missing trigger; the report is what makes it findable.</p>
 *
 * <h2>The tensor may never be singular</h2>
 *
 * <p>The physics loop inverts the tensor every tick, so a degenerate one is a NaN torque rather than
 * a rounding error — a single cube or a perfectly straight mast is enough to produce one. The frame
 * handed here is expected to be regularised already (its builder smears each contributor over its
 * own extent); this class refuses a frame whose tensor cannot be inverted rather than passing it on,
 * because a crash inside the physics tick names neither the ship nor the writer.</p>
 */
public final class ShipInertiaWriter {

    private ShipInertiaWriter() {}

    /**
     * Obtained straight from log4j rather than through the mod class. Reaching for the mod's own
     * static logger initialises the mod, which initialises the block registry, which refuses to load
     * before the game has bootstrapped — so a boundary class that logged that way could not be
     * exercised outside a running server, and the rules it enforces are exactly the kind that must be.
     */
    private static final Logger LOG = LogManager.getLogger("stellurgy.mass");

    /** Relative disagreement in total mass above which drift is worth reporting. */
    private static final double MASS_TOLERANCE = 0.01;

    /** Absolute disagreement in the centre of mass, in blocks, above which drift is worth reporting. */
    private static final double CENTRE_TOLERANCE = 0.05;

    /**
     * Write {@code frame} into {@code record}. All three fields go together: writing mass without the
     * centre, or the centre without the tensor, leaves the engine integrating a body that never existed.
     * Against the record rather than against a world, and public, because the rules it
     * enforces — all three fields together, a singular tensor refused — have to be reachable without a
     * running server: the record is a plain data holder, so a test can hold one, and a rule nothing can
     * exercise is a rule nothing keeps.
     */
    public static boolean applyTo(ShipInertiaData record, ShipMassFrame frame, String shipName) {
        Matrix3d tensor = new Matrix3d(frame.getInertia());
        if (!isInvertible(tensor)) {
            // Refused HERE, where the ship's name is still in hand. The same frame accepted would
            // crash inside the physics tick as a NaN torque, naming neither the craft nor the writer.
            LOG.error("refusing a singular inertia tensor for ship " + shipName
                    + "; the physics tick inverts it every step. frame=" + frame);
            return false;
        }
        record.setGameTickMass(frame.getTotalMass());
        record.setGameTickCenterOfMass(new Vector3d(frame.getCentreOfMass()));
        record.setGameMoITensor(tensor);
        return true;
    }

    /**
     * The comparison, against the record rather than against a world, and WITHOUT reporting: returns
     * the drift or {@code null} when there is none. Reporting is the caller's, so the tolerances can
     * be pinned by a test that is not also asserting how loudly a build complains about them.
     */
    @Nullable
    public static Drift compare(ShipInertiaData record, ShipMassFrame authority, String shipName) {
        double recorded = record.getGameTickMass();
        double expected = authority.getTotalMass();
        // Relative on mass, because the tolerance has to mean the same thing for a shuttle and for a
        // capital hull; absolute on the centre, because a tenth of a block is a tenth of a block.
        double massScale = Math.max(Math.abs(expected), 1.0);
        double centreError = new Vector3d(authority.getCentreOfMass())
                .sub(new Vector3d(record.getGameTickCenterOfMass())).length();
        if (Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE) {
            return null;
        }
        return new Drift(shipName, recorded, expected, (recorded - expected) / massScale, centreError);
    }

    /**
     * A disagreement between the physics record and the authoritative frame, as numbers.
     *
     * <p>SIGN is part of it, not just magnitude: a record that is consistently light points at removals
     * that were never applied, a heavy one at additions counted twice, and those are different missing
     * triggers. The text form is for the log; a reader that wants the sign asks for the number.</p>
     */
    public static final class Drift {
        public final String shipName;
        public final double recordedMass;
        public final double authorityMass;
        /** {@code (recorded - authority) / max(|authority|, 1)}: negative for a light record. */
        public final double relativeMassError;
        /** How far the recorded centre of mass sits from the authority's, in blocks. */
        public final double centreOffBlocks;

        Drift(String shipName, double recordedMass, double authorityMass, double relativeMassError,
              double centreOffBlocks) {
            this.shipName = shipName;
            this.recordedMass = recordedMass;
            this.authorityMass = authorityMass;
            this.relativeMassError = relativeMassError;
            this.centreOffBlocks = centreOffBlocks;
        }

        @Override
        public String toString() {
            return "ship " + shipName + " inertia drift: mass recorded " + recordedMass
                    + " vs authority " + authorityMass + " ("
                    + String.format("%+.2f%%", 100.0 * relativeMassError)
                    + "), centre off by " + String.format("%.4f", centreOffBlocks) + " blocks";
        }
    }

    /**
     * Whether the tensor has an inverse. Checked by determinant rather than by attempting the inverse,
     * because an inversion of a singular matrix yields NaNs instead of failing.
     */
    private static boolean isInvertible(Matrix3d tensor) {
        double det = tensor.determinant();
        return !Double.isNaN(det) && !Double.isInfinite(det) && Math.abs(det) > 1.0e-9;
    }
}
