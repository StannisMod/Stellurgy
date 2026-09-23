package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.DeckCapture;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.PlayerState;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.ArrangementFailure;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.SubsystemStatus;
import zmaster587.advancedRocketry.test.TransitStatus;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.ShipIdentity;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.FIXTURE_CELL_SPACING_BLOCKS;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hyperspace transit with a crew member aboard: four scenarios that used to be four classes and four
 * client boots, now one boot.
 *
 * <h2>Why this cluster shares safely</h2>
 *
 * <p>Every scenario here arranges itself through {@code artest space transit-setup-piloted} /
 * {@code -empty}, and that probe allocates a <b>fresh origin pool cell per call</b>. So each
 * scenario works in a dimension of its own: the whole-dimension ship counts these bodies use as
 * assembly gates ({@code vs ship-count-all &lt;originDim&gt;}) are scoped by construction, not by
 * luck, and none of them needed narrowing. That is the opposite of the ground-fixture cluster, where
 * every scenario shares dim 0 and the gates had to be rewritten.</p>
 *
 * <p>The things the scenarios DO leave behind are closed by {@link AbstractSharedVsClientE2ETest}:
 * a still-riding player and the flight computer's static command channels. Their original
 * {@code @After cleanup()} methods did that by hand and did not check it; the shared reset asserts
 * it, so those methods are dropped rather than carried over. ({@code vs permaload} was the third
 * item here until a test server began holding its ships loaded by default — nobody switches it on
 * for himself any more, and nothing switches it off between scenarios.)</p>
 *
 * <p>{@code bot().setRenderDistance} is the one channel that belongs to this family alone — the
 * sky-observing scenario widens it — so it is restored here, in the reset, and not in an
 * {@code @After} (which JUnit runs BEFORE the failure watcher, destroying the journal a red needs).
 * It is static because JUnit builds a fresh test instance per method while the client JVM keeps the
 * setting.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSTransitCrewGroupE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-transit-crew";
    }

    /**
     * The render distance the sky scenario widened, or -1 when nothing has touched it. Static: the
     * value lives in the client JVM, which outlives every test instance in this class.
     */
    private static int previousRenderDistance = -1;

    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        super.resetFamilyStateBeforeTeleport();
        if (previousRenderDistance >= 0) {
            bot().setRenderDistance(previousRenderDistance);
            previousRenderDistance = -1;
        }
        flyOutAnyJumpLeftInTheAir();
    }

    /**
     * No jump may still be in the air when a scenario in this family starts.
     *
     * <p><b>Why this is a family reset and not each scenario's own housekeeping.</b> Several
     * scenarios here deliberately stop at a reading taken mid-flight — that IS their subject — and
     * every transit in the server's live stack is now advanced once per server tick. So a jump left
     * running does not sit where it was left: it FLIES, it arrives inside whatever scenario happens
     * to be running by then, and its arrival re-seats its crew — the same bot — into the destination
     * cell. Measured 2026-09-08: the seat mount in the next scenario failed five times over with the
     * dummy alive in the origin dim and the player already delivered to the target dim, which read
     * for a month as a flaky mount.</p>
     *
     * <p>This speaks the product's own language rather than undoing a transition: the jump is
     * COMPLETED, not evicted, which is the only way to remove it that leaves the subsystem in a
     * state a player could also reach. And it is asserted — a reset nobody checks cannot be told
     * from no reset — with the bound taken from the longest flight this class ever starts.</p>
     *
     * <p><b>The QUESTION is put to the world, not to the probe's memory.</b> "Is any jump in the
     * air" is a fact about the live stack, and {@code space subsystem-status} answers it off
     * {@code liveStack().transit} with no scaffold standing. {@code transit-status} cannot: it is
     * gated on a handle only {@code transit-setup-*} fills, so before the FIRST scenario of a boot
     * has set anything up it answers {@code error} — which is not "no jump", and the reader is
     * right to refuse it. Measured 2026-09-18: that refusal killed all eight scenarios of this
     * class in setup. The fly-out below is a different matter and keeps the scaffold verb: a jump
     * can only be in the air here because a scenario in this JVM began one, and beginning one is
     * what fills the handle.</p>
     */
    private void flyOutAnyJumpLeftInTheAir() throws Exception {
        SubsystemStatus stack = SubsystemStatus.read(this::exec)
                .requireRegistered("a scenario in this family cannot start without the space stack");
        if (stack.transits == 0) {
            return;
        }
        boolean flownOut = false;
        for (int i = 0; i < LIVABLE_FLIGHT_TICKS / 10L && !flownOut; i++) {
            flownOut = TransitStatus.tick(this::exec, 10).inTransit == 0;
        }
        // An ARRANGEMENT failure: a leftover jump this reset could not fly out has disproved nothing
        // about transits — it is the world the next scenario needs not having been put back.
        scenario().requireArranged("a jump left in the air by an earlier scenario must be flown out"
                + " before this one starts, or its arrival lands INSIDE this scenario and re-seats"
                + " the bot into the destination cell: " + exec("artest space transit-status"),
                flownOut);
    }

    // ---- shared arrangement helpers (byte-identical in all four sources) ----


    /**
     * The bot's own username, off the server's own answer.
     *
     * <p>Needed for more than the {@code space enter} it was first read for: every record this class
     * waits on carries {@code who}, and on a log that any body's mount can write to, the name is what
     * makes a wait about THIS crew member.</p>
     */
    private static String botName(Events.Probe probe) throws Exception {
        return PlayerState.botName(probe::exec);
    }

    /** {@code find-seat} keyed by identity: every scenario here builds at the SAME anchor in the
 *  SAME pooled slot, so the anchor is a question with several right answers. */
    private PilotSeat findSeat(int originDim, String shipId) throws Exception {
        return PilotSeat.byId(this::exec, originDim, shipId);
    }

    /**
     * The DURABLE name of the hull parked in the hyperspace lane, taken from production's own
     * boarding record. The physics id changes at every crossing; this does not, so it is what a
     * corridor-side lookup translates through.
     */
    private String parkedHullName;

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces the load). */
private int waitForLoadedShip(int dim) throws Exception {
        // ASSERTED, not waited for. ShipReadiness carries the measurement behind this: across the
        // server tier the same helper was planted at both branches, and the branch that WAITS never
        // executed — at one fork or at six, the ship is already loaded by the time a scenario asks.
        // A wait that waits for nothing converts a state that should fail loudly into absorbed
        // ambiguity and then answers 0 for several different reasons, so the postcondition replaces
        // it and names what to look at: a craft that never registered, or one that registered and
        // did not load.
        return ShipReadiness.requireLoaded(this::exec, dim,
                "this scenario's craft must be loaded before the transit is driven");
    }

    // ---- the jump as a CHAIN of events: PILOTED_JUMP_CHAIN, transitEvents, arrivedTargetDim live in
    // the VS base, shared with every transit scenario ----

    /**
     * Drive the transit until the crew member is CARRIED into the corridor, and return the corridor's
     * dimension. An arrangement step: every caller stands him up or reads his sky IN the corridor, so
     * whatever this cannot establish is raised as an arrangement failure with the chain attached.
     *
     * <p>Two links, one per side, each read off its own log. The SERVER's: he has been seated on the
     * hull parked in the lane ({@code crew_boarded_parked_hull}) — a flight that settles without that
     * record is one that carried nobody, and the old loop reported it as a slow client. The CLIENT's:
     * its own dimension became the corridor ({@code client_dimension_changed} with the lane's world),
     * recorded at the tail of the respawn packet that rebuilds its world — the fact the old loop
     * sampled with {@code reportWeather} on a tick budget, and could miss when the world was rebuilt
     * twice between two samples.</p>
     */
    private int driveIntoCorridor(Events events, long serverMark, long clientMark) throws Exception {
        try {
            return assertCarriedIntoCorridor(events, serverMark, clientMark);
        } catch (ArrangementFailure alreadyTyped) {
            throw alreadyTyped;
        } catch (AssertionError contract) {
            scenario().arrangementFailed(contract.getMessage());
            return -1; // unreachable: arrangementFailed always throws
        }
    }

    /**
     * The same two links, raised as a CONTRACT failure — for the scenarios whose whole subject is
     * that the crew member goes WITH his ship. {@link #driveIntoCorridor} is this call under an
     * arrangement typing, exactly as {@code requireChain} is {@code assertChain} under one.
     *
     * <p><b>This wait IS the assertion, and that is the point of it.</b> The form it replaces flew
     * the jump on a 120-iteration budget, sampled {@code reportWeather} once per two ticks and then
     * compared the sample against {@code hyperDim} — so a client that was carried a moment after the
     * sample was taken, and a client that was left behind for good, produced the same red, and a
     * jump that arrived inside the budget produced a green with nothing observed at all. A record of
     * the client's own dimension change cannot be missed between two samples and cannot be faked by
     * a fast arrival: it is written when the world is rebuilt, at the tail of the respawn packet.</p>
     *
     * @return the corridor's dimension, as the transit subsystem itself names it
     */
    private int assertCarriedIntoCorridor(Events events, long serverMark, long clientMark)
            throws Exception {
        String boarded = events.await(serverMark, "crew_boarded_parked_hull", "the crew must be"
                + " seated on the hull parked in the lane before anyone can be in the corridor",
                JUMP_LINK_BUDGET_TICKS);
        // The hull in the lane, BY NAME. Production names it in the record it just wrote: the
        // crossing re-assembles the ship, so the physics id from the origin cell is dead here, and
        // the durable name is the only thing that crossed with it.
        String boardedShip = Events.lastField(boarded, "ship");
        scenario().requireArranged("the boarding record must name the ship it seated the crew on,"
                + " or nothing in the corridor can be addressed to this craft: " + boarded,
                boardedShip != null && !boardedShip.isEmpty());
        parkedHullName = boardedShip;
        int corridorDim = TransitStatus.read(this::exec).hyperDim;
        // The server's half is appended on the FAILURE path only, because the two logs answer
        // different questions and the client's alone cannot say whether the jump got as far as the
        // lane; on the happy path it would be a probe call per wait.
        ClientEvents.awaitDim(clientEvents(), clientMark, corridorDim,
                "the crew member must be carried into the corridor with his ship, as HIS OWN CLIENT"
                        + " sees it — the server has already seated him on the hull parked in the"
                        + " lane", JUMP_LINK_BUDGET_TICKS,
                () -> "server chain since the mark: " + events.since(serverMark));
        return corridorDim;
    }

    private static int readInt(String json, String key) {
        assertTrue("expected int \"" + key + "\" in: " + json, Reply.of(json).has(key));
        return Reply.of(json).integer(key);
    }

    private static int readIntOr(String json, String key, int def) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb takes the
        // default as an argument, so every call site names what a missing field means there.
        return Reply.of(json).integerOr(key, def);
    }

    private static double readDouble(String json, String key) {
        Reply reply = Reply.of(json);
        assertTrue("expected number \"" + key + "\" in: " + json, reply.has(key));
        return reply.number(key);
    }

    private static boolean readBool(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).boolOr(key, false);
    }

    /**
     * Cumulative server-tick samples the flight recorder holds for one tile, out of a motion-trace
     * reply. Scoped to the {@code "game"} object on purpose: the reply carries several channels and
     * each of them has its own {@code seen}, so a flat read answers with whichever came first — the
     * PHYSICS channel, which is a different clock and a different claim.
     */
    private static long gameSeen(String traceJson) {
        // Taken as the NESTED OBJECT, not as the tail of the string from where its key appears: a
        // slice of a JSON document is not a JSON document, and handing one to a parser fails with a
        // syntax error about the world. (It did — measured on this very line, 2026-09-17.)
        String game = Reply.of("artest motion trace", traceJson).object(GAME_CHANNEL);
        assertTrue("expected a \"game\" channel in the motion trace: " + traceJson, game != null);
        // A key that was never driven has no ring, and the reply then carries no "seen" at all -
        // which is an ANSWER ("nothing ever ticked here"), not a malformed reply. Reading it as a
        // parse failure hides the finding behind the instrument: the first cut of this helper threw
        // on exactly the reading the leg exists to detect.
        return readIntOr(game, "seen", 0);
    }

    /** The SERVER-tick channel of a motion trace. The reply carries several, each with its own
     *  {@code seen}, so this must be named: a flat read answers with whichever came first. */
    private static final String GAME_CHANNEL = "game";

    /** Blocks per tick for the jump. Slow enough that the ship stays parked for tens of ticks. */
    private static final long PARK_SPEED = HYPERSPACE_JUMP_SPEED;

    // ---- migrated: VSShipTransitCrewE2ETest ----

    @Test
    public void aSeatedCrewMemberSurvivesAHyperspaceTransitStillRiding() throws Exception {

        // Headless: pin ships loaded so a freshly assembled ship does not auto-unload between probe calls.

        // Build a PILOTED tier-2 ship in a fresh transit ORIGIN pool cell. The assembly is DEFERRED
        // rather than threaded — the spawn is queued and the ship manager drains that queue in its
        // own tick — so the ship + its seat are not queryable synchronously; poll for them below.
        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;

        // Wait for the async assembly to load the ship in the origin cell (count-all -> load-ships -> count).
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Now the ship is up: put the bot in the origin cell and on the ship's pilot seat, keyed by
        // the identity the setup handed back rather than by the anchor every scenario here shares.
        // The helper locates the seat, carries the client in, mounts the dummy and asserts the
        // control that makes "still riding after the jump" mean anything.
        seatTheBot(originDim, setup.requireShipId());

        // The mark is taken BEFORE the departure, so nothing the jump does can fall between two reads.
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        // And the CLIENT's own mark beside it, for the remount below: his client PERFORMS the mount
        // when the arrival tells it who is riding what, so that half is a record on the other log.
        long clientMark = clientEvents().mark();

        // Depart into hyperspace at the ship anchor (1,64,1 from transit-setup-piloted).
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + HYPERSPACE_JUMP_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // THE CONTRACT, as the server commits it: the whole jump, link by link and in order. This is
        // where "the jump never completed" and "the crew was left behind" used to be one number.
        events.assertChain(mark, "a seated crew member's jump must pick him up, cut the hull into the"
                + " lane, seat him on the parked hull, cut the hull into its destination and put him"
                + " back aboard before it settles", JUMP_LINK_BUDGET_TICKS, PILOTED_JUMP_CHAIN);
        int targetDim = arrivedTargetDim(this::exec);

        // ACCEPTANCE (client oracle): the client itself must PERFORM the remount and still have the
        // crew member on the ship's seat, in the TARGET cell. The server's re-seat is a link above;
        // what is read here is whether the CLIENT followed it — as its own link, off the mark taken
        // before the departure — and the helper says which of the two failed when it did not.
        JsonObject riding = ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);
        assertTrue("the crew member must survive the jump still riding, on the CLIENT: " + riding
                + " (targetDim=" + targetDim + ", clientDim=" + bot().reportWeather().get("dim").getAsInt() + ")",
                riding.get("riding").getAsBoolean());
        assertTrue("the re-mounted entity must be the ship's seat dummy: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertEquals("the client must have followed the crew into the target cell",
                targetDim, bot().reportWeather().get("dim").getAsInt());

        // ACCEPTANCE, on the crossing's own census: this jump moved the hull it NAMES, and it parked
        // in a lane that was its alone. Both are invisible from everything asserted above - a jump
        // that delivered a stranger's hull with this crew re-seated on it looks exactly like a
        // successful one from the client's side, which is how the positional cut survived so long.
        String census = exec("artest vs arrival-trace");
        Reply cutMReply = Reply.of(census);
        assertTrue("the arrival must leave a cut census behind: " + census, cutMReply.has(ARRIVAL_CUT));
        String cut = cutMReply.text(ARRIVAL_CUT);
        String cutting = censusField(cut, "cutting");
        String byDurableId = censusField(cut, "byDurableId");
        String byPosition = censusField(cut, "byPosition");
        // Printed, not merely asserted: which ARM the assertion below took is the whole value of it.
        // With no durable id resolvable it degenerates into "the cut took the anchor's craft", which
        // is what production did before there was a rule at all - a green that says nothing.
        System.out.println("[JUMP CENSUS] " + cut);
        // The jump must be able to NAME its own hull, not merely fail to mistake somebody else's for
        // it. A crossing carries the craft's durable name onto the record it creates; without that
        // the name is lost at every crossing and can only come back from the ship's own tick, which
        // a parked hull never gets - so this field going back to "null" means the carry is gone and
        // the arrival is resolving by position again, which is exactly what it looks like when it
        // delivers a stranger.
        assertTrue("the jump must resolve its own hull BY NAME in hyperspace - a null here means the "
                + "arrival is back to picking whatever craft its anchor reaches. census: " + cut,
                !"null".equals(byDurableId) && !"(absent)".equals(byDurableId));
        // Unconditional in BOTH arms: where the jump's durable id resolves a hull, that hull is the
        // one cut; where nothing could be established, the anchor's craft is - and saying which arm
        // applied is the difference between a check with three answers and one with none.
        assertEquals("the arrival cut the hull the jump names (byDurableId), or - where no identity "
                        + "could be established - the one at its anchor. census: " + cut,
                "null".equals(byDurableId) ? byPosition : byDurableId, cutting);
        assertTrue("a healthy jump is never REFUSED its own hull - a refusal here means the identity "
                + "check fires on a case it cannot judge. census: " + cut, !"REFUSED".equals(cutting));

        Reply laneMReply = Reply.of(census);
        assertTrue("the departure must leave a lane census behind: " + census, laneMReply.has(DEPART_LANE));
        String lane = laneMReply.text(DEPART_LANE);
        assertTrue("the lane this jump departed into must have been EMPTY when it was handed out - a "
                        + "lane holding a second hull makes every later position lookup at that "
                        + "anchor ambiguous. census: " + lane,
                lane.contains("alreadyThere=[]"));
    }

    /** One {@code key=value} field out of a census line, or {@code "(absent)"}. Values are plain
     *  tokens (uuids, "null", "REFUSED"); the bracketed and BlockPos fields are read whole. */
    private static String censusField(String census, String key) {
        Matcher m = Pattern.compile("(?:^| )" + key + "=(\\S+)").matcher(census);
        return m.find() ? m.group(1) : "(absent)";
    }

    // ---- migrated: VSCrewRidesItsShipThroughHyperspaceE2ETest ----

    @Test
    public void aSeatedCrewMemberIsAboardHisShipInHyperspaceWhileItIsStillFlying() throws Exception {


        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;

        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        seatTheBot(originDim, setup.requireShipId());

        // CONTROL: he is in the ORIGIN cell before the jump — so the mid-flight reading below can
        // move. (That he is RIDING is the last thing seatTheBot asserts.)
        assertEquals("the bot must be in the origin cell before the jump (control)",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // Both marks BEFORE the departure, one per log: the crossing is the server's fact and the
        // world rebuild and the re-seat are the client's, and the two logs number independently.
        String botName = botName(this::exec);
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // THE CONTRACT: the crew travels with its ship. Both halves are production's own records —
        // the server seating him on the hull parked in the lane, and his client's own world becoming
        // that lane — so a crew member left behind names the link that did not happen, where the
        // sampling loop this replaces reported one dimension number and could not tell "left behind"
        // from "carried a tick after I looked".
        int hyperDim = assertCarriedIntoCorridor(events, mark, clientMark);

        // ...and a jump does not take the pilot out of his seat for the duration of the flight. That
        // is a LINK, not a reading: the carry takes him off the seat in the origin cell and puts him
        // on the hull parked in the lane, and his own client PERFORMS that mount when the server
        // tells it who is riding what. With the mark taken before the departure the record cannot be
        // missed, however the two sides interleave — where a read taken at the world change samples
        // the gap between the tear-down and the rebuild and answers `riding:false` for a pilot who is
        // about to be seated (measured 2026-09-10: green on one run of this build, red on the next).
        try {
            clientEvents().awaitMatching(clientMark, "mount",
                    seen -> Events.recordsWhere(seen, "who", botName).stream()
                            .anyMatch(seating -> "true".equals(Events.text(seating, "ok"))),
                    "seating " + botName + " (ok:true)",
                    "a jump must not take the pilot out of his seat: his own client must re-seat him"
                            + " on the hull parked in the lane", JUMP_LINK_BUDGET_TICKS);
        } catch (AssertionError never) {
            // Which silence: a client that never mounted him and a mixin that never wove are the
            // same empty window, and they need opposite investigations. The mount hook announces
            // itself under its own name, so the roster answers about THIS observation point.
            Events.assertInstrumentRan(clientEvents().since(clientMark, "mount"),
                    "entity_mount_writes", "the client's own mounts must be observed at all before"
                            + " an absent one can be read as a pilot left on his feet");
            throw new AssertionError(never.getMessage() + " | the SERVER's chain: "
                    + events.since(mark));
        }

        // Read ONCE, as the contract pin the link cannot make for itself: what he is riding is the
        // ship's own seat dummy and not something he came to rest on.
        JsonObject ridingInFlight = bot().reportRidingEntity();
        assertTrue("the crew member must be back on the SHIP'S SEAT in flight, not merely riding"
                + " something: " + ridingInFlight,
                ridingInFlight.get("entityClass").getAsString().endsWith("EntityDummy"));

        // The far end is another scenario's subject, so everything above was read WHILE the jump was
        // in the air — and that is stated rather than assumed, after the last of those readings. One
        // status reply, read once: the premise and the subsystem's own oracle for where this crew
        // belongs are two fields of the SAME record, so nothing can drift between them.
        TransitStatus inFlight = TransitStatus.read(this::exec);
        scenario().requireArranged("the jump must still be IN FLIGHT when the seat is read, or this"
                + " reads the ARRIVAL — where the crew is re-seated for a different reason entirely."
                + " transit-status=" + inFlight.raw(), inFlight.inTransit >= 1);
        assertEquals("mid-flight the subsystem must place this crew in the hyperspace world"
                + " (crewDim vs hyperDim); the client was carried into dim " + hyperDim
                + "; tick=" + inFlight.raw(),
                inFlight.hyperDim, inFlight.crewDim);
    }

    // ---- migrated: VSCrewedArrivalReseatsWithNobodyToLoadTheShipE2ETest ----

    /**
     * Run a probe and return ONLY its JSON envelope. The server writes its own log lines to the same
     * stream, so joining every returned line hands the assertions whatever unrelated line happened to
     * land in the window — including one that satisfies them.
     */
private String execEnvelope(String cmd) throws Exception {
        String envelope = "";
        for (String line : serverClient().execute(cmd)) {
            int brace = line.indexOf('{');
            if (brace >= 0 && line.endsWith("}")) {
                envelope = line.substring(brace);
            }
        }
        return envelope;
    }

    /** ORIGIN-side arrangement: poll until the fixture ship EXISTS. Asked through the queryable
     *  registry, which answers for an unloaded ship — so this waits without forcing anything. */
private boolean waitForRegisteredShip(int dim) throws Exception {
        // STAYS A LOOP, and the link was checked rather than assumed: `ship_spawned` is recorded at
        // the registry's own add, which is the right MOMENT — but it carries `vsShip` and `name`
        // and no dimension, so it cannot answer "is there a craft in THIS cell", which is the
        // question here. What this cannot see: a craft that registered and deregistered between
        // two reads.
        for (int i = 0; i < 40; i++) {
            if (readIntOr(execEnvelope("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                return true;
            }
            bot().waitTicks(5);
        }
        return false;
    }

    /**
     * Whether the reply carries {@code key} as a field of its OWN — which is what every caller
     * here means.
     *
     * <p>It used to search the rendering for {@code "key":}, which also answers true for a field
     * belonging to a MEMBER of the reply (the reader refuses that by name, saying where it found
     * it) and for the text appearing inside some other field's value. It also answered true for
     * a field whose value is JSON null, which is not a reading.</p>
     */
    private static boolean hasKey(String json, String key) {
        return json != null && Reply.of(json).has(key);
    }

    @Test
    public void aCrewMemberIsReseatedOnArrivalWithNothingForcingTheShipLoaded() throws Exception {

        TransitSetup setup = TransitSetup.of(execEnvelope("artest space transit-setup-piloted"));
        int originDim = setup.originDim;

        // ARRANGEMENT. The fixture's assembly is async, so wait for the ship to EXIST — asked through the
        // queryable registry, which answers for an unloaded ship and therefore forces nothing.
        assertTrue("the piloted origin ship never assembled in the pool cell (dim " + originDim + ")",
                waitForRegisteredShip(originDim));

        String botName = PlayerState.botName(this::execEnvelope);

        // Put the bot in the origin cell FIRST, at the assembly anchor. That is what makes the origin ship
        // loaded — by a real player's proximity, VS's own mechanism — so even the arrangement needs no
        // force-load. (A first cut called `vs load-ships` here instead, and the ship had unloaded again by
        // the very next probe: find-seat came back with the seat located but NO ship world position. That
        // is this same bug biting the arrangement rather than the assertion.)
        // The CLIENT's mark BEFORE the transfer is ordered: what made the origin ship load is this
        // body's PROXIMITY, so the seat search below is asking about a world he has to be in.
        long enterMark = clientEvents().mark();
        String enter = execEnvelope("artest space enter " + botName + " " + originDim + " 1 64 1");
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        awaitClientDim(enterMark, originDim,
                "the proximity load the seat search depends on is this body being THERE");
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // Now locate the seat. Retried, because the ship's world position resolves only once VS has
        // actually loaded it for the nearby bot, a tick or two after the dimension transfer — and
        // raised as an ARRANGEMENT failure, because a seat this scenario could not find has said
        // nothing about what happens to a crew member on arrival.
        // WHY THIS IS STILL A BOUNDED READ and not a link, because the rest of this class is links
        // now and the difference is worth stating. `ship_usable` is a record of a LOAD — it fires
        // once each time the physics object becomes usable — and the question here is whether the
        // ship is loaded RIGHT NOW, which is a state that comes and goes: the comment above records
        // a first cut where `vs load-ships` was answered and the ship had unloaded again by the very
        // next probe. A wait on the record would be satisfied by a load that has since been undone,
        // which is the exact failure this arrangement exists to prevent.
        //
        // What DID change: the loop no longer hands its exit condition to an assertion that restates
        // it. It fails INSIDE, typed as the arrangement it is, carrying the reading — so "the seat
        // was never located" and "it was located without a world position" stay distinguishable
        // without either of them being a re-check of `hasKey`.
        //
        // A syntactic scan for "a loop header value an assertion later reads" still counts this one,
        // because `seat` is concatenated into an assertion in ANOTHER method of this class. It is a
        // false positive, and this paragraph is the reason not to open the site again over it.
        PilotSeat seat = null;
        for (int i = 0; i < 40 && (seat == null || Double.isNaN(seat.shipWorldX)); i++) {
            seat = PilotSeat.of(execEnvelope("artest vs find-seat " + originDim + " id "
                    + setup.requireShipId()));
            if (Double.isNaN(seat.shipWorldX)) {
                bot().waitTicks(5);
            }
        }
        if (!seat.found) {
            // Witness sensitivity: without a located seat the whole "still riding on the far side"
            // observation is vacuous, so this is refused before anything is done to the ship.
            scenario().arrangementFailed("the pilot seat must be found in the assembled ship (else"
                    + " the test is vacuous); searched yard " + seat.describeYard() + ": "
                    + seat.raw());
        }
        if (Double.isNaN(seat.shipWorldX)) {
            scenario().arrangementFailed("the origin ship must resolve a world position with the bot"
                    + " beside it — nothing here force-loads it, so this is the proximity load"
                    + " having taken, and it did not within 200 ticks: " + seat.raw());
        }
        int seatX = seat.seatX, seatY = seat.seatY, seatZ = seat.seatZ;

        mountTheSeatDummy(this::execEnvelope, originDim, seatX, seatY, seatZ);

        // CONTROL: the client confirms it IS riding before the jump, so "riding after" carries information.
        assertTrue("the bot must be seated on the ship BEFORE the jump (control): "
                + bot().reportRidingEntity(), bot().reportRidingEntity().get("riding").getAsBoolean());

        Events events = transitEvents(this::execEnvelope);
        long mark = events.markInstrumented();
        // The CLIENT's own mark, for the remount link below — taken here, before the departure.
        long clientMark = clientEvents().mark();

        String begin = execEnvelope("artest space transit-begin " + originDim + " 1 64 1 " + HYPERSPACE_JUMP_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // The leg under test: the whole chain, with NOTHING forcing the arriving ship loaded — no
        // load-ships against the target, no permaload. If the re-seat needs the ship loaded, there is
        // nothing in this world to load it, and the chain stops at `crew_reseated` with the placement's
        // own account of what it is waiting on (`crew_reseat_blocked`) in the log it prints.
        events.assertChain(mark, "a crew member must be re-seated on arrival with NOTHING forcing the"
                + " ship loaded", JUMP_LINK_BUDGET_TICKS, PILOTED_JUMP_CHAIN);
        int targetDim = arrivedTargetDim(this::execEnvelope);

        JsonObject riding = ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);
        assertTrue("a crew member must be re-seated on arrival with NOTHING forcing the ship loaded; client "
                + "reports " + riding + " (targetDim=" + targetDim + ", clientDim="
                + bot().reportWeather().get("dim").getAsInt() + ")",
                riding.get("riding").getAsBoolean());
        assertTrue("the re-mounted entity must be the ship's seat dummy: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertEquals("the client must have followed the crew into the target cell",
                targetDim, bot().reportWeather().get("dim").getAsInt());
    }

    // ---- migrated: VSJumpTellsThePilotWhatIsHappeningE2ETest ----

    /** The client-side window counting sky and corridor frames — see {@code RenderFrameWindow}. */
    private static final String RENDER_FRAME_WINDOW =
            "zmaster587.advancedRocketry.test.trace.RenderFrameWindow";

    /** This scenario's frame window, opened on its first reading. Every reader here compares a
     *  count before and after a stimulus, so where the window starts does not enter any verdict. */
    private ClientWindow renderFrames;

    /** Above vanilla's sky-pass floor of 4 chunks; the harness otherwise pins the client at 2. */
private static final int SKY_RENDER_DISTANCE = 8;

    /**
     * How long a render counter is watched for growth, in CLIENT ticks. A window, not a deadline:
     * what is read across it is a frame counter, and a frame counter growing is not an event — so a
     * longer window samples more frames and cannot change whether the assertion holds. The control
     * window in an ordinary cell and the corridor window in flight use the same number, which is
     * what makes the two readings comparable.
     */
    private static final int RENDER_WINDOW_TICKS = 20;

    /**
     * How many times a seat dummy is spawned afresh before the arrangement gives up. The number is
     * the count of SPAWNS, not a tick budget — see {@link #mountTheSeatDummy}.
     */
    private static final int SEAT_MOUNT_ATTEMPTS = 5;

    /**
     * Spawn the ship's pilot-seat dummy and put the bot on it, retried with a FRESH spawn each
     * attempt. Four scenarios in this class carried a copy of these ten lines.
     *
     * <p><b>A retry, not a wait.</b> {@code mount-entity} answers synchronously and completely: it
     * says whether the player's own world held the dummy and, when it did not, whether any loaded
     * world does ({@code playerDim} / {@code foundInDim} / {@code gone}). So there is no event to
     * await here and nothing that arrives later — what another pass buys is a NEW dummy, because the
     * one it asked about is glued to the ship's world position only on its first tick, and a spawn
     * chunk that unloads before that tick leaves the returned id resolving to nothing.</p>
     *
     * <p><b>Raised as an ARRANGEMENT failure.</b> A seat dummy that could not be mounted has
     * disproved nothing about hyperspace transits: it is the fixture that did not come up, and the
     * JUnit XML separates that from a broken product by TYPE rather than by prose.</p>
     *
     * @param probe this scenario's own probe — one scenario reads through an envelope-aware one, and
     *              mixing the two inside a single arrangement is how a server log line ends up
     *              answering a question the JSON envelope was asked
     */
    private void mountTheSeatDummy(Events.Probe probe, int originDim, int seatX, int seatY, int seatZ)
            throws Exception {
        String mountAt = "", mount = "";
        boolean mounted = false;
        // The CLIENT's mark, before anything is ordered: what the caller asserts next is the
        // client's own riding state, and the link below is what says the mount reached it.
        long clientMark = clientEvents().mark();
        for (int attempt = 0; attempt < SEAT_MOUNT_ATTEMPTS && !mounted; attempt++) {
            mountAt = probe.exec("artest vs seat-mount-at " + originDim + " " + seatX + " " + seatY
                    + " " + seatZ);
            scenario().requireArranged("seat-mount-at must spawn the seat dummy: " + mountAt,
                    readBool(mountAt, "ok"));
            mount = probe.exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            // absence is the answer: this is a retry loop, and "not yet" is what it is reading for.
            mounted = Reply.of(mount).boolOr("mounted", false);
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        // BOTH replies, because a failed mount has two unrelated causes and the second command's
        // answer cannot separate them alone: the spawn says whether a dummy was made and whether it
        // was reused, and the mount says whether the player's own world held it — `playerDim`
        // against `foundInDim`, and `gone` when no loaded world has it at all. Retrying against the
        // wrong world otherwise learns the same nothing five times.
        scenario().requireArranged("the bot must mount the pilot-seat dummy (" + SEAT_MOUNT_ATTEMPTS
                + " spawn+mount attempts) at the seat " + seatX + "," + seatY + "," + seatZ
                + " in dim " + originDim + " — spawn=" + mountAt + " mount=" + mount, mounted);
        // WAS `bot().waitTicks(10)`. The server says it seated him; the caller then asserts
        // the CLIENT is riding, and between the two stood a tick budget — which is an
        // assertion about how fast this box replicates, not about the mount. The client
        // publishes its own mount chain, and a chain that ENDS seated is the same claim
        // without the budget. Measured 2026-09-20: the control read `riding:false` on a
        // full-tier run and passed serially, which is what a budget does rather than what a
        // mount does.
        ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);
    }

    /** Put the bot in the origin cell and on the ship's pilot seat. */
private void seatTheBot(int originDim, String shipId) throws Exception {
        PilotSeat seat = findSeat(originDim, shipId)
                .requireFound("the pilot seat must be found in the assembled ship, or the test is vacuous");
        int seatX = seat.seatX, seatY = seat.seatY, seatZ = seat.seatZ;

        String botName = botName(this::exec);

        int sx = (int) Math.round(seat.shipWorldX);
        int sy = (int) Math.round(seat.shipWorldY);
        int sz = (int) Math.round(seat.shipWorldZ);
        // The CLIENT's mark BEFORE the transfer is ordered — see the sibling arrangement above.
        long enterMark = clientEvents().mark();
        String enter = exec("artest space enter " + botName + " " + originDim + " " + sx + " " + sy + " " + sz);
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        awaitClientDim(enterMark, originDim,
                "the mount below seats him on a ship in that cell, and the client renders it");
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());
        // The SERVER's own view of the same player, because the mount below is a server-side
        // operation while the line above reads the CLIENT. They can disagree, and when they do the
        // mount fails as "entity not found": the dummy is spawned in the dim this method was GIVEN
        // and then looked up in whatever world the server has the player in. Measured 2026-09-08 —
        // dummy alive in dim 3, player in dim 4, five retries against a lookup that could never
        // succeed. Read here so an arrangement that did not build says so as an arrangement.
        PlayerState serverSide = PlayerState.read(this::exec);
        scenario().requireArranged("the SERVER must have the player in the transit origin cell too —"
                + " the client says " + originDim + " and the seat dummy is spawned there, so a"
                + " server-side dim that differs makes the mount below unreachable rather than"
                + " flaky: " + serverSide.raw(), serverSide.dim == originDim);

        mountTheSeatDummy(this::exec, originDim, seatX, seatY, seatZ);
        assertTrue("the bot must be seated on the ship BEFORE the jump (control): "
                + bot().reportRidingEntity(), bot().reportRidingEntity().get("riding").getAsBoolean());
    }

    /**
     * The Free Flight HUD as the client last DREW it, or {@code ""} when it has never drawn one.
     *
     * <p>The recorder writes only when the line CHANGES, so the latest record is the current text —
     * asking "since 0" is therefore the right window, not a leak: an unchanged HUD is one whose last
     * change is still what the pilot sees. What the record adds over the field it replaces is a
     * sequence number, so a reader can tell an old line from one that appeared during its own leg.</p>
     */
private String hud() throws Exception {
        String rec = Events.lastRecord(clientEvents().since(0, "ff_hud"));
        return rec == null ? "" : Events.text(rec, "text");
    }

    /** Frames on which this sky renderer ran at all, whatever it decided to draw. */
    private long skyFrames() throws Exception {
        return renderFrameCount("skyFrames");
    }

    /** Frames the hyperspace corridor drew. */
    private long tunnelFrames() throws Exception {
        return renderFrameCount("tunnelFrames");
    }

    /** One count of this scenario's frame window, as the record a peek writes. */
    private long renderFrameCount(String field) throws Exception {
        if (renderFrames == null) {
            renderFrames = ClientWindow.open(bot(), RENDER_FRAME_WINDOW);
        }
        long mark = clientEvents().mark();
        renderFrames.peek();
        String rec = Events.lastRecord(clientEvents().since(mark, "render_frame_window"));
        assertTrue("no render_frame_window record after a peek — the client did not answer, so "
                + field + " has no reading", rec != null);
        return (long) Events.number(rec, field);
    }

    // The class read the client's chat here — `chat()`, a 200-line dump matched for "Jump engaged"
    // and "Arrived", plus the void leg's obituary. All three are gone: a chat line is a RENDERING of
    // a game event, so what they were really asking about is `transit_departed`, `transit_settled`
    // and `player_died`, and those are what the scenarios wait on now. Asserting the sentence
    // instead put the language file, the depth of the client's ring and the harness's own command
    // echoes between the test and its subject.

    @Test
    public void aJumpAnnouncesItselfInChatOnTheHudAndInTheSky() throws Exception {

        // Vanilla runs the sky pass only at renderDistanceChunks >= 4 and the harness pins the client
        // at 2, so without this the sky renderer never executes and every sky reading below would be
        // honestly zero for the wrong reason. The gate is read back off the client's own field rather
        // than assumed. The HUD is deliberately NOT hidden here: it is one of the three subjects.
        com.google.gson.JsonObject rd = bot().setRenderDistance(SKY_RENDER_DISTANCE);
        previousRenderDistance = rd.get("previous").getAsInt();
        assertTrue("the sky pass gate must be open, read back off the client's own field: " + rd,
                rd.get("skyPassEnabled").getAsBoolean());


        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        seatTheBot(originDim, setup.requireShipId());

        // ── CONTROL, in an ordinary cell ────────────────────────────────────────────────────────
        long skyBefore = skyFrames();
        long tunnelBefore = tunnelFrames();
        // WINDOW: skyBefore/tunnelBefore and the two reads after, each asserted as a difference.
        bot().waitTicks(RENDER_WINDOW_TICKS);
        long skyAfter = skyFrames();
        long tunnelAfter = tunnelFrames();
        // The sky renderer must run here at all. Without it "the corridor is drawn in hyperspace"
        // answers two questions with one number, and "the corridor came up" is indistinguishable
        // from "the sky pass never ran".
        assertTrue("this sky renderer must run in an ordinary cell (sky frames " + skyBefore + " -> "
                        + skyAfter + "); nothing else in this test means anything if it does not",
                skyAfter > skyBefore);
        assertEquals("the hyperspace corridor must NOT be drawn in an ordinary cell (corridor frames "
                        + tunnelBefore + " -> " + tunnelAfter + ")",
                tunnelBefore, tunnelAfter);
        assertTrue("the HUD must not name a jump phase before the jump: " + hud(),
                !hud().contains("HYPERSPACE"));

        // ── THE JUMP ────────────────────────────────────────────────────────────────────────────
        // Both marks before the departure, one per log: the crossing is the server's fact, the world
        // rebuild that opens the sky window is the client's, and the two logs number independently.
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // The jump HAS STARTED, as the GAME's own event: `transit_departed`. What stood here was a
        // chat assertion ("Jump engaged", somewhere in the last two hundred lines) — a statement
        // about the messaging plumbing rather than about the game, and one a language-file edit
        // moved. The departure is the fact; the sentence is a rendering of it.
        events.await(mark, "transit_departed", "a jump that starts in silence is indistinguishable"
                + " from a key that did nothing", JUMP_LINK_BUDGET_TICKS);

        // The sky window opens when the CLIENT'S OWN world becomes the corridor — production's own
        // record of the rebuild — and not on the tick the server was told to depart. Between those
        // two he is still standing in the cell he left, drawing that cell's sky, so a baseline taken
        // there would count the crossing rather than the corridor. ARRANGEMENT: being in the
        // corridor is where this leg's three subjects are READ, not one of them.
        driveIntoCorridor(events, mark, clientMark);
        long tunnelAtStart = tunnelFrames();
        scenario().record("tunnelAtStart", tunnelAtStart);

        // WINDOW: tunnelAtStart and tunnelInFlight, asserted as a difference below. What grows across
        // it is a frame COUNTER, which is not an event, so a longer window samples more frames and
        // changes nothing about whether the assertion can hold. Same length as the control window
        // above, so the two readings are comparable. The HUD is NOT read off this window — see its
        // link below.
        bot().waitTicks(RENDER_WINDOW_TICKS);
        long tunnelInFlight = tunnelFrames();

        // The premise, read AFTER the window and before anything measured in it is believed: the jump
        // must still be in the air. An arrival inside the window swaps the corridor's backdrop for
        // the arrived craft's own overlay ("FREE FLIGHT ... SPD 0.0 m/s") and stops the corridor
        // being drawn at all, so the corridor reading above would be about the far end. The loop this
        // replaces guarded the same straddle by re-reading the status mid-iteration, and it was a red
        // 3 runs in 4 before that guard existed.
        TransitStatus stillFlying = TransitStatus.read(this::exec);
        scenario().requireArranged("the jump must still be IN FLIGHT after the render window, or the"
                        + " corridor reading above belongs to the ARRIVED craft rather than"
                        + " to the flight: transit-status=" + stillFlying,
                stillFlying.inTransit >= 1);

        // A LINK on the HUD's own record, from the client mark taken before the departure. `ff_hud`
        // is written whenever the drawn line CHANGES, and the control above read a line naming no
        // jump phase, so a line naming it is owed after that mark; a read at the end of the window
        // was a bet that the first corridor frame had been drawn by then. An arrival cannot answer
        // this wait: the arrived craft's overlay names no jump phase. The LATEST line, not any: a
        // HUD that named the phase for one frame and dropped it mid-flight is the failure.
        clientEvents().awaitMatching(clientMark, "ff_hud",
                seen -> {
                    String last = Events.lastRecord(seen);
                    String text = last == null ? null : Events.text(last, "text");
                    return text != null && text.contains("HYPERSPACE");
                },
                "whose latest drawn line names HYPERSPACE",
                "the HUD must name the jump phase while the ship is in flight, so a pilot with no"
                        + " controls can tell a flight from a hang",
                JUMP_LINK_BUDGET_TICKS);

        assertTrue("the corridor must be drawn in hyperspace (corridor frames " + tunnelAtStart
                        + " -> " + tunnelInFlight + " over " + RENDER_WINDOW_TICKS + " ticks)",
                tunnelInFlight > tunnelAtStart);

        // ── ARRIVAL ─────────────────────────────────────────────────────────────────────────────
        // The arrival message is owed once the transit has SETTLED — the commit that follows the crew
        // being back aboard — and the chain names which link is missing if it never does.
        // The arrival is the last link of the chain itself (`transit_settled`), so the jump ending is
        // asserted here and nowhere else. The chat assertion that used to follow this line said the
        // same thing one layer down, in the language file's words.
        events.assertChain(mark, "the jump this leg watched must FINISH, or its whole flight was"
                + " measured on a transit that never arrived", JUMP_LINK_BUDGET_TICKS,
                PILOTED_JUMP_CHAIN);
    }

    // ---- a crew member on his FEET crosses too ----

    /**
     * The sneak key, which is how a player leaves a seat. Held on the real client so the dismount
     * runs vanilla's own client path ({@code EntityPlayerSP} sending the stop-riding action) rather
     * than a server-side {@code dismountRidingEntity} standing in for it.
     */
    private static final int SNEAK_KEY = org.lwjgl.input.Keyboard.KEY_LSHIFT;

    /**
     * Stand the seated bot up and leave him ON the deck, resolved there. Returns the deck-capture
     * report, so the caller can assert the state the crossing's own enumeration reads.
     *
     * <p>The sneak key is the human's action; the server dismount is a fallback for the run where
     * the key path does not fire (the same shape the deck-capture scenarios use), and it replaces
     * only the TRIGGER — the object dismounted, the frame it lands in and the capture that follows
     * are identical either way.</p>
     *
     * <p><b>Where he LANDS is not part of the subject.</b> Vanilla puts a dismounting rider beside
     * his mount, this fixture's whole deck is 3×3, and the cell around it is void — so on some runs
     * he steps off the edge and there is no crew member on a deck to carry (measured: the control
     * passed twice and failed on the third run, with the capture reporting not even aboard by
     * containment). Standing aboard is this scenario's PRECONDITION, not its mechanism, so the
     * arrangement re-drops him over the deck until it takes, geometry-robustly rather than
     * assuming one landing spot.</p>
     */
    private DeckCapture standTheBotOnTheDeck(double shipX, double shipY, double shipZ) throws Exception {
        // One CLIENT mark for both routes: whichever gets him off, his own `dismountRidingEntity`
        // is the record. The sneak route is a link whose expiry is RECORDED rather than failed — the
        // javadoc above says the trigger is not the subject — and the probe route that follows is
        // REQUIRED, as the link it is. The key is held until the record arrives, not for a fixed
        // span: how long sneak is held is no part of what either route is asked.
        long clientMark = clientEvents().mark();
        bot().holdKey(SNEAK_KEY);
        try {
            clientEvents().await(clientMark, "dismount",
                    "the sneak key must take him off his seat", 80);
        } catch (AssertionError sneakDidNotTake) {
            scenario().record("sneakDismountLink", sneakDidNotTake.getMessage());
        } finally {
            bot().releaseKey(SNEAK_KEY);
        }
        if (Events.records(clientEvents().since(clientMark, "dismount")).isEmpty()) {
            exec("artest player dismount");
            awaitClientDismount(clientMark, "the crew member must actually leave his seat, or there"
                    + " is no crew member on his feet to carry", 80);
        }
        scenario().requireArranged("...and he must still be off it when the capture is taken: "
                + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());
        // The DROP is a stimulus and the capture is a LINK, so the two go into the pair built for
        // that: `awaitField(…, stimulus)` re-drops him between reads until his own client
        // records the deck taking him. What stood here re-read `deck-capture` after each drop and
        // handed the last reading back for a caller to assert on — the same reading the loop had
        // just exited on.
        //
        // The client's log, because the resolver that claims a body is the client's (the base's own
        // note on this family). Keyed on nothing but the type: this helper does not know the ship's
        // id, and the caller's assertions that follow name it.
        // The 40-tick STIMULUS PERIOD is the fall: he has to be left alone long enough to land
        // between re-drops, while the record is still read every five ticks. At the read cadence he
        // would be teleported eight times per fall and never reach the deck. Where he LANDS is not
        // the subject — this fixture's deck is 3x3 and the cell around it is void — so the stimulus
        // puts him over it again rather than assuming one landing spot.
        long captureMark = clientEvents().mark();
        try {
            clientEvents().awaitMatching(captureMark, "deck_carry",
                    reply -> !Events.records(reply).isEmpty(), "for any body",
                    "the deck never took the dropped body", 240,
                    () -> exec("tp @a " + shipX + " " + (shipY + 4.0) + " " + shipZ + " 0 0"), 40);
        } catch (AssertionError neverCaught) {
            // Not a verdict here either: the caller reads the capture and makes its own claims, and
            // one of them is about a body the deck did NOT take. Swallowing keeps that case its to
            // decide, and the reading below is what it decides from.
            scenario().record("deckCarryLink", neverCaught.getMessage());
        }
        // Nothing is asserted here and nothing is returned as a verdict: the caller reads the
        // capture for its own claims. That is the difference from the loop this replaces, which
        // handed back the reading it had just exited on for a caller to re-assert.
        return DeckCapture.read(this::exec);
    }

    /**
     * The dimension the CLIENT is in, or a readable failure when it is in none.
     *
     * <p>{@code reportWeather().get("dim")} is absent whenever the client has no world — it was
     * disconnected, it died into a respawn screen, or it never joined — and reading it blind turns
     * every one of those into an {@code NullPointerException} on a line about dimensions. That is a
     * verdict nobody can act on: it names neither which of the three happened nor that the subject
     * left the game at all. This says which, and it says it where the reading is taken.
     */
    private int clientDim(String where) throws Exception {
        JsonObject weather = bot().reportWeather();
        if (weather.get("dim") == null) {
            JsonObject state = bot().reportState();
            org.junit.Assert.fail("the CLIENT has no world at " + where + ", so it is out of the game"
                    + " rather than in the wrong dimension — a death into a respawn screen and a"
                    + " disconnect both look like this, and neither is a statement about the subject."
                    + " weather=" + weather + " state=" + state);
        }
        return weather.get("dim").getAsInt();
    }

    /** Forward, on the real client — the key a player walks with. */
    private static final int FORWARD_KEY = org.lwjgl.input.Keyboard.KEY_W;

    private static final String ARRIVAL_CUT = "arrivalCut";

    private static final String DEPART_LANE = "departLane";

    /**
     * How long the void gives a crew member who is aboard nothing before it takes him, in server
     * ticks — read from production so the waits below cannot drift away from the budget they are
     * about. Mirrors `HyperspaceVoid.GRACE_TICKS`.
     */
    private static final int VOID_GRACE_TICKS = 200;

    /**
     * The name production gives the void's own {@code DamageSource} — the field {@code player_died}
     * carries as {@code source}, and the only thing that tells this mechanic's kill from the fall
     * that would happen anyway. Mirrors {@code HyperspaceVoid.VOID_OF_HYPERSPACE}.
     */
    private static final String VOID_DAMAGE_TYPE = "arHyperspaceVoid";

    /**
     * How much longer than the void's own grace this class waits before calling a verdict, in ticks.
     *
     * <p>One quantity, named once, because all three uses are the same claim: "the countdown has had
     * every chance to fire". It was written out as a bare {@code 60} in three places — the livable
     * leg's span, the lethal leg's deadline, and the flight duration derived from both — where the
     * three could drift apart and only the last of them would notice.</p>
     */
    private static final int VOID_GRACE_MARGIN_TICKS = 60;

    /**
     * How long the flight must LAST, in server ticks, for the scenario that stands a crew member up
     * in hyperspace and waits the void out on him.
     *
     * <p><b>A different quantity from {@link #PARK_SPEED}'s flight, and deliberately not merged with
     * it.</b> Every transit in the server's live stack is advanced once per server tick
     * ({@code SpaceSubsystemEvents.onServerTick}), so a flight is a DURATION this scenario has to
     * fit inside rather than a backdrop it can ignore. The ordinary fixture speed buys 170 ticks and
     * that scenario deliberately spends more than that standing still: the arrival then lands
     * mid-scenario and takes the deck out from under the man, which reads at {@code deck-capture} as
     * exactly the "not tracked" the void itself produces — two opposite findings behind one
     * message. Measured 2026-09-08, {@code inTransit:0} at the gate.</p>
     *
     * <p>Derived from the budgets the scenario actually spends, never chosen: the worst-case drive
     * into the corridor ({@link #JUMP_LINK_BUDGET_TICKS}), the void's grace budget twice — once
     * aboard and once after he has stepped off — and the bounded readings between them; then
     * doubled, so a loaded machine's slower probe round-trips cannot make the flight the shorter of
     * the two.</p>
     */
    private static final long LIVABLE_FLIGHT_TICKS =
            2L * (JUMP_LINK_BUDGET_TICKS + 2L * (VOID_GRACE_TICKS + VOID_GRACE_MARGIN_TICKS) + 260L);

    /**
     * The speed that buys {@link #LIVABLE_FLIGHT_TICKS} over the distance this fixture jumps —
     * blocks per tick, the unit {@code transit-begin} takes.
     */
    private static final long LIVABLE_FLIGHT_SPEED =
            FIXTURE_CELL_SPACING_BLOCKS / LIVABLE_FLIGHT_TICKS;

    /**
     * JUMP-2 and JUMP-8, in one flight, because the first is the honest control for the second:
     * <b>hyperspace is a place you live in, and stepping off your ship there kills you.</b>
     *
     * <p>A crew member stands up mid-flight, stays on his deck for longer than the void's whole
     * budget, and is fine; then he walks off the hull and dies. Without the first leg the second
     * proves only that something in hyperspace kills people; without the second the first proves only
     * that nothing does.</p>
     *
     * <p><b>Livable also means it still LOOKS like a flight.</b> Hyperspace has no bodies in its sky
     * and its descent ring is deliberately suppressed, so the corridor is the only thing that tells a
     * crew member the ship is moving. It is drawn by the client's own sky renderer, and this is the
     * scenario that puts a crew member in hyperspace on his FEET — so the corridor is read here, in
     * the same window that proves he is alive on his deck. The ring's suppression is NOT re-pinned
     * here: that is the seated scenario's subject, and its baseline is order-sensitive.</p>
     */
    @Test
    public void aCrewMemberLivesInHyperspaceUntilHeStepsOffHisShip() throws Exception {

        // The void exempts creative and spectator on purpose, so the mode is SET rather than assumed:
        // in either of them this scenario could only ever come back "he survived".
        exec("gamemode survival @a");

        // Vanilla runs the sky pass only at renderDistanceChunks >= 4 and the harness pins the client
        // at 2, so without this the sky renderer never executes and every corridor reading below is
        // honestly zero for the wrong reason. Read back off the client's own field rather than
        // assumed; restored by this family's reset, not by an @After.
        JsonObject rd = bot().setRenderDistance(SKY_RENDER_DISTANCE);
        previousRenderDistance = rd.get("previous").getAsInt();
        assertTrue("the sky pass gate must be open, read back off the client's own field: " + rd,
                rd.get("skyPassEnabled").getAsBoolean());

        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);
        seatTheBot(originDim, setup.requireShipId());

        // ── CONTROL, in the origin cell ─────────────────────────────────────────────────────────
        // Two readings the hyperspace ones are read against. Without the first, "the corridor did not
        // advance" cannot be told from "this renderer never ran"; without the second, "the corridor
        // advanced in hyperspace" is a first reading rather than a change.
        long skyInCell = skyFrames();
        long tunnelInCell = tunnelFrames();
        // WINDOW: skyInCell/tunnelInCell and the two reads after, each asserted as a difference.
        bot().waitTicks(20);
        long skyAfterInCell = skyFrames();
        long tunnelAfterInCell = tunnelFrames();
        assertTrue("CONTROL: this sky renderer must run in an ordinary cell, or every corridor"
                        + " reading below is a zero for the wrong reason (sky frames " + skyInCell
                        + " -> " + skyAfterInCell + ")",
                skyAfterInCell > skyInCell);
        assertEquals("CONTROL: the hyperspace corridor must NOT be drawn in an ordinary cell (corridor"
                        + " frames " + tunnelInCell + " -> " + tunnelAfterInCell + ")",
                tunnelInCell, tunnelAfterInCell);

        // A third control, for the machinery leg in hyperspace further down: the same recorder, the
        // same channel and the same way of deriving the key, asked of a ship that is plainly alive
        // in an ordinary cell. Without it, silence in hyperspace cannot be told from a key nobody
        // ever writes under - and the two ask for opposite investigations.
        PilotSeat cellSeat = findSeat(originDim, setup.requireShipId());
        String cellAfcKey = originDim + " " + cellSeat.afcX
                + " " + cellSeat.afcY + " " + cellSeat.afcZ;
        long cellTileTicks = gameSeen(exec("artest vs motion-trace " + cellAfcKey));
        // WINDOW: cellTileTicks and cellTileTicksAfter, both in the message, asserted as a rise.
        bot().waitTicks(20);
        long cellTileTicksAfter = gameSeen(exec("artest vs motion-trace " + cellAfcKey));
        assertTrue("CONTROL: the ship's flight computer must be recording server ticks in an"
                        + " ordinary cell, or the hyperspace reading below is a zero for the wrong"
                        + " reason (samples " + cellTileTicks + " -> " + cellTileTicksAfter
                        + " for " + cellAfcKey + ")",
                cellTileTicksAfter > cellTileTicks);

        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 "
                + LIVABLE_FLIGHT_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // Fly as far as hyperspace and then stop TOUCHING the transit — the server flies it from
        // here, which is why this leg is given a jump long enough to hold everything below inside
        // it. The interval this scenario is about is a long flight, not a halted one: a jump that
        // never advances is not a state production can reach, so measuring "hyperspace is livable"
        // against one would be measuring an artefact of the harness.
        int hyperDim = driveIntoCorridor(events, mark, clientMark);

        // The seat's own world position, as the CLIENT renders it — the deck reference for the
        // stand-up, read off the mount rather than from a probe that would need the lane's anchor.
        JsonObject mount = bot().reportRidingEntity();
        scenario().requireArranged("he must still be riding his seat on arrival in hyperspace: " + mount,
                mount.get("riding").getAsBoolean());
        double deckX = mount.get("posX").getAsDouble();
        double deckY = mount.get("posY").getAsDouble();
        double deckZ = mount.get("posZ").getAsDouble();

        // ── JUMP-2: the interval is livable ─────────────────────────────────────────────────────
        DeckCapture capture = standTheBotOnTheDeck(deckX, deckY, deckZ);
        assertTrue("a crew member must be able to leave his seat IN HYPERSPACE and be resolved on his"
                + " deck there — that is what makes the flight an interval rather than a cutscene: "
                + capture.raw(), capture.alreadyTracked);

        // ── The visible half: the backdrop belongs to the FLIGHT, not to the seat ────────────────
        // The arrangement first, because both facts are the axis of the claim: he must be off his
        // seat (or this is the seated case again) and still in hyperspace (or this is a cell's sky).
        JsonObject standing = bot().reportRidingEntity();
        scenario().requireArranged("he must be on his FEET, or this leg is the seated case again: "
                + standing, !standing.get("riding").getAsBoolean());
        int standingDim = bot().reportWeather().get("dim").getAsInt();
        scenario().requireArranged("he must still be in hyperspace, or this reads a cell's sky —"
                + " corridor " + hyperDim + ", client " + standingDim, standingDim == hyperDim);

        long skyStanding = skyFrames();
        long tunnelStanding = tunnelFrames();
        // WINDOW: the standing and after-standing reads of both counters, all four in the messages.
        bot().waitTicks(20);
        long skyAfterStanding = skyFrames();
        long tunnelAfterStanding = tunnelFrames();
        // The renderer itself, first: a corridor counter standing still means nothing until the
        // renderer that would move it is known to be running.
        assertTrue("CONTROL: the sky renderer must still be running in hyperspace, or 'the corridor"
                        + " stopped' cannot be told from 'nothing renders here' (sky frames "
                        + skyStanding + " -> " + skyAfterStanding + ")",
                skyAfterStanding > skyStanding);
        assertTrue("the corridor must keep being drawn for a crew member who has LEFT HIS SEAT: it"
                        + " is the only thing in hyperspace that says the ship is moving — no bodies"
                        + " are synced there and the descent ring is suppressed — so a backdrop that"
                        + " stops when he stands up reads as the flight itself having stopped"
                        + " (corridor frames " + tunnelStanding + " -> " + tunnelAfterStanding
                        + ", sky frames " + skyStanding + " -> " + skyAfterStanding + ")",
                tunnelAfterStanding > tunnelStanding);

        // ── The machinery half: his ship is a LIVE world, not a paused one ──────────────────────
        // "Livable" is not only about him being able to move: the ship's tile entities have to TICK
        // during the flight, which is what makes the interval a place where things
        // keep working rather than a freeze-frame he happens to be standing in. The flight computer
        // is the tile to ask, because its per-tick recorder is keyed on dimension AND subspace
        // position — so the answer is about THIS ship in THIS world and cannot be a global counter
        // answering for whatever else the server is doing.
        // Asked of THIS hull by name. The deck coordinates the client renders are a point in a
        // SHARED world — every scenario in this class parks its craft in the same hyperspace — so a
        // lookup from them resolves the yard nearest that point, which is a different lane's ship
        // whenever the lanes are closer than the caller assumed.
        String hyperShipId = ShipIdentity.awaitPhysicsIdOf(this::exec, hyperDim, parkedHullName,
                20, () -> bot().waitTicks(5));
        PilotSeat hyperSeat = findSeat(hyperDim, hyperShipId);
        int afcX = hyperSeat.afcX;
        int afcY = hyperSeat.afcY;
        int afcZ = hyperSeat.afcZ;
        String afcKey = hyperDim + " " + afcX + " " + afcY + " " + afcZ;
        long tileTicksBefore = gameSeen(exec("artest vs motion-trace " + afcKey));
        // WINDOW: tileTicksBefore and tileTicksAfter, both in the message, asserted as a rise.
        bot().waitTicks(20);
        long tileTicksAfter = gameSeen(exec("artest vs motion-trace " + afcKey));
        assertTrue("the ship's flight computer must keep TICKING while the ship is parked in"
                        + " hyperspace — a jump during which the ship's machinery stops is a"
                        + " cutscene with a player standing in it (server-tick samples "
                        + tileTicksBefore + " -> " + tileTicksAfter + " for the computer at "
                        + afcX + "," + afcY + "," + afcZ + " in dim " + hyperDim + "; the SAME"
                        + " instrument read " + cellTileTicks + " -> " + cellTileTicksAfter
                        + " for the same ship in its origin cell, so the recorder and the key"
                        + " derivation are not what is silent here)",
                tileTicksAfter > tileTicksBefore);
        // CONTROL: the same question one thousand blocks along, where no tile of this ship lives.
        // Without it a rising count could be the recorder answering for the whole server rather
        // than for the computer this leg named.
        long noTileThere = gameSeen(exec("artest vs motion-trace "
                + hyperDim + " " + (afcX + 1000) + " " + afcY + " " + afcZ));
        assertEquals("CONTROL: a subspace address with no tile at it must report no ticks at all,"
                + " or the reading above describes the server and not this ship", 0L, noTileThere);

        // ...and stay there. The span is the void's OWN budget plus a margin, so "he is alive" is a
        // statement about the countdown having had every chance to fire rather than about a window
        // too short to reach it.
        // EXPERIMENT: the dose is the void's grace plus a margin, in SERVER ticks — the clock the
        // countdown runs on (`HyperspaceVoid.onServerTick`). Not client ticks: the two JVMs tick
        // independently, and a server running slower than its client would end a client-counted
        // span before the countdown reached its budget — "he survived" green for a void that never
        // got the chance, which is the silent direction.
        // Overshoot lengthens the exposure — the strict direction — and the premise gate below
        // catches the one thing a longer span can do wrong, an arrival.
        GameTicks.advance(serverClient(), GameTicks.server(),
                VOID_GRACE_TICKS + VOID_GRACE_MARGIN_TICKS);
        // The PREMISE, gated before the subject is read: this leg is about a man standing in a
        // FLIGHT, so the flight has to still be happening. The server advances every transit in the
        // live stack on its own tick, so a window measured against the void's budget is also a
        // window the jump can finish inside — and an arrival that lands here takes the deck out from
        // under him, which reads at `deck-capture` as exactly the same "not tracked" the void would
        // produce. Two opposite investigations behind one message; this line separates them.
        TransitStatus stillFlying = TransitStatus.read(this::exec);
        scenario().requireArranged("the jump must still be IN FLIGHT after the void's whole budget,"
                + " or this leg is reading an ARRIVAL rather than the void — the ship left with the"
                + " deck he was standing on. transit-status=" + stillFlying.raw(),
                stillFlying.inTransit >= 1);
        JsonObject aboardState = bot().reportState();
        DeckCapture aboardCapture = DeckCapture.read(this::exec);
        assertTrue("a crew member standing on his own deck in hyperspace must not be taken by the"
                + " void — he is aboard, and the danger is for bodies that are not: client="
                + aboardState + " capture=" + aboardCapture.raw(),
                aboardState.get("health").getAsFloat() > 0f);
        assertTrue("...and he must still be resolved on that deck after the whole budget: "
                + aboardCapture.raw(), aboardCapture.alreadyTracked);
        // On HIS parked hull. Every scenario in this class parks a craft in this same hyperspace
        // world, and the negative leg below turns on him LEAVING the deck — so a capture held by a
        // neighbouring hull would make both legs read the wrong body's relationship to the void.
        aboardCapture.requireAnchoredOn( hyperShipId,
                "the deck that keeps him out of the void must be his own ship's");

        // ── JUMP-8: the void is lethal ──────────────────────────────────────────────────────────
        // He walks off. Nothing prevents him — the danger is the mechanic, not a wall. The teleport
        // is a fallback for the run where the walk does not clear this fixture's 3x3 deck; it
        // replaces the WAY he leaves, never the leaving, which is what the mechanic reads.
        // Walking off is a LINK — the deck RELEASES him, and his own client records it. The walk
        // is a fixed stimulus rather than a wait-until, because it is best-effort by design (this
        // fixture's deck is 3x3 and the comment above says the teleport replaces the WAY he leaves,
        // never the leaving); the record is then read once, and the teleport follows if it is
        // absent. What stood here polled `deck-capture` every five ticks for the state that record
        // announces.
        long offMark = clientEvents().mark();
        bot().holdKey(FORWARD_KEY);
        // STIMULUS: the length of the walk is how far he is carried from the hull, which is what the
        // fallback teleport's thirty blocks stand in for — five seconds of W take a body far clear
        // of a 3x3 deck, so the one the void is then asked about is nowhere near a deck that could
        // take him back. Stopping at the release record would leave him at the deck's edge.
        bot().waitTicks(100);
        bot().releaseKey(FORWARD_KEY);
        if (Events.records(clientEvents().since(offMark, "deck_released")).isEmpty()) {
            // The fallback that gets him off the hull when walking did not: a teleport, and its far
            // side is the client applying it. Twenty ticks here were the same bet as everywhere
            // else, and the read below — "he must actually be off the hull" — is the assertion they
            // were deciding.
            long shoveMark = clientEvents().mark();
            exec("tp @a " + (deckX + 30.0) + " " + deckY + " " + (deckZ + 30.0) + " 0 0");
            awaitClientPlacedNear(shoveMark, deckX + 30.0, deckZ + 30.0,
                    "the body must be off the hull ON THE CLIENT, which is the side whose resolver"
                            + " decides whether the deck still holds him");
        }
        DeckCapture offHull = DeckCapture.read(this::exec);
        scenario().requireArranged("he must actually be off the hull, or the void has nothing to take: "
                + offHull.raw(), !offHull.alreadyTracked);

        // Arm the channel the verdict is read out of, immediately before the wait and with no server
        // command after it: the harness echoes a marker line into this same chat for every command
        // it runs.
        long deathMark = events.mark();

        // THE VERDICT, and it is production's own record of the kill: WHO died and OF WHAT.
        //
        // The cause is the whole claim. A body that steps off a lane at Y=128 in an all-air world
        // FALLS, and vanilla's own out-of-world damage kills it inside this same window — so "he is
        // dead" is satisfied by a build in which this mechanic does nothing at all, and the countdown
        // loop this replaces reached its verdict on exactly that reading. `player_died` carries the
        // damage type production named the source with, so the discriminating fact is IN the wait
        // rather than checked afterwards against prose in a chat ring.
        //
        // The deadline is the void's own grace plus the same margin the livable leg was given.
        String death = events.awaitField(deathMark, "player_died", "source", VOID_DAMAGE_TYPE,
                "leaving your ship in hyperspace must kill you, and the VOID must be what took him —"
                        + " the same body survived the same span aboard, so this is the step off the"
                        + " hull and not the flight",
                VOID_GRACE_TICKS + VOID_GRACE_MARGIN_TICKS);

        // ...and it happened IN hyperspace, read off that same record: a man killed by this source
        // anywhere else would be a different finding wearing the right name.
        assertEquals("the void must have taken him in the hyperspace world, not somewhere that"
                        + " happens to share its damage source: " + death,
                hyperDim, (int) Events.number(Events.lastRecord(death), "dim"));

        // Fly the jump out. Every other scenario here ends with its ship delivered, and this one
        // deliberately stopped ticking mid-flight — leaving a hull parked in the world every later
        // scenario shares, with a crew record for a player who is no longer alive to be re-seated.
        // Ending the transit puts the shared world back the way this scenario found it.
        // The one pump left in this class, and it is an ACCELERATOR rather than a wait: nothing here
        // is being observed. This scenario shares its world, and it ends the jump to put that world
        // back the way it found it -- fast-forwarding past a flight nobody is watching is exactly
        // what the verb is for now that the server drives transits on its own.
        // Kept even though this family's reset flies out any leftover jump before the NEXT scenario:
        // that net cannot cover the last scenario in the class, whose leftover would leave the class
        // with a jump still in the air for whoever shares this client next. Not a duplicate — a
        // different boundary.
        // The bound comes from the flight this scenario ASKED FOR rather than from a round number:
        // ten accelerated ticks an iteration, over the whole jump, with the same doubling the
        // duration itself carries. A cleanup budget shorter than the flight leaves a hull parked in
        // the world every later scenario in this class shares — which is a red somewhere else,
        // blamed on something else.
        // The accelerator is a STIMULUS and the settling is a LINK, which is the pair
        // `awaitMatching(…, stimulus)` exists for: a mechanic that advances only on a tick nobody is
        // running, so the test has to keep driving it WHILE it waits for the record.
        //
        // KEYED ON THE DURABLE NAME, and that is the whole lesson of getting it wrong once. The
        // first cut asked for `setup.requireShipId()` — the SUBSTRATE's id — and the wait expired with
        // the log full of transit records. `ledgerSettle` is keyed by the identity the ledger keeps,
        // which `TransitSetup.durableId` is documented to be: "the setup mints this on the
        // pad, onto the flight computer, and SETTLES THE LEDGER UNDER IT … Reading either one as the
        // other answers found:false — that is not a missing ship, it is the wrong question."
        //
        // The budget's arithmetic, since it is not the loop's: the wait drives the stimulus once per
        // five ticks of budget and each stimulus accelerates ten, so half of LIVABLE_FLIGHT_TICKS of
        // budget delivers the whole flight the scenario asked for.
        try {
            events.awaitField(mark, "transit_settled", "ship", setup.requireDurableId(),
                    "the jump must be flown out before this scenario returns: it shares its"
                            + " hyperspace with every other scenario in this class, and a transit"
                            + " left running parks a hull there with a crew record for a player who"
                            + " is no longer alive to be re-seated",
                    (int) (LIVABLE_FLIGHT_TICKS / 2L),
                    () -> exec("artest space transit-tick 10"));
        } catch (AssertionError never) {
            // ARRANGEMENT, like the family reset that does the same job before the NEXT scenario:
            // this scenario's own verdict was reached above, and a world it failed to put back is a
            // statement about the fixture rather than about the void.
            scenario().arrangementFailed(never.getMessage() + " | transit-status="
                    + exec("artest space transit-status"));
        }
    }

    /**
     * JUMP-3: both crossings carry every member of the transit crew, in whatever posture he is in.
     *
     * <p>The seated sibling above pins the same contract for a pilot in a chair. This one puts the
     * crew member on his FEET — the posture the crossing's enumeration used to miss entirely, since
     * it walked seat dummies and a standing player rides nothing — and asks the same question of the
     * same instrument: which world is the CLIENT in while the ship is en route, and then the same
     * question again at the far end, because the clause is about BOTH crossings.</p>
     *
     */
    @Test
    public void aWalkingCrewMemberTravelsWithHisShipThroughHyperspace() throws Exception {


        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Board the way every other scenario here boards (seat + its own control), then stand up.
        // The ship's world position is read for the stand-up arrangement's re-drop, not asserted on.
        PilotSeat seat = findSeat(originDim, setup.requireShipId());
        seatTheBot(originDim, setup.requireShipId());
        DeckCapture capture = standTheBotOnTheDeck(seat.shipWorldX, seat.shipWorldY, seat.shipWorldZ);

        // ── CONTROLS, all three before the stimulus ─────────────────────────────────────────────
        // Each one can fail, and each failure would make the in-flight reading vacuous in its own
        // way: a crew member still in his chair is the seated case again; one who is not resolved on
        // the deck is not aboard by the definition the crossing enumerates on; one already outside
        // the origin cell has nowhere to be carried from.
        assertTrue("CONTROL: the crew member must be off his seat before the jump: "
                + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());
        assertTrue("CONTROL: the server must hold a deck capture for him — that is what 'aboard on"
                + " his feet' MEANS to the crossing, and without it this test would be about a"
                + " player standing in a void cell: " + capture.raw(),
                capture.alreadyTracked && !capture.hullStand);
        // ...on the ship that is about to JUMP. The crossing enumerates the bodies aboard ONE hull,
        // so a capture held by any other craft in the cell means the crew member is not in the set
        // under test at all, and every reading downstream would be about somebody it never carried.
        //
        // `TransitSetup.shipId` is ALREADY the physics id — the setup returns the assembler's own answer —
        // and the capture's anchor is a physics id too, so the two compare directly. The durable name
        // is a SEPARATE field of that reply and is what the far end needs; see the arrival below.
        capture.requireAnchoredOn( setup.requireShipId(),
                "CONTROL: the deck he stands on must be the ship this jump is performed with");
        assertEquals("CONTROL: he must be in the origin cell before the jump", originDim,
                bot().reportWeather().get("dim").getAsInt());

        // ── THE JUMP ────────────────────────────────────────────────────────────────────────────
        // Both marks before the departure, one per log — the crossing is the server's fact, the
        // world rebuild is the client's, and the two logs number independently.
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // THE CONTRACT: a crossing carries whoever is aboard, standing included — both halves off
        // production's own records, the server seating him on the hull parked in the lane and his
        // own client's world becoming that lane.
        int hyperDim = assertCarriedIntoCorridor(events, mark, clientMark);

        // Everything below is read WHILE the jump is in the air — stated, not assumed, because the
        // far end is the second crossing's subject and it re-establishes him for entirely different
        // reasons. One status reply, read once: the premise and the subsystem's own oracle for where
        // this crew belongs are two fields of the SAME record.
        TransitStatus inFlight = TransitStatus.read(this::exec);
        scenario().requireArranged("the jump must still be IN FLIGHT when his posture is read, or"
                + " this reads the ARRIVAL rather than the carry: transit-status=" + inFlight.raw(),
                inFlight.inTransit >= 1);
        assertEquals("mid-flight the subsystem must place this crew in the hyperspace world"
                + " (crewDim vs hyperDim); the client was carried into dim " + hyperDim
                + "; tick=" + inFlight.raw(),
                inFlight.hyperDim, inFlight.crewDim);

        // ...and he arrives in the posture he left in: carried, not quietly seated on the way.
        //
        // The POSITIVE half first, and it is not decoration. "He is not riding anything" is also what
        // a client whose mount has not been rebuilt after the world change answers — the seated
        // sibling of this scenario reds on exactly that gap — so on its own this clause would be
        // satisfied by the one thing it must not be satisfied by. The server holding a deck capture
        // for him says he is aboard on his FEET, which is the state the crossing enumerates on.
        DeckCapture captureInFlight = DeckCapture.read(this::exec);
        assertTrue("a crew member carried on his feet must be resolved on a deck in the corridor —"
                + " without that, 'he is not riding' is his client not having a mount yet rather than"
                + " a man standing on a hull: " + captureInFlight.raw(),
                captureInFlight.alreadyTracked);
        JsonObject ridingInFlight = bot().reportRidingEntity();
        assertTrue("a crew member who was standing must still be standing in flight, not folded into"
                + " a seat by the carry: " + ridingInFlight + "; deck capture in flight="
                + captureInFlight.raw(), !ridingInFlight.get("riding").getAsBoolean());

        // ── THE SECOND CROSSING ─────────────────────────────────────────────────────────────────
        // The clause is about BOTH crossings, and the two are not the same code path reached twice:
        // the departure boards him onto a ship parked in hyperspace, the arrival re-establishes him
        // on a ship being re-assembled in a cell that may hold other craft. A green on the first
        // says nothing about the second.
        //
        // An arrival that never completes is a statement about the crew: the settle waits for everyone
        // to be back aboard, so a chain that stops before `transit_settled` IS the placement not
        // converging — and the placement's own account of the step it is stuck on is in the log the
        // failure prints (`crew_reseat_blocked`), not in a server log somebody has to go and find.
        events.assertChain(mark, "a crew member on his feet must be carried by BOTH crossings: seated"
                + " on the parked hull for the flight, put back on his deck at the far end, and only"
                + " then the arrival committed", JUMP_LINK_BUDGET_TICKS, PILOTED_JUMP_CHAIN);
        int targetDim = arrivedTargetDim(this::exec);

        // The CLIENT's half of the arrival: the server's placement is a link above; whether his own
        // client followed it into the target cell is a separate link, and it is a RECORD — the world
        // rebuild at the tail of the respawn packet — not a dimension number to sample. The poll this
        // replaces then re-read the capture for its assertions, so the reply a reader diagnosed from
        // was never the reply that decided the test.
        ClientEvents.awaitDim(clientEvents(), clientMark, targetDim,
                "the arrival crossing must carry the crew member on his feet too — his own client"
                        + " must be moved into the TARGET cell", JUMP_LINK_BUDGET_TICKS,
                () -> "the server's chain: " + events.since(mark));
        // ONE reply, for both the verdict and the diagnosis. The chain above ended at the settle,
        // which the server commits only once everyone is back aboard, so this is a read of a state
        // production has already announced rather than a sample of one still converging.
        DeckCapture captureOnArrival = DeckCapture.read(this::exec);
        // The arrival cell may hold other craft — that is exactly why the second crossing is not the
        // first one reached twice — so "back on the deck there" is only the clause's claim if it is
        // HIS deck. The physics id is re-derived from the ship's durable name because a crossing
        // mints a new one; the name is the handle that survives both crossings.
        captureOnArrival.requireAnchoredOn(
                ShipIdentity.awaitPhysicsIdOf(this::exec, targetDim, setup.requireDurableId(),
                        40, () -> bot().waitTicks(5)),
                "the deck he is put back on at the far end must be his own ship's."
                        + " What production SAID it did, so a red here separates a re-seat that named"
                        + " the wrong craft from a capture that drifted off the right one afterwards"
                        + " — `crew_reseated` carries the durable name, `hyperspace_arrival_cut` the"
                        + " physics id of the hull that landed, and `deck_hold_ended` says on which"
                        + " of its three branches the server's deck hold let go and what was holding"
                        + " the body at that instant: "
                        + events.since(mark, "crew_reseated")
                        + " :: " + events.since(mark, "hyperspace_arrival_cut")
                        + " :: " + events.since(mark, "deck_hold_ended")
                        + " :: releases, with production's own reason for each — a capture that was"
                        + " right when the hold let go and wrong when this was read went through one"
                        + " of these: " + events.since(mark, "deck_released"));
        assertEquals("the arrival crossing must carry the crew member on his feet too — his own"
                + " client must be in the TARGET cell: " + captureOnArrival.raw()
                + "; the server's chain: " + events.since(mark),
                targetDim, bot().reportWeather().get("dim").getAsInt());
        assertTrue("...and he must be back ON THE DECK there, not merely in the right world: "
                + captureOnArrival.raw() + "; the server's chain: " + events.since(mark),
                captureOnArrival.alreadyTracked);
        assertTrue("...and still on his feet, never seated late by the arrival: "
                + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());
    }

    /**
     * The corridor is the backdrop of a WORLD, so it is drawn for everyone in that world — not only
     * for whoever happens to be sitting down.
     *
     * <p>The defect: the sky's gate was the jump phase published on the SEAT entity, and that answers
     * 0 for anybody riding nothing. A crew member who stood up mid-flight lost the corridor, and
     * hyperspace has nothing else in its sky (no cell is loaded, so no body is ever synced), so it
     * went empty and motionless — which the reporter read as the jump itself having stopped.
     *
     * <p><b>Three readings, and the first two are what make the third mean anything.</b> No corridor
     * in an ordinary cell; a corridor while SEATED in hyperspace; a corridor still coming while he is
     * on his FEET. Without the middle reading "drawn while standing" cannot be told from "the sky pass
     * never ran", and the sky counter is read in the same window as the tunnel counter for the same
     * reason — it advances on the renderer's first line, before any branch, so a still sky and a still
     * corridor are distinguishable.
     */
    @Test
    public void aStandingCrewMemberStillSeesTheHyperspaceCorridor() throws Exception {

        // Vanilla runs the sky pass only at renderDistanceChunks >= 4 and the harness pins the client
        // at 2, so without this every sky reading below would be honestly zero for the wrong reason.
        // Read back off the client's own field rather than assumed.
        JsonObject rd = bot().setRenderDistance(SKY_RENDER_DISTANCE);
        previousRenderDistance = rd.get("previous").getAsInt();
        assertTrue("the sky pass gate must be open, read back off the client's own field: " + rd,
                rd.get("skyPassEnabled").getAsBoolean());

        exec("gamemode survival @a");

        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);
        seatTheBot(originDim, setup.requireShipId());

        // ── READING 1, in an ordinary cell: no corridor ──────────────────────────────────────────
        long skyInCell = skyFrames();
        long tunnelInCell = tunnelFrames();
        // WINDOW: skyInCell/tunnelInCell and the two reads after, each asserted as a difference.
        bot().waitTicks(20);
        long skyAfterInCell = skyFrames();
        long tunnelAfterInCell = tunnelFrames();
        assertTrue("this sky renderer must run in an ordinary cell (sky frames " + skyInCell + " -> "
                        + skyAfterInCell + "); nothing below means anything if it does not",
                skyAfterInCell > skyInCell);
        assertEquals("the corridor must NOT be drawn in an ordinary cell — it says 'you are in a jump'"
                        + " (corridor frames " + tunnelInCell + " -> " + tunnelAfterInCell + ")",
                tunnelInCell, tunnelAfterInCell);

        // ── INTO HYPERSPACE, then stop driving the jump ──────────────────────────────────────────
        // An un-ticked transit parks its ship in its lane indefinitely, which is the interval this
        // scenario is about: it needs the flight to still be happening while it reads the sky.
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));
        int hyperDim = driveIntoCorridor(events, mark, clientMark);

        // ── READING 2, SEATED in hyperspace: the corridor comes up ───────────────────────────────
        // Throws with the server's own mount/dismount record if he never came back — the arrangement
        // is asserted INSIDE, where the chain that would explain a failure is still readable.
        JsonObject mount = ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);
        long tunnelSeated = tunnelFrames();
        // WINDOW: tunnelSeated and tunnelAfterSeated, both in the message, asserted as a rise.
        bot().waitTicks(20);
        long tunnelAfterSeated = tunnelFrames();
        long drawnSeated = tunnelAfterSeated - tunnelSeated;
        assertTrue("the corridor must be drawn for a SEATED pilot in hyperspace — this is the leg that"
                        + " proves the instrument can see a corridor at all (corridor frames "
                        + tunnelSeated + " -> " + tunnelAfterSeated + " in 20 ticks)",
                drawnSeated > 0);

        // ── THE STIMULUS: he stands up, IN FLIGHT ────────────────────────────────────────────────
        double deckX = mount.get("posX").getAsDouble();
        double deckY = mount.get("posY").getAsDouble();
        double deckZ = mount.get("posZ").getAsDouble();
        DeckCapture capture = standTheBotOnTheDeck(deckX, deckY, deckZ);
        scenario().requireArranged("he must be resolved on his deck in hyperspace, i.e. aboard on his"
                + " feet rather than adrift in a void world: " + capture.raw(),
                capture.alreadyTracked);
        scenario().requireArranged("and off his seat — riding anything at all would make the reading below"
                        + " the seated case again: " + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());

        // ── READING 3, THE CONTRACT: on his feet, the corridor is still coming ───────────────────
        long skyStanding = skyFrames();
        long tunnelStanding = tunnelFrames();
        // WINDOW: the standing and after-standing reads of both counters, all four in the messages.
        bot().waitTicks(20);
        long skyAfterStanding = skyFrames();
        long tunnelAfterStanding = tunnelFrames();
        long skyDrawnStanding = skyAfterStanding - skyStanding;
        long drawnStanding = tunnelAfterStanding - tunnelStanding;
        assertTrue("INSTRUMENT: the sky renderer must still be running in this window, or a still"
                        + " corridor below would be a still SKY and say nothing about the gate"
                        + " (sky frames " + skyStanding + " -> " + skyAfterStanding + " in 20 ticks)",
                skyDrawnStanding > 0);
        assertTrue("a crew member who stood up mid-flight must still see the corridor: hyperspace has"
                        + " nothing else in its sky, so losing it leaves him looking at a dead"
                        + " starfield and reading his own jump as having stopped. Corridor frames "
                        + tunnelStanding + " -> " + tunnelAfterStanding + " in 20 ticks while standing,"
                        + " against " + drawnSeated + " drawn while seated in the same flight; sky"
                        + " frames " + skyStanding + " -> " + skyAfterStanding,
                drawnStanding > 0);
    }

    /**
     * JUMP-4, the posture half: what the arrival returns is the posture the crew member is IN, not the
     * one he had when the jump fired.
     *
     * <p>The defect: the crew is captured ONCE, at the departure cut, and both re-seats
     * replay that frozen record. Hyperspace is livable by JUMP-2 — stand up, walk, use the ship — so a
     * crew member who stood up in the corridor was force-mounted back into the seat on arrival, undoing
     * an entire flight's worth of what the interval invited him to do.
     *
     * <p><b>Why its sibling could not catch this.</b>
     * {@link #aWalkingCrewMemberTravelsWithHisShipThroughHyperspace} stands the crew member up BEFORE
     * the jump, so the departure record already says STANDING and replaying it lands him on the deck —
     * correct behaviour reached by accident. The defect lives in the posture CHANGE, so this scenario
     * boards him seated, commits the jump from the chair, and only then puts him on his feet. That
     * sibling stays the control: it is green on either side of the fix, and this one is not.
     */
    @Test
    public void aCrewMemberWhoStoodUpMidFlightArrivesOnHisFeet() throws Exception {

        exec("gamemode survival @a");

        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Boards SEATED and jumps from the chair: that is what writes a SEATED departure record, and
        // the record is the subject here.
        seatTheBot(originDim, setup.requireShipId());
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // Fly as far as hyperspace and stop driving: the stand-up has to happen mid-flight, between the
        // two cuts, which is the whole point.
        int hyperDim = driveIntoCorridor(events, mark, clientMark);

        // He must have crossed SEATED — a departure record that already said STANDING is the sibling
        // scenario, and it passes on the broken build. Asserted inside, with the chain.
        JsonObject mount = ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);

        // ── THE STIMULUS: off the seat, mid-flight ───────────────────────────────────────────────
        DeckCapture capture = standTheBotOnTheDeck(mount.get("posX").getAsDouble(),
                mount.get("posY").getAsDouble(), mount.get("posZ").getAsDouble());
        scenario().requireArranged("he must be resolved on his deck in hyperspace — aboard on his feet is"
                + " what the arrival is supposed to give back: " + capture.raw(),
                capture.alreadyTracked);
        scenario().requireArranged("and genuinely out of the chair before the arrival: "
                        + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());

        // ── FINISH THE JUMP ─────────────────────────────────────────────────────────────────────
        // The whole chain from the mark taken before the departure: the second cut removes the hull
        // he is standing on, and the arrival may not settle until he is back on it. A chain that stops
        // at `crew_reseated` prints the placement's own account of what it is stuck on.
        events.assertChain(mark, "a crew member who stood up mid-flight must be put back on his deck"
                + " at the far end before the arrival is committed", JUMP_LINK_BUDGET_TICKS,
                PILOTED_JUMP_CHAIN);
        int targetDim = arrivedTargetDim(this::exec);

        // Is he still THERE — a separate question from his posture, and asked first. A body left
        // adrift on the cut hull has only the void's budget before hyperspace takes him, and that
        // budget is SHORTER than the arrival window the chain just waited through, so "adrift" and
        // "put back aboard" are separated here by whether he is alive at all. Without this the loss
        // surfaces as an NPE on a later line about dimensions, which names neither the loss nor the
        // window it happened in.
        JsonObject state = bot().reportState();
        com.google.gson.JsonElement health = state.get("health");
        assertTrue("the crew member was LOST during the arrival window rather than re-established on"
                        + " the ship: state=" + state + "; the server's chain: " + events.since(mark),
                health != null && health.getAsFloat() > 0f);

        // The CLIENT's half of the arrival, as the siblings read it.
        boolean carriedOn = false;
        for (int i = 0; i < 60 && !carriedOn; i++) {
            bot().waitTicks(2);
            carriedOn = clientDim("the arrival poll") == targetDim
                    && DeckCapture.read(this::exec).alreadyTracked;
        }
        DeckCapture captureOnArrival = DeckCapture.read(this::exec);
        int arrivedDim = clientDim("the arrival verdict");
        scenario().requireArranged("the arrival must have carried him at all — his own client must be in"
                + " the TARGET cell (" + targetDim + ") before his posture there means anything; it is"
                + " in " + arrivedDim + ": " + captureOnArrival.raw(),
                arrivedDim == targetDim);
        // ── THE CONTRACT, before the arrangement-shaped reading below ───────────────────────────
        // Posture first, deliberately: being off the deck is a CONSEQUENCE of having been seated, so a
        // red that leads with the missing deck capture describes the symptom's shadow. Riding at all is
        // the defect, and the probe says so in one field.
        assertTrue("a crew member who was on his FEET when the ship arrived must arrive on his feet:"
                        + " the arrival may not replay where he was sitting when the jump fired, an"
                        + " entire flight earlier. Riding state on arrival="
                        + bot().reportRidingEntity() + " capture=" + captureOnArrival.raw(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());
        assertTrue("...and he must be back ON THE DECK, not merely in the right world: "
                + captureOnArrival.raw(), captureOnArrival.alreadyTracked);
        // ...HIS deck. This class runs in a shared hyperspace world and arrives into a cell that may
        // hold other craft, so "on a deck" and "on the deck he stood up from" are different claims
        // and only the second is what a crossing is supposed to guarantee.
        captureOnArrival.requireAnchoredOn(
                ShipIdentity.awaitPhysicsIdOf(this::exec, targetDim, setup.requireDurableId(),
                        40, () -> bot().waitTicks(5)),
                "the deck he stands on after the arrival must be his own ship's."
                        + " What production SAID it did, so a red here separates a re-seat that named"
                        + " the wrong craft from a capture that drifted off the right one afterwards,"
                        + " and `deck_hold_ended` says which branch let the hold go: "
                        + events.since(mark, "crew_reseated")
                        + " :: " + events.since(mark, "hyperspace_arrival_cut")
                        + " :: " + events.since(mark, "deck_hold_ended")
                        + " :: releases, with production's own reason for each — a capture that was"
                        + " right when the hold let go and wrong when this was read went through one"
                        + " of these: " + events.since(mark, "deck_released"));
    }

}
