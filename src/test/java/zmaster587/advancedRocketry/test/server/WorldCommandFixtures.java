package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.DimList;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;

import java.util.List;

/**
 * shared command-invocation + result-readback helpers for the
 * {@code /ar} (WorldCommand) test suites. Keeps each test class small
 * by absorbing the duplicated <em>"run a command then read state back"</em>
 * boilerplate.
 *
 * <p>Result-readback strategy: prefer {@code /artest planet info <dim>}
 * (independent reader, JSON output) over re-reading via {@code /ar planet get}
 * (shares its codepath with {@code /ar planet set} — same impl reading
 * the same field, so they'd agree-but-be-wrong on a shared bug).
 * {@code /ar planet get} is checked for its own contract once, then we
 * trust the independent JSON readback everywhere else.</p>
 *
 * <p>Package-private — only the {@code /ar} test classes need it.</p>
 */
final class WorldCommandFixtures {

    private WorldCommandFixtures() {}

    /** Send a command via the shared {@link AbstractSharedServerTest}
     *  harness and return the concatenated console response. */
    static String exec(String cmd) throws Exception {
        return String.join("\n", AbstractSharedServerTest.client().execute(cmd));
    }

    /**
     * What time it is in the GAME, asked of the server.
     *
     * <p>The server's own tick counter. A test that needs to know how long it is willing to wait for
     * something asks here rather than looking at a watch.</p>
     *
     * <p>A DELEGATE. The implementation lives in the public {@link GameTicks}, because this class
     * is package-private and bound to the shared-server harness while most of the suite cannot
     * reach it — and because two readers of one clock is exactly one too many. What stays here is
     * the vocabulary: the name reads better at a {@code /ar} call site.</p>
     */
    static long serverTick() throws Exception {
        return GameTicks.read(AbstractSharedServerTest.client(), GameTicks.server());
    }

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
    static String awaitEnteredSpace(zmaster587.advancedRocketry.test.Events events, long mark,
                                    String durableShipId, String what, int tickBudget,
                                    zmaster587.advancedRocketry.test.Events.Stimulus stimulus)
            throws Exception {
        return events.awaitField(mark, "ship_left_planet", "ship", durableShipId,
                what, tickBudget, stimulus);
    }

    /** Read an integer field out of {@code /artest planet info <dim>}
     *  JSON. Asserts the field is present (matcher must find). */
    static int planetIntField(int dim, String field) throws Exception {
        return Integer.parseInt(matchOrThrow(planetInfo(dim), field));
    }

    /** Read a float/double field out of {@code /artest planet info <dim>}. */
    static double planetFloatField(int dim, String field) throws Exception {
        return Double.parseDouble(matchOrThrow(planetInfo(dim), field));
    }

    /** True iff AR's planet registry knows the given dim, observed via
     *  {@code /ar planet list} (which iterates {@code getRegisteredDimensions()}
     *  &rarr; the underlying {@code dimensionList} keyset). Cannot use
     *  {@code /artest planet info} here because
     *  {@code DimensionManager.getDimensionProperties} falls back to
     *  {@code overworldProperties} for unknown dims (line 539), so the
     *  info probe is incapable of distinguishing "registered" from
     *  "absent" by itself. */
    static boolean planetExists(int dim) throws Exception {
        // Asked of the DATA that answers the same question. The comment above rules out
        // `planet info`, and rightly — but `artest dim list` reports
        // `DimensionManager.getRegisteredDimensions()`, which is the very collection
        // `/ar planet list` iterates, and it reports it as a list of integers. The chat form
        // needed the trailing colon to stop `DIM9` matching `DIM90`, which is a bound a reader
        // does not have to remember.
        return DimList.from(WorldCommandFixtures::exec).holds(dim);
    }

    private static String planetInfo(int dim) throws Exception {
        return exec("artest planet info " + dim);
    }

    /**
     * One field of a planet-info reply, as text.
     *
     * <p>It used to build a regex out of a TEMPLATE — {@code "\"%s\":(-?\\d+)"} with the field name
     * formatted into it — one template per Java type, so the reader's answer depended on which
     * template the caller picked as well as on what the probe wrote. A field read by name needs
     * neither.</p>
     */
    private static String matchOrThrow(String src, String field) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        String value = Reply.of("artest planet info", src).textOr(field, null);
        if (value == null) {
            throw new AssertionError("field \"" + field + "\" not found in: " + src);
        }
        return value;
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
