package dev.stannismod.stellurgy.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import dev.stannismod.stellurgy.test.SeatMount;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.TransitSetup;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

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
 * stays open.</p>
 *
 * <p><b>Extreme |X|</b> is measured by the two legs at the end of the class, in the overworld at a
 * Z below the physics mod's reserved quadrant: where a player delivery stops working, and whether a
 * ship ASSEMBLED far from the origin loads, flies and is tracked by its client rider as at the origin.
 * They assemble at the coordinate instead of relocating a ship to it, which is why (3) does not block
 * them.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipExtremeCoordinatesTest extends AbstractSharedVsClientTest {

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
            (double) dev.stannismod.stellurgy.space.GalacticCoord.HALF_CELL - BELOW_BAND_TOP;

    /**
     * How far the client-rendered rider may be from the server ship it is glued to, in blocks — the
     * same tolerance {@link #climbLeg} uses for the tracking it measures during a climb.
     */
    private static final double RIDER_TRACKING_TOLERANCE = 3.0;

    /** How long the client's copy of the rider may take to follow a rigid teleport of his ship —
     *  a deadline for one discrete write, never a settle. */
    private static final int RIDER_ARRIVAL_LINK_BUDGET_TICKS = 200;

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

    /**
     * A seated pilot keeps control of his ship at an extreme Y, through two teleports.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-28. THE SECOND UNPARK — the id-keyed
     * {@code VSBridge#unparkShip} at {@code ship.setPhysicsEnabled(true)} failing a ship's second unpark
     * only: "the second teleport leaves the
     * craft PARKED … {\"ok\":false}". THE SECOND LANDING — {@code VSBridge.teleportShipToByUuid}'s
     * transform writes ({@code VSBridge#teleportShip} at
     * {@code ship.setPrevTickShipTransform(moved); ship.setShipTransform(moved)}) skipped on the second
     * extreme-Y move: "the second
     * teleport must leave the craft where it was sent: commanded X 53400 ship=… posX 3400.0". THE
     * SECOND TELEPORT —
     * {@code teleportShipAndEveryoneAboard} refusing its second call only: "the second teleport must
     * succeed: {\"ok\":false,…}". THE RIDER STAYS WITH HIS SHIP — the server seat glue
     * ({@code EntityDummy#onUpdate} at {@code setPosition(worldSeat[0], worldSeat[1], worldSeat[2])})
     * putting the mount 10 above its seat at extreme Y, and the mount's rider
     * offset lowered by 10 so the rider himself still arrives at the seat: "the CLIENT-rendered rider
     * must arrive WITH his ship … apart by 12.54 blocks". A client-side-only glue offset stayed green:
     * the client's mount is placed by the server's entity tracker, not by its own glue. THE TELEPORT —
     * {@code VSShipCrossingOps#teleportShipAndEveryoneAboard} at
     * {@code if (!teleportShipAndItsMounts(world, shipId, sx, sy, sz, px, py, pz))} refusing:
     * "teleport-ship to extreme Y must succeed: {\"ok\":false,…}". THE UNPARK — the id-keyed
     * {@code VSBridge#unparkShip} at {@code ship.setPhysicsEnabled(true)} never taking: "the unpark must take:
     * {\"ok\":false}". THE RIDER ARRIVES — BOTH carriers off, the mount loop of
     * {@code VSShipCrossingOps#teleportShipAndItsMounts} at
     * {@code d.setPositionAndUpdate(d.posX + (px - sx), d.posY + (py - sy), d.posZ + (pz - sz))} and the
     * seat glue of {@code EntityDummy#onUpdate} at
     * {@code setPosition(worldSeat[0], worldSeat[1], worldSeat[2])} across a teleport-sized gap: "no `pos_jump` a jump
     * of ForgeTestClient to within 3.0 of the ship's Y". Each carrier alone was not measured.</p>
     */
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

        Events events = serverEvents();
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
        String enter = exec("stellurgytest space enter " + botName() + " " + cellDim
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
                Reply.of(exec("stellurgytest player mount-entity " + dummyId)).bool("mounted"));
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
        long riderServerMark = serverEvents().markInstrumented();
        String tpY = exec("stellurgytest vs teleport-ship-by-id " + cellDim + " " + shipId
                + " " + BX + " " + EXTREME_Y + " " + BZ);
        assertTrue("teleport-ship to extreme Y must succeed: " + tpY, Reply.of(tpY).ok());
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
        String unparked = exec("stellurgytest vs unpark-by-id " + cellDim + " " + shipId);
        assertTrue("the teleport leaves the ship PARKED by VS's own recipe, and a parked ship cannot"
                + " be flown — the unpark must take: " + unparked, Reply.of(unparked).ok());
        // Both verbs above run on the server thread before they answer, so this read is of the
        // write; what still travels is the CLIENT's copy of the rider, awaited below.
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
        // The rider's arrival on THIS client is a position write far larger than any flight moves,
        // so its own `pos_jump` records it — with the Y it was written to. The link is the first
        // such write that lands him within tracking tolerance of where the server has the ship.
        final String rider = botName();
        clientEvents().awaitMatching(riderMark, "pos_jump",
                seen -> Events.records(seen).stream().anyMatch(r ->
                        rider.equals(Events.text(r, "who"))
                                && Math.abs(Events.number(r, "to") - shipYAfterTp)
                                        < RIDER_TRACKING_TOLERANCE),
                "a jump of " + rider + " to within " + RIDER_TRACKING_TOLERANCE + " of the ship's"
                        + " Y " + shipYAfterTp,
                "the CLIENT-rendered rider must be carried to the top of the pose band WITH his"
                        + " ship", RIDER_ARRIVAL_LINK_BUDGET_TICKS);
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
        long secondServerMark = serverEvents().markInstrumented();
        String tp2 = exec("stellurgytest vs teleport-ship-by-id " + cellDim + " " + shipId
                + " " + (BX + SECOND_RELOCATION_X) + " " + EXTREME_Y + " " + BZ);
        assertTrue("the second teleport must succeed: " + tp2, Reply.of(tp2).ok());
        String unparked2 = exec("stellurgytest vs unpark-by-id " + cellDim + " " + shipId);
        assertTrue("the second teleport leaves the craft PARKED, and a parked craft cannot be flown"
                + " — a red below would then be about the park, not about the physics: " + unparked2,
                Reply.of(unparked2).ok());
        // No advance: both verbs finished on the server thread, and what follows asks the server
        // first. The client's copy of the move is along X, which no record carries; the climb leg
        // below measures Y differences on either side of its own key, so it does not depend on it.

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

        exec("stellurgytest player dismount");
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
                        + "; the server's dismounts: " + serverEvents().since(serverMark, "dismount")
                        + "; the client's own disconnect: "
                        + clientEvents().since(clientMark, "client_disconnected")
                        + "; the server's logout: "
                        + serverEvents().since(serverMark, "player_logged_out")
                        + "; the server's kicks: "
                        + serverEvents().since(serverMark, "server_kicked_player"),
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
        long climbServerMark = serverEvents().mark();
        double riderYBefore = requireStillAboard("before the " + label + " climb leg is driven",
                climbClientMark, climbServerMark).get("posY").getAsDouble();
        // EXPERIMENT: a dose of thrust from the key's arrival — which is a link inside it, so a key
        // that never reached the computer is not read as a ship that would not climb — and one
        // reading of the ship once the release has arrived too.
        climbOnPilotKey(cellDim, PILOT_THRUST_DOSE_TICKS, "[" + label + "] the held vertical key must"
                + " reach the flight computer of a ship this far out");
        double yAfter = shipY();
        assertTrue("[" + label + "] " + PILOT_THRUST_DOSE_TICKS + " ticks of the vertical-up key must"
                + " lift the ship (yBefore=" + yBefore + " yAfter=" + yAfter + ")",
                yAfter - yBefore > 1.0);
        // EXPERIMENT: the comparison is DEFINED six client ticks after the cut — a rider lagging his
        // ship by more than RIDER_TRACKING_TOLERANCE at that offset is the failure. The tolerance is
        // the test's own and was not measured at this offset.
        bot().waitWorldTicks(6);
        double serverDelta = shipY() - yBefore;
        // Through the guard for the same reason as the read before the climb: a pilot who came adrift
        // DURING the leg is the most interesting way this can fail, and a bare read turns it into a
        // NullPointerException that names neither the leg nor the moment.
        double riderDelta = requireStillAboard("after the " + label + " climb leg was driven",
                climbClientMark, climbServerMark).get("posY").getAsDouble() - riderYBefore;
        // Third witness on divergence: the SERVER-side player position separates "the seat glue died
        // server-side" (server player static too) from "the client stopped tracking" (server player
        // climbed, client did not).
        String serverPlayer = exec("stellurgytest player health");
        assertTrue("[" + label + "] the CLIENT rider must track the server ship's climb (client="
                + riderDelta + " server=" + serverDelta + "); server player: " + serverPlayer,
                Math.abs(riderDelta - serverDelta) < RIDER_TRACKS_SHIP_BLOCKS);
    }

    // ─── extreme |X|: far from the origin, in the overworld ─────────────────────────────────────
    //
    // Both legs below stand at Z = FAR_ARENA_Z, below the physics mod's reserved shipyard quadrant
    // (chunkX >= CHUNK_X_START - MAX_CHUNK_RADIUS && chunkZ >= -1599, i.e. Z >= -25,584): above that Z
    // the quadrant's teleport veto swallows a far coordinate silently and a leg would measure the
    // reservation instead of the coordinate. They are millions of blocks from every plot.

    /** Below the reserved quadrant's Z edge, so the arena is ordinary world at every X. */
    private static final int FAR_ARENA_Z = -100_000;
    /** A deadline for each of a delivery's two records (the chunk, the placement) — not a settle. */
    private static final int FAR_DELIVERY_LINK_BUDGET_TICKS = 200;
    private static final double FAR_ARRIVAL_TOLERANCE = 1.0d;

    /**
     * Set server gamerules for one leg and answer what they were, so the leg can put them back: the
     * server is shared with every other scenario here.
     */
    private java.util.Map<String, String> setGamerules(String... nameThenValue) throws Exception {
        java.util.Map<String, String> before = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nameThenValue.length; i += 2) {
            String name = nameThenValue[i];
            // Vanilla answers a bare `gamerule <name>` with "<name> = <value>".
            String reply = exec("gamerule " + name).trim();
            int eq = reply.lastIndexOf(" = ");
            scenario().requireArranged("gamerule " + name + " must report its value: " + reply, eq >= 0);
            before.put(name, reply.substring(eq + 3).trim());
            exec("gamerule " + name + " " + nameThenValue[i + 1]);
        }
        return before;
    }

    private void restoreGamerules(java.util.Map<String, String> before) throws Exception {
        for (java.util.Map.Entry<String, String> rule : before.entrySet()) {
            exec("gamerule " + rule.getKey() + " " + rule.getValue());
        }
    }

    /**
     * SPIKE — where exactly a player DELIVERY to a far coordinate stops working, pinned against numbers
     * predicted from the physics mod's own predicate, so the mechanism is proven rather than inferred.
     *
     * <p>The physics mod installs a cancellable {@code @Inject} at the HEAD of
     * {@code NetHandlerPlayServer.setPlayerLocation} that cancels any teleport into its reserved
     * shipyard quadrant: the command reports success, the mixin cancels, the player never moves.
     * {@code isChunkInShipyard(cx, cz)} is {@code cx >= CHUNK_X_START - MAX_CHUNK_RADIUS && cz >=
     * CHUNK_Z_START - MAX_CHUNK_RADIUS}, so four outcomes are decided before the run: one chunk under
     * the X edge moves, the first reserved chunk does not, a coordinate deep inside does not, and the
     * same X below the quadrant's Z edge moves again. A miss on ANY of the four falsifies the
     * explanation. The edge is READ from the allocator: a test pinning a mechanism must be keyed to the
     * mechanism's own constant, or it pins the day it was written.</p>
     */
    @Test
    public void whereExactlyDoesADeliveryStopWorking() throws Exception {
        java.util.Map<String, String> rules = setGamerules(
                "sendCommandFeedback", "false", "logAdminCommands", "false");
        try {
            java.util.List<String> report = new java.util.ArrayList<>();
            java.util.List<String> wrong = new java.util.ArrayList<>();
            // The first reserved BLOCK X, straight out of the predicate the teleport is cancelled by.
            final long edgeX = ((long) (org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator.CHUNK_X_START
                    - org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator.MAX_CHUNK_RADIUS)) << 4;
            // Deep inside the quadrant, derived so it stays inside whatever the edge becomes.
            final long deepX = edgeX + 1_000_000L;
            // {x, z, expectedToMove}
            double[][] cases = {
                    {edgeX - 16 + 0.5d, 0.5d, 1d},
                    {edgeX + 0.5d, 0.5d, 0d},
                    {deepX + 0.5d, 0.5d, 0d},
                    {deepX + 0.5d, FAR_ARENA_Z + 0.5d, 1d},
            };
            for (double[] c : cases) {
                boolean expectMove = c[2] != 0d;
                String reply = exec("stellurgytest player far-tp " + farFmt(c[0]) + " 200 " + farFmt(c[1]));
                double from = Reply.of(reply).number("fromX");
                double to = Reply.of(reply).number("posX");
                boolean moved = Math.abs(to - c[0]) < FAR_ARRIVAL_TOLERANCE;
                boolean unchanged = Math.abs(to - from) < 1e-6d;
                report.add("target=(" + farFmt(c[0]) + "," + farFmt(c[1]) + ")"
                        + " chunk=(" + (((long) Math.floor(c[0])) >> 4) + "," + (((long) Math.floor(c[1])) >> 4) + ")"
                        + " predicted=" + (expectMove ? "MOVES" : "CANCELLED")
                        + " observed=" + (moved ? "MOVED" : unchanged ? "CANCELLED" : "ELSEWHERE(" + to + ")"));
                if (moved != expectMove) {
                    wrong.add(report.get(report.size() - 1));
                }
                // Park him back near the origin so the next case starts from a known place.
                exec("stellurgytest player far-tp 0.5 200 0.5");
                dev.stannismod.stellurgy.test.GameTicks.advanceWorld(serverClient(), 0, 20);
            }

            StringBuilder out = new StringBuilder("[SPIKE far-coordinate delivery boundary]\n");
            for (String line : report) {
                out.append("  ").append(line).append('\n');
            }
            System.out.println(out);
            writeSpikeReport("far-coordinate-delivery-boundary.txt", out.toString());
            assertTrue("the reserved-quadrant explanation predicts these four outcomes; it missed:\n" + out,
                    wrong.isEmpty());
        } finally {
            restoreGamerules(rules);
        }
    }

    /**
     * SPIKE — does a tier-2 ship survive a far world COORDINATE the way a bare player does? The
     * extreme-|X| leg the class javadoc names as open.
     *
     * <p>A ship's blocks live in the shipyard subspace while its pose lives in the world, bridged by a
     * transform of its own, so a player measured clean out to 24M says nothing about a ship. The ship
     * is ASSEMBLED at the coordinate rather than teleported to it: relocation sequences have findings
     * of their own (above), and teleporting to |X| would run into them and produce a red that says
     * nothing about the coordinate. The one relocation in the leg is the player's, through
     * {@code far-tp}.</p>
     *
     * <p>Acceptance, stated before the run — the {@code x = 0} rung is the control, assembled and flown
     * by the same commands; at every rung assembly produces a VS ship that LOADS, the pilot seat is
     * findable and mountable, a real held vertical-up key lifts the server ship by more than one block,
     * and the CLIENT-rendered rider tracks that climb within 3 blocks.</p>
     *
     * <p>red-witnessed: with {@code TileAdvancedFlightComputer#setPilotInput} at
     * {@code this.pilotInput = input} discarding every input in the overworld: "the x=0 control failed - the instrument, not the coordinate: the vertical-up
     * key did not lift the ship (serverLift=0.0000 …)", 2026-09-28. The waits each turn an expiry into
     * that RUNG's verdict (spawn, id, load, riding), and the control assertion is where any of them at
     * x=0 surfaces — the path this red went through.</p>
     */
    @Test
    public void doesAShipAssembleLoadAndFlyFarFromTheOrigin() throws Exception {
        // The control, then the ratified half-cell. 24M is not carried: one far rung is the question.
        final int[] xLadder = {0, 16_000_000};
        java.util.Map<String, String> rules = setGamerules(
                "sendCommandFeedback", "false", "logAdminCommands", "false", "doMobSpawning", "false",
                "doDaylightCycle", "false", "doWeatherCycle", "false");

        java.util.Map<Integer, String> verdicts = new java.util.LinkedHashMap<>();
        // Which ship answered for which rung: two rungs reporting one id measured one subject twice.
        java.util.Map<Integer, String> shipIds = new java.util.LinkedHashMap<>();
        java.util.List<String> report = new java.util.ArrayList<>();
        java.util.List<String> inconclusive = new java.util.ArrayList<>();
        StringBuilder out;
        try {
            for (int x : xLadder) {
                String arrangement = arrangeFarRung(x);
                if (arrangement != null) {
                    inconclusive.add("x=" + x + " " + arrangement);
                    continue;
                }
                Events rungLog = serverEvents();
                long spawnMark = rungLog.markInstrumented();
                String assemble = assembleFarFixture(x);
                if (assemble == null) {
                    inconclusive.add("x=" + x + " the fixture did not build or did not assemble"
                            + " (arrangement, not the coordinate)");
                    continue;
                }
                // absence is the answer: this SWEEPS coordinates and records a verdict per one, so a
                // probe that answered nothing is this row's failure and not the end of the sweep.
                if (!(Reply.of(assemble).integerOr("rocketCount", Integer.MIN_VALUE) == 0)) {
                    verdicts.put(x, "the build did not route to a SHIP: " + farOneLine(assemble));
                    continue;
                }
                String durableName = dev.stannismod.stellurgy.test.ShipIdentity.nameFromAssembly(assemble);
                try {
                    rungLog.awaitField(spawnMark, "ship_spawned", "stellurgyShip", durableName,
                            "the rung's assembly must spawn a VS ship", 200);
                } catch (AssertionError noSpawn) {
                    verdicts.put(x, "assembly created no VS ship: " + farOneLine(noSpawn.getMessage()));
                    continue;
                }

                // Put the pilot on the ship — the ONLY relocation in the leg. The mark precedes it:
                // his arrival is what loads the ship.
                long loadMark = rungLog.markInstrumented();
                String delivery = deliverToFarRung(x);
                if (delivery != null) {
                    inconclusive.add("x=" + x + " " + delivery);
                    continue;
                }
                String farShipId = dev.stannismod.stellurgy.test.ShipIdentity.awaitPhysicsIdOf(
                        this::exec, rungLog, 0, durableName, 200);
                try {
                    rungLog.awaitMatching(loadMark, "ship_usable",
                            usable -> dev.stannismod.stellurgy.test.ShipIdentity.endsUsable(usable,
                                    rungLog.since(loadMark, "ship_unloaded"), farShipId, 0),
                            "carrying ship " + farShipId + " in dim 0, later than every unload of it",
                            "the rung's ship must LOAD with the client present", 200);
                } catch (AssertionError neverLoaded) {
                    verdicts.put(x, "the ship never LOADED with the client present: "
                            + farOneLine(neverLoaded.getMessage()));
                    continue;
                }
                String lastInfo = exec("stellurgytest vs ship-info 0 id " + farShipId);
                if (!ShipInfo.isLoaded(lastInfo)) {
                    verdicts.put(x, "the ship was usable and then not loaded at the next read: "
                            + farOneLine(lastInfo));
                    continue;
                }
                double y0 = ShipInfo.of(lastInfo).y;
                if (shipIds.containsValue(farShipId)) {
                    verdicts.put(x, "this rung's ship is the SAME ship a previous rung measured (id "
                            + farShipId + ") - the ladder is measuring one subject twice");
                    continue;
                }
                shipIds.put(x, farShipId);

                // NAME the ship: the bare form takes the first loaded pilot seat, and at 16M it mounted
                // the pilot onto the ORIGIN ship's seat.
                SeatMount mountInfo = SeatMount.onShip(this::exec, 0, farShipId);
                if (!mountInfo.seatFound) {
                    verdicts.put(x, "the pilot seat was not findable: " + farOneLine(mountInfo.raw()));
                    continue;
                }
                long mountMark = clientEvents().mark();
                String mounted = exec("stellurgytest player mount-entity " + mountInfo.requireDummyId());
                // absence is the answer, as above: one row's verdict, not the sweep's end.
                if (!Reply.of(mounted).boolOr("mounted", false)) {
                    verdicts.put(x, "the bot could not mount the seat dummy: " + farOneLine(mounted));
                    continue;
                }
                // "mounted":true is the SERVER's word; the climb measures the CLIENT-rendered rider.
                String riding = awaitFarRiding(mountInfo.requireDummyId(), mountMark);
                if (riding != null) {
                    verdicts.put(x, riding + " (server said " + farOneLine(mounted) + ")");
                    continue;
                }

                String flight = farClimbLeg(farShipId, y0);
                // The seat's own position is a SUBSPACE coordinate — the magnitude the ship's own math
                // runs on, the only number that changes if the shipyard moves.
                report.add("x=" + x + " ship=" + farShipId + " shipY0=" + farFmt(y0)
                        + " subspaceSeatX=" + farFmt((double) mountInfo.seatX())
                        + " subspaceSeatZ=" + farFmt((double) mountInfo.seatZ())
                        + " " + flight);
                verdicts.put(x, flight.startsWith("OK") ? null : flight);

                exec("stellurgytest player dismount");
                advanceServerAndClient(10);
            }
        } finally {
            // The report is the deliverable and worth MOST when the leg died mid-ladder, so it is
            // emitted before anything can escape.
            for (java.util.Map.Entry<Integer, String> e : verdicts.entrySet()) {
                if (e.getValue() != null) {
                    report.add("x=" + e.getKey() + " FAILED " + e.getValue());
                }
            }
            StringBuilder built = new StringBuilder("[SPIKE far-coordinate VS ship]\n");
            for (String line : report) {
                built.append("  ").append(line).append('\n');
            }
            for (String line : inconclusive) {
                built.append("  INCONCLUSIVE ").append(line).append('\n');
            }
            for (int x : xLadder) {
                boolean listed = false;
                for (String line : inconclusive) {
                    listed |= line.startsWith("x=" + x + " ");
                }
                if (!verdicts.containsKey(x) && !listed) {
                    built.append("  NOT REACHED x=").append(x).append('\n');
                }
            }
            System.out.println(built);
            writeSpikeReport("far-coordinate-ship.txt", built.toString());
            out = built;
            try {
                exec("stellurgytest player dismount");
            } catch (Exception ignored) {
                // teardown must not mask the finding
            }
            restoreGamerules(rules);
        }

        // The control first and separately: a ship that will not fly at the ORIGIN makes every far
        // reading meaningless, and that is an instrument failure, not a coordinate ceiling.
        assertTrue("the x=0 control produced no measurement at all, so no far rung is evidence:\n" + out,
                verdicts.containsKey(0));
        assertTrue("the x=0 control failed - the instrument, not the coordinate: " + verdicts.get(0)
                + "\n" + out, verdicts.get(0) == null);
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, String> e : verdicts.entrySet()) {
            if (e.getKey() != 0 && e.getValue() != null) {
                failed.add("x=" + e.getKey() + ": " + e.getValue());
            }
        }
        assertTrue("a ship does not behave at a far coordinate as it does at the origin: " + failed
                + "\n" + out, failed.isEmpty());
        assertTrue("no far rung was measured at all - the leg answered nothing:\n" + out, verdicts.size() > 1);
    }

    /**
     * Waits until the CLIENT reports it is riding, and — if it never does — asks the three questions
     * that decide WHICH thing failed: where the client thinks the player is, what entities it sees near
     * him, and where the server holds the dummy.
     *
     * @return {@code null} once the client is riding, else the reason plus that diagnosis
     */
    private String awaitFarRiding(int dummyId, long clientMark) throws Exception {
        final int ridingTicks = 120;
        com.google.gson.JsonObject last;
        try {
            ClientEvents.awaitMounted(clientEvents(), clientMark, "the client must begin riding the seat dummy",
                    ridingTicks);
            last = bot().reportRidingEntity();
            if (last.has("riding") && last.get("riding").getAsBoolean() && last.has("posY")) {
                return null;
            }
        } catch (AssertionError never) {
            last = bot().reportRidingEntity();
        }
        String clientState;
        String clientEntities;
        try {
            clientState = String.valueOf(bot().reportState());
            clientEntities = String.valueOf(bot().reportEntities("", 128d));
        } catch (Exception e) {
            clientState = "unreadable: " + e;
            clientEntities = "unreadable";
        }
        return "the CLIENT never began riding the seat after " + ridingTicks + " ticks (last report: " + last + ")"
                + " | client state: " + farOneLine(clientState)
                + " | client sees near him: " + farOneLine(clientEntities)
                + " | server holds the dummy at: " + farOneLine(exec("stellurgytest entity info 0 " + dummyId));
    }

    /**
     * Hold the REAL vertical-up key: the SERVER ship must climb and the CLIENT-rendered rider climb
     * with it. A transform that lost precision shows up as divergence between those two.
     *
     * @return {@code "OK ..."} with the numbers, or the reason it failed
     */
    private String farClimbLeg(String farShipId, double yBefore) throws Exception {
        double riderYBefore = bot().reportRidingEntity().get("posY").getAsDouble();
        PilotThrust.climb(bot(), serverEvents(), serverClient(), 0, PilotThrust.DOSE_TICKS,
                "the spike pilot's held vertical key must reach his flight computer");
        // EXPERIMENT: the comparison is DEFINED six client ticks after the cut. The tolerance is the
        // spike's own and was not measured at this offset.
        bot().waitWorldTicks(6);
        double serverDelta = farShipY(farShipId) - yBefore;
        double riderDelta = bot().reportRidingEntity().get("posY").getAsDouble() - riderYBefore;
        String numbers = "serverLift=" + farFmt(serverDelta) + " riderLift=" + farFmt(riderDelta)
                + " divergence=" + farFmt(Math.abs(riderDelta - serverDelta));
        if (!(serverDelta > 1.0d)) {
            // A third witness separates "the seat glue died" from "the ship would not move".
            return "the vertical-up key did not lift the ship (" + numbers + "); server player: "
                    + farOneLine(exec("stellurgytest player health"));
        }
        if (Math.abs(riderDelta - serverDelta) >= 3.0d) {
            return "the CLIENT rider did not track the server ship (" + numbers + ")";
        }
        return "OK " + numbers;
    }

    /** @return {@code null} once the rung's site is loaded and clear, else what is wrong with it */
    private String arrangeFarRung(int x) throws Exception {
        final int baseY = FixtureSite.OPEN_AIR_Y;
        int cx1 = (x - 32) >> 4, cz1 = (FAR_ARENA_Z - 32) >> 4;
        int cx2 = (x + 32) >> 4, cz2 = (FAR_ARENA_Z + 32) >> 4;
        String warm = exec("stellurgytest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        if (!Reply.of(warm).ok()) {
            return "chunk warmup failed: " + farOneLine(warm);
        }
        // A stone pad below and air above: 16M is ocean, and the fixture must not be built into water.
        exec("stellurgytest fill 0 " + (x - 8) + " " + (baseY - 1) + " " + (FAR_ARENA_Z - 8) + " "
                + (x + 12) + " " + (baseY - 1) + " " + (FAR_ARENA_Z + 12) + " minecraft:stone");
        String clear = exec("stellurgytest fill 0 " + (x - 8) + " " + baseY + " " + (FAR_ARENA_Z - 8) + " "
                + (x + 12) + " " + (baseY + 14) + " " + (FAR_ARENA_Z + 12) + " minecraft:air");
        if (!Reply.of(clear).ok()) {
            return "pre-clear failed: " + farOneLine(clear);
        }
        String pad = exec("stellurgytest block at 0 " + x + " " + (baseY - 1) + " " + FAR_ARENA_Z);
        // The id, compared — `contains("stone")` also accepts cobblestone and sandstone. Read bare: the
        // world was loaded a line above, and on that branch the producer always writes `block`.
        if (!"minecraft:stone".equals(Reply.of(pad).text("block"))) {
            return "the pad is not stone (" + farOneLine(pad) + ")";
        }
        return null;
    }

    /**
     * DELIBERATELY NOT ON THE SHARED BUILDER: {@code RocketFixture} raises an arrangement failure when a
     * fixture will not lay, and this leg's subject is WHERE the build stops working, so a refusal is the
     * measurement and is recorded and walked past.
     *
     * @return the assemble reply, or {@code null} if the fixture itself never landed
     */
    private String assembleFarFixture(int x) throws Exception {
        String fixture = exec("stellurgytest fixture rocket 0 " + x + " " + FixtureSite.OPEN_AIR_Y + " "
                + FAR_ARENA_Z + " " + VARIANT);
        if (!Reply.of(fixture).ok()) {
            System.out.println("[SPIKE ship] fixture at x=" + x + " failed: " + farOneLine(fixture));
            return null;
        }
        int[] bp = Reply.of(fixture).blockPos("builderPos");
        if (bp == null) {
            System.out.println("[SPIKE ship] fixture at x=" + x + " gave no builderPos: " + farOneLine(fixture));
            return null;
        }
        return exec("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
    }

    /**
     * Puts the pilot on the far rung's ship through the long-jump path: the chunk's arrival and the
     * placement are the two named steps of {@link ClientEvents#placeOntoGroundItHolds}.
     *
     * @return {@code null} once he is there, or a reason string for the INCONCLUSIVE list
     */
    private String deliverToFarRung(int x) throws Exception {
        final int deliveryY = FixtureSite.OPEN_AIR_Y + 6;
        try {
            ClientEvents.placeOntoGroundItHolds(bot(), clientEvents(), this::exec,
                    "stellurgytest player far-tp " + farFmt(x + 0.5d) + " " + deliveryY + " "
                            + farFmt(FAR_ARENA_Z + 0.5d),
                    x + 0.5d, deliveryY, FAR_ARENA_Z + 0.5d,
                    "the pilot must be delivered onto the rung's ship at x=" + x, FAR_DELIVERY_LINK_BUDGET_TICKS);
        } catch (AssertionError notPlaced) {
            return "the pilot was never placed at x=" + x + " - delivery, not the ship: "
                    + farOneLine(notPlaced.getMessage());
        }
        // absence is the answer: a sweep row with no server position is that row's inconclusive.
        double lastX = Reply.of(exec("stellurgytest player health")).numberOr("posX", Double.NaN);
        if (Math.abs(lastX - (x + 0.5d)) < FAR_ARRIVAL_TOLERANCE) {
            return null;
        }
        return "the pilot was placed and the server does not hold him there (server posX=" + lastX
                + ", wanted " + (x + 0.5d) + ") - delivery, not the ship";
    }

    /**
     * The far rung's ship's {@code posY}, BY ID — ONE read. By id, not by position: on a ladder of two
     * ships 16M apart a nearest-ship lookup would answer a rung whose ship unloaded with the OTHER
     * rung's ship, which looks exactly like a clean far-coordinate result.
     */
    private double farShipY(String farShipId) throws Exception {
        String last = exec("stellurgytest vs ship-info 0 id " + farShipId);
        if (ShipInfo.isLoaded(last)) {
            double py = ShipInfo.of(last).y;
            if (!Double.isNaN(py)) {
                return py;
            }
        }
        throw new AssertionError("the loaded ship did not report a posY at this read: " + last);
    }

    /** The report is the deliverable, so it also lands on disk and survives a truncated console. */
    private static void writeSpikeReport(String name, String text) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("build", "spike-reports").toAbsolutePath();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(name), text.getBytes("UTF-8"));
        } catch (Exception e) {
            System.out.println("[SPIKE] could not write the report file: " + e);
        }
    }

    private static String farOneLine(String s) {
        return s.replace((char) 10, ' ').replace((char) 13, ' ').trim();
    }

    private static String farFmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
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
     * The server ship's posY — ONE read.
     *
     * <p>Every caller asks about a ship the scenario has just read as loaded (the usable link at
     * assembly, then the loaded reads after each teleport), with its pilot aboard. A ship that has
     * UNLOADED since answers {@code managed:false} and carries no {@code posY}, and that is reported
     * here as what it is — the craft went away mid-measurement — rather than retried until a reply
     * looks better. "This ship is not loaded" is a different fact from "the ship near this point
     * moved", and the positional form this replaced could not tell them apart.</p>
     */
    private double shipY() throws Exception {
        String last = shipInfoById();
        if (ShipInfo.isLoaded(last)) {
            double y = ShipInfo.of(last).y;
            if (!Double.isNaN(y)) {
                return y;
            }
        }
        throw new AssertionError("ship " + shipId + " was not loaded with a posY at this read: " + last);
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
