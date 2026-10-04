package dev.stannismod.stellurgy.test.server;

import java.util.List;

/**
 * Stateless helpers for the server suites that need no server of their own. The verbs that DO talk
 * to the server — {@code exec}, {@code serverTick}, the planet-info readers — are instance methods of
 * {@link AbstractSharedServerTest}, because the server they talk to is the class run's.
 *
 * <p>Result-readback strategy for the {@code /ar} suites: prefer {@code /stellurgytest planet info <dim>}
 * (independent reader, JSON output) over re-reading via {@code /ar planet get}
 * (shares its codepath with {@code /ar planet set} — same impl reading
 * the same field, so they'd agree-but-be-wrong on a shared bug).
 * {@code /ar planet get} is checked for its own contract once, then we
 * trust the independent JSON readback everywhere else.</p>
 *
 * <p>Package-private — only the server suites need it.</p>
 */
final class WorldCommandFixtures {

    private WorldCommandFixtures() {}

    /**
     * Wait for ONE craft to finish entering space, on the record production publishes for it.
     *
     * <p>{@code ShipEntryController} posts {@code ShipCrossingEvent.LeftPlanet} in its
     * {@code settled} callback, on the line after {@code ledger.settle(...)} — so the record and the
     * ledger row the six call sites used to poll for are the SAME moment, not two things that
     * usually agree. The record carries the craft's durable id, so the wait is about THIS craft:
     * the ledger's bare form answers with whichever row it iterates first, and a slot holding two
     * craft satisfied "somebody settled" with a neighbour's state.
     *
     * <p><b>The mark is the CALLER's, and it is taken before the act that starts the entry</b> —
     * the unpark, the takeoff command. That is why this helper does not take its own: the record is
     * written once, at the settle, and a mark taken after it has already been written is a wait for
     * a second entry that is never going to happen. A helper that marked for you would hide exactly
     * the ordering a reader of this wait has to get right.
     *
     * <p><b>What this cannot see</b>, and the poll could: the event is skipped, with a warning in
     * the server log, when the destination slot world is not loaded at the instant of settle
     * ({@code ShipEntryController} guards on {@code DimensionManager.getWorld(slotDim) == null}).
     * The crossing has just pasted the hull into that world, so no production caller has been found
     * that reaches it — but if this wait ever times out while the ledger says SETTLED, that guard is
     * the first place to look and this sentence is why.
     *
     * @param stimulus arrangement the wait must keep re-applying — on a headless server there is no
     *                 player to keep the destination slots' ships load-queued. Not the observation:
     *                 what decides is still the record.
     * @return the record that ended the wait, for a caller that wants a field of what happened
     */
    static String awaitEnteredSpace(dev.stannismod.stellurgy.test.Events events, long mark,
                                    String durableShipId, String what, int tickBudget,
                                    dev.stannismod.stellurgy.test.Events.Stimulus stimulus)
            throws Exception {
        return events.awaitField(mark, "ship_left_planet", "ship", durableShipId,
                what, tickBudget, stimulus);
    }

    /** First line that contains the substring, or {@code null}. Useful
     *  for chat-output assertions that don't pin exact wording. */
    static String firstLineContaining(List<String> lines, String needle) {
        for (String l : lines) {
            if (l.contains(needle)) return l;
        }
        return null;
    }
}
