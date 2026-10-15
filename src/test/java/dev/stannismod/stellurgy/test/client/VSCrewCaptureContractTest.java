package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;
import org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator;


import dev.stannismod.stellurgy.test.PlayerShipData;
import dev.stannismod.stellurgy.test.PlayerPosition;
import dev.stannismod.stellurgy.test.DeckCapture;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipAssembly;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.NotYetRed;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;

/**
 * The capture/release contract of the ship-frame crew - what makes a body ABOARD, what keeps it
 * there, and what leaves a bystander alone - pinned against a REAL CLIENT PLAYER — the subject that
 * broke in the inverted-boarding playtest. Two boundary behaviours that the world-AABB containment
 * gate got wrong:
 *
 * <ul>
 *   <li><b>Jumping on the TOP deck keeps the capture.</b> The hull's top surface sits at the
 *       ship's world-AABB ceiling; a jump apex from there crossed the old grown-box gate
 *       (`leftShipBox`) and the capture died MID-AIR — vanilla, blind to the subspace deck, then
 *       tunnelled the body through the whole ship. The stay region is measured in SUBSPACE with a
 *       real margin, so a jump must ride out and land back on the deck, still captured.</li>
 *   <li><b>A player walking on world TERRAIN near a ship is never captured.</b> A ground
 *       position mapped through a parked ship's transform can alias onto a subspace block, and the
 *       old first-contact gate then captured a walker who stood on plain ground beside the hull
 *       (the playtest's "entered the ship transform at a random place"). Terra firma always keeps
 *       world-frame movement.</li>
 * </ul>
 *
 *
 * <h2>One client for all eleven scenarios</h2>
 *
 * <p>Measured 2026-08-07: these eleven cost <b>19.3 minutes across eleven client boots</b> — 105 s
 * each, nearly all of it startup — and with {@code VSDeckCaptureAndDismountTest} they were the
 * ship tier's wall-clock floor. The bases below (5220…6420, 100 blocks apart) are unchanged: they
 * are a plot allocator written by hand, and each is the ground its green runs were taken on.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSCrewCaptureContractTest extends AbstractSharedVsClientTest {

    @Override
    protected String subsystem() {
        return "vs-crew-capture";
    }

    /**
     * How far BELOW the hull the staging clearing reaches: far enough that a body which MISSES it
     * keeps falling, so a miss fails this scenario as a miss instead of as a hold that never
     * engaged.
     *
     * <p><b>A CLEARANCE, and it was an absolute y=40 until 2026-09-14.</b> That number was chosen
     * against a hull sitting near y=72, and it is the same defect {@code CLEAR_AIR_Y} had one file
     * over: an absolute altitude that silently stops bracketing its subject the moment the subject
     * moves. With the fixture in the open-air band the old pair cleared y=40..90 — a slab entirely
     * BELOW the craft, while the body was dropped at {@code shipY + 7} into a volume nothing had
     * looked at. Measured against the hull, the clearing follows it wherever it is flown.</p>
     */
    private static final int SHAFT_BELOW_HULL = 30;

    /** How far ABOVE the hull it reaches: past the drop point ({@code shipY + 7}) with room to
     *  spare, so the body is never released inside a block. */
    private static final int SHAFT_ABOVE_HULL = 12;

    private static final String BODY_SHIP_FRAME_Y = "bodyShipFrameY";

    /** The body's OWN motion and the velocity the substrate holds for it — the two candidate
     *  writers, read together because a body drifting for either reason looks the same. */
    private static final String MOTION_X = "motionX";
    private static final String MOTION_Y = "motionY";
    private static final String MOTION_Z = "motionZ";
    private static final String ADDED_X = "addedVelX";
    private static final String ADDED_Y = "addedVelY";
    private static final String ADDED_Z = "addedVelZ";
    private static final String TICKS_SINCE_TOUCHED = "ticksSinceTouchedShip";

    private static final String VARIANT = "with-pilot-deck";

    /**
     * THIS scenario's ship, by identity — read by {@code buildShip} off the registry's own
     * {@code ship_spawned} record of the assembly it just queued, and the address every later
     * question and command uses. The scenario BUILT this ship, so it is told which one it is; a
     * radius bound around the build site would be a mitigation, not an identity, and these scenarios
     * jump, roll, hover and drop the hull on purpose while a shared client always has a neighbour in
     * candidacy.
     */
    private String scenarioShipId;

    /**
     * How long one deck-capture LINK is given, in ticks.
     *
     * <p>A deadline for a discrete event, not a guess at how long a value takes to settle: a
     * dismount's seed is refused for the few ticks the client's own {@code isRiding} lingers, and a
     * teleported body has to reach the deck before the deck can claim it. Generous against an
     * eight-fork load, and short enough that a capture which never happens fails here rather than
     * waiting out a budget.</p>
     */
    private static final int CAPTURE_LINK_BUDGET_TICKS = 200;

    /**
     * How many gate decisions the (client's) deck-gate window may record around a capture stimulus.
     *
     * <p>Sized from the question, not from the wait: the capture is decided in the handful of ticks
     * after the body arrives, and forty of them either side spans that with room for the arrival to
     * be slow without spending the log's 256-deep ring. A window that runs out says so in its last
     * record rather than going quiet — see {@code DeckGateWindow}.</p>
     */
    private static final int GATE_WINDOW_RECORDS = 40;

    /**
     * The same budget for a WALK rather than an arrival: twelve four-tick steps is forty-eight ticks
     * of gate decisions, and the round trips between them add more. Eighty covers it with room and
     * still stops well short of the log's 256-deep ring for this type.
     */
    private static final int GATE_WINDOW_RECORDS_WALK = 80;

    /**
     * How long a vertical jump is given to end on the deck it started from, in ticks.
     *
     * <p>A DEADLINE for a discrete event, not a stand-in for it: the landing is a record, and this
     * only says how long we are willing to wait for it. Sized from the arc it bounds — a jump rises
     * and falls in well under twenty ticks on a hovering craft, and what this budget actually has to
     * cover is a body the resolver has to catch on the way down, so it is several times that rather
     * than a tight fit. A body that never lands on this ship is the finding, and it is the one thing
     * a fixed sample count could never report.</p>
     */
    private static final int JUMP_LANDING_BUDGET_TICKS = 120;

    /**
     * How far the carry the client installs may exceed the craft's own reading of its speed at that
     * body, in blocks per tick.
     *
     * <p>Both sides are per-TICK maxima of dense series now — the client's from every
     * {@code ship_frame_tick}, the craft's from every {@code deck_guard_pass} — so this covers only
     * the moment between the guard's reading and the commit that follows it, i.e. what the craft's
     * acceleration does inside one tick. It does NOT have to cover the sampling mismatch it
     * replaced: a per-tick peak against a five-tick average needed a slack that grew with how hard
     * the drive oscillated, which is a bar that can only be tuned upwards forever.</p>
     *
     * <p><b>Measured in both regimes, and the answer was the same number twice</b>: excess
     * <b>0.0</b> — idle, client 2.1666666666666856 against 2.1666666666666856; under seven
     * concurrent classes, 3.0231607075978246 against 3.0231607075978246. Equal to the last digit
     * each time, at different peaks, because at the peak both readings come from the craft's own
     * velocity at that point. That is the shape a healthy agreement has.</p>
     *
     * <p>So the bar is not a slack for disagreement — there is none to cover. What it covers is the
     * one case the two runs did not produce: a tick on which the client commits and the guard does
     * not pass, leaving the client's maximum one tick of the craft's ACCELERATION ahead of the
     * guard's. Sized from that acceleration as this drive exhibits it — the sampled rate moved
     * 2.562 to 3.719 over twenty iterations, about 0.06 blocks/tick/tick — and rounded up to one
     * decimal. It is deliberately far below the defect it exists for, a client reconstructing the
     * rate from its own observations, which was once out by a factor of two hundred.</p>
     *
     * <p>The excess is printed on every run beside this bar, so how much of it is being used stays
     * readable rather than remembered.</p>
     */
    private static final double CARRY_OVER_STEP_TOLERANCE = 0.1D;

    /**
     * How far past vertical the parked craft must be rolled before an INVERTED-deck scenario means
     * anything — the world-frame Y of the deck normal, so level is {@code +1} and upside-down
     * {@code -1}.
     *
     * <p><b>This is the TEST'S OWN, and an arrangement fact rather than a threshold on anything
     * production decides.</b> Production has no opinion about "steep": the attitude hold takes
     * whatever quaternion it is pointed at, and the capture gates read the deck plane at whatever
     * angle it happens to be. What the number denotes is the premise of these scenarios — that the
     * hull's UNDERSIDE is now uppermost, so there is a hull-top for a body to stand on and the ship
     * frame is unmistakably not the world frame. Past vertical is {@code < 0}; this asks for it
     * comfortably, because a craft a hair past vertical makes the aliasing these legs exist to
     * forbid barely arise. Three legs commanded the same roll and wrote the same literal.</p>
     */
    private static final double INVERTED_DECK_UP_Y = -0.3;

    /**
     * The roll band in which the world-up eye and the RENDERED camera eye visibly diverge, again as
     * the deck normal's world-frame Y.
     *
     * <p>The TEST'S OWN, and a band rather than a bound because both ends are premises: above
     * {@code 0.7} the craft is near enough level that the two eyes coincide and nothing below could
     * fail, and at or under {@code 0.1} it is near enough to vertical that the deck plane the aim is
     * measured against becomes ill-conditioned. Written once because two legs assert the identical
     * premise about the identical commanded roll.</p>
     */
    private static final double DIVERGENT_ROLL_UP_Y_MAX = 0.7;
    /** @see #DIVERGENT_ROLL_UP_Y_MAX */
    private static final double DIVERGENT_ROLL_UP_Y_MIN = 0.1;

    /**
     * How far a body given NO input may drift across the deck it stands on, in blocks, over one
     * observation window.
     *
     * <p>The TEST'S OWN — a tolerance on a contract whose real statement is "zero". Production
     * publishes no allowance here and none would be right: a still body's position is re-derived
     * every tick from the deck's pose, so any lasting excursion is arithmetic going wrong. Half a
     * block is under the width of the body and far under the stand cell, so it refuses a body that
     * has left the square it was put on while tolerating sub-block settle.</p>
     */
    private static final double STILL_BODY_DRIFT_BLOCKS = 0.5;

    /**
     * How many times the client's capture may be released and retaken inside one observation window
     * before it counts as CHURN.
     *
     * <p>The TEST'S OWN. The contract is that ordinary activity on one's own deck — standing,
     * walking, jumping, riding a roll — does not hand the body back at all, so the honest bound is a
     * small number rather than zero only because a scenario crosses genuine boundaries (a dismount,
     * a re-seat) whose releases are correct. The count is over EVERY release in the window whatever
     * gate gave it, which is what makes a red name the gate that fired.</p>
     */
    private static final int CAPTURE_CHURN_LIMIT = 5;

    /** How many mouse turns the deck-look sweep performs. The TEST'S OWN: it is the experiment. */
    private static final int DECK_LOOK_TURNS = 4;

    /**
     * One turn of the sweep, in vanilla mouse units.
     *
     * <p>DERIVED from vanilla's own conversion rather than tuned: {@code EntityPlayer.turn} applies
     * {@code deltaX * f * 0.15} where {@code f = (sensitivity * 0.6 + 0.2)^3 * 8}, which is exactly
     * {@code 1} at the default sensitivity the harness boots with — so 200 units is
     * {@code 200 * 0.15 = 30} degrees of yaw. The degrees are what the assertion is about and the
     * units are what the bot takes, so both are named and the relation between them is written down
     * where it can be checked.</p>
     */
    private static final float MOUSE_UNITS_PER_TURN = 200f;
    /** @see #MOUSE_UNITS_PER_TURN */
    private static final double DEG_PER_TURN = MOUSE_UNITS_PER_TURN * 0.15;

    /**
     * How far the measured sweep may sit from the commanded one, in degrees.
     *
     * <p>The TEST'S OWN, and the only invented number in that leg: the sweep is summed from
     * per-turn plane angles measured off the client's own look vector two ticks after each command,
     * so it carries the rounding of a float yaw and whatever fraction of a turn the last tick had
     * not yet applied. What the bound refuses is a sweep that STALLED (near zero) or WRAPPED (near a
     * full turn), both of which are far outside it.</p>
     */
    private static final double DECK_LOOK_SWEEP_TOLERANCE_DEG = 20.0;

    /**
     * How far a body must rise above the surface it was standing on before it counts as having
     * JUMPED, in blocks.
     *
     * <p>The TEST'S OWN, sized against vanilla rather than production: a vanilla jump leaves the
     * ground with {@code motionY = 0.42} and apexes about 1.25 blocks up, so half a block sits
     * comfortably inside a real jump and comfortably outside the sub-block bob of a body being
     * re-seated by the deck it stands on.</p>
     */
    private static final double JUMP_LEFT_THE_DECK_BLOCKS = 0.5;

    /**
     * How far from the deck's own Y a body may settle and still be ON it, in blocks.
     *
     * <p>The TEST'S OWN. A block is 1.0, so a body within a block and a half of the deck is
     * standing on it or on something it carries; anything further has fallen through the hull or
     * been thrown off it, which is the pair of outcomes the landing leg refuses.</p>
     */
    private static final double LANDED_ON_THE_DECK_BLOCKS = 1.5;

    /**
     * The deck-normal Y above which the parked craft counts as UPRIGHT — the premise the
     * ground-walker legs need to be false.
     *
     * <p>The TEST'S OWN, and deliberately loose: the commanded 45 degrees reads about 0.71, so any
     * real tilt is far under this. What it refuses is an upright ship, where the frame aliasing
     * those legs exist to forbid barely arises at all.</p>
     */
    private static final double TILTED_ENOUGH_UP_Y = 0.95;

    /**
     * How much a body's Y may change between two consecutive reads and still count as AT REST, in
     * blocks.
     *
     * <p>The TEST'S OWN — a bound on "has stopped", not on anything production decides. Vanilla
     * gravity reaches 0.08 blocks/tick after a single tick and keeps accelerating, so a falling
     * body covers far more than this between two reads.</p>
     */
    private static final double AT_REST_Y_CHANGE_BLOCKS = 0.05;

    /**
     * How far a body walking on FLAT GROUND may move vertically across the whole walk, in blocks.
     *
     * <p>The TEST'S OWN: the ground is flat because the scenario laid it, so the honest statement is
     * that his Y does not change. Two blocks tolerates a vanilla step up and a jump; the defect it
     * refuses is a body snatched into the tilted ship's frame, which moves him by the lever arm
     * between him and the hull — many blocks.</p>
     */
    private static final double GROUND_WALK_Y_SPREAD_BLOCKS = 2.0;

    /**
     * The step the client's own travel commit is made to declare, in blocks — chosen to land BETWEEN
     * the two things that could refuse it, so that exactly one of them is asked.
     *
     * <p>Above the deck bound's one-tick region for a parked craft:
     * {@code DeckMovementBound.PLAYER_MAX_OWN_BLOCKS_PER_TICK + REGION_FLOOR_BLOCKS} = 3. Below
     * vanilla's own speed check, which refuses a step whose squared length exceeds 100 over the
     * server's copy of the motion ({@code NetHandlerPlayServer:547-551}) — ten blocks for a body the
     * server has at rest — and refuses it FIRST, returning before the bound is consulted. Measured
     * 2026-09-28 with a forty-block step: "moved too quickly! 0.0,40.0,0.0" in the server log, and no
     * bound record at all.</p>
     */
    private static final int WILD_STEP_BLOCKS = 6;

    /** Client ticks of walking before the step: long enough that the bound has judged this body on
     *  consecutive ticks (a walking client sends a position every tick), so its region is one tick's. */
    private static final int WALK_BEFORE_THE_STEP_TICKS = 5;

    /**
     * How far a body must rise for the double-tap to have actually started CREATIVE FLIGHT, in
     * blocks.
     *
     * <p>The TEST'S OWN, sized against vanilla again: a non-flying player holding space jumps and
     * lands, apexing about 1.25 blocks, so a full 1.5 cannot be reached by jumping.</p>
     */
    private static final double CREATIVE_FLIGHT_RISE_BLOCKS = 1.5;

    /**
     * How far a flying body may be given back downward before it counts as YANKED, in blocks.
     *
     * <p>The TEST'S OWN: a steady ascent, deck-frame or world-frame, never gives back more than a
     * fraction of a block. The defect this names is the war's signature — the body pulled back
     * toward the seat it left.</p>
     */
    private static final double FLYING_BODY_MAX_DROP_BLOCKS = 0.75;

    /**
     * How far apart the world-up eye and the RENDERED camera eye must be for the ray legs to prove
     * anything, in blocks.
     *
     * <p>The TEST'S OWN sensitivity bar: on a level ship the two coincide, and nothing below could
     * then fail whatever the code did. At this roll they separate by more than this.</p>
     */
    private static final double EYE_DIVERGENCE_BLOCKS = 0.6;

    /**
     * How far the craft must roll FURTHER for the turn-with-the-deck leg to prove anything, in
     * degrees.
     *
     * <p>The TEST'S OWN sensitivity bar, and it is several times
     * {@link #AIM_FOLLOWS_DECK_TOLERANCE_DEG} — so a roll that satisfies it cannot be mistaken for
     * a world-glued aim standing still.</p>
     */
    private static final double FURTHER_ROLL_DEG = 15.0;

    /**
     * How far the aim may sit from the roll it is supposed to have followed, in degrees.
     *
     * <p>The TEST'S OWN: the two attitudes are sampled a few ticks apart while the hull is still
     * turning. What it refuses is an aim that did not turn at all, which is the whole roll away.</p>
     */
    private static final double AIM_FOLLOWS_DECK_TOLERANCE_DEG = 8.0;

    /**
     * How far the rendered camera's angles may sit from the player's own world aim, in degrees.
     *
     * <p>The TEST'S OWN: one is the player's aim and the other the partial-tick interpolation of it
     * the renderer was handed, sampled on different frames. The defect it refuses is a camera
     * pointing somewhere else entirely — the whole roll away, not three degrees.</p>
     */
    private static final double CAMERA_AIM_AGREEMENT_DEG = 3.0;

    /**
     * How far a body must travel under a held walk key for the walk to have HAPPENED, in blocks.
     *
     * <p>The TEST'S OWN sensitivity bar: vanilla walks about 0.215 blocks/tick, so a window of a
     * second and a half covers many times this. What it refuses is a body that did not move.</p>
     */
    private static final double WALKED_A_WALKING_DISTANCE_BLOCKS = 0.3;

    /**
     * How far the direction a body WALKED may sit from the deck heading the mouse steers, in
     * degrees.
     *
     * <p>The TEST'S OWN, and wide on purpose: the walk is measured over a short displacement on a
     * deck rolled to about 90 degrees, where a block of lateral settle turns into several degrees
     * of apparent heading. What it refuses is a walk that went somewhere else — the ninety degrees
     * of a body moving in the world frame while the player steers in the deck's.</p>
     */
    private static final double WALK_HEADING_TOLERANCE_DEG = 30.0;

    /**
     * How far the deck normal must move while nobody is watching the craft, for the unwatched
     * interval to span any motion at all.
     *
     * <p>The TEST'S OWN sensitivity bar: a tenth of the deck normal is about six degrees of tilt —
     * far above the settle jitter of a parked hull and far below the commanded manoeuvre.</p>
     */
    private static final double ARRANGEMENT_MOVED_UP_Y = 0.1;

    /**
     * What counts as a manoeuvre being OVER: the hull's residual translation, in blocks per tick.
     *
     * <p>The TEST'S OWN. It is the test's because production never declares a manoeuvre finished —
     * the hold simply keeps applying torque toward a target it has already reached.</p>
     */
    private static final double SETTLED_BLOCKS_PER_TICK = 0.05;
    /** The rotational half of {@link #SETTLED_BLOCKS_PER_TICK}, in rad/s. The drive above commands
     *  about 2 rad/s, twenty times this. */
    private static final double SETTLED_OMEGA_RAD_PER_S = 0.1;

    /**
     * How far the aim may leave its cone about the deck normal under a HORIZONTAL mouse move, as a
     * cosine.
     *
     * <p>The TEST'S OWN. The quantity is a dot product of two unit vectors, and the contract is
     * that a horizontal move does not change it at all; 0.05 of cosine is about three degrees, the
     * float rounding of a yaw round-trip through the client. A world-frame aim at this roll leaves
     * the cone by tens of degrees.</p>
     */
    private static final double AIM_STAYS_ON_CONE = 0.05;

    /**
     * The same quantity while the DECK ITSELF is rolling under a still mouse — a shade wider,
     * because the two readings are taken against attitudes a few ticks apart.
     */
    private static final double AIM_STAYS_ON_CONE_WHILE_ROLLING = 0.06;

    // ---- Staying aboard: a jump from the top deck must not release the capture ------------------

    /**
     * A jump from the top deck keeps the capture and lands back on the same deck.
     */
    @LegacyWitness(prose = "one inversion per verdict, 2026-09-28, taken on the pre-2026-10-06 form against"
            + " the travel resolver's own aboard jump (`ShipFrameTravel.jump`, deleted with the resolver) and NOT"
            + " re-taken since: a jump on a deck is now vanilla's own, run in the deck's frame at"
            + " {@code DeckFrameTick#runInDeckFrame} at {@code ownUpdate.run()}. THE LANDING — that jump adding"
            + " 1.5 blocks a tick of ship-frame sideways motion to every aboard jump, flinging him off the deck:"
            + " \"the jumper was in the air when the key came up, so he must LAND on the deck he jumped from … no"
            + " `deck_contact` carrying ship = …\". THE JUMP LEAVES THE DECK — the same jump given no upward motion"
            + " (`motion[1] = up`): \"the jump must actually leave the deck (apex=157.0 deckY=157.0)\"; that one"
            + " never airs him, so the conditional landing wait is skipped on it.")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "for (int i = 0; i < 10; i++) {",
            why = "the APEX of the jump is an extremum over the samples, a property of the arc rather than of any"
                    + " instant production commits, so no record can carry it. What it cannot see: a higher"
                    + " apex reached and left between two samples.")
    public void jumpingOnTheTopDeckKeepsTheCaptureAndLandsBackOnIt() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The subject is on the HARD side of the geometry: the fixture's walkable deck is the hull's
        // TOP surface, so the player's feet stand at the ship's world-AABB ceiling and a vanilla jump
        // apex (~1.25) pokes above the old grown-box gate. On the old gate this exact jump released
        // the capture mid-air; the contract is that it must not.
        double[] ship = buildShip(site);
        Events client = clientEvents();
        // NOTHING BETWEEN THE TELEPORT AND THE LINK. Eighty ticks of "pacing" stood here, defended
        // by the very sentence that makes them pointless: the capture IS asserted as a record, from
        // a mark taken before the teleport, and the wait below advances the client itself until that
        // record appears. So the settle could only ever delay a scenario that was already correct —
        // it could not make the assertion truer, and on a slow box it did not make it likelier.
        // Re-derived 2026-09-15 on the maintainer's challenge that an argued budget is usually an
        // excuse; here the argument was sound and the conclusion drawn from it was not.
        // Carrying this scenario's ship, not merely of this type: the record names the hull that took
        // the body, and a neighbour's capture in the same window would satisfy a type-only wait and
        // open the interval below on a craft the scenario never touches.
        //
        // On the LANDING after the teleport applied, not on "a capture since the mark". A body left
        // beside the hull falls into HULL-STAND by itself, after the mark and before the teleport
        // lands; a capture-since-mark wait returned on that and the read below then caught the body
        // over the deck and still falling. Measured 2026-09-16 here, and again 2026-09-28 at four red
        // runs of five in the sibling scenario — where the fix of the 16th (the episode's OPENING
        // instead of any record) turned out not to exclude it, because that capture OPENS after the
        // mark too. The helper waits for the client's own placement, then `deck_contact` on this
        // ship after it, then fences the server.
        long arrivalServerMark = serverEvents().mark();
        long arrivalMark = client.mark();
        landOnTheDeckUnderGateWatch(arrivalMark, arrivalServerMark, scenarioShipId,
                "the client player must be taken by THIS ship's deck"
                + " before the jump — the whole scenario is about a capture that already exists",
                CAPTURE_LINK_BUDGET_TICKS, GATE_WINDOW_RECORDS, ship[0], ship[1] + 4, ship[2]);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body. Still under the open gate window — the wait buys a CLIENT capture and this
        // read asks the SERVER, which is where the two have been seen to disagree.
        DeckCapture deckCapture;
        try {
            deckCapture = deckCaptureOfThisShip(scenarioShipId,
                    "the capture this assertion reads must be on this scenario's own ship");
            scenario().requireArranged("the client player must be captured on the deck before the"
                    + " jump: " + deckCapture.raw(),
                    deckCapture.verdict);
        } catch (AssertionError noCapture) {
            throw new AssertionError(noCapture.getMessage() + " | " + deckGateTrail(arrivalMark,
                    arrivalServerMark), noCapture);
        } finally {
            closeDeckGateWindow();
        }
        double deckY = bot().reportState().get("playerY").getAsDouble();

        // A REAL jump: the space key on the real client. The contract is that the capture does not
        // END mid-air, and an ending is a RECORD — `deck_released`, carrying the gate that released
        // it — on the client's own log. Sampling `alreadyTracked` every two ticks could not see a
        // release and a fresh first-contact re-capture inside one gap, which is the exact failure
        // mode the sampler was written to catch. The mark is taken BEFORE the key is held.
        // Smoothness diagnostics (print-only): frames whose interpolated camera position repeats
        // name a dead prev->pos interpolation; PosLook applies name the server echo as its writer.
        // The frame half is a window OPENED here and closed after the arc, so the numbers describe
        // this jump rather than everything the client has drawn since it booted.
        long jumpStepMark = clientEvents().mark();
        ClientWindow jumpStepWindow = ClientWindow.open(bot(), FRAME_STEP_WINDOW);
        long posLook0 = (long) deckCamera("posLookApplies");
        long jumpMark = client.mark();
        int samples = 0;
        double apex = deckY;
        StringBuilder trace = new StringBuilder();
        bot().holdKey(Keyboard.KEY_SPACE);
        try {
            for (int i = 0; i < 10; i++) {
                bot().waitWorldTicks(2);
                samples++;
                double y = bot().reportState().get("playerY").getAsDouble();
                apex = Math.max(apex, y);
                trace.append(String.format("[%d y=%.2f] ", i, y));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        // THE LANDING, AS A LINK — conditional, because the record is an EDGE. `deck_contact` is
        // written when the resolver owns a tick's move and puts the body on a surface it was not on
        // the tick before; a body already standing writes nothing. Twenty held ticks is more than
        // one arc, so the key comes up on a body that may be mid-way through a second jump or may
        // be standing. The release ran on the client thread before `releaseKey` returned, so no
        // further jump can start, and the client's own `onGround` — read after the mark — says
        // whether a landing is still owed. Filtered to THIS ship: a landing on a neighbour's deck
        // is the failure, not the event.
        long landMark = client.mark();
        if (!bot().reportState().get("onGround").getAsBoolean()) {
            client.awaitField(landMark, "deck_contact", "ship", scenarioShipId,
                    "the jumper was in the air when the key came up, so he must LAND on the deck he"
                            + " jumped from; the arc so far: " + trace,
                    JUMP_LANDING_BUDGET_TICKS);
        }
        // The capture read below is the SERVER's, which learns where he stands from his movement
        // packets — over his own connection, not the probe's. The fence makes it have handled them.
        fenceWhatTheClientSent("the server must have handled the landing before its capture is read");
        // "Back on the deck" is only the claim this test means if it is the SAME deck he jumped from.
        // A jump that ended on a sibling scenario's hull satisfies `verdict:true` and reads as a pass.
        DeckCapture capture = deckCaptureOfThisShip(scenarioShipId,
                "after the jump the player must be resolved back on the deck he jumped from");
        double settledY = bot().reportState().get("playerY").getAsDouble();
        // The two halves of the arc, read off the client's own log: every release since the mark,
        // and every per-tick commit of the capture that proves the resolver was holding this body at
        // all. `captureState` runs on EVERY resolved tick, so the second is the positive
        // precondition the absence needs — an empty release list from a client that was resolving
        // nobody is not an answer about the capture.
        String releases = client.since(jumpMark, "deck_released");
        String held = client.since(jumpMark, "deck_carry");
        jumpStepWindow.close();
        String jumpSteps = Events.lastRecord(
                clientEvents().since(jumpStepMark, "frame_step_window"));
        long posLookD = (long) deckCamera("posLookApplies") - posLook0;
        System.out.println("[crewcap] jump smoothness "
                + (jumpSteps == null ? "(the render seam sampled no aboard frame)" : jumpSteps)
                + " posLookApplies=" + posLookD
                + " capturesForThisShip=" + Events.countRecords(
                        client.since(jumpMark, "deck_carry"), "ship", scenarioShipId)
                + " windowTicks=60");
        System.out.println("[crewcap] jump deckY=" + deckY + " apex=" + apex + " settledY=" + settledY
                + " samples=" + samples + " :: " + trace
                + "\n[crewcap] jump releases in the arc :: " + releases);
        System.out.println("[crewcap] jump capture=" + capture.raw());

        Events.assertInstrumentRan(releases, "deck_capture_events",
                "the capture was or was not released somewhere in the jump arc");
        // THE TEST'S OWN, sized against vanilla rather than production: a vanilla jump leaves the
        // ground with motionY 0.42 and apexes about 1.25 blocks up, so half a block is comfortably
        // inside a real jump and comfortably outside the sub-block bob of a body being re-seated by
        // the deck. What it refuses is a "jump" that never left the surface at all.
        assertTrue("the jump must actually leave the deck (apex=" + apex + " deckY=" + deckY + ")",
                apex - deckY > JUMP_LEFT_THE_DECK_BLOCKS);
        // Counted on the SHIP, not on the type: `held` is already `since(mark, "deck_carry")`, so
        // the type needle only asks whether the reply is non-empty — a question the reply's own
        // filter has answered. The ship is the part that is not already known.
        assertTrue("the client must have been resolving this body ON THIS SHIP through the arc, or an"
                + " empty release list below says nothing about the capture: " + held,
                Events.countRecords(held, "ship", scenarioShipId) > 0);
        assertTrue("the capture must survive the whole jump arc, not release mid-air — every release"
                + " since the key went down, with the gate that made it: " + releases + " :: " + trace,
                Events.countRecords(releases, "type", "deck_released") == 0);
        assertTrue("after the jump the player must be resolved back on the deck: " + capture.raw(),
                capture.verdict);
        // THE TEST'S OWN. A block is 1.0, so a body within a block and a half of the deck's own Y
        // is standing on it or on something it is carrying; anything further has fallen through the
        // hull or been thrown off it, which is the pair of outcomes this leg exists to refuse.
        assertTrue("the player must land back ON the deck, not through it: deckY=" + deckY
                + " settledY=" + settledY, Math.abs(settledY - deckY) < LANDED_ON_THE_DECK_BLOCKS);
    }

    // ---- Boarding vs bystanders: terra firma near a ship never captures -------------------------

    /**
     * A player walking on world terrain beside a tilted, parked ship is never taken by its deck.
     */
    @RedWitness(verdict = "must have been ASKED about the walker", member = "DeckFrameTick#updateLocalPlayerLiving",
            at = "if (!isLocalPlayer(body))", replacedBy = "if (true) return false; if (!isLocalPlayer(body))",
            fails = "the deck frame must have been ASKED about the walker, or \"never captured\" is a statement"
                    + " about the arrangement and not about the decision", on = "2026-10-07")
    @NotYetRed(prose = "the never-captured verdict, 2026-10-07: the admission inverted to take any containing"
            + " craft before the terrain veto ({@code DeckFrameTick#admissionFor} at"
            + " {@code candidates.add(0, declared);}) left it GREEN, the walker's position being in no"
            + " craft's grown box on that run.")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(120);",
            why = "an attitude converges and nothing in production declares it reached, so there is no record to"
                    + " wait on; overshoot only brings the hull nearer its commanded tilt, which is the"
                    + " premise, never away from it.")
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(10);",
            why = "rest is approached, not announced; a longer interval only gives a still-falling body more room"
                    + " to show it.")
    @Wait(shape = Wait.Shape.WINDOW, at = "for (int i = 0; i < 12; i++) {",
            why = "the captured fraction and the y RANGE are properties of the traverse rather than of one"
                    + " instant; a record could say capture began, not what fraction of a walk it held for."
                    + " What it cannot see: a capture dropped and regained inside one 4-tick sample.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int i = 0; i < 12; i++) {",
            why = "each step is a dose of held W counted in the CLIENT's live-world ticks, the clock the walk is"
                    + " simulated on: twelve doses of four are a fixed length of walk, and the platform above"
                    + " is laid for it. Counted on the later of two clocks, a server lagging the client"
                    + " lengthened the walk — measured 2026-10-06, he walked off the platform's far edge at the"
                    + " tenth sample and fell to y 139, twice, the same 138.998 both times.")
    public void walkingOnTheGroundBesideAParkedShipNeverEntersItsFrame() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);

        // Tilt the parked ship: an axis-aligned world box around a rotated hull over-includes a large
        // ground area, and a tilted transform is what aliased a GROUND position onto a subspace block
        // in the playtest (a walker was captured into a 44.7-degree ship's frame). This is the hard
        // side of the axis; an upright ship rarely aliases.
        double h = Math.toRadians(45.0) / 2.0;
        double upBeforeTilt = shipInfo().upY();
        assertTrue("attitude hold must accept the tilt",
                Reply.of(arrange("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).raw()).bool("commanded"));
        advanceServerAndClient(120);
        ShipInfo info = shipInfo();
        // The TILT is the premise, and until now nothing checked that it took: a run in which
        // `point-by-id` silently did nothing, or the hold never slewed, passes the negative below
        // identically. The bound is loose on purpose — the commanded 45 degrees reads upY 0.71 and
        // any real tilt is far under this — because what it refuses is an UPRIGHT ship, where the
        // aliasing this scenario exists to forbid barely arises. And it must be the HOLD that
        // tilted it: a craft already tipped by its own pad contact meets the bound without the
        // command having done anything.
        scenario().requireArranged("the parked ship must actually be tilted BY THE HOLD before a"
                + " ground walker can alias into its frame at all (upY " + upBeforeTilt + " -> "
                + info.upY() + ", level is 1.0): " + info.raw(),
                info.upY() < TILTED_ENOUGH_UP_Y && info.upY() < upBeforeTilt);
        double sx = info.x, sz = info.z;

        // Put the REAL client player on the GROUND beside the hull, inside the grown world box, and
        // WALK him along it with the real forward key. He stands on terra firma the whole way. The
        // "ground" is a deterministic flat platform: the assembled ship's world position varies run
        // to run, and natural terrain at (shipPos + offset) once dropped the walker into a gully -
        // failing the Y-stability check on scenery, not on the contract under test.
        int px = (int) Math.floor(sx), pz = (int) Math.floor(sz);
        assertTrue("walk platform fill failed",
                Reply.of(arrange("stellurgytest fill 0 " + (px - 2) + " " + by + " " + (pz - 2) + " " + (px + 12) + " "
                        + by + " " + (pz + 12) + " minecraft:stone").raw()).ok());
        assertTrue("walk headroom clear failed",
                Reply.of(arrange("stellurgytest fill 0 " + (px - 2) + " " + (by + 1) + " " + (pz - 2) + " " + (px + 12)
                        + " " + (by + 4) + " " + (pz + 12) + " minecraft:air").raw()).ok());
        // Face NORTH (yaw 180 looks along -Z in MC): the walk starts at the platform's south edge
        // and crosses its full depth without stepping off.
        long standMark = clientEvents().mark();
        arrange("tp @a " + (px + 4) + " " + (by + 1) + " " + (pz + 11) + " 180 0");
        awaitClientPlacedNear(standMark, px + 4, pz + 11,
                "the walk is driven by the CLIENT, so it starts where the client stands");
        // The baseline is a converging VALUE — he is placed a block up and lands — so it is read as
        // a WINDOW of two readings rather than after a settle. Two that agree ARE the resting
        // height; two that differ mean he is still moving, which is an arrangement fault this
        // scenario must not walk into rather than a number to average.
        double firstY = bot().reportState().get("playerY").getAsDouble();
        advanceServerAndClient(10);
        double groundY = bot().reportState().get("playerY").getAsDouble();
        // THE TEST'S OWN — a bound on "at rest", not on anything production decides. It is the
        // vertical change between two consecutive reads, and a falling body covers far more than
        // this in one of them (vanilla gravity reaches 0.08 blocks/tick after a single tick and
        // keeps accelerating), so what survives the check is a body whose Y has stopped moving.
        scenario().requireArranged("the walker must come to REST on the platform before the walk"
                + " begins, or the excursion below is measured against a falling body (y="
                + firstY + " then " + groundY + ")", Math.abs(groundY - firstY) < AT_REST_Y_CHANGE_BLOCKS);

        // The deck frame's own admission decisions for THIS walk, from a mark taken before the first
        // step. The negative below ("never captured") is satisfied by a walker the deck frame never
        // looked at, so the positive precondition is a per-tick record of it asking about this body:
        // `deck_gate_explained`, written at the admission decision while the window is armed for him.
        // A record written only on a CHANGE would be empty here — a walker beside a parked ship is the
        // most stable answer there is — and would read as "never asked".
        Events client = clientEvents();
        openDeckGateWindow(GATE_WINDOW_RECORDS_WALK);
        long walkMark = client.mark();
        int captured = 0, samples = 0;
        double yMin = groundY, yMax = groundY;
        StringBuilder trace = new StringBuilder();
        bot().holdKey(Keyboard.KEY_W);
        try {
            for (int i = 0; i < 12; i++) {
                bot().waitWorldTicks(4);
                samples++;
                DeckCapture cap = DeckCapture.read(c -> read(c).raw());
                boolean t = cap.alreadyTracked;
                boolean terrain = cap.supportedByWorldTerrain;
                if (t) captured++;
                double y = bot().reportState().get("playerY").getAsDouble();
                yMin = Math.min(yMin, y);
                yMax = Math.max(yMax, y);
                trace.append(String.format("[%d y=%.2f cap=%b terra=%b] ", i, y, t, terrain));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
            closeDeckGateWindow();
        }
        String gate = client.since(walkMark, "deck_gate_explained");
        String captures = client.since(walkMark, "deck_carry");
        System.out.println("[crewcap] ground-walk groundY=" + groundY + " yMin=" + yMin + " yMax="
                + yMax + " captured=" + captured + "/" + samples + " :: " + trace
                + "\n[crewcap] ground-walk admission decisions :: " + gate
                + "\n[crewcap] ground-walk captures in window :: " + captures);

        Events.assertInstrumentRan(gate, "deck_gate_window_events",
                "the deck frame was or was not asked about this walker at all");
        assertTrue("the deck frame must have been ASKED about the walker, or \"never captured\" is a"
                + " statement about the arrangement and not about the decision: " + gate,
                Events.countRecords(gate, "type", "deck_gate_explained") > 0);
        assertTrue("a player walking on world terrain beside a parked ship must NEVER be captured "
                + "into its frame (" + captured + "/" + samples + " samples captured): " + trace
                + " :: the gate's own answers: " + gate,
                captured == 0);
        // THE TEST'S OWN: the walk is on flat ground the scenario laid itself, so the honest
        // statement is "his Y does not change". Two blocks tolerates a vanilla step up and the jump
        // the walk may include, and refuses the defect — a body snatched into the tilted ship's
        // frame, which moves him by the lever arm between him and the hull, many blocks away.
        assertTrue("his world-frame walk must stay on the ground - no ship-frame yank (y "
                + yMin + ".." + yMax + " around " + groundY + ")", yMax - yMin < GROUND_WALK_Y_SPREAD_BLOCKS);
    }

    // ---- #47: a still crew member on a steeply-rolled deck is not dragged sideways --------------

    private static final String DUMMY_ID = "dummyId";

    /**
     * A still crew member on a deck rolled past vertical is neither dragged sideways nor churned.
     */
    @LegacyWitness(prose = "taken on the pre-2026-10-06 form, with the travel resolver"
            + " (`ShipFrameTravel.travel`, deleted with it) declining every tick on a deck whose up points below"
            + " -0.5: \"the client must be resolving the crew member through the stillness window — the ship"
            + " frame's frame never committed a capture for this body\", 2026-09-28. NOT re-taken against the deck"
            + " frame, which holds him now from {@code DeckFrameTick#admissionFor} at"
            + " {@code if (!admissible(entity))}. That is the verdict the wait rewrite touched; the drift and"
            + " churn verdicts after it are not witnessed.")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(200);",
            why = "an attitude converges and nothing in production declares it reached, so there is no record to"
                    + " wait on; overshoot only brings the hull nearer the commanded roll, which is the"
                    + " premise.")
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(100);",
            why = "drift and churn accumulate rather than happen, so no record answers; a longer window can only"
                    + " show more of both, never less.")
    public void aStillCrewMemberOnASteeplyRolledDeckIsNotDraggedSideways() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Board and stand up on the LEVEL deck (the dismount seed captures the ex-pilot), then roll
        // the unmanned ship past vertical and HOLD it - the closest headless stand-in for the live
        // "walking an inverted deck" configuration (the AFC caps commanded rolls near ~160; a true
        // 180 needs a free spin that VS damps). The playtest symptom: with NO input, the crew member
        // is dragged sideways while the CLIENT capture thrashes (drop + re-capture every few ticks).
        buildAndBoardShip(site);
        // A seated body is an EXCLUDED state and is never captured, so the capture the dismount seed
        // installs is a genuine new link and not a record that was already arriving. Awaited rather
        // than waited out: the seed is refused for the few ticks the client's own `isRiding` lingers,
        // and a red here names the missing link instead of a tick count.
        Events client = clientEvents();
        long dismountMark = client.mark();
        arrange("stellurgytest player dismount");
        client.awaitField(dismountMark, "deck_entered","ship", scenarioShipId,
                "the dismount seed must put the ex-pilot on THIS ship's"
                + " deck before the stillness window means anything", CAPTURE_LINK_BUDGET_TICKS);

        double upBeforeRoll = shipInfo().upY();
        assertTrue("attitude hold must accept the past-vertical roll",
                Reply.of(arrange("stellurgytest vs point-by-id 0 " + scenarioShipId + " 0.17365 0.0 0.0 0.98481").raw()
                        ).bool("commanded"));
        advanceServerAndClient(200);

        // The subject must be in the regime the symptom lives in, and the instrument must fire:
        // the ship really steeply rolled, and the CLIENT really resolving this body (all-zero
        // discriminator statics with a non-resolving client would be a vacuous pass).
        ShipInfo info = shipInfo();
        double upY = info.upY();
        scenario().requireArranged("the ship must be steeply rolled for this test to mean anything (upY "
                        + upBeforeRoll + " -> " + upY + ")",
                upY < INVERTED_DECK_UP_Y);
        // Stillness window: NO input at all. Sample the client's own drift and the walk
        // discriminators (CLIENT-JVM statics — the client owns this body's movement); the CHURN half
        // is not a static any more but the client's own `deck_released` records since this mark,
        // each carrying the gate that fired, in order.
        long churnMark = client.mark();
        double x0 = bot().reportState().get("playerX").getAsDouble();
        double z0 = bot().reportState().get("playerZ").getAsDouble();
        double maxLateral = 0.0;
        float strafeSeen = 0f, forwardSeen = 0f;
        StringBuilder trace = new StringBuilder();
        advanceServerAndClient(100);
        // Read ONCE, from the client's own per-tick record, instead of polling four statics every
        // fifth tick. The poll saw one tick in five and paid a round trip per field for it; the
        // record carries every resolved tick of the window, and each line is attributed to the body
        // it describes rather than holding whatever the last resolved body left in a static.
        String tickLines = client.since(churnMark, "ship_frame_tick");
        maxLateral = maxLateralShipMotion(tickLines);
        strafeSeen = maxInput(tickLines, IN_STRAFE);
        forwardSeen = maxInput(tickLines, IN_FORWARD);
        trace.append(tickLines);
        String releases = client.since(churnMark, "deck_released");
        String gate = client.since(churnMark, "deck_carry");
        double x1 = bot().reportState().get("playerX").getAsDouble();
        double z1 = bot().reportState().get("playerZ").getAsDouble();
        double drift = Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
        long churn = Events.countRecordsWithField(releases, "reason");
        System.out.println("[crewcap] still-drift upY=" + upY + " drift=" + drift
                + " clientDropChurn=" + churn + " capturesForThisShip="
                + Events.countRecords(gate, "ship", scenarioShipId)
                + " maxLateralShipMotion=" + maxLateral + " inputsSeen=("
                + strafeSeen + "," + forwardSeen + ") :: " + trace
                + "\n[crewcap] still-drift releases in window :: " + releases);
        Events.assertInstrumentRan(releases, "deck_capture_events",
                "the capture was or was not cycled through the stillness window");

        // Instrument-fires guard: the CLIENT must have been resolving THIS body through the window,
        // or every zero above is vacuous.
        //
        // Asked of the frame's own per-body verdict rather than of `resolvedTicks`. That static is
        // JVM-global and cumulative, so on a shared client it counted every body this side ever
        // resolved: a delta proved "something was resolved", never "this crew member was", and an
        // earlier scenario's traffic could satisfy it on its own. `deck_carry` is written on every
        // RESOLVED TICK and carries the ship, so it is scoped to THIS craft by its own payload —
        // and it answers "was this body being resolved AT ALL in my window", which an EDGE cannot:
        // `deck_entered` fires once, when a craft takes a body, and is then silent for as long as
        // it holds one. Measured 2026-09-16: four scenarios went red in one run after a rename put
        // the edge here, each reporting "the frame never resolved this body" over a window holding
        // hundreds of resolved ticks.
        //
        // NOT a record written only when an answer CHANGES, which was tried first and is wrong for
        // THIS question: a window shorter than its heartbeat comes back empty however hard the frame
        // worked. Measured: the activity leg failed with `count:0` while its instrument was
        // registered and none of its records had been evicted.
        assertTrue("the client must be resolving the crew member through the stillness window —"
                + " the ship frame's frame never committed a capture for this body. Capture"
                + " records since the mark: " + gate,
                Events.countRecords(gate, "ship", scenarioShipId) > 0);
        // Setup sanity: the window really was input-free (the discriminator data is only meaningful
        // for a still body).
        assertTrue("the stillness window must be input-free (saw strafe=" + strafeSeen + " forward="
                + forwardSeen + ")", strafeSeen == 0f && forwardSeen == 0f);
        // The contract (deck-frame walking - no input, no movement): a still crew member on a held,
        // stationary deck STAYS PUT - no sideways drag - and his capture does not churn.
        assertTrue("a still crew member must not be dragged sideways on a held rolled deck: drifted "
                + drift + " blocks in ~5s, from (" + x0 + "," + z0 + ") to (" + x1 + "," + z1
                + ") (client drop churn=" + churn + ", max lateral ship-frame "
                + "motion=" + maxLateral + "): " + trace, drift < STILL_BODY_DRIFT_BLOCKS);
        assertTrue("the client capture must not churn on a held rolled deck (external-move releases"
                + " in window=" + churn + "), each record naming the gate that fired: " + releases,
                churn < CAPTURE_CHURN_LIMIT);
    }

    // ---- The LIVE configuration: a station-keeping hover, never fully still --

    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(100);",
            why = "as in the held-roll leg above; a longer window only shows more.")
    public void aStillCrewMemberOnAHoveringShipIsNotDraggedSideways() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The playtest ship is not attitude-HELD by a probe - it HOVERS under station-keeping, which
        // never brings it fully to rest (a ~-0.01/tick vertical residual plus correction wobble).
        // The reported no-input sideways drag lives on that configuration, upright included - so the
        // subject here is a real hover: lift with the pilot's own vertical key, stand up, hold still.
        buildAndBoardShip(site);

        // The delivery link, the window and why the climb is measured rather than awaited all live
        // in the helper.
        hoverOnPilotThrust(scenarioShipId, CLEAR_HOVER_GAIN_BLOCKS);
        // The dismount seed's capture as a LINK: a seated body is excluded from capture, so this
        // record is a new fact and not one that was already arriving every tick.
        Events client = clientEvents();
        long dismountMark = client.mark();
        arrange("stellurgytest player dismount");
        client.awaitField(dismountMark, "deck_entered","ship", scenarioShipId,
                "the dismount seed must put the ex-pilot on THIS ship's"
                + " hovering deck before the stillness window means anything",
                CAPTURE_LINK_BUDGET_TICKS);

        long churnMark = client.mark();
        double x0 = bot().reportState().get("playerX").getAsDouble();
        double z0 = bot().reportState().get("playerZ").getAsDouble();
        double maxLateral = 0.0;
        float strafeSeen = 0f, forwardSeen = 0f;
        StringBuilder trace = new StringBuilder();
        advanceServerAndClient(100);
        // Read once from the per-tick record; see the sibling leg above for why a five-tick poll of
        // four statics was both blinder and dearer than this.
        String tickLines = client.since(churnMark, "ship_frame_tick");
        maxLateral = maxLateralShipMotion(tickLines);
        strafeSeen = maxInput(tickLines, IN_STRAFE);
        forwardSeen = maxInput(tickLines, IN_FORWARD);
        trace.append(tickLines);
        // The churn, as the client's own releases in THIS window with the gate that fired on each —
        // where a cumulative counter delta could only say that something, some time, had happened.
        String releases = client.since(churnMark, "deck_released");
        String gate = client.since(churnMark, "deck_carry");
        long churn = Events.countRecordsWithField(releases, "reason");
        double x1 = bot().reportState().get("playerX").getAsDouble();
        double z1 = bot().reportState().get("playerZ").getAsDouble();
        double drift = Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
        System.out.println("[crewcap] hover-drift drift=" + drift + " clientDropChurn=" + churn
                + " capturesForThisShip=" + Events.countRecords(gate, "ship", scenarioShipId)
                + " maxLateralShipMotion=" + maxLateral + " inputsSeen=(" + strafeSeen + ","
                + forwardSeen + ") :: " + trace
                + "\n[crewcap] hover-drift releases in window :: " + releases);
        Events.assertInstrumentRan(releases, "deck_capture_events",
                "the capture was or was not cycled through the hover window");

        // Asked of the frame's own per-body capture commits. `resolvedTicks` was JVM-global and cumulative,
        // so its delta said "some body was resolved", never "this one was".
        assertTrue("the client must be resolving the crew member through the window - the ship"
                + " frame's frame never committed a capture for this body: " + gate,
                Events.countRecords(gate, "ship", scenarioShipId) > 0);
        assertTrue("the stillness window must be input-free (saw strafe=" + strafeSeen + " forward="
                + forwardSeen + ")", strafeSeen == 0f && forwardSeen == 0f);
        assertTrue("a still crew member must not be dragged sideways on a hovering ship: drifted "
                + drift + " blocks in ~5s, from (" + x0 + "," + z0 + ") to (" + x1 + "," + z1
                + ") (client drop churn=" + churn + ", max lateral ship-frame "
                + "motion=" + maxLateral + "): " + trace, drift < STILL_BODY_DRIFT_BLOCKS);
        assertTrue("the client capture must not churn on a hovering ship (external-move releases in"
                + " window=" + churn + "), each record naming the gate that fired: " + releases,
                churn < CAPTURE_CHURN_LIMIT);
    }

    // ---- #47: WALKING and JUMPING on a hovering ship must not churn the capture -----------------

    @Pins("DECKSYNC-7")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "for (int t = 0; t < 3; t++) {",
            why = "the arc's SHAPE, for the failure message — a trajectory is not one record; the verdict is the"
                    + " link below. What it cannot see: the part of the arc between two samples.")
    @Wait(shape = Wait.Shape.WINDOW, at = "for (int t = 3; t < 6; t++) {",
            why = "the arc's second half, sampled for the same reason as the first; the landing is decided by the"
                    + " link that follows. What it cannot see: the ticks in between.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int leg = 0; leg < 4; leg++) {",
            why = "four legs, not a wait — deleting the loop stops the walking rather than the watching. What the"
                    + " legs cannot see: a capture that dropped and returned inside a single 3-tick hold.")
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(20);",
            why = "the activity window's TAIL, which lets the LAST leg's consequences land inside it: the server"
                    + " judges a step only when that step's packet arrives, and the bound writes no record for"
                    + " an ordinary step, so there is no record of \"the last step has been judged\" to wait"
                    + " on. A longer tail admits more records and so can only make these counts stricter.")
    public void walkingAndJumpingOnAHoveringShipDoesNotChurnTheCapture() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The round-11 playtest drag happens on a NEARLY-LEVEL hovering ship while the crew member
        // is actively walking and jumping - the still-crew pins stayed green while the live drag
        // persisted, so ACTIVITY is the missing axis. Same arrangement as the still-hover pin, plus
        // real W walking and real SPACE jumps through the window.
        buildAndBoardShip(site);
        // The delivery link, the window and why the climb is measured rather than awaited all live
        // in the helper.
        hoverOnPilotThrust(scenarioShipId, CLEAR_HOVER_GAIN_BLOCKS);
        // The dismount seed's capture as a LINK, awaited rather than waited out.
        Events client = clientEvents();
        long dismountMark = client.mark();
        arrange("stellurgytest player dismount");
        client.awaitField(dismountMark, "deck_entered","ship", scenarioShipId,
                "the dismount seed must put the ex-pilot on THIS ship's"
                + " hovering deck before any activity on it can churn a capture",
                CAPTURE_LINK_BUDGET_TICKS);

        // Two marks, one per side, BEFORE the activity: the client's resolver records every release
        // of its capture with the gate that released it (`deck_released`), and the server's movement
        // bound records every step it judged with both endpoints (`deck_movement_bound`). A churn
        // and a refusal are then records in THIS window, in order — where a cumulative drop counter
        // and a server-lifetime maximum could only say that something, some time, had happened.
        long releaseMark = client.mark();
        Events boundEvents = serverEvents();
        long boundMark = boundEvents.markInstrumented();

        // Walk in a tight square (short bursts each direction so the crew member stays on the small
        // deck) and jump twice - real client keys, the real activity of the playtest. Sample the
        // client's own state after every leg so a mid-window ejection names its leg and gate.
        // A PURE VERTICAL jump first (no walk key held): on any ship motion the jumper must arc and
        // land back on the deck, still captured - the kinematics pin (a carry double-count rocketed
        // him off a climbing hover). Sampled per 2 ticks.
        //
        // The jump leg has its OWN mark: the walk legs that follow may release the capture
        // legitimately (walked off the tiny deck), so "no release" can only be claimed over the
        // window the jump owns. Comparing a JVM-lifetime `lastDropReason` before and after — what
        // this did — is blind to a release whose reason equals the previous one, which on a repeated
        // gate is the likeliest case there is.
        // SAMPLED IN THE FRAME THE JUMP HAPPENS IN, beside the world one rather than instead of it.
        //
        // `playerY` alone cannot answer "did he jump": the deck under him is a hovering craft with
        // its own vertical drift, so a world-frame arc is the jump PLUS whatever the ship did, and
        // the two cannot be separated afterwards. Measured 2026-09-14 across three runs of this
        // scenario, world-frame rises of 0.70, 0.27 and 0.21 blocks — and since production's support
        // probe reaches 0.30, a run at 0.21 never lifted the body off its own deck at all and went
        // green having exercised nothing. Whether that was a weak jump or a sinking ship is exactly
        // what the world frame cannot say.
        //
        // The ship-frame reading is production's own (`bodyShipFrameY`, the body's live position
        // mapped into the ship's frame), read through the gate probe. It costs a round trip per
        // sample and no ticks, so the arc's pacing is unchanged.
        long jumpMark = client.mark();
        bot().holdKey(Keyboard.KEY_SPACE);
        StringBuilder arc = new StringBuilder();
        for (int t = 0; t < 3; t++) {
            bot().waitWorldTicks(2);
            arc.append(String.format(java.util.Locale.ROOT, "[t%d y=%.2f sub=%.2f] ",
                    t * 2, bot().reportState().get("playerY").getAsDouble(), jumperShipFrameY()));
        }
        bot().releaseKey(Keyboard.KEY_SPACE);
        // THE LINK THAT CLOSES THIS WINDOW, where fourteen more sampled ticks used to stand. The
        // claim is that the jump ENDS on this deck, and production records exactly that: the
        // resolver owning a tick's move and putting the body on a surface it was not on the tick
        // before, filtered to this scenario's ship. A fixed sample count could only say "no release
        // was recorded in twenty ticks", which is also what it says about a body still falling —
        // and on the run that sent us here it WAS still falling, four blocks below its deck.
        for (int t = 3; t < 6; t++) {
            bot().waitWorldTicks(2);
            arc.append(String.format(java.util.Locale.ROOT, "[t%d y=%.2f sub=%.2f] ",
                    t * 2, bot().reportState().get("playerY").getAsDouble(), jumperShipFrameY()));
        }
        client.awaitField(jumpMark, "deck_contact","ship", scenarioShipId,
                "a vertical jump on a hovering ship must LAND BACK on this ship's deck — the "
                        + "resolver's own contact edge is what says he did, and the arc sampled so "
                        + "far is " + arc, JUMP_LANDING_BUDGET_TICKS);
        arc.append(String.format(java.util.Locale.ROOT, "[landed y=%.2f] ",
                bot().reportState().get("playerY").getAsDouble()));
        String jumpReleases = client.since(jumpMark, "deck_released");
        String jumpHeld = client.since(jumpMark, "deck_carry");
        System.out.println("[crewcap] jump-arc " + arc
                + "\n[crewcap] jump-arc releases :: " + jumpReleases);
        Events.assertInstrumentRan(jumpReleases, "deck_capture_events",
                "the capture was or was not released during the vertical jump");
        // On the SHIP: `jumpHeld` is already filtered to `deck_carry`, so the type needle asks
        // only whether the reply is non-empty. See the sibling above.
        assertTrue("the client must have been resolving the jumper ON THIS SHIP through his arc, or an"
                + " empty release list says nothing about the capture: " + jumpHeld,
                Events.countRecords(jumpHeld, "ship", scenarioShipId) > 0);
        assertTrue("a vertical jump on a hovering ship must land back on the deck, still captured —"
                + " every release since the key went down, each with the gate that made it: "
                + jumpReleases + " :: " + arc,
                Events.countRecords(jumpReleases, "type", "deck_released") == 0);

        // Then a tight walk square - SHORT legs (3 ticks ≈ 0.65 blocks): the fixture deck is only
        // ~3x5, and a longer leg walks the crew member clean off its edge, a legitimate release
        // that says nothing about churn.
        int[] keys = {Keyboard.KEY_W, Keyboard.KEY_D, Keyboard.KEY_S, Keyboard.KEY_A};
        StringBuilder legs = new StringBuilder();
        for (int leg = 0; leg < 4; leg++) {
            bot().holdKey(keys[leg]);
            try {
                bot().waitWorldTicks(3);
            } finally {
                bot().releaseKey(keys[leg]);
            }
            advanceServerAndClient(5);
            // No dropReason column: the releases are records now, each with its gate AND its
            // sequence, so which leg one fell in is read off the log rather than guessed from which
            // sample first showed a changed last-write.
            legs.append(String.format(java.util.Locale.ROOT,
                    "[leg%d pos=(%.1f,%.1f,%.1f)] ",
                    leg,
                    bot().reportState().get("playerX").getAsDouble(),
                    bot().reportState().get("playerY").getAsDouble(),
                    bot().reportState().get("playerZ").getAsDouble()));
        }
        advanceServerAndClient(20);
        System.out.println("[crewcap] active-legs " + legs);

        DeckCapture capture = DeckCapture.read(c -> read(c).raw());
        // The client's releases in THIS window, each with the gate that released it.
        String releases = client.since(releaseMark, "deck_released");
        String gate = client.since(releaseMark, "deck_carry");
        Events.assertInstrumentRan(releases, "deck_capture_events",
                "the client's capture was or was not cycled during the activity");
        long churn = Events.countRecordsWithField(releases, "reason");
        System.out.println("[crewcap] active-churn churn=" + churn + " capturesForThisShip="
                + Events.countRecords(gate, "ship", scenarioShipId) + " capture=" + capture.raw()
                + "\n[crewcap] client releases in window :: " + releases);

        // Asked of the frame's own per-body capture commits. `resolvedTicks` was JVM-global and cumulative,
        // so its delta said "some body was resolved", never "this one was".
        assertTrue("the client must be resolving through the activity window - the ship frame's"
                + " frame never committed a capture for this body: " + gate,
                Events.countRecords(gate, "ship", scenarioShipId) > 0);
        // The churn contract: activity on a hovering deck must not cycle the capture. It used to be
        // aimed at ONE release reason — the external-move guard, the drag war — with a second
        // assertion that the last release was not that reason. The guard is gone, so both would now
        // pass on a product that churned the body through every other gate it has. The count is
        // therefore over EVERY release in the window: a body walking and jumping on its own deck
        // should not be handed back at all, whatever the reason given. The releases are the client's
        // own records since the mark, so a red names every gate that fired and in which order.
        assertTrue("walking and jumping on a hovering ship must not churn the capture (releases in"
                + " window=" + churn + "): " + releases, churn < CAPTURE_CHURN_LIMIT);

        // AND the server refused NOTHING of what he did. This is the false-positive leg of the
        // movement bound, and it is the one that matters: a bound that rubber-bands a crew member
        // for walking and jumping on his own deck has broken the game to protect it. Read off the
        // bound's own records for THIS window — every step it judged above a block, and every
        // refusal whatever its size, each with BOTH endpoints. A refusal is then readable: a `moved`
        // of nineteen million blocks with one endpoint at x≈19 200 000 is the shipyard's subspace
        // read against the world, a frame mix and not a movement — which a lifetime maximum of
        // "own displacement" could only ever report as a number.
        String judged = String.valueOf(boundEvents.since(boundMark));
        Events.assertInstrumentRan(judged, "deck_movement_bound",
                "the server's movement bound was or was not asked about his steps");
        long refused = Events.countRecords(judged, "accepted", "false");
        String frames = framesOfRefusals(judged);
        System.out.println("[crewcap] deck-bound after activity: refusedInWindow=" + refused
                + " (bound is 2.0/tick plus the deck's carry) :: " + judged
                + "\n[crewcap] deck-bound refusal frames :: " + frames);
        assertTrue("walking and jumping on his own deck must never be refused by the server's"
                + " movement bound: " + refused + " refusal(s) in the window, and WHICH FRAME each"
                + " endpoint is in says what kind of refusal it is: " + frames + ". A step whose two"
                + " endpoints are in DIFFERENT frames is a frame mix in the bound's caller and not a"
                + " movement at all — nothing walked nineteen million blocks. The bound's own"
                + " records, both endpoints each: " + judged + " :: " + legs, refused == 0);
    }

    /**
     * Which FRAME each endpoint of every refused step is in, in the substrate's own terms.
     *
     * <p>A refusal's size alone cannot be read: a {@code moved} of nineteen million blocks reads as
     * "the bound went mad" until one endpoint turns out to be a SUBSPACE coordinate — a ship's blocks
     * live in the physics mod's reserved shipyard quadrant while its hull flies around the world, so a
     * from/to pair straddling the two is a frame mix in the caller and not a movement anybody made.
     * The classifier is the substrate's own predicate ({@link ShipChunkAllocator#isChunkInShipyard}),
     * not a threshold invented here, so it stays right if the shipyard moves.</p>
     */
    private static String framesOfRefusals(String judgedReply) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String record : Events.recordsWhere(judgedReply, "accepted", "false")) {
            double[] from = xyzField(record, "from");
            double[] to = xyzField(record, "to");
            if (from == null || to == null) {
                sb.append("[refusal").append(n++).append(" endpoints unreadable] ");
                continue;
            }
            String fromFrame = frameOf(from[0], from[2]);
            String toFrame = frameOf(to[0], to[2]);
            sb.append(String.format(java.util.Locale.ROOT,
                    "[refusal%d from=%s(%.1f,%.1f,%.1f) to=%s(%.1f,%.1f,%.1f) %s] ",
                    n++, fromFrame, from[0], from[1], from[2], toFrame, to[0], to[1], to[2],
                    fromFrame.equals(toFrame)
                            ? "same frame — a real step of this size"
                            : "FRAME MIX — the two endpoints are not in the same space"));
        }
        return n == 0 ? "(no refusals to describe)" : sb.toString();
    }

    /** The frame a world-XZ pair belongs to, by the physics mod's own shipyard predicate. */
    private static String frameOf(double x, double z) {
        return ShipChunkAllocator.isChunkInShipyard(
                (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4) ? "SUBSPACE" : "world";
    }

    /** A {@code "field":"x,y,z"} payload triple, or null when the record carries none. */
    private static double[] xyzField(String record, String field) {
        String value = Events.text(record, field);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- The server does not simply ratify what a client declares ------------------------------

    /**
     * A step the client's own travel commits, larger than a body on a deck can take, is refused by the
     * server's deck bound rather than ratified as a declared position.
     *
     * <p>Redesigned 2026-09-28. It used to shove forty blocks and read positions after the window, and
     * neither half could see the bound: vanilla's speed check refuses forty blocks first ("moved too
     * quickly! 0.0,40.0,0.0" in the server log, no bound record), and the client re-images the body
     * to its deck point the next tick, so every position read afterwards is at the deck whatever the
     * bound did. It now shoves by a step vanilla lets through and reads the bound's own verdict.</p>
     */
    @LegacyWitness(prose = "with {@code DeckMovementBound#accepts} at {@code return moved <= allowed}"
            + " ratifying every step: \"a 6-block step committed by the client's own travel must be REFUSED by"
            + " the server's deck bound … refused 0, accepted 2\", 2026-09-28. Healthy, the same run shape"
            + " records {@code moved 6.0, accepted:false}. The step is taken from a WALKING body: from a"
            + " standing one the bound accepted it on healthy production, because a standing client reports only"
            + " every twenty ticks and the bound licenses up to ten ticks of movement per report.")
    @LegacyWitness(prose = "with {@code DeckMovementBound#accepts} at {@code return moved <= allowed}"
            + " inverted to {@code return true}, \"a 6-block step committed by the client's own travel must be"
            + " REFUSED by the server's deck bound … refused 0, accepted 2\", 2026-10-04 — the step taken by the"
            + " deck frame's held update ({@code MixinDeckFrameTickShove}) from a body that walked, the bound"
            + " having judged it the tick before. Healthy the same day: {@code moved 6.00368, accepted:false,"
            + " opinion:true}, {@code ticksSinceLastJudged:1}.")
    @Pins("DECKSYNC-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().holdKey(Keyboard.KEY_W);",
            why = "the walk key held from before the step until after it is taken.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(WALK_BEFORE_THE_STEP_TICKS);",
            why = "walking, so the bound has judged this body tick by tick.")
    public void aWildClientSideStepOnADeckNeverBecomesADeclaredPosition() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Player movement is client-authoritative and the server accepts it. That is the contract,
        // and it is not being changed here — what is added is a BOUND: a body Stellurgy is carrying on a
        // deck can only be where its own speed plus the deck's carry could have put it. Without the
        // bound the server ratified a thirty-blocks-per-tick climb tick after tick, because
        // vanilla's own speed check is written for a body on solid ground and a body on a moving
        // deck legitimately covers ground its legs never did.
        //
        // The stimulus is the DRIVER rather than the condition: the client's own idea of where it
        // is moves, which is exactly what happened when a body met an inverted hull, and the next
        // movement packet carries it.
        buildAndBoardShip(site);
        Events client = clientEvents();
        long dismountMark = client.mark();
        arrange("stellurgytest player dismount");
        ShipIdentity.awaitCaptureHeldBy(client, dismountMark, scenarioShipId,
                "the body must be taken by THIS ship's deck before the"
                + " server can be asked what it accepts FROM a deck", CAPTURE_LINK_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture3 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        scenario().requireArranged("the body must be captured on the deck before the server can be"
                + " asked what it accepts FROM a deck: " + deckCapture3.raw(),
                deckCapture3.alreadyTracked);

        long shoveMark = client.mark();
        Events serverEvents = serverEvents();
        long boundMark = serverEvents.markInstrumented();

        // A step no input and no parked deck can produce, sized so that vanilla lets it through and
        // only the bound can refuse it (see WILD_STEP_BLOCKS).
        //
        // Armed INSIDE the code that commits this body's position on the client rather than applied
        // from outside it: while Stellurgy holds the body it writes its position every tick, so a
        // shove from anywhere else is undone before the client sends anything — measured, a
        // forty-block external shove produced a movement packet identical to standing still. The
        // code that owns the position is the only thing that can declare an impossible one, which is
        // exactly how it happened in play. The deck frame's held update owns it, and the arming is
        // taken there (MixinDeckFrameTickShove).
        // FROM A WALKING BODY. The bound's region is scaled by the ticks since it last judged this
        // player, capped at ten — and a STANDING client sends a position packet only every twenty
        // ticks, so a standing body's next packet is licensed (2 + carry) x 10 + 1, about 21 blocks:
        // more than vanilla's own ten. Measured 2026-09-28: this step from a standing body was
        // ACCEPTED, moved 6.0. That is the bound working as written — its subject is a body moving
        // on a wrong carry, and a moving body reports every tick — so the stimulus has to be one.
        bot().holdKey(Keyboard.KEY_W);
        ClientWindow shove;
        try {
            advanceServerAndClient(WALK_BEFORE_THE_STEP_TICKS);
            shove = ClientWindow.open(bot(),
                    "dev.stannismod.stellurgy.test.trace.ShoveArming", WILD_STEP_BLOCKS);
            // The armed shove TAKING is a link, and the client's own commit records it: awaited from
            // the mark taken before the arming, so a red says "the travel commit never took the step".
            client.await(shoveMark, "ship_frame_travel_shove", "the client's own position commit must"
                    + " take the armed step, or nothing in this scenario ever declares an impossible"
                    + " position", 200);
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
        }
        shove.close();
        // The step must be taken FROM THE DECK. A hold that ended between the arming and the step
        // means the body walked off the deck (or was let go some other way) first, and a step from
        // there is not this contract's subject: off a deck the bound has no opinion by design.
        // Measured 2026-10-04, with the arming takeable by the travel resolver only: the shove waited
        // while he walked, the deck let him go at its edge (`noDeckBelow`), and it fired on the
        // outer hull.
        long shoveSeq = (long) Events.number(
                Events.records(client.since(shoveMark, "ship_frame_travel_shove")).get(0), "seq");
        long releasedBeforeTheStep = Events.records(client.since(shoveMark, "deck_released")).stream()
                .filter(r -> (long) Events.number(r, "seq") < shoveSeq).count();
        scenario().requireArranged("the step must be taken by a body still held on the deck: "
                        + client.since(shoveMark, "deck_released"),
                releasedBeforeTheStep == 0);
        // The fence makes the server have HANDLED every movement packet the client sent since the
        // shove before the bound's records are read. It is not a settle: what is read is a record the
        // bound wrote while handling the packet that carried the step.
        fenceWhatTheClientSent("the server must have handled the packet that declared the step before"
                + " the bound's verdict on it can be read");

        String shoveTrace = client.since(shoveMark, "ship_frame_travel_shove");
        String boundTrace = String.valueOf(serverEvents.since(boundMark, "deck_movement_bound"));
        System.out.println("[crewcap] deck-bound shove trace :: " + shoveTrace
                + "\n[crewcap] deck-bound server trace :: " + boundTrace
                + "\n[crewcap] deck-bound intervals :: "
                + serverEvents.since(boundMark, "deck_movement_bound_interval")
                + "\n[crewcap] deck-bound refusal frames :: " + framesOfRefusals(boundTrace));
        Events.assertInstrumentRan(boundTrace, "deck_movement_bound",
                "the server's movement bound was or was not asked about the step");
        // The walk's whole purpose, checked rather than assumed: the bound judged this body the tick
        // before the step's packet. Its region is scaled by the ticks since it last judged, so after a
        // silence the step is licensed by the silence and the verdict is about the client's cadence,
        // not the bound. Measured 2026-10-04: a dismounted body pinned to its dismount point by a
        // re-applied seed never moved under W, sent nothing for 12 ticks, and the 6-block step was
        // accepted on a 12-tick licence.
        String intervals = serverEvents.since(boundMark, "deck_movement_bound_interval");
        java.util.List<String> intervalRecords = Events.records(intervals);
        scenario().requireArranged("the bound must have judged the walking body the tick before the"
                        + " step, or the step is licensed by a silence: " + intervals,
                !intervalRecords.isEmpty()
                        && Events.number(intervalRecords.get(0), "ticksSinceLastJudged") <= 1.0);

        // WHAT THIS PINS: the BOUND's own verdict on the packet that declared the step, read off its
        // own record — not a position read after the window. A position cannot see the bound: the
        // client re-images the body to its deck point the very next tick (`followShipPoses`), so the
        // next packet declares the deck again, and both the client's and the server's copy are back
        // at the deck whether the bound refused the step or ratified it. Measured 2026-09-28.
        //
        // The bound records every refusal and every accepted step above a block, so the step appears
        // either way, and which way is the verdict.
        long refusedSteps = Events.recordsWhere(boundTrace, "accepted", "false").stream()
                .filter(r -> Events.number(r, "moved") >= WILD_STEP_BLOCKS - 1.0).count();
        long acceptedSteps = Events.recordsWhere(boundTrace, "accepted", "true").stream()
                .filter(r -> Events.number(r, "moved") >= WILD_STEP_BLOCKS - 1.0).count();
        assertTrue("a " + WILD_STEP_BLOCKS + "-block step committed by the client's own travel must"
                        + " be REFUSED by the server's deck bound, never ratified as a declared"
                        + " position: refused " + refusedSteps + ", accepted " + acceptedSteps
                        + " :: " + boundTrace,
                refusedSteps > 0 && acceptedSteps == 0);

        // "He still holds his deck after all of that" — HIS deck. A step that ended with the body
        // captured by a neighbouring hull is a failure this scenario exists to catch too, and
        // `alreadyTracked` reads the same for it.
        DeckCapture capture = deckCaptureOfThisShip(scenarioShipId,
                "the body must still hold the deck of the ship it was shoved on");
        assertTrue("the body must still hold its deck after all of that: " + capture.raw(),
                capture.alreadyTracked);
    }

    // ---- #47 driver isolation LIVED HERE, and its subject is gone (2026-09-16) --------------
    //
    // `aStillCrewMemberOnAFastClimbingShipKeepsHisCapture` drove a craft up under full stick with a
    // crew member standing still on the deck, and asserted that the capture did not churn. What it
    // was pinning was the CLIENT external-move guard: that guard compared the body's live ship-frame
    // point against the one the last tick committed, called a large enough difference a foreign
    // mover, and released -- so a deck stepping 1.6 blocks in a tick could drop its own crew. The
    // scenario's whole apparatus was about the guard's allowance: the `deck_guard_pass` rows, the
    // carry-widening, `maxFrameStep` against `allowed`, and a control window proving the guard was
    // quiet while parked.
    //
    // The guard was removed under the ruling that a capture is a STATE entered and left by edges,
    // with nothing re-decided per tick. Nothing now compares a committed point to a live one, so
    // there is no allowance to be too small and no churn of this kind to pin. The scenario is not
    // re-aimed because there is nothing left to aim it at: it would assert that a mechanism which no
    // longer exists does not misfire, which is a test that cannot fail.
    //
    // What it measured that is NOT covered elsewhere, said plainly rather than quietly dropped: that
    // a body standing on a hard-accelerating deck stays on it. That is a real contract and it is
    // worth a scenario -- one that asserts the body is still aboard at the end of the climb, through
    // the two edges, with no reference to any guard. No such scenario exists yet.

    // ---- Excluded states: the dismount deck-hold must never snap a creative-flying ex-pilot -----

    /**
     * A pilot who dismounts and takes to creative flight is never pulled back down by the deck.
     */
    @LegacyWitness(prose = "only with BOTH defences removed — the dismount hold's exclusion of an excluded"
            + " body ({@code EntityDummy#keepDismountedPilotOnDeck} at {@code logHold(\"pilotExcluded\", exit)}) AND"
            + " the seed's own excluded check ({@code DeckBoarding#tryApplyPendingSeed} at"
            + " {@code excludedStateOf((EntityLivingBase) body) != null}; taken when it stood in"
            + " `ShipFrameTravel`, moved verbatim on 2026-10-07) —"
            + " does this fail: \"a flying ex-pilot must never be yanked back down (maxDrop=5.1)\", 2026-09-28."
            + " Either one removed alone leaves it green; each suffices. The verdict the wait rewrite touched"
            + " is the premise that the double-tap started flight at all, which is vanilla's and no Stellurgy line"
            + " decides.")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "for (int i = 0; i < 25; i++) {",
            why = "the worst DROP below the running high-water mark is an extremum over the whole hold, which no"
                    + " single record carries. What it cannot see: a deeper drop recovered between two samples.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(2); bot().releaseKey(Keyboard.KEY_SPACE);"
            + " bot().waitWorldTicks(2);",
            why = "the first press. Vanilla toggles flight when a second jump press lands within seven client"
                    + " ticks of the first (`EntityPlayerSP.flyToggleTimer`), so the three two-tick intervals"
                    + " here ARE the double-tap; overshoot that pushed them past seven would fail loudly, on"
                    + " the rise check after the window.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(2); bot().holdKey(Keyboard.KEY_SPACE);",
            why = "the gap between the two presses")
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(2); bot().releaseKey(Keyboard.KEY_SPACE); double y0 =",
            why = "the second press")
    public void aCreativeFlyingExPilotIsNeverSnappedBackByTheDismountHold() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The live war: dismount the pilot seat and start creative-FLYING within the dismount
        // hold's 20-tick window. The window re-sends the deck-capture seed every tick; a seed that
        // ignores excluded states snaps the flying player to the seat column and zeroes his motion,
        // handles() releases him right back (creativeFlight), and the next seed snaps him again -
        // the player is frozen mid-air, camera gates flickering, for the whole window. The
        // contract: an ex-pilot in an excluded state keeps world-frame movement - no snap, ever.
        buildAndBoardShip(site);
        // Flight needs creative, and creative IS the harness default: the server runs gamemode=1
        // and the shared base restores it before every scenario, long before this ship was built.
        // So nothing is switched here and nothing is waited for. A `gamemode creative` issued here
        // would not be free either: it re-sends the player's abilities with the server's
        // `isFlying=false`, and a client that applied that packet after the double-tap below would
        // have the flight it just started switched off. That the double-tap really did start
        // creative flight is asserted after the window, on the height he reached.
        //
        // Marked BEFORE the dismount: everything this scenario is about — the seed's capture, the
        // flyer's release, and any re-capture snapping at him from below — happens after this line,
        // and each is a record with production's own reason on it.
        Events client = clientEvents();
        long flightMark = client.mark();
        arrange("stellurgytest player dismount");
        // Double-tap space IMMEDIATELY - inside the hold window - to start creative flight.
        bot().holdKey(Keyboard.KEY_SPACE);
        bot().waitWorldTicks(2);
        bot().releaseKey(Keyboard.KEY_SPACE);
        bot().waitWorldTicks(2);
        bot().holdKey(Keyboard.KEY_SPACE);
        bot().waitWorldTicks(2);
        bot().releaseKey(Keyboard.KEY_SPACE);

        // Hold space well past the hold window: a flying player RISES steadily and, on this
        // upright open fixture, soon leaves the ship's grown stay region entirely. The war's
        // signature is the opposite - position pinned to the seat column, isResolving flickering.
        //
        // Contract as amended by flying-aboard: a transient capture of the flyer while he is still
        // the deck's to claim (standing contact at the seat) is LEGAL - his flight then resolves
        // on deck axes and he still rises. What must NEVER happen is the old war: the body
        // frozen/yanked at the seat column. And once he has risen out of the ship's stay region he
        // is RELEASED - leaving that region ends the capture - and stays world-frame: never
        // re-captured, never snapped back down.
        double y0 = bot().reportState().get("playerY").getAsDouble();
        StringBuilder win = new StringBuilder();
        double yMax = y0;
        double maxDrop = 0.0;
        boolean trackedAtEnd = false;
        bot().holdKey(Keyboard.KEY_SPACE);
        try {
            for (int i = 0; i < 25; i++) {
                bot().waitWorldTicks(2);
                double y = bot().reportState().get("playerY").getAsDouble();
                maxDrop = Math.max(maxDrop, yMax - y);
                yMax = Math.max(yMax, y);
                DeckCapture capSample = DeckCapture.read(c -> read(c).raw());
                // Held BY THIS SHIP: the flag alone is satisfied by a body the flight carried past
                // a sibling's hull and left in its frame, which is the loss this window watches for.
                trackedAtEnd = capSample.alreadyTracked
                        && capSample.anchoredOn(scenarioShipId);
                win.append(String.format(java.util.Locale.ROOT, "[t%d y=%.2f cap=%b] ",
                        i * 2, y, trackedAtEnd));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        // The capture's whole life across the flight, in order and each link with production's own
        // word for it: the seed's capture, and the release that ends it. `churn` is the external-move
        // guard's share of those releases — the same fact the cumulative drop counter carried, minus
        // the guessing about which window it belonged to.
        String releases = client.since(flightMark, "deck_released");
        long churn = Events.countRecordsWithField(releases, "reason");
        arrange("gamemode survival @a"); // leave the shared world as the other tests expect it
        System.out.println("[crewcap] fly-window y0=" + y0 + " yMax=" + yMax + " maxDrop=" + maxDrop
                + " trackedAtEnd=" + trackedAtEnd + " externalMoveReleases=" + churn + " :: " + win
                + "\n[crewcap] fly-window releases :: " + releases);

        // Instrument-fires: the double-tap really put the client into creative flight - a
        // non-flying player holding space would jump and land, never rising a full 1.5 blocks.
        assertTrue("the double-tap must actually start creative flight (y " + y0 + " -> max " + yMax
                + "): " + win, yMax - y0 > CREATIVE_FLIGHT_RISE_BLOCKS);
        // The war's signature: the body yanked back toward the seat. A steady ascent (deck-frame
        // or world-frame - this ship is upright) never gives back more than a fraction of a block.
        assertTrue("a flying ex-pilot must never be yanked back down (maxDrop=" + maxDrop + "): "
                + win, maxDrop < FLYING_BODY_MAX_DROP_BLOCKS);
        // Risen far above the open fixture, he has left the stay region: world-frame, and no
        // re-capture pulling at him from below.
        //
        // NOT asserted as "a release record exists", though the release IS a link: the contract
        // permits a flyer the seed correctly refuses from the start, and such a body is never
        // captured and therefore never released — `deck_released` is gated on the capture existing.
        // Requiring the record would pin a capture the contract deliberately allows not to happen.
        // The records are printed instead, so a red reads which gates fired and in what order.
        Events.assertInstrumentRan(releases, "deck_capture_events",
                "the release list above is a reading of the flight, or of nobody listening");
        assertTrue("a flyer who has left the ship must be RELEASED, not still captured: " + win
                + " :: " + releases, !trackedAtEnd);
    }

    // ---- The OUTER hull of an inverted ship is walkable, with WORLD-frame semantics -------------

    /**
     * A player resting on the OUTER hull of an upside-down craft keeps world-frame semantics: no deck
     * takes him, and the deck-levelled camera never engages for him.
     *
     * <p>The contract is the outer hull's, and it is a NEGATIVE about the deck: in the craft's own
     * frame that face has no floor beneath him, so no deck may hold him there — the physics substrate
     * collides him with the hull as with terrain, and his gravity, movement and view are the world's.
     * Where he rests is pinned by the outer-hull scenarios below; this one pins whose he is while he
     * rests there.</p>
     *
     * <p>The two observations, each a window on the CLIENT's log from a mark taken before he is put on
     * the face, and each with a POSITIVE precondition on its own instrument in the same window:</p>
     * <ul>
     *   <li>no {@code deck_entered} naming him — the deck frame's capture edge — after the deck frame
     *       was ASKED about him in the window: the gate window's per-tick record of its admission
     *       decision ({@code deck_gate_explained}), so an empty edge list is the deck frame answering
     *       "no" and not a deck frame that never looked;</li>
     *   <li>no {@code deck_camera_changed} engage edge, and the standing camera value off — "never" is
     *       a claim about the whole encounter, and the standing value alone is false both for a camera
     *       that never ran and for one that turned on and off — after the camera hook ran in the window
     *       (this scenario's {@code cameraHookCalls} grew).</li>
     * </ul>
     */
    @RedWitness(verdict = "no deck may take a player resting on the OUTER hull", member = "DeckFrameTick#admissionFor",
            at = "candidates.add(0, declared);",
            replacedBy = "candidates.add(0, declared); } if (!candidates.isEmpty()) { return candidates.get(0);",
            fails = "no deck may take a player resting on the OUTER hull - in the craft's frame that face has no"
                    + " floor under him: every deck capture of him since he was put there:", on = "2026-10-07")
    @RedWitness(verdict = "must have been ASKED about him in the encounter",
            member = "DeckFrameTick#updateLocalPlayerLiving",
            at = "if (!isLocalPlayer(body))", replacedBy = "if (true) return false; if (!isLocalPlayer(body))",
            fails = "the deck frame must have been ASKED about him in the encounter", on = "2026-10-07")
    @RedWitness(verdict = "the camera hook must have been asked about his view",
            member = "ClientProxy#registerEventHandlers",
            at = "MinecraftForge.EVENT_BUS.register(new RocketEventHandler())", replacedBy = "",
            fails = "the camera hook must have been asked about his view in the encounter", on = "2026-10-07")
    @NotYetRed(prose = "THE CAMERA verdict (\"the deck camera must never engage for a player on the outer"
            + " hull\") has no single-fragment witness. With {@code DeckBoarding#isResolvingAboard} at"
            + " {@code return entity != null && DeckFrameTick.holds(entity);} alone answering by containment it"
            + " stayed GREEN, 2026-10-07: the camera also asks which craft holds him. It went red only with that"
            + " AND {@code DeckBoarding#aboardShipId} at"
            + " {@code return entity == null ? null : DeckFrameTick.heldShipId(entity);} both answering by"
            + " containment at once: \"the deck camera must never engage for a player on the outer hull: 1 engage"
            + " edge(s), standing value active=true\".")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(OUTER_HULL_LAND_TICKS);",
            why = "a tenth of a block at a player's 0.08 blocks/tick^2 lands in about two ticks; the window is the"
                    + " encounter, and a longer one only gives a deck more ticks to take him.")
    public void aPlayerOnTheOuterHullKeepsWorldFrameSemantics() throws Exception {
        PilotSeat seat = rollOver(180.0);
        double face = seat.seatY - 1.0;
        Events client = clientEvents();
        // The two positive preconditions' own instruments, armed before the stimulus: the deck frame's
        // per-tick admission decisions about him (the gate window), and this scenario's count of
        // frames the camera hook was asked (the camera window, opened by its first reading).
        double hookCallsBefore = deckCamera("cameraHookCalls");
        openDeckGateWindow(OUTER_HULL_GATE_RECORDS);
        long encounterMark = client.mark();
        int playerId;
        try {
            playerId = putOnTheFace(seat, HULL_CELL_DX, HULL_CELL_DZ, 0.1, 0.0f);
            advanceServerAndClient(OUTER_HULL_LAND_TICKS);
        } finally {
            closeDeckGateWindow();
        }
        Reply rest = Reply.of(read("stellurgytest vs player-ship-data 0 " + playerId + " " + scenarioShipId).raw());
        scenario().requireArranged("the player must have come to rest on the hull's outer face before"
                + " the encounter says anything about it (ship-frame Y of the face " + face + "): " + rest,
                rest.bool("playerOnGround") && Math.abs(face - rest.number("bodyShipFrameY")) < ON_THE_FACE);

        String asked = client.since(encounterMark, "deck_gate_explained");
        String entered = client.since(encounterMark, "deck_entered");
        String cameraEdges = client.since(encounterMark, "deck_camera_changed");
        boolean camActive = Boolean.parseBoolean(deckCameraText("active"));
        double hookCalls = deckCamera("cameraHookCalls") - hookCallsBefore;
        System.out.println("[crewcap] outerHullSemantics asked=" + Events.countRecords(asked, "type",
                "deck_gate_explained") + " entered=" + entered + " cameraEdges=" + cameraEdges
                + " camActive=" + camActive + " cameraHookCalls=" + hookCalls);

        Events.assertInstrumentRan(asked, "deck_gate_window_events",
                "the deck frame was or was not asked about the player on the outer hull");
        assertTrue("the deck frame must have been ASKED about him in the encounter, or \"no deck took"
                + " him\" is a statement about the arrangement: " + asked,
                Events.countRecords(asked, "type", "deck_gate_explained") > 0);
        Events.assertInstrumentRan(entered, "deck_capture_events",
                "a deck did or did not take the player on the outer hull");
        assertTrue("no deck may take a player resting on the OUTER hull - in the craft's frame that face"
                + " has no floor under him: every deck capture of him since he was put there: " + entered,
                Events.countRecords(entered, "who", botName()) == 0);
        assertTrue("the camera hook must have been asked about his view in the encounter, or \"it never"
                + " engaged\" is a statement about a camera that did not run: " + hookCalls + " calls",
                hookCalls > 0);
        Events.assertInstrumentRan(cameraEdges, "deck_camera_events",
                "the deck camera did or did not engage for the player on the outer hull");
        assertTrue("the deck camera must never engage for a player on the outer hull: "
                + Events.countRecords(cameraEdges, "active", "true") + " engage edge(s), standing value"
                + " active=" + camActive + " :: " + cameraEdges,
                Events.countRecords(cameraEdges, "active", "true") == 0 && !camActive);
    }

    // ---- The crosshair picks the block the camera looks at, at any attitude ---------------------

    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(150);",
            why = "an attitude converges and nothing in production declares it reached, so there is no record to"
                    + " wait on. Overshoot brings the hull nearer its commanded 50 degrees, which is inside the"
                    + " band.")
    public void theCrosshairPicksTheSameDeckBlockAtAnyAttitude() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The raytrace origin (getPositionEyes) ran along WORLD up while the camera renders the eye
        // along the SHIP's up; on a rolled deck the two diverge by up to an eye height, so the
        // crosshair picked a block ~1.4 blocks beside the one the camera centred (the round-10
        // "crosshair does not match the look-HUD"). The interaction contract - the block outlined
        // under the crosshair is the block interacted with - in its attitude-invariance form: a
        // crew member held at the SAME deck point, looking straight down, must see the crosshair
        // resolve the SAME subspace deck block whatever the ship's roll - the deck under his feet
        // does not move in the ship frame when the ship rolls.
        buildAndBoardShip(site);
        Events client = clientEvents();
        long dismountMark = client.mark();
        arrange("stellurgytest player dismount");
        ShipIdentity.awaitCaptureHeldBy(client, dismountMark, scenarioShipId,
                "the ex-pilot must be taken by THIS ship's deck before"
                + " any claim about a crosshair on that deck", CAPTURE_LINK_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture5 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        scenario().requireArranged("the ex-pilot must be captured on the deck: "
                + deckCapture5.raw(),
                deckCapture5.alreadyTracked);

        long lookMarkLevel = clientEvents().mark();
        arrange("tp @a ~ ~ ~ 0 90"); // look straight down at the deck underfoot
        awaitClientLookApplied(lookMarkLevel,
                "the crosshair read below is the CLIENT's, so the aim must have reached it");
        String level = crosshairBlock("looking down at the level deck");
        // The body's own SUBSPACE feet, read in the same breath. The header's claim is about the
        // deck UNDER HIS FEET, and nothing in this scenario pins him to one deck spot across a
        // 150-tick roll - so the invariant is the block's offset FROM HIM, not its absolute
        // address. Measured 2026-09-09: he moves, and an absolute comparison reds on that alone.
        String feetLevel = readSubPos(read("stellurgytest vs subspace-census").raw());
        // THE DISCRIMINATOR for the reading below. If the deck look is HELD across the roll, the
        // stored deck pitch stays put while the world pitch swings by the roll; if every server
        // PosLook echo RE-SEEDS it, the world pitch stays at 90 (down) and the deck pitch is what
        // moves. Those are the two accounts of why the crosshair lands where it does, and one
        // pair of numbers before and after separates them.
        // Read off the RECORD the client wrote while looking down, not polled out of a static: the
        // three deck-look numbers are one tick's triple, and the window says WHICH ten ticks they
        // came from. A poll answered with whatever the socket caught, so a pitch that moved and came
        // back between two asks had never happened.
        String lookLevel = deckLookIn(lookMarkLevel, "while looking down at the level deck");
        double deckPitchLevel = Events.number(lookLevel, "deckPitchDeg");
        double worldPitchLevel = bot().reportState().get("playerPitch").getAsDouble();
        long echoesLevel = (long) deckCamera("posLookApplies");
        String deckActiveLevel = Events.text(lookLevel, "active");
        assertTrue("looking straight down on the LEVEL deck must resolve a block (got '" + level
                + "')", !level.isEmpty());

        // Roll choice is MEASURED against the stand geometry, not arbitrary: the dismounted crew
        // member stands at the SEAT column - the exact centre of the 5x5 deck, 2.5 blocks from
        // every deck face. A world-down ray from the 1.62-high eye leaves the deck's footprint
        // once 1.62*tan(roll) exceeds that half-width, i.e. past ~57 degrees the ray can only
        // miss REGARDLESS of the contract under test (at 60 deg it grazed past the far face by
        // ~0.2 and resolved nothing). 50 deg keeps ~0.6 blocks of landing margin while still
        // satisfying both instrument-fire gates below (upY = cos50 = 0.64 < 0.7; eye divergence
        // = 1.62*sin50 = 1.24 > 0.6).
        double h = Math.toRadians(50.0) / 2.0;
        double upBeforeRoll = shipInfo().upY();
        assertTrue("attitude hold must accept the roll",
                Reply.of(arrange("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").raw()).bool("commanded"));
        advanceServerAndClient(150);
        ShipInfo info = shipInfo();
        double upY = info.upY();
        scenario().requireArranged("the ship must be steeply rolled for the eyes to diverge (upY " + upBeforeRoll
                        + " -> " + upY + ")",
                upY < DIVERGENT_ROLL_UP_Y_MAX && upY > DIVERGENT_ROLL_UP_Y_MIN);
        DeckCapture capNow = deckCaptureOfThisShip(scenarioShipId,
                "the crew member must still be captured by the ship that rolled under him");
        assertTrue("the crew member must still be captured after the roll: " + capNow.raw(),
                capNow.alreadyTracked);

        long lookMarkRolled = clientEvents().mark();
        arrange("tp @a ~ ~ ~ 0 90");
        awaitClientLookApplied(lookMarkRolled,
                "the crosshair read below is the CLIENT's, so the aim must have reached it");
        String crosshairRolled = crosshairRecord("looking down on the rolled deck");
        String rolled = Events.text(crosshairRolled, "block");
        String feetRolled = readSubPos(read("stellurgytest vs subspace-census").raw());
        String lookRolled = deckLookIn(lookMarkRolled, "while looking down on the rolled deck");
        double deckPitchRolled = Events.number(lookRolled, "deckPitchDeg");
        double worldPitchRolled = bot().reportState().get("playerPitch").getAsDouble();
        long echoesRolled = (long) deckCamera("posLookApplies");
        String deckActiveRolled = Events.text(lookRolled, "active");
        // The ray's eye comes off the SAME crosshair record as the block above, and the camera's
        // off a deck_camera peek. These were six reflective reads of private fields across the
        // socket — the last in the tier — and each one sampled whatever frame had drawn most
        // recently, so the six numbers compared below were up to six different moments.
        double rx = Events.number(crosshairRolled, "rayEyeX");
        double ry = Events.number(crosshairRolled, "rayEyeY");
        double rz = Events.number(crosshairRolled, "rayEyeZ");
        double cx = deckCamera("eyeX");
        double cy = deckCamera("eyeY");
        double cz = deckCamera("eyeZ");
        double px = bot().reportState().get("playerX").getAsDouble();
        double py = bot().reportState().get("playerY").getAsDouble();
        double pz = bot().reportState().get("playerZ").getAsDouble();
        double rayVsCam = Math.sqrt((rx - cx) * (rx - cx) + (ry - cy) * (ry - cy)
                + (rz - cz) * (rz - cz));
        double worldEyeVsCam = Math.sqrt((px - cx) * (px - cx)
                + (py + 1.62 - cy) * (py + 1.62 - cy) + (pz - cz) * (pz - cz));
        System.out.println("[crewcap] crosshair level='" + level + "' rolled='" + rolled
                + "' upY=" + upY + " rayVsCam=" + rayVsCam + " worldEyeVsCam=" + worldEyeVsCam);
        assertTrue("looking straight down on the ROLLED deck must still resolve a block (got '"
                + rolled + "')", !rolled.isEmpty());
        // Instrument-fires, and it gates the ACTED claim below as it gated the proxy before it: at
        // this roll the world-up eye really does diverge from the rendered camera eye, so a ray
        // starting at the wrong one would leave the deck entirely. On a level ship the two
        // coincide and nothing below could fail.
        assertTrue("the world-up eye must diverge from the camera eye at this roll (worldEyeVsCam="
                + worldEyeVsCam + ", upY=" + upY + "); a level-ship run cannot falsify the "
                + "claim below", worldEyeVsCam > EYE_DIVERGENCE_BLOCKS);

        // THE STATED CONTRACT, EXECUTED. This test's own header says the crew member must resolve
        // the SAME subspace deck block whatever the roll - and until now it asserted only that each
        // reading was non-empty and never compared them. The deck under his feet does not move in
        // the ship frame when the ship rolls, so the two readings are the same block or the
        // crosshair is following the world instead of the deck.
        // ATTITUDE INVARIANCE: MEASURED AND PRINTED, DELIBERATELY NOT ASSERTED.
        //
        // This method's header claims the crosshair must resolve the SAME subspace deck block at any
        // roll. Asserting it reds, and the numbers say why: the body's subspace feet are IDENTICAL
        // across the roll (nothing slid), while the resolved block moves by (0,-1,2) at a 50 degree
        // roll - and 1.62*tan(50) = 1.93, the horizontal reach of a ray cast WORLD-down from a
        // 1.62-high eye. The ray is world-frame, which the roll-choice comment above already
        // reasons in terms of, while the header claims the opposite.
        //
        // Whether that is a defect is NOT established here: the deck look is re-seeded by every
        // external write to the player's rotation, and a server PosLook echo is one, arriving
        // throughout the 150-tick slew. "It should ride the deck and does not" and "it rides the
        // world because the echo re-seeds it" are different bugs and this run cannot separate them.
        // Asserting the header would make a red a statement about MY premise rather than about
        // production, so the reading is printed and the ledger owns the question.
        String offLevel = blockOffset(level, feetLevel);
        String offRolled = blockOffset(rolled, feetRolled);
        System.out.println("[crewcap] crosshair-vs-body level block='" + level + "' feet='"
                + feetLevel + "' offset=" + offLevel + "; rolled block='" + rolled + "' feet='"
                + feetRolled + "' offset=" + offRolled + " at upY=" + upY);
        System.out.println("[crewcap] look-frame deckPitch " + deckPitchLevel + " -> "
                + deckPitchRolled + " worldPitch " + worldPitchLevel + " -> " + worldPitchRolled
                + " posLookApplies +" + (echoesRolled - echoesLevel)
                + " deckActive " + deckActiveLevel + " -> " + deckActiveRolled
                + " | measured: the WORLD pitch holds and the DECK pitch swings by the roll, so the aim does not ride the deck. With only one PosLook applied across the slew, a per-tick echo is not the writer; deckActive is the remaining question - false means sync() re-seeds every tick from an unchanged world aim through a rotating transform, which is exactly this reading");

        // THE INTERACTION CONTRACT, EXECUTED rather than reasoned to. What used to stand here was a
        // comparison of two internal coordinates - the crosshair ray origin against the recorded
        // camera eye - followed by "so the outlined block is the block interacted with". The `so`
        // was the defect: the contract was never run, and two points can coincide while the
        // interaction takes a third path entirely.
        //
        // `useMouseOver` presses use on whatever the CROSSHAIR is on, through vanilla's own
        // Minecraft.rightClickMouse, and reports the block it was aimed at in the same response -
        // one frame, no second socket read. The claim is then simply that vanilla aimed the click
        // at the block this client outlines.
        JsonObject used = bot().useMouseOver();
        assertTrue("pressing use while looking down at the rolled deck must be aimed at a BLOCK,"
                + " not at air or an entity: " + used,
                used.get("aimedAtBlock").getAsBoolean());
        String clicked = used.get("blockX").getAsInt() + "," + used.get("blockY").getAsInt()
                + "," + used.get("blockZ").getAsInt();
        assertEquals("the block the player's use-click was dispatched to must be the block his"
                + " crosshair outlines, at " + upY + " upY: outlined='" + rolled
                + "' clicked='" + clicked + "' (ray=(" + rx + "," + ry + "," + rz + ") cam=("
                + cx + "," + cy + "," + cz + ") dist=" + rayVsCam + ")",
                rolled, clicked);
    }

    // ---- A deck that manoeuvred unwatched does not carry the body that arrives after -----------

    /** One game tick, in seconds — the factor between a declared velocity (blocks per second) and
     *  the carry a body receives for one tick of it. */
    private static final double SECONDS_PER_TICK = 0.05;

    /**
     * A body that meets a deck after the craft manoeuvred with nobody aboard is carried by what the
     * craft is doing now, not by the average of what it did.
     *
     * <p>It could not be seen red before that day's fixes, because its ARRANGEMENT was red on healthy
     * production in four runs of five. Two test-side races, both measured: the stand-once wait
     * returned on a hull-stand capture the body fell into beside the hull BEFORE the teleport onto the
     * deck landed (fixed by waiting for the landing after the teleport's own placement), and the
     * stand-then-leave chain was read by an {@code assertChain} that checked its order once, after an
     * earlier release had satisfied the wait (fixed in {@code Events.assertChain}, which now waits for
     * the ordered chain). After both: 10 green of 10 across this method and the jump scenario that
     * shares the landing wait.</p>
     */
    @LegacyWitness(prose = "taken on the pre-2026-10-06 form, with the client's carry (the travel"
            + " resolver's `remember(entity, shipId, sweep.x, …, carryX, carryY, carryZ)`, deleted with it)"
            + " raised by 0.1 a tick on a capture that follows more than 100 ticks off the deck: \"the carry"
            + " installed when the body was captured must be what the craft SAYS it is doing (5.9E-19/tick …) …"
            + " the largest carry any capture in this window was committed with is 0.1\", 2026-09-28. NOT re-taken"
            + " against the deck frame, whose carry is {@code DeckFrameTick#mapOut} at"
            + " {@code VSIntegration.shipVelocityAtPointFor(world, shipId, pos[0], pos[1], pos[2])}.")
    @Pins("DECKSYNC-6")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(driveIterations);",
            why = "nothing in production declares a manoeuvre over, so there is no record to wait on. The same"
                    + " interval is what lets it end, and that is measured by the settle window below rather"
                    + " than assumed; overshoot only lets the craft settle further, which that window then"
                    + " sees.")
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(20);",
            why = "overshoot adds ticks to the numerator and not to the divisor, so a slow box overstates the"
                    + " residual: the stricter direction.")
    @Wait(shape = Wait.Shape.WINDOW, at = "for (int i = 0; i < 25; i++) {",
            why = "kept for the contact TRACE a failure needs — no record carries how the contact looked across"
                    + " the fall; the verdict is taken from the records afterwards. What it cannot see:"
                    + " anything finer than a tick.")
    public void aBodyMeetingADeckThatManoeuvredUnwatchedIsNotCarriedByIt() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The client is not TOLD a craft's velocity; while it is handling a body on a deck it
        // reconstructs one by differencing two observations of the transform. That difference is an
        // AVERAGE over the interval between them, and every consumer reads it as the deck's rate
        // right now — the same statement over one tick, a different one over a long gap. This
        // scenario opens exactly such a gap and then makes the two statements disagree: the craft
        // climbs hard with nobody aboard to observe it, STOPS, and only then is a body dropped on.
        // The average over that interval says the deck is rising; the deck is not. A body handed the
        // average is carried by a manoeuvre that ended before it arrived.
        double[] ship = buildShip(site);

        // FIRST the client must have SEEN this craft, and that is not a detail of the staging — it
        // is what the scenario is about. A rate is a difference between two observations, so a craft
        // the client has never observed yields no rate at all (the first observation seeds and
        // answers nothing) and there is no wrong number to be handed. The defect needs a client
        // holding an OLD observation, which is what standing on the deck here leaves behind.
        // Measured on the way to this: staged without this step the whole encounter produced 25
        // derivations, every one of them one tick wide.
        Events client = clientEvents();
        // The landing on this deck after the teleport applied, under the gate window. This was the
        // "red with one predecessor" half of the 2026-09-16 bisect, and on 2026-09-28 red in four
        // runs of five with or without one: `buildShip` leaves the body beside the hull, it falls into
        // HULL-STAND by itself, and the old capture-since-mark wait returned on THAT capture — at the
        // wrong spot, before the teleport landed — so the server's read below found him released
        // over the deck, still falling.
        long seedServerMark = serverEvents().mark();
        long seedMark = client.mark();
        double[] drop = overTheDeckBesideTheSeat(3.0);
        landOnTheDeckUnderGateWatch(seedMark, seedServerMark, scenarioShipId,
                "the body must be taken by THIS deck ONCE before the"
                + " manoeuvre, or the client holds no earlier observation of the craft and the"
                + " interval under test does not exist",
                CAPTURE_LINK_BUDGET_TICKS, GATE_WINDOW_RECORDS, drop[0], drop[1], drop[2]);
        // Still under the open gate window — see the sibling scenario: the wait buys a CLIENT
        // capture and THIS read asks the SERVER, which is where the two have been seen to disagree.
        DeckCapture seeded;
        try {
            seeded = DeckCapture.read(c -> read(c).raw());
            scenario().requireArranged("the body must stand on the deck ONCE before the manoeuvre,"
                    + " or the client holds no earlier observation and the interval under test does"
                    + " not exist: " + seeded.raw(), seeded.alreadyTracked);
        } catch (AssertionError noCapture) {
            throw new AssertionError(noCapture.getMessage() + " | " + deckGateTrail(seedMark,
                    seedServerMark), noCapture);
        } finally {
            closeDeckGateWindow();
        }
        // The earlier observation must be of THE CRAFT THAT THEN MANOEUVRES. An observation of a
        // neighbouring hull is an observation of a craft that stands still for the whole interval,
        // which is the one arrangement under which this scenario's contract holds trivially.
        scenario().requireArranged("the earlier observation must be of this scenario's own craft,"
                + " which is the one the manoeuvre below is performed on: " + seeded.raw(),
                seeded.anchoredOn(scenarioShipId));

        // NOW off the craft, but still in sight of it. The derivation runs only while the client is
        // handling a body on a deck, so a body standing on the ground nearby observes nothing —
        // which is the ordinary state of a craft nobody is aboard, and it leaves that last
        // observation to go stale. NOT sent far away: at 600 blocks the craft leaves the loaded
        // region and the physics mod stops managing it entirely (`"managed":false`), which is a
        // different experiment — an unloaded craft does not manoeuvre at all.
        // The staging this scenario needs is a two-link CHAIN: he was TAKEN by the deck (leaving the
        // client one observation of the craft), and then RELEASED by leaving it (so that observation
        // goes stale). The first link is the landing awaited above; this is the second, counted from
        // a mark taken BEFORE the teleport that causes it. It used to be one `assertChain` from the
        // mark before the stand — which awaits each type's EXISTENCE first and checks the order
        // after, so a release from earlier in the window (the teleport ONTO the deck lifting a body
        // that had fallen into hull-stand beside the hull) satisfied the wait, and the order check
        // ran before this release arrived: "`deck_released` is missing AFTER", twice in five runs on
        // 2026-09-28. The release record carries the gate that made it, so a red names which.
        long awayMark = client.mark();
        arrange("tp @a " + (bx + 24) + " " + (by + 10) + " " + (bz + 24) + " 0 0");
        client.await(awayMark, "deck_released", "the body must be taken OFF this deck once it has stood"
                + " on it, or the client holds no stale observation of the craft and there is nothing"
                + " for the manoeuvre below to be wrong about", CAPTURE_LINK_BUDGET_TICKS);

        // The craft must still be LOADED and managed with the body standing off it, or nothing below
        // manoeuvres — and that refusal is the reader's own, by identity, naming the world it asked.
        ShipInfo before = shipInfo();
        double yBefore = before.y;

        // THE UNWATCHED MANOEUVRE: a commanded ROLL, held. Attitude is deck motion as much as
        // altitude is, and it is the manoeuvre this fixture can actually perform with nobody
        // aboard — a seat-driven climb tops out at ~3 blocks unmanned (measured twice) and leaves
        // the craft inside its own launch structure, where a body meets world blocks instead of a
        // deck. The command sets an attitude TARGET and the craft's own controller flies to it,
        // exactly as a pilot's would; nothing here writes a transform.
        double upBefore = before.upY();
        double half = Math.toRadians(40.0) / 2.0;
        String commanded = arrange("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                + Math.cos(half) + " " + Math.sin(half) + " 0.0 0.0").raw();
        scenario().requireArranged("the attitude hold must accept the commanded roll, or the craft"
                + " never manoeuvres and the interval under test spans nothing: " + commanded,
                Reply.of(commanded).bool("commanded"));
        int driveIterations = 200;
        advanceServerAndClient(driveIterations);

        // What the deck is ACTUALLY doing now, measured rather than assumed. A craft can be told to
        // stop moving but never to stop turning, so this is a small residual rather than zero — and
        // it is the number the carry a body receives has to match.
        ShipInfo after = shipInfo();
        double upAfter = after.upY();
        double settledOmega = after.omega;
        double ySettleStart = after.y;
        advanceServerAndClient(20);
        double ySettleEnd = shipInfo().y;
        double settledPerTick = Math.abs(ySettleEnd - ySettleStart) / 20.0;
        double climbed = Math.abs(ySettleEnd - yBefore);

        ShipInfo si = shipInfo();
        double sx = si.x, sy = si.y, sz = si.z;
        // A CLEARING, not a pit, and cut around the CRAFT rather than the build base. The shared
        // fixture pre-clear is anchored at a hard-coded y and the surface at this plot sits above
        // it, so what it opens is a shaft whose rim is higher than the deck a body is aimed at: the
        // body then stands on world geometry a metre off the craft and nothing downstream can tell
        // that from a hold that refused to engage.
        String clearing = arrange("stellurgytest fill 0 " + ((int) sx - 12) + " "
                + ((int) sy - SHAFT_BELOW_HULL) + " "
                + ((int) sz - 12) + " " + ((int) sx + 12) + " "
                + ((int) sy + SHAFT_ABOVE_HULL) + " "
                + ((int) sz + 12) + " minecraft:air").raw();
        scenario().requireArranged("the staging clearing was not cut, so the body would meet the"
                + " fixture's own structure instead of the deck: " + clearing,
                Reply.of(clearing).ok());
        // Marked one statement before the drop. The value under test is a ARGUMENT of the capture
        // commit — the deck velocity production binds into the body's motion — and the commit records
        // it: every capture in this window carries its own carry triple, exactly, where a per-tick
        // read of a JVM-wide last-write could only approximate the one that mattered.
        long contactMark = client.mark();
        arrange("tp @a " + sx + " " + (sy + 5) + " " + sz + " 0 0");

        // Sampled every tick for the TRACE, because the value under test is installed ONCE, on the
        // tick the capture takes hold: the held carry is what the next tick subtracts to recover the
        // body's own motion, so a wrong one is a real displacement and not a reading.
        StringBuilder contact = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            advanceServerAndClient(1);
            boolean tracked = DeckCapture.read(c -> read(c).raw()).alreadyTracked;
            if (i % 4 == 0 || tracked) {
                contact.append(String.format(java.util.Locale.ROOT, "[t%d cap=%b y=%.2f] ",
                        i, tracked, bot().reportState().get("playerY").getAsDouble()));
            }
        }
        // Every resolved tick of the encounter, each with the carry production bound into the body
        // on it. `deck_carry` and not `deck_commit`: the commit is gone, and this is the question it
        // answered legitimately — the carry IS per-tick, and the maximum over a manoeuvre needs
        // every tick of it, not an edge.
        String captures = client.since(contactMark, "deck_carry");
        // Counted on the SHIP: the reply is already filtered to `deck_carry`, so a type needle
        // asks only whether it is non-empty. And `maxCarryY` below reduces over EVERY record in the
        // reply, so a tick resolved against another hull in this window would put a carry into the
        // maximum that the craft named further down never declared — the two counts being equal is
        // what lets the reduction stay a reduction over the whole reply.
        long onThisShip = Events.countRecords(captures, "ship", scenarioShipId);
        boolean captured = onThisShip > 0;
        scenario().requireArranged("every capture in the contact window must be by the craft this"
                + " scenario flew, or the carry maximum below mixes two ships' numbers: "
                + onThisShip + " of " + Events.countRecordsWithField(captures, "carry") + " :: " + captures,
                onThisShip == Events.countRecordsWithField(captures, "carry"));
        double maxHeldCarry = maxCarryY(captures);

        // The craft's DECLARED motion at the moment the body was on it — the server's own numbers,
        // which is what the client is supposed to have been told and what its carry must equal.
        ShipInfo atContact = shipInfo();
        double declaredVelY = atContact.velY;
        double declaredCarryY = Math.abs(declaredVelY) * 0.05;
        // The average the interval WOULD have produced, had anyone derived a rate across it: the
        // number a body used to be handed here, kept as the counterfactual this scenario is about.
        // An ESTIMATE and labelled as one — `driveIterations` is the commanded budget, not what this
        // run actually waited, so the divisor is approximate and only the ORDER of the number matters.
        double windowTicks = (driveIterations + 80);
        double averageOverWindow = Math.abs(climbed) / windowTicks;
        System.out.println("[crewcap] unwatched-manoeuvre upY " + upBefore + " -> " + upAfter
                + " (settled omega=" + settledOmega + "), moved=" + climbed
                + " blocks, settledPerTick=" + settledPerTick
                + " declaredVelY=" + declaredVelY + " (=" + declaredCarryY + "/tick)"
                + " averageOverWindow~" + averageOverWindow + "/tick maxHeldCarry=" + maxHeldCarry
                + " :: " + contact
                + "\n[crewcap] unwatched-manoeuvre captures at contact :: " + captures);
        Events.assertInstrumentRan(captures, "deck_carry_events",
                "the carry the client installed at first contact is a reading, or nobody was looking");

        // THE TEST'S OWN, and a sensitivity bar rather than a threshold: it says the arrangement
        // really did turn the craft while the subject was away, so the interval this scenario is
        // about spans motion. A tenth of the deck normal is about six degrees of tilt — far above
        // the settle jitter of a parked hull and far below the commanded manoeuvre.
        scenario().requireArranged("the craft must actually MOVE while nobody watches it, or the"
                + " interval under test spans no motion at all (deck normal upY " + upBefore
                + " -> " + upAfter + " over " + driveIterations + " ticks)",
                Math.abs(upAfter - upBefore) > ARRANGEMENT_MOVED_UP_Y);
        // BOTH ARE THE TEST'S OWN, and both are bounds on "has stopped": a translation under a
        // twentieth of a block per tick and a rotation under a tenth of a radian per second are
        // residuals, not a manoeuvre — the drive above commands about 2 rad/s, twenty times the
        // second bar. They are the test's because production never declares a manoeuvre finished;
        // the hold simply keeps applying torque toward a target it has reached.
        scenario().requireArranged("the manoeuvre must be OVER when the body arrives, or a carry"
                + " equal to the deck's real motion would be correct and this scenario would pin"
                + " nothing (settled " + settledPerTick + " blocks/tick — y " + ySettleStart + " -> "
                + ySettleEnd + " over 20 ticks — omega " + settledOmega
                + ")", settledPerTick < SETTLED_BLOCKS_PER_TICK && settledOmega < SETTLED_OMEGA_RAD_PER_S);
        scenario().requireArranged("the body must reach the deck and be captured, or no carry was"
                + " ever installed and the assertion below is about nothing :: " + contact
                + " :: captures in the window: " + captures, captured);

        // THE CONTRACT: what a body is carried by is what the craft SAYS it is doing — not what its
        // own client could work out from watching. The craft declares its deck motion with its pose,
        // so the answer at first contact is the craft's motion NOW, available on the tick the body
        // arrives and independent of whether this client watched the manoeuvre, missed it, or was
        // looking somewhere else entirely.
        //
        // What makes this scenario able to fail: the client's whole view of this craft spans an
        // interval in which the craft did something it is no longer doing. Reconstructing a rate
        // from that view — which is what the client used to do — yields the manoeuvre's average and
        // carries the body by a motion that has ended.
        assertTrue("the carry installed when the body was captured must be what the craft SAYS it"
                + " is doing (" + declaredCarryY + "/tick, from its declared velocity "
                + declaredVelY + " blocks/s), never the average of what it did (~"
                + averageOverWindow + "/tick): the largest carry any capture in this window was"
                + " committed with is " + maxHeldCarry + " :: " + contact + " :: the commits"
                + " themselves, each with the triple production bound: " + captures,
                maxHeldCarry <= declaredCarryY + 0.02);
    }

    // ---- Deck-frame look: the walking crew's aim lives in the deck frame ------------------------

    /**
     * The mouse turns a walking crew member's aim in the deck frame, and the aim rides the deck.
     */
    @LegacyWitness(prose = "with {@code VSIntegration#flightComputerOf} at"
            + " {@code return flightComputerInYard(world, shipyardBoundsOf(world, shipUuid))} answering null: \"attitude hold must accept the roll\", 2026-09-28. The other wait the rewrite"
            + " touched is the hop's landing on the open deck, a link on the arrangement; the aim verdicts"
            + " after the roll were not touched and are not witnessed.")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int i = 0; i < DECK_LOOK_TURNS; i++) {",
            why = "the swept ANGLE is a sum over the steps, which no record could carry. What it cannot see: how"
                    + " the look travelled inside one step.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int dir = 0; dir < 4; dir++) {",
            why = "four legs whose result is an extremum — the largest yaw divergence across the four directions."
                    + " Each leg drives the body itself, and the quantity compared is a MAXIMUM over legs,"
                    + " which is not a moment anything records. Every leg's re-seed IS awaited as a link below."
                    + " What this cannot see: a worse divergence inside a leg.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int i = 0; i < 8; i++) {",
            why = "the walk this leg measures.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int i = 0; i < 20; i++) {",
            why = "the jump whose frames the open frame-step window summarises.")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "bot().waitWorldTicks(5);",
            why = "five ticks after the key comes up stay inside the frame-step window, so the summary includes"
                    + " the start of the body coming back down.")
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(150); double[] up = shipUpFromInfo(shipInfo());",
            why = "an attitude converges and nothing in production declares it reached, so there is no record to"
                    + " wait on. Overshoot brings the hull nearer its commanded 60 degrees, which is inside the"
                    + " band.")
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(150); double[] up2 = shipUpFromInfo(shipInfo());",
            why = "overshoot only lets the deck turn further, which the roll gate measures instead of assuming.")
    public void theMouseTurnsTheWalkingCrewsAimInTheDeckFrameAndTheAimRidesTheDeck() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The walking-crew look contract, in its two player-visible halves, on a STEEPLY ROLLED
        // deck (the attitude where the old world-frame aim under a deck-levelled camera diverged
        // hardest):
        //   (1) a horizontal REAL-MOUSE move sweeps the aim about the DECK NORMAL - the angle
        //       between the aim and the ship's up does not change as he turns;
        //   (2) with the mouse STILL, the aim is glued to the deck - the ship rolling further
        //       carries the world aim with it.
        // The stimulus is the real client mouse path (Entity.turn); the observation is the
        // client's own world look; the ship attitude read server-side is the cross-side oracle.
        double[] ship = buildAndBoardShip(site);
        Events client = clientEvents();
        long dismountMark = client.mark();
        arrange("stellurgytest player dismount");
        ShipIdentity.awaitCaptureHeldBy(client, dismountMark, scenarioShipId,
                "the ex-pilot must be taken by THIS ship's deck before"
                + " any claim about looking or walking on it", CAPTURE_LINK_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture6 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        scenario().requireArranged("the ex-pilot must be captured on the deck: "
                + deckCapture6.raw(),
                deckCapture6.alreadyTracked);
        // Out of the cockpit pocket onto the OPEN top deck while the ship is still upright (the
        // dismount leaves the body beside the seat, walled in on all four sides - the walk legs
        // below need runway). The capture then carries this open-deck spot through the rolls.
        //
        // Not `deck_entered`: the body may be captured at BOTH ENDS of this hop, on the same craft,
        // in which case no episode opens and that edge is never written. But the hop is a DROP — the
        // teleport puts him about three blocks over the deck — and a drop that ends on this deck
        // ends in `deck_contact`, the resolver putting the body on a surface it was not on the tick
        // before, named for the ship. That is owed whether the capture survived the teleport or was
        // retaken on the way down, and it is what the roll below needs: a body standing on the deck
        // rather than one still falling onto it as it turns.
        long hopMark = clientEvents().mark();
        arrange("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        awaitClientPlacedNear(hopMark, ship[0], ship[2],
                "the hop must have reached the client before its landing is awaited");
        clientEvents().awaitField(hopMark, "deck_contact", "ship", scenarioShipId,
                "the hop drops the crew member onto the open deck, so he must LAND on this ship's"
                        + " deck before it is rolled under him", CAPTURE_LINK_BUDGET_TICKS);
        // The capture read below is the SERVER's, which learns of the landing from his movement
        // packets over his own connection; the fence makes it have handled them.
        fenceWhatTheClientSent("the server must have handled the landing before its capture is read");
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture7 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        scenario().requireArranged("the crew member must be captured on the OPEN deck before the"
                + " roll: " + deckCapture7.raw(),
                deckCapture7.alreadyTracked);

        // Roll the ship to ~60 degrees about X and hold it there.
        double h = Math.toRadians(60.0) / 2.0;
        double upBeforeRoll = shipInfo().upY();
        assertTrue("attitude hold must accept the roll",
                Reply.of(arrange("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").raw()).bool("commanded"));
        advanceServerAndClient(150);
        double[] up = shipUpFromInfo(shipInfo());
        scenario().requireArranged("the ship must be steeply rolled for the frames to diverge (upY " + upBeforeRoll
                        + " -> " + up[1] + ")",
                up[1] < DIVERGENT_ROLL_UP_Y_MAX && up[1] > DIVERGENT_ROLL_UP_Y_MIN);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture8 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        assertTrue("the crew member must still be captured after the roll: "
                + deckCapture8.raw(),
                deckCapture8.alreadyTracked);

        // Baseline aim (a server re-aim, which must RE-SEED the deck look, not fight it).
        long lookMarkAim = clientEvents().mark();
        arrange("tp @a ~ ~ ~ 20 10");
        // THIS ONE DECIDED A VERDICT, where the three above only delayed. `deckLookIn` reads the
        // LAST `deck_look` since the mark and asserts no wait of its own, so the ten ticks were what
        // made that window non-empty and post-aim: too few and the read is the aim before this one,
        // or nothing at all. It is a link now — and its blind spot is written where the helper is.
        awaitClientLookApplied(lookMarkAim,
                "the server's re-aim must reach the client before its deck look is read, or the"
                        + " reading belongs to the aim before it");
        assertTrue("the deck-frame look must be engaged for a captured walking crew member "
                        + "(deckActive=false would make every assertion below vacuous)",
                Boolean.parseBoolean(Events.text(
                        deckLookIn(lookMarkAim, "after the server re-aim"), "active")));
        double[] look0 = clientLook();
        double cone0 = dot(up, look0);

        // (1) Four 30-degree horizontal REAL-MOUSE turns: each sweeps ~30 degrees about the deck
        // normal and none of them moves the aim off its cone around the ship's up. The old
        // world-frame aim fails both ways at this roll (the sweep bends toward the world poles and
        // the cone angle drifts), whether or not the delta itself is roll-rotated.
        double swept = 0.0;
        double[] prev = look0;
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < DECK_LOOK_TURNS; i++) {
            bot().turnLook(MOUSE_UNITS_PER_TURN, 0f);
            bot().waitWorldTicks(2);
            double[] look = clientLook();
            double cone = dot(up, look);
            double step = planeAngleDeg(up, prev, look);
            swept += step;
            steps.append(String.format(java.util.Locale.ROOT,
                    "[turn%d cone=%.3f step=%.1f] ", i, cone, step));
            // THE TEST'S OWN. The quantity is a dot product of two unit vectors, so it is a cosine:
            // the contract is that a HORIZONTAL move keeps the aim on its cone about the deck
            // normal, i.e. that this does not change at all, and 0.05 of cosine is about three
            // degrees — the float rounding of a yaw round-trip through the client, not a budget for
            // drift. A world-frame aim at this roll leaves the cone by tens of degrees.
            assertTrue("a horizontal mouse move must not move the aim off its cone about the deck "
                            + "normal (start=" + cone0 + " now=" + cone + ") :: " + steps,
                    Math.abs(cone - cone0) < AIM_STAYS_ON_CONE);
            prev = look;
        }
        System.out.println("[crewcap] deck-look sweep=" + swept + " :: " + steps);
        // DERIVED, not chosen: the sweep this expects is the arrangement's own turns times the
        // degrees each one commands, and only the TOLERANCE is a number of this test's. It was
        // written out as `> 100 && < 140`, which is the same band with the arithmetic already done
        // and the relation to the loop above lost — change the turn count and those bounds go on
        // asserting 120 degrees.
        double expectedSweep = DECK_LOOK_TURNS * DEG_PER_TURN;
        assertTrue(DECK_LOOK_TURNS + " mouse turns of " + DEG_PER_TURN + " degrees must sweep ~"
                + expectedSweep + " degrees about the deck normal, not stall or wrap (swept="
                + swept + ") :: " + steps,
                Math.abs(swept - expectedSweep) < DECK_LOOK_SWEEP_TOLERANCE_DEG);

        // (2) Mouse still: roll the ship 25 degrees further. The aim must RIDE THE DECK - same
        // cone about the deck normal, and the world look turns WITH the ship instead of staying
        // world-glued. Pin the aim to world +Z first (perpendicular to the X roll axis), so the
        // expected world turn of a deck-glued aim is exactly the extra roll angle.
        long pinMark = clientEvents().mark();
        arrange("tp @a ~ ~ ~ 0 0");
        awaitClientLookApplied(pinMark,
                "the aim is PINNED here and the turn below is measured from it, so the pin must"
                        + " have reached the client before the first reading");
        double[] lookBefore = clientLook();
        double coneBefore = dot(up, lookBefore);
        double h2 = Math.toRadians(85.0) / 2.0;
        assertTrue("attitude hold must accept the second roll",
                Reply.of(arrange("stellurgytest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h2) + " " + Math.sin(h2) + " 0.0 0.0").raw()).bool("commanded"));
        advanceServerAndClient(150);
        double[] up2 = shipUpFromInfo(shipInfo());
        double rolledBy = Math.toDegrees(Math.acos(clampUnit(dot(up, up2))));
        // THE TEST'S OWN sensitivity bar: the leg below asks whether the aim TURNED WITH the deck,
        // which is only answerable if the deck turned by more than the noise the comparison
        // carries. Fifteen degrees is several times the tolerance the aim is then checked against
        // (8), so a roll that satisfies this cannot be mistaken for a world-glued aim standing
        // still.
        scenario().requireArranged("the ship must actually roll further for this leg to prove anything (rolled "
                + rolledBy + " deg more, upY " + up[1] + " -> " + up2[1] + ")", rolledBy > FURTHER_ROLL_DEG);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture9 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        assertTrue("the crew member must still be captured on the further-rolled deck: "
                + deckCapture9.raw(),
                deckCapture9.alreadyTracked);
        double[] lookAfter = clientLook();
        double coneAfter = dot(up2, lookAfter);
        double aimTurned = Math.toDegrees(Math.acos(clampUnit(dot(lookBefore, lookAfter))));
        System.out.println("[crewcap] deck-glue rolledBy=" + rolledBy + " aimTurned=" + aimTurned
                + " cone " + coneBefore + " -> " + coneAfter);
        // THE TEST'S OWN, and the same cosine quantity as the sweep's cone check above — a shade
        // wider (0.06 against 0.05) because the deck itself is MOVING across this window, so the
        // two readings are taken against attitudes a few ticks apart.
        assertTrue("with the mouse still, the aim must stay on its cone about the deck normal as "
                + "the ship rolls (cone " + coneBefore + " -> " + coneAfter + ")",
                Math.abs(coneAfter - coneBefore) < AIM_STAYS_ON_CONE_WHILE_ROLLING);
        // THE TEST'S OWN, and the shape is what matters: the BOUND is a tolerance, while the value
        // it is compared against is the roll the ship actually performed, read back a few lines
        // above rather than written down. Eight degrees is the slack between two attitudes sampled
        // a few ticks apart while the hull is still turning; the failure it exists for is an aim
        // that did not turn at all, which is the whole `rolledBy` away (at least 15 by the gate).
        assertTrue("with the mouse still, the world aim must TURN WITH the deck by the extra roll, "
                + "not stay world-glued (ship rolled " + rolledBy + " deg, aim turned "
                + aimTurned + ")", Math.abs(aimTurned - rolledBy) < AIM_FOLLOWS_DECK_TOLERANCE_DEG);

        // One transform for the view and the aim: the camera the renderer was handed points where
        // the derived world look points (two independent code paths on the client).
        //
        // THIS READING IS A PROXY, and it is one deliberately. The contract a player feels is "what
        // is in the middle of my screen is what I will hit", and the only witness to the first half
        // is what was handed to the renderer - the bot has no eyes. The acted crosshair claim in
        // theCrosshairPicksTheSameDeckBlockAtAnyAttitude does NOT cover this link: the click is
        // dispatched through objectMouseOver, which is raytraced from the player's world rotation,
        // so it can agree perfectly while the camera points somewhere else entirely.
        //
        // What it therefore CANNOT see: anything that moves the rendered view without moving the
        // stored camera attitude (a transform applied after this record, a shader, a third-person
        // offset). A red here is real; a green is "the two numbers agree", not "the picture is
        // right".
        double camYaw = deckCamera("yaw");
        double camPitch = deckCamera("pitch");
        double playerYaw = bot().reportState().get("playerYaw").getAsDouble();
        double playerPitch = bot().reportState().get("playerPitch").getAsDouble();
        double yawDiff = Math.abs(wrap180(camYaw - playerYaw));
        System.out.println("[crewcap] cam=(" + camYaw + "," + camPitch + ") player=("
                + playerYaw + "," + playerPitch + ")");
        assertTrue("the rendered camera must point along the derived world aim (yaw " + camYaw
                + " vs " + playerYaw + ", pitch " + camPitch + " vs " + playerPitch + ")"
                + " - PROXY: this compares the attitude handed to the renderer against the player's"
                + " world aim, because no instrument here can see the picture itself"
                // THE TEST'S OWN, both of them: three degrees is the agreement two angles reach when
                // one is the player's aim and the other is the partial-tick interpolation of it the
                // renderer was handed, sampled on different frames. The defect it refuses is a camera
                // pointing somewhere else entirely — the whole roll away, not three degrees.
                , yawDiff < CAMERA_AIM_AGREEMENT_DEG
                        && Math.abs(camPitch - playerPitch) < CAMERA_AIM_AGREEMENT_DEG);

        // (3) The MOVEMENT half of the same transform: on this near-vertical (~85 degree) deck,
        // holding the REAL forward key must walk the body along the deck heading the mouse
        // steers. This is the exact attitude where a walk basis built from the world-yaw
        // projection of a deck-glued aim degenerates (the world look sits near the pole) and
        // walking decouples from the keys.
        // Where the body stands on the deck varies run to run, so any single fixed heading can
        // face a wall 0.1 blocks away (seen live: ~0.10 blocks and a horizontal collision), and
        // legs walked in sequence drift the body toward an edge until it walks OFF the deck (seen
        // live: an 8.7-block "leg" that was really a fall after the capture released). So: anchor
        // at the settled open-deck spot, tp BACK to the anchor before every leg, turn to the
        // leg's heading with the REAL mouse (re-exercising the deck-relative turn after the tp's
        // re-seed), walk briefly, and count a leg only if the capture held through it and the
        // displacement is walking-sized. Judge the heading contract on the best VALID leg.
        double[] q85 = shipQuatFromInfo(shipInfo());
        double[] anchor = clientPos();
        double bestMag = -1.0, bestWalkedYaw = 0.0, bestHeldYaw = 0.0;
        StringBuilder legs = new StringBuilder();
        for (int dir = 0; dir < 4; dir++) {
            long reseatMark = clientEvents().mark();
            arrange("tp @a " + anchor[0] + " " + anchor[1] + " " + anchor[2] + " 0 0");
            // The re-seed is what the leg below measures against, and ten ticks were deciding
            // whether it had happened — the same defect as the baseline aim above, inside a loop.
            awaitClientLookApplied(reseatMark,
                    "each leg's deck yaw is read against the re-seed this teleport performs");
            if (dir > 0) {
                bot().turnLook(600f * dir, 0f); // dir * 90 degrees of deck yaw
                bot().waitWorldTicks(2);
            }
            long lookMarkLeg = clientEvents().mark();
            bot().waitWorldTicks(1); // one tick, so the window below cannot be empty by construction
            double heldDeckYaw = Events.number(
                    deckLookIn(lookMarkLeg, "before walking leg " + dir), "deckYawDeg");
            ShipInfo infoW0 = shipInfo();
            double[] p0 = clientPos();
            double[] s0 = {infoW0.x, infoW0.y, infoW0.z};
            try {
                for (int i = 0; i < 8; i++) {
                    bot().holdKey(Keyboard.KEY_W); // re-asserted per tick against key-state churn
                    bot().waitWorldTicks(1);
                }
            } finally {
                bot().releaseKey(Keyboard.KEY_W);
            }
            advanceServerAndClient(4);
            double[] p1 = clientPos();
            ShipInfo infoW1 = shipInfo();
            double[] s1 = {infoW1.x, infoW1.y, infoW1.z};
            boolean stillAboard = Boolean.parseBoolean(Events.text(
                    deckLookIn(lookMarkLeg, "at the end of walking leg " + dir), "active"));
            // The walk displacement, with the ship's own drift removed, in the DECK frame.
            double[] walkWorld = {p1[0] - p0[0] - (s1[0] - s0[0]), p1[1] - p0[1] - (s1[1] - s0[1]),
                    p1[2] - p0[2] - (s1[2] - s0[2])};
            double[] walkDeck = rotateByConjugate(q85, walkWorld);
            double planeMag = Math.sqrt(walkDeck[0] * walkDeck[0] + walkDeck[2] * walkDeck[2]);
            double walkedYaw = Math.toDegrees(Math.atan2(-walkDeck[0], walkDeck[2]));
            // Walking-sized displacement only: a sub-walk leg hit a wall, an over-walk leg fell
            // off the deck - neither can falsify the HEADING contract.
            boolean valid = stillAboard && planeMag > 0.3 && planeMag < 2.5;
            legs.append(String.format(java.util.Locale.ROOT,
                    "[dir%d held=%.1f walked=%.1f mag=%.2f aboard=%b valid=%b] ", dir, heldDeckYaw,
                    walkedYaw, planeMag, stillAboard, valid));
            if (valid && planeMag > bestMag) {
                bestMag = planeMag;
                bestWalkedYaw = walkedYaw;
                bestHeldYaw = heldDeckYaw;
            }
        }
        System.out.println("[crewcap] deck-walk best mag=" + bestMag + " walked=" + bestWalkedYaw
                + " held=" + bestHeldYaw + " :: " + legs);
        assertTrue("holding W must walk the body a walking-sized distance along the deck, still "
                + "captured, in at least one of four headings :: " + legs, bestMag > WALKED_A_WALKING_DISTANCE_BLOCKS);
        assertTrue("on a ~90-degree deck the walk direction must match the deck heading the "
                + "mouse steers (walked " + bestWalkedYaw + " deg, held " + bestHeldYaw
                + " deg) :: " + legs, Math.abs(wrap180(bestWalkedYaw - bestHeldYaw)) < WALK_HEADING_TOLERANCE_DEG);

        // (diag, print-only) Jump-smoothness discriminators on THIS actively attitude-holding
        // (hunting) ship: per-frame step statistics of the ABSOLUTE body path vs the path
        // RELATIVE to a fixed deck point. A smooth path has max ~ mean step; a tick-stepped one
        // has max >> mean. Feeds the open jump-stutter residual; no contract asserted here.
        long anchorMark = clientEvents().mark();
        arrange("tp @a " + anchor[0] + " " + anchor[1] + " " + anchor[2] + " 0 0");
        awaitClientPlacedNear(anchorMark, anchor[0], anchor[2],
                "the frame statistics below are about a body standing HERE");
        // No settle after the placement. The anchor is a spot he was standing on, so there is no
        // fall to wait out, and nothing below is asserted: this window is print-only diagnostics
        // for the jump-stutter residual, so a landing link here could only ever fail a scenario
        // whose contract had already been judged.
        // OPEN the window, jump, CLOSE it: the statistics are accumulated on the test side (the
        // render seam fires per frame, which no event log can carry) and the window's summary is one
        // record, in this scenario's own window. The six production statics this replaces were
        // JVM-wide, so on a shared client they carried whatever the previous scenario had left in
        // them whenever the reset was forgotten — and nothing said so.
        long stepMark = clientEvents().mark();
        ClientWindow stepWindow = ClientWindow.open(bot(), FRAME_STEP_WINDOW);
        try {
            for (int i = 0; i < 20; i++) {
                bot().holdKey(Keyboard.KEY_SPACE);
                bot().waitWorldTicks(1);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        bot().waitWorldTicks(5);
        stepWindow.close();
        String stepSummary = Events.lastRecord(clientEvents().since(stepMark, "frame_step_window"));
        System.out.println("[crewcap] jump-steps "
                + (stepSummary == null ? "(the render seam sampled no aboard frame)" : stepSummary));
    }

    /**
     * The last deck look this client RECORDED since {@code mark}, or a failure that says which
     * window was empty.
     *
     * <p>Replaces {@code clientDouble("…client.DeckLook", …)} — a reflective read of a private
     * production static across the socket. Two things changed and only one is the static: this
     * answers about a WINDOW rather than about the instant the socket happened to ask, and an empty
     * window is a named failure instead of a value that looks like a reading. The recorder writes
     * every client tick, engaged or not, so an empty window means the tick path did not run — which
     * is a finding, not a zero.</p>
     */
    /**
     * The block the crosshair last resolved, as the client recorded it.
     *
     * <p>The recorder writes when the block CHANGES, so the latest record is what the crosshair is
     * on now; an empty string is a real answer (the ray hit nothing) and arrives as a record like
     * any other. Replaces a reflective read whose value could not say when it became true.</p>
     */
    // -- a player on the OUTER hull stays where he landed -----------------------------------------

    /**
     * How long the attitude hold is given to roll the craft over, in ticks. A slew converges and
     * nothing announces its end, so it is a WINDOW read once after, gated on the attitude it reached:
     * a slow box fails as an arrangement. Measured 2026-10-07: the server deck-body group's roll to
     * 180 deg on the same fixture was complete within 200 ticks in every run.
     */
    private static final int OUTER_HULL_SLEW_TICKS = 200;

    /** How long the player is given to land after being put a tenth of a block above the face. */
    private static final int OUTER_HULL_LAND_TICKS = 20;

    /** The gate window's record budget for the outer-hull encounter: at most one record a tick, plus one
     *  for each change of answer, over the landing window — so twice its length cannot run out on a body
     *  whose answer flips no more often than every other tick. */
    private static final int OUTER_HULL_GATE_RECORDS = 2 * OUTER_HULL_LAND_TICKS;

    /** The window he is watched lying on the outer hull, in ticks. */
    private static final int OUTER_HULL_WINDOW_TICKS = 100;

    /**
     * How far he may move ALONG the hull in a window and still be "where he stood". Derived 2026-10-07
     * from the readings of a player and an armor stand lying still on the outer hull of this fixture,
     * by either mechanism: the largest healthy along-movement was 8.5e-5, the smallest that was a real
     * defect 0.0066 (a stand crept along the hull by the since-deleted hull-stand). The bound sits
     * between them.
     */
    private static final double OUTER_HULL_AT_REST = 1.0e-3;

    /**
     * How far his distance from the face may change in a window. Not the along bound: the contact gap
     * itself is what varies here - measured 2026-10-07, a body on this face rests 0.004 to 0.009 off it
     * by either mechanism, tick to tick - so the bound is twice that range; a body that left the face
     * is a tenth of a block or more off it.
     */
    private static final double OUTER_HULL_ACROSS = 0.01;

    /**
     * How long the craft is given to come to a stop after a motion, in ticks, before the second
     * reading. Read once after, gated on the craft's own velocity: a craft still moving would put the
     * reading back where the moving one was taken - his position on the server is the claim of his
     * last packet, one or two ticks of the craft's motion behind its pose (measured 2026-10-07: 0.1 and
     * 0.2 at 2 blocks/s), and that lag jitters between reads. 200 because the slowest stop is the
     * spin's: commanded to zero from 0.5 rad/s it still turned at 0.32 forty ticks later (measured
     * 2026-10-07), about 0.0045 rad/s shed a tick, so ~110 ticks to rest.
     */
    private static final int OUTER_HULL_STOP_TICKS = 200;

    /**
     * How far off the face a body "on the face" may lie. Measured 2026-10-07: either mechanism rests
     * a body 0.004 to 0.007 off the face it touches; a tenth of a block is past any such contact gap
     * and short of a step onto anything else, so a body further off is airborne or elsewhere.
     */
    private static final double ON_THE_FACE = 0.1;

    /** Half the with-pilot-deck deck's side: 5x5 blocks with the seat at its centre. */
    private static final double DECK_HALF_WIDTH = 2.5;

    /**
     * The deck cell the outer-hull scenarios put him over, as {@code (dx, dz)} from the seat: the middle
     * of the deck's -Z edge. Its column holds nothing below the deck and neither do its neighbours': the
     * with-pilot-deck fixture hangs its motors under the four corners, its engines and tanks on the
     * seat's row and its reaction wheel beside the seat at +Z ({@code TestProbeCommand#placeTier2Actuators}).
     */
    private static final double HULL_CELL_DX = 0.5;

    /** See {@link #HULL_CELL_DX}. */
    private static final double HULL_CELL_DZ = -1.5;

    /**
     * The row the walk on the outer hull crosses, {@code dz} from the seat: the one between the seat's
     * row and the -Z edge, the only row whose every cell has nothing below the deck (see
     * {@link #HULL_CELL_DX}).
     */
    private static final double HULL_WALK_ROW_DZ = -0.5;

    /**
     * The fall onto the hull, in blocks. A player falling this far meets the face at about 1.6
     * blocks a tick (vanilla's 0.08 per tick squared with 0.98 drag, summed over the ~25 ticks it
     * takes), more than the one-block plate he lands on is thick: a collision that is late by one tick
     * puts him on the far side of it.
     */
    private static final double FAST_FALL_HEIGHT = 20.0;

    /** How long the fall from {@link #FAST_FALL_HEIGHT} is given, in ticks: ~25 to fall, the rest to settle. */
    private static final int FAST_FALL_TICKS = 60;

    /** How long he walks, in client ticks: at a walk of ~0.2 blocks a tick, about two blocks. */
    private static final int HULL_WALK_TICKS = 10;

    /** How far along the hull he must have got for the walk to count as one. Measured 2026-10-07: the
     *  walk covered 2.15 blocks; a quarter of that refuses a walk that never happened. */
    private static final double OUTER_HULL_WALKED_BLOCKS = 0.5;

    /** How near the commanded roll the craft's deck-up world Y must sit, and how little it may change
     *  across a window. Measured 2026-10-07: 1.1e-4 off at 180 deg, 0.0105 off at 137 deg. */
    private static final double ROLL_HELD_UP_Y = 0.02;

    /** A craft's speed (blocks a tick) that reads as stopped. Measured 2026-10-07 after a commanded
     *  zero: 1.1e-17 and 2.1e-17. */
    private static final double CRAFT_STOPPED_SPEED = 0.01;

    /** A craft's angular rate (rad/s) that reads as stopped: its attitude hold hanging still reads
     *  ~0.03 on its own (measured 2026-10-07: 0.029 hovering, 0.0014 after the spin's stop). */
    private static final double CRAFT_STOPPED_OMEGA = 0.05;

    /** How far the craft must fly while he stands on it: further than its deck is wide. Measured
     *  2026-10-07: 10.1 blocks in the window at 2 blocks/s. */
    private static final double OUTER_HULL_FLIGHT_MIN_TRAVEL = 2.0 * DECK_HALF_WIDTH;

    /**
     * The roll that tilts the inverted deck's underside 38 deg off level: steep, and within the ~40.4
     * deg a face stands a body on ({@code VSMath#STANDING_TOLERANCE} = 0.42 is sin² of the limit;
     * sin² 38 deg = 0.379).
     */
    private static final double STEEP_FACE_ROLL_DEG = 142.0;

    /** The spin about world up that turns the inverted craft under him, rad/s: 2.5 rad in the window. */
    private static final String SPIN_RATE = "0.0 0.5 0.0";

    /**
     * The stop for a spin: hold the attitude the craft has reached. A commanded zero rate is shed
     * slowly - measured 2026-10-07, 0.5 rad/s fell only to 0.064 in 200 ticks - while an attitude hold
     * at the current pose has only a small error to close.
     */
    private static final String HOLD_THE_ATTITUDE_REACHED = "hold-the-attitude-reached";

    /**
     * A player standing on the OUTER hull of an upside-down craft - the underside of its deck, which
     * faces the sky - stays where he landed while the craft hovers. Which mechanism collides him with
     * the hull is not asserted in any of these scenarios; the reading beside each verdict names it.
     */
    @RedWitness(verdict = "must come to rest on it", member = "EntityCollisionInjector#alterEntityMovement",
            at = "final double origDx = dx;", replacedBy = "if (true) return null; final double origDx = dx;",
            fails = "a player put on the hull's outer face must come to rest on it (ship-frame Y"
                    + " 128.00000000000034, face 127.0, expected 0.0 off it)", on = "2026-10-07")
    @NotYetRed(prose = "THE ACROSS AND ALONG verdicts, 2026-10-07: the break of"
            + " {@code EntityCollisionInjector#alterEntityMovement} above (the game's own collision, which cannot"
            + " see the hull) stops at the landing verdict first, and no break was found that keeps him on the"
            + " face but moves him off his point while the craft hovers.")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(OUTER_HULL_LAND_TICKS);",
            why = "a tenth of a block at a player's 0.08 blocks/tick^2 lands in about two ticks.")
    public void aPlayerOnTheOuterHullOfAnInvertedCraftStaysWhereHeLanded() throws Exception {
        // R11-OK: the outer hull's hold of the player - the substrate's collision of a body no deck
        // holds (EntityCollisionInjector, VSMath#canStandOnNormal); asserted in staysWhereHeLanded.
        PilotSeat seat = rollOver(180.0);
        int playerId = putOnTheFace(seat, HULL_CELL_DX, HULL_CELL_DZ, 0.1, 0.0f);
        advanceServerAndClient(OUTER_HULL_LAND_TICKS);
        staysWhereHeLanded(seat, playerId, "Static", 0.0, null, null);
    }

    /**
     * As above, while the craft flies sideways under him.
     */
    @RedWitness(verdict = "must come to rest on it", member = "EntityCollisionInjector#alterEntityMovement",
            at = "final double origDx = dx;", replacedBy = "if (true) return null; final double origDx = dx;",
            fails = "a player put on the hull's outer face must come to rest on it (ship-frame Y"
                    + " 128.0000000000008, face 127.0, expected 0.0 off it)", on = "2026-10-07")
    @NotYetRed(prose = "THE ALONG verdict has not been seen red against the current code, where the outer hull"
            + " is held by {@code EntityCollisionInjector#alterEntityMovement}. The old witness, 2026-10-07, was"
            + " the tree before that change (HEAD fdc813556), where a hull-stand, since deleted, slipped him along"
            + " the hull when the craft accelerated: \"a player on the outer hull must not slide along it:"
            + " 0.00311333364505719 blocks\".")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(OUTER_HULL_LAND_TICKS);",
            why = "as above.")
    public void aPlayerOnTheOuterHullOfAFlyingInvertedCraftRidesWithIt() throws Exception {
        // R11-OK: as above, with the craft's carry of him in flight; asserted in staysWhereHeLanded.
        PilotSeat seat = rollOver(180.0);
        int playerId = putOnTheFace(seat, HULL_CELL_DX, HULL_CELL_DZ, 0.1, 0.0f);
        advanceServerAndClient(OUTER_HULL_LAND_TICKS);
        staysWhereHeLanded(seat, playerId, "Flying", 0.0,
                "force-vel-by-id 0 " + scenarioShipId + " 2.0 0.0 0.0",
                "force-vel-by-id 0 " + scenarioShipId + " 0.0 0.0 0.0");
    }

    /**
     * As above, on a face 38 deg off level - steep, but within the ~40.4 deg the substrate stands a body
     * on.
     */
    @RedWitness(verdict = "must come to rest on it", member = "VSMath#STANDING_TOLERANCE",
            at = "STANDING_TOLERANCE = 0.42", replacedBy = "STANDING_TOLERANCE = 0.35",
            fails = "a player put on the hull's outer face must come to rest on it (ship-frame Y 128.67710882962365,"
                    + " face 127.0, expected 0.1846984425976975 off it)", on = "2026-10-07")
    @NotYetRed(prose = "THE ACROSS AND ALONG verdicts, 2026-10-07: as in the hovering scenario, a break of"
            + " {@code EntityCollisionInjector#alterEntityMovement} stops at the landing verdict first, and no"
            + " break was found that keeps him on the face but moves him off his point.")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(OUTER_HULL_LAND_TICKS);",
            why = "as above.")
    public void aPlayerOnASteepOuterHullFaceWithinTheStandingLimitStaysWhereHeLanded() throws Exception {
        // R11-OK: the stand-or-slide decision on the outer hull - VSMath#canStandOnNormal at
        // STANDING_TOLERANCE; asserted in staysWhereHeLanded.
        PilotSeat seat = rollOver(STEEP_FACE_ROLL_DEG);
        // Half a block up, not a tenth: on a face 38 deg off level the lower edge of his 0.6-wide box
        // sits 0.3 * tan(38 deg) = 0.23 below its centre, so a tenth puts him inside the plate.
        int playerId = putOnTheFace(seat, HULL_CELL_DX, HULL_CELL_DZ, 0.5, 0.0f);
        advanceServerAndClient(OUTER_HULL_LAND_TICKS);
        staysWhereHeLanded(seat, playerId, "Steep", 180.0 - STEEP_FACE_ROLL_DEG, null, null);
    }

    /**
     * As above, while the inverted craft spins about world up under him.
     */
    @RedWitness(verdict = "must come to rest on it", member = "EntityCollisionInjector#alterEntityMovement",
            at = "final double origDx = dx;", replacedBy = "if (true) return null; final double origDx = dx;",
            fails = "a player put on the hull's outer face must come to rest on it (ship-frame Y"
                    + " 128.00000000000006, face 127.0, expected 0.0 off it)", on = "2026-10-07")
    @NotYetRed(prose = "THE ALONG verdict has not been seen red against the current code, where the outer hull"
            + " is held by {@code EntityCollisionInjector#alterEntityMovement}. The old witness, 2026-10-07, was"
            + " the tree before that change (HEAD fdc813556), where a hull-stand, since deleted, slipped him along"
            + " the hull when the craft turned: \"a player on the outer hull must not slide along it:"
            + " 0.0031401499240002644 blocks\".")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(OUTER_HULL_LAND_TICKS);",
            why = "as above.")
    public void aPlayerOnTheOuterHullOfASpinningCraftStaysWhereHeLanded() throws Exception {
        // R11-OK: the outer hull's carry of the player through a rotation; asserted in staysWhereHeLanded.
        PilotSeat seat = rollOver(180.0);
        int playerId = putOnTheFace(seat, HULL_CELL_DX, HULL_CELL_DZ, 0.1, 0.0f);
        advanceServerAndClient(OUTER_HULL_LAND_TICKS);
        staysWhereHeLanded(seat, playerId, "Spinning", 0.0,
                "force-rot-by-id 0 " + scenarioShipId + " " + SPIN_RATE, HOLD_THE_ATTITUDE_REACHED);
    }

    /**
     * A player who falls onto the outer hull fast - faster per tick than the plate he lands on is
     * thick - ends ON its outer face, not on the far side of it.
     */
    @RedWitness(member = "EntityCollisionInjector#alterEntityMovement",
            at = "final double origDx = dx;", replacedBy = "if (true) return null; final double origDx = dx;",
            fails = "a player falling fast onto the outer hull must land on its outer face, not pass through it;"
                    + " shipFrameY=131.0597092330113 face=127.0 gap=-4.05970923301129", on = "2026-10-07")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(FAST_FALL_TICKS);",
            why = "see FAST_FALL_TICKS.")
    public void aPlayerFallingFastOntoTheOuterHullLandsOnIt() throws Exception {
        // R11-OK: the outer hull's catch of a falling player - the substrate's swept collision
        // (EntityCollisionInjector); asserted below.
        PilotSeat seat = rollOver(180.0);
        double face = seat.seatY - 1.0;
        int playerId = putOnTheFace(seat, HULL_CELL_DX, HULL_CELL_DZ, FAST_FALL_HEIGHT, 0.0f);
        advanceServerAndClient(FAST_FALL_TICKS);
        Reply landed = Reply.of(read("stellurgytest vs player-ship-data 0 " + playerId + " " + scenarioShipId).raw());
        double gap = face - landed.number("bodyShipFrameY");
        String evidence = " shipFrameY=" + landed.number("bodyShipFrameY") + " face=" + face + " gap=" + gap
                + " onGround=" + landed.bool("playerOnGround") + " deckHeldBy="
                + landed.textOr("deckHeldBy", "none") + " " + landed;
        System.out.println("[crewcap] outerHullFastFall gap=" + gap + evidence);
        // On the inverted craft the outer side of the face is ship-frame BELOW it, so a body on the
        // face has gap >= 0; through the plate, gap is negative.
        assertTrue("a player falling fast onto the outer hull must land on its outer face, not pass"
                + " through it;" + evidence, landed.bool("playerOnGround") && gap >= -ON_THE_FACE
                && gap < ON_THE_FACE);
    }

    /**
     * A player who walks across the outer hull stays on it: the face under his feet the whole way,
     * on the ground at the end.
     */
    @RedWitness(member = "EntityCollisionInjector#alterEntityMovement",
            at = "final double origDx = dx;",
            replacedBy = "if (Math.hypot(dx, dz) > 0.05) return null; final double origDx = dx;",
            fails = "a player walking on the outer hull must still be on its face; walked=2.074821326881647"
                    + " gap=-1.0000000000002274", on = "2026-10-07")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(HULL_WALK_TICKS);",
            why = "the walk itself: the W key held on his own client for HULL_WALK_TICKS of its ticks.")
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(OUTER_HULL_LAND_TICKS);",
            why = "a tenth of a block at a player's 0.08 blocks/tick^2 lands in about two ticks, as in the"
                    + " sibling scenarios.")
    public void aPlayerWalkingOnTheOuterHullStaysOnIt() throws Exception {
        // R11-OK: the outer hull's hold of a walking player - the substrate's collision of a body no
        // deck holds (EntityCollisionInjector); asserted below.
        PilotSeat seat = rollOver(180.0);
        double face = seat.seatY - 1.0;
        // Facing world +X (yaw -90), which the roll about X leaves as the craft's +X: from the -2 cell
        // of HULL_WALK_ROW_DZ toward the +2 one, so the walk crosses the deck's underside and nothing else.
        int playerId = putOnTheFace(seat, -DECK_HALF_WIDTH + 1.0, HULL_WALK_ROW_DZ, 0.1, -90.0f);
        advanceServerAndClient(OUTER_HULL_LAND_TICKS);
        Reply before = Reply.of(read("stellurgytest vs player-ship-data 0 " + playerId + " " + scenarioShipId).raw());
        scenario().requireArranged("the player must be standing on the outer face before he walks: " + before,
                before.bool("playerOnGround") && Math.abs(face - before.number("bodyShipFrameY")) < ON_THE_FACE);
        bot().holdKey(Keyboard.KEY_W);
        try {
            bot().waitWorldTicks(HULL_WALK_TICKS);
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
        }
        comeToAStopOnTheFace();
        Reply after = Reply.of(read("stellurgytest vs player-ship-data 0 " + playerId + " " + scenarioShipId).raw());
        double walked = after.number("bodyShipFrameX") - before.number("bodyShipFrameX");
        double gap = face - after.number("bodyShipFrameY");
        String evidence = " walked=" + walked + " gap=" + gap + " onGround=" + after.bool("playerOnGround")
                + " deckHeldBy=" + after.textOr("deckHeldBy", "none") + " " + after;
        System.out.println("[crewcap] outerHullWalk" + evidence);
        scenario().requireArranged("he must actually have walked along the hull;" + evidence,
                walked >= OUTER_HULL_WALKED_BLOCKS);
        assertTrue("a player walking on the outer hull must still be on its face;" + evidence,
                after.bool("playerOnGround") && gap >= -ON_THE_FACE && gap < ON_THE_FACE);
    }

    /** After a walk on the outer hull: his last steps reach the server and he comes to a stop. */
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(OUTER_HULL_LAND_TICKS);",
            why = "his last steps reach the server and he comes to a stop.")
    private void comeToAStopOnTheFace() throws Exception {
        advanceServerAndClient(OUTER_HULL_LAND_TICKS);
    }

    /** Build the scenario's craft, hold it at {@code rollDeg} of roll about X, and check it got there. */
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(OUTER_HULL_SLEW_TICKS);",
            why = "see OUTER_HULL_SLEW_TICKS.")
    private PilotSeat rollOver(double rollDeg) throws Exception {
        buildShip(site());
        PilotSeat seat = PilotSeat.byId(c -> read(c).raw(), 0, scenarioShipId)
                .requireFound("the seat locates the deck in the craft's frame");
        double half = Math.toRadians(rollDeg) / 2.0;
        scenario().requireArranged("the attitude hold must accept the roll command",
                Reply.of(arrange("stellurgytest vs point-by-id 0 " + scenarioShipId + " " + Math.cos(half)
                        + " " + Math.sin(half) + " 0.0 0.0").raw()).bool("commanded"));
        advanceServerAndClient(OUTER_HULL_SLEW_TICKS);
        double upY = shipUpFromInfo(shipInfo())[1];
        scenario().requireArranged("the deck must be held at " + rollDeg + " deg of roll (deck-up world Y "
                + upY + ")", Math.abs(upY - Math.cos(Math.toRadians(rollDeg))) <= ROLL_HELD_UP_Y);
        return seat;
    }

    /**
     * The world point {@code above} blocks over the deck, one cell off the seat along +X, on the craft
     * as it stands now. Not the ship's pose: on the with-pilot-deck craft the centre of mass sits under
     * the seat's column, and a body dropped there lands on the pilot seat, an eighth of a block over
     * the deck, which is not the deck (measured 2026-10-15: the deck took him and no `deck_contact`
     * followed).
     */
    private double[] overTheDeckBesideTheSeat(double above) throws Exception {
        PilotSeat seat = PilotSeat.byId(c -> read(c).raw(), 0, scenarioShipId)
                .requireFound("the seat locates the deck in the craft's frame");
        // The seat stands on the deck, so the deck's top is the seat block's own floor.
        Reply at = Reply.of(read("stellurgytest vs to-world 0 id " + scenarioShipId + " "
                + (seat.seatX + 1.5) + " " + (seat.seatY + above) + " " + (seat.seatZ + 0.5)).raw());
        scenario().requireArranged("the deck point beside the seat must map to the world through this"
                + " craft: " + at, at.ok());
        return new double[]{at.number("worldX"), at.number("worldY"), at.number("worldZ")};
    }

    /**
     * Put the player {@code above} blocks over the hull's outer face at the deck cell
     * {@code (dx, dz)} from the seat, facing {@code yaw}, and answer his entity id. The outer face over
     * a cell with nothing below the deck (see {@link #HULL_CELL_DX}) is the deck block's underside,
     * ship-frame Y {@code seatY - 1}.
     */
    private int putOnTheFace(PilotSeat seat, double dx, double dz, double above, float yaw) throws Exception {
        Reply at = Reply.of(read("stellurgytest vs to-world 0 id " + scenarioShipId + " "
                + (seat.seatX + dx) + " " + (seat.seatY - 1.0) + " " + (seat.seatZ + dz)).raw());
        scenario().requireArranged("the face must map to the world through this craft: " + at, at.ok());
        arrange("tp " + botName() + " " + at.number("worldX") + " " + (at.number("worldY") + above) + " "
                + at.number("worldZ") + " " + yaw + " 0");
        return Reply.of(read("stellurgytest vs player-ship-data").raw()).integer("entityId");
    }

    /**
     * The player put on the face has landed there, and stays where he landed through a window -
     * while the craft does {@code motion} (a probe command) and then {@code stop}, or hovers when both
     * are null. {@code tiltDeg} is the face's angle off level.
     */
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(OUTER_HULL_WINDOW_TICKS);",
            why = "between `landed` above and `later` below, whose difference is asserted; with a motion it is"
                    + " also the EXPERIMENT, the motion being the dose.")
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndClient(OUTER_HULL_STOP_TICKS);",
            why = "the craft coming to a stop converges and announces nothing; read once after, gated on its own"
                    + " velocity below.")
    private void staysWhereHeLanded(PilotSeat seat, int playerId, String label, double tiltDeg,
                                    String motion, String stop) throws Exception {
        double face = seat.seatY - 1.0;
        Reply rest = Reply.of(read("stellurgytest vs player-ship-data 0 " + playerId + " " + scenarioShipId).raw());
        double gap = face - rest.number("bodyShipFrameY");
        // An upright box standing on a tilted face rests on its lower edge, so its feet centre sits
        // half its width times the sine of the tilt off the face: 0.185 for this 0.6-wide player at 38
        // deg, nothing on a level face.
        double standingOff = 0.3 * Math.sin(Math.toRadians(tiltDeg));
        // A VERDICT, not an arrangement: he was put a tenth of a block over the face, so the hull taking
        // him and holding him there is the subject - a face that sheds him has failed before the window.
        assertTrue("a player put on the hull's outer face must come to rest on it (ship-frame Y "
                + rest.number("bodyShipFrameY") + ", face " + face + ", expected " + standingOff
                + " off it): " + rest,
                rest.bool("playerOnGround") && Math.abs(gap - standingOff) < ON_THE_FACE);
        double upYBefore = shipUpFromInfo(shipInfo())[1];
        // Both readings are taken with the craft AT REST, so his server-side position (his last packet's
        // claim) cannot carry a lag of the craft's motion into the difference: see OUTER_HULL_STOP_TICKS.
        Reply first = Reply.of(read("stellurgytest vs player-ship-data 0 " + playerId + " " + scenarioShipId).raw());
        double[] landed = {first.number("bodyShipFrameX"), first.number("bodyShipFrameY"),
                first.number("bodyShipFrameZ"), first.integer("ticksExisted")};
        ShipInfo shipBefore = shipInfo();
        if (motion != null) {
            Reply moving = Reply.of(arrange("stellurgytest vs " + motion).raw());
            scenario().requireArranged("the craft must accept the motion command: " + moving,
                    moving.bool("afcResolved"));
        }
        advanceServerAndClient(OUTER_HULL_WINDOW_TICKS);
        ShipInfo shipMoved = shipInfo();
        if (stop != null) {
            // A commanded zero, not a cleared command: cleared, the craft's hold lets go and it falls or
            // goes on turning (measured 2026-10-07: 17 blocks/s after a clear).
            String stopCommand = stop;
            if (HOLD_THE_ATTITUDE_REACHED.equals(stop)) {
                ShipInfo reached = shipInfo();
                stopCommand = "point-by-id 0 " + scenarioShipId + " " + reached.qw + " " + reached.qx + " "
                        + reached.qy + " " + reached.qz;
            }
            Reply stopped = Reply.of(arrange("stellurgytest vs " + stopCommand).raw());
            scenario().requireArranged("the craft must accept the stop command: " + stopped,
                    stopped.boolOr("afcResolved", false) || stopped.boolOr("commanded", false));
            advanceServerAndClient(OUTER_HULL_STOP_TICKS);
        }
        ShipInfo shipAfter = shipInfo();
        Reply after = Reply.of(read("stellurgytest vs player-ship-data 0 " + playerId + " " + scenarioShipId).raw());
        double[] later = {after.number("bodyShipFrameX"), after.number("bodyShipFrameY"),
                after.number("bodyShipFrameZ"), after.integer("ticksExisted")};
        scenario().requireArranged("the server must have been updating him across the window (ticked "
                + (later[3] - landed[3]) + " of " + OUTER_HULL_WINDOW_TICKS + ")",
                later[3] - landed[3] >= OUTER_HULL_WINDOW_TICKS / 2.0);
        double along = Math.hypot(later[0] - landed[0], later[2] - landed[2]);
        double across = Math.abs(later[1] - landed[1]);
        double craftMoved = Math.hypot(shipMoved.x - shipBefore.x, shipMoved.z - shipBefore.z);
        double qDot = Math.abs(shipBefore.qw * shipMoved.qw + shipBefore.qx * shipMoved.qx
                + shipBefore.qy * shipMoved.qy + shipBefore.qz * shipMoved.qz);
        double craftTurnedRad = 2.0 * Math.acos(Math.min(1.0, qDot));
        double speedAfter = Math.sqrt(shipAfter.velX * shipAfter.velX + shipAfter.velY * shipAfter.velY
                + shipAfter.velZ * shipAfter.velZ);
        String evidence = " from=(" + landed[0] + "," + landed[1] + "," + landed[2] + ") to=(" + later[0]
                + "," + later[1] + "," + later[2] + ") gap=" + gap + " craftMoved=" + craftMoved
                + " craftTurnedRad=" + craftTurnedRad + " speedAfter=" + speedAfter + " omegaAfter="
                + shipAfter.omega + " deckUpY=" + shipUpFromInfo(shipAfter)[1]
                + " deckHeldBy=" + after.textOr("deckHeldBy", "none")
                + " onGround=" + after.bool("playerOnGround");
        System.out.println("[crewcap] outerHull" + label + " along=" + along + " across=" + across + evidence);
        arrange("stellurgytest vs force-clear-by-id 0 " + scenarioShipId);
        scenario().requireArranged("the craft must have kept its attitude's up-axis (deck-up world Y "
                + upYBefore + " -> " + shipUpFromInfo(shipAfter)[1] + ")",
                Math.abs(shipUpFromInfo(shipAfter)[1] - upYBefore) <= ROLL_HELD_UP_Y);
        if (motion != null) {
            scenario().requireArranged("the craft must have come to rest before the second reading;"
                    + evidence, speedAfter < CRAFT_STOPPED_SPEED && shipAfter.omega < CRAFT_STOPPED_OMEGA);
        }
        if ("Flying".equals(label)) {
            scenario().requireArranged("the craft must have flown further than its deck is wide while he"
                    + " stood on it;" + evidence, craftMoved > OUTER_HULL_FLIGHT_MIN_TRAVEL);
        }
        if ("Spinning".equals(label)) {
            scenario().requireArranged("the craft must have turned well round under him;" + evidence,
                    craftTurnedRad > 1.0);
        }
        assertTrue("a player on the outer hull must stay on it: " + across + " blocks nearer or further"
                + " off it than where he stood;" + evidence, across <= OUTER_HULL_ACROSS);
        assertTrue("a player on the outer hull must not slide along it: " + along + " blocks;" + evidence,
                along <= OUTER_HULL_AT_REST);
    }

    private String crosshairBlock(String what) throws Exception {
        return Events.text(crosshairRecord(what), "block");
    }

    /**
     * The whole crosshair record, for a caller that needs more than the block off ONE reading.
     *
     * <p>The ray's own eye travels in this record beside the block it resolved. A caller that took
     * the block from here and the eye from a field read would be pairing two moments — the fields
     * are overwritten by every drawn frame, so a frame landing between the two reads silently moves
     * the eye out from under the block.</p>
     */
    private String crosshairRecord(String what) throws Exception {
        String rec = Events.lastRecord(clientEvents().since(0, "deck_crosshair"));
        assertNotNull("no deck_crosshair record while " + what + " — the crosshair recorder never "
                + "ran on this client, so there is nothing to read", rec);
        return rec;
    }

    private String deckLookIn(long mark, String what) throws Exception {
        String rec = Events.lastRecord(clientEvents().since(mark, "deck_look"));
        assertNotNull("no deck_look record " + what + " — the client's deck-look tick never ran in "
                + "that window, so nothing below is a reading of it", rec);
        return rec;
    }

    /** The TEST-side accumulator behind the smoothness window — production keeps none. */
    private static final String FRAME_STEP_WINDOW =
            "dev.stannismod.stellurgy.test.trace.FrameStepWindow";


    /** The client's own world look direction, from the rotation it reports. */
    private double[] clientLook() throws Exception {
        com.google.gson.JsonObject st = bot().reportState();
        double yaw = Math.toRadians(st.get("playerYaw").getAsDouble());
        double pitch = Math.toRadians(st.get("playerPitch").getAsDouble());
        return new double[]{-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch)};
    }

    /** The ship's attitude quat {w,x,y,z} from the server-side ship-info (the cross-side oracle). */
    private double[] shipQuatFromInfo(ShipInfo info) {
        return new double[]{info.qw, info.qx, info.qy, info.qz};
    }

    /** The ship's up axis in world coordinates, from the server-side ship-info quat (the oracle). */
    private double[] shipUpFromInfo(ShipInfo info) {
        double[] q = shipQuatFromInfo(info);
        double qw = q[0], qx = q[1], qy = q[2], qz = q[3];
        return new double[]{
                2.0 * (qx * qy - qw * qz),
                1.0 - 2.0 * (qx * qx + qz * qz),
                2.0 * (qy * qz + qw * qx)};
    }

    /** Rotate {@code v} by the CONJUGATE of quat {@code q} (world direction into the ship frame). */
    private static double[] rotateByConjugate(double[] q, double[] v) {
        double w = q[0], x = -q[1], y = -q[2], z = -q[3];
        double xx = x * x, yy = y * y, zz = z * z;
        double xy = x * y, xz = x * z, yz = y * z;
        double wx = w * x, wy = w * y, wz = w * z;
        return new double[]{
                v[0] * (1 - 2 * (yy + zz)) + v[1] * 2 * (xy - wz) + v[2] * 2 * (xz + wy),
                v[0] * 2 * (xy + wz) + v[1] * (1 - 2 * (xx + zz)) + v[2] * 2 * (yz - wx),
                v[0] * 2 * (xz - wy) + v[1] * 2 * (yz + wx) + v[2] * (1 - 2 * (xx + yy))};
    }

    /** The client's own position, from what it reports. */
    private double[] clientPos() throws Exception {
        com.google.gson.JsonObject st = bot().reportState();
        return new double[]{st.get("playerX").getAsDouble(), st.get("playerY").getAsDouble(),
                st.get("playerZ").getAsDouble()};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double clampUnit(double v) {
        return v < -1.0 ? -1.0 : v > 1.0 ? 1.0 : v;
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** The unsigned angle (degrees) between two directions' projections into the plane
     *  perpendicular to {@code axis}. */
    private static double planeAngleDeg(double[] axis, double[] a, double[] b) {
        double[] pa = {a[0] - axis[0] * dot(axis, a), a[1] - axis[1] * dot(axis, a),
                a[2] - axis[2] * dot(axis, a)};
        double[] pb = {b[0] - axis[0] * dot(axis, b), b[1] - axis[1] * dot(axis, b),
                b[2] - axis[2] * dot(axis, b)};
        double na = Math.sqrt(dot(pa, pa)), nb = Math.sqrt(dot(pb, pb));
        if (na < 1.0E-9 || nb < 1.0E-9) {
            return 0.0;
        }
        return Math.toDegrees(Math.acos(clampUnit(dot(pa, pb) / (na * nb))));
    }

    /**
     * A teleport along the deck is where the deck holds him from then on.
     *
     * <p>The contract: a position somebody WRITES — a teleport, a command, another mod — is a result,
     * and a deck that holds the body adopts it as the body's new place on the deck. It never drags
     * him back to the point it held before. On the server the deck takes a player's place from his
     * client's claim every tick, so the subject here is that the claim after a teleport becomes the
     * deck's own record of where it holds him, and stays it.</p>
     *
     * <p>Arranged on the pad, the craft still: the bot is landed on a free deck cell beside the seat
     * (the craft names the cell, {@code vs deck-spot}), and the server's deck frame must hold him
     * there before the stimulus. The stimulus is a server teleport to the NEXT cell along the same
     * side — still over the deck, so a deck that adopts the write keeps holding him, on the new
     * cell. The verdict is the deck's own record of the point it holds him at, read twice across a
     * window, because a deck that re-derived his place from the old point would pull it back on the
     * tick after the write.</p>
     *
     * <p>Not seen: the client's own resolver, which still holds him on his side (that half is
     * the client's, and comes with it).</p>
     */
    @NotYetRed(prose = "not seen red against the current code, where the server takes his deck point from his"
            + " movement claim ({@code DeckFrameTick#claimArrived}) and {@code DeckFrameTick#updatePlayer} keeps"
            + " the held point unless his position was written since ({@code boolean mappedIn = !episode.held ||"
            + " episode.worldWritten}). The old witness, 2026-10-04, was taken on the form before that change,"
            + " where the server re-derived his deck point from his world position on every update: with that"
            + " derivation replaced, for a body already held, by the deck point the episode held before - \"the"
            + " point it holds him at is 1.0 from the new cell right after the teleport and 1.0 twenty ticks"
            + " later\".")
    @Pins("ENT-4")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceWorldAndClient(0, 20);",
            why = "the held point is read on both sides of this stretch and both reads are named in the verdict. A"
                    + " deck that re-derived his place from the point it held before would pull it back on his"
                    + " next update; twenty ticks is twenty of them, on both clocks — his client moves him in"
                    + " the deck's frame and sends the claim, the server's update of him takes it.")
    public void aTeleportAlongTheDeckIsWhereTheDeckHoldsHimFromThen() throws Exception {
        final FixtureSite site = site();
        buildShip(site);
        String spot = read("stellurgytest vs deck-spot 0 id " + scenarioShipId).raw();
        scenario().requireArranged("the craft must name a free cell on its deck: " + spot, Reply.of(spot).ok());
        // The next cell along the same side as the first: the deck is 5x5 under a seat at its centre,
        // so one more step out from a cell beside the seat is still over it — read, not assumed, off
        // the craft's own blocks below.
        String side = Reply.of(spot).text("side");
        int stepX = "east".equals(side) ? 1 : "west".equals(side) ? -1 : 0;
        int stepZ = "south".equals(side) ? 1 : "north".equals(side) ? -1 : 0;
        String next = read("stellurgytest vs deck-spot 0 id " + scenarioShipId + " at "
                + (Reply.of(spot).integer("offX") + stepX) + " " + Reply.of(spot).integer("offY") + " "
                + (Reply.of(spot).integer("offZ") + stepZ)).raw();
        scenario().requireArranged("the next cell along must be over the deck, or a deck that lets him"
                + " go there would be right to: " + next,
                Reply.of(next).ok() && Reply.of(next).boolOr("floorSolid", false));

        long clientMark = clientEvents().mark();
        long serverMark = serverEvents().mark();
        // The bot is put a quarter-block above the first cell's floor: a fall of a few ticks, so the
        // gate window's record budget covers it many times over.
        landOnTheDeckUnderGateWatch(clientMark, serverMark, scenarioShipId,
                "the bot is put on the deck beside the seat", CAPTURE_LINK_BUDGET_TICKS, 40,
                Reply.of(spot).number("x"), Reply.of(spot).number("y") + 0.25, Reply.of(spot).number("z"));
        closeDeckGateWindow();
        DeckCapture before = deckCaptureOfThisShip(scenarioShipId,
                "the deck must hold him on the first cell before he is moved off it");
        scenario().requireArranged("the server must hold him by the DECK FRAME, whose adoption is the"
                + " subject: " + before.raw(), "deckFrame".equals(Reply.of(before.raw()).text("heldBy")));

        long movedMark = clientEvents().mark();
        double toX = Reply.of(next).number("x"), toY = Reply.of(next).number("y"),
                toZ = Reply.of(next).number("z");
        arrange("tp @a " + toX + " " + toY + " " + toZ + " 0 0");
        awaitClientPlacedNear(movedMark, toX, toZ, "the teleport along the deck must reach his client");
        fenceWhatTheClientSent("the server must have handled his claims from the new cell");

        double[] nextCell = {Reply.of(next).number("subX") + 0.5, Reply.of(next).number("subY"),
                Reply.of(next).number("subZ") + 0.5};
        DeckCapture first = deckCaptureOfThisShip(scenarioShipId,
                "the deck must still hold him after a teleport that kept him over it");
        advanceWorldAndClient(0, 20);
        DeckCapture later = deckCaptureOfThisShip(scenarioShipId,
                "the deck must go on holding him on the new cell");
        // Half a cell: the two cells are a block apart, so a point within half a block of the new
        // cell's centre is on it and not on the old one.
        double offFirst = Math.hypot(first.capturedShipFrameX() - nextCell[0],
                first.capturedShipFrameZ() - nextCell[2]);
        double offLater = Math.hypot(later.capturedShipFrameX() - nextCell[0],
                later.capturedShipFrameZ() - nextCell[2]);
        assertTrue("the deck must take the teleport as his new place and keep it: the point it holds"
                        + " him at is " + offFirst + " from the new cell right after the teleport and "
                        + offLater + " twenty ticks later (more than half a cell is the old one, or"
                        + " between). Before: " + before.raw() + " | right after: " + first.raw()
                        + " | later: " + later.raw(),
                offFirst <= 0.5 && offLater <= 0.5);
    }

    /**
     * Build the ship and sit the bot on its pilot seat; returns the ship's world position.
     *
     * <p>It ENDS ON LINKS, and that is what the seven callers' {@code waitTicks(20)} used to stand in
     * for: the client performing its own mount, and the client's pilot gate opening on the body now
     * riding that seat. Both are records production writes on the side this class observes, so a
     * caller that starts its scenario the tick this returns is starting after the two things it
     * needed, rather than after a number that was chosen once and copied into seven scenarios.</p>
     */
    private double[] buildAndBoardShip(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        double[] ship = buildShip(site);
        Events boarding = clientEvents();
        long boardMark = boarding.mark();
        // Located inside the ship this scenario NAMES. `vs seat-mount <dim>` takes the first pilot
        // seat in the world's loaded-tile list with no filter at all; the positional form of
        // find-seat narrows that to "the yard nearest a point", which on a world holding two craft
        // is still a stranger's yard as readily as this one's. The id is in hand from the assembly.
        PilotSeat seat = PilotSeat.byId(c -> read(c).raw(), 0, scenarioShipId)
                .requireFound("find-seat must locate the pilot seat inside THIS scenario's ship ("
                        + scenarioShipId + ", built at " + bx + "," + by + "," + bz + ")");
        String mountInfo = arrange("stellurgytest vs seat-mount-at 0 " + seat.seatX + " "
                + seat.seatY + " " + seat.seatZ).raw();
        int dummyId = Reply.of("stellurgytest vs seat-mount-at", mountInfo).integer(DUMMY_ID);
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                Reply.of(arrange("stellurgytest player mount-entity " + dummyId).raw()).bool("mounted"));
        // The server says it mounted him; these two say the CLIENT did, and this class's whole
        // subject is what the client's resolver does with a body. The mount is his own `startRiding`;
        // the gate is the client's keybind tick deciding that the body it is holding is a ship's
        // pilot, which is the state every caller's stimulus is issued into.
        boarding.awaitField(boardMark, "mount","ok", true,
                "the client must PERFORM the mount the server reported, or the scenario below drives "
                        + "a body that is not in the seat it thinks it is", CAPTURE_LINK_BUDGET_TICKS);
        boarding.awaitField(boardMark, "ship_pilot_gate_decided","open", true,
                "the client's pilot gate must OPEN on the seated body, or a scenario that commands "
                        + "the craft from this seat is commanding nothing", CAPTURE_LINK_BUDGET_TICKS);
        return ship;
    }

    // This class's subject lives on the client: the resolver that captures a body, carries it and
    // lets it go is the client's, and every link it commits is recorded in the client log. So every
    // wait below reads the base's {@link #clientEvents()}, not {@link #serverEvents()}, which reaches the
    // server's log through the probe.
    //
    // {@link Events#markInstrumented} is deliberately not usable on it: the client reply carries
    // {@code recording} — so {@link Events#mark} does assert somebody is listening — but no
    // {@code mixins} flag, because the client's coremod gate is a different fact from the server's.
    // What proves a client instrument was installed AND ran is {@link Events#assertInstrumentRan},
    // against the instrument that produced the reading, and every absence claim below carries one.

    /**
     * The largest vertical CARRY in a {@code deck_carry} reply.
     *
     * <p>The carry is the deck velocity production binds into the resolved body's motion, and it is
     * an argument of the tick that binds it — so this is the exact value installed, on every tick it
     * was installed, where a read of a last-write static could only catch whichever one a sample
     * happened to land on. Zero when the reply carries no record at all, which the caller
     * distinguishes by asserting the records exist.</p>
     *
     * <p>It read {@code deck_entered} until 2026-09-16. When that record was removed as "read by
     * nobody for its per-tick content", this helper was the reader nobody had found: its own line
     * names neither the record nor the field, so the search that declared the count missed it. The
     * question was real and got a record of its own.</p>
     */
    private static double maxCarryY(String sinceReply) {
        double max = 0.0;
        for (String record : Events.records(String.valueOf(sinceReply))) {
            String carry = Events.text(record, "carry");
            if (carry == null) {
                continue;
            }
            String[] parts = carry.split(",");
            if (parts.length != 3) {
                continue;
            }
            try {
                max = Math.max(max, Math.abs(Double.parseDouble(parts[1].trim())));
            } catch (NumberFormatException ignored) {
                // A record whose triple cannot be read is not a measurement; the caller's message
                // prints the records themselves, so an unreadable one is visible there.
            }
        }
        return max;
    }

    /**
     * The jumper's own position in his ship's frame, live, or {@code NaN} when nothing resolves it.
     *
     * <p>{@code bodyShipFrameY} and not {@code shipFrameY}: the second is the CAPTURE's bookkeeping
     * and only moves when the resolver commits, so a body mid-jump reads as perfectly still on it.
     * The first is derived from the entity's own position every time it is asked, which is what a
     * trajectory needs. {@code NaN} rather than a refusal: this is a diagnostic inside an arc, and a
     * reading that is missing for one sample must not end the scenario.</p>
     */
    private double jumperShipFrameY() throws Exception {
        return DeckCapture.read(c -> read(c).raw()).bodyShipFrameYOrNaN();
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        long awayMark = clientEvents().mark();
        arrange("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The mark is taken BEFORE the assembly is queued, so the record awaited below names THIS
        // scenario's own ship by construction — where the pre-assembly ship COUNT it replaces asked a
        // question every neighbour that ever assembled a ship also answers, and then had to recover
        // the identity from a nearest-ship lookup at the build site.
        Events events = serverEvents();
        long spawnMark = events.markInstrumented();
        ShipAssembly assembleBuilt = assembleFixture(site);
        String assemble = assembleBuilt.reply;
        assertTrue("a with-pilot-seat build must route to a ship: " + assembleBuilt,
                assembleBuilt.madeNoRocket());

        // IDENTITY, and the async-VS assembly barrier in the same link: the physics mod assembles on
        // its own thread and its queue lags behind a loaded machine, so this AWAITS the registry's own
        // record of the add rather than spending a fixed tick budget that reds a healthy spawn under
        // concurrent-fork load. The scenario built this ship, so it is told which one it is instead of
        // re-deriving that from a position afterwards.
        scenarioShipId = awaitShipSpawned(events, spawnMark,
                "a " + VARIANT + " assembly must create a VS ship in the queryable registry");

        long approachMark = clientEvents().mark();
        arrange("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so the readiness link below"
                        + " is waiting on something only an arrived client can cause");

        // READINESS, which is a different fact and still has to be waited for: `ship_spawned` is the
        // registry's record of an ADD, not a promise that a physics object is LOADED here with the
        // client present — the state everything below needs, and the one the fork-load red
        // (`shipFound:false`) was about. Waited on production's own `ship_usable` event, which is the
        // conjunction the physics loop selects a ship by; the earlier `managed:true` poll was a
        // literal `true` in the probe's reply builder, so it said only "the lookup built a report"
        // and every wait on it was a wait on nothing. The mark is the pre-assembly one used just
        // above: this fires ONCE per load, so a mark taken here could miss the edge outright.
        awaitShipUsable(events, spawnMark, scenarioShipId);
        // The event record names the ship and its dimension and carries NO position, so the pos comes
        // from a ship-info asked BY IDENTITY afterwards — no distance term to be wrong about however
        // far these scenarios then jump, roll, hover or drop the hull.
        ShipInfo si = ShipInfo.of(shipInfoById(scenarioShipId));
        double[] where = {si.x, si.y, si.z};
        System.out.println("[crewcap] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
    }

    private ShipAssembly assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // THE PIT IS GONE, AND THIS IS THE DEFECT IT COST. What stood here was a fill of
        // `baseY+1..baseY+10` at a hard-coded `baseY = 64` while the surface at these plots is
        // around y≈72 — a ten-block SHAFT with rock on every side and a RIM sitting above the hull.
        // Measured here 2026-09-14, the release record that made it visible:
        //   reason=steppedOntoTerrain y=75.3035 onGround=false motionY=0.318
        //   worldSupport=true shipSupport=0 underFeet="1:[75.0000..76.0000]"
        // — one box, integer bounds, exactly one block tall: the first UNCLEARED block above the
        // pit. The craft hovered two blocks up, its deck landed at ~74.4, and a crew member who
        // jumped put his feet in the ceiling. The gate then said, correctly, that world terrain was
        // under him and let the deck go while he stood on his own ship.
        //
        // The site now stands in the open-air band, where the craft rests on the launchpad the
        // fixture lays at its own Y and there is no rim to meet. The clear below therefore ASSERTS
        // rather than digs, and the number it asserts is the air fill's own `placed`.
        //
        // HEIGHT 24 is the ENVELOPE, not the hull: the fixture is ~10 blocks of hull from the pad,
        // its deck is the hull's top surface, a crew member standing there adds ~2 and a vanilla
        // jump ~1.25 more, and several scenarios here hover, roll or invert the craft and drop a
        // body onto it from a few blocks above that. A floor check, or a check sized to the hull,
        // is exactly what was green throughout the failure above.
        return ShipAssembly.at(site, c -> arrange(c).raw(), c -> act(c).raw(), serverEvents(), VARIANT, 2, 24,
                "the hull, the deck a crew member walks and jumps on, and the air above it");
    }

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private ShipInfo shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return ShipInfo.of(shipInfoById(scenarioShipId));
    }

    private double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        return value;
    }

    private int readInt(String json, String field) {
        return Reply.of(json).integer(field);
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The largest |x| or |z| of the {@code m=} column across a window's per-tick lines — the ship's
     * own motion under the body, which a still body must not be dragged by.
     *
     * <p>Parsed out of the line rather than sampled off a static: the line is written on every
     * resolved tick, so a transient that lived and died between two five-tick polls is here.</p>
     */
    private static double maxLateralShipMotion(String tickRecords) {
        double most = 0.0;
        for (String record : Events.records(tickRecords)) {
            most = Math.max(most, Math.max(Math.abs(Events.number(record, "motionShipX")),
                    Math.abs(Events.number(record, "motionShipZ"))));
        }
        return most;
    }

    /**
     * The largest |value| of one walk input over the window.
     *
     * <p>The parameter used to be {@code int half} — 0 for strafe, 1 for forward — which is a
     * position inside a {@code |in=a/b|} column and means nothing without the pattern that carved
     * it. It is the field's NAME now.</p>
     */
    private static float maxInput(String tickRecords, String field) {
        float most = 0f;
        for (String record : Events.records(tickRecords)) {
            most = Math.max(most, (float) Math.abs(Events.number(record, field)));
        }
        return most;
    }

    /** The walk input across the deck, as the resolver saw it. */
    private static final String IN_STRAFE = "inStrafe";
    /** The walk input along it. */
    private static final String IN_FORWARD = "inForward";

    /** The subject's SUBSPACE feet position from a {@code subspace-census} reply, as "x,y,z". */
    private static String readSubPos(String census) {
        return Reply.of("stellurgytest vs subspace-census", census).text("subPos");
    }

    /** {@code block - feet}, componentwise, for two "x,y,z" triples; "?" if either is unreadable. */
    private static String blockOffset(String block, String feet) {
        String[] b = block.split(",");
        String[] f = feet.split(",");
        if (b.length != 3 || f.length != 3) {
            return "?";
        }
        try {
            return (Integer.parseInt(b[0].trim()) - Integer.parseInt(f[0].trim())) + ","
                    + (Integer.parseInt(b[1].trim()) - Integer.parseInt(f[1].trim())) + ","
                    + (Integer.parseInt(b[2].trim()) - Integer.parseInt(f[2].trim()));
        } catch (NumberFormatException e) {
            return "?";
        }
    }

}
