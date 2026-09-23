package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import zmaster587.advancedRocketry.space.CellSeam;
import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.PlayerState;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.EntryStatus;
import zmaster587.advancedRocketry.test.TransitStatus;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A pilot who RELOGS in the middle of a hyperspace transit regains control ON ARRIVAL: after the
 * jump completes he is seated on his ship in the target cell and his held key flies it again — no
 * re-board, no re-click. (During the transit park itself the ship ignores input by design; what
 * this pins is that a mid-transit relog does not sever the control chain the arrival hands back.)
 *
 * <p><b>Why this must be a client test.</b> The relog is the one seam every lower tier fakes: the
 * crew record captured at departure references the pre-relog player entity, and a fresh login
 * replaces that entity wholesale. Whether the arrival's re-seating finds the RETURNED player — and
 * whether his client's input chain then reaches the arrived ship's computer — is observable only
 * with a real client logging out and back in around a real (probe-driven) transit.</p>
 *
 * <p><b>Shape.</b> The probe transit stack of the crewed-transit scenarios, but over a ship
 * that can actually FLY: {@code space transit-setup-empty} installs the stack with an EMPTY origin
 * cell, and the real {@code with-pilot-seat} fixture is built there with the real assembler — the
 * piloted setup's bare 3x3 deck has no propulsion, so a held key can move nothing and a control
 * pin on it is vacuous (measured: it slowly SINKS instead). Plus: a landing platform under the
 * ship's berth (the departure cuts the deck out from under the standing-by crew, and a pilot
 * mid-relog must not be falling into the void while the test drives the jump), a real
 * {@code reconnect} between departure and arrival, and the planet-side relog pin's held-key climb
 * as the load-bearing acceptance.</p>
 *
 * <p><b>The park is no longer deterministic, and that is worth saying out loud.</b> This class used
 * to argue that "the transit only advances when the probe ticks it, so the park deterministically
 * outlasts the relog" — true while the fixture drove its own subsystem, and false since 2026-09-08:
 * the server advances a transit on its own tick, so the relog now RACES the flight instead of
 * being guaranteed to fit inside it. The flight is {@code DIRECT_CROSSING_MAX_TICKS + 10} ticks and
 * a reconnect is far shorter, so the margin is large — but it is a margin now, not a guarantee, and
 * a red here that says the ship had already arrived is this and not the control chain. If that ever
 * happens, the fix is to make the fixture's flight longer for this scenario, not to give the test
 * back its own clock.</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSMidTransitRelogControlE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-mid-transit-relog-control";
    }

    private static final String SHIP_ID = "id";

    /** A demonstrable held-key climb: well above settle jitter, cheap to reach. */
    private static final double MIN_CLIMB = 1.0;

    /** Ticks the attitude hold is given to bring the hull level after the lift. The slew ceiling is
     *  2.0 rad/s and it ramps at 4.0 rad/s^2, so a half-turn is about 45 ticks; this is four times
     *  that. The slew advances per TICK, so the number says how far the hull turns, not how long
     *  we are willing to wait. */
    private static final int LEVEL_WINDOW_TICKS = 400;

    /** How long the CLIENT is given to perform a seating the server has already done, in ticks —
     *  a ceiling on one round trip, not a guess at how long sitting down takes. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    @Test
    public void aPilotWhoRelogsMidTransitRegainsControlOnArrival() throws Exception {

        // Headless: pin ships loaded so the assembled ship survives between probe calls.

        // ---- ARRANGE: the transit stack over an EMPTY origin cell, then a real FLYABLE piloted
        // ship built there with the real assembler. --------------------------------------------
        int originDim = TransitSetup.empty(this::exec).originDim;

        // The origin CELL is a void world, so this lift is not about escaping terrain — it is about
        // there being ONE definition of where a fixture stands, shared with every other class,
        // instead of a 64 nobody chose. What the first link below buys here is real all the same:
        // `transit-setup-empty` says the cell is empty and this MEASURES it, on the air fill's own
        // `placed`, before a craft is built in it.
        //
        // NOT ALLOCATED FROM A PLOT, deliberately. A plot keeps a scenario clear of its siblings in
        // a SHARED world; this craft is alone in a cell made for it, so there is nobody to be kept
        // clear of, and moving it into a region of that dimension whose extent nobody has measured
        // would be a real risk taken for no contract.
        final zmaster587.advancedRocketry.test.FixtureSite site =
                zmaster587.advancedRocketry.test.FixtureSite.openAir(originDim, 40, 40);
        String assembled = zmaster587.advancedRocketry.test.RocketFixture.assembleAt(
                site, this::exec, "with-pilot-seat", 2, 16,
                "the craft that is flown, relogged and flown again inside this cell");
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assembled,
                (Reply.of(assembled).integer("rocketCount") == 0));
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim "
                + originDim + ")", waitForLoadedShip(originDim) >= 1);

        // The scenario's ship, by IDENTITY, from the assembler that minted its durable name. This
        // was a bounded lookup at the build site, defended as the one place "no other craft could
        // satisfy" the bound — but a bound limits DISTANCE, and two hulls can sit at one point, so
        // that premise was about today's fixture rather than about the lookup. Every question
        // afterwards is keyed on this: the ship is about to be flown, departed and re-materialised
        // in another cell, and a transit cell is a POOL slot that routinely holds an earlier
        // scenario's leavings.
        String durableId = ShipIdentity.nameFromAssembly(assembled);
        String shipId = ShipIdentity.awaitPhysicsIdOf(this::exec, originDim,
                durableId, 40, () -> bot().waitTicks(5));
        // The transit stack must know WHICH craft the jump is about, by its durable name: a jump
        // begun for a ship the stack cannot name captures nobody and never reaches the ledger, so a
        // relogging pilot is sent to spawn as SHIP_UNKNOWN (measured 2026-09-05, this very class).
        String named = exec("artest space transit-name " + originDim + " " + shipId);
        scenario().requireArranged("the transit stack must resolve this ship's flight computer and its"
                + " durable id, or the jump departs nameless: " + named,
                Reply.of(named).bool("afcFound") && !"".equals(Reply.of(named).text("durableId")));

        PilotSeat seat = PilotSeat.byId(this::exec, originDim, shipId)
                .requireFound("the pilot seat must be found in the assembled ship, or the test is vacuous");
        int seatX = seat.seatX, seatY = seat.seatY, seatZ = seat.seatZ;
        // A WORLD-frame pose, and everything below is built on it — the platform, and the cell the
        // bot is placed into. It is carried across several commands, which is only sound because
        // nothing has been ordered that can move this hull: it was assembled at its berth and the
        // departure is not commanded until leg 3. The seat's own seatX/Y/Z above need no such note;
        // they are SUBSPACE and would survive the hull flying.
        int sx = (int) Math.round(seat.shipWorldX);
        int sy = (int) Math.round(seat.shipWorldY);
        int sz = (int) Math.round(seat.shipWorldZ);

        // A landing platform under the berth: the departure cuts the ship out from under its
        // standing-by crew, and a pilot who relogs mid-transit resumes FALLING at login — over a
        // void cell he would be dead before the arrival could re-seat him. Geometry measured off
        // the ship's own world pose, not assumed.
        scenario().requireArranged("the landing platform must build: ",
                Reply.of(exec("artest fill " + originDim + " " + (sx - 12) + " " + (sy - 8) + " " + (sz - 12)
                        + " " + (sx + 12) + " " + (sy - 8) + " " + (sz + 12) + " minecraft:stone")
                        ).ok());

        String botName = PlayerState.botName(this::exec);

        // The CLIENT's mark BEFORE the transfer is ordered: the twenty ticks that stood here were a
        // guess at one round trip plus a world teardown, and the read below is the client's own.
        long enterMark = clientEvents().mark();
        String enter = exec("artest space enter " + botName + " " + originDim
                + " " + sx + " " + sy + " " + sz);
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        awaitClientDim(enterMark, originDim,
                "everything this scenario does next is aimed at a ship in that cell");
        // The standing state, read ONCE after the link: the record says the respawn arrived, this
        // says which world he renders now, and a disagreement between them is worth seeing.
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // ONE spawn and ONE mount. The loop that stood here re-spawned the dummy and re-issued the
        // mount up to five times, and defended it as a stimulus loop. It was not one: both halves
        // are synchronous server commands — `seat-mount-at` spawns the dummy inside the command,
        // `mount-entity` looks it up in the player's own world and calls `startRiding` inside
        // THAT command — and the player is already in this world server-side, because the server
        // moves him before it sends the respawn the client link above waited for. Nothing is left
        // for a second attempt to wait for; a retry could only turn a refused mount into a pause.
        //
        // The mount's reply keeps what the retry was said to be FOR: the player's dim against the
        // dim the dummy was found in, and `gone` when no loaded world holds it — the answers that
        // separate "the wrong world was asked" from "it is not there at all". They now arrive in
        // the failure of the one attempt instead of being overwritten by the next.
        long seatClientMark = clientEvents().mark();
        String mountAt = exec("artest vs seat-mount-at " + originDim
                + " " + seatX + " " + seatY + " " + seatZ);
        assertTrue("seat-mount-at must spawn the seat dummy: " + mountAt, readBool(mountAt, "ok"));
        String mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
        // A read of the command's own verdict, not a wait, so a missing field is a refusal.
        assertTrue("the bot must mount the pilot-seat dummy on the first attempt: " + mount,
                Reply.of(mount).boolOr("mounted", false));
        int dummyId = readInt(mountAt, "dummyId");
        // THE CLIENT'S OWN SEATING, waited for as the record it is. Ten ticks stood here and the
        // failure they produced is quoted in this file's own diagnostic below — "the mount reported
        // success and he is off ten ticks later" — which under four client forks was a replication
        // lag and nothing else: the server reported him riding a live dummy holding one passenger
        // while the client had not applied it yet (measured 2026-09-15 in a full-tier gate).
        awaitClientMount(seatClientMark, "the bot must be seated BEFORE the jump (control), as the"
                + " CLIENT renders it — everything this scenario asks afterwards is about a pilot"
                + " the client believes is in the seat", SEAT_LINK_BUDGET_TICKS, "");
        // WHICH of the two happened, because "he is not riding" covers both and they are different
        // faults. Measured 2026-09-07: the mount reports success and ten ticks later he is off — and
        // nothing said whether the DUMMY was removed under him or he was dismounted from a dummy that
        // is still there. `deck-capture <dim> <id>` answers "entity not found" for a removed entity
        // and a full gate dump for a live one, which is the cheapest discriminator in the tree; it is
        // read only on the failing path, so a green pays nothing for it.
        if (!bot().reportRidingEntity().get("riding").getAsBoolean()) {
            String dummyNow = exec("artest vs deck-capture " + originDim + " " + dummyId);
            scenario().arrangementFailed("the bot must be seated BEFORE the jump (control) — the"
                    + " mount reported success and he is off ten ticks later. Whether the seat dummy"
                    + " (entity " + dummyId + ") still EXISTS is the difference between something"
                    + " removing it under him and something dismounting him from a live one:"
                    + " riding=" + bot().reportRidingEntity()
                    + " mountReply=" + mount
                    + " dummyNow=" + dummyNow
                    + " serverSaysRiding=" + exec("artest player riding-of " + botName));
        }

        // CONTROL LEG (pre-transit): the seated pilot's REAL key must fly the ship in the origin
        // cell BEFORE anything happens to him — a dead key after the arrival could otherwise be a
        // chain that never worked here at all. Retried on a bounded budget: right after the async
        // assembly the ship can still be settling (measured: the first climb window sometimes
        // catches it sinking), and the contract is a bounded window, not the first ten seconds.
        //
        // Marked on the CLIENT's log first. The chain from a held key to a moved ship has three
        // links and only two of them are the server's: this client decides it is piloting a ship,
        // it puts a packet on the wire, the seat receives it. The server's delivery window is the
        // third link's voice and it can only ever answer "nothing arrived" — which reads identically for a
        // client that never tried and a packet that was eaten on the way. The client's own gate
        // record separates them, and that is the difference between an ARRANGEMENT this test
        // failed to make and a control chain production broke.
        // The craft's ATTITUDE before the key is ever held. The failure message below reads the pose
        // at the END, and a craft found on its side there could have been placed that way or rolled
        // during the climb — two different faults with one symptom, and nothing in this scenario said
        // which. Measured 2026-09-07: the control leg fails with the ship at ~92 degrees thrusting
        // sideways at 40 b/s, every pilot input delivered and honoured, because up-thrust follows the
        // SHIP's up axis and that axis is horizontal.
        //
        // THE TILT IS NOW UNDERSTOOD, and the sentence above — "a tilted one is not yet known to be
        // a fault" — was true when it was written and is not any more. It is not the take-off tilt
        // the base class's lift helper exists for, and reaching for that helper here is wrong: it
        // lifts a craft OFF A PAD, and this craft is not standing on one.
        //
        // This scenario assembles its craft in the cell `transit-setup-empty` materializes, and that
        // cell is EMPTY by contract — the probe's own comment says so. So from the instant of
        // assembly there is nothing under the hull. And a craft that has never been FLOWN is
        // deliberately inert: `stationKeeping` is the persisted "was flown" witness, and until it is
        // set the flight computer returns having set no target attitude and no commanded velocity at
        // all. Nothing holds the hull up and nothing corrects its attitude, so it falls and whatever
        // spin the assembly gave it runs free. By the time the seated pilot first holds a key, the
        // pilot branch seeds `attitudeReference` from the hull's CURRENT attitude — so the tilt it
        // has accumulated by then is pinned permanently, and a vertical-up command, being
        // body-frame, becomes horizontal thrust.
        //
        // Measured 2026-09-13, in order: up-Y 0.23 with omega at the 2.0 rad/s cap and the hull
        // translating at ~40 b/s, while all 96 pilot inputs were received AND delivered with the
        // guard and the flight computer both satisfied — the red accused the control chain and
        // nothing was wrong with it. Then, with the level command below: up-Y 0.02 -> 0.77, velY
        // 0.0, omega 1.39 — the hold works, it simply needs its slew window.
        //
        // The probe attitude channel is used deliberately: it carries its own zero velocity and does
        // not go through the `stationKeeping` gate, so it can steady a craft that has never flown —
        // which is exactly this craft's state.
        String poseBeforeLevel = shipInfoById(originDim, shipId);
        assertTrue("the craft must accept a level attitude command before it is flown",
                Reply.of(exec("artest vs point-by-id " + originDim + " " + shipId + " 1.0 0.0 0.0 0.0")
                        ).bool("commanded"));
        // WINDOW: a converging attitude, where nothing decides it has arrived — the hold never
        // publishes "level". Its two ends are poseBeforeLevel and poseBeforeClimb, and both reach the
        // gate's message, so a red says whether the hull was slewing (short window) or never moved
        // (a command accepted and not obeyed, which no window repairs). Sized from the computer's own
        // limits: a 2.0 rad/s ceiling reached at 4.0 rad/s^2, so even a half-turn is about 45 ticks.
        // Overshoot is lenient, and the gate it feeds is an ARRANGEMENT, never the verdict.
        bot().waitTicks(LEVEL_WINDOW_TICKS);
        String poseBeforeClimb = shipInfoById(originDim, shipId);
        System.out.println("[relog] level window :: before=" + poseBeforeLevel
                + " || after " + LEVEL_WINDOW_TICKS + " ticks=" + poseBeforeClimb);
        requireUprightForAnAltitudeClaim(poseBeforeClimb,
                "the seated pilot's own key flies his craft before the transit (up-Y went "
                        + upYOf(poseBeforeLevel) + " -> " + upYOf(poseBeforeClimb) + " across the "
                        + LEVEL_WINDOW_TICKS + "-tick level window)");
        // AND THEN LET GO. The probe channel does not merely aim the hull: it carries a zero
        // velocity and keeps commanding it, so a craft left under it is being told to stay exactly
        // where it is. Leaving it active would put the pilot's own key in competition with a
        // standing order not to move, and the control leg would red with every input delivered and
        // honoured — which is what it did on the run before this line existed, with the hull level
        // (up-Y 0.9999), stationary (velY ~1e-18) and all 96 inputs received and delivered.
        assertTrue("the probe's attitude hold must be released before the pilot is asked to fly:"
                        + " it commands a zero velocity, so a craft still under it cannot climb",
                Reply.of(exec("artest vs force-clear-by-id " + originDim + " " + shipId)
                        ).ok());
        long clientPilotMark = clientEvents().mark();
        if (!climbedWithinAttempts(3)) {
            scenario().arrangementFailed("control leg: the pilot must be able to fly BEFORE the"
                    + " transit." + clientPilotAccount(clientPilotMark)
                    + " delivery=" + seatDelivery()
                    + " shipBeforeClimb=" + poseBeforeClimb
                    + " shipAfterClimb=" + shipInfoById(originDim, shipId));
        }

        // The climb moved the ship: the departure anchor is its CURRENT pose, never the build pose.
        // No settle before it: `transit-name` above gave the stack this craft's durable id, and a
        // named departure relocates its anchor to the flight computer's live position by identity
        // (`anchorRelocated` in the reply), so these three numbers are not what the jump reads —
        // and a pilot may jump a craft that is still easing off a climb.
        ShipInfo shipNow = ShipInfo.of(shipInfoById(originDim, shipId));
        int ax = (int) Math.round(shipNow.x);
        int ay = (int) Math.round(shipNow.y);
        int az = (int) Math.round(shipNow.z);

        // ---- ACT 1: depart into hyperspace. The reduced speed sizes the park at ~40 probe-driven
        // ticks (the cells sit one 4M-block sector apart), so the relog lands INSIDE the transit
        // instead of racing a single-tick jump. ---------------------------------------------------
        // The mark is taken BEFORE the departure: every link of the jump, and the relog inside it,
        // is then in the log, in order.
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        String begin = exec("artest space transit-begin " + originDim
                + " " + ax + " " + ay + " " + az + " " + HYPERSPACE_JUMP_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));
        scenario().requireArranged("the jump must depart under the craft's own name, never the synthetic"
                + " id a nameless fixture gets: " + begin, !"t".equals(Reply.of(begin).text("shipId")));
        // READ, not driven. This scenario's whole subject is a relog that happens WHILE the ship is
        // in transit, so a pump here would be advancing the jump towards the exit for the sake of
        // one field — and the server is advancing it on its own tick anyway.
        String firstTick = exec("artest space transit-status");
        assertTrue("the ship must actually be IN TRANSIT when the pilot relogs — otherwise this "
                + "pins an ordinary relog, not the mid-transit one: " + firstTick,
                TransitStatus.of(firstTick).inTransit >= 1);

        // ---- ACT 2: the real mid-transit relog. The transit is probe-driven, so the park waits
        // out the relog deterministically — no race between the login and the arrival. -----------
        // The CLIENT's mark goes before the reconnect, for the remount link at the end of the leg.
        long clientMark = clientEvents().mark();
        bot().reconnect();
        bot().waitForWorld();

        // ---- ACT 3 + ASSERT 1, as the server commits it: the whole jump, link by link, with the
        // relog's own restore verdict INSIDE the flight. The old form drove ticks until `inTransit`
        // hit 0 and then polled the client for `riding`, so a jump that settled with its pilot left in
        // the origin cell read as "not riding" and named no step; the chain stops at the link that did
        // not happen and prints the placement's own account (`crew_reseat_blocked`) beside it.
        events.assertChain(mark, "the relog must fall INSIDE the flight, between the departure and the"
                + " arrival cut — or this pins an ordinary relog", JUMP_LINK_BUDGET_TICKS,
                "transit_departed", "login_restored", "hyperspace_arrival_cut");
        events.assertChain(mark, "a pilot who relogged mid-transit must be re-seated on his ship ON"
                + " ARRIVAL: the jump must pick him up, board him on the parked hull, land, put him"
                + " back aboard and only then settle", JUMP_LINK_BUDGET_TICKS, PILOTED_JUMP_CHAIN);
        int targetDim = arrivedTargetDim(this::exec);

        // The CLIENT's half: the server's re-seat is a link above; whether his own client followed
        // it is read here as the LINK it is — his own `startRiding` — off the mark taken before the
        // reconnect. A poll of `reportRidingEntity` stood here and could only sample the state this
        // record announces.
        //
        // WHAT THIS LINK CANNOT SEPARATE, and the server chain is what covers it: there are two
        // remounts in this leg — the one his client performs when the login restores him to the
        // parked hull, and the one it performs on arrival — and NOTHING on the client log marks the
        // arrival cut, so a mark cannot be placed between them (a server sequence number is not a
        // client mark). The claim this link makes is therefore "his client did seat him again after
        // the relog"; the ARRIVAL re-seat is pinned by `PILOTED_JUMP_CHAIN` above, and the settled
        // reads below pin the end state — the seat dummy, and the target cell.
        JsonObject riding = ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);
        assertTrue("the re-mounted entity must be the ship's seat dummy: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertEquals("the relogged pilot must have followed his ship into the target cell",
                targetDim, bot().reportWeather().get("dim").getAsInt());

        // ---- ASSERT 1b: he arrived WHERE HIS SHIP'S LEDGER ROW SAYS, not still in the paste band.
        //
        // This used to ask whether his rendered Y had cleared HALF_CELL, on the reasoning that a
        // cell realized its contents megablocks up while an unsettled arrival was left at the
        // staging band's ordinary block Y. That discriminator was an artefact of the Y shift:
        // `CellWorldMapper` was centred on 2026-09-11 — world Y = local Y, the cell centre at the
        // world origin — so a settled ship whose coordinate has a local Y of 0 sits at world Y 0,
        // which no magnitude test can tell from anything. This scenario's target is
        // `ofSectorLocal(7001, 0, 0, 0, 0, 0)`, i.e. exactly that, so the old assertion demanded a
        // number the arrival can no longer produce and had been red since the mapping changed.
        //
        // A magnitude test cannot be repaired, either: under centring the paste band (world Y ~200)
        // and the cell centre (0) are 200 apart, which is well inside CARRY_MARGIN — production's
        // own answer to "still at this coordinate". So the claim is made against the LEDGER's
        // coordinate instead, which is what it meant all along and survives the next change of
        // mapping too.
        double arrivedY = clientPlayerY();
        // FOUR ALTITUDES, not one, because "he is not in the pose band" has four different subjects
        // and the number alone cannot say which lost it. Read in the order the value travels: the
        // ship the server holds, the server's own copy of the player, the seat dummy as the CLIENT
        // renders it, and the client's own player. A break between any two adjacent pair names the
        // hop; all four agreeing on a wrong number is a different bug from the client alone being
        // wrong, and this assertion used to report only the last of them.
        PlayerState serverPlayer = PlayerState.read(this::exec);
        String arrivedShip = shipInfoById(targetDim, shipId);
        double ridingY = riding.has("posY") ? riding.get("posY").getAsDouble() : Double.NaN;
        // "not loaded" rather than NaN: a ship the destination world does not hold has no altitude,
        // and a blank in this list must say WHICH of the four subjects is missing, not merely that
        // one number could not be read.
        String shipOnServer = ShipInfo.isLoaded(arrivedShip)
                ? String.valueOf(ShipInfo.of(arrivedShip).y) : "not-loaded-in-" + targetDim;
        String altitudes = "shipOnServer=" + shipOnServer
                + " playerOnServer=" + serverPlayer.y
                + " dummyOnClient=" + ridingY
                + " playerOnClient=" + arrivedY;
        scenario().record("arrivalAltitudes", altitudes);
        System.out.println("[relog] arrival altitudes :: " + altitudes
                + " || ship=" + arrivedShip + " || server=" + serverPlayer);
        // `requireFound` and not the bare read: the three offsets below are what the arrival is
        // measured against, and an absent row answers them NaN — which casts to a long zero and
        // names the cell CENTRE, a coordinate that looks exactly like a settled pose.
        EntryStatus ledgerRow = EntryStatus.forShip(this::exec, durableId)
                .requireFound("the ship this pilot relogged aboard must be in the entry ledger before"
                        + " its settled pose can be read off the row");
        double[] settled = CellWorldMapper.poseWorldOf(GalacticCoord.ofSectorLocal(0L, 0L, 0L,
                ledgerRow.lx, ledgerRow.ly, ledgerRow.lz));
        scenario().record("settledPose", java.util.Arrays.toString(settled));
        // CARRY_MARGIN as the tolerance, because it is production's OWN answer to "still at this
        // coordinate as far as the cell is concerned" — the distance a craft may sit past a face
        // before the seam carries it. The pilot rides a seat dummy a few blocks off his hull's own
        // pose, which that margin absorbs many times over.
        assertTrue("the relogged pilot must arrive where his ship's LEDGER ROW says it settled, not"
                        + " somewhere else in the cell: client-rendered Y=" + arrivedY
                        + " vs the ledger coordinate realized at Y=" + settled[1]
                        + " (tolerance " + CellSeam.CARRY_MARGIN + "). ledger=" + ledgerRow
                        + ". The four altitudes, in the order the value travels: " + altitudes
                        + " || ship=" + arrivedShip + " || server=" + serverPlayer,
                Math.abs(arrivedY - settled[1]) < CellSeam.CARRY_MARGIN);

        // ---- ASSERT 2 (load-bearing): control RESUMES on arrival — the held key flies the -------
        // arrived ship. A restored seat with a dead key is a broken chain, and it is exactly what
        // a stale pre-relog crew reference would produce. Same bounded retry as the pre-leg: the
        // just-crossed ship settles asynchronously in its target cell.
        long arrivedPilotMark = clientEvents().mark();
        boolean flewAfterRelog = climbedWithinAttempts(3);
        assertTrue("after a mid-transit relog, held input must MOVE THE ARRIVED SHIP - control "
                + "resumes on arrival." + (flewAfterRelog ? "" : clientPilotAccount(arrivedPilotMark))
                + " delivery=" + seatDelivery(),
                flewAfterRelog);
    }

    @After
    public void cleanup() {
        try {
            exec("artest player dismount");
        } catch (Exception ignored) {
        }
    }

    // --- helpers (mirror the tier-2 client e2e classes) -----------------------------------------

    /** Hold {@code key} until the client-rendered rider altitude climbs {@link #MIN_CLIMB} over
     *  {@code from} (bounded, early-exit); returns the last observed altitude. */
    private double climbWith(int key, double from) throws Exception {
        // THE MULTIPLIER STAYS, and this is what it waits on: a held key is sampled and re-sent per
        // CLIENT TICK - on change, plus a re-assert every PilotInputCadence.REPEAT_TICKS - so a loaded
        // box stretches the climb through the client's TICK rate. Wall-clock-bound work, which is the
        // one shape a fork scale measures. (NOT "once per rendered frame": that was the standing
        // explanation until 2026-08-21 and it is false.)
        int budget = 40;
        double last = from;
        bot().holdKey(key);
        try {
            // A WINDOW with the key HELD across it: the iterations are part of the stimulus, and
            // what is asked is whether the climb reached a threshold — a value, not an instant
            // anything commits. What it cannot see: a climb that reached MIN_CLIMB and sagged back
            // inside one 5-tick sample.
            for (int i = 0; i < budget && (last - from) < MIN_CLIMB; i++) {
                bot().waitTicks(5);
                last = clientPlayerY();
            }
        } finally {
            bot().releaseKey(key);
        }
        return last;
    }

    /** Up to {@code attempts} bounded held-key climb windows with a settle between them; true as
     *  soon as one window sees the client-rendered altitude gain {@link #MIN_CLIMB}. */
    private boolean climbedWithinAttempts(int attempts) throws Exception {
        // STAYS A LOOP: each iteration IS a stimulus — a fresh held-key climb window — and the
        // thing being asked is whether one of them moved the client-rendered altitude by enough.
        // A link would have to be a record of the climb, and the drive publishes none: what exists
        // is the input reaching the flight computer, which is already awaited where the key is
        // pressed. What this cannot see: which attempt did it, and whether an earlier one nearly
        // did.
        for (int i = 0; i < attempts; i++) {
            double from = clientPlayerY();
            double to = climbWith(Keyboard.KEY_R, from);
            if ((to - from) >= MIN_CLIMB) {
                return true;
            }
            bot().waitTicks(40);
        }
        return false;
    }

    /**
     * What THIS CLIENT did about piloting since {@code mark}, in its own words — the two links of the
     * control chain that live on its side of the wire.
     *
     * <p>{@code ship_pilot_gate_decided} is the keybind handler's own return: {@code open} is true
     * exactly on a tick where it resolved a linked, ship-managed pilot seat for the mount the player
     * rides, and {@code ridingDummy} says he was on a seat mount at all. {@code pilot_input_sent} is
     * recorded at the one call that puts a {@code PACKET_PILOT_INPUT} on the wire. Between them and
     * the server's {@code received}, a dead key names its own link:</p>
     *
     * <ul>
     *   <li>no {@code open:true} at all, with {@code ridingDummy:true} — the client rode the seat
     *       mount and could NOT resolve its seat: the ship is not on this client (its subspace
     *       chunks never arrived, or nothing manages them here). Nothing was ever sent, and the
     *       scenario's subject was never exercised — an ARRANGEMENT this test did not make.</li>
     *   <li>{@code open:true} and {@code pilot_input_sent} records, against a server {@code received}
     *       of zero — the packet left and did not arrive, which is the wire, not the arrangement.</li>
     *   <li>{@code open:true} and sends, and the server received and delivered them, and the ship
     *       still did not carry him — the ship ignored its pilot, which is the contract.</li>
     * </ul>
     *
     * <p>The instrument is asserted to have RUN before any of that is read: an empty client log is
     * produced equally by a gate that never opened and by a mixin that never wove, and only the
     * first is an answer.</p>
     */
    private String clientPilotAccount(long mark) throws Exception {
        String gate = clientEvents().since(mark, "ship_pilot_gate_decided");
        Events.assertInstrumentRan(gate, "ship_pilot_gate_events",
                "this client's ship-control gate did, or did not, open while the key was held");
        String sent = clientEvents().since(mark, "pilot_input_sent");
        return " client: gateOpen=" + Events.countRecords(gate, "open", "true")
                + " gateClosed=" + Events.countRecords(gate, "open", "false")
                + " onSeatMount=" + Events.countRecords(gate, "ridingDummy", "true")
                + " inputsSent=" + Events.countRecordsWithField(sent, "seat")
                + " lastSentSeat=" + Events.lastField(sent, "seat")
                + " (gate=" + gate + " sent=" + sent + ")";
    }

    /** The client's own rendered player altitude, or NaN while it has no world/player. */
    private double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces the load). */
    private int waitForLoadedShip(int dim) throws Exception {
        // ASSERTED, not waited for — see ShipReadiness, which carries the measurement: the waiting
        // branch of this helper never executed on the server tier, at one fork or at six, because
        // the ship is already loaded by the time a scenario asks. The postcondition fails at once
        // and names whether the craft never REGISTERED or registered and did not LOAD.
        return ShipReadiness.requireLoaded(this::exec, dim,
                "this scenario's craft must be loaded before the transit is driven");
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
        assertTrue("expected number \"" + key + "\" in: " + json, Reply.of(json).has(key));
        return Reply.of(json).number(key);
    }

    /** As above, but ABSENCE is an answer: a diagnostic that refuses is worse than one that says
     *  the field was not there. Only for building a failure message — never for a verdict. */
    private static double readDoubleOr(String json, String key) {
        return Reply.of(json).number(key);
    }

    private static boolean readBool(String json, String key) {
        return Reply.of(json).bool(key);
    }
}
