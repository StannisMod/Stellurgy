package zmaster587.advancedRocketry.test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** How a caller lets the world advance between attempts — its own clock, whichever it uses. */
    @FunctionalInterface
    public interface Waiter {
        void await() throws Exception;
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
        Matcher m = Pattern.compile("\"shipId\":\"([^\"]*)\"").matcher(String.valueOf(assembleReply));
        String durableId = m.find() ? m.group(1) : null;
        assertTrue("the assembler did not name the ship it built, so nothing downstream can be about"
                + " one particular craft: " + assembleReply, durableId != null);
        assertTrue("the pad carried more than one flight computer, so the id names one of several"
                + " craft and the scenario cannot say which: " + assembleReply,
                assembleReply.contains("\"afcCount\":1"));
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
                reply.contains("\"found\":true"));
        Matcher m = Pattern.compile("\"id\":\"([^\"]*)\"").matcher(reply);
        assertTrue("the bridge reported found:true without an id: " + reply, m.find());
        return m.group(1);
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
        Matcher each = Pattern.compile(
                "\"posX\":(-?[0-9.E\\-]+),\"posY\":(-?[0-9.E\\-]+),\"posZ\":(-?[0-9.E\\-]+)")
                .matcher(reply);
        while (each.find()) {
            double dx = Double.parseDouble(each.group(1)) - x;
            double dy = Double.parseDouble(each.group(2)) - y;
            double dz = Double.parseDouble(each.group(3)) - z;
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
        Matcher n = Pattern.compile("\"count\":(-?\\d+)").matcher(counted);
        assertTrue("the ship count for dim " + dim + " is unreadable: " + counted, n.find());
        assertEquals("dim " + dim + " must hold exactly ONE loaded ship for it to be nameable this"
                + " way — with two, nothing here says which one a reading is about: " + counted,
                1, Integer.parseInt(n.group(1)));
        Matcher only = Pattern.compile("\"ships\":\\[\"([^\"]+)\"").matcher(counted);
        assertTrue("the count says one ship but does not name it: " + counted, only.find());
        return only.group(1);
    }

    /**
     * {@link #physicsIdOf} with a wait: the physics mod assembles on its own thread, so a caller that
     * asks the instant the assembler returns is asking before the hull exists. Retries {@code
     * attempts} times, letting {@code between} advance the caller's own clock, and fails naming the
     * ship it could not find — never {@code null}, which downstream turns back into a guess.
     */
    public static String awaitPhysicsIdOf(Probe probe, int dim, String durableShipId,
                                          int attempts, Waiter between) throws Exception {
        String reply = "";
        for (int attempt = 0; attempt < attempts; attempt++) {
            reply = probe.exec("artest vs ship-uuid " + dim + " " + durableShipId);
            Matcher m = Pattern.compile("\"id\":\"([^\"]*)\"").matcher(reply);
            if (reply.contains("\"found\":true") && m.find()) {
                return m.group(1);
            }
            between.await();
        }
        throw new AssertionError("ARRANGEMENT: no hull in dim " + dim + " ever carried the name "
                + durableShipId + " within " + attempts + " attempts, so nothing below could be"
                + " addressed to this scenario's own craft; last reply: " + reply);
    }

    /** The ship holding the capture described by a {@code deck-capture} reply, or {@code null} when
     *  the reply says nobody holds it. Never absent from a reply: production emits the key with a
     *  null value, so a missing key means the reply is not a deck-capture answer at all. */
    public static String anchorOf(String deckCaptureReply) {
        Matcher m = Pattern.compile("\"anchorShipId\":\"([^\"]*)\"")
                .matcher(String.valueOf(deckCaptureReply));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Every loaded hull whose world box contains the body a {@code deck-capture} reply is about, as
     * the reply's own JSON array text (e.g. {@code ["a1"]}), or {@code null} when the reply carries
     * no such key.
     *
     * <p>Returned as TEXT, including its brackets, on purpose: the size is the part a caller has to
     * assert, and a helper that handed back "the first one" would rebuild at the reading end exactly
     * the first-match ambiguity the list exists to expose. Compare against {@code "[\"" + shipId +
     * "\"]"} to say "this ship, and no other hull is here".</p>
     *
     * <p>This is the identity behind {@code shipSupportObstacles} on the reply's GATED branch — the
     * one taken for a body that cannot steer (a mob, a stand, an item), where production resolves the
     * ship frame by containment and takes the first match, so the count names nobody.</p>
     */
    public static String containingShipsOf(String deckCaptureReply) {
        Matcher m = Pattern.compile("\"containingShipIds\":(\\[[^\\]]*\\])")
                .matcher(String.valueOf(deckCaptureReply));
        return m.find() ? m.group(1) : null;
    }

    /** The ship a {@code deck-capture} reply says would TAKE this body on first contact — the
     *  identity behind {@code shipSupportObstacles} / {@code supportedByShip} for a body nothing has
     *  captured yet. Null when the reply describes a body that is already tracked (there the anchor
     *  is the identity) or one no hull supports. */
    public static String firstContactCandidateOf(String deckCaptureReply) {
        Matcher m = Pattern.compile("\"firstContactCandidate\":\"([^\"]*)\"")
                .matcher(String.valueOf(deckCaptureReply));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Fail unless the capture in {@code deckCaptureReply} is held by {@code expectedShipId}.
     *
     * <p>Asserted BESIDE the caller's own flag check rather than instead of it: "he is held" and "he
     * is held by this ship" are different claims and a scenario usually means both. The failure
     * prints the whole reply, because the interesting case is not "no anchor" but an anchor that
     * names a craft the reader has to recognise as a neighbour.</p>
     *
     * @param what the scenario's own sentence for what the capture means, used in the failure
     */
    public static void assertCaptureAnchoredOn(String deckCaptureReply, String expectedShipId,
                                               String what) {
        assertTrue("this assertion cannot mean anything without the scenario's own ship id — it was"
                + " null, so nothing distinguishes this craft from a neighbour's: " + deckCaptureReply,
                expectedShipId != null);
        String anchor = anchorOf(deckCaptureReply);
        assertTrue(what + " — the reply names NO ship holding this body, so \"" + what + "\" cannot"
                + " be read out of it: " + deckCaptureReply, anchor != null && !anchor.isEmpty());
        assertEquals(what + " — the body is held, but by a DIFFERENT craft than this scenario's."
                + " On a world this class shares with its siblings that is the whole failure mode,"
                + " and every flag in the reply reads the same either way: " + deckCaptureReply,
                expectedShipId, anchor);
    }

    /** The ship an {@code artest vs player-ship-data} reply says the body is inside, or {@code null}
     *  when it is inside none. Production emits the key with a null value beside {@code shipLoaded},
     *  so a missing key means the reply is not a player-ship-data answer at all. */
    public static String aboardShipOf(String playerShipDataReply) {
        Matcher m = Pattern.compile("\"shipId\":\"([^\"]*)\"")
                .matcher(String.valueOf(playerShipDataReply));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Fail unless the body described by {@code playerShipDataReply} is aboard {@code expectedShipId}.
     *
     * <p>{@code shipLoaded} is genuine CONTAINMENT — the body's world position lies inside a loaded
     * hull's box, not merely near one — so it cannot be satisfied by a distant craft. It can be
     * satisfied by an ADJACENT one: two hulls may occupy the same space, and on a world a class
     * shares with its siblings the flag reads {@code true} either way. The reply's {@code localX/Y/Z}
     * are that hull's subspace coordinates, so a wrong hull does not merely mislabel the claim, it
     * silently changes what every deck-frame number below the assertion means.</p>
     *
     * @param what the scenario's own sentence for what being aboard means, used in the failure
     */
    public static void assertAboardShip(String playerShipDataReply, String expectedShipId,
                                        String what) {
        assertTrue("this assertion cannot mean anything without the scenario's own ship id — it was"
                + " null, so nothing distinguishes this craft from a neighbour's: "
                + playerShipDataReply, expectedShipId != null);
        String aboard = aboardShipOf(playerShipDataReply);
        assertTrue(what + " — the reply names NO ship containing this body, so \"" + what + "\""
                + " cannot be read out of it: " + playerShipDataReply,
                aboard != null && !aboard.isEmpty());
        assertEquals(what + " — the body IS inside a hull, but a DIFFERENT one than this scenario's."
                + " Every flag in the reply reads the same either way, and the subspace coordinates"
                + " beside them belong to that other hull: " + playerShipDataReply,
                expectedShipId, aboard);
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
     * 2026-09-16, ledger #501: seven client scenarios, eight refuted hypotheses, and the body in
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
        java.util.List<String> captures = Events.recordsWithAll(log.since(mark, "deck_entered"),
                "\"ship\":\"" + shipId + "\"");
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
            if (!entered.contains("\"ship\":\"" + shipId + "\"") && endsIt(entered, held)) {
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
