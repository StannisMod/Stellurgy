package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import com.google.gson.JsonObject;

import zmaster587.advancedRocketry.test.DeckCapture;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.ShipIdentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The relog-persistence contract of the ship-frame crew: a player who logs out standing ABOARD a
 * ship's deck logs back in ABOARD, at the same deck point, at any ship attitude - never handed to
 * world gravity while the capture re-seeds.
 *
 * <p>The subject is the HARD side of every axis this bug lives on: a real client player, captured
 * on the deck of an INVERTED ship (world gravity points away from the deck overhead, so any
 * un-captured tick starts a fall), across a REAL relog ({@code ClientBot.reconnect} - a full
 * server logout with player-data save and a fresh login, not a teleport).</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSCrewRelogPersistenceE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-crew-relog";
    }

    private static final String POS_X = "posX";
    private static final String POS_Y = "posY";
    private static final String POS_Z = "posZ";

    private static final String VARIANT = "with-pilot-deck";

    /**
     * THIS scenario's ship, by identity — read by {@code buildShip} off the assembly's own
     * {@code ship_spawned} record, and the address every later question and command uses. A
     * scenario that builds its own ship is told which ship that is; a radius bound would be a
     * mitigation rather than an identity, and these scenarios roll, hover and drop the ship on
     * purpose while a shared client always has a neighbour in candidacy.
     */
    private String scenarioShipId;

    /** The account every client harness launches under; the server keys his data by it. */
    private static final String BOT = "ForgeTestClient";

    private static final String SHIP_FRAME_X = "bodyShipFrameX";
    private static final String SHIP_FRAME_Y = "bodyShipFrameY";
    private static final String SHIP_FRAME_Z = "bodyShipFrameZ";

    /**
     * The ship's OWN rotation must never count as someone else moving the crew member.
     *
     * <p>This test fails if production breaks the contract that <b>a body standing on a deck stays
     * captured, and stays put in the ship frame, while the ship rotates under it</b> - at any rotation
     * rate, with no input at all.</p>
     *
     * <p><b>Why this leg exists, and why it is on a planet.</b> The maintainer reported being dragged
     * along his deck after a login in space, on a ship that was inverted. His own session log named the
     * mechanism: the external-move guard released the deck capture 25 times in ~50 s with
     * {@code externalMove(sub)} deltas up to 0.43 blocks/tick against its 0.2-block slack, while the
     * ship's attitude was settling at a 53.6 deg tilt - and between the release and the re-capture the
     * body belongs to vanilla and the physics mod, so it slides. The guard's own javadoc names this case
     * in advance: the slack absorbs "about one tick of ship motion at the body's radius from the
     * rotation centre ... a far-from-centre pilot on a violently spinning ship is the one case where it
     * could still approach the slack".</p>
     *
     * <p>So the DRIVER is ship rotation at radius - not the space cell, not the relog, not the login
     * restore, all of which merely accompanied it in the report. Reproducing the driver puts the subject
     * where rolling a ship is already proven to work, and it removes three variables from the
     * arrangement. A space-cell version would additionally need a way to rotate a ship inside a cell,
     * which the harness currently does not have.</p>
     *
     * <p>The idle window before the roll is the control: the guard must be quiet while nothing rotates,
     * or a count taken during the roll is not attributable to the roll.</p>
     */
    @Test
    public void aCrewMemberStandingOnADeckIsNotReleasedWhileTheShipRolls() throws Exception {
        requireHeIsHeldThroughARoll(site(), "ordinary world coordinates");
    }

    // A "far from origin" variant of this leg was tried and is REFUTED, so it is not here: the
    // 5120001.5 / 51200.9 coordinates in the reporter's log are the ship's SUBSPACE point, not a world
    // position. Measured - a ship built at world 6720 reports its crew at B=5120000.000, 51200.000 in
    // exactly the same range, because that is simply where Valkyrien Skies parks a subspace. There is
    // no coordinate-magnitude variable to vary.
    private void requireHeIsHeldThroughARoll(FixtureSite site, String where) throws Exception {
        // The caller asks the allocator for this site; it used to pass two coordinates it had chosen,
        // and the Y was a bare `final int by = 64;` until 2026-09-14 — a staging decision with no
        // name, which the fixture-site counter's own patterns could not see either.
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);
        // The mark BEFORE he is put on the deck. A capture is an EPISODE the resolver opens when a
        // body meets a deck, and the 80-tick sleep this replaces could only ask whether it happened
        // to be open when it finally looked - an episode that opened and closed inside the sleep,
        // or one that never opened at all, are the same reading to it.
        //
        // ...and the wait is over the EPISODE, not over the record: `deck_entered` is production's
        // per-tick commit, so a wait on one returns for a capture the deck may already have let go
        // of, and the one-shot read a line below then answers with no anchor at all. Measured here
        // on 2026-09-15, in the tick-burst leg. `awaitCaptureHeldBy` states the whole chain.
        Events events = events();
        long captureMark = events.markInstrumented();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        ShipIdentity.awaitCaptureHeldBy(events, captureMark, scenarioShipId,
                "the crew member must be TAKEN by THIS ship's deck"
                + " after being put on it - nothing below is about a deck capture until there is"
                + " one", CAPTURE_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        scenario().requireArranged("he must be captured on the deck before anything rotates: "
                        + deckCapture.raw(),
                deckCapture.alreadyTracked);

        // CONTROL: a still ship must produce no releases and no travel. Without it, a nonzero count
        // during the roll could belong to the arrangement (the walk onto the deck, the settle) rather
        // than to the rotation.
        //
        // Counted off the client's own RELEASE RECORDS, each carrying production's reason for it.
        // What stood here was a difference of two reads of a production counter, and the reader
        // answers -1 when the field cannot be read at all: an unreadable instrument produced
        // -1 - -1 == 0 and every zero-release pin in this class went green on it, saying nothing.
        long restReleaseMark = clientEvents().mark();
        long restMark = lastClientTick();
        bot().waitTicks(30);
        String restHistory = clientTickHistory();
        String restReleases = clientReleases(restReleaseMark, "the still-ship control window");
        long dropsDuringRest = guardReleases(restReleases);
        double restTravel = bodyPointTravel(restHistory, restMark);

        // THE DRIVER: roll the ship under him, and measure WHILE it turns - the release happens during
        // the attitude change, not after it.
        double h = Math.toRadians(170.0) / 2.0;
        scenario().requireArranged("the attitude hold must accept the roll command",
                Reply.of(exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0")).bool("commanded"));
        long rollMark = lastClientTick();
        long rollReleaseMark = clientEvents().mark();
        // The per-tick pose trace, armed on the axis this scenario turns on: a body sliding across a
        // rotating deck is an ANGLE going wrong, and a column of vertical positions cannot show it.
        long poseTraceMark = clientEvents().mark();
        bot().invokeStaticInt("org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject",
                "arTest$armPoseTrace", 200);
        // A WINDOW, not a poll. The header used to exit on `upY > -0.9`, which is the arrangement
        // gate below — so its green said "some sample was inverted" and could not be disproved. An
        // attitude converging under the hold IS a physical value, and the hold never decides it has
        // arrived, so there is no link to await; but a loop that re-reads until the value is
        // acceptable is a poll whatever the value is made of. Give the roll its ticks, then read.
        //
        // The window is the roll's own time rather than a budget: the hold slews at about 2 rad/s,
        // so half a turn is ~31 ticks, and an attitude this far past the reference reseed is ADOPTED
        // and then HELD — a window longer than the slew cannot walk back out of the state.
        bot().waitTicks(ROLL_WINDOW_TICKS);
        // The shared reading, which uses the full expression 1 - 2(qx^2 + qz^2). The single-axis
        // shortcut this leg carried answers a confident 1.0 for a ship that rolled about a different
        // axis, and would fail this ARRANGEMENT gate for the wrong reason.
        double upY = upYOf(shipInfo());
        String rollHistory = clientTickHistory();
        double rollSeatMiss = seatMiss(rollHistory, rollMark);
        // A zero deckStep is ambiguous on its own — a pass that never ran and a pass that ran on a
        // still ship both leave it there. This is the number that separates them.
        // Bodies re-seated DURING THIS ROLL, from the pass's own records. The counter this replaces
        // was a lifetime total, so on a shared client the sensitivity gate below — "the mechanism
        // must have run at all" — was satisfied by any earlier scenario's re-seat, which is a
        // statement about the JVM and not about this roll.
        // The deck's own step out from under a standing body: the displacement the leg below has to
        // be able to see, so it is measured rather than assumed. Taken as the LARGEST step over the
        // passes inside this roll's own marks, because it peaks mid-roll — the pass that ends the
        // turn carries the rate the craft finished at, which is zero. It used to be sampled off a
        // latest-value field, which answers with the last pass on either side whenever that was.
        long reseated = 0L;
        double deckStep = 0.0;
        for (String pass : Events.records(
                clientEvents().since(rollReleaseMark, "deck_reseat_pass"))) {
            reseated += (long) Events.number(pass, "bodies");
            double step = Events.number(pass, "maxStep");
            if (step > deckStep) {
                deckStep = step;
            }
        }
        String rollReleases = clientReleases(rollReleaseMark, "the roll");
        long dropsDuringRoll = guardReleases(rollReleases);
        double rollTravel = bodyPointTravel(rollHistory, rollMark);
        int resolvedDuringRoll = resolvedSince(rollHistory, rollMark);

        String observed = "\n  at rest:      drops=" + dropsDuringRest + " bodyTravel=" + restTravel
                + " resolved=" + resolvedSince(restHistory, restMark)
                + "\n  during roll:  drops=" + dropsDuringRoll + " bodyTravel=" + rollTravel
                + " resolved=" + resolvedDuringRoll + " upY=" + upY
                + "\n  seat:         miss=" + rollSeatMiss + " deckStep=" + deckStep
                + " reseated=" + reseated
                + "\n  releases at rest:     " + restReleases
                + "\n  releases in the roll: " + rollReleases
                + "\n" + mover();
        System.out.println("[roll-hold]" + observed
                + "\n[roll-hold] pose trace :: " + clientEvents().since(poseTraceMark, "client_deck_pose_tick"));

        scenario().requireArranged("the ship must actually have rotated, or nothing was driven (upY="
                + upY + ")" + observed, upY < INVERTED_UP_Y);
        scenario().requireArranged("the client must have resolved the body through the roll, or a clean "
                + "result describes the instrument" + observed, resolvedDuringRoll >= RESOLVED_TICKS_MIN);
        assertEquals("CONTROL: the guard must be quiet while the ship is still - otherwise the count "
                + "during the roll is not attributable to the rotation" + observed,
                0L, dropsDuringRest);

        assertEquals("the ship's own rotation must not count as someone else moving the crew member: "
                        + "the external-move guard released the capture " + dropsDuringRoll + " time(s) "
                        + "while the ship rolled under a body with no input. Between a release and the "
                        + "re-capture the body belongs to vanilla and the physics mod, which is what a "
                        + "player feels as being dragged along his own deck." + observed,
                0L, dropsDuringRoll);
        assertTrue("and he must not travel along the deck while it rotates under him (moved "
                        + rollTravel + " blocks in the ship frame, bar " + SHIP_FRAME_DRIFT_TOLERANCE + ")"
                        + observed,
                rollTravel < SHIP_FRAME_DRIFT_TOLERANCE);

        // SENSITIVITY, stated before the verdict: the leg below is only worth reading if the deck
        // genuinely stepped out from under him by more than the bar it is judged against. On this
        // 5x5 fixture the body stands under two blocks from the roll axis, so the displacement is
        // small in absolute terms however fast the craft turns - a bar chosen without this witness
        // would be a bar the scenario cannot fail.
        scenario().requireArranged("the mechanism that keeps him in step must have run at all, or a "
                        + "zero deckStep below is the absence of a pass rather than a still ship "
                        + "(reseated=" + reseated + ")" + observed,
                reseated > 0L);
        scenario().requireArranged("the deck must have stepped out from under him by several times "
                        + "the bar, or this fixture cannot exhibit the lag at all (deckStep="
                        + deckStep + ", bar=" + SEAT_MISS_TOLERANCE + ")" + observed,
                deckStep > DECK_STEP_SLACK_MULTIPLE * SEAT_MISS_TOLERANCE);
        assertTrue("a crew member must stand on his deck point at the pose the ship holds NOW, not "
                        + "at the one it held a tick ago: he was found " + rollSeatMiss + " blocks "
                        + "off the point this class committed for him (bar " + SEAT_MISS_TOLERANCE
                        + "), while the deck was stepping " + deckStep + " blocks a tick under him. "
                        + "That lag is what a player sees as his own body sliding around the deck "
                        + "and snapping back, twenty times a second, and it grows with his distance "
                        + "from the axis the craft is turning about." + observed,
                rollSeatMiss < SEAT_MISS_TOLERANCE);
    }

    /**
     * How far a carried body's own ship-frame point may travel across an observation window in which
     * the body itself gave no input, in blocks.
     *
     * <p>Named for the QUANTITY and not for an occasion, because two legs measure it: a ship rotating
     * under a standing body, and a body that relogged and stands still. The second used to compare
     * against a bare {@code 0.2} written at its assertion — the same digits as
     * {@link #GUARD_SLACK_BLOCKS}, which is a PER-TICK slack and therefore a different kind of
     * quantity. A path across a window and a rate per tick are not interchangeable however well the
     * numbers agree.</p>
     */
    private static final double SHIP_FRAME_DRIFT_TOLERANCE = 0.35D;

    /**
     * How far a body may be found from the deck point committed for it, in blocks.
     *
     * <p>Twenty times the record's own resolution (the per-tick history prints three decimals), and
     * a fifth of what the same body measured before the deck was made to carry it in step: 0.197
     * blocks a tick at 2 rad/s, two blocks off the axis. A bar between those two numbers separates
     * "in step" from "one tick behind" on this fixture; it says nothing about a wider craft, where
     * the same defect is the same angle through a longer arm.</p>
     */
    private static final double SEAT_MISS_TOLERANCE = 0.02D;

    /**
     * How far past vertical the craft must be for an inverted-deck leg to mean anything — the
     * world-frame Y of the deck normal.
     *
     * <p>The TEST'S OWN arrangement fact: production holds whatever attitude it was pointed at and
     * has no opinion about "inverted". At -0.9 the hull is about 155 degrees over, which is where
     * a body that is NOT being carried falls off rather than sliding.</p>
     */
    private static final double INVERTED_UP_Y = -0.9D;

    /**
     * How many client ticks must have RESOLVED the body through a window before any count taken over
     * that window can be read.
     *
     * <p>The TEST'S OWN, and it is an instrument check rather than a contract: a clean result over
     * a window the resolver never ran in describes the instrument, not the subject. Twenty ticks is
     * a second of resolution.</p>
     */
    private static final int RESOLVED_TICKS_MIN = 20;

    /**
     * How many of those ticks must have carried a WALK INPUT before a walk can be judged.
     *
     * <p>The TEST'S OWN: half of {@link #RESOLVED_TICKS_MIN}, because the key is pressed inside the
     * window rather than held across all of it. What it refuses is a key that never reached the
     * client's movement path at all.</p>
     */
    private static final int INPUT_TICKS_MIN = 10;

    /**
     * How many times {@link #SEAT_MISS_TOLERANCE} the deck must step out from under the body for
     * this fixture to exhibit the lag at all.
     *
     * <p>The TEST'S OWN sensitivity bar, and a MULTIPLE rather than a distance on purpose: it is
     * stated against the very bar the subject is then judged by, which is what makes a pass mean
     * the arrangement was hard rather than easy. Written as a bare {@code 5.0} beside that bar
     * until 2026-09-21, where it read as a number of blocks.</p>
     */
    private static final double DECK_STEP_SLACK_MULTIPLE = 5.0;

    /**
     * How far from his own deck spot a restored body may be and still count as AT it, in blocks.
     *
     * <p>The TEST'S OWN: a block and a half is about a body's width, so what it refuses is a body
     * that fell off the deck or came back somewhere else entirely.</p>
     */
    private static final double BACK_AT_HIS_SPOT_BLOCKS = 1.5;

    /**
     * How far the crew member must walk on the deck before logging out, in blocks.
     *
     * <p>The TEST'S OWN sensitivity bar: the leg's whole subject is coming back to a place he
     * WALKED to, so he has to have gone somewhere first.</p>
     */
    private static final double WALKED_ON_DECK_BLOCKS = 0.75;

    /**
     * How far a restored body may move ALONG THE DECK NORMAL before it counts as sinking through
     * it, in blocks.
     *
     * <p>The TEST'S OWN, and a separate constant from {@link #WALKED_ON_DECK_BLOCKS} although the
     * two carry the same number today: one is a distance ACROSS the deck that must be exceeded, the
     * other a distance THROUGH it that must not be. Merging them would tie a sensitivity bar to a
     * contract bound.</p>
     */
    private static final double DID_NOT_SINK_BLOCKS = 0.75;

    /**
     * How far from its own build base the ship may have loaded, in blocks.
     *
     * <p>The TEST'S OWN: a craft settles and drifts between assembly and the first read, and
     * twenty-four blocks is well inside its own plot while being far under the distance to any
     * neighbour's. What it pins is that the identity resolved to the craft THIS scenario built.</p>
     */
    private static final double LOADED_AT_ITS_BASE_BLOCKS = 24.0;

    /**
     * How long a commanded half-turn is given to finish, in ticks.
     *
     * <p>The attitude hold slews at about 2 rad/s, so half a turn takes roughly 31 ticks; this is
     * about two and a half times that. Two legs command the same 170-degree roll and both wait
     * exactly this long, because it is a property of the hold and not of either occasion.</p>
     *
     * <p>This number does not denote how long we are willing to wait — it denotes how far the craft
     * turns, which is a fixed amount per tick. What protects the wait under load is the other half
     * of the arrangement: the attitude is ADOPTED and then HELD, so a window longer than the slew
     * reads the same state as one exactly its length.</p>
     */
    private static final int ROLL_WINDOW_TICKS = 80;

    /**
     * A crew member WALKING his own deck must never be released by the external-move guard.
     *
     * <p>This test fails if production breaks the contract that <b>the guard releases a deck capture
     * only on movement the ship-frame resolver did not itself produce</b>. A walk that the resolver
     * swept and committed is movement it produced; treating it as a foreign teleport hands the body
     * back to vanilla and to the physics mod for the ticks between the release and the re-capture,
     * which is what a player feels as being dragged along his own deck.</p>
     *
     * <p><b>Why the stimulus is a walk, and why the ship is upright and still.</b> Taken from the
     * distribution in the reporter's own session log rather than from one quoted line: of the 174
     * {@code externalMove(sub)} releases in it, <b>every one is {@code remote=true}</b> (the CLIENT's
     * copy of the local player - the server's copy is a follower and rebases instead of dropping),
     * <b>148 are at {@code tiltDeg} of 1.1 deg or less</b>, and <b>150 report {@code shipObstacles}
     * of 1 or more</b>, i.e. an upright ship and a body with deck support under it. {@code carrySeen}
     * is 0.0 at nearly all of them, so the ship was not moving either. Each burst is bracketed by
     * {@code [FF-TRACE/WALK] forward=0.98} lines: the body was WALKING.</p>
     *
     * <p>The releases' own numbers name the shape: the released {@code dSub} equals, in magnitude and
     * with the opposite sign, the SERVER's {@code MoverType.PLAYER} step on the same tick (e.g. server
     * {@code d=(-0.0516, -9.9e-5, 0.20830)} against client {@code dSub=(0.0516, -2e-10, -0.20830)}),
     * with {@code frameMoved} at zero. The body's live point sits exactly one walk step BEHIND the
     * point this class committed - the "walking thrash whose entityMoved exactly negated this commit's
     * motion" the resolver's own drag-suppression comment names.</p>
     *
     * <p>The idle window before the walk is the control - a body on a moving platform needs its own
     * no-change leg in the SAME run, or a green walk leg cannot be told from an instrument that never
     * fires - and it doubles as the sensitivity witness. The walk is taken in short bursts with a 180-degree turn between them, because the
     * fixture's deck is 5x5: one long hold would walk him off it and the leg would measure the edge
     * rather than the guard.</p>
     */
    @Test
    public void aCrewMemberWalkingHisOwnDeckIsNeverReleasedByTheExternalMoveGuard() throws Exception {
        requireHeIsHeldThroughAWalk(site(), "ordinary world coordinates");
    }

    private void requireHeIsHeldThroughAWalk(FixtureSite site, String where) throws Exception {
        // Allocated, not chosen; see the sibling above for why the Y was a nameless `by = 64`.
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);
        Events events = events();
        long captureMark = events.markInstrumented();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        ShipIdentity.awaitCaptureHeldBy(events, captureMark, scenarioShipId,
                "the crew member must be TAKEN by THIS ship's deck"
                + " before he walks on it (" + where + ")", CAPTURE_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture2 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        scenario().requireArranged("he must be captured on the deck before he walks (" + where + "): "
                        + deckCapture2.raw(),
                deckCapture2.alreadyTracked);

        // CONTROL, same body, same deck, same window length, stimulus absent. Counted off the
        // client's own release records - see the roll leg for why a difference of two counter reads
        // could not fail.
        long idleReleaseMark = clientEvents().mark();
        long idleMark = lastClientTick();
        bot().waitTicks(40);
        String idleHistory = clientTickHistory();
        String idleReleases = clientReleases(idleReleaseMark, "the standing-still control window");
        long dropsIdle = guardReleases(idleReleases);
        int resolvedIdle = resolvedSince(idleHistory, idleMark);

        // THE STIMULUS: a real key on the real client input surface, in bursts that keep him on a
        // 5x5 deck. The turn between bursts is a real look, so each burst walks the way he faces.
        long walkMark = lastClientTick();
        long walkReleaseMark = clientEvents().mark();
        double walked = walkInBursts();
        String walkHistory = clientTickHistory();
        String walkReleases = clientReleases(walkReleaseMark, "a walk the resolver swept and committed");
        long dropsWalk = guardReleases(walkReleases);
        int resolvedWalk = resolvedSince(walkHistory, walkMark);
        int inputTicks = inputTicksSince(walkHistory, walkMark);
        int offDeckTicks = offDeckTicksSince(walkHistory, walkMark);
        DeckCapture capAfter = DeckCapture.read(this::exec);

        String observed = "\n  " + where
                + "\n  idle (control): drops=" + dropsIdle + " resolved=" + resolvedIdle
                + "\n  walking:        drops=" + dropsWalk + " resolved=" + resolvedWalk
                + " inputTicks=" + inputTicks + " offDeckTicks=" + offDeckTicks
                + " walked=" + walked
                + "\n  releases while idle:    " + idleReleases
                + "\n  releases while walking: " + walkReleases
                + "\n  capture after=" + capAfter.raw()
                + "\n" + mover()
                + "\n  CLIENT per-tick record:\n" + walkHistory;
        System.out.println("[walk-hold]" + observed);

        // ARRANGEMENT first, so a clean result can never be the instrument's silence.
        scenario().requireArranged("the client must have resolved the body through both windows, or "
                + "neither count means anything" + observed, resolvedIdle >= RESOLVED_TICKS_MIN && resolvedWalk >= RESOLVED_TICKS_MIN);
        scenario().requireArranged("the resolver must have SEEN the walk input, or the key never "
                + "reached the client's movement path" + observed, inputTicks >= INPUT_TICKS_MIN);
        scenario().requireArranged("he must actually have covered ground on the deck" + observed,
                walked > 1.0);
        scenario().requireArranged("he must have stayed ON the deck for the whole walk - a body that "
                + "walked off the edge is measuring the edge, not the guard" + observed,
                offDeckTicks == 0);
        scenario().requireArranged("he must still be captured ABOARD at the end" + observed,
                capAfter.alreadyTracked && !capAfter.hullStand);
        scenario().requireArranged("...and ABOARD THE DECK HE WALKED ON — a body that ended the walk"
                + " held by a neighbouring hull satisfies the line above, and the drop counts below"
                + " would then be about a deck he is no longer on" + observed,
                capAfter.anchoredOn(scenarioShipId));
        assertEquals("CONTROL: the guard must be quiet while he stands still - otherwise the count "
                + "during the walk is not attributable to the walk" + observed, 0L, dropsIdle);

        assertEquals("a crew member's own walk must not count as someone else moving him: the "
                + "external-move guard released the deck capture " + dropsWalk + " time(s) across "
                + resolvedWalk + " resolved ticks of walking on a still, upright deck. Between a "
                + "release and the re-capture the body belongs to vanilla and to the physics mod, "
                + "which is what a player feels as being dragged along his own deck." + observed,
                0L, dropsWalk);
    }

    /** Walk bursts, and ticks per burst: short enough that the body stays on a 5x5 deck, and enough
     *  of them that a per-tick misfire cannot hide in a single burst. */
    private static final int WALK_BURSTS = 4;
    /** Six, measured: ten carried him 2.3 blocks per burst and off a deck whose half-width is 2.5,
     *  and the leg then measured the deck edge instead of the guard. */
    private static final int WALK_BURST_TICKS = 6;

    /** Walk him back and forth across the deck with a real key; returns the ground he covered. */
    private double walkInBursts() throws Exception {
        double walked = 0.0;
        for (int burst = 0; burst < WALK_BURSTS; burst++) {
            bot().setLook(burst % 2 == 0 ? 0f : 180f, 0f);
            bot().waitTicks(4);
            double[] from = clientPos();
            bot().holdKey(Keyboard.KEY_W);
            bot().waitTicks(WALK_BURST_TICKS);
            bot().releaseKey(Keyboard.KEY_W);
            walked += distance(from, clientPos());
        }
        return walked;
    }

    /**
     * A server tick BURST must not cost a crew member his deck capture.
     *
     * <p>This test fails if production breaks the contract that <b>the external-move guard's
     * per-tick allowance means the same thing across a tick that really took three seconds as across
     * one that took fifty milliseconds</b>. The guard compares a raw subspace delta against a flat
     * 0.2 blocks and calls anything larger a foreign teleport; across a skipped-tick burst the two
     * sides of the same body legitimately arrive that far apart, and the release hands a body the
     * resolver was holding back to vanilla and to the physics mod.</p>
     *
     * <p><b>Why this stimulus, out of everything the report mentioned.</b> Correlation over the whole
     * reporter's log, not one line: <b>150 of the 174</b> {@code externalMove(sub)} releases fall
     * within 20 seconds of a {@code "Can't keep up! ... skipping N tick(s)"} warning, 30 of them
     * within 5 - and the eleven stalls in those logs skip 53 to 64 ticks each. The tilt, the space
     * cell, the inverted deck and the relog are all things the reporter happened to be doing; the
     * stall is the thing that keeps arriving just before the releases. So the stall is the driver and
     * the rest are conditions, and this leg reproduces the driver on the plainest possible subject:
     * an upright, stationary ship on a planet, exactly the arrangement whose walking leg is green.</p>
     *
     * <p>The walk is part of the stimulus, not decoration, and the leg carries BOTH halves of it as
     * separate windows: a freeze with the body standing still, and a freeze with the key held ACROSS
     * it. Only the second one lets the two sides diverge - the client keeps ticking and keeps walking
     * him while the server's copy stands frozen, so the resumed loop has to absorb the whole
     * accumulated step at once. A freeze on a body that was not moving has nothing to catch up on,
     * which is why it is the control rather than the stimulus.</p>
     */
    @Test
    public void aCrewMemberIsNotReleasedWhenTheServerSkipsATickBurst() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);
        Events events = events();
        long captureMark = events.markInstrumented();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        ShipIdentity.awaitCaptureHeldBy(events, captureMark, scenarioShipId,
                "the crew member must be TAKEN by THIS ship's deck"
                + " before the server is made to stall under him", CAPTURE_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture3 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        scenario().requireArranged("he must be captured on the deck before the server stalls: "
                        + deckCapture3.raw(),
                deckCapture3.alreadyTracked);

        // CONTROL: the same body, the same deck, the same walk - without the stall. The walking leg
        // measures this too, but it has to be in THIS run: a control from another boot has a
        // different ship pose and a different settle history.
        // Counted off the client's own release records - see the roll leg for why a difference of
        // two counter reads could not fail.
        long controlReleaseMark = clientEvents().mark();
        long controlMark = lastClientTick();
        bot().waitTicks(20);
        double controlWalked = walkInBursts();
        String controlHistory = clientTickHistory();
        String controlReleases = clientReleases(controlReleaseMark, "the same walk WITHOUT a stall");
        long dropsControl = guardReleases(controlReleases);
        int resolvedControl = resolvedSince(controlHistory, controlMark);

        // CONTROL B: the stall with the body STANDING STILL. Measured because it separates the two
        // halves of the driver - a frozen tick loop on its own, versus a frozen tick loop while the
        // client keeps resolving movement the server has not applied yet.
        long idleStallMark = lastClientTick();
        long idleStallReleaseMark = clientEvents().mark();
        String idleStall = exec("artest server stall " + STALL_MS);
        bot().waitTicks(20);
        String idleStallReleases = clientReleases(idleStallReleaseMark,
                "the same freeze with him standing still");
        long dropsIdleStall = guardReleases(idleStallReleases);
        int resolvedIdleStall = resolvedSince(clientTickHistory(), idleStallMark);

        // THE DRIVER: the key is HELD ACROSS the freeze. The client keeps ticking and keeps walking
        // him while the server's copy of him stands frozen; when the loop resumes, one server tick
        // has to absorb everything the client did meanwhile. That accumulated step is what the
        // reporter's log shows the guard measuring against its flat per-tick 0.2 blocks.
        //
        // He is walked to the far edge first, so the whole freeze happens with a deck's width of
        // runway ahead of him - the freeze is not shortened to fit the fixture, the runway is
        // arranged to fit the freeze.
        bot().setLook(180f, 0f);
        bot().waitTicks(4);
        bot().holdKey(Keyboard.KEY_W);
        bot().waitTicks(WALK_BURST_TICKS);
        bot().releaseKey(Keyboard.KEY_W);
        bot().setLook(0f, 0f);
        bot().waitTicks(4);
        long stallMark = lastClientTick();
        long stallReleaseMark = clientEvents().mark();
        double[] beforeStalledWalk = clientPos();
        bot().holdKey(Keyboard.KEY_W);
        String stall = exec("artest server stall " + WALK_STALL_MS);
        // Released the instant the loop resumes, and the window closed with it. The releases this leg
        // is about arrive as the two sides re-converge, within a tick or two of the resume; every
        // further tick with the key held only spends runway. Under parallel load the round trips
        // stretch, and a 1000 ms freeze with six trailing ticks walked him 6.0 blocks off a deck
        // 5 across - the leg then measured the deck edge and said so (offDeckTicks=3).
        bot().releaseKey(Keyboard.KEY_W);
        double stalledWalked = distance(beforeStalledWalk, clientPos());
        // The window stays open a while longer with the key DOWN: the re-convergence takes a few
        // ticks, and a window that closes on the release tick is too short to be witnessed (14
        // resolved ticks, against the 20 every other window in this class is held to). Nothing walks
        // here, so the runway is not spent.
        bot().waitTicks(15);
        String stallHistory = clientTickHistory();
        String stallReleases = clientReleases(stallReleaseMark, "a walk held ACROSS the freeze");
        long dropsAfterStall = guardReleases(stallReleases);
        int resolvedAfterStall = resolvedSince(stallHistory, stallMark);
        int offDeckAfterStall = offDeckTicksSince(stallHistory, stallMark);
        DeckCapture capAfter = DeckCapture.read(this::exec);

        String observed = "\n  control A, walk, no stall:   drops=" + dropsControl + " resolved="
                + resolvedControl + " walked=" + controlWalked
                + "\n  control B, stall while idle: drops=" + dropsIdleStall + " resolved="
                + resolvedIdleStall + "  " + idleStall.substring(Math.max(0, idleStall.indexOf('{')))
                + "\n  walk ACROSS the stall:       drops=" + dropsAfterStall + " resolved="
                + resolvedAfterStall + " walked=" + stalledWalked
                + " offDeckTicks=" + offDeckAfterStall
                + "\n  stall probe: " + stall
                + "\n  releases, control A:      " + controlReleases
                + "\n  releases, control B:      " + idleStallReleases
                + "\n  releases across the walk: " + stallReleases
                + "\n  capture after=" + capAfter.raw()
                + "\n" + mover();
        System.out.println("[tick-burst]" + observed);

        scenario().requireArranged("both freezes must really have SKIPPED ticks, or nothing was driven - "
                        + "a stall that advanced the world clock normally is not a stall" + observed,
                Reply.of(stall).ok() && stalledTicks(stall) <= WALK_STALL_MS / 200
                        && Reply.of(idleStall).ok()
                        && stalledTicks(idleStall) <= STALL_MS / 200);
        scenario().requireArranged("the client must have resolved the body through every window"
                        + observed,
                resolvedControl >= RESOLVED_TICKS_MIN && resolvedIdleStall >= RESOLVED_TICKS_MIN
                        && resolvedAfterStall >= RESOLVED_TICKS_MIN);
        scenario().requireArranged("he must have covered ground both times he walked - the walk"
                        + " across the freeze has to drive the two sides at least "
                        + STIMULUS_SLACK_MULTIPLE + "x the guard's own slack (" + GUARD_SLACK_BLOCKS
                        + " blocks), i.e. more than " + STIMULUS_BLOCKS + ", or the resumed loop has"
                        + " nothing to absorb and the pin below is arithmetic rather than a contract"
                        + observed,
                controlWalked > 1.0 && stalledWalked > STIMULUS_BLOCKS);
        scenario().requireArranged("he must have stayed ON the deck across the stall - a body that "
                + "walked off the edge is measuring the edge, not the guard" + observed,
                offDeckAfterStall == 0);
        assertEquals("CONTROL A: the guard must be quiet for the same walk without a stall, or the "
                + "count across the stall is not attributable to it" + observed, 0L, dropsControl);

        assertEquals("a skipped-tick burst must not cost a crew member his deck capture: the "
                + "external-move guard released him " + dropsAfterStall + " time(s) when the server "
                + "froze for " + WALK_STALL_MS + " ms with his walk key held - against "
                + dropsControl + " for the same walk with no freeze, and " + dropsIdleStall
                + " for the same freeze with him standing still. Its allowance is per TICK and flat, "
                + "so everything the client resolved while the loop was frozen arrives in one server "
                + "tick and is measured against the budget of a tick that took 50 ms." + observed,
                0L, dropsAfterStall);
    }

    /** How long the server's tick loop is frozen: the reporter's own stalls ran 2.87-3.22 s and
     *  skipped 53-64 ticks. */
    private static final int STALL_MS = 3000;

    /** The freeze he WALKS across is shorter, and the reason is the fixture's runway rather than the
     *  mechanism: at ~0.117 blocks per client tick a full three-second freeze carries him seven
     *  blocks, and this deck is five across. Ten skipped ticks already put ten times the guard's
     *  per-tick assumption into one server tick, and leave slop for the round trips to stretch
     *  under parallel load. */
    private static final int WALK_STALL_MS = 500;

    /**
     * The slack the external-move guard allows before it calls a step a foreign teleport, in blocks
     * — the quantity this leg's stimulus has to beat, and the only one it has to beat.
     *
     * <p>Named here because the arrangement gate below is DERIVED from it rather than guessed. What
     * stood there was a flat "he must have walked more than one block", a number nothing in the
     * mechanism asks for: it happened to sit just above what a 500 ms freeze buys a body starting
     * from a standstill, so the leg rejected runs that had produced the stimulus perfectly well.
     * Measured 2026-09-06 — {@code walked=0.978} across the freeze, against a control walk of 4.13
     * over four times as many key-down ticks, with {@code offDeckTicks=0} and 27 resolved ticks in
     * the window: a body that walked nearly five times the guard's slack and was called a body that
     * did not walk.</p>
     *
     * <p>It is deliberately not read off production: the guard's epsilon is private, and a test that
     * reached in for it would fail on the day the field is renamed rather than on the day the
     * contract breaks. The number is the one the leg's own prose already quotes.</p>
     */
    private static final double GUARD_SLACK_BLOCKS = 0.2;

    /** How many times the slack the walk must cover, so the divergence is unmistakably the walk's
     *  and not a rounding of the deck's carry. */
    private static final int STIMULUS_SLACK_MULTIPLE = 3;

    /** The arrangement floor the two numbers above produce. */
    private static final double STIMULUS_BLOCKS = STIMULUS_SLACK_MULTIPLE * GUARD_SLACK_BLOCKS;

    /** Ticks the world clock advanced across the stall probe's window - the witness that it really
     *  froze the loop rather than sleeping a command thread beside it. */
    private static long stalledTicks(String stallJson) {
        Reply mReply = Reply.of(stallJson);
        return mReply.has("ticksAdvanced") ? (long) mReply.number("ticksAdvanced") : Long.MAX_VALUE;
    }

    @Test
    public void aPlayerWhoRelogsOnAnInvertedDeckStaysAboardIt() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Capture the client player on the OPEN top deck while the ship is upright, then roll the
        // ship to inverted UNDER him - the capture carries his deck spot through the roll, leaving
        // him standing on the deck of an inverted ship (hanging under the hull in world terms).
        double[] ship = buildShip(site);
        Events events = events();
        long captureMark = events.markInstrumented();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        ShipIdentity.awaitCaptureHeldBy(events, captureMark, scenarioShipId,
                "the player must be TAKEN by THIS ship's deck while the"
                + " ship is still upright - the capture is what carries his deck spot through the"
                + " roll", CAPTURE_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture4 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        assertTrue("the player must be captured on the deck before the roll: "
                + deckCapture4.raw(),
                deckCapture4.alreadyTracked);

        double h = Math.toRadians(170.0) / 2.0;
        assertTrue("attitude hold must accept the inversion",
                Reply.of(exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0")).bool("commanded"));
        // An attitude CONVERGING under the hold is a physical value and not a link — production
        // never decides it has arrived — so this is a WINDOW and one read. It was a poll whose exit
        // condition is the gate below, which is the one shape question 2 does not license however
        // physical the value: its green says "some sample was inverted". The same window as the
        // roll leg above, for the same reason: the hold slews at ~2 rad/s, half a turn is ~31
        // ticks, and the attitude is HELD once reached.
        //
        // Read through the shared upYOf, which uses the full expression: the single-axis shortcut
        // this leg carried (1 - 2*qx^2) answers a confident 1.0 for a ship that rolled about a
        // different axis, and the sibling deck-crew leg records exactly that mistake.
        // READ BOTH ENDS of the window, so a red says WHICH failure it is. Measured 2026-09-15 in a
        // full-tier gate: this leg failed at upY=0.9999929 — not part-way through a slew that needed
        // longer, but a ship that had not moved AT ALL, with `commanded:true` in hand. A one-ended
        // read cannot tell "the hold is still slewing" from "the hold never started", and those have
        // opposite fixes: the first is a window too short, the second is a command that was accepted
        // and did nothing, which no wait can repair.
        double upYBefore = upYOf(shipInfo());
        bot().waitTicks(ROLL_WINDOW_TICKS);
        double upY = upYOf(shipInfo());
        assertTrue("the ship must be (near-)inverted for the relog to be able to drop the player"
                + " (upY went " + upYBefore + " -> " + upY + " across " + ROLL_WINDOW_TICKS
                + " ticks). If those two are equal the attitude hold never started and the window is"
                + " innocent; if they differ it was still slewing and the window is short.",
                upY < INVERTED_UP_Y);
        DeckCapture capBefore = DeckCapture.read(this::exec);
        assertTrue("the player must still be captured on the inverted deck before the relog: "
                + capBefore.raw(), capBefore.alreadyTracked);
        // The BEFORE half of "his capture came back on his ship" — pinned here so the after half has
        // something to be equal to, rather than merely being captured by whatever is around.
        capBefore.requireAnchoredOn( scenarioShipId,
                "the capture the relog must restore is the one on THIS scenario's inverted deck");
        double preY = bot().reportState().get("playerY").getAsDouble();

        // The REAL relog: full server logout (player data saved) + fresh login. Both marks first -
        // the client JVM is REUSED across a reconnect, so its log still holds this session and zero
        // would be the whole scenario rather than the relog.
        long relogMark = events.markInstrumented();
        long clientRelogMark = clientEvents().mark();
        bot().reconnect();
        bot().waitForWorld();
        // The rejoined client must be given a world, and the resolver must TAKE him again. The two
        // are read off the two logs separately: cross-side order within a tick is undefined.
        clientEvents().await(clientRelogMark, "client_dimension_changed",
                "the reconnected client must be given a world before anything can be asked about"
                        + " where it put him", CAPTURE_BUDGET_TICKS);
        // Carrying HIS ship's name, not merely of this type: "the deck TOOK him again" is a claim
        // about the deck he logged out on, and a record written by any other hull's capture would
        // satisfy a type-only wait and start the mode loop below at the wrong moment.
        ShipIdentity.awaitCaptureHeldBy(events, relogMark, scenarioShipId,
                "after the relog HIS deck must TAKE him again -"
                + " a body nobody captured is one vanilla and the physics mod are holding, which"
                + " under an inverted hull is a fall", CAPTURE_BUDGET_TICKS);
        // ABOARD specifically. A hull-stand catch (falling under the inverted hull until the hull
        // geometry stops the body somewhere) is exactly the captured-but-world-camera desync of the
        // original report, and it must NOT satisfy this contract — so the capture above is the link
        // that he was taken, and this is the link that says in WHICH mode.
        String modes = awaitCommittedAboardOnHisShip(events, relogMark,
                "after a relog on an inverted deck the player must be captured ABOARD again, not"
                        + " held with world semantics wherever the inverted hull stopped him");
        DeckCapture capNow = DeckCapture.read(this::exec);
        double postY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[relog] preY=" + preY + " postY=" + postY + " dY=" + (postY - preY)
                + " modes=" + modes);
        System.out.println("[relog] cap=" + capNow.raw());
        // What the client DID with the deck hold's restore seed - APPLY, KEEP_PREEXISTING,
        // ALREADY_SEEDED, EXPIRE or WAIT. The five-way verdict is the difference between "he was
        // put back on his deck point" and "the hold expired and vanilla had him", and nothing else
        // in this run distinguishes them.
        System.out.println("[relog] client seed decisions :: " + clientSeedDecisions(clientRelogMark));

        // Relog persistence. The ABOARD half is the link above — it fails there, with the sequence
        // of modes production committed — so what is left is the other half: he is still AT the
        // deck spot he logged out on, never handed to world gravity for a visible fall.
        assertTrue("after a relog the player must still be AT his deck spot, not fallen off "
                + "(preY=" + preY + " postY=" + postY + "): " + capNow.raw(),
                Math.abs(postY - preY) < BACK_AT_HIS_SPOT_BLOCKS);
    }

    /**
     * A crew member who logs out WHILE WALKING must come back STANDING STILL on the spot the durable
     * record names — not sliding on in the direction he was going.
     *
     * <p><b>What this pins that the inverted-deck leg cannot.</b> That leg logs out a body at rest,
     * so the only thing it can catch is a lost position. A walking body carries something else
     * across the logout: its MOTION. The deck resolver writes the body's velocity onto the server
     * entity every tick it commits, vanilla saves that into the player file, and the fresh entity
     * comes back holding it. If the restore lets the client's own first-contact capture take the
     * body — instead of applying the deck point the record names — that velocity is inherited as
     * ship-relative motion and the crew member skates across his own deck after logging in, with no
     * input. Reported from a real session on 2026-07-27.</p>
     *
     * <p>The ship is PARKED for this leg on purpose: a stationary deck makes "the spot he left" a
     * fixed world position, so a drift of a couple of blocks cannot be confused with the deck having
     * carried him somewhere. The walk itself is witnessed (he must actually cover ground before the
     * logout), because a leg where the body never moved would pass without exercising anything.</p>
     */
    @Test
    public void aCrewMemberWhoLogsOutWalkingComesBackStandingStillOnHisDeckSpot() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);
        Events events = events();
        long captureMark = events.markInstrumented();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        ShipIdentity.awaitCaptureHeldBy(events, captureMark, scenarioShipId,
                "the player must be TAKEN by THIS ship's deck before he"
                + " walks on it - the walk he logs out carrying is only meaningful under a capture",
                CAPTURE_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        DeckCapture deckCapture5 = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        assertTrue("the player must be captured on the deck before he walks: "
                + deckCapture5.raw(),
                deckCapture5.alreadyTracked);

        // CONTROL, before anything is done to him: does a settled crew member creep along this deck
        // ANYWAY? The ship holds station rather than standing still, and a station-keeping deck is
        // documented to feed its crew a constant no-input drift. Without this baseline, any creep
        // measured after the relog would be blamed on the restore by default.
        double[] idle0 = deckPoint();
        bot().waitTicks(30);
        double idleCreep = alongDeck(idle0, deckPoint());
        System.out.println("[walk-relog] CONTROL idle creep along the deck over 30 ticks = "
                + idleCreep);

        double[] beforeWalk = clientPos();
        bot().holdKey(Keyboard.KEY_W);
        bot().waitTicks(12);
        double[] walking = clientPos();
        bot().releaseKey(Keyboard.KEY_W);
        // Give the release a couple of ticks to actually reach the server as "no input" before the
        // logout. The subject of this leg is an inherited VELOCITY, not an inherited INPUT, and the
        // two are separable: the velocity survives about ten ticks of deck drag, so two ticks keep
        // nearly all of it while making it impossible for a key still held (the harness reuses one
        // client JVM across the reconnect) to masquerade as a restore defect afterwards.
        bot().waitTicks(2);
        // ARRANGEMENT WITNESS: he has to be genuinely under way, or the motion this leg is about
        // never exists and a green below would mean nothing.
        assertTrue("the crew member must actually cover ground on the deck before logging out "
                + "(moved " + distance(beforeWalk, walking) + " blocks)",
                distance(beforeWalk, walking) > WALKED_ON_DECK_BLOCKS);

        // Log out WHILE the body still carries that walk. The release above only stops the input;
        // the velocity is still on the entity for several ticks of drag, and it is what gets saved.
        //
        // The reference is the SERVER's position, read as the last thing before the reconnect —
        // NOT an earlier client sample. The body is still decelerating, so a sample taken before it
        // stopped names a spot he had not reached yet; measuring against one made the restore look
        // 1.3 blocks wrong when it was landing him exactly where he logged out. Even this reference
        // lags by the command's own round trip, which is why the comparison against it is a bound on
        // gross misplacement, and the TIGHT pins are the two drift windows below — drift being what
        // was actually reported from play.
        double[] logoutOffset = deckPoint();

        // Both marks before the reconnect - the client JVM is reused across it, so its own log
        // still holds the walk and zero would be the whole scenario rather than the relog.
        long relogMark = events.markInstrumented();
        long clientRelogMark = clientEvents().mark();
        bot().reconnect();
        bot().waitForWorld();
        clientEvents().await(clientRelogMark, "client_dimension_changed",
                "the reconnected client must be given a world before anything can be asked about"
                        + " where it put him", CAPTURE_BUDGET_TICKS);
        // Carrying HIS ship's name — the message already says "on something that is not this deck",
        // and a type-only wait cannot tell that case from a pass.
        events.awaitField(relogMark, "deck_entered","ship", scenarioShipId,
                "after the relog HIS deck must TAKE him again -"
                + " otherwise the drift windows below measure a body vanilla and the physics mod"
                + " are holding, on something that is not this deck", CAPTURE_BUDGET_TICKS);

        // ABOARD specifically: the capture above is the link that he was taken, and this is the
        // link that says in which MODE - a hull-stand catch is a capture too, and it is not this
        // contract. On HIS ship, because a capture on a neighbour's hull commits `aboard` in
        // exactly the same shape, and then "he did not drift" would only mean he is standing on
        // something else.
        awaitCommittedAboardOnHisShip(events, relogMark,
                "after the relog he must be captured ABOARD the deck again, or 'he did not drift'"
                        + " would just mean he is standing on something else");

        // Everything the tight pins below measure is taken from the CLIENT's own per-tick record,
        // which starts here: the client owns this body's movement, and the server-side probe reads
        // the server's copy of it.
        long fromTick = lastClientTick();

        // Measured in the SHIP FRAME, one snapshot per sample (see deckPoint's note on the three
        // instruments that were wrong before it). The whole observation is TRACED rather than
        // sampled at two points: what a residual velocity looks like - a decaying slide - and what a
        // late re-capture looks like - a step - are indistinguishable from two readings, and the
        // difference decides which writer to go after.
        double[][] trace = new double[11][];
        String[] who = new String[11];
        trace[0] = deckPoint();
        who[0] = mover();
        for (int i = 1; i < trace.length; i++) {
            bot().waitTicks(5);
            trace[i] = deckPoint();
            who[i] = mover();
        }
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < trace.length; i++) {
            path.append(String.format(java.util.Locale.ROOT, "%nt=%-3d %s  step=%.4f  %s",
                    i * 5, fmt(trace[i]), i == 0 ? 0.0 : distance(trace[i - 1], trace[i]), who[i]));
        }
        // What the client DID with the deck hold's restore seed, as its own five-way verdict -
        // APPLY, KEEP_PREEXISTING, ALREADY_SEEDED, EXPIRE or WAIT - every decision it took since
        // the reconnect, in order. This replaces a production static that kept only the last one
        // and could not say when it was taken.
        String seeds = clientSeedDecisions(clientRelogMark);
        String seedOutcome = Events.lastField(seeds, "decision");
        System.out.println("[walk-relog] logoutDeckPoint=" + fmt(logoutOffset) + " seedOutcome="
                + seedOutcome + " decisions=" + seeds + " trace:" + path);
        System.out.println("[walk-relog] CLIENT per-tick history (B = the client body's own "
                + "ship-frame point):\n" + clientTickHistory());

        double[] justAfter = trace[0];
        double[] oneSecondLater = trace[4];
        double[] later = trace[10];
        double slid = distance(justAfter, oneSecondLater);

        // Split by axis, because the two are different claims. ALONG the deck is the contract this
        // leg exists for - a body that inherited the walk it logged out on travels there, and that
        // is what was reported from play. ACROSS it (the deck normal, ship-frame Y) a restored body
        // legitimately settles the last fraction onto the surface it was placed just above; measured
        // at ~0.02 blocks/tick, decaying. Pinning the two together would either let a skate hide
        // inside a settle tolerance or fail the leg for a body doing exactly the right thing.
        assertTrue("a crew member restored onto his deck must not SLIDE along it: he is given a "
                + "recorded position, not re-acquired from the velocity vanilla handed his fresh "
                + "entity (moved " + alongDeck(justAfter, oneSecondLater) + " blocks along the deck "
                + "in 20 ticks with no input; the client's own seed verdicts since the relog were "
                + seeds + ", the last of them " + seedOutcome + ")" + path,
                alongDeck(justAfter, oneSecondLater) < SHIP_FRAME_DRIFT_TOLERANCE);
        assertTrue("and he must not sink through it either (moved "
                + Math.abs(justAfter[1] - oneSecondLater[1]) + " blocks along the deck normal)" + path,
                Math.abs(justAfter[1] - oneSecondLater[1]) < DID_NOT_SINK_BLOCKS);

        // And he must STAY put - not merely have stopped by then.
        double afterCreep = alongDeck(oneSecondLater, later);
        assertTrue("a restored crew member must not creep along the deck once he has landed on it "
                + "(after the relog he moved " + afterCreep
                + " blocks along the deck in 30 ticks; the same body before the relog moved "
                + idleCreep + " over the same window)" + path, afterCreep < SHIP_FRAME_DRIFT_TOLERANCE);

        // The two CLIENT-side pins, and the reason they exist alongside the sampled trace above.
        //
        // (1) ONE deck point. The client is where this body's movement is decided, and what it
        // decides each tick is a ship-frame point. Held still, that point must not travel: a body
        // given a recorded position keeps it. The reading is the client's own committed point, so
        // it carries none of the ship-transform skew that contaminates any position re-derived from
        // world coordinates while the ship is moving - and the ship IS moving here, for the first
        // second or so after a rejoin, which is exactly when this used to break.
        //
        // (2) ONE capture mode. Standing on a deck is ABOARD (deck gravity, deck camera, deck walk
        // basis); the world-frame HULL mode is for a body on the ship's outer skin. A body that
        // alternates between them is in neither contract, and the alternation is what produced (1):
        // the hull mode re-bases the held deck point onto the body's current world position, so
        // every flip banked the skew and the crew member ratcheted along his own deck.
        String history = clientTickHistory();
        assertTrue("the client's per-tick record must exist, or these pins measure nothing "
                + "(is the client JVM in test mode?)", history.contains("|B="));
        double heldTravel = heldPointTravel(history, fromTick);
        int hullTicks = hullStandTicks(history, fromTick);
        int covered = resolvedSince(history, fromTick);
        System.out.println("[walk-relog] CLIENT held-point travel along the deck = " + heldTravel
                + " over " + covered + " resolved ticks, hullStand ticks = " + hullTicks);
        // WITNESS: "it did not travel" and "nothing was recorded" are the same number otherwise.
        assertTrue("the client must have resolved the body through the observation window, or the "
                + "two pins below cannot fail (" + covered + " ticks recorded)\n" + history,
                covered > RESOLVED_TICKS_MIN);
        assertTrue("the deck point the client holds him at must not travel along the deck with no "
                + "input (moved " + heldTravel + " blocks, bar " + SHIP_FRAME_DRIFT_TOLERANCE + ")\n"
                + history, heldTravel < SHIP_FRAME_DRIFT_TOLERANCE);
        assertTrue("a crew member standing on a deck must stay in ABOARD capture semantics - "
                + "flipping to the world-frame hull mode re-bases his deck point onto wherever the "
                + "world thinks he is (" + hullTicks + " hull-stand ticks)\n" + history,
                hullTicks == 0);

        // Gross-misplacement bound: he has to come back on the deck spot he left, not somewhere
        // else on the ship. Loose on purpose - the reference is read one command before the logout
        // and the body is still decelerating, so a few tenths of a block are the instrument's.
        assertTrue("he must come back where he logged out (deck point " + fmt(logoutOffset)
                + "), not " + distance(logoutOffset, later) + " blocks away at " + fmt(later),
                distance(logoutOffset, later) < BACK_AT_HIS_SPOT_BLOCKS);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /**
     * The BODY's live position in the ship frame — the crew member himself, mapped through his
     * anchor ship's transform at the moment of asking.
     *
     * <p>Three instruments were tried before this one and each was wrong in its own way, which is
     * worth keeping written down: a WORLD position counts the ship carrying the body as the body
     * moving; a world position differenced against a separately-sampled ship pose counts the ship's
     * own station-keeping step (~1.5 blocks between two samples taken ticks apart) as the body
     * sliding; and the CAPTURE's committed point freezes while anything else holds the body, so a
     * pinned body reads as perfectly still. This one is a single snapshot, in the right frame,
     * derived from the body's own coordinates every time it is asked.</p>
     */
    private double[] deckPoint() throws Exception {
        DeckCapture cap = DeckCapture.read(this::exec);
        assertTrue("the deck capture must be live to report a ship-frame point: " + cap.raw(),
                cap.alreadyTracked);
        // In WHOSE ship frame. The three numbers below are subspace coordinates of the ANCHOR ship,
        // so a capture that has moved to a neighbouring hull does not make this reading wrong-looking
        // — it silently re-expresses it in another frame, and every drift the callers compute across
        // two such samples is then a difference between two different coordinate systems.
        cap.requireAnchoredOn( scenarioShipId,
                "a ship-frame point is only comparable while it stays in ONE ship's frame");
        return new double[]{cap.bodyShipFrameX(), cap.bodyShipFrameY(),
                cap.bodyShipFrameZ()};
    }

    /**
     * WHO is moving the body, as counters rather than inference: how many ticks the ship-frame
     * resolver has committed, how many it has DECLINED (leaving the body to vanilla and to the
     * physics mod's own mover), and how many external world moves it has had to absorb. A drift
     * while `declined` climbs is a resolver that stepped back; a drift while `worldMove` climbs is
     * something else pulling the body.
     */
    private String mover() throws Exception {
        // The SERVER's most recent resolved tick, its most recent guard pass and its most recent
        // world-frame mover, each as its own record. Everything this used to read out of a probe
        // reply one field at a time is on those records, and each arrives NAMING the body and the
        // tick it belongs to. The reply could offer neither: it published whatever the last
        // resolution on the server had left in a static, so on a world with a second body aboard
        // anything the loop below compared across two samples could be two different subjects. The
        // probe verb it came from no longer exists.
        String srvTick = Events.lastRecord(events().since(0, "ship_frame_tick"));
        String srvGuard = Events.lastRecord(events().since(0, "deck_guard_pass"));
        String srvMove = Events.lastRecord(events().since(0, "ship_frame_world_move"));
        return "SRV[guard=" + (srvGuard == null ? "(no pass)" : srvGuard)
                + " tick=" + (srvTick == null
                        ? "(the server has resolved no tick at all)" : Events.text(srvTick, "line"))
                + " lastWorldMove=" + (srvMove == null ? "(none)" : srvMove)
                + "]";
                // No client half sampled per iteration any more. It was three lifetime, JVM-global
                // counters, and on a shared client none of them described this body; the client's
                // own per-tick record and its releases are read once, at the end, where a round trip
                // does not stretch the timeline being sampled (clientTickHistory).
    }

    /**
     * The CLIENT's own per-tick record of the resolution, read as one field. The sampled trace above
     * cannot answer this leg's question on its own: it costs a round trip per field, so its
     * "5 ticks" are really 5 ticks plus the reads, and anything that settles between two samples is
     * invisible. This is the client body's ship-frame point every tick it was resolved.
     */
    private String clientTickHistory() throws Exception {
        return clientEvents().since(0, "ship_frame_tick");
    }

    /** The resolved-tick counter of one record — the window's own clock. */
    private static long tickOf(String record) {
        return (long) Events.number(record, "resolvedTick");
    }

    /** One ship-frame point of a record: {@code bodyLocal} (found) or {@code held} (committed). */
    private static double[] pointOf(String record, String prefix) {
        return new double[]{Events.number(record, prefix + "X"), Events.number(record, prefix + "Y"),
                Events.number(record, prefix + "Z")};
    }

    /**
     * Four patterns stood here — one per QUESTION, because group numbers are positional and a reader
     * wanting a different field of the same line needed its own expression. Their own comment said
     * as much: *"a separate pattern rather than extra groups … so the existing helpers' group
     * numbers stay where they are."* Read by field name, that reason is gone and so are they.
     */
    private static final String BODY_LOCAL = "bodyLocal";
    private static final String HELD = "held";

    /** How far the BODY travelled along the deck in the window: first tick after {@code fromTick} to
     *  the farthest one, so a body that wanders out and back cannot pass. */
    private double bodyPointTravel(String history, long fromTick) {
        double[] first = null;
        double worst = 0.0;
        for (String record : Events.records(history)) {
            if (tickOf(record) <= fromTick) {
                continue;
            }
            double[] point = pointOf(record, BODY_LOCAL);
            if (first == null) {
                first = point;
            } else {
                worst = Math.max(worst, alongDeck(first, point));
            }
        }
        return worst;
    }

    /**
     * The worst distance, over the window, between where a body was FOUND at the top of its
     * movement tick and the deck point this class had committed for it.
     *
     * <p>Both points are in the ship frame, so a deck carrying a body honestly contributes nothing:
     * what is left is the body standing somewhere its own deck point is not. A ship's pose advances
     * at the END of a tick, after the entities have already moved, so a body that is not put back
     * afterwards is found exactly one tick of ship motion away from its seat — every tick, for as
     * long as the craft keeps moving.</p>
     *
     * <p>Distinct from {@link #bodyPointTravel}, which asks whether the body WANDERED and compares
     * each sample against the first one. A steady one-tick lag does not wander: it is the same
     * offset every tick, and the wander measure sees only the ramp into it.</p>
     */
    private double seatMiss(String history, long fromTick) {
        double worst = 0.0;
        for (String record : Events.records(history)) {
            if (tickOf(record) <= fromTick) {
                continue;
            }
            double[] found = pointOf(record, BODY_LOCAL);
            double[] committed = pointOf(record, HELD);
            worst = Math.max(worst, distance(found, committed));
        }
        return worst;
    }

    /**
     * The same line read for what came INTO the tick rather than where the body ended up: the walk
     * inputs the resolver saw, and whether it had the deck under the feet.
     *
     * <p>Both are arrangement witnesses for the walking leg, and both were needed the hard way: a
     * clean drop count means nothing if the key never reached the resolver, and a body that has
     * walked off a 5x5 deck is measuring the deck edge rather than the guard.</p>
     */
    /** Ticks after {@code fromTick} in which the resolver saw a nonzero walk input. */
    private int inputTicksSince(String history, long fromTick) {
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

    /** Ticks after {@code fromTick} the body spent without the deck under it. */
    private int offDeckTicksSince(String history, long fromTick) {
        int n = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick && "false".equals(Events.text(record, "onDeck"))) {
                n++;
            }
        }
        return n;
    }

    /** The most recent resolved-tick number in the client's record — the mark an observation starts
     *  from, so the pins never read ticks from before the window (the record survives the relog:
     *  the harness reuses one client JVM). */
    private long lastClientTick() throws Exception {
        long last = -1;
        for (String record : Events.records(clientTickHistory())) {
            last = tickOf(record);
        }
        return last;
    }

    /** How far the committed deck point travelled ALONG the deck across the window — first commit
     *  after {@code fromTick} to the farthest one, not merely the last, so a body that wanders out
     *  and back cannot pass. */
    private double heldPointTravel(String history, long fromTick) {
        double[] first = null;
        double worst = 0.0;
        for (String record : Events.records(history)) {
            if (tickOf(record) <= fromTick) {
                continue;
            }
            double[] held = pointOf(record, HELD);
            if (first == null) {
                first = held;
            } else {
                worst = Math.max(worst, alongDeck(first, held));
            }
        }
        return worst;
    }

    private int hullStandTicks(String history, long fromTick) {
        int hull = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick && "h".equals(Events.text(record, "path"))) {
                hull++;
            }
        }
        return hull;
    }

    /** How many ticks the window actually covers — a witness that the pins above had something to
     *  look at, since "no travel" and "no ticks recorded" read the same. */
    private int resolvedSince(String history, long fromTick) {
        int n = 0;
        for (String record : Events.records(history)) {
            if (tickOf(record) > fromTick) {
                n++;
            }
        }
        return n;
    }

    /** A field of a probe reply FOR A MESSAGE — never a reading; see {@code Reply.reported}. */
    private static String readString(String json, String key) {
        return Reply.of(json).reported(key);
    }

    /** The client's own rendered position. */
    private double[] clientPos() throws Exception {
        com.google.gson.JsonObject state = bot().reportState();
        return new double[]{state.get("playerX").getAsDouble(),
                state.get("playerY").getAsDouble(), state.get("playerZ").getAsDouble()};
    }

    private static String fmt(double[] p) {
        return "[" + p[0] + "," + p[1] + "," + p[2] + "]";
    }

    /**
     * How long a deck may take to TAKE a body put on it, in ticks.
     *
     * <p>A deadline for a discrete event, where the 80-tick sleeps this replaces were a guess at
     * how long it usually takes. Early-exit, so a healthy run pays what the capture actually costs
     * and a broken one still gets past a chunk stream on a loaded box before it is called a red.</p>
     */
    private static final int CAPTURE_BUDGET_TICKS = 200;

    /**
     * Wait until production's LAST committed capture mode for THIS scenario's ship is
     * {@code aboard}.
     *
     * <p><b>Replaces a 40-step probe poll whose exit condition was re-asserted verbatim after it</b>
     * — so that assertion could not fail except by the budget running out, and its message then
     * blamed the deck for a timeout. Both sites carried the same argument for polling: that the mode
     * is "a MODE the resolver picks per tick rather than a link". The first half is true and the
     * conclusion no longer follows: production COMMITS the mode itself at every transition, and the
     * record carries the ship, so the settled answer is a condition over the log rather than a state
     * to sample. A promotion (hull-stand caught first, aboard a few ticks later) is exactly what
     * "the LAST record for this ship" expresses.</p>
     *
     * <p>Filtered on the SHIP, which is what makes it a persistence claim: a capture by any hull
     * that happens to be in the airspace commits {@code aboard} in the same shape, and on a shared
     * world there is always a neighbour's craft. {@code VSCrewInteriorBoarding} has a sibling helper
     * with NO ship filter — deliberately, because there it is the body's own last mode that is the
     * subject and the ship is fixed by construction. Fold the two only if a third caller appears.</p>
     */
    private String awaitCommittedAboardOnHisShip(Events log, long mark, String what)
            throws Exception {
        try {
            return log.awaitMatching(mark, "deck_mode_committed", reply -> {
                java.util.List<String> mine = Events.recordsWhere(reply, "ship", scenarioShipId);
                return !mine.isEmpty()
                        && "aboard".equals(Events.text(mine.get(mine.size() - 1), "mode"));
            }, "committing `aboard` as the LAST mode for " + scenarioShipId, what,
                    CAPTURE_BUDGET_TICKS);
        } catch (AssertionError never) {
            // Which silence: nothing committed a mode at all, or something committed a different
            // one. They ask for opposite investigations, and an empty window reads as the second.
            Events.assertInstrumentRan(log.since(mark, "deck_mode_committed"), "deck_mode_events",
                    "a capture MODE was committed at all after the relog");
            throw new AssertionError(never.getMessage() + " | server verdict "
                    + exec("artest vs deck-capture"));
        }
    }

    // The two logs are separate instruments with separate sequences: events() reads the server's
    // through the probe channel, clientEvents() the client's through the bot. A client link is
    // therefore always awaited BESIDE a server one and never inside the same chain - cross-side
    // order within a tick is undefined.

    /** Every verdict the client took on a pending deck seed since {@code mark}, in order. */
    private String clientSeedDecisions(long mark) throws Exception {
        return clientEvents().since(mark, "deck_seed_decided");
    }

    /**
     * Every deck capture the CLIENT ended inside a window, in order, each carrying production's own
     * reason for ending it - and the proof that anybody was recording at all.
     *
     * <p>{@link Events#assertInstrumentRan} is the load-bearing half. Every guard pin in this class
     * is a ZERO, and "the guard released him zero times" and "the observation point never wove" are
     * the same empty reply. The counter these calls replace could not even say that much: its reader
     * answers {@code -1} for a field it cannot read and every count was a DIFFERENCE of two reads,
     * so an unreadable instrument produced a clean {@code 0} and the pin went green on it.</p>
     */
    private String clientReleases(long mark, String whatFor) throws Exception {
        String releases = clientEvents().since(mark, "deck_released");
        Events.assertInstrumentRan(releases, "deck_capture_events",
                "the client's deck capture was, or was not, cycled during " + whatFor);
        return releases;
    }

    /**
     * How many of those releases were the EXTERNAL-MOVE guard's - the mechanism every leg here is
     * about. A GEOMETRIC release (walked off the deck, no hull contact) is legitimate and is
     * deliberately not counted; it still shows in the reply the caller prints.
     */
    private static long guardReleases(String releases) {
        return Events.countRecordsWithField(releases, "reason");
    }

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The registry's own record of the ship being ADDED, read from a mark taken BEFORE the
        // assembler is told. What it replaces was a count of every ship on the world, differenced
        // across the assembly: on a shared client that count is answered by every neighbour that
        // ever assembled one, it cannot say WHICH ship arrived, and a spawn that happened between
        // two of its samples is indistinguishable from one that never happened.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(site);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        // The IDENTITY, off that same record: this scenario built the ship, so it is TOLD which
        // ship that is, and nothing below re-derives it from a position.
        scenarioShipId = awaitShipSpawned(events, spawnMark, "a with-pilot-seat build must become a"
                + " ship in the physics mod's own registry - not a rocket, and not nothing");
        bot().waitTicks(40);

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so the readiness link below"
                        + " is waiting on something only an arrived client can cause");

        // USABLE - and that is ALL that is waited for here. The SPAWN above was the link, and it
        // gave the identity; what it does not give is the physics object being loaded and drivable,
        // which is the fact the whole class's later readings rest on. That fact is production's own
        // edge - it is published once per load and recorded as ship_usable - so it is AWAITED off
        // the same mark taken before the assembly, not polled: what this replaces polled ship-info
        // for managed:true, which is a literal true in the reply builder and meant only that the
        // lookup found a ship, so the wait was a wait on nothing.
        awaitShipUsable(events, spawnMark, scenarioShipId);
        // The POSITION still has to be asked for - the event carries the ship and its dimension and
        // no coordinate - and it is asked BY ID: a subject that has climbed out of a radius and a
        // neighbour's craft that has drifted into one are both answers a by-identity question
        // cannot give.
        String info = shipInfoById(scenarioShipId);
        double[] where = {readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
        assertTrue("the ship this scenario built must have loaded AT ITS OWN BASE - the identity is"
                        + " the assembly's own record, so this pins where the fixture came up rather"
                        + " than which ship answered: " + info,
                distance(where, new double[]{bx, by, bz}) < LOADED_AT_ITS_BASE_BLOCKS);
        return where;
    }

    private String assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`. Open air, so
        // this ASSERTS rather than digging the shaft it replaces — whose rim put world terrain under
        // a crew member standing on his own deck, which is the state this class carries across a
        // relog and would therefore have carried the defect across too.
        //
        // HEIGHT 24 is the ENVELOPE: hull, deck, a body standing and walking on it, and the room the
        // hull rolls through.
        return RocketFixture.assembleAt(site, this::exec, VARIANT, 2, 24,
                "the hull, the deck the crew member is held on, and the air he walks and rolls through");
    }

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return shipInfoById(scenarioShipId);
    }

    private double readDouble(String json, String field) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        double value = Reply.of(json).numberOr(field, Double.NaN);
        return value;
    }

    /** Distance ALONG the deck - the ship-frame horizontal plane, with the deck normal dropped. */
    private static double alongDeck(double[] a, double[] b) {
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
