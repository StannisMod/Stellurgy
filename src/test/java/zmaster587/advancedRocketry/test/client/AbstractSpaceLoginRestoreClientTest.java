package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.lwjgl.input.Keyboard;

import java.nio.file.Files;
import java.nio.file.Path;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.LedgerEntry;
import zmaster587.advancedRocketry.test.PlayerPosition;
import zmaster587.advancedRocketry.test.SubsystemStatus;
import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.Chains;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

/**
 * Login restore for a tier-2 ship's crew, observed on the REAL CLIENT across a REAL server restart:
 * a player who logs out seated on his ship in a space cell has to come back aboard that ship, in his
 * ship's own slot dimension - not in the overworld, and not merely standing beside it.
 *
 * <p><b>Why this must be a client test.</b> The only real subject of this contract is a live player
 * with a client attached. The first restore phase rewrites where a logging-in player is placed, and
 * the second re-seats him a few ticks later; the unit tier fakes the whole world seam and the server
 * tier has no logging-in player at all, so both of those are blind here BY CONSTRUCTION. They are
 * already green. This test is therefore the primary verification, not a formality on top of one.</p>
 *
 * <p><b>Shape.</b> Two server boots over one world root, two client JVMs, manual harness lifecycle.
 * The harness reserves a fresh port on every boot, so a client cannot be reconnected across a
 * restart; a second client JVM is started instead. That is sound because the client's identity is
 * deterministic - every client launches under the same username and therefore under the same
 * offline-mode UUID, and the server keys player data by UUID. Boot 1 arranges and witnesses, then
 * the server is simply stopped with NO explicit save first: the shutdown save is part of what is
 * under test, and saving twice would hide an implementation that only marks its snapshot dirty
 * during that last pass.</p>
 *
 * <p><b>The ship gets into space the way a ship really gets into space.</b> The arrangement does not
 * conjure a settled ship: it builds a piloted tier-2 ship on the ground with the real assembler,
 * feeds it a held-throttle input so its flight computer sees a pilot flying, and lifts it past the
 * launch dimension's orbit ceiling. The flight computer's own server tick then runs the entry
 * on-ramp, which picks the destination cell, crosses the ship into it and settles it in the ship
 * ledger. Every identifier this test works with - the ship id, the cell, the slot dimension - is
 * therefore CHOSEN BY PRODUCTION and read back, never invented here. That matters beyond tidiness:
 * the restore reads the very ledger that entry wrote, so an arrangement that wrote its own ledger
 * would be testing a fixture instead of the subsystem.</p>
 *
 * <p><b>What the instrument actually delivers.</b> Acceptance is client-observed: the client's own
 * rendered dimension, its own riding entity, its own position. The end STATE is still what the
 * assertions pin, and "he never appeared in the overworld" is still not proven by them - they read
 * where the client is when they look. What has changed is that the path is no longer invisible: the
 * client now keeps an ordered transcript of the worlds it was put into ({@code
 * client_dimension_changed}, one record per world the connection built), so a restore that flickered
 * through an overworld frame leaves a record of it in the log every failure here prints, even though
 * no assertion refuses it. The restore itself is read on the SERVER's log as the chain production
 * commits - {@code login_restored}, {@code crew_transfer_reseated}, {@code mount} - where the poll
 * this replaced could only report a client that was not seated, for any of four reasons.</p>
 *
 * <p><b>Exactly ONE ship in the cell.</b> The entry materializes a fresh cell for a single ship, and
 * that is load-bearing rather than incidental: the re-seating matches a seat by proximity to the
 * ship's pose and by the seat's flight-computer link offset, with no ship-id filter. Two ships of
 * the same fixture geometry parked near each other share that offset, so a second nearby ship could
 * satisfy the riding assertion for the wrong ship. One ship per cell removes the ambiguity by
 * construction instead of by hoping. The same assumption lets the ship be located by "the ship
 * nearest any point in the cell" without a search.</p>
 *
 * <p><b>Scope.</b> Every branch of the restore decision is already pinned exhaustively at the unit
 * tier and is deliberately not re-derived here. What this adds is that the REAL wiring runs that
 * decision: a real ledger written by the real entry path, persisted to and restored from disk, the
 * real login hook, a real slot dimension registered again on the second boot, and a real client that
 * has to end up inside it - with a WORKING control chain: a seated return whose held key no longer
 * flies the ship is the play-reported shape of a broken relog, so both sides of the restart hold the
 * client's real vertical-up key and require the client-rendered altitude to climb (the pre-restart
 * leg makes a post-restart red attributable to the restore rather than to a chain that never worked
 * in the cell).</p>
 *
 * <p>Position is never written out as a literal. The pilot is expected back at his ship, so the
 * ship's own live pose is the actual he is compared against; and that pose is separately required to
 * realize a coordinate inside his ship's LEDGERED cell, checked through
 * {@link CellWorldMapper#coordOfPose(GalacticCoord, double, double, double)} - the documented
 * inverse of the cell-to-world pose mapping. That is what catches "right dimension, ordinary block
 * height": a position outside the pose band renormalises into a neighbouring sector and stops
 * matching the cell. Hardcoding the band offset instead would turn a legitimate retune of a value
 * documented as tunable into a test failure.</p>
 *
 * <p>Skips (never fails) when the server harness is off, when the client harness is off, or when
 * Valkyrien Skies is absent - the production subsystem declines to register without it, so the
 * wiring under test would not exist at all.</p>
 */
public abstract class AbstractSpaceLoginRestoreClientTest {

    /** The account every client harness launches under; the server keys his player data by it. */
    protected static final String BOT = "ForgeTestClient";

    /**
     * The ship fixture. A crew member has to be able to STAND and WALK on this ship: the seat-only
     * variant has no floor, and a body walked off it is dropped by the capture with
     * {@code noHullContact} - which arrives as a silent record and reads exactly like "he did not
     * move". This variant adds the 5x5 deck under the seat and is the one the planet-side walking
     * relog test flies for the same reason.
     */
    protected static final String VARIANT = "with-pilot-deck";

    /** Where an orphaned login lands, and the one dimension a restored pilot must NOT be in. */
    protected static final int OVERWORLD_DIM = 0;

    // `SHIP_LOST_NEEDLE` lived here — a fragment of the sentence a pilot reads when the server has no
    // record of his ship. Its one consumer now waits on the restore's own verdict instead
    // (`login_restored` carrying `reason:SHIP_UNKNOWN`): the sentence is a rendering of that
    // decision, it cannot say WHICH of the four orphan causes fired, and it moves when the language
    // file does.

    /** The launch dimension the ship takes off from - always registered, always terrain-generated. */
    protected static final int LAUNCH_DIM = 0;

    /** Where the piloted ship is built: a loaded overworld region well clear of other fixtures. */
    protected static final int SRC_X = 6800;
    protected static final int SRC_Y = FixtureSite.OPEN_AIR_Y;
    protected static final int SRC_Z = 6800;

    /** A world height comfortably above the default orbit ceiling, so the ceiling check fires. */
    protected static final int ABOVE_CEILING_Y = 1200;

    /**
     * The six flight channels - forward, vertical, strafe, yaw, pitch, roll - as the flight-input
     * probe takes them. {@link #HELD_CLIMB} is a pilot holding the ship up; letting go is the same
     * verb with the channels omitted, which CLEARS the input rather than publishing a zero one - and
     * the difference matters, because an all-zero input is still an input and keeps the computer in
     * its piloted branch.
     *
     * <p>They are now issued through {@code vs ff-input-by-id <dim> <shipId> …}, which writes ONE
     * ship's flight computer. The verb they used to go through wrote a JVM-wide static that every
     * flight computer read as its fallback, so a throttle held here flew every other ship on the
     * server too - and, worse for this family, it never went away: an all-zero input is still an
     * input, so the computer took the PILOTED branch forever and re-pinned the ship's attitude every
     * tick. That is why the inverted deck-crew leg could not roll its ship with the attitude verb and
     * had to roll it through the input instead.</p>
     */
    protected static final String HELD_CLIMB = "0 1 0 0 0 0";

    /**
     * The world-frame speed, in blocks/second, at which a window's deck is driven.
     *
     * <p>0.2 blocks per tick: a {@link #OBSERVE_TICKS}-tick window sees 8 blocks, well over
     * {@link #MIN_DECK_MOVE} and well under {@link #MAX_DECK_RATE}. Deterministic, because the
     * controller realizes a commanded world velocity directly rather than integrating a setpoint.</p>
     */
    protected static final double WINDOW_SPEED_BLOCKS_PER_SECOND = 4.0;

    /**
     * How far off his ship the client may be and still count as "back at his ship". Covers the
     * seat's offset from the ship's own origin plus a few ticks of settling, and is far too small to
     * be satisfied by any other dimension's spawn.
     */
    protected static final double POSE_EPSILON = 24.0D;

    /**
     * How long the re-seated client is given to finish resolving WHERE its seat is, in ticks.
     *
     * <p>Not a budget: the mount itself is a link and is awaited as one. What remains afterwards is
     * the rider's position being written each tick, which nothing publishes — so it is measured
     * through a window, and this is the window. The resolution advances per tick, so this number
     * says how much the world does.</p>
     */
    protected static final int SEAT_SETTLE_TICKS = 40;

    /** Sentinel for "the client has no world yet", so nobody reads a "dim" key that is absent. */
    protected static final int NO_CLIENT_WORLD = Integer.MIN_VALUE;

    /**
     * A demonstrable held-key climb: well above settle jitter, cheap to reach. Same bar as the
     * planet-side relog-control pin ({@link VSPilotSeatRelogControlE2ETest}) - the contract is
     * "held input MOVES the ship within a bounded window", not any particular rate.
     */
    protected static final double MIN_CLIMB = 1.0;

    /**
     * The no-input observation window, in ticks. Two seconds: long enough that a drift of the
     * reported size (about a block per thirty ticks) is unmistakable, short enough not to invite the
     * station-hold's own settling into the measurement.
     */
    protected static final int OBSERVE_TICKS = 40;

    /**
     * The floor below which the window is not evidence. "The body did not move" and "the resolver
     * never ran" are the same reading, and only one of them is a pass.
     */
    protected static final int MIN_RESOLVED = 20;

    /**
     * How far a no-input body may travel along its deck across the window, in blocks. Same bar as the
     * planet-side relog pin, so a red here is comparable with that leg rather than a new standard;
     * the reported drift is several times it.
     */
    protected static final double DRIFT_TOLERANCE = 0.35D;

    /**
     * How far the ship itself must travel across the window for the window to mean anything, in
     * blocks. A body held to a motionless deck cannot drift however broken the hold is - the first cut
     * of this pin measured exactly that and came back all zeros.
     */
    protected static final double MIN_DECK_MOVE = 1.0D;

    /**
     * And the ceiling of that band. The reported symptom is a ship that is ALMOST STATIONARY -
     * settling - which is also the regime the capture-mode flip lives in (~0.15 blocks/tick).
     * Holding the throttle through the window instead let the ship reach its cruise cap, two blocks
     * per tick, and there the client's record simply went silent: a different regime with its own
     * suspected defect, tracked separately. Expressed as a RATE, in blocks per tick: nothing damps a
     * pulse in a space cell, so the totals accumulate window over window while the rate is what
     * actually distinguishes a settling ship from one at its cap.
     */
    protected static final double MAX_DECK_RATE = 0.75D;

    /**
     * The throttle pulse, in ticks: enough to set the ship moving, short enough that what the window
     * observes is the station hold SETTLING it rather than the ship accelerating to its cap.
     */
    protected static final int THROTTLE_PULSE_TICKS = 8;

    /**
     * The per-tick step band a creep lives in, in blocks: above the floating-point noise of a held
     * point, below the one-off jump of a placement or a teleport. Summing only the steps inside it
     * separates "he was put somewhere" from "he is being walked along the deck".
     */
    protected static final double CREEP_STEP_MIN = 0.002D;
    protected static final double CREEP_STEP_MAX = 0.30D;

    /**
     * How far the walk itself must carry him along the deck, in blocks, for the post-release window to
     * be about an inherited velocity rather than about a body that never walked.
     */
    protected static final double MIN_WALK_TRAVEL = 0.5D;

    /** The class whose client-side statics carry the per-tick ship-frame record. */
    protected static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";

    /**
     * One line of that record: {@code <tick><path>|B=<live body point>|H=<committed point>|m=<incoming
     * ship-relative motion>|c=<carry>|in=<strafe>/<forward>|d=<on deck>}. Every field is in the SHIP's
     * frame, which is what makes a drift measurable at all.
     */

    protected static final String SHIP_ID = "shipId";
    /** The PHYSICS id in a {@code vs ship-uuid} reply — the other half of a tier-2 craft's identity. */
    protected static final String SHIP_UUID = "id";
    protected static final String BUILDER_POS = "builderPos";
    protected static final String FORGE_DIMS = "forgeDimensions";

    /** The ship production minted for the arranged pilot, and the cell production settled it in. */
    protected String arrangedShipId;

    /**
     * The settled ship's flight computer, as {@code "x y z"} — its own subspace block position, taken
     * from {@code space find-afc} at the same moment {@link #arrangedShipId} is.
     *
     * <p>Held because a tier-2 ship has TWO identities and the probe verbs split along that seam:
     * {@link #arrangedShipId} is the DURABLE id the space ledger is keyed by, while the {@code
     * *-by-id} command verbs resolve a VS ship uuid. Handing one to the other resolves nothing and
     * says so. This is the bridge between them, and it is still identity addressing: a block position
     * names exactly one tile, unlike "the ship nearest a point".</p>
     */
    protected String arrangedAfcPos;
    protected String arrangedCellKey;

    protected Path root;
    protected RealDedicatedServerHarness serverHarness;
    protected RealClientHarness clientHarness;

    @Before
    public void seedWorldDirectory() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled - set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        root = Files.createTempDirectory("forge-client-space-login-restore-");
    }

    @After
    public void stopHarnesses() throws Exception {
        closeBoth();
    }

    // `chatLineContaining` lived here, scraping the last twenty chat lines for a rendered sentence.
    // Its only caller now awaits the game event that sentence announces: a chat line is a RENDERING
    // of something the game did, so the thing it renders is what a test has to be about.

    /**
     * The shared subject of both positive legs: whatever route put the pilot in his seat in a cell,
     * stopping the server under him must bring him back in that seat, in his ship's cell, with a
     * control chain that still flies. Takes the slot dimension the arrangement banked him in.
     */
    protected void requireHeComesBackAboardHisShip(int slotDim) throws Exception {
        // CONTROL LEG (pre-restart): the seated pilot's REAL key must fly the ship in its cell
        // BEFORE the restart - without this, a dead key after the reboot could be a chain that
        // never worked in the cell at all, and the post-restart assertion could not indict the
        // restore. The stimulus is the client's own vertical-up key, not the flight-input probe
        // the arrangement used: what is being proven here is the key->packet->flight-computer
        // chain the restored pilot will need again on the other side of the restart.
        double preY0 = clientPlayerY();
        double preY1 = requireClimbWith(Keyboard.KEY_R, preY0,
                "control leg: the seated pilot must be able to fly his ship in its cell BEFORE the"
                        + " restart");
        // No settle before the logout. One stood here for "a ship still drifting when the server
        // stops turns the restore's comparison into a moving target" — but that comparison reads the
        // ship's LIVE pose after the client's, on the far side of the restart, against a pilot
        // SEATED on it, and a seated pilot drifts with his ship. Nothing below reads the drift.

        // The restore can only be exercised if he is STILL aboard in the slot dimension at the moment
        // the server writes him to disk. Assert that here rather than at the end: a pilot who has
        // already been moved out by this point makes the whole reboot leg vacuous, and the resulting
        // "he came back in the overworld" would be a statement about the arrangement, not about the
        // restore. Failing here says "he never logged out aboard"; failing after the reboot says
        // "he logged out aboard and did not come back".
        // Read this SERVER-side, not from the client. What gets written to disk is the server's
        // player entity, and the two can disagree: the client keeps rendering the cell it was sent
        // to while the server has already put the entity somewhere else. A client-side check here
        // passes in exactly the case this assertion exists to catch.
        PlayerPosition serverBeforeLogout = PlayerPosition.of(this::exec, BOT);
        JsonObject ridingBeforeLogout = bot().reportRidingEntity();
        assertEquals("the SERVER must still have him in his ship's slot dimension when it writes him "
                        + "to disk - the login restore keys off the saved dimension, so if he is "
                        + "banked in the overworld here the reboot leg proves nothing: "
                        + serverBeforeLogout.raw() + " clientRiding=" + ridingBeforeLogout,
                slotDim, serverBeforeLogout.dim);
        // The dimension FIELD is what gets persisted, and it is maintained separately from the world
        // the entity ticks in. If it has drifted back to the overworld while he stands in the cell,
        // he is written to disk as an overworld player and the restore can never fire for him.
        assertEquals("the pilot's persisted dimension field must match the cell he is standing in: "
                        + serverBeforeLogout.raw(), slotDim, serverBeforeLogout.dimField);

        // The pool's composition on this side of the restart, kept so the boot-2 assertions can say
        // whether the slot the pilot was banked in still means the same thing afterwards.
        SubsystemStatus statusBefore = SubsystemStatus.read(this::exec);

        // Deliberately NO explicit save before the stop: what survives has to survive the shutdown
        // save alone, which is the only save a real operator's stop ever runs.
        closeBoth();
        keepBootLog("boot1");

        // --- boot 2: a brand new server JVM and a brand new client JVM, same world root ----------
        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        SubsystemStatus statusAfter = SubsystemStatus.read(this::exec);
        assertTrue("the production subsystem must come up again on boot 2, or nothing below is "
                + "exercising it: " + statusAfter.raw(), statusAfter.registered);

        // The pool re-mints its slot dimension ids on every boot, so the id the pilot was banked under
        // (slotDim) routinely means nothing on this side of the restart - the two sets can be entirely
        // disjoint. That is NOT asserted either way here: it is the subsystem's business, and pinning
        // it would freeze an implementation detail. What matters is that the restore survives it, which
        // is what the assertions below measure. The two pool snapshots ride along in their failure text
        // so that a red is attributable to the id churn rather than merely correlated with it.
        String pools = "\n  pool on boot 1: " + statusBefore.slotDims()
                + "\n  pool on boot 2: " + statusAfter.slotDims()
                + "\n  pilot was banked in slot dim " + slotDim;

        // The ledger is what carries the ship across the restart, so read it back BEFORE the client
        // connects: a restore that finds no ledgered ship resolves "ship unknown" and drops the
        // pilot at an ordinary spawn, and that failure must be attributable to the ledger rather
        // than to the login hook.
        LedgerEntry ledger = LedgerEntry.forShip(this::exec, arrangedShipId);
        assertTrue("the settled ship must survive the shutdown save and come back in the ledger - "
                + "without it there is nothing for the restore to restore him onto: " + ledger.raw(),
                ledger.found);
        assertEquals("and it must come back SETTLED in the same cell it entered: " + ledger.raw(),
                arrangedCellKey, ledger.cellKey());
        assertEquals("a ship that came back in some other ledger state would send the restore down "
                + "a different branch entirely: " + ledger.raw(), "SETTLED", ledger.state());

        // Issued BEFORE the client connects: the restore fires on his connection, and a headless
        // server has nobody standing near the ship to hold it loaded for the re-seating.

        // AND THE MARK BEFORE THE CLIENT EXISTS, because the restore fires ON the connection: a
        // reader that arrived after it would find an empty log and could not tell that from a
        // restore that never ran. This boot's log is new - a sequence from boot 1 means nothing
        // here - so the mark is taken on the boot-2 server, not remembered across the restart.
        Events events = events();
        long restoreMark = events.mark();

        startClient();
        bot().waitForWorld();

        // THE RESTORE, AS THE CHAIN PRODUCTION COMMITS IT, on the server that performs it: the
        // login hook resolves where he belongs (login_restored, carrying its own reason - NO_TAG,
        // SHIP_UNKNOWN, CELL_UNAVAILABLE, ABOARD_SETTLED - and the dimension it chose), the pending
        // seat is drained onto his re-assembled ship (crew_transfer_reseated, carrying the caller
        // trail that tells a login re-seat from a crossing one), and he is put on the mount.
        //
        // This is what the poll it replaces could not say. The re-seat retries for a couple of
        // hundred server ticks and then gives up SILENTLY, leaving him standing aboard, so a client
        // that came back un-seated used to be four different failures wearing one number: the
        // restore never ran, it ran and sent him elsewhere, the seat never re-appeared, or the
        // client was merely slow. Each is now a different link of this chain.
        // TWO links, and the pair is deliberate: the restore DECIDED (a pure resolve, so this is the
        // hook running rather than some other mechanism seating him) and he ENDED UP on a mount.
        // `crew_transfer_reseated` is NOT in the chain, though it is printed below: it is taken at
        // the RETURN of the very method that performs the mounting, so `mount` before it is
        // guaranteed by nesting, not promised by anything. Asserting that order would pin the call
        // structure — rewrite the re-seat to mount through another path and this reds while the
        // player's experience is identical. The contract itself is asserted at the foot of this
        // method, where it belongs: he is in a world, not the overworld, riding, and riding a seat.
        events.assertChain(restoreMark, "a pilot who logged out seated must be RESTORED by the "
                        + "login hook and end up on a mount",
                RESTORE_LINK_BUDGET_TICKS, "login_restored", "mount");
        String restored = events.since(restoreMark, "login_restored");
        String reseats = events.since(restoreMark, "crew_transfer_reseated");
        String chain = "\n  login_restored: " + restored + "\n  crew_transfer_reseated: " + reseats;

        // And the CLIENT's own side of it, on the client's own log: its world became a dimension.
        // Awaited separately rather than appended to the chain above - the two logs are joined only
        // by game tick, and cross-side order within one tick is undefined. Zero is the mark because
        // this client JVM is BRAND NEW: the fact wanted here is recorded at handleJoinGame, inside
        // startClient, before any mark could have been taken.
        String joined = awaitClientEvent(CLIENT_SESSION_START, "client_dimension_changed",
                "the restored client must end up IN a world" + chain, RESTORE_LINK_BUDGET_TICKS);
        // The server has put him back on the mount; the CLIENT still has to PERFORM it. That is a
        // link, not a round trip to be sampled - his own `startRiding` - and it is waited for as
        // one. A bounded poll of `reportRidingEntity` stood here, justified as "not a link this
        // test owns"; the record it could have read was already being printed three lines below,
        // into this method's own failure text.
        awaitClientEventWithField(CLIENT_SESSION_START, "mount", "ok", true,
                "the restored client must PERFORM the mount the login put him back on" + chain,
                RESTORE_LINK_BUDGET_TICKS);
        // Read once, now that the link says it happened: a settled state.
        JsonObject riding = bot().reportRidingEntity();
        int dim = clientDim();
        JsonObject state = bot().reportState();
        String observed = "clientDim=" + dim + " riding=" + riding + " state=" + state + pools
                + chain + "\n  client dimension changes: " + joined
                + "\n  client mounts: " + clientEvents().since(CLIENT_SESSION_START, "mount");

        assertTrue("the client must have a world at all before anything can be read from it: "
                + observed, dim != NO_CLIENT_WORLD);
        assertNotEquals("he logged out aboard his ship, so he must NOT come back in the overworld. "
                + "Note dim 0 is an AMBIGUOUS failure: it is produced both by the subsystem's own "
                + "orphan fallback and by vanilla silently forcing dim 0 when the target world did "
                + "not load - the login_restored record above is what separates them, and it names "
                + "the reason and the dimension the hook chose. " + observed, OVERWORLD_DIM, dim);
        assertTrue("the pilot must come back SEATED on his ship rather than merely in its cell - "
                + "being put back in the chair is what a player experiences as the restore working: "
                + observed, riding.get("riding").getAsBoolean());
        assertTrue("and the thing he is riding must be a ship seat's mount: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));

        // Which dimension he came back to only means something relative to his ship. Slot ids are a
        // POOL and are re-minted every boot, so the id itself is not stable across a restart and
        // must not be asserted; what must hold is that the slot he is seated in is bound to HIS
        // ship's cell. Sitting down re-stamps the aboard record from the world he is actually in,
        // which is exactly that statement, and it also proves the record was rebuilt rather than
        // merely surviving.
        String tag = exec("artest space aboard-tag " + BOT);
        assertTrue("being re-seated must leave him aboard again: " + tag, Reply.of(tag).bool("tagged"));
        assertTrue("he must be back aboard the SAME ship, not some other one: " + tag
                + " (entered ship " + arrangedShipId + ")", arrangedShipId.equals(Reply.of(tag).text("shipId")));
        assertTrue("and the slot dimension he woke up in must be the one bound to his ship's cell "
                + arrangedCellKey + " - a different cell would mean the restore materialized the "
                + "wrong address: " + tag, String.valueOf(arrangedCellKey).equals(Reply.of(tag).text("cell")));

        // Where the ship actually is, right now, in the dimension the client reports. Read from the
        // server rather than remembered from boot 1, so the comparison is against the ship's live
        // pose and not against a snapshot that a legitimate drift would invalidate.
        double[] shipPose = awaitShipPose(dim);
        assertNotNull("his ship must be live in the dimension he came back to - if it is not, "
                + "'he is riding something' says nothing about the ship: " + observed, shipPose);

        assertTrue("the client must report a player position: " + state,
                state.get("worldReady").getAsBoolean());

        // Being seated is reported before the client has finished resolving WHERE the seat is: right
        // after the join its X and Z snap to the ship while Y is still converging, so a sample taken
        // the instant "riding" turns true catches a position that belongs to neither end.
        //
        // A WINDOW, and not a poll. The loop that stood here re-read until
        // `|clientY - shipPose[1]| <= POSE_EPSILON`, which is the assertion below — so a green said
        // "one sample happened to match" and a red was a timeout wearing the words of a position
        // claim. The act this was really waiting on is his own mount, and that is a link, awaited
        // above; what is left is the rider's position being written each tick, which nothing
        // publishes and no record could carry. So: give it the ticks, then read.
        double joinY = state.get("playerY").getAsDouble();
        // WINDOW: a value converging, where nothing decides. Its two ends are `joinY` (read the
        // instant the mount link closed) and the read below, and both are printed and asserted
        // against, so a red says whether he was converging or never left the wrong height.
        bot().waitTicks(SEAT_SETTLE_TICKS);
        state = bot().reportState();
        double clientX = state.get("playerX").getAsDouble();
        double clientY = state.get("playerY").getAsDouble();
        double clientZ = state.get("playerZ").getAsDouble();
        // The ship's pose read AFTER the client's, so the comparison is against where the ship was
        // at the later of the two moments: a craft that drifted between the reads shows up as a
        // residual rather than being hidden by a reference taken before it moved.
        double[] settledPose = awaitShipPose(dim);
        if (settledPose != null) {
            shipPose = settledPose;
        }
        System.out.println("[restore] seat settle after " + SEAT_SETTLE_TICKS + " ticks: client="
                + clientX + "," + clientY + "," + clientZ + " ship=" + shipPose[0] + ","
                + shipPose[1] + "," + shipPose[2] + " dY=" + Math.abs(clientY - shipPose[1])
                + " joinY=" + joinY + " (the bar is " + POSE_EPSILON + ")");
        observed = "clientDim=" + dim + " riding=" + bot().reportRidingEntity() + " state=" + state
                + " shipPose=[" + shipPose[0] + "," + shipPose[1] + "," + shipPose[2] + "]"
                + " clientY at the mount link=" + joinY + ", " + SEAT_SETTLE_TICKS
                + " ticks later=" + clientY + pools;
        assertEquals("he must come back at his ship on X: " + observed,
                shipPose[0], clientX, POSE_EPSILON);
        assertEquals("he must come back at his ship on Y: " + observed,
                shipPose[1], clientY, POSE_EPSILON);
        assertEquals("he must come back at his ship on Z: " + observed,
                shipPose[2], clientZ, POSE_EPSILON);

        // And that position has to realize a coordinate inside his ship's ledgered cell. This is the
        // check "he is in a slot dimension" cannot make: a slot world is an ordinary world, so a
        // pilot dumped at ordinary block height inside the right slot would still read as "not the
        // overworld" - but mapped back through the pose band he lands in a neighbouring sector.
        GalacticCoord cell = GalacticCoord.fromCellKey(arrangedCellKey);
        assertNotNull("the ledger reported an unreadable cell key: " + arrangedCellKey, cell);
        GalacticCoord realized = CellWorldMapper.coordOfPose(cell, clientX, clientY, clientZ);
        assertTrue("the client's position must realize a coordinate in his ship's own cell "
                + arrangedCellKey + ", but it maps to " + realized.cellKey() + ": " + observed,
                realized.sameCell(cell));

        // CONTROL LEG (load-bearing): the restored chain still FLIES the ship. Being put back in
        // the chair is only half the relog promise - a restored seat with a dead key is exactly
        // the play-reported shape of a broken control chain, and it would read green on every
        // assertion above. Same real-key stimulus, same client-observed altitude as the pre-restart
        // leg, so a red here is attributable to the restart and nothing else.
        double postY0 = clientPlayerY();
        climbWith(Keyboard.KEY_R, postY0,
                "after the restart, held input must MOVE THE SHIP - a restored seat with a dead key"
                        + " is a broken control chain");
    }

    /**
     * A crew member restored onto his deck must not be DRAGGED along it: with no input at all, his
     * own position in the ship's frame has to stay put.
     *
     * <p><b>Frame, and why it is the whole measurement.</b> The subject is the body's point in the
     * SHIP's frame, so the ship's own motion is already divided out and a carried body reads as
     * still. A world-frame reading cannot make this statement at all - it counts the ship carrying
     * the crew member as the crew member moving.</p>
     *
     * <p><b>Why the LIVE body point and not the held one.</b> The per-tick record carries both: the
     * body's own coordinates ({@code B}) and the point the resolver has COMMITTED for it ({@code H}).
     * A body that something else is pulling has a perfectly still committed point, so the existing
     * planet-side pins - which read the committed point - pass while the player slides. {@code B} is
     * the only field in this repo that answers "did the body move along the deck".</p>
     *
     * <p><b>The window must be witnessed.</b> "No travel" and "no ticks recorded" read identically,
     * so the number of resolved ticks inside the window is asserted before the travel is. Without
     * that, a client whose resolver never ran would produce the cleanest possible pass.</p>
     */
    protected void requireHeIsNotDraggedAlongHisDeck(int dim) throws Exception {
        // DIAGNOSTIC FIRST, and it covers the login itself. The client JVM is new on this boot, so its
        // record starts empty and everything in it belongs to this session: the whole restore, the
        // placement jump included. A drag reported "right after entering the game" is a TRANSIENT, and
        // a window opened once the placement has settled is exactly the shape that cannot see one.
        String sinceConnect = clientTickHistory();
        String transient_ = "resolved=" + resolvedSince(sinceConnect, -1L)
                + " worstStep=" + worstStep(sinceConnect, -1L)
                + " creepBandTotal=" + creepBandTotal(sinceConnect, -1L)
                + " " + writerSummary(sinceConnect, -1L);

        // WHERE the login put him, and HOW the hold that is supposed to seat a STANDING crew member
        // ended. The placement carries its own y; `deck_hold_ended` carries `why` — `resolving`
        // means the hold concluded with the capture on its own ship, `expired` means the window ran
        // out and the body was left wherever the login chose, which for a settled ship is the
        // SHIP'S OWN world position rather than the deck point he stood on. The whole scenario's
        // records, because a login produces exactly one of each and a mark would only narrow what
        // is already unambiguous.
        String placement = events().since(0, "login_restored");
        String holdEnded = events().since(0, "deck_hold_login")
                + " | ended: " + events().since(0, "deck_hold_ended")
                + " | pins: " + events().since(0, "deck_hold_pin");

        // THE DRIVER, not the condition. The previous cut of this pin measured a body on a deck that
        // was standing perfectly still - every field came back exactly 0.0, carry included - and a
        // held body on a motionless deck cannot drift no matter what is wrong with the hold. That
        // defect's own retraction says it outright: the relog was never the variable, the SHIP'S MOTION
        // was. So the deck is put in motion for the window, and the ship's own displacement is
        // WITNESSED afterwards: without that witness a still ship reads as a clean pass again.
        commandWindowCruise(dim);
        double[] deckBefore = awaitShipPose(dim);
        assertNotNull("the ship must be live before the window opens", deckBefore);
        long fromTick = lastClientTick();
        // The mark BEFORE the window, on the CLIENT's log, because that is where this body's
        // capture lives. What used to stand here was a difference of two reads of a production
        // counter - and the reader answers -1 when the field cannot be read at all, so an
        // unreadable instrument produced -1 - -1 == 0 and every zero-release pin below went green
        // on it. A release is an EVENT with production's own reason string on it, and an empty log
        // is only an answer once the recorder says it was listening.
        long idleReleaseMark = clientEvents().mark();
        // WINDOW: deckBefore / deckAfter and the tick history from fromTick bound it, and every
        // assertion below is over what happened between the two reads.
        bot().waitTicks(OBSERVE_TICKS);
        String idleReleases = clientReleases(idleReleaseMark, "an idle window with no input at all");
        long dropsInIdle = guardReleases(idleReleases);
        double[] deckAfter = awaitShipPose(dim);
        assertNotNull("the ship must still be live after the window", deckAfter);
        double deckMoved = distance(deckBefore, deckAfter);
        requireArranged("the deck must MOVE during the observation window, or a body that "
                        + "stayed put proves nothing about the hold - a motionless deck cannot drag "
                        + "anyone (the ship moved " + deckMoved + " blocks, need > " + MIN_DECK_MOVE
                        + "). Transient window since connect: " + transient_,
                deckMoved > MIN_DECK_MOVE);
        requireArranged("this window must observe a SETTLING deck, not a ship at its cruise "
                        + "cap - the reported symptom is a ship that is almost stationary, and at cap "
                        + "speed the hold has a separate suspected defect of its own (the ship moved "
                        + deckMoved + " blocks in " + OBSERVE_TICKS + " ticks = "
                        + (deckMoved / OBSERVE_TICKS) + " per tick, ceiling " + MAX_DECK_RATE + "). "
                        + dropReasons(),
                deckMoved / OBSERVE_TICKS < MAX_DECK_RATE);

        String history = clientTickHistory();
        assertTrue("the client's per-tick ship-frame record must exist, or this pin measures nothing "
                + "at all (history=" + history + ")", history != null && history.length() > 0);
        int resolved = resolvedSince(history, fromTick);
        assertTrue("the client must have resolved the body through the observation window, or a clean "
                        + "result here is a statement about the instrument rather than the body "
                        + "(resolved " + resolved + " ticks after tick " + fromTick + ", need >= "
                        + MIN_RESOLVED + "). A silent record has three readings and only one of them "
                        + "is 'no drift': " + dropReasons() + "\n" + history,
                resolved >= MIN_RESOLVED);

        double bodyTravel = bodyPointTravel(history, fromTick);
        double heldTravel = heldPointTravel(history, fromTick);
        String writers = writerSummary(history, fromTick);

        // DIAGNOSTIC, never a pin: the maintainer's own cure - sitting down and standing up again
        // re-installs the capture and the drift stops. It names WHERE the bad state comes from (an
        // install, not the steady-state hold), which is what a fix has to act on; but "the cure
        // works" is not a promise production makes, so it rides in the failure text only.
        // THE OTHER HALF OF THE REPORT, and the half an idle window cannot reach: the drift rides ON
        // TOP OF WALKING. The stimulus is a real key; the observation is the body's own ship-frame
        // point after the key is RELEASED - a body that keeps going once the input stops is carrying
        // something the walk did not give it. Measured under the RESTORED capture first.
        double[] restoredWalk = walkThenIdle(dim);
        String restoredWalkLines = lastWalkLines;
        String restoredWalkMovers = lastWalkMovers;
        // TWICE under the SAME capture. The first pair came back four times weaker under the restored
        // capture than under the re-installed one, and those two legs differed in ORDER as well as in
        // provenance. A second walk through the unchanged capture separates them: still weak means the
        // restored capture is the variable, back to normal means the first walk after a login is.
        double[] restoredWalkAgain = walkThenIdle(dim);
        String restoredWalkAgainLines = lastWalkLines;

        String cure = measureAfterReCapture(dim);

        // The same stimulus again, through a capture just re-installed by hand: the maintainer's own
        // cure turned into an in-run CONTROL - same body, same deck, same key, only the capture's
        // provenance differs. Diagnostic, not a pin.
        double[] freshWalk = walkThenIdle(dim);
        String freshWalkLines = lastWalkLines;
        String freshWalkMovers = lastWalkMovers;
        double[] freshWalkAgain = walkThenIdle(dim);

        System.out.println("[space-drag] deckMoved=" + deckMoved + " bodyTravel=" + bodyTravel
                + " heldTravel=" + heldTravel + " resolved=" + resolved
                + "\n[space-drag] " + writers
                + "\n[space-drag] since connect (transient): " + transient_
                + "\n[space-drag] walk 1 under the RESTORED capture: " + describeWalk(restoredWalk)
                + "\n[space-drag] walk 2 under the RESTORED capture: " + describeWalk(restoredWalkAgain)
                + "\n[space-drag] after a re-capture: " + cure
                + "\n[space-drag] walk 1 under a FRESH capture:     " + describeWalk(freshWalk)
                + "\n[space-drag] walk 2 under a FRESH capture:     " + describeWalk(freshWalkAgain));

        // The walking half, asserted with its own witnesses: he must really have walked, the deck must
        // really have moved, and the record must really cover the window - otherwise a clean result is
        // about the arrangement. The subject is what remains AFTER the key is released.
        // Every number is measured and printed BEFORE any witness is allowed to fire: a witness that
        // aborts mid-experiment throws away the comparison that makes the result legible, which is
        // exactly what happened on the first plain-relog run.
        // Read AGAIN, after the walks. The first read is taken a few ticks into the login and a hold
        // lives for two hundred: asking once at the top says what the hold had done by then, which
        // is not the same question as how it ENDED. Both are printed, in that order, so a reader can
        // see the hold still running at the first read and finished by the second.
        String holdAfter = events().since(0, "deck_hold_login")
                + " | ended: " + events().since(0, "deck_hold_ended")
                + " | pins: " + events().since(0, "deck_hold_pin");

        String walkTable = "\n  restored 1: " + describeWalk(restoredWalk)
                + "\n  restored 2: " + describeWalk(restoredWalkAgain)
                + "\n  fresh 1:    " + describeWalk(freshWalk)
                + "\n  fresh 2:    " + describeWalk(freshWalkAgain)
                // The per-tick evidence for the comparison the table asks the reader to make. The
                // totals say a restored walk is weak; only the lines say WHEN it was weak — a body
                // that fell for its first ticks and one that never left the ground produce the same
                // summary, and the deck flag and obstacle count per tick tell them apart. The
                // FRESH leg rides along as the control, because "restored looks odd" means nothing
                // without the shape a healthy walk makes on this same deck.
                + "\n  the login's own placement: " + placement
                + "\n  the hold, a few ticks in:  " + holdEnded
                + "\n  the hold, after the walks: " + holdAfter
                + "\n  restored 1, tick by tick:" + restoredWalkLines + restoredWalkMovers
                + "\n  restored 2, tick by tick:" + restoredWalkAgainLines
                + "\n  fresh 1, tick by tick (the control):" + freshWalkLines + freshWalkMovers;
        requireArranged("the walk must actually move him along the deck, or the pin below "
                        + "measures a body that never walked. A walk that is weak ONLY under the "
                        + "restored capture is itself the finding rather than an arrangement fault - "
                        + "read the four together:" + walkTable,
                restoredWalk[0] > MIN_WALK_TRAVEL);
        double walkWindowTicks = 6 + 2 + OBSERVE_TICKS;
        requireArranged("the deck must move during the walk window, in the settling band ("
                        + (restoredWalk[3] / walkWindowTicks) + " blocks per tick, band "
                        + (MIN_DECK_MOVE / walkWindowTicks) + ".." + MAX_DECK_RATE + "): "
                        + describeWalk(restoredWalk) + " " + dropReasons(),
                restoredWalk[3] > MIN_DECK_MOVE
                        && restoredWalk[3] / walkWindowTicks < MAX_DECK_RATE);
        requireArranged("the record must cover the window AFTER the key was released, or the "
                        + "clean result is a frozen record rather than a still body - the tell is a "
                        + "travel figure identical to the previous leg's to the last digit: "
                        + describeWalk(restoredWalk) + " " + dropReasons(),
                restoredWalk[2] >= MIN_RESOLVED);
        assertTrue("a crew member restored onto his deck must not keep travelling once he stops "
                        + "walking: his ship-frame point moved " + restoredWalk[1] + " blocks over "
                        + (int) restoredWalk[2] + " ticks AFTER the key was released (bar "
                        + DRIFT_TOLERANCE + "), of which " + restoredWalk[4] + " arrived as per-tick "
                        + "creep. The same stimulus through a freshly re-installed capture: "
                        + describeWalk(freshWalk) + " (diagnostic control, not a promise)"
                        + "\n  walk under the restored capture: " + describeWalk(restoredWalk),
                restoredWalk[1] < DRIFT_TOLERANCE);

        assertTrue("a crew member restored onto his deck must not be dragged along it: with no input "
                        + "his own ship-frame point travelled " + bodyTravel + " blocks over "
                        + resolved + " resolved ticks (bar " + DRIFT_TOLERANCE + ") while his deck "
                        + "moved " + deckMoved + " blocks, and the point the resolver commits for him "
                        + "moved " + heldTravel + " - a still committed point under a travelling body "
                        + "is the signature of another writer moving him."
                        + "\n  writers: " + writers
                        + "\n  since connect (the login transient): " + transient_
                        + "\n  after a re-capture (diagnostic, not a promise): " + cure
                        + "\n" + history,
                bodyTravel < DRIFT_TOLERANCE);

        // THE MECHANISM ITSELF, asserted rather than inferred from the drift it produces.
        //
        // This fails if production breaks the contract that the external-move guard releases a deck
        // capture only on movement the ship-frame resolver did NOT itself produce. Nothing in either
        // window teleports the body: it stands still, or it walks a walk this class swept and
        // committed. Every release therefore hands a body the resolver was holding back to vanilla
        // and to the physics mod for the ticks until the re-capture, which is the drag as the player
        // experiences it - and it is why the maintainer's cure (a re-seat) works only until the next
        // release.
        //
        // Kept separate from the drift pins above because the two can disagree in BOTH directions: a
        // release whose vanilla ticks happen to net out leaves the drift clean, and a drift with no
        // release at all would name a different writer entirely. The count is the discriminator.
        assertEquals("the external-move guard must not release a crew member the resolver is itself "
                        + "moving: it released him " + dropsInIdle + " time(s) across " + resolved
                        + " resolved ticks with NO input at all, and " + (int) restoredWalk[10]
                        + " time(s) during a " + (int) restoredWalk[6] + "-tick walk it swept and "
                        + "committed. Each release below carries production's own reason for it, in "
                        + "order: " + idleReleases + " " + dropReasons() + walkTable,
                0L, dropsInIdle + (long) restoredWalk[10]);
    }

    /**
     * Walk him along the deck with a real key, release it, and watch what he does afterwards. Returns
     * {@code [walkTravel, idleTravelAfterRelease, resolvedTicksInTheIdleWindow, deckMoved,
     * creepBandTotal]}, all in the SHIP's frame except {@code deckMoved}.
     *
     * <p>The two ticks between the release and the idle window are deliberate and were learned on the
     * planet side: the subject is an inherited VELOCITY, not an inherited INPUT, and a window opened on
     * the release tick cannot tell a key that is still held from a body that is still moving.</p>
     */
    /**
     * The WALK window's own per-tick lines from the most recent {@link #walkThenIdle}, so a caller
     * comparing several walks can keep each one's evidence rather than only its totals.
     */
    protected String lastWalkLines = "";

    /**
     * WHO ELSE touched the body during that same walk window: every world-frame move request the
     * suppression hook caught, and every re-seat pass that moved something.
     *
     * <p>The tick lines say the body lags the point the resolver committed; they cannot say who put
     * it there. These two records name the two candidates production actually has — a world-frame
     * mover pushing a resolved body, and the pose pass re-seating it — and an empty pair is a
     * reading of its own: then the lag comes from neither and the resolver's own commit is the
     * place to look.</p>
     */
    protected String lastWalkMovers = "";

    protected double[] walkThenIdle(int dim) throws Exception {
        commandWindowCruise(dim);
        double[] deckBefore = awaitShipPose(dim);
        long walkFrom = lastClientTick();
        // Two marks, one per window, on the CLIENT's own log - see the idle window above for why a
        // difference of two counter reads could not fail.
        long walkReleaseMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_W);
        // STIMULUS: six ticks of W, not twelve: the fixture's deck is small, and a walk long enough
        // to carry him off its edge ends the capture - which reads as a silent record rather than as
        // a clean body.
        bot().waitTicks(6);
        bot().releaseKey(Keyboard.KEY_W);
        String walkHistory = clientTickHistory();
        lastWalkLines = linesAfter(walkHistory, walkFrom);
        lastWalkMovers = "\n      world-frame movers: "
                + clientEvents().since(walkReleaseMark, "ship_frame_world_move")
                + "\n      re-seat passes: "
                + clientEvents().since(walkReleaseMark, "deck_reseat_pass");
        long dropsInWalk = guardReleases(clientReleases(walkReleaseMark, "a swept and committed walk"));
        // EXPERIMENT: the idle window opens two client ticks after the release, so the walk's last
        // applied input tick is outside it and "idle" means no input at all.
        bot().waitTicks(2);
        long idleFrom = lastClientTick();
        long idleReleaseMark = clientEvents().mark();
        // WINDOW: from idleFrom to the history read below, with the deck's pose on either side.
        bot().waitTicks(OBSERVE_TICKS);
        String idleHistory = clientTickHistory();
        long dropsInIdle = guardReleases(clientReleases(idleReleaseMark,
                "the idle window after the key was released"));
        double[] deckAfter = awaitShipPose(dim);
        // THE INSTRUMENT-OR-SUBJECT SPLIT, and it has to be measured, not argued. A restored body that
        // does not move under the key has two readings: the hold cancels the motion, or the key never
        // arrived. They are told apart by whether the resolver SAW the input at all - and a reconnect
        // is exactly the moment a harness could lose the client's input focus, with the mount/dismount
        // of the "cure" quietly restoring it. Without this pair of numbers the finding would rest on
        // an assumption about the harness.
        return new double[]{
                bodyPointTravel(walkHistory, walkFrom),
                bodyPointTravel(idleHistory, idleFrom),
                resolvedSince(idleHistory, idleFrom),
                deckBefore == null || deckAfter == null ? -1.0 : distance(deckBefore, deckAfter),
                creepBandTotal(idleHistory, idleFrom),
                inputTicksIn(walkHistory, walkFrom),
                resolvedSince(walkHistory, walkFrom),
                // DECK CONTACT during the walk. The walk factor is chosen by it: a body the resolver
                // does not consider to be standing on the deck is moved with AIR control and damped
                // with air friction instead of the block's, which is weak input and slow decay - the
                // two halves of "he barely walks" and "he slides". This is the field that says which.
                offDeckTicksIn(walkHistory, walkFrom),
                sweepPinnedTicksIn(walkHistory, walkFrom),
                maxObstaclesIn(walkHistory, walkFrom),
                // WHO OWNED THE BODY. Every tick between an external-move release and the re-capture
                // belongs to vanilla and to the physics mod rather than to the ship-frame resolver,
                // and the reporter's own session log is 174 such releases - all of them on the CLIENT,
                // 148 of them on a deck tilted 1.1 deg or less, 150 of them with deck support under
                // the body. A release count is therefore not a diagnostic detail here: it is the
                // mechanism the report describes, measured directly.
                dropsInWalk,
                dropsInIdle};
    }

    /**
     * Ticks whose sweep collided HORIZONTALLY: the motion the input produced was zeroed against
     * geometry rather than swallowed by another writer. This is the discriminator between the two
     * remaining candidates for a pinned body - a zero here alongside a zero travel indicts whatever
     * re-applies the committed point, not the collision box.
     */
    protected int sweepPinnedTicksIn(String history, long fromTick) {
        int n = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick
                    && ("true".equals(Events.text(record, "collidedX"))
                            || "true".equals(Events.text(record, "collidedZ")))) {
                n++;
            }
        }
        return n;
    }

    /**
     * The per-tick lines of the window that starts after {@code fromTick}, oldest first.
     *
     * <p>The summary fields answer "how much" and cannot answer "from which tick onwards": a walk
     * that is weak because the body spent its first ticks falling and a walk that is weak because it
     * never left the ground read the same in {@code walkTravel} and {@code offDeckTicksInWalk}. The
     * lines carry the deck flag, the obstacle count, the committed point and the live one per tick,
     * so the shape is readable rather than inferred.</p>
     *
     * <p>An empty window says so in words: no resolved tick at all is a different reading from a
     * body that resolved and did not move, and the two are otherwise both printed as zeros.</p>
     */
    protected String linesAfter(String history, long fromTick) {
        StringBuilder out = new StringBuilder();
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick) {
                // The record, not the packed `line` it also carries: this is a failure message, and
                // the record shows every field the readers above judged on.
                out.append("\n      ").append(record);
            }
        }
        return out.length() == 0 ? " (no resolved tick at all in this window)" : out.toString();
    }

    /** The most obstacles the sweep saw in the window - a body standing inside geometry sees more. */
    protected int maxObstaclesIn(String history, long fromTick) {
        int worst = -1;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick) {
                worst = Math.max(worst, (int) Events.number(record, "obstacles"));
            }
        }
        return worst;
    }

    /** How many ticks of the window the resolver did NOT consider the body to be on the deck. */
    protected int offDeckTicksIn(String history, long fromTick) {
        int n = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick && "false".equals(Events.text(record, "onDeck"))) {
                n++;
            }
        }
        return n;
    }

    /** How many ticks of the window carried a nonzero walk input, as the resolver saw it. */
    protected int inputTicksIn(String history, long fromTick) {
        int n = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick
                    && (Events.number(record, "inStrafe") != 0.0
                            || Events.number(record, "inForward") != 0.0)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Put THIS scenario's deck in motion for the window that follows, by commanding the ship's own
     * cruise setpoint - the state production flies an unmanned ship on.
     *
     * <p><b>Why not an input, and why not a cruise setpoint.</b> These windows need a deck that moves
     * while NOBODY is at the controls. A pilot INPUT cannot supply that: a riderless seat dummy
     * clears its computer's pilot input every tick, on purpose, and only a JVM-wide static was immune
     * - which is exactly why this family used one, and what it cost was that every other ship on the
     * server flew too. A retained cruise SETPOINT is what outlives a pilot planet-side, and it was
     * tried here first; measured twice on a settled ship in a CELL, at 4 and at 12 blocks/second, the
     * deck travelled 0.870 and 0.862 blocks in the same window. Tripling the command changed the
     * result by one percent, so the cruise is not being realized here at all and those numbers are
     * drift. That is a finding about ships in cells, recorded in the ledger, and it is not this
     * family's subject.
     *
     * <p>So the deck is driven through the computer's own PROBE command channel: per-tile, addressed
     * at this ship's computer, outranking the pilot channel and cleared by nothing. What it replaces
     * was no more production-shaped than it is - a server-wide static input - and the subject here is
     * a BODY on a moving deck, never how the deck came to move.</p>
     *
     * <p>Commanded fresh before each window rather than once at the start: the channel is live state
     * and does not survive the server restart one leg performs.</p>
     */
    protected void commandWindowCruise(int dim) throws Exception {
        assertNotNull("commandWindowCruise before the arrangement located the ship's computer",
                arrangedAfcPos);
        String driven = exec("artest vs force-vel-at " + dim + " " + arrangedAfcPos
                + " 0 " + WINDOW_SPEED_BLOCKS_PER_SECOND + " 0");
        requireArranged("the drive must reach THIS ship's own flight computer, or the window "
                + "below observes a motionless deck and cannot fail: " + driven,
                Reply.of(driven).bool("afcResolved"));
        // EXPERIMENT: the pulse is the dose — long enough to set the ship moving, short enough that
        // the window the caller opens next watches the hold SETTLING it (see THROTTLE_PULSE_TICKS).
        bot().waitTicks(THROTTLE_PULSE_TICKS);
    }

    /**
     * Why the record might be silent, as the three readings an absent line actually has: the resolver
     * never ran (resolved/declined counters), it ran and dropped the body (external-move drops and the
     * last drop's reason), or it is still holding him and simply had nothing to say. Without this a
     * silent record reads as "he did not move", which is the one reading it must never be able to fake.
     */
    protected String dropReasons() throws Exception {
        // "The resolver never ran" is asked of its own RECORDS, not of `resolvedTicks`. That counter
        // was cumulative and JVM-global: on a shared client it had already been advanced by whatever
        // ran before this scenario, so a non-zero could not mean "it ran for HIM". Each capture
        // record names the body and the ship, which is the reading this message was after.
        // And the releases are asked of their own records for the same reason: `externalMoveDrops`
        // was a lifetime count over every body, and `lastDropReason` was the reason of whichever
        // drop this JVM made last — on a shared client, routinely another scenario's. Each release
        // record names the body, the mode and the gate's whole reason.
        return "CLIENT[deckCommits=" + clientEvents().since(0, "deck_entered")
                + " deckReleases=" + clientEvents().since(0, "deck_released")
                // And the world-frame movers as their own records, for the same reason: the counter
                // that stood here said how many such requests this JVM had ever suppressed, not
                // whether anything pushed HIM.
                + " worldMoves=" + clientEvents().since(0, "ship_frame_world_move") + "]";
    }

    protected static String describeWalk(double[] w) {
        return "walkTravel=" + w[0] + " idleAfterRelease=" + w[1] + " resolvedIdle=" + (int) w[2]
                + " deckMoved=" + w[3] + " creepBandTotal=" + w[4]
                + " inputTicksSeenByResolver=" + (int) w[5] + "/" + (int) w[6]
                + " offDeckTicksInWalk=" + (int) w[7]
                + " sweepPinnedTicks=" + (int) w[8] + " maxObstacles=" + (int) w[9]
                + " guardReleases=" + (int) w[10] + "walk/" + (int) w[11] + "idle";
    }

    /**
     * Every deck capture the CLIENT ended inside a window, in order, each with production's own
     * reason for ending it - and the proof that anybody was recording at all.
     *
     * <p>{@link Events#assertInstrumentRan} is not decoration here: every pin built on this is a
     * ZERO, and "the guard released him zero times" and "the observation point never wove" are the
     * same empty reply. The counter these calls replace could not even say that much - its reader
     * answers {@code -1} for an unreadable field and every count was a DIFFERENCE of two reads, so
     * an instrument that was never there produced a clean {@code 0}.</p>
     */
    protected String clientReleases(long mark, String whatFor) throws Exception {
        String releases = clientEvents().since(mark, "deck_released");
        Events.assertInstrumentRan(releases, "deck_capture_events",
                "the client's deck capture was, or was not, cycled during " + whatFor);
        return releases;
    }

    /**
     * How many of those releases were the EXTERNAL-MOVE guard's - the mechanism these windows are
     * about. A GEOMETRIC release (walked off the deck, no hull contact) is legitimate and is
     * deliberately not counted here; it shows in the reply the caller prints.
     */
    protected static long guardReleases(String releases) {
        return Events.countRecordsWithField(releases, "reason");
    }

    /**
     * Re-install the capture the way the maintainer did - sit in the ship's seat, stand up again -
     * and measure the same drift over the same window. Diagnostic only: any failure to find a seat
     * degrades to text, because this must never be the reason the subject's pin goes red.
     */
    protected String measureAfterReCapture(int dim) {
        try {
            // On THIS pilot's own ship. The bare form mounts the first pilot seat in the world's
            // loaded-tile list, and this method does not merely observe — it seats the bot — so an
            // unaddressed mount would put him on a neighbour's craft and then measure that.
            SeatMount seat = SeatMount.onShip(this::exec, dim,
                    ShipIdentity.awaitPhysicsIdOf(this::exec, events(), dim, arrangedShipId, 100));
            if (!seat.seatFound) {
                return "<no seat to re-capture through: " + seat.raw() + ">";
            }
            long seatMark = clientEvents().mark();
            String mount = exec("artest player mount-entity " + seat.requireDummyId());
            // absence is the answer, and here it is the WHOLE point of the method: the verb writes
            // `{"error":"entity not found"}` with no `mounted` when the seat dummy has gone, and a
            // refusing read of it throws an AssertionError — which is an Error, so the catch below
            // does not see it and this diagnostic becomes the reason the subject's pin goes red.
            if (!Reply.of(mount).boolOr("mounted", false)) {
                return "<could not re-seat: " + mount + ">";
            }
            try {
                ClientEvents.awaitMounted(clientEvents(), seatMark,
                        "the re-capture diagnostic's seating, replicated to the client", 100);
            } catch (AssertionError notSeated) {
                return "<the client never followed the re-seat: " + notSeated.getMessage() + ">";
            }
            long standMark = clientEvents().mark();
            String dismount = exec("artest player dismount");
            if (!Reply.of(dismount).ok()) {
                return "<could not stand up again: " + dismount + ">";
            }
            try {
                awaitClientEvent(standMark, "dismount",
                        "the re-capture diagnostic's standing up, replicated to the client", 100);
            } catch (AssertionError notStood) {
                return "<the client never followed the stand-up: " + notStood.getMessage() + ">";
            }
            // Under MOTION, like the subject window - a re-capture measured on a motionless deck
            // would come back all zeros and could not be compared with anything.
            commandWindowCruise(dim);
            double[] deckBefore = awaitShipPose(dim);
            long fromTick = lastClientTick();
            // WINDOW: the same one the subject measures, bounded by the two deck reads.
            bot().waitTicks(OBSERVE_TICKS);
            double[] deckAfter = awaitShipPose(dim);
            String history = clientTickHistory();
            String moved = deckBefore == null || deckAfter == null
                    ? "deckMoved=<ship not live>" : "deckMoved=" + distance(deckBefore, deckAfter);
            return moved
                    + " bodyTravel=" + bodyPointTravel(history, fromTick)
                    + " heldTravel=" + heldPointTravel(history, fromTick)
                    + " resolved=" + resolvedSince(history, fromTick)
                    + " " + writerSummary(history, fromTick);
        } catch (Exception unavailable) {
            return "<re-capture diagnostic unavailable: " + unavailable + ">";
        }
    }

    // --- arrangement -------------------------------------------------------------------------------

    /**
     * Boot 1, shared by both legs: bring the production subsystem up over the seeded world root, fly
     * ONE piloted ship into space through the real entry on-ramp, walk the real client into the cell
     * production put it in and sit him on its seat. Records the ship id and the cell key production
     * chose, which is everything either leg needs afterwards - the slot dimension deliberately is
     * not, because slot ids are re-minted every boot and must be re-observed rather than remembered.
     *
     * <p>The production subsystem is deliberately left to own the whole stack. There is a probe verb
     * that installs its own entry stack for the server-tier tests, and it must NOT be used here: it
     * replaces the shared manager, ledger and controller wholesale, so the ledger this test's entry
     * would write is a throwaway that no save hook persists - the restart would then find nothing and
     * the test would be red for an arrangement reason. It also mints extra slot dimensions outside
     * the production pool, which is the two-pool conflict the subsystem's harness standdown exists to
     * prevent in the first place. That choice is why the entry is watched through the subsystem's own
     * status and ledger rather than through the entry probe's status verb: the latter reports on that
     * probe-local stack and answers nothing at all when it was never installed.</p>
     *
     * <p>Every step is witnessed as it happens. An arrangement that half-succeeded quietly is the
     * failure mode that makes a two-boot test unattributable: after the restart there is no way left
     * to tell "the restore lost him" from "he was never aboard in the first place".</p>
     */
    protected int seatThePilotAboardHisShip() throws Exception {
        int slotDim = flyOneShipIntoItsCell();

        // The cell holds exactly this one ship, so "the ship nearest anywhere" is unambiguous.
        double[] shipPose = awaitShipPose(slotDim);
        assertNotNull("the settled ship is not live in its own slot dimension " + slotDim, shipPose);

        // Locate the pilot seat inside the re-assembled ship: the seat's SUBSPACE position (what the
        // seat's mount is bound to) and the seat's WORLD position (where the client has to stand).
        // Polled, not sampled once. The seat is searched from the ship's live pose, and a ship that
        // has just been re-assembled in its cell can still be settling when the ledger already calls
        // it SETTLED - so both the anchor and the shipyard's queryability lag by a few ticks. A single
        // shot here fails intermittently, and it fails in the ARRANGEMENT, which is the most expensive
        // kind of red: it looks like the subject broke.
        // Searched inside the ship the ledger NAMES, not around the pose it happens to be at. The
        // crossing re-assembles the hull, so the physics id is a new one on this side and is
        // translated from the durable name the ledger kept. What is still awaited is the shipyard
        // becoming queryable — a fact about time, not about which craft answers.
        String arrivedShipId = ShipIdentity.awaitPhysicsIdOf(this::exec, events(), slotDim, arrangedShipId,
                300);
        // MEASURED, and the loop stays on what the measurement does NOT say. What it could still be
        // waiting for was narrowed first: `awaitPhysicsIdOf` above has already established that the
        // queryable registry carries this ship, `shipyardBoundsOf` builds the box straight off its
        // chunk claim, and `pilotSeatInYard` force-loads those chunks itself before scanning — so
        // chunk streaming is not the lag. What is left is the BLOCKS: a crossing re-assembles the
        // hull into the subspace, and a claim can exist before its contents do.
        //
        // Measured 2026-09-13, both scenarios of this class that reach here, one unloaded run:
        // ONE attempt, no refusal, and therefore zero ticks spent — so nothing below can be relying
        // on time this loop buys. That is two samples against a comment claiming an intermittent
        // single-shot failure under parallel-fork load, which two unloaded samples cannot refute, so
        // the retry stays: it costs nothing when it is not needed.
        //
        // What the retry may no longer do is refuse in one word. `seatFound:false` covers three
        // different arrangement faults and the probe now names the box it searched, so they can be
        // told apart: `yard:null` is a ship with no chunk claim at all, a yard box with no seat in
        // it is a claim whose blocks have not arrived (or a craft that genuinely carries no pilot
        // seat), and the attempt count says whether the wait was ever real.
        // STAYS A LOOP, and the link does not answer: `ledger_settled` names the ship and its CELL,
        // not whether the hull's BLOCKS have landed in the subspace — which is what a seat scan
        // needs and what a claim can precede. What this cannot see: a seat that was findable and
        // stopped being so between two attempts.
        PilotSeat seat = null;
        PilotSeat firstRefusal = null;
        int attempts = 0;
        for (int attempt = 0; attempt < 30; attempt++) {
            seat = PilotSeat.byId(this::exec, slotDim, arrivedShipId);
            attempts++;
            if (seat.found) {
                break;
            }
            if (firstRefusal == null) {
                firstRefusal = seat;
            }
            bot().waitTicks(10);
        }
        // The reader names the searched yard in its own refusal — which fault this is, not merely
        // that there is one — so what this message adds is the part it cannot know: how many
        // attempts were spent, and what the FIRST of them said.
        seat.requireFound("the pilot seat must survive the crossing and be locatable in the settled"
                + " ship - without a seat there is nothing to be restored into. " + attempts
                + " attempt(s) in dim " + slotDim + "; first refusal: " + firstRefusal);
        int seatX = seat.seatX;
        int seatY = seat.seatY;
        int seatZ = seat.seatZ;

        long enterMark = clientEvents().mark();
        String enter = exec("artest space enter " + BOT + " " + slotDim
                + " " + seat.shipWorldX
                + " " + seat.shipWorldY
                + " " + seat.shipWorldZ);
        assertTrue("the client must be transferred into the ship's cell: " + enter,
                readBool(enter, "ok"));
        // WAS `waitTicks(20)` and a read. The client PUBLISHES the dimension it changed to, and
        // a budget between the order and the read is an assertion about how fast this box
        // replicates rather than about the transfer.
        ClientEvents.awaitDim(clientEvents(), enterMark, slotDim,
                "everything below is arranged on the client's side of a dimension boundary",
                CLIENT_DIM_LINK_BUDGET_TICKS);

        String mountAt = exec("artest vs seat-mount-at " + slotDim
                + " " + seatX + " " + seatY + " " + seatZ);
        assertTrue("the pilot seat's mount must exist: " + mountAt, readBool(mountAt, "ok"));
        Events events = events();
        // Before the mount, so the two links it produces cannot be missed between two reads.
        long seatMark = events.mark();
        // The CLIENT's own mark for the same mount, beside the server's: the seating this scenario
        // depends on is one the CLIENT has to perform, and that is a record rather than a delay.
        long seatClientMark = clientEvents().mark();
        // `seat-mount-at` is a DIFFERENT verb from `seat-mount` — it puts an armour stand in the
        // seat — and has no reader yet, so its own field is read by name here.
        String mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
        assertTrue("the client must take the pilot seat: " + mount, readBool(mount, "mounted"));
        // Measured 2026-09-15 in a full-tier gate: these ten ticks were not enough under four client
        // forks, and the scenario then reported `riding:false` — which reads as the seat refusing a
        // pilot the server had already seated. A replication lag, said as one.
        ClientEvents.awaitMounted(clientEvents(), seatClientMark,
                "the CLIENT must confirm it is seated BEFORE the restart, or 'seated afterwards' is"
                        + " not an observation about the restore at all", RESTORE_LINK_BUDGET_TICKS);

        assertTrue("the CLIENT must confirm it is seated BEFORE the restart, or 'seated afterwards' "
                + "is not an observation about the restore at all: " + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        // The SERVER's own view, taken here as well as just before the logout: these two samples
        // bracket the window in which the entity can drift back out of the cell, so a failure says
        // WHICH side of the mount lost him instead of merely that he was lost.
        PlayerPosition serverAfterMount = PlayerPosition.of(this::exec, BOT);
        assertEquals("the SERVER must agree the pilot is in the slot dimension right after he sits "
                        + "down - if it does not, the client and the server disagree from the very "
                        + "start and nothing downstream is measuring the restore: "
                        + serverAfterMount.raw(),
                slotDim, serverAfterMount.dim);

        // Sitting down aboard a ship in a cell is a CHAIN, and it is asserted as one: he takes the
        // seat, and the reconciler's next pass stamps the durable record. The order is production's
        // own - the record is DERIVED from where he is and what he is riding, so it cannot precede
        // the mount - and a red now names which of the two did not happen, where the poll it
        // replaces reported a tag that stayed false for either reason.
        events.assertChain(seatMark, "sitting down on a ship's seat in a cell must put him on the "
                        + "mount and then leave a durable aboard record - that record is the only "
                        + "thing that carries the pilot's ship across the restart",
                RESTORE_LINK_BUDGET_TICKS, "mount", "aboard_record_stamped");
        String stamped = events.since(seatMark, "aboard_record_stamped");
        String tag = exec("artest space aboard-tag " + BOT);
        assertTrue("sitting down must leave a durable aboard record - it is the only thing that "
                + "carries the pilot's ship across the restart: " + tag + " | stamps: " + stamped,
                Reply.of(tag).bool("tagged"));
        assertTrue("and that record must name the ship the entry minted, not some other one: " + tag
                + " (entered ship " + arrangedShipId + ")", arrangedShipId.equals(Reply.of(tag).text("shipId")));
        return slotDim;
    }

    /**
     * The half of the arrangement that has nothing to do with the pilot: bring the production
     * subsystem up over the seeded world root and fly ONE piloted ship into space through the real
     * entry on-ramp, leaving the client wherever it started. Records the ship id and the cell key
     * production chose and returns the slot dimension the ship settled in.
     *
     * <p>Shared so that the leg where nobody ever boards runs the SAME world, the same entry and the
     * same ledger as the legs where somebody does - which is what makes it a witness for their
     * oracles rather than a different experiment.</p>
     */
    protected int flyOneShipIntoItsCell() throws Exception {
        serverHarness = RealDedicatedServerHarness.startWith(root, false);

        SubsystemStatus status = SubsystemStatus.read(this::exec);
        assertTrue("the production space subsystem must be live on boot 1 (that is what the seeded "
                + "config opt-in is for) - without it this test would silently assert nothing: "
                + status.raw(), status.registered);
        // CONTROL (witness sensitivity): no ship is ledgered before the climb, so a ledgered ship
        // afterwards is an observation about the entry and not about a pre-existing record.
        assertEquals("no ship may be ledgered before the flight: " + status.raw(),
                0, status.ledger);

        // Headless: nothing holds a freshly assembled or freshly crossed ship loaded between calls.

        startClient();
        bot().waitForWorld();

        // The entry sends the ship to the launch body's own address. Resolve it first: a launch dim
        // that resolves to no cell would send the ship to the configured home anchor instead, which
        // is a different arrangement than the one this test believes it is running.
        String launch = exec("artest space launch-cell " + LAUNCH_DIM);
        assertTrue("the launch dimension must resolve to a galactic address: " + launch,
                // `has` is the whole claim: it answers false for a field that is absent
                // AND for one whose value is JSON null, which is the shape the needle
                // `"cellKey":null` was hunting for one rendering of.
                Reply.of(launch).ok() && Reply.of(launch).has("cellKey"));

        // Build a PILOTED tier-2 ship on the ground and assemble it with the real assembler - which
        // is what mints the durable ship id the aboard record and the ledger are both keyed by.
        Events events = events();
        String coords = placeFixture(FixtureSite.openAir(LAUNCH_DIM, SRC_X, SRC_Z), VARIANT);
        // The mark BEFORE the assembler is told, so the ship this arrangement is about cannot be
        // missed between two counts and cannot be confused with one that already existed.
        long assemblyMark = events.mark();
        String assembled = exec("artest rocket assemble " + LAUNCH_DIM + " " + coords);
        assertTrue("a build carrying a flight computer must become a ship, not a rocket: " + assembled,
                (Reply.of(assembled).integer("rocketCount") == 0));
        assertTrue("the ship never assembled in the launch dimension",
                waitForLoadedShip(events, assemblyMark, LAUNCH_DIM) >= 1);

        // The ship BY NAME, from the assembler that minted the durable id — then the physics id it
        // maps to. The pad lookup this replaced was a proximity query on a world the whole family
        // shares, and a neighbour's craft answers it in the same shape.
        String durableShipId = ShipIdentity.nameFromAssembly(assembled);
        String srcVsId = ShipIdentity.physicsIdOf(this::exec, LAUNCH_DIM, durableShipId);
        ShipInfo srcInfo = ShipInfo.byId(this::exec, LAUNCH_DIM, srcVsId);
        int sx = (int) Math.round(srcInfo.x);
        int sy = (int) Math.round(srcInfo.y);
        int sz = (int) Math.round(srcInfo.z);

        // No throttle. Crossing an atmosphere does not ask who is at the controls - the climb past the
        // dimension's orbit ceiling is the whole trigger - and `VSUnpilotedEntryE2ETest` pins exactly
        // that with the ship's input explicitly CLEARED. The held throttle this used to publish was a
        // relic of a channel that also happened to be JVM-wide, and it cost this leg twice: it flew
        // every other ship on the server, and the all-zero input it left behind kept this ship's
        // computer in its PILOTED branch for the rest of the scenario.
        long entryMark = events.mark();
        String climb = exec("artest vs teleport-ship-by-id " + LAUNCH_DIM + " " + srcVsId
                + " " + sx + " " + ABOVE_CEILING_Y + " " + sz);
        assertTrue("the climb past the orbit ceiling failed: " + climb, Reply.of(climb).ok());
        exec("artest vs unpark-by-id " + LAUNCH_DIM + " " + srcVsId);

        // The flight computer's own tick now runs the entry: it crosses the ship into the launch
        // body's cell and, on completion, settles it in the ledger. Nothing here drives it.
        //
        // Awaited as the LINK the ledger count was standing in for. A count that never reaches one
        // is the same reading whether the ceiling check never fired, the gate refused, the crossing
        // stalled or the ledger write was lost; the record names the ship and the cell it settled
        // in, and a red prints every link the on-ramp DID commit on the way. The entry's remaining
        // links are deliberately not asserted as a chain here: this climb carries no crew, and
        // whether an empty crossing commits its re-seat link is a fact of a run.
        String settledRecord = events.await(entryMark, "ledger_settled",
                "the ship must enter space through the flight computer's own tick and be settled in"
                        + " the production ledger - the restore reads that very ledger, so an"
                        + " arrangement that never wrote it tests nothing",
                RESTORE_LINK_BUDGET_TICKS);
        SubsystemStatus ledgerStatus = SubsystemStatus.read(this::exec);
        assertTrue("the settled ship must be countable in the subsystem's own ledger, not only in"
                + " the record of the write: " + ledgerStatus.raw() + " | " + settledRecord,
                ledgerStatus.ledger >= 1);

        // Find the slot the entry bound the cell to. Slot ids are minted per boot, so they are read
        // rather than known: the one slot dimension whose settled ship's flight computer resolves is
        // the ship's own. An entry that ended up ABANDONED settles the ledger too but leaves the
        // ship at its paste site rather than at its cell pose, and then nothing resolves here - which
        // is the right way for that outcome to surface.
        String[] slot = awaitSettledShipSlot();
        assertNotNull("the ledger holds a ship, but no slot dimension owns up to it - the entry "
                + "settled without leaving a workable ship at its cell pose", slot);
        int slotDim = Integer.parseInt(slot[0]);
        arrangedShipId = slot[1];
        arrangedAfcPos = slot[2] + " " + slot[3] + " " + slot[4];

        // The reader refuses a ship the ledger has no entry for, and refuses to answer a cell for
        // one — which is what the has-check and the notNull below it each stood for.
        LedgerEntry ledgerEntry = LedgerEntry.forShip(this::exec, arrangedShipId)
                .requireFound("the entered ship must be in the production ledger");
        assertEquals("and it must be settled, not mid-jump: " + ledgerEntry.raw(),
                "SETTLED", ledgerEntry.state());
        arrangedCellKey = ledgerEntry.cellKey();

        // AND SAY THAT IT HAS BEEN FLOWN. This is not decoration, and it is the one thing the deleted
        // throttle was silently doing for the rest of the scenario: an all-zero input is still an
        // input, so the computer took its PILOTED branch every tick, which enables the ship's physics
        // and holds it on station. Without a pilot AND without the `stationKeeping` witness, the
        // unmanned branch returns immediately - the ship is deliberately inert, its physics is never
        // enabled, and a crew member standing on that deck is not resolved as aboard anything.
        //
        // The witness is honest here: this ship really has flown - it climbed past its planet's orbit
        // ceiling and crossed into a cell. It lacks the flag only because the climb is arranged by a
        // relocation rather than by a pilot at the controls. A zero cruise is a hover, exactly what
        // production leaves a flown ship holding when nobody is aboard.
        String holdsStation = exec("artest vs ff-cruise-at " + slotDim + " " + arrangedAfcPos
                + " 0 0 0");
        requireArranged("the settled ship must be left holding station like a flown ship, or "
                + "its physics never comes on and nothing can be aboard its deck: " + holdsStation,
                Reply.of(holdsStation).bool("afcResolved"));

        return slotDim;
    }

    /**
     * Boot 1 for the planet-boarded leg: same ship, same entry, but the pilot is IN THE SEAT before
     * the ship ever leaves the ground, and the crossing has to bring him along. Returns the slot
     * dimension he ends up banked in, and records the ship id and cell key production chose.
     *
     * <p>The two record readings around the flight are the point of the arrangement, not decoration.
     * On the ground the record must be ABSENT - being aboard means being aboard a ship in a cell, and
     * a planet-side seat is not that. After the arrival it must be PRESENT. Together they say the
     * record was produced BY the flight, which is the claim a green restart leg would otherwise be
     * unable to distinguish from "it was there all along".</p>
     */
    protected int seatThePilotBeforeHeLeavesTheGround() throws Exception {
        serverHarness = RealDedicatedServerHarness.startWith(root, false);

        SubsystemStatus status = SubsystemStatus.read(this::exec);
        assertTrue("the production space subsystem must be live on boot 1: " + status.raw(),
                status.registered);
        assertEquals("no ship may be ledgered before the flight: " + status.raw(),
                0, status.ledger);

        startClient();
        bot().waitForWorld();

        String launch = exec("artest space launch-cell " + LAUNCH_DIM);
        assertTrue("the launch dimension must resolve to a galactic address: " + launch,
                // `has` is the whole claim: it answers false for a field that is absent
                // AND for one whose value is JSON null, which is the shape the needle
                // `"cellKey":null` was hunting for one rendering of.
                Reply.of(launch).ok() && Reply.of(launch).has("cellKey"));

        Events events = events();
        String coords = placeFixture(FixtureSite.openAir(LAUNCH_DIM, SRC_X, SRC_Z), VARIANT);
        long assemblyMark = events.mark();
        String assembled = exec("artest rocket assemble " + LAUNCH_DIM + " " + coords);
        assertTrue("a build carrying a flight computer must become a ship, not a rocket: " + assembled,
                (Reply.of(assembled).integer("rocketCount") == 0));
        assertTrue("the ship never assembled in the launch dimension",
                waitForLoadedShip(events, assemblyMark, LAUNCH_DIM) >= 1);

        // The ship's identity on the GROUND, TRANSLATED from the name the assembler minted rather than
        // captured from a lookup at the fixture's spot: the family shares this world, so "the ship at
        // (SRC_X,SRC_Y,SRC_Z)" is a question a sibling scenario's craft can answer, in the same shape.
        // It is not the id the ledger reports after the entry: the crossing cuts the ship's blocks and
        // re-assembles them, so the craft that flies is a different VS object. This one addresses the
        // pilot's throttle before the crossing; `arrangedShipId` addresses everything after it.
        String durableShipId = ShipIdentity.nameFromAssembly(assembled);
        String groundShipId = ShipIdentity.physicsIdOf(this::exec, LAUNCH_DIM, durableShipId);

        ShipInfo srcInfo = ShipInfo.byId(this::exec, LAUNCH_DIM, groundShipId);
        int sx = (int) Math.round(srcInfo.x);
        int sy = (int) Math.round(srcInfo.y);
        int sz = (int) Math.round(srcInfo.z);

        // Board on the ground. The client has to be standing at the ship for its seat to be a loaded
        // tile at all, which is what the mount probe searches.
        long boardMark = clientEvents().mark();
        exec("tp @a " + (SRC_X + 0.5) + " " + (SRC_Y + 6) + " " + (SRC_Z + 0.5) + " 0 0");
        // The comment above IS the reason this is a link: "the client has to be standing at the
        // ship" is a fact about the client, and the seat is a loaded tile because of where it is.
        ClientEvents.awaitPlacedNear(clientEvents(), boardMark, SRC_X + 0.5, SRC_Z + 0.5,
                "the mount probe searches LOADED tiles, and the seat is loaded because the client is"
                        + " standing at the ship", RESTORE_LINK_BUDGET_TICKS);
        // On the ship this scenario built, by the id resolved from its own name. The bare form takes
        // the first pilot seat in the world's loaded-tile list — an arrival order — and this mounts
        // the bot on whatever it finds.
        SeatMount seatMount = SeatMount.onShip(this::exec, LAUNCH_DIM, groundShipId);
        assertTrue("the ground-side pilot seat must offer a mount: " + seatMount.raw(),
                seatMount.seatFound);
        long groundSeatMark = clientEvents().mark();
        String mount = exec("artest player mount-entity " + seatMount.requireDummyId());
        assertTrue("the client must take the pilot seat while still on the ground: " + mount,
                readBool(mount, "mounted"));
        // The same replication lag the restart-side seating hit, and the red it produced was in both
        // runs of the third acceptance pair: ten ticks, then an absolute read of the client's state.
        ClientEvents.awaitMounted(clientEvents(), groundSeatMark,
                "the CLIENT must confirm it is seated on the ground before this pilot is flown"
                        + " anywhere", RESTORE_LINK_BUDGET_TICKS);
        assertTrue("the CLIENT must confirm it is seated on the ground: " + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        // CONTROL (the record's meaning): a seat on a planet is not "aboard". If this already reads
        // tagged, the post-arrival reading below proves nothing about the flight.
        String groundTag = exec("artest space aboard-tag " + BOT);
        assertTrue("a pilot sitting on a planet must NOT yet carry an aboard record - the record "
                        + "means 'aboard a ship in a cell', and reading it as set here would make the "
                        + "post-arrival reading vacuous: " + groundTag,
                (!Reply.of(groundTag).bool("tagged")));

        // Fly, with him in the chair the whole way. Addressed to HIS ship's flight computer: a
        // seated rider is what keeps the input alive there (a riderless dummy clears it every tick),
        // so this is the one site in the family where a real throttle is the honest arrangement.
        String heldClimb = exec("artest vs ff-input-by-id " + LAUNCH_DIM + " " + groundShipId
                + " " + HELD_CLIMB);
        requireArranged("the throttle must reach the seated pilot's own flight computer: "
                + heldClimb, Reply.of(heldClimb).bool("afcResolved"));
        // Both marks BEFORE the lift: the entry is committed on the flight computer's own tick, so
        // there is no later moment at which a reader could still be sure it had not already run.
        long entryMark = events.mark();
        long clientEntryMark = clientEvents().mark();
        String climb = exec("artest vs teleport-ship-by-id " + LAUNCH_DIM + " " + groundShipId
                + " " + sx + " " + ABOVE_CEILING_Y + " " + sz);
        assertTrue("the climb past the orbit ceiling failed: " + climb, Reply.of(climb).ok());
        exec("artest vs unpark-by-id " + LAUNCH_DIM + " " + groundShipId);
        // WAS `waitTicks(20)` and a read of the client's riding flag. The budget decided the
        // verdict, and a first attempt to name a WINDOW instead was wrong for a second
        // reason worth recording: this is not a negative claim. A ship teleport legitimately
        // takes the client's mount OFF and puts it back, so "no dismount since the lift" is
        // false on a CORRECT run — the twenty ticks were covering the re-establishment. What
        // is claimed is that the chain ENDS seated, which is a link and cannot be sampled
        // past.
        try {
            clientEvents().awaitMatching(clientEntryMark, "mount",
                    seen -> ClientEvents.endsMounted(clientEvents(), clientEntryMark),
                    "a chain that ENDS in a mount (a mount exists, after every dismount)",
                    "the pilot must be back in his seat once the ship has reached the ceiling"
                            + " - if the lift alone unseats him this leg never tests the"
                            + " crossing", CLIENT_REMOUNT_LINK_BUDGET_TICKS);
        } catch (AssertionError never) {
            Events.assertInstrumentRan(clientEvents().since(clientEntryMark, "mount"),
                    "entity_mount_writes", "the client's own mounts must be observed at all"
                            + " before an absent one can be read as a seat he never regained");
            requireArranged(never.getMessage() + " | client says "
                    + bot().reportRidingEntity() + "; its own mounts since the lift: "
                    + clientEvents().since(clientEntryMark, "mount")
                    + "; its dismounts: " + clientEvents().since(clientEntryMark, "dismount"),
                    false);
        }

        // The entry as the chain a GRANTED one IS - the ship is cut into the cell, the gate records
        // its decision, the arrived hull's pose is written, the crew is put back on it and the
        // ledger is told where the ship now is. This craft carries its pilot across, so the
        // re-seat link is part of its contract rather than an empty formality, and a red now names
        // the link the on-ramp stopped at where the ledger count could only say "still zero".
        events.assertChain(entryMark, "a piloted craft flown past its planet's orbit ceiling must "
                        + "cross into its launch body's cell and settle there, carrying its pilot",
                RESTORE_LINK_BUDGET_TICKS, Chains.GRANTED_ENTRY);
        String entryChain = events.since(entryMark);
        SubsystemStatus ledgerStatus = SubsystemStatus.read(this::exec);
        assertTrue("the entered ship must be countable in the subsystem's own ledger, not only in "
                + "the record of the write: " + ledgerStatus.raw(), ledgerStatus.ledger >= 1);
        // Hands off. Aimed at the ship he actually flew - the pre-crossing one - because that is the
        // computer his throttle went to; the craft on the far side is a different VS object with a
        // fresh tile.
        exec("artest vs ff-input-by-id " + LAUNCH_DIM + " " + groundShipId);

        String[] slot = awaitSettledShipSlot();
        assertNotNull("the ledger holds a ship, but no slot dimension owns up to it", slot);
        int slotDim = Integer.parseInt(slot[0]);
        arrangedShipId = slot[1];
        arrangedAfcPos = slot[2] + " " + slot[3] + " " + slot[4];

        // The reader refuses a ship the ledger has no entry for, and refuses to answer a cell for
        // one — which is what the has-check and the notNull below it each stood for.
        LedgerEntry ledgerEntry = LedgerEntry.forShip(this::exec, arrangedShipId)
                .requireFound("the entered ship must be in the production ledger");
        assertEquals("and it must be settled, not mid-jump: " + ledgerEntry.raw(),
                "SETTLED", ledgerEntry.state());
        arrangedCellKey = ledgerEntry.cellKey();

        // The setpoint his long climb ramped is parked to a hover on the ship that came out of the
        // crossing, and that ship is marked flown. Full deflection saturates the setpoint at the
        // craft's 40 b/s cap inside 60 ticks - nearly three times the ceiling the observation windows
        // require - so a ship left holding it would make every one of them unreadable.
        String parked = exec("artest vs ff-cruise-at " + slotDim + " " + arrangedAfcPos + " 0 0 0");
        requireArranged("the arrived ship must be left holding station: " + parked,
                Reply.of(parked).bool("afcResolved"));

        // He rode his own ship across the seam: no probe transferred him, so a wrong dimension here
        // is the crossing failing to carry its crew, not an arrangement that walked him somewhere.
        // The CLIENT's own record of being put in that world, from the mark taken before the lift -
        // a world rebuilt twice between two samples shows one change to a poll, and none of the
        // three failures the assertion below separates would be distinguishable then.
        // The trailing comma is not decoration: `"dim":5` is a prefix of `"dim":51`, and the record
        // always carries `via` after the dimension.
        ClientEvents.awaitDim(clientEvents(), clientEntryMark, slotDim,
                "the pilot rode his ship across the seam, so the slot dimension the entry chose is"
                        + " where his client must end up. Server chain: " + entryChain,
                RESTORE_LINK_BUDGET_TICKS);
        int dim = clientDim();
        // WHERE he actually is, and whether he is still ON the thing that was supposed to carry
        // him. This used to be a bare sentence and a dimension mismatch, which is the same red
        // whether the crossing never happened, happened without him, or happened and dropped him -
        // three different bugs. The riding report is the discriminator: still seated in the WRONG
        // dim means the ship did not cross; not seated in the RIGHT dim means it crossed and left
        // him behind.
        assertEquals("the pilot must arrive in his ship's slot dimension by riding it there."
                        + " clientDim=" + dim + " expected=" + slotDim
                        + " riding=" + bot().reportRidingEntity()
                        + " serverSaysHeIsAt=" + exec("artest player position-of " + BOT)
                                .replace('\n', ' '),
                slotDim, dim);
        assertTrue("and he must still be seated after the crossing: " + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        PlayerPosition serverAfterArrival = PlayerPosition.of(this::exec, BOT);
        assertEquals("the SERVER must agree he is in the slot dimension after the crossing: "
                + serverAfterArrival.raw(), slotDim, serverAfterArrival.dim);

        // THE SUBJECT: he never sat down in a cell, so if the record is written only by the mount
        // transition there is nothing here - and the restart leg that follows would then put him back
        // at his overworld build site, which is precisely the played-through report.
        //
        // Awaited on the WRITE rather than polled on the tag: the record is produced by the
        // reconciler noticing he is now aboard a ship that is in a cell, and that write is the
        // event. The ground-side control above (tagged:false) is what makes this one mean the
        // FLIGHT produced it, and the mark it is read from was taken before the lift.
        String stamped = events.await(entryMark, "aboard_record_stamped",
                "a pilot who boarded on the ground and rode his ship into a cell must have a "
                        + "durable aboard record WRITTEN for him - it is the only evidence the "
                        + "restore has that he was ever aboard",
                RESTORE_LINK_BUDGET_TICKS);
        String tag = exec("artest space aboard-tag " + BOT);
        assertTrue("a pilot who boarded on the ground and rode his ship into a cell must carry the "
                        + "durable aboard record - it is the only evidence the restore has that he "
                        + "was ever aboard: " + tag + " | stamps: " + stamped,
                Reply.of(tag).bool("tagged"));
        assertTrue("and that record must name the ship the entry minted: " + tag
                + " (entered ship " + arrangedShipId + ")", arrangedShipId.equals(Reply.of(tag).text("shipId")));
        return slotDim;
    }

    /**
     * Copy the server's log aside under {@code label}. Each boot reopens {@code logs/latest.log} from
     * scratch, so without this the first boot's record - the only place that says what happened to the
     * pilot before he was written to disk - is destroyed by the second boot.
     */
    protected void keepBootLog(String label) {
        try {
            java.nio.file.Path live = root.resolve("logs").resolve("latest.log");
            if (java.nio.file.Files.exists(live)) {
                java.nio.file.Files.copy(live, root.resolve("logs").resolve(label + ".log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // The player file too: the SECOND boot's own logout rewrites it, so by the time anyone
            // inspects the world directory afterwards, what the FIRST boot persisted - the actual
            // input to the restore - is already gone.
            java.nio.file.Path live_pd = root.resolve("world").resolve("playerdata");
            java.nio.file.Path kept = root.resolve("world").resolve("playerdata-" + label);
            if (java.nio.file.Files.isDirectory(live_pd)) {
                java.nio.file.Files.createDirectories(kept);
                try (java.util.stream.Stream<java.nio.file.Path> files =
                             java.nio.file.Files.list(live_pd)) {
                    for (java.nio.file.Path f : files.collect(java.util.stream.Collectors.toList())) {
                        java.nio.file.Files.copy(f, kept.resolve(f.getFileName().toString()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception ignored) {
            // Diagnostics only - never fail a test because a log could not be copied.
        }
    }

    // THERE IS NO `assumeProductionSubsystemAvailable()` ANY MORE. It skipped the scenario when the
    // physics substrate was absent — a condition that cannot arise, because the substrate is
    // compiled into this jar. Worse, `Assume` skips SILENTLY: had the condition ever been true,
    // these scenarios would have vanished from the run and the gate would have stayed green over a
    // mod with no ship physics at all. Both callers already assert `status.registered` on the line
    // after it, which asks the same question and FAILS instead of disappearing. Removed 2026-09-22.

    // --- lifecycle ---------------------------------------------------------------------------------

    /** Start the client against the live server, never leaking the server JVM if the client fails. */
    protected void startClient() throws Exception {
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startupFailure) {
            try {
                serverHarness.close();
            } catch (Exception cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            serverHarness = null;
            throw startupFailure;
        }
    }

    /** Client first, then server: reversing the order leaks or hangs. Safe to call when nothing runs. */
    protected void closeBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception clientFailure) {
                deferred = clientFailure;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception serverFailure) {
                if (deferred == null) {
                    deferred = serverFailure;
                } else {
                    deferred.addSuppressed(serverFailure);
                }
            }
            serverHarness = null;
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    protected String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    protected ClientBot bot() {
        return clientHarness.bot();
    }

    /**
     * Hold {@code key} until the client-rendered rider altitude climbs {@link #MIN_CLIMB} over
     * {@code from} (bounded, early-exit), and FAIL HERE if it does not. Same
     * stimulus/observation pair as the planet-side relog-control pin: the REAL key in, the client's
     * own rendered player altitude out.
     *
     * <h2>Why the verdict lives here now</h2>
     *
     * <p>Both callers used to read the returned altitude and assert {@code (y1 - y0) >= MIN_CLIMB} —
     * the loop's own exit condition, restated. Neither could fail except by the budget running out,
     * and the message then made a claim about held input for what was a timeout.</p>
     *
     * <p>This one is not fixed by a WINDOW, and the reason is worth keeping. Where a quantity can
     * come back, a window and its extremum are right; where a drive must stop on its own threshold,
     * an independent fact has to carry the leg. Here there is neither: the ship must NOT be flown on
     * (the caller's next step settles a hovering hull before the server writes it to disk, and a
     * craft still climbing turns the restore comparison into a moving target), and the climb IS the
     * whole claim — "held input moves the ship" is what these legs are about. So the drive itself is
     * the assertion, and the expiry IS the failure, said in those words with the altitude it
     * reached. One assertion, in the place that knows what happened.</p>
     *
     * @param what what this climb proves, for the failure — the caller's own sentence
     */
    private double climbWith(int key, double from, String what, boolean arrangement)
            throws Exception {
        // THE MULTIPLIER STAYS, but NOT for the reason this comment used to give. A held key is
        // sampled and re-sent per CLIENT TICK - on change, plus a re-assert every
        // PilotInputCadence.REPEAT_TICKS - not once per rendered frame. What a loaded box stretches
        // is therefore the client's TICK rate, not its frame rate, and that is still wall-clock-bound
        // work a fork scale measures correctly. The frame story was refuted 2026-08-21 by arithmetic
        // on a red: 111 packets over ~2150 ticks is exactly the 20-tick re-assert, i.e. no starvation
        // at all - so a climb that stalls is NOT explained by this budget and must not be read that way.
        int budget = 40;
        double last = from;
        // THIS climb's pilot-input delivery chain, both halves, for the failure below. Opened per
        // climb because the class restarts the server between its legs, and a window lives and dies
        // with the server it was opened on.
        SeatDelivery seatDelivery = SeatDelivery.open(this::exec, bot(), events(), clientEvents());
        bot().holdKey(key);
        try {
            // A WINDOW with the key HELD across it: the iterations are part of the stimulus, and
            // what is asked is whether the climb reached a threshold — a value, not an instant
            // anything commits. The input reaching the flight computer IS a record and is awaited
            // where the key goes down; this measures what the thrust then did. What it cannot see:
            // a climb that reached MIN_CLIMB and sagged back inside one 5-tick sample.
            for (int i = 0; i < budget && (last - from) < MIN_CLIMB; i++) {
                bot().waitTicks(5);
                last = clientPlayerY();
            }
        } finally {
            bot().releaseKey(key);
        }
        if ((last - from) < MIN_CLIMB) {
            String why = what + " — the client's own rendered rider altitude went from " + from
                    + " to " + last + " over " + (budget * 5) + " ticks with the key"
                    + " held, which is " + (last - from) + " against the " + MIN_CLIMB
                    + " this needs. delivery=" + seatDelivery.reading();
            // This class types its arrangement failures through `ArrangementFailure`, not through a
            // `Scenario` — it is not on the shared-scenario base — so the refusal goes the same way
            // the rest of the file's do. Both calls throw; the branch picks WHICH kind.
            if (arrangement) {
                requireArranged(why, false);
            }
            assertTrue(why, false);
        }
        return last;
    }

    /**
     * {@link #climbWith} as the CONTRACT it is for the post-restart leg: a restored seat with a dead
     * key is a broken control chain, and that is this class's subject.
     */
    protected double climbWith(int key, double from, String what) throws Exception {
        return climbWith(key, from, what, false);
    }

    /**
     * {@link #climbWith} as an ARRANGEMENT — the pre-restart control leg. A pilot who could not fly
     * his ship BEFORE the restart has disproved nothing about the restore, so the JUnit XML must say
     * "the setup this test needed never happened" rather than name the contract.
     *
     * <p>Two NAMED methods rather than one with a flag: a boolean parameter would pick the failure's
     * TYPE silently at each call site, and the type is the whole difference between "this build is
     * broken" and "this run could not ask the question".</p>
     */
    protected double requireClimbWith(int key, double from, String what) throws Exception {
        return climbWith(key, from, what, true);
    }

    /**
     * Stand up through the production path and wait for the RECORD that says he is still aboard -
     * then hand back the aboard tag, for the caller's own assertions.
     *
     * <p>Two links, in the order production commits them: he leaves the mount ({@code dismount}),
     * and the reconciler's next pass re-derives his record and writes it ({@code
     * aboard_record_stamped}). The record is refreshed on a one-second cadence rather than on the
     * dismount itself, which is why this cannot be a single read; but it is a LINK and not a value,
     * so it is not polled either. The historic defect this leg exists for shows as the other write
     * appearing instead - {@code aboard_record_cleared} - and a red then says the record was
     * DROPPED rather than merely that a tag came back false.</p>
     */
    protected String standUpAndAwaitTheStandingRecord(Events events) throws Exception {
        long mark = events.mark();
        String dismount = exec("artest player dismount");
        assertTrue("the pilot must leave his seat: " + dismount, Reply.of(dismount).ok());
        events.assertChain(mark, "standing up on his own deck must keep him aboard: he leaves the "
                        + "mount, and the reconciler's next pass re-stamps his durable record. A "
                        + "record DROPPED here (an aboard_record_cleared in the chain below instead "
                        + "of a stamp) is exactly what used to send him to an ordinary spawn",
                RESTORE_LINK_BUDGET_TICKS, "dismount", "aboard_record_stamped");
        String stamps = events.since(mark, "aboard_record_stamped");
        String posture = Events.lastField(stamps, "posture");
        assertTrue("the record written after he stood up must say he is STANDING, not still seated"
                + " - it is the shape of the record, not its existence, that the restore reads:"
                + " posture=" + posture + " | " + stamps, "STANDING".equals(posture));
        return exec("artest space aboard-tag " + BOT);
    }

    /** The client's own rendered player altitude, or NaN while it has no world/player. */
    protected double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    /**
     * The client's OWN view of which dimension it is in, or {@link #NO_CLIENT_WORLD} while it has no
     * world yet. The weather report is the only client-side dimension oracle there is, and while
     * {@code mc.world} is null it answers with the readiness flag and nothing else - so the flag has
     * to be read before "dim" exists to be read at all.
     */
    protected int clientDim() throws Exception {
        JsonObject weather = bot().reportWeather();
        if (!weather.get("worldReady").getAsBoolean()) {
            return NO_CLIENT_WORLD;
        }
        return weather.get("dim").getAsInt();
    }

    /**
     * The slot dimension holding the settled ship and that ship's id, as
     * {@code [dimensionId, shipId]} - or {@code null} if no slot ever owns up to one. Slot dimension
     * ids are minted fresh on every boot, so they can only be discovered: every registered dimension
     * is asked whether the production ledger has a settled ship there whose flight computer resolves
     * at the cell pose. The re-assembly is asynchronous, so this retries, force-loading the ships of
     * any dimension that answered at all.
     */
    protected String[] awaitSettledShipSlot() throws Exception {
        String dims = exec("artest dim list");
        Reply listed = Reply.of("artest dim list", dims);
        assertTrue("could not read the registered dimensions: " + dims, listed.has(FORGE_DIMS));
        int[] ids = listed.intArray(FORGE_DIMS);
        // STAYS A LOOP, and the link that looks right does not answer. `ledger_settled` is awaited
        // elsewhere in this class and carries `ship` and `cell` — the CELL, not the Forge dimension
        // id, which is minted fresh on every boot and is the very thing this search exists to
        // discover. So a record can say the craft settled without saying where to ask for it, and
        // what remains is a scan of the registered dimensions. What this cannot see: a slot that
        // answered and stopped answering between two attempts.
        for (int attempt = 0; attempt < 30; attempt++) {
            for (int id : ids) {
                String trimmed = String.valueOf(id);
                // WHICH ship is asked for by name where the caller has one. This method is also the
                // path that DISCOVERS the arranged ship in the first place — the scenario has just
                // flown a craft up and does not yet know which slot took it — so on that first pass
                // there is no id to give and the slot's own settled row answers. Once
                // `arrangedShipId` is set (a relog, a second reading) the search is about that craft
                // and nothing else, which is what a dimension list holding several slots needs.
                String found = arrangedShipId == null
                        ? exec("artest space find-afc " + trimmed)
                        : exec("artest space find-afc " + trimmed + " " + arrangedShipId);
                // absence is the answer: the probe answers `{"error":"world or ledger not
                // ready"}` while the slot is still coming up, and this loop exists to sit
                // through exactly that.
                if (Reply.of(found).boolOr("found", false)) {
                    // Its flight computer's own block position rides along. The ledger's id and the
                    // VS ship uuid are DIFFERENT identities, and the by-id command verbs resolve the
                    // second; this is how a caller holding the first reaches that ship's computer.
                    // Its flight COMPUTER's position, not just a block of its hull - `x,y,z` is the
                    // first non-air block in the shipyard, which is whatever the scan met first.
                    assertTrue("the settled ship's flight computer must be locatable, and must be the"
                            + " one whose own durable id matches the ledger's: " + found,
                            readBool(found, "afcFound"));
                    return new String[]{trimmed, readShipId(found),
                            "" + readInt(found, "afcX"), "" + readInt(found, "afcY"),
                            "" + readInt(found, "afcZ")};
                }
                // absence is the answer, as above.
                if ((!Reply.of(found).boolOr("found", false))) {
                    // That dimension is loaded and the ledger is readable there; if the ship is
                    // simply not up yet, queueing its ships is what makes it resolvable.
                    exec("artest vs load-ships " + trimmed);
                }
            }
            bot().waitTicks(5);
        }
        return null;
    }

    /**
     * The live world position of the one ship in {@code dim}, or {@code null} if none is up within
     * the wait.
     *
     * <p>The cell holds exactly one ship, so the nearest ship to any point is that ship — and that
     * premise is now CHECKED on every answer rather than stated here, because a second craft in the
     * cell would make the reply indistinguishable from a correct one.</p>
     */
    protected double[] awaitShipPose(int dim) throws Exception {
        assertNotNull("awaitShipPose is about THIS pilot's ship, and the arrangement has not named"
                + " one yet", arrangedShipId);
        // STAYS A LOOP: what it reads is a state that FLICKERS — whether this ship is loaded and
        // queryable RIGHT NOW. `ledger_settled` records that it settled once, which is satisfied by
        // a settle since undone, and no record says "it is loaded at this instant" because that is
        // not an event anything commits. What this cannot see: a craft that came up and unloaded
        // again between two attempts.
        for (int attempt = 0; attempt < 40; attempt++) {
            // ASKED BY NAME. This was `ship-info <dim> 0 0 0` — an unbounded nearest lookup —
            // defended by asserting the cell held exactly one loaded ship. That defence answers a
            // different question than the one being asked: one loaded ship is not evidence that the
            // one loaded ship is HIS, and the case where it is not is precisely the case where his
            // has failed to load and a neighbour's has. A name has no such gap.
            String hull = exec("artest vs ship-uuid " + dim + " " + arrangedShipId);
            Reply namedReply = Reply.of(hull);
            // absence is the answer: this is a retry loop, and "not yet" is what it is reading for.
            if (Reply.of(hull).boolOr("found", false) && namedReply.has(SHIP_UUID)) {
                String info = exec("artest vs ship-info " + dim + " id " + namedReply.text(SHIP_UUID));
                if (ShipInfo.isLoaded(info)) {
                    ShipInfo pose = ShipInfo.of(info);
                    return new double[]{pose.x, pose.y, pose.z};
                }
            }
            exec("artest vs load-ships " + dim);
            bot().waitTicks(5);
        }
        return null;
    }

    /**
     * The assembly becoming a SHIP, then that ship being LOADED in {@code dim}.
     *
     * <p>The first half is a link and is awaited as one: {@code ship_spawned} is the physics mod's
     * own registry taking the ship, so a red says the assembly never produced one rather than
     * reporting a count that stayed where it was. The second half is not a link - "the shipyard is
     * loaded" is live state on a headless server that nobody stands near, and force-loading it is
     * what this poll is for - so it stays a bounded poll and returns the count it found.</p>
     */
    protected int waitForLoadedShip(Events events, long mark, int dim) throws Exception {
        events.await(mark, "ship_spawned", "the build must become a ship in the physics mod's own"
                + " registry - a ship count that never rises cannot tell an assembler that refused"
                + " from a queue that never drained", RESTORE_LINK_BUDGET_TICKS);
        // The spawn is a LINK and is awaited above. What follows was a poll for the LOAD, and it
        // is asserted instead: a headless server has no player to hold a craft loaded, so the ask
        // is made once — and then it either took or it did not. Repeating the ask forty times
        // cannot make a registered craft load if the first ask failed, it only converts that into
        // 200 ticks of ambiguity and an answer of 0 that means several different things.
        exec("artest vs load-ships " + dim);
        return ShipReadiness.requireLoaded(this::exec, dim,
                "the ship this scenario just spawned must be loaded before a login can restore it");
    }

    /** Clear the build site so the fixture is not welded to whatever terrain generated there. */

    /** Place a fixture build and return its build-controller position, as the assembler wants it. */
    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    protected static String readShipId(String json) {
        return Reply.of(json).text(SHIP_ID);
    }

    protected static int readInt(String json, String key) {
        return Reply.of(json).integer(key);
    }

    protected static int readIntOr(String json, String key, int def) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb takes the
        // default as an argument, so every call site names what a missing field means there.
        return Reply.of(json).integerOr(key, def);
    }

    /**
     * The JSON object out of a probe answer. The harness returns whatever the server printed, and a
     * test-mode server interleaves its own trace lines with the reply - so a numeric read against the
     * raw blob can match a digit from a log line and answer confidently with the wrong value.
     */
    protected static String jsonOf(String answer) {
        // The reply does not arrive on a line of its own: the server prints it THROUGH its logger, so
        // the object is embedded in "[14:40:23] [Server thread/INFO] [advancedrocketry]: {...}". A
        // line-level startsWith("{") therefore never matches and quietly returns the whole blob -
        // which is how a numeric read picks a digit out of a trace line and answers confidently.
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        return open >= 0 && close > open ? answer.substring(open, close + 1) : answer;
    }

    protected static double readDouble(String json, String key) {
        assertTrue("expected number \"" + key + "\" in: " + json, Reply.of(json).has(key));
        return Reply.of(json).number(key);
    }

    protected static String readString(String json, String key) {
        return Reply.of(json).text(key);
    }

    protected static boolean readBool(String json, String key) {
        return Reply.of(json).bool(key);
    }

    // --- the ordered event log ---------------------------------------------------------------------

    /**
     * The SERVER's ordered event log, for the boot that is up RIGHT NOW.
     *
     * <p>A fresh object every call on purpose: this family stops one server and starts another over
     * the same world root, and a sequence taken on boot 1 means nothing on boot 2 - both the log and
     * the numbering are new. A mark is therefore always taken on the boot that will record the
     * chain, never carried across {@link #closeBoth()}.</p>
     *
     * <p>{@link Events#mark()} asserts the recorder is subscribed before it answers, which is what
     * makes an empty log below mean "it did not happen" rather than "nobody was listening". The
     * recorder is registered when the probe command is (server start), so a mark taken before the
     * client connects is already covered - and it has to be taken there, because the login restore
     * fires ON the connection.</p>
     */
    protected Events events() {
        return new Events(this::exec, ticks -> bot().waitTicks(ticks));
    }

    /**
     * The same log, advanced on the SERVER's own clock instead of the client's.
     *
     * <p>For the one window this family has in which there is no client to wait in: between a
     * disconnect and the reconnect. {@link #events()} steps by {@code bot().waitTicks}, and the bot
     * is the thing that went away; the server is still ticking, and a logout is something it does on
     * a tick. {@link GameTicks#advance} also fails loudly when that clock STOPS, so a hung server is
     * reported as a stalled clock rather than as a link that never arrived.</p>
     */
    protected Events serverClockEvents() {
        return new Events(this::exec,
                ticks -> GameTicks.advance(serverHarness.client(), GameTicks.server(), ticks));
    }

    /**
     * How long one link of a login chain may take, in ticks - the same 450 the polls it replaces
     * spent (45 x 10). It is a deadline for a discrete event, not a guess at how long a value takes
     * to settle: the re-seat retries for {@code MAX_SEAT_ATTEMPTS} server ticks and then gives up
     * SILENTLY, so the budget has to outlive that silence rather than merely outlast a settle.
     */
    protected static final int RESTORE_LINK_BUDGET_TICKS = 450;

    /**
     * How long the CLIENT may take to publish the dimension it followed the bot into.
     *
     * <p>A link's budget, not a wait's: the record it waits on is published exactly once per
     * transfer, so the number only has to be longer than a slow box and a verdict never turns
     * on it. What stood here was {@code waitTicks(20)} followed by a read of the client's
     * dimension, which is a different thing — that one asserted how fast this box
     * replicates.</p>
     */
    protected static final int CLIENT_DIM_LINK_BUDGET_TICKS = 200;

    /** How long the CLIENT may take to end its mount chain seated again after a ship the
     *  pilot is riding is moved under him. A link's budget: the chain is published, and the
     *  verdict is the chain rather than the number. */
    protected static final int CLIENT_REMOUNT_LINK_BUDGET_TICKS = 200;

    /**
     * Wait for one record of {@code type} on the CLIENT's own log - optionally one carrying
     * {@code needle} - or fail naming everything the client DID record since {@code mark}.
     *
     * <p>The two logs are separate instruments with separate sequences: {@link #events()} reads the
     * server's through the probe channel, and the client's own is reachable only through the bot.
     * Cross-side ORDER within a tick is undefined, so a client link is always awaited BESIDE a
     * server chain and never inside one — which is why this family has a client wait of its own
     * rather than adding links to {@link Events#assertChain}.</p>
     */
    protected String awaitClientEvent(long mark, String type, String what, int tickBudget)
            throws Exception {
        return clientEvents().await(mark, type, what, tickBudget);
    }

    /**
     * The same, narrowed to a record whose {@code field} equals {@code value}.
     *
     * <p>Two methods rather than one taking a nullable needle: the nullable form decided between two
     * different waits — any record of the type, or one particular record — on the shape of an
     * argument, which is a branch a caller cannot see at the call site. It also took the narrowing
     * as a RENDERING, so a recorder that reordered two fields would turn "this one" back into "any
     * of them" silently.</p>
     */
    protected String awaitClientEventWithField(long mark, String type, String field, Object value,
                                               String what, int tickBudget) throws Exception {
        return clientEvents().awaitField(mark, type, field, value, what, tickBudget);
    }

    /**
     * The CLIENT's own ordered event log, behind the same verbs as {@link #events()}.
     *
     * <p>This family boots its own harness rather than extending the shared client base, so it
     * reaches {@link ClientEvents} directly. {@link Events#markInstrumented} must never be called on
     * it: the client reply carries no {@code mixins} flag.</p>
     */
    protected Events clientEvents() {
        return ClientEvents.of(bot());
    }

    /**
     * The client's log from the very beginning of ITS session.
     *
     * <p>Every restart leg starts a BRAND NEW client JVM, and the fact a chain needs -
     * {@code client_dimension_changed} at {@code handleJoinGame} - happens inside
     * {@link #startClient()}, before a mark could be taken. The client's log is empty at that
     * moment, so zero IS the mark, and nothing older than this session can be in it. A leg that
     * reuses one client across a reconnect must NOT use this: there the pre-logout session is still
     * in the ring, and a mark taken before the disconnect is the honest one.</p>
     */
    protected static final long CLIENT_SESSION_START = 0L;

    // --- the per-tick ship-frame record (client side) -----------------------------------------------

    /**
     * The client's whole per-tick record, read as ONE field. Sampling the individual statics instead
     * costs a round trip each, which stretches the very timeline being measured and hides everything
     * between the samples.
     */
    /**
     * This client's per-tick ship-frame record, as the REPLY it is.
     *
     * <p>It used to hand back {@code fieldLines(…, "line")} — the packed rendering each record also
     * carries — and every reader below re-parsed that with a regex. The record has carried the same
     * numbers as FIELDS since the trace moved into {@code MixinShipFrameTravelWrites}, which kept
     * the line only so the readers would not have to change in the same step. They change here.</p>
     */
    protected String clientTickHistory() throws Exception {
        return clientEvents().since(0, "ship_frame_tick");
    }

    /** The resolved-tick counter of one {@code ship_frame_tick} record — the window's own clock. */
    protected static long tickOf(String record) {
        return (long) Events.number(record, "resolvedTick");
    }

    /** One ship-frame point of a record: {@code bodyLocal} (where the body IS) or {@code held}
     *  (where the resolver committed it). */
    protected static double[] pointOf(String record, String prefix) {
        return new double[]{Events.number(record, prefix + "X"), Events.number(record, prefix + "Y"),
                Events.number(record, prefix + "Z")};
    }

    /** Where the body IS, in the ship's frame. */
    protected static final String BODY_LOCAL = "bodyLocal";
    /** Where the resolver COMMITTED it, in the ship's frame. */
    protected static final String HELD = "held";



    /** The newest resolved-tick number on record - the mark a window starts from. The record survives
     *  the reconnect, so without this mark the pins would read ticks from before the restart. */
    protected long lastClientTick() throws Exception {
        long last = -1L;
        for (String record : Events.records(clientTickHistory())) {
            last = tickOf(record);
        }
        return last;
    }

    /**
     * How far the BODY's own ship-frame point travelled along the deck inside the window: from the
     * first tick after {@code fromTick} to the FARTHEST one, not the last, so a body that wanders out
     * and comes back cannot pass.
     */
    protected double bodyPointTravel(String history, long fromTick) {
        return travel(history, fromTick, BODY_LOCAL);
    }

    /** The same measure for the point the resolver COMMITS - still for a body someone else pulls. */
    protected double heldPointTravel(String history, long fromTick) {
        return travel(history, fromTick, HELD);
    }

    /**
     * @param prefix which ship-frame point to follow — {@link #BODY_LOCAL} or {@link #HELD}.
     *               It used to be a GROUP INDEX into the packed line (3 or 6), which is a number
     *               whose meaning lived in a regex somewhere else.
     */
    protected double travel(String history, long fromTick, String prefix) {
        double[] first = null;
        double worst = 0.0;
        for (String record : Events.records(history)) {
            if (tickOf(record) <= fromTick) {
                continue;
            }
            double[] point = pointOf(record, prefix);
            if (first == null) {
                first = point;
            } else {
                worst = Math.max(worst, alongDeck(first, point));
            }
        }
        return worst;
    }

    /** Ticks the window actually covers - the witness that the pins had something to look at. */
    protected int resolvedSince(String history, long fromTick) {
        int n = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick) {
                n++;
            }
        }
        return n;
    }

    /**
     * WHO moved the body, as numbers rather than inference. A nonzero incoming ship-relative motion
     * names a VELOCITY writer; a carry that does not match what the deck is doing names the held
     * carry; ticks on the hull path name the capture-mode flip itself; input ticks say
     * the body was not actually idle and the whole window is void.
     */
    protected String writerSummary(String history, long fromTick) {
        double worstMotion = 0.0;
        double worstCarry = 0.0;
        int hull = 0;
        int inputTicks = 0;
        int offDeck = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) <= fromTick) {
                continue;
            }
            double mx = Events.number(record, "motionShipX");
            double mz = Events.number(record, "motionShipZ");
            worstMotion = Math.max(worstMotion, Math.sqrt(mx * mx + mz * mz));
            // The line packed the carry's LENGTH; the record carries its three components, so the
            // length is computed here. This is the only value of the trace that was not a field.
            double cx = Events.number(record, "carryX");
            double cy = Events.number(record, "carryY");
            double cz = Events.number(record, "carryZ");
            worstCarry = Math.max(worstCarry, Math.sqrt(cx * cx + cy * cy + cz * cz));
            if ("h".equals(Events.text(record, "path"))) {
                hull++;
            }
            if (Events.number(record, "inStrafe") != 0.0
                    || Events.number(record, "inForward") != 0.0) {
                inputTicks++;
            }
            if ("false".equals(Events.text(record, "onDeck"))) {
                offDeck++;
            }
        }
        return "maxShipRelativeMotion=" + worstMotion + " maxCarry=" + worstCarry
                + " hullPathTicks=" + hull + " ticksWithInput=" + inputTicks
                + " ticksOffDeck=" + offDeck;
    }

    /**
     * The largest single-tick step the body's ship-frame point took. A placement jump shows up here
     * and nowhere else, which is what lets the creep total below stay honest about a steady drag.
     */
    protected double worstStep(String history, long fromTick) {
        return stepStat(history, fromTick, false);
    }

    /**
     * The sum of the per-tick steps that fall inside the creep band - the size of a drag, with the
     * one-off jumps of a placement excluded rather than averaged away.
     */
    protected double creepBandTotal(String history, long fromTick) {
        return stepStat(history, fromTick, true);
    }

    protected double stepStat(String history, long fromTick, boolean bandSum) {
        double[] previous = null;
        double worst = 0.0;
        double total = 0.0;
        for (String record : Events.records(history)) {
            if (tickOf(record) <= fromTick) {
                continue;
            }
            double[] point = pointOf(record, BODY_LOCAL);
            if (previous != null) {
                double step = alongDeck(previous, point);
                worst = Math.max(worst, step);
                if (step >= CREEP_STEP_MIN && step <= CREEP_STEP_MAX) {
                    total += step;
                }
            }
            previous = point;
        }
        return bandSum ? total : worst;
    }

    /** Distance in the deck plane: the ship frame's own horizontal, gravity excluded. */
    protected static double alongDeck(double[] a, double[] b) {
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    protected static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
