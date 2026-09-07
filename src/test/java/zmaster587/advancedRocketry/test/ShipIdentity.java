package zmaster587.advancedRocketry.test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Asserting that a reading is about the ship the scenario MEANS, and not about a neighbour's.
 *
 * <p>Every harness-tier class that assembles a craft runs in a world it shares with its siblings —
 * one world per class, twelve to fourteen scenarios in the densest ones, each leaving a hull behind
 * and some of them setting {@code vs permaload}. A reading that says "a ship holds this body" is
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
}
