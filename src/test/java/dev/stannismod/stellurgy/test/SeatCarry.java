package dev.stannismod.stellurgy.test;

import java.util.List;

/**
 * "The seated rider travels with his ship", read off the client's own {@code seat_carried} records
 * — the rider's, the seat's and the craft's shown height, written in ONE call per tick
 * ({@code MixinEntityDummyCarryEvents}).
 *
 * <p><b>Why a reader and not a wait.</b> The claim is a RELATION between two moving things, and it
 * was asked as a server read of the ship and a client read of the rider, six client ticks after the
 * key came up: two processes, two clocks, and an offset whose meaning moved with the speed of the
 * box. Here both halves of every sample come from the same tick of the same process, so the verdict
 * is about where the client put the rider relative to where it showed his ship, and about nothing
 * else.</p>
 *
 * <p><b>What it does not claim.</b> That the client SHOWS the ship where the server has it — pose
 * replication is its own mechanic with its own record ({@code client_deck_pose_tick}). A scenario
 * that needs the ship to have moved on the server asserts that on the server, as an arrangement.</p>
 */
public final class SeatCarry {

    /** How many samples the window held for this ship. */
    public final int samples;
    /** The ship's shown climb, last sample minus first, in blocks. */
    public final double shipClimb;
    /** The rider's (the player's) climb over the same samples. */
    public final double riderClimb;
    /** The seat's climb over the same samples. */
    public final double seatClimb;
    /**
     * The largest change, over the window, in how far the rider sits from his ship's shown pose,
     * in blocks: {@code max |(riderY - shipY) - (first riderY - first shipY)|}. A rider glued to his
     * seat on a ship that carries the seat holds that offset at every tick.
     */
    public final double riderDrift;
    /** The same quantity for the seat. */
    public final double seatDrift;
    /** The records it was computed from, for a failure message. */
    public final String raw;

    private SeatCarry(int samples, double shipClimb, double riderClimb, double seatClimb,
                      double riderDrift, double seatDrift, String raw) {
        this.samples = samples;
        this.shipClimb = shipClimb;
        this.riderClimb = riderClimb;
        this.seatClimb = seatClimb;
        this.riderDrift = riderDrift;
        this.seatDrift = seatDrift;
        this.raw = raw;
    }

    /**
     * Wait until the client has SHOWN {@code shipId} climb by more than {@code minShownClimb} since
     * {@code mark}, while carrying a seated rider on it, and read the window up to that sample.
     *
     * <p>A LINK, and the end of the window is the record that satisfied it: the reading never
     * depends on how long after the climb the test got round to reading. A ship the server lifted
     * and the client never showed lifting is a failure of THIS wait, which names the records it did
     * see.</p>
     *
     * @param clientLog the CLIENT's log
     * @param mark      a mark on that log taken BEFORE the stimulus that climbs the ship
     */
    public static SeatCarry awaitShownClimb(Events clientLog, long mark, String shipId,
                                            double minShownClimb, String what, int tickBudget)
            throws Exception {
        String reply = clientLog.awaitMatching(mark, "seat_carried",
                seen -> over(seen, shipId).shipClimb > minShownClimb,
                "carrying a rider on " + shipId + " shown climbing more than " + minShownClimb
                        + " blocks", what, tickBudget);
        Events.assertInstrumentRan(reply, "seat_carry_events",
                "the client recorded where it carried the seated rider");
        return over(reply, shipId);
    }

    /** The reading over every {@code seat_carried} record in {@code sinceReply} naming {@code shipId}. */
    public static SeatCarry over(String sinceReply, String shipId) {
        List<String> on = Events.recordsWhere(sinceReply, "ship", shipId);
        if (on.isEmpty()) {
            return new SeatCarry(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    sinceReply);
        }
        // Read bare inside the wait's predicate too: the producer always writes all three heights on
        // a record that names a ship, and only those are kept above.
        Reply first = Reply.of(on.get(0));
        Reply last = Reply.of(on.get(on.size() - 1));
        double riderOffset0 = first.number("riderY") - first.number("shipY");
        double seatOffset0 = first.number("seatY") - first.number("shipY");
        double riderDrift = 0.0;
        double seatDrift = 0.0;
        for (String record : on) {
            Reply r = Reply.of(record);
            double shipY = r.number("shipY");
            riderDrift = Math.max(riderDrift, Math.abs(r.number("riderY") - shipY - riderOffset0));
            seatDrift = Math.max(seatDrift, Math.abs(r.number("seatY") - shipY - seatOffset0));
        }
        return new SeatCarry(on.size(),
                last.number("shipY") - first.number("shipY"),
                last.number("riderY") - first.number("riderY"),
                last.number("seatY") - first.number("seatY"),
                riderDrift, seatDrift, String.valueOf(on));
    }

    @Override
    public String toString() {
        return "samples=" + samples + " shipClimb=" + shipClimb + " riderClimb=" + riderClimb
                + " seatClimb=" + seatClimb + " riderDrift=" + riderDrift + " seatDrift=" + seatDrift;
    }
}
