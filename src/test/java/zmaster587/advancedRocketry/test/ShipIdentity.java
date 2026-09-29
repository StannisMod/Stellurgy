package zmaster587.advancedRocketry.test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Asserting that a reading is about the ship the scenario MEANS, and not about a neighbour's.
 *
 * <p>Every harness-tier class that assembles a craft runs in a world it shares with its siblings —
 * one world per class, twelve to fourteen scenarios in the densest ones, each leaving a hull behind,
 * and a test server keeps every one of those hulls loaded. A reading that says "a ship holds this body" is
 * therefore satisfied byte-identically by a body resolved against somebody else's craft, and no
 * amount of tightening the flag itself changes that: the flag is not the part that is ambiguous.</p>
 *
 * <p><b>The disambiguator was already in the reply.</b> {@code ShipFrameTravel.explainHandles} puts
 * {@code anchorShipId} — the id of the ship actually holding the capture — into every
 * {@code artest vs deck-capture} answer. Measured 2026-09-06: 0 of the suite's 103 {@code
 * deck-capture} call sites read it, against 52 that assert on {@code alreadyTracked} /
 * {@code verdict} / {@code hullStand}. So this is not a new capability; it is spending one that was
 * already being thrown away.</p>
 *
 * <p><b>That reply is read by {@link DeckCapture}, not here</b> (2026-09-17): the anchor, the
 * containing hulls and the first-contact candidate are fields of ONE producer, and its reader owns
 * them together with the branch rules that say when each exists. What stays in this class is
 * identity BRIDGING — a durable name to a physics id and back — which is not one verb's answer.</p>
 *
 * <p>Kept out of any base class deliberately: the classes that need it sit under three different
 * bases ({@code AbstractSharedVsClientE2ETest}, {@code AbstractSpaceLoginRestoreClientTest}, the
 * harness's own per-method base), and a helper that only some of them can reach is how the same ten
 * lines end up copied three times.</p>
 */
public final class ShipIdentity {

    private ShipIdentity() {
    }

    /** How this helper reaches the probe. Every caller already has one; none of them share a base. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /**
     * The DURABLE ship id out of an {@code artest rocket assemble} reply — the ship's name, taken from
     * the moment that created it.
     *
     * <p>This is the honest start of a scenario's identity chain. The alternative in use across this
     * tier is {@code vs ship-info <dim> <x> <y> <z>} at the pad, which is a proximity lookup on a world
     * every sibling scenario also builds in: it answers with a real craft's id whether or not that
     * craft is this scenario's, and the two replies are byte-identical.</p>
     *
     * <p>Fails when the pad carried more than one flight computer — production reports {@code
     * afcCount} beside the id precisely so a caller can refuse an id that names one of two craft with
     * nothing saying which.</p>
     */
    public static String nameFromAssembly(String assembleReply) {
        // The READ is the refusal: `text` names the verb and prints the reply, which is what
        // the assertion that stood here used to carry by hand.
        String durableId = Reply.of("artest rocket assemble", String.valueOf(assembleReply))
                .text("shipId");
        assertTrue("the pad carried more than one flight computer, so the id names one of several"
                + " craft and the scenario cannot say which: " + assembleReply,
                (Reply.of(assembleReply).integer("afcCount") == 1));
        return durableId;
    }

    /**
     * The PHYSICS mod's id for the ship named {@code durableShipId}, in {@code dim} — the crossing
     * between the two identities a tier-2 craft has. The durable id keys the ledger and survives every
     * re-assembly; the {@code vs} verbs are keyed on the physics id and on nothing else.
     *
     * <p>Ask again after any crossing: a jump, an entry and a cell-seam carry each rebuild the hull,
     * so the physics id the caller held names nothing on the far side. Fails rather than answering
     * null — a scenario that cannot find its own ship has an arrangement problem, and carrying a null
     * forward turns it into a positional lookup two calls later.</p>
     */
    public static String physicsIdOf(Probe probe, int dim, String durableShipId) throws Exception {
        String reply = probe.exec("artest vs ship-uuid " + dim + " " + durableShipId);
        assertTrue("no loaded hull in dim " + dim + " carries the name " + durableShipId + ", so every"
                + " later `vs` call would have to guess which craft is meant: " + reply,
                Reply.of(reply).bool("found"));
        // The READ is the refusal, and it says the same thing the assertion that stood here
        // said: a bridge that reported `found:true` and then named no id.
        String id = Reply.of("artest vs ship-uuid", reply).text("id");
        return id;
    }

    /**
     * Is any LOADED ship in {@code dim} sitting within {@code tolerance} of {@code (x,y,z)}?
     *
     * <p>A question about a PLACE, which is a real question and not an identification. Answered by
     * enumerating the loaded ships and their poses, rather than by asking which ship is nearest the
     * point: the nearest lookup is unbounded, so it answers with a craft however far away it is, and
     * the pose comparison that followed then tested only the ONE ship the lookup chose.</p>
     *
     * <p><b>ONE probe call.</b> The list and the poses come back together, because a caller polling a
     * world where nothing holds ships loaded reads a hull that is resident for about a tick after its
     * own load pump — and an extra round-trip inside that gap misses it every time. Measured
     * 2026-09-06: a two-call form of this helper turned {@code
     * VSCrossingOutOfAnUnloadedSourceE2ETest} red with {@code loaded=0} in the reply, and that class
     * had passed on the same tree with the nearest lookup it replaced.</p>
     */
    public static boolean aLoadedShipIsAt(Probe probe, int dim, double x, double y, double z,
                                          double tolerance) throws Exception {
        String reply = probe.exec("artest vs ships-loaded " + dim);
        // Each ship is read as its own object. The regex this replaces matched the three coordinate
        // fields in ONE expression, which held only while they stayed adjacent and in that order —
        // and would otherwise have paired one hull's x with another hull's z.
        for (String ship : Reply.of("artest vs ships-loaded", reply).objectArray("ships")) {
            Reply one = Reply.of(ship);
            double dx = one.number("posX") - x;
            double dy = one.number("posY") - y;
            double dz = one.number("posZ") - z;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    /**
     * The identity of the ONLY loaded ship in {@code dim} — for the one caller that genuinely has no
     * name to ask by.
     *
     * <p>A tier-2 craft is normally followed by its durable name; a fixture built without a flight
     * computer has none, so after a crossing there is nothing about it to ask for. This is what such
     * a caller may use INSTEAD of a nearest-ship lookup: the cell's ship count now names what it
     * counted, so "exactly one ship is here" and "and this is it" are one reading rather than a
     * premise followed by a positional query that could answer about the other one.</p>
     *
     * <p>Fails when the count is not exactly one, naming the count — which is the arrangement
     * failure, not a finding about the subject.</p>
     */
    public static String theOnlyLoadedShipIn(Probe probe, int dim) throws Exception {
        String counted = probe.exec("artest vs ship-count " + dim);
        Reply count = Reply.of("artest vs ship-count", counted);
        assertTrue("the ship count for dim " + dim + " is unreadable: " + counted,
                count.has("count"));
        assertEquals("dim " + dim + " must hold exactly ONE loaded ship for it to be nameable this"
                + " way — with two, nothing here says which one a reading is about: " + counted,
                1, count.integer("count"));
        String[] named = count.textArray("ships");
        assertTrue("the count says one ship but does not name it: " + counted, named.length >= 1);
        return named[0];
    }

    /**
     * {@link #physicsIdOf} once the craft named {@code durableShipId} is USABLE in {@code dim}: a
     * caller that asks the instant the assembler returns — or a crossing reports — is asking before
     * the hull exists on the far side of the substrate's spawn queue.
     *
     * <p><b>A link, read first.</b> The mark is taken BEFORE the one read, so a craft that is already
     * there is answered at once and a craft that is not is waited for on production's own
     * announcement of it, {@code ship_usable}, which the read cannot have missed. The wait is over
     * the CHAIN ({@link #endsUsable}): a load a later unload undid does not count. Then the id is
     * read once more through the refusing {@link #physicsIdOf} — never {@code null}, which
     * downstream turns back into a guess.</p>
     *
     * <p>It replaced a bounded retry of the same read, stepped on whatever clock the caller handed
     * it; the budget now counts ticks of the caller's own log, the same total.</p>
     *
     * @param serverLog the SERVER's event log, where {@code ship_usable} is recorded
     */
    public static String awaitPhysicsIdOf(Probe probe, Events serverLog, int dim, String durableShipId,
                                          int tickBudget) throws Exception {
        long mark = serverLog.markInstrumented();
        String reply = probe.exec("artest vs ship-uuid " + dim + " " + durableShipId);
        // absence is the answer here and only here: "no hull carries that name yet" is the branch
        // that waits, and the wait's own refusing read below is what reports it if it never comes.
        String hullId = Reply.of("artest vs ship-uuid", reply).textOr("id", null);
        if (Reply.of(reply).boolOr("found", false) && hullId != null) {
            return hullId;
        }
        serverLog.awaitMatching(mark, "ship_usable",
                usable -> endsUsable(usable, serverLog.since(mark, "ship_unloaded"), durableShipId, dim),
                "carrying ship " + durableShipId + " in dim " + dim + ", later than every unload of it",
                "ARRANGEMENT: this scenario's own craft " + durableShipId + " must become usable in"
                        + " dim " + dim + ", or nothing below can be addressed to it (the read before"
                        + " the wait said: " + reply + ")", tickBudget);
        return physicsIdOf(probe, dim, durableShipId);
    }

    /**
     * Whether the latest LOAD of {@code shipId} in {@code usable} ({@code ship_usable} records) is
     * later, by {@code seq}, than every UNLOAD of that craft in {@code unloaded} ({@code
     * ship_unloaded} records). An empty load list is NOT YET.
     *
     * <p>{@code shipId} may be either spelling a caller holds — the durable AR name ({@code ship}) or
     * the substrate's key ({@code vsShip}); they are minted in different places and are not the same
     * value. An unload carries only the substrate's key and the substrate's NAME, so it is matched by
     * the key the load itself carried as well as by the caller's id, or a durable id would never
     * match an unload and a load since undone would read as usable.</p>
     *
     * @param dim the world the craft must be usable IN, or {@code null} for any
     */
    public static boolean endsUsable(String usable, String unloaded, String shipId, Integer dim) {
        long lastLoad = Long.MIN_VALUE;
        String loadedKey = null;
        for (String record : Events.records(usable)) {
            if ((shipId.equals(Events.text(record, "ship")) || shipId.equals(Events.text(record, "vsShip")))
                    && inDim(record, dim)) {
                long seq = (long) Events.number(record, "seq");
                if (seq > lastLoad) {
                    lastLoad = seq;
                    loadedKey = Events.text(record, "vsShip");
                }
            }
        }
        if (lastLoad == Long.MIN_VALUE) {
            return false;
        }
        for (String record : Events.records(unloaded)) {
            String key = Events.text(record, "vsShip");
            boolean same = shipId.equals(key) || shipId.equals(Events.text(record, "name"))
                    || (loadedKey != null && loadedKey.equals(key));
            if (same && inDim(record, dim) && (long) Events.number(record, "seq") > lastLoad) {
                return false;
            }
        }
        return true;
    }

    private static boolean inDim(String record, Integer dim) {
        return dim == null || Events.number(record, "dim") == dim;
    }


    /**
     * Wait until a deck episode OPENED on {@code shipId} since {@code mark} and is still unbroken: a
     * {@code deck_entered} naming that craft, later than every {@code deck_released} and every
     * {@code deck_entered} onto another hull in the window.
     *
     * <p><b>There was a second form of this wait for one day, and the ruling that removed the
     * per-tick commit removed the need for it.</b> The distinction was "is he on this deck now"
     * versus "did he GET on it since my mark", and it existed because a held body republished
     * {@code deck_commit} every tick — so a window opened over a body that was already aboard
     * contained a record immediately, and the wait returned before its own stimulus had applied.
     * With the commit gone there is nothing to observe but the two edges, and an edge cannot
     * predate the mark it is read after. The two questions are one question. <i>Measured
     * 2026-09-16, and it cost seven client scenarios and eight refuted hypotheses: the body in
     * the trail was already held in HULL-STAND by the very craft the scenario was about to
     * teleport it onto.</i></p>
     *
     * <p>An empty window means NOT YET, which is what a wait needs; and a release carries
     * production's own {@code reason}, so an expiry names why he was let go instead of leaving the
     * reader to guess at a budget.</p>
     *
     * <p>Here rather than on a base class for the reason this whole class is: the scenarios that
     * capture a body on a deck sit under three different bases, and the edges they wait on are on
     * whichever log — the client's or the server's — belongs to the side whose resolver took the
     * body.</p>
     *
     * @param log    the log the capture is recorded on, client or server
     * @param mark   a mark on THAT log, taken BEFORE whatever puts the body on the deck
     * @param what   the scenario's own sentence for what the capture means, used in the failure
     * @return the {@code deck_entered} records since the mark, for the caller's own reading
     */
    public static String awaitCaptureHeldBy(Events log, long mark, String shipId, String what,
                                            int tickBudget) throws Exception {
        assertTrue("this wait cannot mean anything without the scenario's own ship id — it was null,"
                + " so any hull's capture would satisfy it: " + what, shipId != null);
        try {
            return log.awaitMatching(mark, "deck_entered",
                    seen -> endsCapturedBy(log, mark, shipId),
                    "an episode OPENED on " + shipId + " since the mark, with no LATER release and"
                    + " no LATER entry onto another hull",
                    what, tickBudget);
        } catch (AssertionError never) {
            throw new AssertionError(never.getMessage() + " | the releases in this window, with"
                    + " production's own reason for each: " + log.since(mark, "deck_released")
                    + " ||| the episode edges, each naming the anchor it replaced: "
                    + log.since(mark, "deck_entered"), never);
        }
    }

    /**
     * Whether the deck episode in {@code log} since {@code mark} ends HELD by {@code shipId}: an
     * episode OPENED on that craft since the mark, and nothing later ended it.
     *
     * <p><b>Over the two EDGES, and over nothing else</b> — which is the whole vocabulary there is
     * since the per-tick commit was removed (2026-09-16). A body enters a craft's frame and leaves
     * it; the resolver no longer republishes "still here" twenty times a second, so there is no
     * longer a record whose presence in a window says nothing about when the episode began. The
     * predicate that used to need spelling out — "wait for the edge, not the commit" — is now the
     * only one expressible, and the second wait built for it has been deleted rather than kept as a
     * synonym.</p>
     *
     * <p><b>An episode can end two ways, and only one of them is a release.</b> The other is an
     * anchor SWITCH — {@code captureState} overwrites the state, so a body held by A and then
     * captured by B leaves no {@code deck_released} at all. Against releases alone this predicate
     * would answer "still held by A" forever, which on a world a class shares with its siblings is
     * exactly the confusion the whole class exists to remove. So a {@code deck_entered} naming any
     * OTHER ship counts as an end too.</p>
     *
     * <p>Compared by {@code seq}, the only ordering per-type rings share. {@code deck_released}
     * carries the body but not the ship, so any release in the window is treated as this body's —
     * which errs toward waiting longer rather than toward reporting a capture that has already
     * ended.</p>
     */
    public static boolean endsCapturedBy(Events log, long mark, String shipId) throws Exception {
        java.util.List<String> captures =
                Events.recordsWhere(log.since(mark, "deck_entered"), "ship", shipId);
        if (captures.isEmpty()) {
            return false;
        }
        double held = Events.number(captures.get(captures.size() - 1), "seq");
        if (Double.isNaN(held)) {
            return false; // an unreadable sequence orders nothing; wait rather than answer from it
        }
        for (String release : Events.records(log.since(mark, "deck_released"))) {
            if (endsIt(release, held)) {
                return false;
            }
        }
        for (String entered : Events.records(log.since(mark, "deck_entered"))) {
            // the producer always writes `ship` on this record — it is the argument the capture
            // was installed with, rendered unconditionally — so absence here is a broken
            // instrument and not an entry onto nothing. Which matters because this read runs
            // inside a wait predicate: a defaulting read would silently call every record an
            // entry onto another hull and end the episode that is still open.
            if (!String.valueOf(shipId).equals(Reply.of(entered).text("ship")) && endsIt(entered, held)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code record} comes after the commit at {@code held} — and so ended that episode. An
     *  unreadable {@code seq} counts as ending it: a comparison against NaN is false either way, and
     *  the honest reading of "I cannot order this" is to keep waiting rather than to report held. */
    private static boolean endsIt(String record, double held) {
        double seq = Events.number(record, "seq");
        return Double.isNaN(seq) || seq > held;
    }
}
