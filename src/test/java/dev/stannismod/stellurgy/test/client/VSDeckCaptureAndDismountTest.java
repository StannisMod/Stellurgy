package dev.stannismod.stellurgy.test.client;

import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;



import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.PlayerShipData;
import dev.stannismod.stellurgy.test.DeckCapture;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipFrameCheck;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.ShipReadiness;

import static org.junit.Assert.assertTrue;

/**
 * The two open tier-2 playtest bugs, pinned against a REAL CLIENT PLAYER on a REAL assembled ship -
 * the subject that can actually exhibit them. Both were reported from a hands-on playtest and neither
 * is reproducible by the earlier suite, which read an armour stand's position through a SERVER probe:
 * a server-only body cannot fall through a deck on the client, and a probe cannot see where the client
 * renders the player. This class drives and observes the client, so it can.
 *
 * <ul>
 *   <li><b>A walking client player on a GROUNDED ship's deck stays on it, not through it.</b> The
 *       maintainer stood on a docked ship and fell through the deck. The subject here is the bot
 *       itself - a walking client whose own client resolves its movement - and the observation is the
 *       Y its client renders, cross-checked against where the server holds it (the honest oracle).</li>
 *   <li><b>Standing up from the pilot seat while hovering keeps the ship up and the pilot aboard.</b>
 *       The maintainer hovered, stood up with Shift, and both fell: the ship dropped and he was left
 *       in the world. The ship's hold is driven by live pilot input, so it dies at dismount, and the
 *       dismounted player is handed to a capture path at the exact moment the deck starts falling.</li>
 * </ul>
 *
 *
 * <h2>One client for all twelve scenarios</h2>
 *
 * <p>Measured 2026-08-07 over the whole ship client tier: these twelve scenarios cost <b>19.4
 * minutes across twelve client boots</b> — 97 s each, of which the body is a small minority. They
 * were the tier's wall-clock floor. Sharing one harness is what removes it, and the base coordinates
 * below are unchanged: the scenarios already stood 100 blocks apart, which is a plot allocator by
 * hand, and each one's ground is the ground its green runs were taken on.</p>
 *
 * <p>Two gates had to become scoped to survive a shared world, both marked at their call sites: the
 * pilot seat is now located INSIDE this scenario's own ship ({@code vs find-seat} at its base)
 * instead of taking the first pilot seat in the world, and "has the ship unloaded?" reads this
 * scenario's own base rather than a whole-dimension ship count that a neighbour's ship answers.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSDeckCaptureAndDismountTest extends AbstractSharedVsClientTest {

    @Override
    protected String subsystem() {
        return "vs-deck-capture";
    }

    private static final String PLAYER_Y = "playerY";
    private static final String OBSTACLES = "shipSupportObstacles";
    private static final String DUMMY_ID = "dummyId";

    private static final String VARIANT = "with-pilot-deck";

    /**
     * How long one link of a deck-capture or ship-lifecycle chain may take. A DEADLINE for a
     * discrete event, not a guess at how long a value takes to settle: production either captures a
     * body, releases it, loads a ship or unloads one — 240 ticks is generous against an eight-fork
     * load and still fails a scenario that never gets there rather than waiting out a budget.
     */
    private static final int DECK_LINK_BUDGET_TICKS = 240;

    /**
     * Attitude-slew windows, in ticks of the hull's WORLD clock — the same numbers these slews were
     * given in client ticks before, moved onto the clock the hull's world advances on. Nothing
     * publishes "the attitude has arrived" (the hold never decides it has), so each slew is a
     * window whose two ends are read and named by its gate; a short one fails loudly as a hull
     * that did not get there, never as a pass.
     */
    private static final int SLEW_WINDOW_TICKS = 120;
    private static final int SIDE_SLEW_WINDOW_TICKS = 160;
    private static final int LONG_SLEW_WINDOW_TICKS = 200;

    /**
     * The half-turn onto the craft's back: 160 ticks, the most the loop it replaced could spend (forty
     * commands four ticks apart), against a measured ~31 ticks for half a turn at the 2 rad/s the
     * hold reaches. It was a loop only because the command was believed to need re-sending; it does
     * not — {@code commandProbeAttitude} keeps its target until {@code force-clear}, so one command
     * stands for the whole window.
     */
    private static final int INVERT_SLEW_WINDOW_TICKS = 160;

    /** The stretch an inverted craft is left alone after its hold is cut, before it is read again:
     *  forty ticks of its world, the number these scenarios have always used. */
    private static final int INVERTED_HOLD_WINDOW_TICKS = 40;

    /** The window a turn command's answer is read over: readings of the hull's rate this many ticks
     *  of its world apart, the largest of which is the measurement. */
    private static final int TURN_WINDOW_GAP_TICKS = 2;

    /** Client ticks a body put down five blocks over a deck is given to land and be captured there:
     *  about three times the thirteen vanilla gravity needs for the drop. */
    private static final int DECK_LANDING_TICKS = 40;

    /**
     * How far the CLIENT's rendering of a body's height may sit from the SERVER's, in blocks, on a
     * LEVEL deck.
     *
     * <p>The TEST'S OWN — a replication tolerance, not a production threshold: nothing in the mod
     * decides how far apart the two sides may be, and the real contract is "the same place". Two
     * blocks is under the height of a body, so anything past it is the failure this class exists
     * for: the client drawing him below the deck the server is holding him on.</p>
     */
    private static final double CLIENT_SERVER_Y_AGREEMENT_BLOCKS = 2.0;

    /**
     * The same quantity on a TILTED or ROLLED deck, where it is wider.
     *
     * <p>Also the test's own, and it is a separate constant rather than a relaxation of the one
     * above because the reason is different: on a tilt the two sides interpolate a body's position
     * through different poses of the same hull, so their disagreement grows with the angle. Keeping
     * them apart is what stops a later "these are both about 2, merge them" from quietly widening
     * the level case.</p>
     */
    private static final double CLIENT_SERVER_Y_AGREEMENT_TILTED_BLOCKS = 3.0;

    /**
     * How far BELOW the hull's own reported altitude a dismounted pilot may settle and still count
     * as having stayed up ON the ship, in blocks.
     *
     * <p>The TEST'S OWN, and a RELATION rather than an altitude: it is a body's standing height
     * plus the thickness of the deck he is on, which is what "he is on it, not under it" means at
     * any altitude. The bound it replaces was an absolute {@code 66.0} — the old ground level plus
     * a block — and it stopped discriminating anything the day the fixture moved into the band.</p>
     */
    private static final double PILOT_MAY_SETTLE_BELOW_HULL_BLOCKS = 4.0;

    /**
     * The angular rate that separates a ship a turn command MOVED from one it left dead, in rad/s.
     *
     * <p>The TEST'S OWN sensitivity bar. Production's own commanded rate is a full order of
     * magnitude above it (the attitude hold slews at about 2 rad/s), so what this refuses is zero
     * and near-zero — a computer that took the command and did nothing — rather than a slow turn.</p>
     */
    private static final double TURN_COMMAND_OMEGA_RAD_PER_S = 0.1;

    /**
     * How far two independent readings of the SAME ship-up direction may disagree, as a unit-vector
     * component.
     *
     * <p>The TEST'S OWN, and the number is small on purpose: both readings come from the same
     * client tick through two different production paths (the movement rotation and the camera
     * quaternion), so they are the same quantity twice and the only honest allowance is float
     * noise. Four assertions in this class compare such a pair.</p>
     */
    private static final double FRAME_AGREEMENT_EPSILON = 0.02;

    /**
     * How far a HELD body may move vertically between two reads, in blocks.
     *
     * <p>The TEST'S OWN: a body that is held does not sink at all, and a block and a half is under
     * the height of one — so what this refuses is a body going DOWN through the deck, while
     * tolerating the sub-block settle of being re-seated on it. The oscillation bound of a body
     * held on a ROLLED deck is the same quantity and the same number.</p>
     */
    private static final double HELD_BODY_Y_MOVEMENT_BLOCKS = 1.5;

    /**
     * How far a station-keeping hull may SAG when its pilot stands up, in blocks.
     *
     * <p>The TEST'S OWN: the contract is that the hold keeps it where it was, so the honest
     * statement is zero. Two blocks is the correction a hold shows; a hull that is actually falling
     * is metres down inside this window.</p>
     */
    private static final double HOVER_SAG_BLOCKS = 2.0;

    /**
     * The vertical velocity below which a hull counts as having STARTED TO FALL, in blocks/tick.
     *
     * <p>The TEST'S OWN, and a SIGN with slack rather than a rate: vanilla gravity alone passes it
     * within a few ticks, so a hull still above it is one something is holding.</p>
     */
    private static final double STARTED_FALLING_VEL_Y = -0.5;

    /**
     * The client/server agreement for a pilot in the ACT of standing up, in blocks.
     *
     * <p>The TEST'S OWN, and deliberately between {@link #CLIENT_SERVER_Y_AGREEMENT_BLOCKS} and
     * {@link #CLIENT_SERVER_Y_AGREEMENT_TILTED_BLOCKS}: here the two sides interpolate a body that
     * is MOVING as well as a hull that is tilted.</p>
     */
    private static final double CLIENT_SERVER_Y_AGREEMENT_DISMOUNT_BLOCKS = 2.5;

    /**
     * How far a hovering hull may sag ACROSS A RELOAD, in blocks.
     *
     * <p>The TEST'S OWN, and wider than {@link #HOVER_SAG_BLOCKS} for a stated reason: a reload
     * re-creates the hull and its hold from NBT, so it sags while the restored computer takes its
     * first corrective ticks. A hull that fell out of the sky is metres down.</p>
     */
    private static final double RELOAD_SAG_BLOCKS = 3.0;

    /**
     * The band of deck-normal Y in which a craft is STEEP BUT STANDABLE.
     *
     * <p>Both ends are the test's own, and both are premises: under 0.25 the craft is too near
     * vertical for a body to stand on at all, and over 0.80 it is near enough level that the tilt
     * the leg is about barely exists. Production draws neither line — it holds whatever attitude it
     * was pointed at.</p>
     */
    private static final double STANDABLE_TILT_MIN_UP_Y = 0.25;
    /** @see #STANDABLE_TILT_MIN_UP_Y */
    private static final double STANDABLE_TILT_MAX_UP_Y = 0.80;

    /**
     * How far past vertical the craft must be for the INVERTED legs, as read on the server.
     *
     * <p>The TEST'S OWN arrangement fact, and strict because those legs' whole subject is the
     * inverted case: -0.85 is about 150 degrees over, well past the point where world-up and
     * ship-up could be confused.</p>
     */
    private static final double INVERTED_UP_Y = -0.85;

    /**
     * The same premise read off the CLIENT's camera state, which is looser on purpose: the camera
     * lags the hull it draws, so the same attitude reads shallower there.
     */
    private static final double INVERTED_UP_Y_ON_CLIENT = -0.4;

    /**
     * The attitude the controller actually SETTLES at when asked to inverted, as deck-up Y.
     *
     * <p>The TEST'S OWN: axis-angle is singular at a full 180, so the hold converges near 135
     * degrees. This asks for what it can reach — deck-up well below horizontal — which is all the
     * consistency check underneath needs.</p>
     */
    private static final double STRONGLY_INVERTED_UP_Y = -0.5;

    /**
     * How far a hard flight-cursor deflection must move the cursor to have REGISTERED, in the
     * cursor's own normalised units.
     *
     * <p>The TEST'S OWN sensitivity bar: a hard deflection drives the cursor toward 1, so what this
     * refuses is an input that did not arrive.</p>
     */
    private static final double CURSOR_DEFLECTED = 0.2;

    /**
     * How far above the rolled deck, along the deck's own normal, the fly-in point is, in blocks. Not
     * chosen: it is the clearance this scenario's green runs were taken at — 3 blocks of world height
     * over the pose at a 45-degree roll, on a craft whose centre of mass sat 0.91 under the deck top
     * (3 × cos45° − 0.91 = 1.21) — kept when the craft changed under it.
     */
    private static final double FLY_IN_DECK_CLEARANCE = 1.21;

    /** The same green-run geometry across the deck: 3 × sin45° = 2.12 blocks along the deck's +X. */
    private static final double FLY_IN_ACROSS = 2.12;

    /**
     * How far from the ship's pose, on X and on Z, the fly-in leg sends the player before the fly-in,
     * in blocks. Two bounds and this sits between them: FAR enough that he is out of the ship's
     * region — the hull's box is about seven blocks across and production's stay margin is four, so
     * any capture is released — and NEAR enough, two chunks, that his client keeps the ship loaded.
     * It was 200, and measured 2026-10-05 that is past the near bound: on the first tick back the
     * client held no ship at all, and he fell onto the deck while it loaded.
     */
    private static final int FLY_IN_AWAY_BLOCKS = 32;

    /**
     * How far the levelled camera roll may move while the ship is STATIONARY, in degrees.
     *
     * <p>The TEST'S OWN: the ship is not moving, so the honest statement is that the roll does not
     * change at all. Five degrees is the float noise of recomputing an Euler angle from a
     * quaternion each frame, and the defect it exists for is the pole — where the same attitude
     * yields wildly different angles between frames.</p>
     */
    private static final double LEVELLED_ROLL_JITTER_DEG = 5.0;

    // The bugs this class exists for are all CLIENT facts — a player falls through a deck on his OWN
    // client while the server holds him on it, which is exactly why an armour stand read through a
    // server probe could never reproduce them. So every wait below reads the base's
    // {@link #clientEvents()}, not {@link #serverEvents()}.

    /**
     * Wait for a ship-lifecycle record naming THIS scenario's ship, or fail printing every such
     * record that DID arrive.
     *
     * <p>{@link Events#await} filters by type only, and on a shared world every neighbour's ship
     * loads and unloads through the same seam — so a bare await would be answered by somebody else's
     * craft. The physics uuid is the join: {@code ship_loaded} / {@code ship_unloaded} /
     * {@code ship_removed} carry {@code vsShip} = {@code ShipData.getUuid()}, and the id this
     * scenario holds is the {@code vsShip} of its own {@code ship_spawned} record — the same field,
     * written from the same uuid at the registry's add.</p>
     *
     * <p>The filter itself now lives on {@link Events#awaitMatching}, which this delegates to: the
     * only thing local is WHICH field joins, and the failure it raises carries the four-cause
     * triage for an empty log that a hand-rolled loop here never printed.</p>
     */
    private String awaitThisShip(Events events, long mark, String type, String what)
            throws Exception {
        return events.awaitMatching(mark, type,
                reply -> Events.countRecords(reply, "vsShip", scenarioShipId) > 0,
                "naming this scenario's ship (" + scenarioShipId + ")", what,
                DECK_LINK_BUDGET_TICKS);
    }

    /**
     * The oldest decision in a {@code deck_gate_explained} reply that the window made about
     * {@code body} with production's breakdown on it, or {@code null} when there is none yet. A
     * record that stopped the window ({@code budgetExhausted}) carries no breakdown and is no
     * decision; one whose breakdown threw says so in {@code gateRead} and is no decision either.
     */
    private static String firstGateDecision(String gateReply, int body) {
        for (String record : Events.records(gateReply)) {
            if (isGateDecisionAbout(record, body)) {
                return record;
            }
        }
        return null;
    }

    /** Whether {@code record} is a window decision about {@code body} carrying production's whole
     *  breakdown. The producer always writes {@code e}; {@code gateRead} is asked for by name
     *  because the budget-exhausted record omits it. */
    private static boolean isGateDecisionAbout(String record, int body) {
        Reply r = Reply.of(record);
        return r.integer("e") == body && r.has("gateRead") && "ok".equals(r.text("gateRead"));
    }

    /**
     * The oldest decision in a {@code deck_gate_explained} reply in which {@code body}'s own client
     * holds it ON this scenario's deck — tracked, anchored on this ship, and not in hull-stand — or
     * {@code null} when there is none yet. Read bare inside the wait's predicate: on a decision whose
     * {@code gateRead} is ok the producer always writes {@code alreadyTracked} and {@code hullStand},
     * and {@code anchorShipId} is non-null exactly when the body is tracked, which is the only time
     * it is read.
     */
    private String firstOnThisDeck(String gateReply, int body) {
        for (String record : Events.records(gateReply)) {
            if (!isGateDecisionAbout(record, body)) {
                continue;
            }
            Reply r = Reply.of(record);
            // the producer always writes alreadyTracked and hullStand on a decision whose gateRead is
            // ok, and anchorShipId is non-null whenever alreadyTracked is true — the only time it is read
            if (r.bool("alreadyTracked") && !r.bool("hullStand")
                    && scenarioShipId.equals(r.text("anchorShipId"))) {
                return record;
            }
        }
        return null;
    }

    /**
     * THIS scenario's ship, by identity — read by {@link #buildShip} off the registry's own
     * {@code ship_spawned} record for the assembly it just queued, and the address every later
     * question uses. A scenario that builds its own ship is TOLD which ship that is; nothing here
     * re-derives that from a position.
     *
     * <p>Every question here used to be "the ship nearest my base, within a radius". That radius is a
     * mitigation and not an identity: these scenarios deliberately tumble, invert and hover their
     * ship, and on a shared client the neighbour built by another scenario is a candidate the whole
     * time. An id has no distance term to be wrong about.</p>
     */
    private String scenarioShipId;

    // ---- Bug: a walking client player on a grounded deck falls through it -----------------------

    @Test
    public void aRealClientPlayerOnAGroundedDeckStaysOnItInsteadOfFallingThrough() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Grounded on purpose: a freshly assembled ship has physics disabled, so it rests where it was
        // built. Its world AABB spans from the deck down to the keel and overlaps the terrain beneath -
        // the exact overlap the playtest fell through - and the deck sits several blocks above the ground,
        // so a fall-through is an unmistakable multi-block drop, not a one-block ambiguity.
        double[] ship = buildShip(site);

        // The subject is the REAL client player. Drop the bot onto the deck and let its OWN client
        // resolve the landing (this is the thing that breaks; an armour stand read via a server probe
        // is not). Mirrors the crew test's drop-and-settle.
        // The landing is a LINK, not a duration: his own client's resolver either takes him onto the
        // deck or it does not, and 80 ticks was a guess at how long that takes. The mark goes before
        // the teleport, so nothing can happen between the stimulus and the read.
        //
        // This used to note that the record was a per-tick COMMIT, so a body the build step had
        // already left standing on this deck satisfied the await at once — and called that
        // acceptable. It was not: that is exactly the defect that cost seven scenarios before the
        // per-tick commit was removed, and this class held two of them. The wait is now on the EDGE, so the teleport has to
        // produce a capture of its own before it returns. What this scenario's bug looks like is NO
        // capture on the client at all while the server holds him, and a capture that existed and
        // was then lost shows up in the release absence over the sink window below.
        Events clientEvents = clientEvents();
        long landingMark = clientEvents.mark();
        long serverLandingMark = serverEvents().mark();
        // The SERVER is read below, and it only knows where he is from his client's movement packets:
        // a client capture is taken while he is still falling (measured: 1.5 above the deck), so a
        // server read after it can find him in the air over the deck — once, red, with the server's
        // body 0.52 up and nothing under its feet. So the link is the client's LANDING on this ship
        // after the teleport applied, and then a fence: the server has handled every packet his
        // client sent up to it. The window's budget is one link's — the landing link may take at
        // most DECK_LINK_BUDGET_TICKS, under the log's 256-deep ring.
        String landed = landOnTheDeckUnderGateWatch(landingMark, serverLandingMark, scenarioShipId,
                "the player's OWN client must resolve him on the deck of THIS grounded ship — a"
                        + " fall-through leaves the client with no landing on it at all, which is the"
                        + " fault this scenario exists for", DECK_LINK_BUDGET_TICKS,
                DECK_LINK_BUDGET_TICKS, ship[0], ship[1] + 4, ship[2]);
        // Carrying this scenario's ship: the record names the hull that took the body, and this class
        // shares its world — a type-only wait returns on a sibling scenario's capture and calls the
        // fall-through "resolved". And over the EPISODE rather than the edge alone: an episode that
        // OPENED and was then let go of would satisfy "it happened" while the server read on the
        // next line sees nobody holding him, so the predicate is the chain — the opening, with no
        // later release and no later entry onto another hull.
        String landing = ShipIdentity.awaitCaptureHeldBy(clientEvents, landingMark, scenarioShipId,
                "the player's OWN client must still hold him on the deck of THIS grounded ship after"
                        + " he landed on it", DECK_LINK_BUDGET_TICKS);
        closeDeckGateWindow();
        System.out.println("[deckcap] grounded client landing=" + landed + " capture=" + landing);

        // Server oracle: does the server capture the standing player on the deck at all, and is the deck
        // solid under his feet in the ship frame? deck-capture prints the whole handles() decision.
        PlayerShipData server = PlayerShipData.read(this::exec);
        DeckCapture capture = deckCaptureOfThisShip(scenarioShipId,
                "the server must resolve him on THIS scenario's grounded ship");
        double serverY = server.playerY;
        System.out.println("[deckcap] grounded server=" + server.raw());
        System.out.println("[deckcap] grounded capture=" + capture.raw());
        assertTrue("server must recognise the client player as aboard the grounded ship: " + server.raw(),
                server.shipLoaded);
        server.requireAboard( scenarioShipId,
                "the server must place him inside THIS scenario's grounded ship");
        assertTrue("server must resolve the player in the ship frame, not hand him to vanilla: " + capture.raw(),
                capture.verdict);
        assertTrue("the deck must be solid under his feet in the ship frame (>0), else he falls "
                + "through: " + capture.raw(), capture.shipSupportObstacles > 0);
        assertTrue("a client player standing on the deck must be on the ground: " + server.raw(),
                server.onGround);

        // Client observation: where does the player's OWN client render him? A client fall-through
        // leaves his client Y well below where the server is holding him on the deck.
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] grounded serverY=" + serverY + " clientY=" + clientY);
        assertTrue("the client must render the player ON the deck where the server holds him, not "
                + "fallen through it: serverY=" + serverY + " clientY=" + clientY,
                Math.abs(clientY - serverY) < CLIENT_SERVER_Y_AGREEMENT_BLOCKS);

        // And he must not keep sinking through it over time. The window stays a window — expiry is
        // not the failure — but the client's resolver records EVERY release with the gate that
        // performed it, so "he was not dropped during it" is now an absence in the log rather than
        // an inference from two Y samples. An absence only means something once the instrument has
        // announced itself, which is what assertInstrumentRan is for.
        long sinkMark = clientEvents.mark();
        // WINDOW: overshoot only gives a sinking body longer to sink, so a slow box makes this
        // stricter, never more lenient.
        advanceServerAndClient(60);
        double clientYLater = bot().reportState().get("playerY").getAsDouble();
        String sinkReleases = clientEvents.since(sinkMark, "deck_released");
        Events.assertInstrumentRan(sinkReleases, "deck_capture_events",
                "the client held the player on the deck for the whole window");
        // THE TEST'S OWN: a body that is held does not sink at all, and a block and a half is
        // under the height of one — so what this refuses is a body going DOWN through the deck
        // between two reads, while tolerating the sub-block settle of being re-seated on it.
        assertTrue("the client player must stay on the deck, not sink through it: " + clientY + " -> "
                + clientYLater, clientY - clientYLater < HELD_BODY_Y_MOVEMENT_BLOCKS);
        assertTrue("the client must not let go of a player standing still on a grounded deck; a"
                + " release here names the gate that dropped him: " + sinkReleases,
                Events.countRecordsWithField(sinkReleases, "reason") == 0);
    }

    // ---- Bug: dismounting mid-hover drops the ship and the pilot --------------------------------

    @Test
    public void standingUpWhileHoveringKeepsTheShipUpAndThePilotOnTheDeck() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        buildAndBoardShip(site);

        // Lift into a real hover with the pilot's own vertical-up key. The delivery link, the window
        // and why the climb is measured rather than awaited all live in the helper.
        hoverOnPilotThrust(scenarioShipId, CLEAR_HOVER_GAIN_BLOCKS);

        // EXPERIMENT: the sag window opens after the coast. The helper returns the instant it cuts,
        // and the hull then coasts UP past its hold (measured +3.07..+4.75 against a hold to +3.0) —
        // a climb that, left inside the window, would cancel the very sag it measures, which is the
        // silent direction. Its vertical velocity at the window's start is printed with the verdict,
        // so a coast still under way is visible.
        // SERVER-ONLY: the coast is the server's physics; the read is the server's ship report at the window's start.
        GameTicks.advanceWorld(serverClient(), 0, 10);
        // The start of the sag window below, read at the instant before the stimulus it measures.
        ShipInfo atWindowStart = shipInfo();
        double shipYPre = atWindowStart.y;
        double velYAtWindowStart = atWindowStart.velY;

        // Dismount exactly as the maintainer did: the real sneak key. (While seated it also feeds the
        // flight brake, but a held sneak still triggers vanilla's dismount.) Confirm on the CLIENT that
        // the player left the seat; fall back to the server dismount only if the key path did not fire.
        // The un-seating is recorded where production performs it — at
        // {@code Entity.dismountRidingEntity}, with the CALLER that performed it — so the wait is on
        // that record rather than on the client's replicated riding flag, and the record's own
        // caller trail says which of the two routes below actually got him out. (The client flag is
        // the replication of a server write; polling it measured the round trip as much as the
        // dismount.)
        Events events = serverEvents();
        long dismountMark = events.markInstrumented();
        Events clientEvents = clientEvents();
        long clientDismountMark = clientEvents.mark();
        boolean dismounted = false;
        String dismountPath = "sneak-key";
        bot().holdKey(Keyboard.KEY_LSHIFT);
        for (int i = 0; i < 40 && !dismounted; i++) {
            advanceServerAndClient(2);
            dismounted = Events.countRecordsWithField(events.since(dismountMark, "dismount"), "mount") > 0;
        }
        bot().releaseKey(Keyboard.KEY_LSHIFT);
        String serverDismount = "";
        if (!dismounted) {
            System.out.println("[deckcap] sneak key did not dismount; using server dismount");
            dismountPath = "sneak-key-then-server-dismount";
            serverDismount = exec("stellurgytest player dismount").replace('\n', ' ');
        }
        // Which PATH was taken is the diagnosis, and this message used to be a bare sentence. Both
        // paths failing is a different fault from the key path alone failing: the first says the
        // player cannot be un-seated at all, the second says only the real key route is dead, which
        // is the one a player actually uses and the one this scenario is about. The record's `by`
        // trail names the un-seater — vanilla's own updateRidden for the sneak key, the probe verb
        // for the fallback — so the two are now told apart by the log instead of by which branch the
        // test happened to take.
        String dismountRecord;
        try {
            dismountRecord = events.await(dismountMark, "dismount",
                    "the pilot must actually leave the seat, and NEITHER route got him out. tried="
                            + dismountPath
                            + (serverDismount.isEmpty() ? "" : " serverDismount=" + serverDismount),
                    DECK_LINK_BUDGET_TICKS);
        } catch (AssertionError neverDismounted) {
            throw new AssertionError(neverDismounted.getMessage()
                    + " | riding=" + bot().reportRidingEntity()
                    + " | capture=" + exec("stellurgytest vs deck-capture"), neverDismounted);
        }
        System.out.println("[deckcap] dismount route tried=" + dismountPath
                + " unSeatedBy=" + Events.lastField(dismountRecord, "by"));

        // Let the now-unmanned ship reveal whether it holds or falls — waited on the flight
        // computer's OWN decision rather than on 40 ticks. With no pilot input it either commands a
        // hover or returns inert, and until that record existed a 2-block drop could not say
        // "station-keeping was off" apart from "the hold engaged and under-thrust".
        String hold = events.await(dismountMark, "unmanned_hold_decided",
                "the flight computer must take an unmanned decision once the pilot stands up — with"
                        + " no record of one, a ship that then falls cannot be told from a ship whose"
                        + " computer never noticed it was unmanned", DECK_LINK_BUDGET_TICKS);

        // The pilot must stay aboard: resolved on the deck in the ship frame, and rendered there by his
        // own client - not dropped into the world. The client's capture is a link and is awaited as
        // one; a seat dismount seeds it, so a client that never TOOK him since the un-seating is the
        // "left in the world" half of the report, named instead of inferred from two heights.
        // Awaited BEFORE the reads below, because the server's capture and the client's height are
        // both read there and both are only meaningful once his own client has taken him.
        //
        // The EDGE and not the per-tick commit, because the sentence below is "must take him": a
        // RIDING body is excluded from capture (`isExcludedFromCapture`), so it holds none while he
        // is seated and standing up has to produce an entry. Where a body may already be held at the
        // mark, this wait would have nothing to close on and `awaitCaptureHeldBy` is the form.
        clientEvents.awaitField(clientDismountMark, "deck_entered","ship", scenarioShipId,
                "the ex-pilot's OWN client must take him onto THIS ship's deck when he stands up"
                        + " mid-hover", DECK_LINK_BUDGET_TICKS);

        // WINDOW: its length is how long a hull whose hold is off needs to fall visibly; overshoot
        // only lets it fall further.
        advanceServerAndClient(40);
        ShipInfo info = shipInfo();
        double shipYPost = info.y;
        double velYPost = info.velY;
        PlayerShipData server = PlayerShipData.read(this::exec);
        DeckCapture capture = deckCaptureOfThisShip(scenarioShipId,
                "the dismounted pilot must be resolved on the deck of the ship he was flying");
        double serverY = server.playerY;
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] dismount shipY " + shipYPre + "->" + shipYPost + " velY "
                + velYAtWindowStart + "->" + velYPost + " serverY=" + serverY + " clientY=" + clientY);
        System.out.println("[deckcap] dismount capture=" + capture.raw());

        // The ship must keep hovering, not drop, when the pilot stands up. The computer's own
        // unmanned decision rides in the message: `held=false` says station-keeping was never on,
        // which is a different defect from a hold that engaged and under-thrust.
        // THE TEST'S OWN. The contract is that the hold keeps the hull where it was, so the honest
        // statement is zero; two blocks is the sag a station-keeping hull shows while it corrects,
        // and a hull that is actually falling is metres down inside this window.
        assertTrue("a hovering ship must not fall when the pilot dismounts: it dropped from " + shipYPre
                + " to " + shipYPost + " (vertical velocity " + velYAtWindowStart + " -> " + velYPost
                + "). The computer's unmanned decision was " + hold,
                shipYPre - shipYPost < HOVER_SAG_BLOCKS);
        // THE TEST'S OWN, and a SIGN with slack rather than a rate: what it refuses is a hull that
        // has begun to accelerate downward. Vanilla gravity alone reaches this within a few ticks,
        // so a hull still above it is one something is holding.
        assertTrue("a hovering ship must not start falling when the pilot dismounts (velY=" + velYPost
                + "). The computer's unmanned decision was " + hold, velYPost > STARTED_FALLING_VEL_Y);

        assertTrue("the dismounted pilot must be resolved on the deck, not handed to vanilla: " + capture.raw(),
                capture.verdict && capture.shipSupportObstacles > 0);
        // THE TEST'S OWN, and deliberately between the level bound and the tilted one: the pilot is
        // in the act of standing UP here, so the two sides are interpolating a body that is moving
        // as well as a hull that is tilted.
        assertTrue("the client must render the dismounted pilot on the deck where the server holds him: "
                + "serverY=" + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < CLIENT_SERVER_Y_AGREEMENT_DISMOUNT_BLOCKS);

        exec("stellurgytest player dismount"); // clean state for any following test
    }

    // ---- Bug: a ship reloaded from a save drops a walking client player through its deck ---------

    @Test
    @Ignore("HELD FOR THE BODY-MOVEMENT CONTRACT BATCH, by the maintainer's ruling of 2026-09-23:"
            + " every deck-hold red waits for the contract on moving an entity aboard a craft. Red"
            + " on a full client tier: the returning player's own client never took him onto the"
            + " RELOADED deck (no capture within 240 ticks), and its log shows the ship loaded and"
            + " then unloaded again after he returned. Green alone, and green on the next full tier"
            + " — intermittent, not gone. RE-ENABLE with that batch; the acceptance is this method"
            + " green on a full tier, twice.")
    public void aClientPlayerReturningToASavedShipStandsOnItsDeckInsteadOfFallingThrough() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // This scenario's SUBJECT is the unload, so it opts out of the server's default for itself.
        // A test server holds its ships permanently loaded; that is right for every scenario whose
        // craft merely has to exist, and fatal for the two here, whose whole path begins with a hull
        // going away. Without the opt-out the arrangement gate refuses — correctly — with "no
        // `ship_unloaded` naming this ship", and it refuses for the WHOLE budget.
        //
        // Scoped to this scenario rather than to the class: eight of the fourteen here pass BECAUSE
        // ships stay loaded, so a class-level opt-out trades these two reds for a different set. The
        // restore is the dangerous half — a `false` left in force turns the default off for
        // everything after it in this JVM — so it is put back in a `finally` at the end.
        ShipReadiness.letShipsUnload(this::exec,
                "this scenario's subject IS the unload-and-reload path of a saved ship");
        try {

        // The maintainer's "old ships" are ones from a PRIOR SESSION - assembled, the world saved and
        // unloaded, then loaded again. A freshly assembled ship (the grounded test above) is already
        // loaded and holds him fine; a ship loaded from disk starts in the registry, UNLOADED, until a
        // player brings it back. This drives that path in-harness: build, walk away until the ship's
        // chunks unload (VS saves it to the registry), then return to its deck.
        double[] ship = buildShip(site);
        assertTrue("the ship must be loaded before we unload it", shipIsLoaded());

        // Walk away far enough that nothing tickets the ship's chunks; the harness warmup holds no
        // ticket, so idle chunks unload. Belt and braces: drop any tickets a prior step left.
        Events events = serverEvents();
        long unloadMark = events.markInstrumented();
        exec("stellurgytest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        // Scoped to THIS ship: a whole-dimension "no ship is loaded" gate would wait on every
        // neighbour scenario's ship as well, and would answer about theirs rather than ours.
        // ASK for the unload rather than waiting to see whether one happens. Walking away is what a
        // player does; on a headless server with no other observer it is a wait on chance, and this
        // scenario used to SKIP whenever the chance did not come — the "different mechanism" its old
        // skip message asked for is this verb.
        // Waiting for the unload is not waiting on chance, which is what the SKIP this replaced
        // assumed: with nothing holding the craft, the substrate's own loading controller queues an
        // unload every tick for any ship with no player inside its unload distance, and the shared
        // base resets the one affordance that would override that. So the state below is REACHED,
        // not hoped for, and failing to reach it is news.
        //
        // The unload is a COMMIT — the substrate drops the physics object at one seam — so it is
        // awaited as one, keyed on this scenario's own ship. The 80x10 poll it replaces read a
        // by-id state and could only ever say "not yet".
        String unloaded = awaitThisShip(events, unloadMark, "ship_unloaded",
                "arrangement: the ship must actually unload before the RELOAD path can be exercised");
        assertTrue("the by-id state must agree with the unload record " + unloaded, !shipIsLoaded());
        // The registry must not have LOST it: an unloaded ship is a SAVED ship, and the registry
        // removal is a different seam with its own record. This is an absence, so the instrument
        // says out loud that it was listening.
        String removals = events.since(unloadMark, "ship_removed");
        Events.assertInstrumentRan(removals, "ship_registry_events",
                "the unloaded ship survived in the registry rather than being removed from it");
        assertTrue("the unloaded ship must survive in the registry (a saved ship is unloaded, never"
                        + " removed): " + removals,
                Events.countRecords(removals, "vsShip", scenarioShipId) == 0);

        // Return to the ship exactly as re-entering a docked ship from a saved world, and stand on
        // it. Two links, and each one names a different fault: the ship comes back
        // (`ship_loaded` — a new physics object for THIS ship), and his own client then takes him
        // onto its deck (`deck_entered`). 80 ticks used to cover both and could distinguish
        // neither.
        long reloadMark = events.markInstrumented();
        Events clientEvents = clientEvents();
        long landingMark = clientEvents.mark();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        String reloaded = awaitThisShip(events, reloadMark, "ship_loaded",
                "a saved ship must come back when the player returns to its deck");
        String landing = ShipIdentity.awaitCaptureHeldBy(clientEvents, landingMark, scenarioShipId,
                "the returning player's OWN client must resolve him on THIS RELOADED deck — the"
                        + " playtest's \"old ships drop me through\" is exactly this link missing",
                DECK_LINK_BUDGET_TICKS);
        System.out.println("[deckcap] reloaded ship=" + reloaded + " clientCapture=" + landing);

        PlayerShipData server = PlayerShipData.read(this::exec);
        DeckCapture capture = deckCaptureOfThisShip(scenarioShipId,
                "the returning player must be resolved on the ship this scenario built, which is the"
                        + " one that was saved and reloaded");
        double serverY = server.playerY;
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] reloaded server=" + server.raw());
        System.out.println("[deckcap] reloaded capture=" + capture.raw());
        System.out.println("[deckcap] reloaded serverY=" + serverY + " clientY=" + clientY
                + " loadedNow=" + shipIsLoaded());

        assertTrue("a reloaded ship must come back when the player returns to its deck: " + server.raw(),
                server.shipLoaded);
        // "A ship came back" is not the claim — THIS ship coming back is. A sibling scenario's hull
        // standing in the same airspace satisfies `shipLoaded` byte-identically.
        server.requireAboard( scenarioShipId,
                "the ship that came back under him must be the one this scenario saved");
        assertTrue("the player must be resolved on the reloaded deck, not fall through it: " + capture.raw(),
                capture.verdict && capture.shipSupportObstacles > 0);
        assertTrue("the client must render him ON the reloaded deck, not fallen through: serverY="
                + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < CLIENT_SERVER_Y_AGREEMENT_BLOCKS);

        } finally {
            // In a `finally` and not at the end of the happy path: a scenario that fails here would
            // otherwise leave the default OFF for every scenario that runs after it in this JVM,
            // which turns one red into a class of them and hides the original.
            ShipReadiness.holdShipsLoaded(this::exec,
                    "the unload was this scenario's subject; the rest of the class needs the default");
        }
    }

    // ---- Bug: flying into a ship's airspace hijacks a walking player's camera ------------------

    @Test
    public void flyingIntoAShipsAirspaceWithoutStandingOnItDoesNotHijackTheCamera() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);

        // Roll the ship so its world AABB spans a large air volume with a tilted deck - the airspace you
        // cross flying up to a ship. Attitude hold does it with no pilot aboard.
        double h = Math.toRadians(45.0) / 2.0;
        double upBeforeRoll = shipInfo().upY();
        assertTrue("attitude hold must accept the roll",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h))).bool("commanded"));
        // WINDOW: a deck that has not tilted is not "the airspace you cross flying up to a ship" this
        // leg describes.
        // SERVER-ONLY: the slew is a server command to the flight computer; the read is the server's ship report.
        GameTicks.advanceWorld(serverClient(), 0, SLEW_WINDOW_TICKS);
        ShipInfo info = shipInfo();
        scenario().requireArranged("the ship must be rolled before the fly-in: upY " + upBeforeRoll
                        + " -> " + info.upY() + " over " + SLEW_WINDOW_TICKS + " server ticks",
                info.upY() < Math.cos(Math.toRadians(30.0)));
        double sx = info.x, sy = info.y, sz = info.z;

        // NEGATIVE (the bug): a player who has NEVER stood on this deck flies into its airspace, off the
        // deck. He comes from outside the ship's region, so nothing has captured him (his ship-frame
        // movement state is empty). His view must stay his own - not snap to the tilted deck's horizon.
        long awayMark = clientEvents().mark();
        exec("tp @a " + (sx + FLY_IN_AWAY_BLOCKS) + " " + sy + " " + (sz + FLY_IN_AWAY_BLOCKS) + " 0 0");
        // He must be AWAY on the client, because the claim below is about a body the deck has never
        // touched: a client still standing on the deck is still being captured there.
        awaitClientPlacedNear(awayMark, sx + FLY_IN_AWAY_BLOCKS, sz + FLY_IN_AWAY_BLOCKS,
                "the negative leg needs a body that has never stood on this deck");
        // THE FLY-IN POINT IS PLACED RELATIVE TO THE DECK, not to the pose. The pose is the centre of
        // mass, and where that sits under the deck is a property of the hull, not of this scenario:
        // the craft this was written against had it 0.91 below the deck top, so 3 blocks of world
        // height at the 45-degree roll put him, in the deck's own frame, 2.12 across from the pose
        // and 1.21 above the deck. The rebuilt craft carries its actuators under the deck and has
        // it 1.6 below; the same 3 blocks put him 0.4 over the deck, where the deck took him and
        // engaged the camera (2026-09-30), and a purely vertical correction pushed him past the
        // deck's edge instead. So the point is that deck-frame offset — 2.12 across, 1.21 over the
        // deck top — carried into the world through the hull's own attitude. The deck top is the
        // flight computer's floor (it stands on the deck); the centre of mass and the attitude are
        // the ship report's own.
        Reply pose = Reply.of(info.raw());
        double deckTopAboveCom = Reply.of(exec("stellurgytest vs flight-model-by-id 0 " + scenarioShipId))
                .integer("afcY") - pose.number("comY");
        double[] flyIn = rotateByShip(pose, FLY_IN_ACROSS, deckTopAboveCom + FLY_IN_DECK_CLEARANCE, 0.0);
        final double fx = sx + flyIn[0], fz = sz + flyIn[2];
        Events clientEvents = clientEvents();
        // The body falls from the fly-in point onto the tilted deck within a few ticks, so "he is in
        // the airspace, off the deck" is true for a STRETCH that ends at his landing, and any read
        // taken after a wait returns describes wherever the speed of the box left him by then. Both
        // the premise and the verdict are therefore read off records stamped inside that stretch:
        // the client's own gate decision on the first tick at the new point (the per-tick window,
        // armed BEFORE the teleport so it cannot miss that tick), and the ORDER of the camera's edges
        // against the tick the deck took him.
        //
        // The window's budget is one link's: it is read up to the landing link below, which may take
        // at most DECK_LINK_BUDGET_TICKS, and a record a tick fits that under the log's 256-deep ring.
        int body = openDeckGateWindow(DECK_LINK_BUDGET_TICKS);
        long flyInMark = clientEvents.mark();
        exec("tp @a " + fx + " " + (sy + flyIn[1]) + " " + fz + " 0 0");
        // An ARRANGEMENT link: the placement is vanilla's teleport and the premise of the reads below.
        // The wait returns on the first placement near the point, so the newest record is that one.
        String placements = ArrangementFailure.arranged(() -> ClientEvents.awaitPlacedNear(clientEvents,
                flyInMark, fx, fz, "the fly-in teleport must reach the client before anything about"
                        + " him at that point can be read", DECK_LINK_BUDGET_TICKS));
        final long placed = Reply.of(Events.lastRecord(placements)).longInteger("seq");

        // The deck takes him: the first gate decision after the placement in which his own client
        // holds him ON this ship's deck (tracked, not hull-stand). It is the END of the stretch the
        // negative is about, and an ARRANGEMENT link — a body the deck never takes is not this
        // scenario's subject, and the positive control below is where a deck that takes nobody fails.
        String gate = ArrangementFailure.arranged(() -> clientEvents.awaitMatching(placed + 1,
                "deck_gate_explained", seen -> firstOnThisDeck(seen, body) != null,
                "a gate decision holding him on " + scenarioShipId + "'s deck",
                "he must fall from the fly-in point onto the tilted deck, which ends the stretch"
                        + " in which he is in its airspace and not on it", DECK_LINK_BUDGET_TICKS));
        String firstTick = firstGateDecision(gate, body);
        String takenOnDeck = firstOnThisDeck(gate, body);
        // The INSTANT the deck took him is production's own mode commit, not the window's record of
        // it: the window writes once a tick, so its first "held" record can come after a frame the
        // capture had already been drawn in. `deck_mode_committed` is written by `logCapture`, which
        // every first contact and every hull-to-deck hand-over calls right after installing the
        // state. Blind spot, named by the recorder: a re-capture on the hull-stand travel path records
        // "aboard" for a body that stays in hull-stand, which can only make this instant EARLIER —
        // the lenient direction for the order asserted below.
        String modes = clientEvents.since(placed + 1, "deck_mode_committed");
        String aboardCommit = null;
        for (String record : Events.recordsWhere(modes, "ship", scenarioShipId)) {
            // the producer always writes `mode` on this record
            if ("aboard".equals(Reply.of(record).text("mode"))) {
                aboardCommit = record;
                break;
            }
        }
        scenario().requireArranged("the gate held him on this ship's deck, so production must have"
                + " committed him aboard it: modes=" + modes + " gate=" + takenOnDeck, aboardCommit != null);
        long takenSeq = Reply.of(aboardCommit).longInteger("seq");
        System.out.println("[deckcap] fly-in first gate tick=" + firstTick + " held=" + takenOnDeck
                + " committed=" + aboardCommit);

        // The premise, from the gate's FIRST decision at the new point: his client holds this ship,
        // he is inside its box, off every deck block, and not held aboard. Hull-stand is allowed — it
        // keeps world gravity, world movement and the WORLD camera, which is what this leg asserts.
        // Containment is asked of THIS ship by name: a client that does not hold the ship answers
        // "not contained" about every point (measured 2026-10-05 from 200 blocks away: shipCount 0 on
        // the first tick back, and the deck had him five ticks later), which is a reading about
        // loading and not about where the point is.
        Reply first = Reply.of(firstTick);
        scenario().requireArranged("the fly-in point must be inside THIS ship's box as his client holds"
                        + " it, off any deck block, and he must not be ABOARD on a deck there, on the"
                        + " first tick his client decided at it: " + firstTick,
                java.util.Arrays.asList(first.textArray("containingShipIds")).contains(scenarioShipId)
                        && !first.bool("supportedByShip")
                        && !(first.bool("alreadyTracked") && !first.bool("hullStand"))
                        && takenSeq > first.longInteger("seq"));

        // The negative, as ORDER on one log: the renderer records the camera's ENGAGE edge, and an
        // engage that precedes the tick the deck took him engaged on a body in the airspace — the
        // hijack. Over the same stretch, his view must also not have been engaged when he arrived:
        // the newest edge before the placement, since he was sent away, is not an engage.
        String camEdges = clientEvents.since(awayMark, "deck_camera_changed");
        Events.assertInstrumentRan(camEdges, "deck_camera_events",
                "the deck camera stayed out of a fly-in player's view");
        String engagedOnArrival = null;
        String hijack = null;
        for (String edge : Events.records(camEdges)) {
            Reply e = Reply.of(edge);
            long seq = e.longInteger("seq");
            if (seq < placed) {
                engagedOnArrival = e.bool("active") ? edge : null;
                continue;
            }
            // the producer always writes `active` on both of its edges (engage and release)
            if (seq < takenSeq && e.bool("active") && hijack == null) {
                hijack = edge;
            }
        }
        assertTrue("a player in a ship's airspace, not standing on its deck, must keep his own view;"
                + " the deck camera engaged before the deck took him (engage=" + hijack
                + ", camera on arrival=" + engagedOnArrival + ", committed aboard at seq " + takenSeq
                + ", the episode edges=" + clientEvents.since(placed + 1, "deck_entered")
                + ", modes=" + modes + ", camera edges=" + camEdges + ")",
                hijack == null && engagedOnArrival == null);
        closeDeckGateWindow();

        // POSITIVE control: level the ship and land him ON the deck. Now the deck camera SHOULD engage -
        // so the negative above is a real on-deck/off-deck discrimination, not the camera never firing.
        double upBeforeLevel = shipInfo().upY();
        assertTrue("attitude hold must accept levelling",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " 1.0 0.0 0.0 0.0")
                        ).bool("commanded"));
        // WINDOW: the levelling slew; an attitude converges and nothing declares it reached.
        // SERVER-ONLY: the slew is a server command to the flight computer; the read is the server's ship report.
        GameTicks.advanceWorld(serverClient(), 0, SLEW_WINDOW_TICKS);
        ShipInfo lvl = shipInfo();
        scenario().requireArranged("the ship must be level again before the positive control lands"
                        + " him on its deck: upY " + upBeforeLevel + " -> " + lvl.upY() + " over "
                        + SLEW_WINDOW_TICKS + " server ticks",
                lvl.upY() > Math.cos(Math.toRadians(15.0)));
        // The control is the camera's STATE, and it may NOT be its engage edge — a fact about the
        // recorder, not a preference. `deck_camera_changed` is written at
        // {@code ShipFrameCamera.recordCamera} only when `active` differs from what the last frame
        // left in the field, production's two release branches set `shipCamActive = false` directly
        // without passing that seam, and every production caller of `recordCamera` passes `true`. So
        // an engage edge exists only where the flag had actually DROPPED — and the negative leg
        // above has already left the bot standing on this deck with the camera engaged, so
        // levelling the ship and putting him down again produces no edge at all. Awaiting one reds a
        // client whose camera is engaged, which is the opposite of what this control asserts.
        //
        // The discrimination the negative leg needs survives intact and is still made of two
        // measurements of the same flag: OFF while he is in the airspace off the deck, ON while he
        // stands on it. The edges since the mark stay in the message — one recorded HERE would say
        // the camera had been released and came back, which is a different and interesting story
        // from one that never dropped.
        long onDeckMark = clientEvents.mark();
        exec("tp @a " + lvl.x + " " + (lvl.y + 5) + " " + lvl.z + " 0 0");
        clientEvents.awaitMatching(onDeckMark, "client_pos_look_applied",
                reply -> ClientEvents.appliedNear(reply, lvl.x, lvl.z),
                "placing the client over the level deck",
                "the put-down over the level deck must reach the client before its landing can be"
                        + " read", DECK_LINK_BUDGET_TICKS);
        // EXPERIMENT: the reading is DEFINED forty client ticks after the put-down reached the client.
        // He is dropped five blocks, which vanilla gravity covers in about thirteen ticks, and it is
        // the client's own ticks that move him and resolve his capture — so forty of them are a
        // landing and then some, on any box. A camera that has not engaged by then fails below with
        // the capture verdict beside it.
        bot().waitWorldTicks(DECK_LANDING_TICKS);
        String engaged = clientEvents.since(onDeckMark, "deck_camera_changed");
        boolean onDeckCam = Boolean.parseBoolean(deckCameraText("active"));
        DeckCapture camCapture = DeckCapture.read(this::exec);
        System.out.println("[deckcap] cam on-deck active=" + onDeckCam
                + " edgesSincePutDown=" + engaged + " cap=" + camCapture.raw());
        // The camera is DOWNSTREAM of the capture, so a bare "no deck camera" blames the renderer
        // for something that usually happened one link earlier. The capture verdict is already read
        // for the stdout line above; putting it in the message is free and it splits the two: a
        // capture that says verdict=false means the body was never resolved on the deck at all and
        // the camera is behaving correctly, while a true capture with no camera is a real render gap.
        assertTrue("a player actually standing on the deck must get the deck camera. If the capture"
                        + " below says the body is NOT on the deck then this is not a camera fault at"
                        + " all - the body never got there. capture="
                        + camCapture.raw().replace('\n', ' ')
                        + " cameraEdgesSincePutDown=" + engaged
                        + " playerY=" + bot().reportState().get("playerY").getAsDouble(),
                onDeckCam);
    }

    // ---- Bug: a hovering ship falls (and tumbles inverted) after a world reload -----------------

    @Test
    public void aHoveringShipKeepsHoveringAcrossAReloadInsteadOfFalling() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Same opt-out and the same reason as the sibling reload scenario above: this one's subject
        // is a hull going away and coming back, which the server's permanently-loaded default makes
        // impossible. Restored in a `finally` so a red here cannot disarm the default for whatever
        // runs next in this JVM.
        ShipReadiness.letShipsUnload(this::exec,
                "this scenario's subject IS a hovering craft surviving an unload and reload");
        try {

        buildAndBoardShip(site);

        // Fly it into a hover, then stand up: it is now an unmanned, station-keeping, hovering ship -
        // exactly the state a saved hovering ship is in on disk.
        double startY = shipInfo().y;
        // The delivery link, the window and why the climb is measured rather than awaited all live
        // in the helper; startY is kept because this scenario compares against it after the reload.
        hoverOnPilotThrust(scenarioShipId, CLEAR_HOVER_GAIN_BLOCKS);
        // The hold engaging is the computer's own decision, so the wait is on that record rather
        // than on 40 ticks: the state this whole scenario saves and restores is the one it names.
        Events events = serverEvents();
        long standUpMark = events.markInstrumented();
        exec("stellurgytest player dismount");
        String holdBefore = events.await(standUpMark, "unmanned_hold_decided",
                "the flight computer must take an unmanned decision when the pilot stands up — the"
                        + " hover this scenario then saves and reloads is that decision's result",
                DECK_LINK_BUDGET_TICKS);
        double hoverY = shipInfo().y;
        assertTrue("the unmanned ship must still be hovering off the ground: " + hoverY
                + " (the computer's unmanned decision was " + holdBefore + ")",
                hoverY - startY > 1.0);

        // Simulate a world reload: unload the ship (its flight-computer tile is written to NBT, its LIVE
        // attitudeReference lost) and load it again. The persisted station-keeping flag must bring the
        // hold back so the ship does NOT fall - the live playtest's "hovering ship survived a restart,
        // then fell and flipped".
        long unloadMark = events.markInstrumented();
        exec("stellurgytest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        // Scoped to THIS ship, like the saved-ship scenario above: on a shared world a whole-dimension
        // count answers about whichever neighbour's ship is loaded — and the substrate's own
        // unload/load commits are what is awaited, keyed on this ship's physics uuid.
        // Waiting for the unload is not waiting on chance, which is what the SKIP this replaced
        // assumed: with nothing holding the craft, the substrate's own loading controller queues an
        // unload every tick for any ship with no player inside its unload distance, and the shared
        // base resets the one affordance that would override that. So the state below is REACHED,
        // not hoped for, and failing to reach it is news.
        String unloaded = awaitThisShip(events, unloadMark, "ship_unloaded",
                "arrangement: the ship must actually unload before the RELOAD path can be exercised");
        assertTrue("the by-id state must agree with the unload record " + unloaded, !shipIsLoaded());

        long reloadMark = events.markInstrumented();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        // The reload is a COMMIT — a fresh physics object for this ship — and the poll it replaces
        // discarded its own `satisfied` flag, so a reload that never happened surfaced further down
        // as a regex failure reading like the contract breaking.
        String reloaded = awaitThisShip(events, reloadMark, "ship_loaded",
                "arrangement: the saved ship must come back before its hold can be judged");
        // WINDOW: its length is how long a hull whose hold did not come back needs to fall visibly
        // after the reload; overshoot only lets it fall further.
        advanceServerAndClient(80);

        // What the flight computer restored from NBT, and what it then decided unmanned. Read, not
        // awaited: the contract below is the ALTITUDE, and these two records are what let its failure
        // say "the persisted flag did not survive the save" apart from "it did and the hold
        // under-thrust" — the question a 3-block drop on its own can never answer.
        String restored = events.since(reloadMark, "station_keeping_restored");
        String heldAfter = events.since(reloadMark, "unmanned_hold_decided");
        double afterY = shipInfo().y;
        System.out.println("[deckcap] reload-hover startY=" + startY + " hoverY=" + hoverY
                + " afterReloadY=" + afterY + " loaded=" + reloaded + " restored=" + restored
                + " unmanned=" + heldAfter);
        // THE TEST'S OWN, and wider than the live-dismount bound above for a stated reason: a
        // reload re-creates the hull and its hold from NBT, so the hull sags while the restored
        // computer takes its first corrective ticks. A hull that fell out of the sky is metres down.
        assertTrue("a hovering ship must KEEP hovering across a reload, not fall out of the sky: it was "
                + "at " + hoverY + " and after reload is at " + afterY
                + ". What the computer restored from NBT: " + restored
                + " | what it then decided unmanned: " + heldAfter, hoverY - afterY < RELOAD_SAG_BLOCKS);

        } finally {
            ShipReadiness.holdShipsLoaded(this::exec,
                    "the unload was this scenario's subject; the rest of the class needs the default");
        }
    }

    // ---- Bug: entering / leaving the seat on a truly INVERTED ship (the maintainer's live scenario) --

    private static final String KEY_BINDINGS = "dev.stannismod.stellurgy.client.KeyBindings";

    private double shipUpYFromInfo(ShipInfo info) {
        return info.upY(); // world-Y of the ship's local +Y
    }

    private double[] readShipInfoXYZ(ShipInfo info) {
        return new double[]{info.x, info.y, info.z};
    }

    @Test
    public void standingUpFromASeatOnASteeplyTiltedShipKeepsThePilotOnTheDeck() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        buildAndBoardShip(site);

        // Put the craft at a steep but STANDABLE tilt, then stand up FROM the seat while it is
        // tilted — the maintainer's "after leaving, I fall through" is on a non-upright ship, which
        // the upright dismount test never exercised. 60 degrees of roll about X is
        // q = (cos30, sin30, 0, 0), i.e. up.y = 0.5, in the middle of the envelope this scenario
        // needs rather than at either edge of it.
        //
        // Commanded, not mouse-rolled. The roll this replaced was an open loop against a craft that
        // keeps turning after the cursor centres — its own comment says so — so it landed anywhere
        // between no tilt and a vertical wall and SKIPPED whenever it missed. The mouse is not the
        // subject here; standing up on a tilted deck is, and it is still the real client that stands.
        for (int i = 0; i < 40 && shipUpYFromInfo(shipInfo()) > 0.55; i++) {
            exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " 0.8660254 0.5 0 0");
            advanceServerAndClient(4);
        }
        // The pilot is at the controls, so the tilt is his to keep once the probe lets go — which his
        // computer does only once he has put his hands on the stick (see takeTheStickWhileHeld).
        takeTheStickWhileHeld("at the steep tilt, before the probe hold is cut");
        exec("stellurgytest vs force-clear-by-id 0 " + scenarioShipId);
        double releasedUpY = shipUpYFromInfo(shipInfo());
        // WINDOW: releasedUpY (the instant the command is cut) to `tilted`, and the assertion below
        // holds BOTH ends in the envelope and prints both — the craft has to KEEP the tilt it was
        // brought to, not merely pass through it. Overshoot only gives it longer to drift out.
        advanceServerAndClient(30);
        double tilted = shipUpYFromInfo(shipInfo());
        // An ASSERT, not an Assume: the tilt is commanded to a value inside the envelope, so failing
        // to be there is news about how a craft holds a commanded attitude — not a dice roll to be
        // stepped over. The client-side capture packet snaps the fresh dismount onto the deck and
        // holds it there, like a crew member who rode in and holds at 90 degrees.
        // BOTH ENDS ARE THE TEST'S OWN, and both are premises: under 0.25 of deck-normal Y the
        // craft is too near vertical for a body to stand on it at all, and over 0.80 it is near
        // enough level that the tilt this leg is about barely exists. Production draws neither
        // line — it holds whatever attitude it was pointed at.
        assertTrue("arrangement: the craft must sit in the steep-but-standable envelope before the"
                + " subject is exercised, and stay there once the command is cut (upY at release="
                + releasedUpY + ", after the window=" + tilted + ")",
                releasedUpY >= STANDABLE_TILT_MIN_UP_Y && releasedUpY < STANDABLE_TILT_MAX_UP_Y
                        && tilted >= STANDABLE_TILT_MIN_UP_Y && tilted < STANDABLE_TILT_MAX_UP_Y);

        double[] seat = readShipInfoXYZ(shipInfo());
        // The seat dismount seeds the ex-pilot's capture on the CLIENT, and that is the link this
        // scenario's report is about ("after leaving, I fall through"). Marked before the stimulus,
        // awaited after it — the settle window below then measures a body that is provably captured
        // rather than one that may never have been.
        Events clientEvents = clientEvents();
        long dismountMark = clientEvents.mark();
        exec("stellurgytest player dismount");
        String seeded = clientEvents.awaitField(dismountMark, "deck_entered","ship", scenarioShipId,
                "standing up on THIS tilted deck must leave the ex-pilot captured ON THE CLIENT — the"
                        + " seed is what puts him there, and without it the heights below are"
                        + " measuring a body vanilla owns", DECK_LINK_BUDGET_TICKS);
        StringBuilder traj = new StringBuilder();
        double settledMin = Double.MAX_VALUE;
        // WINDOW: the MINIMUM height over the settled tail — a body that dipped and recovered is
        // exactly the failure being looked for, so a last-sample read would miss it, and no record
        // carries a minimum over a stretch of ticks. What this cannot see: a dip inside one 2-tick
        // sample.
        for (int i = 0; i < 22; i++) {
            advanceServerAndClient(2);
            double y = bot().reportState().get("playerY").getAsDouble();
            traj.append(String.format("%.1f ", y));
            if (i >= 14) { // last ~8 samples, once the dismount motion has settled
                settledMin = Math.min(settledMin, y);
            }
        }
        // Every release in the window, each with the gate that performed it — production's own words
        // for what the cumulative counters this replaced could only report as a number.
        String releases = clientEvents.since(dismountMark, "deck_released");
        DeckCapture capture = DeckCapture.read(this::exec);
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = PlayerShipData.read(this::exec).playerY;
        System.out.println("[deckcap] tilted-dismount upY=" + tilted + " shipPosY=" + seat[1]
                + " settledMinY=" + settledMin + " Ytraj=" + traj);
        System.out.println("[deckcap] tilted-dismount seed=" + seeded + " releases=" + releases);
        System.out.println("[deckcap] tilted-dismount capture=" + capture.raw() + " clientY=" + clientY
                + " serverY=" + serverY);

        // THE BOUND IS THE SHIP'S OWN ALTITUDE, not a number, and this is a literal that had
        // silently stopped meaning what it said. It read `settledMin > 66.0` under a comment about
        // "the by=64 ground (its solid top at y=65)" — true while every fixture stood at y=64, and
        // false the moment the site moved into the open-air band: at a hull near y≈150 a bound of
        // 66 is satisfied by a body four score blocks BELOW the deck, which is the exact outcome
        // the assertion exists to catch. The contract is "he stayed up ON THE SHIP", so the
        // reference is the ship, and the slack below it is a body's own height plus the deck's
        // thickness.
        //
        // A single-instant "aboard" read is unreliable (it can catch him mid-fall while still
        // nominally inside the AABB), so the SETTLED trajectory is what is asserted.
        double stayedUpFloor = seat[1] - PILOT_MAY_SETTLE_BELOW_HULL_BLOCKS;
        assertTrue("standing up on a tilted ship must keep the pilot UP ON IT, not drop him away:"
                + " settledMinY=" + settledMin + " shipPosY=" + seat[1] + " floor=" + stayedUpFloor
                + " Ytraj=" + traj
                + ". The client's releases in this window (each with the gate that performed it): "
                + releases, settledMin > stayedUpFloor);
        assertTrue("the client and server must agree on the ex-pilot's height on the tilted ship: serverY="
                + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < CLIENT_SERVER_Y_AGREEMENT_TILTED_BLOCKS);
    }

    @Test
    public void aFreshlyDismountedPilotStaysCapturedWhenTheShipThenRollsNinetyDegrees() throws Exception {
        double h = Math.toRadians(90.0) / 2.0; // deck on its side (upY ~ 0)
        assertDismountThenRollHolds(site(),
                Math.cos(h), Math.sin(h), -0.35, 0.35, "90deg");
    }

    @Test
    public void aFreshlyDismountedPilotStaysCapturedWhenTheShipThenRollsPastVertical() throws Exception {
        // Command 160deg; the attitude hold settles well PAST vertical on this fixture (measured deck-up
        // ~ -0.93, i.e. ~160deg - nearly inverted, the ex-pilot hanging below the deck). An EXACT 180deg is
        // the axis-angle singularity the controller cannot converge to, and a free spin to it is VS-damped
        // in a headless run - so the last few degrees to full inversion are a manual-playtest item.
        assertDismountThenRollHolds(site(),
                0.17365, 0.98481, -1.01, -0.4, "past-vertical");
    }

    /**
     * The fresh-dismount capture must survive the ship SUBSEQUENTLY rolling to a steep/inverted attitude:
     * stand up from the seat on a LEVEL deck (the client-side {@code PacketDeckCapture} seeds the ex-pilot
     * on the deck), THEN command the ship to a fixed roll about its nose and hold it, and assert the
     * ex-pilot rode the deck over - still resolved on it, held at deck height, client and server agreeing -
     * instead of being dropped through the hull or left behind in the world.
     *
     * <p>Reliable because the roll is a commanded quaternion on an UNMANNED ship ({@code stellurgytest vs point}):
     * a seated pilot's own input overwrites the attitude target every tick, and a free spin is VS-damped,
     * so commanding the attitude after the dismount is the only way to put a walking ex-pilot on a
     * steep/inverted deck in the headless harness. Thresholds are deck-relative (derived from the measured
     * ship Y), never a magic absolute.</p>
     */
    private void assertDismountThenRollHolds(FixtureSite site, double qw, double qz,
            double upYLo, double upYHi, String label) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged. It arrives as
        // a SITE rather than as three ints because the two callers used to pass a literal y=64 in
        // the middle of a positional argument list — a staging decision that read as a magic number
        // and that the fixture-site counter cannot see, since the line mentions no fixture at all.
        final int bx = site.x, by = site.y, bz = site.z;

        buildAndBoardShip(site);

        // Stand up on the LEVEL deck: the dismount capture packet seeds the ex-pilot on the deck.
        //
        // Awaited on the CLIENT's own record of THIS dismount. What it replaces could not fail: the
        // gate read `ShipFrameTravel.resolvedTicks`, a counter that is cumulative for the life of
        // the side, and on a shared client an earlier scenario has already resolved somebody on a
        // deck — so "> 0" was true before this dismount ever happened. A mark taken immediately
        // before the stimulus is what turns the same question into one that can come back "no".
        Events clientEvents = clientEvents();
        long dismountMark = clientEvents.mark();
        exec("stellurgytest player dismount");
        String seeded = clientEvents.awaitField(dismountMark, "deck_entered","ship", scenarioShipId,
                "the fresh dismount must engage the ship-frame capture on THIS ship's level deck",
                DECK_LINK_BUDGET_TICKS);

        // Roll the now-UNMANNED ship (a mounted pilot would overwrite the target) to the commanded attitude.
        double upBeforeTilt = shipUpYFromInfo(shipInfo());
        assertTrue("attitude hold must accept the " + label + " roll command",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " " + qw + " 0.0 0.0 " + qz)
                        ).bool("commanded"));
        // WINDOW: the slew to the roll; an attitude converges and nothing declares it reached.
        // SERVER-ONLY: the slew is a server command to the flight computer; the read is the server's ship report.
        GameTicks.advanceWorld(serverClient(), 0, LONG_SLEW_WINDOW_TICKS);
        double tilted = shipUpYFromInfo(shipInfo());
        // Reliable command -> a HARD assert that the regime was reached (fail loudly, not a silent skip).
        assertTrue("the ship must reach the " + label + " regime for the test to mean anything (upY "
                + upBeforeTilt + " -> " + tilted + " over " + LONG_SLEW_WINDOW_TICKS
                + " server ticks, expected [" + upYLo + "," + upYHi + "])",
                tilted >= upYLo && tilted <= upYHi);

        double shipPosY = readShipInfoXYZ(shipInfo())[1];
        long rollMark = clientEvents.mark();
        StringBuilder traj = new StringBuilder();
        double settledMin = Double.MAX_VALUE, settledMax = -Double.MAX_VALUE;
        // WINDOW: under a roll the claim is that the body stays within a band, a property of the
        // stretch and not of any instant. What this cannot see: an excursion inside one 2-tick
        // sample.
        for (int i = 0; i < 22; i++) {
            advanceServerAndClient(2);
            double y = bot().reportState().get("playerY").getAsDouble();
            traj.append(String.format("%.1f ", y));
            if (i >= 14) { // last ~8 samples, once the roll has settled
                settledMin = Math.min(settledMin, y);
                settledMax = Math.max(settledMax, y);
            }
        }
        double osc = settledMax - settledMin;
        // What the client's resolver did across the settle, in its own records: how many ticks it
        // committed a capture, and every release with the gate that performed it. This replaces a
        // read of the cumulative `resolvedTicks` static, which counted every body this side ever
        // resolved and so could not be scoped to this window at all.
        String rollCaptures = clientEvents.since(rollMark, "deck_carry");
        String rollReleases = clientEvents.since(rollMark, "deck_released");
        // Read once and proved to be about this scenario's craft: the whole claim below is "the roll
        // did not hand him away", and a capture re-anchored onto a neighbour's hull mid-roll is
        // exactly that failure while reading `verdict:true`.
        DeckCapture capture = deckCaptureOfThisShip(scenarioShipId,
                "the ex-pilot must stay resolved on the ship he was rolled with");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = PlayerShipData.read(this::exec).playerY;
        System.out.println("[deckcap] dismount-then-roll " + label + " upY=" + tilted + " shipPosY="
                + shipPosY + " settledMin=" + settledMin + " osc=" + osc + " seed=" + seeded
                + " capturesOnRoll=" + Events.countRecordsWithField(rollCaptures, "ship")
                + " releasesOnRoll=" + rollReleases
                + " capture=" + capture.raw() + " clientY=" + clientY + " serverY=" + serverY + " Ytraj=" + traj);

        // Still captured while the deck is steep/inverted - the ship frame keeps resolving him, not vanilla.
        assertTrue("the ex-pilot must stay resolved on the " + label + " deck, not be handed to vanilla: "
                + capture.raw() + ". The client's releases across the roll: " + rollReleases,
                capture.verdict);
        // Deck-relative hold: he must not slide down toward the ~" + (by + 1) + " ground - his settled
        // height stays within a body of the measured ship, not 2.5+ blocks below it.
        assertTrue("the ex-pilot must ride the " + label + " deck over, not drop to the ground: settledMin="
                + settledMin + " shipPosY=" + shipPosY + " Ytraj=" + traj
                + ". The client's releases across the roll: " + rollReleases,
                settledMin > shipPosY - 2.5);
        // Held, not sliding: a captured body is stationary on the stationary rolled ship (small tail swing);
        // a body sliding off shows a large monotonic settle.
        // THE TEST'S OWN: the quantity is the OSCILLATION of the settled trajectory, so the
        // contract is that it is small and bounded rather than monotonic. A block and a half is
        // under a body's height; a body sliding off a rolled deck shows many blocks of one-way
        // travel.
        assertTrue("the captured ex-pilot must be HELD on the " + label + " deck, not sliding (settled Y "
                + "oscillation=" + osc + "): " + traj, osc < HELD_BODY_Y_MOVEMENT_BLOCKS);
        assertTrue("the client and server must agree on the ex-pilot's height (serverY=" + serverY
                + " clientY=" + clientY + ")", Math.abs(clientY - serverY) < CLIENT_SERVER_Y_AGREEMENT_TILTED_BLOCKS);
    }

    @Test
    public void enteringAndLeavingTheSeatOnAnInvertedShipWorks() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);

        // Put the FRESH (never-piloted) craft into a held inversion by writing the attitude: 180
        // degrees about X is q = (0, 1, 0, 0), so the deck's own +Y points at world −Y — which is
        // also the maintainer's ship, stuck inverted after a tumble. It does NOT stay there on its
        // own once the hold is cut: an unmanned computer holds the reference it was assembled with,
        // and a hull whose actuators can turn it (every fixture since 2026-09-30) slews back to
        // level. So the hold stands until the pilot is seated and has put his hands on the stick,
        // which makes the inverted attitude his computer's own (see takeTheStickWhileHeld).
        //
        // Three arrangements were measured side by side (2026-08-22, server tier, same craft type),
        // and only this one works. Re-applying a raw 5 rad/s spin — what this scenario did until
        // today — never even reached inversion: best up.y = +0.490 over 60 attempts, because the
        // unmanned auto-level torques the deck back to horizontal as fast as the write arrives, and
        // that is why this scenario had been SKIPPING. Commanding the roll through the flight
        // computer does reach it (−0.902) and then loses it: released, the craft rights itself to
        // +0.353 within 60 ticks. Neither can arrange what this test is about.
        // `point-by-id` COMMANDS a target attitude held by torque — it is the attitude-hold
        // interface, not a pose write — so the craft has to slew there, and the command has to stand
        // while it does. Measured cost of getting this wrong: commanded once and read 20 ticks later,
        // the craft was at up.y = +0.038 with omega = 2.0 rad/s, i.e. still on its way round. Half a
        // turn at that rate needs about 31 ticks.
        //
        // NOT a chain: `point-by-id` engages an attitude HOLD, and the computer applies torque toward
        // its target quaternion every tick without ever deciding that it has arrived — there is no
        // "reached" for a record to carry. So the slew is a WINDOW, and the hold is a command that
        // STANDS: set once, it is in force until the force-clear below.
        double upBeforeSlew = shipUpYFromInfo(shipInfo());
        String held = exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " 0 1 0 0");
        scenario().requireArranged("the attitude hold must be accepted by THIS craft's computer: "
                + held, Reply.of(held).bool("commanded"));
        // WINDOW: a slew that did not get there fails in the gate below, loudly.
        // SERVER-ONLY: the slew is a server command to the flight computer; the read is the server's ship report.
        GameTicks.advanceWorld(serverClient(), 0, INVERT_SLEW_WINDOW_TICKS);
        double reachedUpY = shipUpYFromInfo(shipInfo());

        // ENTER the seat on the inverted ship, with the hold still standing — located inside THIS
        // ship, not "the first seat in the world" (see mountPilotSeatOfShipAt). The mark is taken
        // here rather than inside the helper because the helper's other callers take their own.
        long seatClientMark = clientEvents().mark();
        mountPilotSeatOfShipAt(bx, by, bz);
        awaitClientMount(seatClientMark, "the turn commands below are sent BY THE CLIENT from the"
                + " seat it believes he is in, so the server's own mount receipt is not enough",
                DECK_LINK_BUDGET_TICKS, "");
        takeTheStickWhileHeld("seated on the inverted ship, before the probe hold is cut");

        // Then let go, and let it sit: REACHING an attitude and KEEPING it are different questions,
        // and everything below needs the second one.
        exec("stellurgytest vs force-clear-by-id 0 " + scenarioShipId);
        // WINDOW: overshoot only gives the craft longer to right itself.
        // SERVER-ONLY: the hold is the server computer's, the pilot's idle input already linked in takeTheStickWhileHeld; the read is the server's ship report.
        GameTicks.advanceWorld(serverClient(), 0, INVERTED_HOLD_WINDOW_TICKS);
        ShipInfo info0 = shipInfo();
        double invertedUpY = shipUpYFromInfo(info0);
        System.out.println("[deckcap] force-invert reachedUpY=" + reachedUpY + " upY=" + invertedUpY
                + " info=" + info0);
        // An ASSERT, not an Assume: the attitude write is deterministic, so a craft that is not
        // inverted here is a real change in how a craft holds an adopted attitude — which is a thing
        // this suite should go red for, not skip over. The skip it replaces hid this scenario for as
        // long as the spin arrangement was failing, and a scenario nobody sees fail is not a test.
        // THE TEST'S OWN arrangement fact, and a strict one because this leg's whole subject is
        // the inverted case: -0.85 of deck-normal Y is about 150 degrees over, well past the
        // point where world-up and ship-up could be confused for one another.
        assertTrue("arrangement: the craft must be INVERTED before the subject is exercised, and"
                + " stay inverted once the hold is cut (upY before the slew=" + upBeforeSlew
                + ", when cut=" + reachedUpY + ", after the window=" + invertedUpY + "): " + info0,
                reachedUpY < INVERTED_UP_Y && invertedUpY < INVERTED_UP_Y);

        // SYMPTOM "after entering, the ship does not react": a turn command must actually move it.
        // Judged ABOVE what the slew left behind, read here before the command: the hull is not
        // still after an inversion (0.27 rad/s measured in the sibling scenario below), and a bare
        // bar would be cleared by that residue with the controls dead.
        double omegaSettled = shipInfo().omega;
        // STIMULUS: fifteen raw mouse deltas two ticks apart are the turn command itself; the loop
        // applies the input and reads nothing. What it produced is judged after it — the cursor
        // through its `flight_cursor` link, the hull's answer through the rate window below.
        for (int i = 0; i < 15; i++) {
            mouseDelta(60, 0);
            bot().waitWorldTicks(2);
        }
        // NOT a chain, and this is the argument. The link on the way IN exists and is taken: the
        // command reaching the client's flight handler is `flight_cursor`, which is what
        // flightCursorX below reads. What has no link is the hull's ANSWER — no record says "it
        // turned", because nothing DECIDES that: the attitude law integrates a torque every tick and
        // the result is an angular RATE. A rate is measured, and the window's EXTREMUM is the
        // measurement, because a hull commanded round passes through every attitude and a last
        // sample of a completed turn reads as "unmoved".
        // WINDOW: the hull's rate over 20 readings two ticks of its world apart — the stretch the poll
        // it replaced could spend — and the verdict below is on the LARGEST of them.
        java.util.List<Double> turnRates = new java.util.ArrayList<Double>();
        // Both clocks: the client keeps the deflected cursor's turn command going while the hull answers.
        GameTicks.observe(worldAndClient(0), 20, TURN_WINDOW_GAP_TICKS,
                () -> turnRates.add(shipInfo().omega));
        double omegaAfter = java.util.Collections.max(turnRates);
        System.out.println("[deckcap] force-invert control cursor="
                + flightCursorX("at the force-invert leg") + " omegaAfter=" + omegaAfter);

        // SYMPTOM "after leaving, I fall through": dismount, the pilot must stay on the inverted deck.
        // The client's own capture of THIS dismount is the link; a body that fell through has none.
        Events clientEvents = clientEvents();
        long dismountMark = clientEvents.mark();
        exec("stellurgytest player dismount");
        String seeded = ShipIdentity.awaitCaptureHeldBy(clientEvents, dismountMark, scenarioShipId,
                "leaving the seat on THIS INVERTED ship must leave the ex-pilot captured BY IT on his"
                        + " own client, which is where the reported fall-through happens",
                DECK_LINK_BUDGET_TICKS);
        DeckCapture capture = deckCaptureOfThisShip(scenarioShipId,
                "after leaving the seat on an INVERTED ship the ex-pilot must stay resolved on THAT"
                        + " ship, not on whatever else is in the airspace");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = PlayerShipData.read(this::exec).playerY;
        System.out.println("[deckcap] force-invert dismount seed=" + seeded + " capture=" + capture.raw()
                + " clientY=" + clientY + " serverY=" + serverY);

        assertTrue("after ENTERING an inverted ship, a turn command must move it, not leave it dead "
                + "(omega " + omegaSettled + " before the command, largest " + omegaAfter + " after)",
                omegaAfter > omegaSettled + TURN_COMMAND_OMEGA_RAD_PER_S);
        assertTrue("after LEAVING an inverted ship, the pilot must stay resolved on the deck, not fall "
                + "through: " + capture.raw(), capture.verdict);
    }

    @Test
    public void aSeatedPilotCanStillTurnTheShipWhenItIsInverted() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        buildAndBoardShip(site);

        // Command the craft over to inverted and let it hold there. The subject is what the pilot's
        // controls do ONCE INVERTED — the maintainer's report is that they stop working there — and
        // that subject is still driven below by the real mouse. Only the way IN changed: rolling
        // over by mouse also demonstrated that the controls work on the way, but it arrived at a
        // variable attitude and SKIPPED whenever it undershot, which bought that side observation at
        // the price of the scenario running at all.
        // The same standing hold as the force-invert leg above, and the same argument: one command,
        // in force until the force-clear.
        double upBeforeSlew = deckCamera("shipUpY");
        String held = exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " 0 1 0 0");
        scenario().requireArranged("the attitude hold must be accepted by THIS craft's computer: "
                + held, Reply.of(held).bool("commanded"));
        // WINDOW: the reading is the client's deck camera, so the client's ticks count too.
        advanceWorldAndClient(0, INVERT_SLEW_WINDOW_TICKS);
        double releasedUpY = deckCamera("shipUpY");
        // His hands on the stick while the hold stands, so the inversion is what his computer keeps
        // once it is cut (see takeTheStickWhileHeld); it leaves the cursor centred.
        takeTheStickWhileHeld("at the inversion, before the probe hold is cut");
        exec("stellurgytest vs force-clear-by-id 0 " + scenarioShipId);
        // WINDOW: releasedUpY (the instant the command is cut) to shipUpY, and the assertion holds
        // BOTH ends inverted and prints both. The same ticks let the slew's residual spin decay
        // before the turn below; that spin is printed as omegaSettled and asserted nowhere.
        advanceWorldAndClient(0, INVERTED_HOLD_WINDOW_TICKS);
        double shipUpY = deckCamera("shipUpY");
        // An ASSERT: the attitude is commanded, so not being there is news, not a dice roll. And it
        // is read from the CLIENT's own camera state, which is what the pilot below is looking at.
        // THE TEST'S OWN, and looser than its server-side sibling above on purpose: this one is
        // read off the CLIENT's camera state, which lags the hull it is drawing, so the same
        // attitude reads shallower here. What it asserts is the same premise — past horizontal.
        assertTrue("arrangement: the craft must be inverted ON THE CLIENT before its controls are"
                + " tested there, and stay inverted once the command is cut (shipUpY before the slew="
                + upBeforeSlew + ", when cut=" + releasedUpY + ", after the window=" + shipUpY + ")",
                releasedUpY < INVERTED_UP_Y_ON_CLIENT && shipUpY < INVERTED_UP_Y_ON_CLIENT);
        double omegaSettled = shipInfo().omega;
        System.out.println("[deckcap] inverted-control shipUpY=" + shipUpY + " omegaSettled=" + omegaSettled);
        // The baseline the turn below is judged against. The hull is NOT still here — measured on the
        // 2026-09-23 gate, 0.28 rad/s left over from the slew — so "the turn spun it up" is asked
        // ABOVE that residue: the pilot's command must add the bar to what the slew left.

        // Now, WHILE inverted, command a fresh turn. The ship must respond - its angular velocity must
        // rise - just as it does upright. If it stays at rest, the controls are dead at inversion.
        // STIMULUS: twenty raw mouse deltas two ticks apart are the command; the loop applies the
        // input and reads nothing, and what it produced is judged after it, as in the leg above.
        for (int i = 0; i < 20; i++) {
            mouseDelta(60, 0);
            bot().waitWorldTicks(2);
        }
        double cursor = flightCursorX("after twenty raw mouse deltas while inverted");
        // Same measurement as the force-invert leg above, and the same argument: `flight_cursor` is
        // the link for the command going in — asserted three lines below, not merely printed — and
        // the hull's answer is an angular rate that nothing decides and no record carries. Read the
        // refusal there; it is not repeated here.
        // WINDOW: 30 readings two ticks of the hull's world apart, the stretch the poll it replaced
        // could spend; the verdict below is on the LARGEST.
        java.util.List<Double> turnRates = new java.util.ArrayList<Double>();
        // Both clocks: the client keeps the deflected cursor's turn command going while the hull answers.
        GameTicks.observe(worldAndClient(0), 30, TURN_WINDOW_GAP_TICKS,
                () -> turnRates.add(shipInfo().omega));
        double omegaTurning = java.util.Collections.max(turnRates);
        System.out.println("[deckcap] inverted-control cursor=" + cursor + " omegaTurning=" + omegaTurning);

        // THE TEST'S OWN sensitivity bar, in the cursor's own normalised units: what it refuses is
        // a deflection that did not register at all. A hard deflection drives the cursor toward 1.
        assertTrue("a hard flight-cursor deflection must register on the client even when inverted "
                + "(cursor=" + cursor + ")", Math.abs(cursor) > CURSOR_DEFLECTED);
        assertTrue("a seated pilot must still be able to TURN the ship when it is inverted - commanding a "
                + "turn must spin it up, not leave it dead (omega " + omegaSettled + " before the command,"
                + " " + omegaTurning + " after)", omegaTurning > omegaSettled + TURN_COMMAND_OMEGA_RAD_PER_S);
    }

    /**
     * The seated pilot takes the stick and lets it centre again, while a probe hold keeps the hull at
     * the attitude it was brought to — so the attitude his computer holds once the probe lets go is
     * the one he is sitting at, not the one it held before.
     *
     * <p>Derived from the flight computer's own rule: while a pilot is at the controls and asks for no
     * rotation, the reference it holds is pinned to where the ship IS
     * ({@code TileAdvancedFlightComputer.update}, the {@code !turning} re-seed), and an unmanned or
     * idle-seated craft keeps holding the last reference it had. A pilot who has never touched the
     * stick has sent no input at all — the client sends on a change ({@code PilotInputCadence}) — so
     * without this his craft still holds the attitude it was assembled at and slews back to it the
     * moment the probe is cleared, which is production doing its job and not the arrangement the
     * scenario needs.</p>
     *
     * <p>The link is the computer's own record of the input arriving ({@code pilot_input_set}, for
     * THIS ship). The centring's last send is the idle input; the scenario's following read of the
     * attitude over a window is what says the hold took.</p>
     */
    private void takeTheStickWhileHeld(String what) throws Exception {
        // STIMULUS: each iteration IS a push of the cursor off centre, and the loop ends on its goal
        // state — a deflection past the dead-zone, which is a CHANGE the client sends.
        for (int i = 0; i < 20 && Math.abs(flightCursorX(what + ", deflecting")) <= CURSOR_DEFLECTED; i++) {
            mouseDelta(60, 0);
            bot().waitWorldTicks(1);
        }
        long inputMark = serverEvents().mark();
        centreFlightCursor();
        // An ARRANGEMENT, not a verdict: the scenario's subject comes after the hold is cut, and this
        // only establishes that the attitude his computer holds then is the one he is sitting at.
        ArrangementFailure.arranged(() -> serverEvents().awaitRecordWithFields(inputMark, "pilot_input_set",
                what + " — the pilot's hands on the stick must reach THIS ship's flight computer, or"
                        + " the hold it keeps afterwards is still the one it was assembled with",
                DECK_LINK_BUDGET_TICKS, "vsShip", scenarioShipId, "input", "set"));
    }

    /**
     * A vector in the hull's own frame carried into the world by the hull's attitude as the ship
     * report gives it ({@code qw,qx,qy,qz}, the subspace-to-world rotation): v' = v + 2w(u×v) +
     * 2u×(u×v), u = (qx,qy,qz).
     */
    private static double[] rotateByShip(Reply pose, double x, double y, double z) {
        double w = pose.number("qw"), ux = pose.number("qx"), uy = pose.number("qy"), uz = pose.number("qz");
        double cx = uy * z - uz * y, cy = uz * x - ux * z, cz = ux * y - uy * x;
        double ccx = uy * cz - uz * cy, ccy = uz * cx - ux * cz, ccz = ux * cy - uy * cx;
        return new double[]{x + 2 * (w * cx + ccx), y + 2 * (w * cy + ccy), z + 2 * (w * cz + ccz)};
    }

    /** Feed a raw mouse delta to the client's own ship-pilot handler, as the window's mouse would. */
    private void mouseDelta(int dx, int dy) throws Exception {
        bot().invokeStaticInt(KEY_BINDINGS, "acceptShipPilotMouseDelta", dx, dy);
    }

    /**
     * Bring the client's flight cursor back inside its centre dead-zone.
     *
     * <p>A feedback controller, not a wait, which is why there is no event here to await: each
     * iteration nudges and then reads the cursor back off production's own {@code flight_cursor}
     * record, and the loop's TERMINATION is the goal state rather than something that happens to
     * the game. It stays bounded so a dead input path fails instead of spinning forever, and
     * {@link #flightCursorX} is what makes that failure legible — against a dead path it names the
     * empty window instead of handing back the value the last live tick left behind.</p>
     */
    private void centreFlightCursor() throws Exception {
        double cursor = flightCursorX("before centring");
        // STIMULUS: each iteration IS a nudge of the cursor, and the loop ends on its goal state;
        // delete it and the centring stops happening, not merely stops being watched.
        for (int i = 0; i < 200 && Math.abs(cursor) >= 0.03; i++) {
            int step = Math.abs(cursor) > 0.2 ? 30 : 2;
            mouseDelta(cursor > 0 ? -step : step, 0);
            bot().waitWorldTicks(1);
            cursor = flightCursorX("while centring, nudge " + i);
        }
    }

    /**
     * The flight cursor as the client last RECORDED it, or a failure naming the empty window.
     *
     * <p>Replaces a reflective read of a private production static. The field keeps its last value
     * when the input path stops running, so a poll cannot separate "the cursor is where I left it"
     * from "nothing has updated it since" — and the centring loop above, against a dead input path,
     * would nudge two hundred times and hand back a stale number that reads like a measurement.</p>
     */
    private double flightCursorX(String what) throws Exception {
        // The ship path writes one `flight_cursor` per client tick while a pilot seat is handling
        // input, so the first record after the mark is the next tick's cursor, with every delta
        // accepted before the mark already in it.
        long mark = clientEvents().mark();
        String rec = Events.lastRecord(clientEvents().await(mark, "flight_cursor",
                "a flight_cursor reading " + what + " — the client's flight-input path is not running,"
                        + " so there is no cursor reading to act on", DECK_LINK_BUDGET_TICKS));
        return Events.number(rec, "x");
    }

    // ---- Bug: camera/capture instability on a steeply tilted, HELD deck ------------------------

    @Test
    public void aClientPlayerRidingASteeplyTiltedDeckHasStableCaptureAndCamera() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);

        // Stand the client player on the UPRIGHT deck first (capture works there), then tilt the ship
        // and hold it. The player rides the deck; the camera and capture must stay STABLE while the ship
        // is stationary at a steep angle - not jitter frame-to-frame (RC-2 Euler pole) nor flicker the
        // capture (which alternates gravity and drags the body "back and forth through the deck").
        Events clientEvents = clientEvents();
        long landingMark = clientEvents.mark();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        // The message says CLIENT, so the read is the client's: his own resolver's capture record,
        // awaited rather than given 80 ticks. (The server probe beside it re-evaluates handles() for
        // the SERVER player and reads the SERVER state map — a second opinion, not this one.)
        ShipIdentity.awaitCaptureHeldBy(clientEvents, landingMark, scenarioShipId,
                "the client must be captured on THIS upright deck before the ship is tilted under him",
                DECK_LINK_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        assertTrue("server must agree the body is on the upright deck first: "
                + deckCapture.raw(),
                deckCapture.verdict);

        double h = Math.toRadians(90.0) / 2.0; // 90deg roll about the nose (+Z): deck on its side
        double upBeforeSide = shipInfo().upY();
        assertTrue("attitude hold must accept the tilt",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h))).bool("commanded"));
        // WINDOW: "on its side" is the band this class already holds the same 90-degree command to
        // (aFreshlyDismountedPilotStaysCaptured...Ninety).
        // SERVER-ONLY: the slew is a server command to the flight computer; the read is the server's ship report.
        GameTicks.advanceWorld(serverClient(), 0, SIDE_SLEW_WINDOW_TICKS);
        double upOnSide = shipInfo().upY();
        scenario().requireArranged("the deck must be on its side before its stability is sampled:"
                        + " upY " + upBeforeSide + " -> " + upOnSide + " over "
                        + SIDE_SLEW_WINDOW_TICKS + " server ticks, band [-0.35, 0.35]",
                upOnSide >= -0.35 && upOnSide <= 0.35);

        // Sample across frames while the ship is stationary. Any variation is instability, not motion.
        // The mark opens BEFORE the sampling: "the capture did not flicker" is an absence, and five
        // 4-tick samples of a server verdict cannot see a release that was recovered between two of
        // them — the client's log records every one, with the gate that performed it.
        long stabilityMark = clientEvents.mark();
        int n = 5;
        double rollMin = Double.MAX_VALUE, rollMax = -Double.MAX_VALUE;
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        int captured = 0, camOn = 0;
        StringBuilder trace = new StringBuilder();
        // WINDOW: how many samples had the deck camera on, and how far roll and height moved across
        // them, are properties of the observation rather than of a moment, so no record could
        // answer. What this cannot see: a capture or a camera that flipped and returned inside one
        // 4-tick sample.
        for (int i = 0; i < n; i++) {
            advanceServerAndClient(4);
            boolean active = Boolean.parseBoolean(deckCameraText("active"));
            double roll = deckCamera("roll");
            // Counted as "captured" only while the capture is anchored on THIS scenario's ship: the
            // claim below is that one capture held for the whole window, and a body handed from this
            // hull to a neighbour's and back keeps `verdict:true` at every sample.
            DeckCapture sample = DeckCapture.read(this::exec);
            boolean verdict = sample.verdict
                    && sample.anchoredOn(scenarioShipId);
            double y = bot().reportState().get("playerY").getAsDouble();
            if (active) { camOn++; rollMin = Math.min(rollMin, roll); rollMax = Math.max(rollMax, roll); }
            if (verdict) captured++;
            yMin = Math.min(yMin, y); yMax = Math.max(yMax, y);
            trace.append(String.format("[%d act=%b roll=%.1f verd=%b y=%.2f] ", i, active, roll, verdict, y));
        }
        double rollJitter = camOn > 0 ? rollMax - rollMin : 0.0;
        double yOsc = yMax - yMin;
        String flickers = clientEvents.since(stabilityMark, "deck_released");
        Events.assertInstrumentRan(flickers, "deck_capture_events",
                "the client's capture held for the whole stationary window");
        System.out.println("[deckcap] tilted-stability n=" + n + " captured=" + captured + " camOn=" + camOn
                + " rollJitter=" + rollJitter + " yOsc=" + yOsc + " releases=" + flickers
                + " :: " + trace);

        assertTrue("capture must stay STABLE on a held tilted deck, not flicker - the client released"
                + " it, and the record names the gate: " + flickers + " :: " + trace,
                Events.countRecordsWithField(flickers, "reason") == 0);
        assertTrue("capture must stay STABLE on a held tilted deck, not flicker (captured " + captured
                + "/" + n + "): " + trace, captured == n);
        // The camera's DISENGAGE is not recordable — production drops `shipCamActive` directly in the
        // two release branches without passing through the seam the engage edge is taken at, and the
        // mixin says so — so "it stayed engaged" is still read off the client's render flag.
        assertTrue("the deck camera must stay engaged on a held tilted deck (camOn " + camOn + "/" + n
                + "): " + trace, camOn == n);
        // THE TEST'S OWN, in degrees: the ship is STATIONARY, so the honest statement is that the
        // levelled roll does not change at all. Five degrees is the float noise of recomputing an
        // Euler angle from a quaternion each frame; the defect it exists for is the pole, where
        // the same attitude yields wildly different angles between frames.
        assertTrue("the levelled camera roll must be STABLE while the ship is stationary, not jitter at "
                + "the Euler pole (jitter=" + rollJitter + " deg): " + trace, rollJitter < LEVELLED_ROLL_JITTER_DEG);
        assertTrue("the client player must not be dragged through the deck (Y oscillation=" + yOsc
                + "): " + trace, yOsc < 1.0);
    }

    // ---- Bug: coordinate transforms break at extreme (inverted) attitudes ----------------------

    @Test
    public void anInvertedShipsMovementAndCameraFramesStayConsistent() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);

        // Flip the ship nearly upside-down: a 160-degree roll about its nose (+Z) - past inverted, but
        // shy of the exact 180 axis-angle singularity so the controller converges cleanly. Quaternion
        // (w,x,y,z) = (cos80, 0, 0, sin80). This is the regime the playtest saw break.
        // For these two hundred ticks NOBODY is near the craft — the observer is teleported into its
        // box only after the pose is read — and the substrate drops a physics object no player
        // holds. Measured 2026-09-16 in a full-tier pair: this leg's `ship-info` came back
        // `{"managed":false}`, the hull unloaded under it mid-slew, on the second run of a tree
        // whose first run was green. What keeps it loaded is no longer anything this leg says: a
        // test server holds every ship loaded from the moment the probes register.
        double upBeforeFlip = shipInfo().upY();
        assertTrue("attitude hold must accept the flip",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " 0.17365 0.0 0.0 0.98481")
                        ).bool("commanded"));
        // WINDOW: the flip; an attitude publishes no record, so the frame check below reads it.
        // SERVER-ONLY: the slew is a server command to the flight computer; the read is the server's ship report.
        GameTicks.advanceWorld(serverClient(), 0, LONG_SLEW_WINDOW_TICKS);

        ShipInfo info = shipInfo();
        double sx = info.x, sy = info.y, sz = info.z;
        // Inside the AABB so the probe resolves. No wait: the check is the SERVER's, it resolves the
        // hull from the server player's position, and `tp` has written that before it replies.
        exec("tp @a " + sx + " " + (sy + 1) + " " + sz + " 0 0");

        ShipFrameCheck tc = ShipFrameCheck.ofFirstPlayer(this::exec)
                .requireMeasured("the frame check must run on the player standing on this deck");
        System.out.println("[deckcap] inverted transform-check=" + tc.raw());
        // The attitude controller converges shy of a full 180 (axis-angle is singular there), settling
        // near 135deg - deck-up well past horizontal and pointing downward. That is a strongly non-trivial
        // attitude, which is all the consistency check needs.
        // THE TEST'S OWN arrangement fact. The controller settles shy of a full 180 (axis-angle is
        // singular there), near 135 degrees, so this asks for what it can actually reach: deck-up
        // well below horizontal, which is all the consistency check underneath needs.
        assertTrue("ship must be strongly inverted (deck-up points well below horizontal): upY "
                + upBeforeFlip + " -> " + tc.upQuatY() + " over " + LONG_SLEW_WINDOW_TICKS
                + " server ticks; " + tc.raw(), tc.upQuatY() < STRONGLY_INVERTED_UP_Y);

        // THE decisive check: the MOVEMENT frame (VS vector rotate, used by ShipFrameTravel) and the
        // CAMERA/gravity frame (the attitude quaternion) must describe the SAME rotation. A disagreement
        // here is the root of "the inverted ship drags me through the deck while the camera never turns
        // over" - movement resolving in one frame, the camera reading another.
        double upDis = tc.upDisagreement();
        double fwdDis = tc.fwdDisagreement();
        double posRt = tc.posRoundTripError();
        double rotRt = tc.rotRoundTripError();
        System.out.println("[deckcap] inverted upDis=" + upDis + " fwdDis=" + fwdDis
                + " posRt=" + posRt + " rotRt=" + rotRt);
        assertTrue("movement rotate and camera quaternion must agree on ship-up (disagree=" + upDis
                + "): " + tc.raw(), upDis < FRAME_AGREEMENT_EPSILON);
        assertTrue("movement rotate and camera quaternion must agree on ship-forward (disagree=" + fwdDis
                + ")", fwdDis < FRAME_AGREEMENT_EPSILON);
        assertTrue("world<->subspace position round-trip must be exact (err=" + posRt + ")", posRt < FRAME_AGREEMENT_EPSILON);
        assertTrue("world<->subspace rotation round-trip must be exact (err=" + rotRt + ")", rotRt < FRAME_AGREEMENT_EPSILON);
    }

    // ---- the surface a body stands on is the surface the renderer draws -----------------------
    //
    // A ship is DRAWN through the client's interpolated render transform, but every collision and
    // standing computation for a resolved body maps through the GAME-TICK transform. When the two
    // diverge (a hovering ship holding an attitude never stops moving), the surface the player collides
    // with sits visibly beside the surface he sees — the playtest report: "I walk not on the blocks I
    // see but about a block away from them". The observable is the client's `render_pose_skew` record,
    // written at every commit: the distance between the committed world position (tick pose) and where
    // the renderer draws the same subspace point, carrying the ship, the mode and the raw pair. Each leg
    // opens a WINDOW and reads it once, so its maximum is over every sample production produced.

    /** The steep inversion the hull leg needs, as deck-normal Y: the commanded ~160-degree roll settles
     *  shy of a full turn, so this asks for what it can reach — past vertical. */
    private static final double SKEW_STEEP_INVERSION_UP_Y = -0.3;
    /** How long a teleport is given to reach the CLIENT and be applied there. */
    private static final int SKEW_POS_LOOK_BUDGET_TICKS = 300;
    /** Client ticks a dropped body is watched before "did he start falling" is read: several times the
     *  three a free body needs to fall the 0.4 blocks the premise asks for. */
    private static final int SKEW_FALL_WATCH_TICKS = 20;
    /**
     * How long the commanded ~160-degree roll is given, in ticks: the attitude hold slews at a 2.0 rad/s
     * ceiling, ramping at 4.0 rad/s², so the 2.79 rad turn is about 45 ticks and the gate (up-Y below
     * -0.3, past 107 degrees) is crossed inside 30. The hold KEEPS the attitude once reached, which is
     * what makes a window safe here.
     */
    private static final int SKEW_ROLL_WINDOW_TICKS = 120;
    /** The observation points behind the skew and hull-sweep records, asserted before a silence is read. */
    private static final String SKEW_INSTRUMENT = "render_pose_skew_events";
    private static final String SKEW_SOLID_INSTRUMENT = "hull_collision_solid_events";
    /** The gap a player can feel as "standing beside the blocks I see": the contract bound. */
    private static final double VISIBLE_SKEW = 0.35;

    /**
     * A body standing on a ship stands on the surface the renderer draws: parked (the control), and
     * hull-standing on a hovering, inverted ship (the subject).
     *
     * <p>red-witnessed: with the hull-stand sweep's box ({@code ShipFrameTravel#hullStandTravel} at
     * {@code feet[0] - half, feet[1], feet[2] - half}) centred on the SHIP's up rather than the world's — the subspace-aligned phantom: "the largest
     * gap between the swept solid and the body's own box was 1.77 over 95 sweeps (… upY=-0.93)",
     * 2026-09-28. The two lines the wait rewrite touched are arrangements (the drop point is air, the
     * body starts falling).</p>
     */
    @Test
    public void theSurfaceABodyStandsOnIsTheSurfaceTheRendererDraws() throws Exception {
        final FixtureSite site = site();

        // ---- Leg A (control): a PARKED ship's render transform converges onto its tick pose, so the
        // skew of a body on its deck bounds the instrument's noise floor.
        Events events = serverEvents();
        double[] ship = buildShip(site);
        long captureMark = events.markInstrumented();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        // `deck_entered` is an EDGE; the sample below needs the deck to be HOLDING him when the wait
        // returns — the chain: the opening on this ship, later than the last release.
        ShipIdentity.awaitCaptureHeldBy(events, captureMark, scenarioShipId,
                "the client player must be TAKEN by THIS parked deck, and still held by it, before the"
                        + " control window opens", DECK_LINK_BUDGET_TICKS);
        DeckCapture parkedCapture = DeckCapture.read(this::exec);
        assertTrue("the client player must be captured on the parked deck before sampling: "
                + parkedCapture.raw(), parkedCapture.verdict);
        parkedCapture.requireAnchoredOn(scenarioShipId,
                "the client player must be captured on the parked deck this scenario built");
        long restMark = clientEvents().mark();
        double restCrossMax = 0.0;
        StringBuilder restTrace = new StringBuilder();
        // WINDOW: the MAXIMUM cross-side delta at rest — a difference between two rendered sides, which
        // no record carries. What it cannot see: a spike between two samples, hence the log read after.
        for (int i = 0; i < 5; i++) {
            double cross = renderCrossSideDelta();
            if (!Double.isNaN(cross)) restCrossMax = Math.max(restCrossMax, cross);
            restTrace.append(String.format(java.util.Locale.ROOT, "[cross=%.4f] ", cross));
            advanceServerAndClient(6);
        }
        String restReply = clientEvents().since(restMark, "render_pose_skew");
        Events.assertInstrumentRan(restReply, SKEW_INSTRUMENT,
                "a body standing on the parked deck is committed against a pose at all");
        RenderSkew rest = RenderSkew.of(restReply, null);
        System.out.println("[poseskew] rest " + rest + " crossMax=" + restCrossMax + " :: " + restTrace);
        assertTrue("the skew instrument must fire while standing on the parked deck: " + rest, rest.samples > 0);
        assertTrue("on a PARKED ship the drawn pose must sit on the tick pose (control; " + rest + "): "
                + restTrace, rest.max < VISIBLE_SKEW);

        // ---- Leg B (subject): a ship HOVERING on an attitude hold, inverted, so the world-top is a
        // hull-stand surface. The attitude is a physical value nobody publishes and the hold keeps it
        // once reached, so the slew gets its ticks and is then measured: upBefore -> upY, both in the
        // gate's message, says whether the hull never moved or was still slewing.
        double h = Math.toRadians(160.0) / 2.0;
        double upBefore = shipInfo().upY();
        assertTrue("attitude hold must accept the past-vertical roll",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0")).bool("commanded"));
        // WINDOW: the attitude is a value no record publishes, so the slew is read, not linked.
        advanceServerAndClient(SKEW_ROLL_WINDOW_TICKS);
        ShipInfo info = shipInfo();
        double upY = info.upY();
        System.out.println("[poseskew] upY " + upBefore + " -> " + upY + " over " + SKEW_ROLL_WINDOW_TICKS
                + " ticks (the gate is < " + SKEW_STEEP_INVERSION_UP_Y + ")");
        assertTrue("the ship must reach the steep inversion before the hull leg (upY " + upBefore + " -> "
                + upY + " over " + SKEW_ROLL_WINDOW_TICKS + " ticks of a commanded 160-degree roll): "
                + info.raw(), upY < SKEW_STEEP_INVERSION_UP_Y);
        // The drop point must be FREE AIR: the rolled ship sinks, so shipY+7 can land inside terrain.
        // Ship blocks live in the shipyard subspace, so the fill removes world terrain only.
        clearSkewDropColumn(info.x, info.y, info.z);
        info = shipInfo(); // re-read after the fill, which ran on the server thread before it answered
        double sx = info.x, sy = info.y, sz = info.z;
        String dropBlock = exec("stellurgytest block at 0 " + (int) Math.floor(sx) + " "
                + (int) Math.floor(sy + 7) + " " + (int) Math.floor(sz));
        assertTrue("the drop point must be free air — otherwise this leg measures the ground rather than"
                + " the hull: " + dropBlock + " ship=" + info.raw(), Reply.of(dropBlock).bool("isAir"));

        // Two marks, one per side, both before the stimulus: the SERVER's for the hull-stand mode
        // commit, the CLIENT's for the teleport's arrival.
        long hullMark = events.markInstrumented();
        long dropMark = clientEvents().mark();
        exec("tp @a " + sx + " " + (sy + 7) + " " + sz + " 0 0");
        clientEvents().await(dropMark, "client_pos_look_applied", "the drop teleport must be APPLIED"
                + " on the client before its fall can be watched", SKEW_POS_LOOK_BUDGET_TICKS);
        double preY = bot().reportState().get("playerY").getAsDouble();
        long preTicks = bot().reportState().get("ticks").getAsLong();
        // EXPERIMENT: the reading is DEFINED twenty client ticks after the placement was applied.
        bot().waitWorldTicks(SKEW_FALL_WATCH_TICKS);
        double fallY = bot().reportState().get("playerY").getAsDouble();
        // Excluded by construction: a client tick stall (waitWorldTicks errors on its own timeout — the
        // delta is printed so the proof travels with the red), a teleport that never landed (its
        // record was awaited), the world's ground (the column was cleared and asserted air). What
        // remains holding him up is the inverted hull or the deck capture — both the PRODUCT working.
        long tickDelta = bot().reportState().get("ticks").getAsLong() - preTicks;
        assertTrue("the teleported client must start falling before the hull leg. target=" + (sy + 7)
                + " preY=" + preY + " y after " + SKEW_FALL_WATCH_TICKS + " client ticks=" + fallY
                + " clientTicksElapsed=" + tickDelta + " (so this is NOT a tick stall) capture="
                + exec("stellurgytest vs deck-capture"), Math.abs(fallY - preY) > 0.4);

        // The hull-stand hold ENGAGING is a decision production commits in one place, awaited as the
        // link it is. Filtered on mode "hull": the same commit names both modes.
        String hullCommit;
        try {
            hullCommit = events.awaitField(hullMark, "deck_mode_committed", "mode", "hull",
                    "the encounter must engage the HULL-STAND hold before sampling", DECK_LINK_BUDGET_TICKS);
        } catch (AssertionError missed) {
            throw new AssertionError(missed.getMessage() + " | the server gate says: "
                    + exec("stellurgytest vs deck-capture") + " | client y="
                    + bot().reportState().get("playerY").getAsDouble());
        }
        System.out.println("[poseskew] hull-stand committed :: " + hullCommit
                + " | probe :: " + exec("stellurgytest vs deck-capture"));

        // The mode travels ON each record, so the filter below is per SAMPLE.
        long hullSkewMark = clientEvents().mark();
        StringBuilder hullTrace = new StringBuilder();
        double hullCrossMax = 0.0;
        // WINDOW: the MAXIMUM cross-side delta over the hull-stand mode. What it cannot see: a spike
        // inside one 13-tick gap.
        for (int i = 0; i < 6; i++) {
            double cross = renderCrossSideDelta();
            if (!Double.isNaN(cross)) hullCrossMax = Math.max(hullCrossMax, cross);
            hullTrace.append(String.format(java.util.Locale.ROOT, "[cross=%.4f y=%.2f] ",
                    cross, bot().reportState().get("playerY").getAsDouble()));
            advanceServerAndClient(13);
        }
        String hullReply = clientEvents().since(hullSkewMark, "render_pose_skew");
        Events.assertInstrumentRan(hullReply, SKEW_INSTRUMENT,
                "a body on the inverted hull is committed against a pose at all");
        RenderSkew hull = RenderSkew.of(hullReply, "hull");
        // What SOLID each of those sweeps consumed, against the body's own world box — from records:
        // the production static this used to read had become a literal 0.0 and could not fail.
        String solidReply = clientEvents().since(hullSkewMark, "hull_collision_solid");
        Events.assertInstrumentRan(solidReply, SKEW_SOLID_INSTRUMENT,
                "the hull-stand sweep consumed the body's own world-upright volume");
        int solidSweeps = 0;
        double solidOffsetMax = 0.0;
        String worstSolid = "(none)";
        for (String record : Events.records(solidReply)) {
            solidSweeps++;
            double offset = Events.number(record, "offset");
            // `>=` on the first sweep too: a healthy body measures exactly 0.0, and the narrative must
            // still name one sample.
            if (solidSweeps == 1 || offset > solidOffsetMax) {
                solidOffsetMax = Math.max(solidOffsetMax, offset);
                worstSolid = record;
            }
        }
        System.out.println("[poseskew] hull " + hull + " crossMax=" + hullCrossMax + " restMax=" + rest.max
                + " :: " + hullTrace);
        System.out.println("[poseskew] hull solid sweeps=" + solidSweeps + " offsetMax=" + solidOffsetMax
                + " worst=" + worstSolid);
        System.out.println("[poseskew] hull ship-info=" + shipInfo());

        // ---- Leg C (driver isolation, diagnostic): the same hull-stand configuration under SUSTAINED
        // motion. The discriminating number is the CROSS-SIDE delta: one that scales with the commanded
        // speed names the client pose LAG; a speed-independent one names a constant offset.
        for (double climb : new double[]{0.6, 1.2}) {
            assertTrue("velocity command must engage for the moving leg",
                    Reply.of(exec("stellurgytest vs force-vel-by-id 0 " + scenarioShipId + " 0 " + climb + " 0"))
                            .bool("commanded"));
            long moveMark = clientEvents().mark();
            double moveCrossMax = 0.0, moveCrossSum = 0.0;
            int moveCrossN = 0;
            StringBuilder moveTrace = new StringBuilder();
            // WINDOW: the MAXIMUM and the MEAN cross-side delta under motion; a mean is not something
            // one record can carry.
            for (int i = 0; i < 8; i++) {
                double cross = renderCrossSideDelta();
                if (!Double.isNaN(cross)) {
                    moveCrossMax = Math.max(moveCrossMax, cross);
                    moveCrossSum += cross;
                    moveCrossN++;
                }
                moveTrace.append(String.format(java.util.Locale.ROOT, "[cross=%.4f] ", cross));
                advanceServerAndClient(7);
            }
            RenderSkew moving = RenderSkew.of(clientEvents().since(moveMark, "render_pose_skew"), null);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[poseskew] moving climb=%.1f %s crossMax=%.4f crossMean=%.4f (n=%d) :: %s", climb, moving,
                    moveCrossMax, moveCrossN == 0 ? -1.0 : moveCrossSum / moveCrossN, moveCrossN, moveTrace));
            System.out.println("[poseskew] moving ship-info=" + shipInfo().raw());
        }
        exec("stellurgytest vs force-clear-by-id 0 " + scenarioShipId);

        assertTrue("the skew instrument must fire in HULL mode (" + hull + "): " + hullTrace, hull.samples > 0);
        assertTrue("a body hull-standing on a hovering ship must stand on the surface the renderer draws —"
                + " render-vs-collision pose skew " + hull + " (control at rest=" + rest.max + ", bound="
                + VISIBLE_SKEW + "): " + hullTrace, hull.max < VISIBLE_SKEW);
        // A hull-stand body is a WORLD-upright capsule; a sweep colliding a SUBSPACE-aligned box puts
        // every contact h*sin(tilt/2) from where the player sees himself (~1.77 blocks at ~160 degrees).
        assertTrue("the hull sweep must be OBSERVED before its solid can be judged — no sweep was recorded"
                + " in a window that committed " + hull.records + " hull poses", solidSweeps > 0);
        assertTrue("a hull-stand body must collide as its real world-upright volume, not a subspace-aligned"
                + " phantom displaced by h*sin(tilt/2) — the largest gap between the swept solid and the"
                + " body's own box was " + solidOffsetMax + " over " + solidSweeps + " sweeps (visible bound="
                + VISIBLE_SKEW + ", upY=" + upY + "); worst sweep: " + worstSolid, solidOffsetMax < VISIBLE_SKEW);
    }

    /**
     * One cross-side pose sample: the CLIENT's latest committed world position, from the last commit
     * RECORDED in a two-tick window, against the SERVER's mapping of the same subspace point through
     * THIS ship's transform by id. On a ship moving at {@code v} it carries an error of roughly
     * {@code v * 0.15 s}. NaN when the client committed nothing in the window, or the server cannot map
     * the point.
     */
    private double renderCrossSideDelta() throws Exception {
        long mark = clientEvents().mark();
        // WINDOW: whatever the client committed in two of its own ticks; none is NaN, which every
        // caller counts as "no signal".
        bot().waitWorldTicks(2);
        String latest = Events.lastRecord(clientEvents().since(mark, "render_pose_skew"));
        if (latest == null) {
            return Double.NaN;
        }
        String tw = exec("stellurgytest vs to-world 0 id " + scenarioShipId + " " + Events.number(latest, "subX")
                + " " + Events.number(latest, "subY") + " " + Events.number(latest, "subZ"));
        if (!Reply.of(tw).ok()) {
            return Double.NaN;
        }
        // absence is the answer: a mapping with no coordinate is no sample, NaN, which the callers skip.
        double dx = Events.number(latest, "commitX") - Reply.of(tw).numberOr("worldX", Double.NaN);
        double dy = Events.number(latest, "commitY") - Reply.of(tw).numberOr("worldY", Double.NaN);
        double dz = Events.number(latest, "commitZ") - Reply.of(tw).numberOr("worldZ", Double.NaN);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The render-pose skew samples of one window, folded: {@code records} is every commit, {@code samples}
     * those in the kept mode with a render pose to compare against, {@code max} the largest gap among
     * them. Kept apart because they fail differently — no records: the resolver never ran; records
     * without samples: the mode never occurred; {@code drawn:false}: nothing to compare against.
     */
    private static final class RenderSkew {
        final String mode;
        final int records;
        final int samples;
        final int undrawn;
        final double max;

        private RenderSkew(String mode, int records, int samples, int undrawn, double max) {
            this.mode = mode;
            this.records = records;
            this.samples = samples;
            this.undrawn = undrawn;
            this.max = max;
        }

        /** Fold a client {@code since} reply; {@code mode} filters to one resolution mode, or null keeps all. */
        static RenderSkew of(String sinceReply, String mode) {
            int records = 0, samples = 0, undrawn = 0;
            double max = 0.0;
            for (String record : Events.records(sinceReply)) {
                records++;
                if (mode != null && !mode.equals(Events.text(record, "mode"))) {
                    continue;
                }
                double skew = Events.number(record, "skew");
                if (Double.isNaN(skew)) {
                    undrawn++;
                    continue;
                }
                samples++;
                max = Math.max(max, skew);
            }
            return new RenderSkew(mode, records, samples, undrawn, max);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "mode=%s records=%d samples=%d undrawn=%d max=%.4f",
                    mode == null ? "*" : mode, records, samples, undrawn, max);
        }
    }

    /** Empty the WORLD terrain over the ship for the drop leg, from just above it to over the drop
     *  point at {@code y+7}. Ship blocks live in the subspace, and nothing below the ship is touched. */
    private void clearSkewDropColumn(double x, double y, double z) throws Exception {
        int fx = (int) Math.floor(x), fy = (int) Math.floor(y), fz = (int) Math.floor(z);
        String cleared = exec("stellurgytest fill 0 " + (fx - 8) + " " + (fy + 1) + " " + (fz - 8)
                + " " + (fx + 8) + " " + (fy + 14) + " " + (fz + 8) + " minecraft:air");
        assertTrue("the drop column must be cleared of world terrain: " + cleared, Reply.of(cleared).ok());
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The registry's own addShip, awaited as a LINK since a mark taken BEFORE the assembly is
        // queued — so the record is THIS scenario's ship and names it, where a whole-dimension count
        // that merely went up is answered by any neighbour that assembled one in the same window.
        Events events = serverEvents();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(site);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        scenarioShipId = awaitShipSpawned(events, spawnMark,
                "assembly must create a NEW VS ship in the queryable registry (async spawn)");

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so what is asked of the"
                        + " ship below is only answerable because a client got here");

        // The IDENTITY is already known: this scenario ASSEMBLED the ship, and the registry's own
        // `ship_spawned` record above names it. What still has to be waited for is a different fact
        // — the physics object being USABLE, which a registry add does not prove. That fact is
        // production's own `ShipEvent.ShipLoadedEvent`, recorded off the bus as `ship_usable`: the
        // conjunction the physics loop selects a ship by, where the old `managed:true` poll read a
        // literal `true` in the probe's reply builder and so waited on nothing. The wait is still
        // keyed BY ID: these scenarios hover, tumble and invert their ship on purpose, and a bounded
        // nearest-ship lookup cannot follow it there — out of the radius it answers about nothing,
        // inside it about a neighbour, and both replies read like a correct one. An id has no
        // distance term to be wrong about. The mark is `spawnMark`, taken BEFORE the assembly above:
        // `ship_usable` fires ONCE per load and is not a state to poll, so a later mark could miss it.
        awaitShipUsable(events, spawnMark, scenarioShipId);
        ShipInfo si = shipInfo();
        double[] where = {si.x, si.y, si.z};
        System.out.println("[deckcap] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
    }

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(FixtureSite site) throws Exception {
        double[] ship = buildShip(site);
        long seatClientMark = clientEvents().mark();
        mountPilotSeatOfShipAt(site.x, site.y, site.z);
        awaitClientMount(seatClientMark, "the bot must be seated as HIS OWN CLIENT renders him before"
                + " this helper hands the ship back — every caller drives him as a seated pilot, and"
                + " the pilot keys and mouse are read on the client", DECK_LINK_BUDGET_TICKS, "");
        return ship;
    }

    /**
     * Sit the bot on the pilot seat of the ship this scenario BUILT — the one it holds the id of,
     * not the one nearest a coordinate.
     *
     * <p>{@code stellurgytest vs seat-mount <dim>} takes the first {@code TilePilotSeat} in the world's
     * loaded-tile list, with no position filter — unambiguous when the world holds exactly one ship,
     * and a scenario mounting a NEIGHBOUR's ship once several scenarios share a world. The positional
     * {@code find-seat} was narrower and no cure: it resolves through {@code
     * VSBridge.shipyardBoundsAt}, which answers for the ship NEAREST the anchor — no containment
     * test, no distance bound, over the registry, so an unloaded craft or a blockless crossing
     * remnant is a candidate. Twelve scenarios share dim 0 here.</p>
     *
     * <p>{@code find-seat <dim> id <shipUuid>} resolves the yard from the ship's own name instead, so
     * a wrong craft is unreachable rather than merely unlikely. The base coordinates stay in the
     * signature because the failure message needs them: a reader diagnosing a miss wants to know
     * where the scenario thought its ship was.</p>
     */
    private void mountPilotSeatOfShipAt(int bx, int by, int bz) throws Exception {
        PilotSeat seat = PilotSeat.byId(this::exec, 0, scenarioShipId)
                .requireFound("find-seat must locate the pilot seat inside THIS scenario's ship ("
                        + scenarioShipId + ", built at " + bx + "," + by + "," + bz + ")");
        String mountInfo = exec("stellurgytest vs seat-mount-at 0 " + seat.seatX + " "
                + seat.seatY + " " + seat.seatZ);
        int dummyId = Reply.of("stellurgytest vs seat-mount-at", mountInfo).integer(DUMMY_ID);
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                Reply.of(exec("stellurgytest player mount-entity " + dummyId)).bool("mounted"));
    }

    private String assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`. The site stands
        // in the open-air band, where the craft rests on the launchpad the fixture lays at its own
        // Y — so this ASSERTS rather than digging the ten-block shaft it replaces, whose rim sat
        // above the hull and was read, for weeks, as the deck-capture gate misfiring.
        //
        // HEIGHT 24 is the ENVELOPE, not the hull: ~10 blocks of hull, a deck on top of it, a body
        // standing (~2) and jumping (~1.25) there, and the room the scenarios below dismount, drop
        // and roll in. A check sized to what is BUILT is green in exactly the case that failed.
        return RocketFixture.assembleAt(site, this::exec, VARIANT, 2, 24,
                "the hull, the deck a pilot dismounts onto, and the air he jumps into above it");
    }

    /** This scenario's ship, asked by identity, as the probe answered it. */
    private String shipInfoReply() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return exec("stellurgytest vs ship-info 0 id " + scenarioShipId);
    }

    /** The same reading, parsed — it refuses a craft this world does not hold. */
    private ShipInfo shipInfo() throws Exception {
        return ShipInfo.of(shipInfoReply());
    }

    /**
     * Is THIS scenario's ship loaded? Asked BY IDENTITY: with one boot per test a whole-dimension
     * ship count had exactly one ship to report on, and on a shared client it answers with whichever
     * neighbour's ship happens to be loaded.
     *
     * <p>It took three coordinates until today and used none of them, so every failure message built
     * around it printed a position the check had never looked at.</p>
     */
    private boolean shipIsLoaded() throws Exception {
        return ShipInfo.isLoaded(shipInfoReply());
    }

    // ---- A falling deck keeps the body it has taken -----------------------------------------------
    //
    // The two scenarios above hold a hovering deck. This one lets the deck FALL, because that is where
    // a body and its deck part company if anything does: an entity and a VS ship do not fall at the
    // same rate (an entity accumulates -0.08 per game tick against a 0.98 drag; a ship integrates its
    // field at its own step and drag), so a body resolved in the WORLD frame over a falling deck sinks
    // toward it and through it. A body resolved in the SHIP frame cannot — its ship-frame position is
    // authoritative and the deck is static in that frame — and the support probe that keeps it there
    // reaches further the faster the body falls in the ship frame. So the contract is stated on the
    // resolver's own record: taken onto this deck, and still held by it after the deck has fallen.

    /** How long the released deck is let fall, in ticks of the hull's world clock. */
    private static final int DECK_FALL_TICKS = 30;

    /**
     * How far the deck must have fallen for this scenario to be about a FALLING deck, in blocks. A
     * released craft covers 88.7 to 128.3 blocks in 61 ticks on the server tier (four runs,
     * 2026-09-29), so a
     * deck that clears this in thirty has been released; one that does not is still being held.
     */
    private static final double DECK_FELL_MIN = 4.0;

    /**
     * How far above its pad the deck is lifted before it is let fall, in blocks: more than a released
     * craft covers in {@link #DECK_FALL_TICKS} (at most about 31 from rest, scaling the server tier's
     * largest measured fall, 128.3 in 61 ticks, by the square of the time), so
     * the fall ends in the air and not on the pad.
     */
    private static final int DECK_FALL_CLEARANCE_BLOCKS = 60;

    /**
     * A pilot who stands up and is taken onto his deck stays on it while the deck falls.
     *
     * <p>red-witnessed: NOT YET — three attempts, all GREEN. (1) {@code ShipFrameTravel.FLOOR_PROBE_DEPTH}
     * at 0, 2026-09-29: with no floor in reach a body still touching the hull goes to HULL-STAND
     * ({@code ShipFrameTravel#handles} at {@code state.hullStand = true;}), which keeps the episode open, so no release is recorded.
     * (2) {@code ShipFrameTravel#travel} at {@code String shipId = anchored.shipId;} handing every aboard body to vanilla's world-frame travel,
     * 2026-09-30. (3) that, plus Valkyrien Skies' own carry off ({@code EntityDraggable#tickAddedVelocityForWorld} at {@code if (!e.isDead)}),
     * 2026-09-30. So the body is kept by more than one mechanism — the ship-frame resolver and the
     * substrate's world-frame collision — and none of the three drove the resolver to a release, which
     * is the only thing this verdict reads. What would turn it red is a release on a falling deck; the
     * production line that decides one there is not yet identified.</p>
     */
    @Test
    public void yABodyTakenOntoADeckIsKeptWhileTheDeckFalls() throws Exception {
        buildAndBoardShip(site());
        // Off the pad first: the launchpad is WORLD blocks under the hull, so a deck released where it
        // was built falls one block onto it and stops — measured 2026-09-29, 1.6 blocks in 30 ticks.
        // The rigid lift carries the seated pilot with it.
        liftClearOfThePad(scenarioShipId, DECK_FALL_CLEARANCE_BLOCKS);

        // Stand up the way a player does — the real sneak key — and wait for his OWN client to take
        // him onto THIS ship's deck. A riding body is excluded from capture, so standing up has to
        // produce an entry, and an entry is an edge the mark cannot miss.
        Events clientEvents = clientEvents();
        long clientMark = clientEvents.mark();
        long dismountMark = serverEvents().markInstrumented();
        bot().holdKey(Keyboard.KEY_LSHIFT);
        // STIMULUS: the sneak key held across client ticks, as a player holds it to stand up.
        bot().waitWorldTicks(4);
        bot().releaseKey(Keyboard.KEY_LSHIFT);
        ArrangementFailure.arranged(() -> serverEvents().await(dismountMark, "dismount", "the real sneak key"
                + " must take the pilot out of his seat before anything about the deck can be asked",
                DECK_LINK_BUDGET_TICKS));
        ArrangementFailure.arranged(() -> clientEvents.awaitField(clientMark, "deck_entered", "ship",
                scenarioShipId, "the ex-pilot's OWN client must take him onto THIS ship's deck when he"
                        + " stands up", DECK_LINK_BUDGET_TICKS));

        // Release the craft. Flight Assist is the unmanned mode switch: on, an unpiloted craft holds;
        // off, it is handed to the field.
        scenario().requireArranged("the flight computer of THIS craft must take the release",
                Reply.of(exec("stellurgytest vs fa-by-id 0 " + scenarioShipId + " false"))
                        .bool("afcResolved"));
        double deckFrom = shipInfo().y;
        // EXPERIMENT: the dose is DECK_FALL_TICKS of fall. The gate below is a LOWER bound on the
        // deck's drop, which extra ticks only make easier to meet — for a deck that is falling at all.
        advanceWorldAndClient(0, DECK_FALL_TICKS);
        double deckTo = shipInfo().y;
        scenario().requireArranged("the released deck must actually FALL, or nothing here is about a"
                + " falling deck: it went from " + deckFrom + " to " + deckTo + " in "
                + DECK_FALL_TICKS + " ticks", deckFrom - deckTo >= DECK_FELL_MIN);

        scenario().asserting("the body taken onto the deck is still held by it after the fall");
        assertTrue("a body taken onto a deck must still be held by it after the deck has fallen "
                        + (deckFrom - deckTo) + " blocks. The releases in the window, with production's"
                        + " own reason for each: " + clientEvents.since(clientMark, "deck_released")
                        + " ||| the episode edges: " + clientEvents.since(clientMark, "deck_entered"),
                ShipIdentity.endsCapturedBy(clientEvents, clientMark, scenarioShipId));
    }

    private int readInt(String json, String field) {
        return Reply.of(json).integer(field);
    }
}
