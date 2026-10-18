package dev.stannismod.stellurgy.ship.control;

/**
 * What one physics step is allocated under, beside the hull itself: the propellant it may eject
 * (C10 STAT-39), the pressure outside (STAT-31, STAT-43), and whether the ship-wide switch lets the
 * allocator use boost columns (STAT-42). Immutable: one step, one set of conditions.
 */
public final class StepConditions {

    private final PropellantBudget budget;
    private final double ambientPascals;
    private final boolean boost;

    private StepConditions(PropellantBudget budget, double ambientPascals, boolean boost) {
        this.budget = budget;
        this.ambientPascals = ambientPascals;
        this.boost = boost;
    }

    /**
     * @param ambientPascals the pressure outside the drives this step, Pa; NaN for "the pressure the
     *                       capability was solved at"
     */
    public static StepConditions of(PropellantBudget budget, double ambientPascals, boolean boost) {
        return new StepConditions(budget, ambientPascals, boost);
    }

    /**
     * Nothing metered, the cruise columns only, at the pressure the capability was solved at — the
     * conditions in which an allocation is a question about the hull's geometry alone.
     */
    public static StepConditions unmeteredCruiseAsSolved() {
        return new StepConditions(PropellantBudget.unmetered(), Double.NaN, false);
    }

    public PropellantBudget budget() {
        return budget;
    }

    /** Pa; NaN for the pressure the capability was solved at. */
    public double ambientPascals() {
        return ambientPascals;
    }

    public boolean boost() {
        return boost;
    }
}
