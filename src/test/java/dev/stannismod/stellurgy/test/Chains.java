package dev.stannismod.stellurgy.test;

/**
 * The chains this project's contracts ARE, in the order production commits their links — what a test
 * hands to {@link Events#assertChain}.
 *
 * <p>Each link is recorded by a test-only mixin at the seam where production performs it; none is
 * emitted by production. The order in each chain was MEASURED on a green run and, where the first
 * draft had it wrong, corrected by the chain's own order failure — so an order here is a fact about
 * the code, not a diagram of it.</p>
 *
 * <p>Each chain is a method returning a fresh array, not a shared one: an array is mutable, so a
 * shared one would let any caller rewrite a contract for every caller after it.</p>
 */
public final class Chains {

    private Chains() {}

    /**
     * A piloted hyperspace jump: the crew is picked up, the hull is cut into the lane, the departure is
     * committed, the crew is seated on the parked hull for the flight, the hull is cut into its
     * destination, the crew is put back on it, and only then is the arrival committed.
     */
    public static String[] pilotedJump() {
        return new String[] {
                "crew_captured", "hyperspace_depart_cut", "transit_departed", "crew_boarded_parked_hull",
                "hyperspace_arrival_cut", "crew_reseated", "transit_settled"};
    }

    /**
     * A GRANTED space entry: the hull is cut into the cell, THEN the gate records its decision
     * ({@code STARTED} is decided by the cut having succeeded), the pose is written on the arrived
     * hull, the crew is put back on it, and the ledger is told where the ship now is.
     */
    public static String[] grantedEntry() {
        return new String[] {
                "cell_crossing_begun", "entry_decided", "crossing_pose_settled", "crossing_crew_reseated",
                "ledger_settled"};
    }

    /**
     * A GRANTED descent: the gate grants, the ship leaves the ledger (it is no longer in space), the
     * hull is cut onto the planet, its pose is written, and the crew is put back on it. There is no
     * ledger write at the end — a ship on a planet is in no cell.
     */
    public static String[] grantedDescent() {
        return new String[] {
                "descent_requested", "ledger_removed", "cell_crossing_begun", "crossing_pose_settled",
                "crossing_crew_reseated"};
    }
}
