package dev.stannismod.stellurgy.ship.control;

/**
 * Every numerical tolerance the allocation compares against, in one place so that none of them is a
 * magic number standing in a formula somewhere. `tunable`, each with the reason for its size.
 *
 * <p>The residual tolerances are RELATIVE to the hull's own largest actuator figure, because the
 * same absolute newton is noise on a cruiser and a real error on a shuttle.</p>
 */
final class AllocationTolerances {

    private AllocationTolerances() {}

    /**
     * Unwanted force left in a clean recipe, as a fraction of the largest single actuator force.
     * Measured 2026-09-30 over 500 randomised layouts: the worst residual is 1.9e-15 of that scale
     * (torque 6.6e-16), so this is nine orders of slack for rounding and still far below any push a
     * player could feel.
     */
    static final double FORCE_RESIDUAL = 1.0e-6D;

    /** Unwanted torque left in a clean recipe, relative to the largest torque column. Same measurement. */
    static final double TORQUE_RESIDUAL = 1.0e-6D;

    /**
     * Below this magnitude a scaled tableau entry is zero. The rows are scaled to order one before
     * solving, so this is a statement about double precision, not about hulls.
     */
    static final double PIVOT = 1.0e-10D;

    /** A bound Bland's rule makes unreachable; reaching it means the arithmetic broke down. */
    static final int MAX_ITERATIONS = 20_000;

    /** A hull lighter than this, in kilograms, has no meaningful acceleration and is given no authority. */
    static final double MASS_EPSILON = 1.0e-6D;
}
