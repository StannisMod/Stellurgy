package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.ShipInfo;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE e2e: is a tier-2 ship CONTROLLABLE — and does the real client keep tracking it — at extreme
 * world Y, just under the TOP of the cells' realized pose band, so the whole advertised vertical
 * range is evidenced and not only the middle? The altitude is DERIVED from the band's own
 * production constants (see {@link #EXTREME_Y}) rather than written down, so it follows the band
 * when the band moves. The honest-Y realization
 * question: entities are NOT capped by the 256
 * build height (blocks are; vanilla's only hard line for entities is the void-kill below −64), so a
 * ship's world-frame pose can realize a galactic local-Y directly. A green run = GO for amending
 * the planar realization rule to an honest Y mapping.
 *
 * <p>The leg re-runs the SAME full-path pilot contract as the in-run control (real seated bot, real
 * vertical-up key, ship climbs; client rider tracks the server ship) — so a FAIL localises to the
 * coordinate regime, not to the pilot path. The arrange step is the rigid ship teleport
 * ({@code vs teleport-ship}: pose moves, subspace blocks stay, VS Y-limits widen, riders carried).
 *</p>
 *
 * <h2>Where this is staged, and why it could not be staged where it was</h2>
 *
 * <p>Inside a CELL world, reached through {@code space transit-setup-empty}, which hands back an
 * empty origin cell's slot dimension. Not in the overworld: an ordinary world has an orbit line, so
 * a craft rigid-teleported to an extreme altitude there is taken by the production entry on-ramp
 * into a space cell UNDER A NEW IDENTITY before the first assertion runs (measured 2026-08-21, and
 * it is why this scenario spent three weeks disabled). There is no altitude that is both extreme and
 * still in an ordinary world — but a cell world is where the pose band is realized in the first
 * place, so staging it there is not a workaround, it is the honest home.</p>
 *
 * <p>That the craft STAYS put is therefore asserted, not assumed: after the teleport this scenario
 * checks the craft is still the same identity in the same world. That single check is what the old
 * arrangement lacked, and without it a red here describes a craft the test never flew.</p>
 *
 * <p><b>Three findings were recorded while first building this spike, and all three are SUSPECT:</b>
 * they were taken under the overworld arrangement above, which is now known to have been measuring a
 * CROSSING rather than an extreme pose. They are listed here as open questions, not as facts, and
 * must be re-taken here before anyone cites them:
 * (1) VS's load controller UNLOADS the teleported ship's physics object even with the pilot aboard
 * — {@code permanentlyLoaded} was the workaround, and if it holds, production honest-Y must own
 * loadedness;
 * (2) a VS collision mixin ({@code preGetCollisionBoxes}) prints a console line EVERY TICK for an
 * entity at extreme Y — log flood, and it races probe replies;
 * (3) after a SECOND relocation the ship's physics goes inert (neither pilot key nor push-ship
 * moves it) and the pilot-key path dies after a dismount&rarr;re-seat across the map.
 *
 * <p><b>(3) first half: RE-TAKEN 2026-09-11 and it does NOT reproduce.</b> Leg 2 below relocates the
 * same craft a second time, in the same cell, and the pilot flies it afterwards through the same
 * contract the control leg passed — craft climbs, client rider tracks. So "a second relocation kills
 * the physics" is not true of a craft that STAYS ITSELF. It is not thereby disproved of the
 * arrangement it was seen in: that one teleported in an ordinary world, where the entry on-ramp took
 * the craft into a cell under a NEW identity between the two moves, and a second move landing on a
 * craft whose identity changed under it is a different question this leg does not ask. The SECOND
 * half of (3) — the pilot-key path after a dismount&rarr;re-seat across the map — is untested and
 * stays open, and so does the extreme-|X| precision leg it blocks.</p></p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipExtremeCoordinatesE2ETest extends AbstractSharedVsClientE2ETest {

    /**
     * How far a rigid teleport may leave the craft from where it was SENT, in blocks.
     *
     * <p>The TEST'S OWN, and wide because the subject is extreme coordinates: at a magnitude of
     * millions a double's own spacing is metres, so two hundred blocks is the precision the
     * arrangement can claim rather than a tolerance for drift.</p>
     */
    private static final double TELEPORT_LANDED_WITHIN_BLOCKS = 200;

    /**
     * How far the CLIENT's rendered rider may sit from the SERVER's ship climb, in blocks — the
     * test's own replication tolerance, under a craft's own height.
     */
    private static final double RIDER_TRACKS_SHIP_BLOCKS = 3.0;

    @Override
    protected String subsystem() {
        return "vs-ship-extreme-coordinates";
    }

    private static final String DUMMY_ID = "dummyId";
    private static final String ORIGIN_DIM = "originDim";

    private static final String VARIANT = "with-pilot-seat";
    /**
     * The craft's horizontal base. The Y is not here because the SITE owns it, and the site cannot
     * be a constant: this class builds in a space CELL whose dimension id is only known at run time.
     *
     * <p><b>NOT allocated from a plot, and this is the reason rather than an oversight.</b> Every
     * other ship scenario asks {@code site()} for its ground, because it shares a world with its
     * siblings and the plot is what keeps them apart. This one builds inside a cell created for it
     * alone: there is no sibling to collide with, so an allocator would be protecting against
     * nothing — and it would move the craft into a region of a dimension whose extent nobody here
     * has measured, which is a real risk bought for no contract. A cell scenario that ever stands
     * TWO structures is the case that changes this answer.</p>
     */
    private static final int BX = 3400, BZ = 3400;

    /**
     * How far below the TOP of the realized pose band this scenario flies, in blocks. The margin is
     * the quantity — it says "near the ceiling, with room to climb" — and the ceiling itself is read
     * from production rather than copied into a literal.
     */
    private static final double BELOW_BAND_TOP = 1_000d;

    /**
     * The extreme altitude under test: just under the top of the band a cell's poses are realized in.
     *
     * <p><b>DERIVED, never a literal.</b> {@code CellWorldMapper} realizes a cell's local Y
     * directly — the cell is centred on the world origin on all three axes — so the band occupies
     * world {@code [-HALF_CELL, HALF_CELL)}, and that bound is a production constant that MOVES.
     * This test carried {@code 3_999_000} as a literal, chosen when a cell was 4,000,000
     * blocks; the cell became 32,000,000 on 2026-08-20 and the literal silently stopped meaning
     * "near the top of the range" — it became a point in the lower eighth of it, so the scenario
     * stopped evidencing the thing its own javadoc says it evidences. A test that hard-codes a
     * coordinate the product derives is pinned to an implementation detail, and it goes on passing
     * or failing for reasons that have nothing to do with its subject.</p>
     */
    private static final double EXTREME_Y =
            (double) zmaster587.advancedRocketry.space.GalacticCoord.HALF_CELL - BELOW_BAND_TOP;

    /**
     * How far the client-rendered rider may be from the server ship it is glued to, in blocks — the
     * same tolerance {@link #climbLeg} uses for the tracking it measures during a climb.
     */
    private static final double RIDER_TRACKING_TOLERANCE = 3.0;

    /**
     * How far along X the craft is moved a SECOND time, in blocks. Far enough that the move is a
     * real relocation rather than a nudge, and small against {@code HALF_CELL} so the seam cannot
     * carry the craft into a neighbouring cell part-way through the leg — the subject is the
     * SEQUENCE of two moves, and a crossing in the middle of it would answer a different question.
     */
    private static final int SECOND_RELOCATION_X = 50_000;

    /**
     * This scenario's ship, by IDENTITY. Captured once at the base, where the ship is the only
     * thing that can be there, and used for every question afterwards.
     *
     * <p>The positional form of {@code ship-info} is a NEAREST-ship lookup, and this scenario spends
     * its whole length making that lookup meaningless on purpose: the ship is rigid-teleported to
     * {@link #EXTREME_Y} and then flown further. A query point that trails the ship answers
     * about a neighbour or about nothing, and both replies have the shape of a correct one — so a
     * red here would describe a craft the test never built, which is a worse outcome than the red
     * it is trying to explain.</p>
     */
    private String shipId;

    /**
     * The cell world this scenario is staged in, answered by the setup rather than written down: a
     * slot is handed out from a pool, so the number differs per run and per fork.
     */
    private int cellDim;

    @Test
    public void aSeatedPilotKeepsControlAtExtremeY() throws Exception {

        // ── Arrange: an empty origin CELL, and a real piloted craft assembled inside it. The cell is
        // where the pose band is realized, and it carries no orbit line to take the craft off the
        // altitude this whole scenario is about — see the class javadoc for what staging it in the
        // overworld cost. ──
        cellDim = TransitSetup.empty(this::exec).originDim;

        // Held loaded BEFORE the craft exists, and the reason is worth keeping even though the lever
        // is no longer pulled here. A craft assembled in a cell has no player anywhere near it — the
        // pilot cannot enter until his seat is found, and the seat cannot be found until the craft is
        // built — so the substrate's load controller drops its physics object inside that very
        // window. Measured on this scenario's first run in a cell: the log read `ship_spawned`,
        // `ship_loaded`, `ship_unloaded`, and the craft never became usable at all. A test server
        // holds its ships loaded from the moment the probes register, which is before any of this.

        Events events = events();
        long assemblyMark = events.markInstrumented();
        String assemble = assembleFixture(FixtureSite.openAir(cellDim, BX, BZ), VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        // The craft's CREATION, and its identity, from one record. A count that rises says a ship
        // appeared somewhere in the world; `ship_spawned` says which craft was made, so the identity
        // and the existence are the same fact and neither is polled for.
        shipId = awaitShipSpawned(events, assemblyMark,
                "the with-pilot-seat build must become a ship inside the origin cell");
        // LOADED is a second fact and it has its own record: a spawned ship the physics loop is not
        // stepping cannot be flown, and the difference used to be a loop reading `managed` back.
        awaitShipUsable(events, assemblyMark, shipId);

        // Put the CLIENT in the cell too: every tracking assertion below is about what this client
        // renders, and a client in another world renders none of it.
        PilotSeat seat = PilotSeat.byId(this::exec, cellDim, shipId)
                .requireFound("the pilot seat must be found in the assembled craft");
        long enterMark = clientEvents().mark();
        String enter = exec("artest space enter " + botName() + " " + cellDim
                + " " + (int) Math.round(seat.shipWorldX)
                + " " + (int) Math.round(seat.shipWorldY)
                + " " + (int) Math.round(seat.shipWorldZ));
        scenario().requireArranged("space enter into the origin cell must succeed: " + enter,
                Reply.of(enter).ok());
        // WAS `waitTicks(20)` and a read of the weather report's dim — a budget between the
        // order and the read is an assertion about this box, not about the transfer.
        awaitClientDim(enterMark, cellDim,
                "everything below is arranged on the client's side of the boundary");

        SeatMount mountInfo = SeatMount.onShip(this::exec, cellDim, shipId);
        assertTrue("seat-mount must find the pilot seat: " + mountInfo.raw(),
                mountInfo.seatFound);
        // The reader's own id. The `Reply` this replaces was labelled `seat-mount-at` while reading
        // a `seat-mount` answer, so every refusal it could raise named a verb nobody had asked.
        int dummyId = mountInfo.requireDummyId();
        // The mark before the mount command, and then the CLIENT's own seating as a LINK. The ten
        // ticks this replaces were a guess at replication, and everything below rides on him being
        // aboard: measured 2026-09-16 in a full-tier pair, the same tree that had just run this
        // green failed the very next arrangement gate with `riding:false` and an EMPTY client mount
        // window — the boarding had not reached the client inside ten ticks under load, and the
        // budget could only ever be too short, never wrong in a way that says so.
        long seatMountMark = clientEvents().mark();
        assertTrue("bot must mount the seat dummy",
                Reply.of(exec("artest player mount-entity " + dummyId)).bool("mounted"));
        awaitClientMount(seatMountMark, "the client must FOLLOW the seat boarding before anything"
                        + " below is asked of a pilot — every leg here is about what a SEATED body"
                        + " does when its craft moves", CLIENT_REMOUNT_BUDGET_TICKS,
                " | the server's own seat-mount reply was: " + mountInfo.raw());

        // SUSPECT FINDING (1), and it is kept as a WORKAROUND here rather than re-taken: after a
        // rigid teleport to extreme Y, VS's load controller was seen unloading the physics object
        // even with the pilot aboard ("managed":false) — the ship exists but stops ticking.
        // It was seen under the overworld arrangement, which was measuring a crossing, so it is not
        // evidence about an extreme pose yet — and it has already lost half its mystery: the same
        // unload happens at ORDINARY coordinates in a cell, to a craft nobody is near, which is why
        // the lever is now taken above before the craft is even built. Re-taking what is left of the
        // finding means dropping the lever around the teleport ALONE and watching for a
        // `ship_unloaded` carrying this craft: a separate measurement, deliberately not folded into
        // the first run of a re-homed scenario, where it would be a second variable.

        // ── CONTROL leg: the pilot path works at ordinary coordinates (proves the instrument fires). ──
        climbLeg("control @ base");

        // ── Leg 1: the top of the pose band. Both commands name the SHIP, not a place: the source
        // pose is the probe's business (it reads the registry) and the destination is somewhere the
        // ship has never been, so neither end is a point this test can address from. ──
        // Marks taken BEFORE the move, on BOTH logs: whatever happens to the rider happens during
        // it, and a mark taken afterwards cannot see an edge that has already passed.
        long riderMark = clientEvents().mark();
        long riderServerMark = events().markInstrumented();
        String tpY = exec("artest vs teleport-ship-by-id " + cellDim + " " + shipId
                + " " + BX + " " + EXTREME_Y + " " + BZ);
        assertTrue("teleport-ship to extreme Y must succeed: " + tpY, Reply.of(tpY).ok());
        bot().waitTicks(30); // transform adoption + rider sync settle
        // THE PREMISE THIS SCENARIO DIED OF, now asserted. In an ordinary world the teleport above is
        // followed by the entry on-ramp taking the craft into a cell under a NEW identity, and every
        // reading afterwards is about a craft this test never flew. Here the craft is already in a
        // cell, so it should stay — and "should" is why this is a check and not a comment. Asked BY
        // IDENTITY of the cell's own ship manager: if the craft had crossed, the same id answers
        // managed:false here, which is exactly the discrimination the old arrangement lacked.
        String stayed = shipInfoById(cellDim, shipId);
        scenario().requireArranged("the craft must still be THIS craft in THIS cell after the"
                + " teleport — a crossing here would replace it with a new identity and everything"
                + " below would describe a different ship: " + stayed,
                ShipInfo.isLoaded(stayed));
        String unparked = exec("artest vs unpark-by-id " + cellDim + " " + shipId);
        assertTrue("the teleport leaves the ship PARKED by VS's own recipe, and a parked ship cannot"
                + " be flown — the unpark must take: " + unparked, Reply.of(unparked).ok());
        bot().waitTicks(10);
        String serverInfoAfterTp = shipInfoById();
        scenario().requireArranged("the teleported ship must still be loaded, or there is no server "
                        + "pose for the rider to be compared against: " + serverInfoAfterTp,
                ShipInfo.isLoaded(serverInfoAfterTp));

        // THE CONTRACT, and it names no coordinate: a rider is glued to his ship, so wherever the
        // ship ends up the client must render him THERE. Asserting he reached a particular altitude
        // instead would pin the arrangement's own request — and did: the old form compared him to a
        // hard-coded destination, so it could fail either because the rider came adrift or because
        // the ship never went where it was sent, and the message could not tell the two apart.
        double shipYAfterTp = ShipInfo.of(serverInfoAfterTp).y;
        // IS HE STILL ABOARD AT ALL — asked before he is measured, because the two are different
        // questions and only one of them has an answer shaped like a number. A bare
        // `reportRidingEntity().get("posY")` raised a NullPointerException here with no message at
        // all when the client was not riding (measured on this scenario's first green-arrangement
        // run), which reports nothing about a client that has just been carried thirty million
        // blocks. The client's own mount records across the teleport are in the message so a reader
        // can tell "he was put down" from "he was never picked up".
        //
        // NOT `ridingOnceTheClientHasRemounted`: that helper waits for the client's re-`startRiding`
        // after a DIMENSION CHANGE tears his world down and rebuilds it. This is a rigid teleport
        // inside one world — no rebuild, so no remount is owed, and waiting for one would time out
        // and then blame a crossing that never happened.
        double riderY = requireStillAboard("after the craft is rigid-teleported to the top of the"
                + " pose band", riderMark, riderServerMark).get("posY").getAsDouble();
        assertTrue("the CLIENT-rendered rider must arrive WITH his ship: rider=" + riderY
                        + " ship=" + shipYAfterTp + " (apart by "
                        + Math.abs(riderY - shipYAfterTp) + " blocks); commanded=" + EXTREME_Y
                        + "; server ship after teleport: " + serverInfoAfterTp,
                Math.abs(riderY - shipYAfterTp) < RIDER_TRACKING_TOLERANCE);

        // Separately, and only after the tracking question is settled: the rigid teleport must have
        // put the ship where it was TOLD to go. Two facts, two assertions — a single one comparing
        // the rider to the request conflates them.
        assertTrue("teleport-ship must leave the ship at the altitude it was given: commanded="
                        + EXTREME_Y + " ship=" + shipYAfterTp,
                Math.abs(shipYAfterTp - EXTREME_Y) < TELEPORT_LANDED_WITHIN_BLOCKS);
        climbLeg("extreme Y");

        // ── Leg 2: A SECOND RELOCATION, which is suspect finding (3) of the class javadoc — "after a
        // second relocation the ship's physics goes inert (neither the pilot key nor the push-ship
        // velocity setpoint moves it)". It was recorded under the overworld arrangement, so it
        // describes a craft that had been taken into a cell under a new identity between the two
        // moves, and nobody has asked it of a craft that stayed itself. Ask it here: the same craft,
        // the same cell, moved again, and then flown by the same pilot through the same contract.
        //
        // Along the cell's X, at the altitude already reached: the subject is the SEQUENCE (a second
        // move at all), not a second coordinate regime, and changing two things at once would make a
        // red unattributable. Well inside the face, so the seam cannot carry the craft mid-leg.
        long secondMark = clientEvents().mark();
        long secondServerMark = events().markInstrumented();
        String tp2 = exec("artest vs teleport-ship-by-id " + cellDim + " " + shipId
                + " " + (BX + SECOND_RELOCATION_X) + " " + EXTREME_Y + " " + BZ);
        assertTrue("the second teleport must succeed: " + tp2, Reply.of(tp2).ok());
        bot().waitTicks(30);
        String unparked2 = exec("artest vs unpark-by-id " + cellDim + " " + shipId);
        assertTrue("the second teleport leaves the craft PARKED, and a parked craft cannot be flown"
                + " — a red below would then be about the park, not about the physics: " + unparked2,
                Reply.of(unparked2).ok());
        bot().waitTicks(10);

        String afterSecond = shipInfoById();
        // THE MOVE ITSELF, before anything is asked about flying. A craft that did not arrive cannot
        // disprove anything about a craft that did, and the two reds read identically at the climb.
        scenario().requireArranged("the craft must still be THIS craft in THIS cell after the second"
                + " teleport: " + afterSecond, ShipInfo.isLoaded(afterSecond));
        assertTrue("the second teleport must leave the craft where it was sent: commanded X "
                        + (BX + SECOND_RELOCATION_X) + " ship=" + afterSecond,
                Math.abs(ShipInfo.of(afterSecond).x - (BX + SECOND_RELOCATION_X)) < TELEPORT_LANDED_WITHIN_BLOCKS);
        requireStillAboard("after the craft's SECOND relocation", secondMark, secondServerMark);

        // The subject: does he still fly it? `climbLeg` holds the real vertical key, asserts the
        // craft climbs, and asserts the CLIENT-rendered rider climbs with it — the same contract the
        // control leg and the first relocation passed, so a red here is about the second move and
        // nothing else.
        climbLeg("after a second relocation");

        exec("artest player dismount");
    }

    /**
     * One controllability measurement at the ship's current location: hold the REAL vertical-up key,
     * the server ship must climb, and the CLIENT-rendered rider must climb WITH it (tracking within
     * the same tolerance the ordinary-coordinates pilot e2e uses — a precision breakdown at extreme
     * coordinates shows up here as divergence).
     */
    /**
     * The pilot is still ABOARD, or a failure carrying every record that says why he is not.
     *
     * <p>Asked before he is MEASURED, because "where is he" and "is he there at all" are different
     * questions and only the first has an answer shaped like a number. A bare
     * {@code reportRidingEntity().get("posY")} raised a NullPointerException with no message at all
     * when the client was not riding — which reports nothing about a client that has just been
     * carried across a cell.</p>
     *
     * <p>The records are what turned this from a symptom into a diagnosis on its first run: the
     * server's own dismount carried the caller trail {@code PlayerList.playerLoggedOut}, so the
     * rider had not come adrift — the CONNECTION had gone — and the kick record then named
     * {@code multiplayer.disconnect.invalid_player_movement}. A kick type ABSENT while he is gone
     * says the server did not throw him out at all, which is a different failure from any kick.</p>
     *
     * <p>NOT {@code ridingOnceTheClientHasRemounted}: that waits for the client's
     * re-{@code startRiding} after a DIMENSION CHANGE tears his world down and rebuilds it. A rigid
     * teleport inside one world owes no remount, and waiting for one would time out and then blame a
     * crossing that never happened.</p>
     */
    private com.google.gson.JsonObject requireStillAboard(String when, long clientMark,
                                                          long serverMark) throws Exception {
        com.google.gson.JsonObject riding = bot().reportRidingEntity();
        assertTrue("the pilot must still be ABOARD " + when + " — everything that rides a craft is"
                        + " carried by the move, so a client that is not riding here is the finding,"
                        + " not a detail. client=" + riding
                        + "; the client's own mounts: " + clientEvents().since(clientMark, "mount")
                        + "; its dismounts: " + clientEvents().since(clientMark, "dismount")
                        + "; the server's dismounts: " + events().since(serverMark, "dismount")
                        + "; the client's own disconnect: "
                        + clientEvents().since(clientMark, "client_disconnected")
                        + "; the server's logout: "
                        + events().since(serverMark, "player_logged_out")
                        + "; the server's kicks: "
                        + events().since(serverMark, "server_kicked_player"),
                riding.has("posY"));
        return riding;
    }

    private void climbLeg(String label) throws Exception {
        double yBefore = shipY();
        // THROUGH THE GUARD, not a bare read. This call site was doing
        // `reportRidingEntity().get("posY")` directly, and on 2026-09-14 it raised a
        // NullPointerException with no message at all — the single least informative red a client
        // e2e can produce, about the one question this class exists to answer. The guard beside it
        // was written for exactly this and says whether he was PUT DOWN or never PICKED UP, with the
        // client's mounts, the server's dismounts, the logout and the kick all in the message. The
        // marks are taken here because a climb leg has no earlier one of its own.
        long climbClientMark = clientEvents().mark();
        long climbServerMark = events().mark();
        double riderYBefore = requireStillAboard("before the " + label + " climb leg is driven",
                climbClientMark, climbServerMark).get("posY").getAsDouble();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // A MEASUREMENT, not a wait, and the window early-exits because the key is held while it
            // runs: nothing decides an altitude, so there is no record to await and none worth
            // adding, while the delivery of the held key — the half that IS something production
            // does — is the link this leg's own rider comparison rests on.
            lift = ClientPoll.until(bot()::waitTicks,
                    this::shipY,
                    y -> y - yBefore > 1.5, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double yAfter = lift.value;
        assertTrue("[" + label + "] the vertical-up key must lift the ship (yBefore=" + yBefore
                + " yAfter=" + yAfter + ")", yAfter - yBefore > 1.0);
        bot().waitTicks(6);
        double serverDelta = shipY() - yBefore;
        // Through the guard for the same reason as the read before the climb: a pilot who came adrift
        // DURING the leg is the most interesting way this can fail, and a bare read turns it into a
        // NullPointerException that names neither the leg nor the moment.
        double riderDelta = requireStillAboard("after the " + label + " climb leg was driven",
                climbClientMark, climbServerMark).get("posY").getAsDouble() - riderYBefore;
        // Third witness on divergence: the SERVER-side player position separates "the seat glue died
        // server-side" (server player static too) from "the client stopped tracking" (server player
        // climbed, client did not).
        String serverPlayer = exec("artest player health");
        assertTrue("[" + label + "] the CLIENT rider must track the server ship's climb (client="
                + riderDelta + " server=" + serverDelta + "); server player: " + serverPlayer,
                Math.abs(riderDelta - serverDelta) < RIDER_TRACKS_SHIP_BLOCKS);
    }

    /**
     * The report for THIS scenario's ship, asked of THIS scenario's cell — by identity, so there is
     * no distance term to be wrong about, and named to the world it is staged in, so a
     * {@code managed:false} means "not loaded" rather than "you asked the overworld".
     */
    private String shipInfoById() throws Exception {
        return shipInfoById(cellDim, shipId);
    }

    /**
     * The server ship's posY, retried while the craft is not loaded here.
     *
     * <p>A ship that has UNLOADED answers {@code managed:false} and carries no {@code posY}, so it
     * exhausts the retries and fails naming the reply. That is the intended report: "this ship is
     * not loaded" is a different fact from "the ship near this point moved", and the positional form
     * this replaced could not tell them apart.</p>
     *
     * <p>It is NOT tolerant of a mangled reply, and the note claiming otherwise was removed rather
     * than kept: at extreme coordinates a VS collision mixin spams STDERR into the captured console
     * window, and the retry was written for that — but the parse under it has refused a non-JSON
     * reply outright since it moved onto the shared reader, so the loop never saw a second chance.
     * A reply that is not this verb's now fails here, loudly, instead of being retried nine times
     * and then reported as a ship that would not load.</p>
     */
    private double shipY() throws Exception {
        String last = "";
        for (int i = 0; i < 10; i++) {
            last = shipInfoById();
            if (ShipInfo.isLoaded(last)) {
                double y = ShipInfo.of(last).y;
                if (!Double.isNaN(y)) {
                    return y;
                }
            }
            bot().waitTicks(2);
        }
        throw new AssertionError("ship-info never returned a parseable posY for ship " + shipId
                + "; last reply: " + last);
    }

    private double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        return value;
    }

    private String assembleFixture(FixtureSite site, String variant)
            throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int dim = site.dim, baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`, which is the
        // number the pre-clear it replaces was throwing away. The cell this builds in is void, so
        // there is no terrain to escape — what the band buys here is ONE definition of where a
        // fixture stands, shared with every other class, instead of a 64 nobody chose.
        return RocketFixture.assembleAt(site, this::exec, variant, 2, 16,
                "the craft that is then flown to the far edge of the realized pose band");
    }
}
