package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;

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
 * The extreme-|X| precision leg stays an open follow-up until (3) is settled.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipExtremeCoordinatesE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-ship-extreme-coordinates";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern ORIGIN_DIM = Pattern.compile("\"originDim\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_WORLD_X = Pattern.compile("\"shipWorldX\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_WORLD_Y = Pattern.compile("\"shipWorldY\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_WORLD_Z = Pattern.compile("\"shipWorldZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 3400, BY = 64, BZ = 3400;

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
        String setup = exec("artest space transit-setup-empty");
        scenario().requireArranged("the empty transit setup must succeed, or there is no cell to fly"
                + " in: " + setup, setup.contains("\"ok\":true"));
        cellDim = (int) readDouble(setup, ORIGIN_DIM);

        // Held loaded BEFORE the craft exists, and this ordering is load-bearing. A craft assembled
        // in a cell has no player anywhere near it — the pilot cannot enter until his seat is found,
        // and the seat cannot be found until the craft is built — so the substrate's load controller
        // drops its physics object inside that very window. Measured on this scenario's first run in
        // a cell: the log read `ship_spawned`, `ship_loaded`, `ship_unloaded`, and the craft never
        // became usable at all.
        assertTrue("the craft must be held loaded across the window where nobody is near it, or the"
                + " substrate drops it before it can ever be flown",
                exec("artest vs permaload true").contains("\"ok\":true"));

        Events events = events();
        long assemblyMark = events.markInstrumented();
        String assemble = assembleFixture(cellDim, BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
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
        String seat = exec("artest vs find-seat " + cellDim + " id " + shipId);
        scenario().requireArranged("the pilot seat must be found in the assembled craft: " + seat,
                seat.contains("\"seatFound\":true"));
        String enter = exec("artest space enter " + botName() + " " + cellDim
                + " " + (int) Math.round(readDouble(seat, SHIP_WORLD_X))
                + " " + (int) Math.round(readDouble(seat, SHIP_WORLD_Y))
                + " " + (int) Math.round(readDouble(seat, SHIP_WORLD_Z)));
        scenario().requireArranged("space enter into the origin cell must succeed: " + enter,
                enter.contains("\"ok\":true"));
        bot().waitTicks(20);
        scenario().requireArranged("the client must have followed into the origin cell (dim "
                        + cellDim + ")",
                bot().reportWeather().get("dim").getAsInt() == cellDim);

        String mountInfo = exec("artest vs seat-mount " + cellDim + " id " + shipId);
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy",
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
        bot().waitTicks(10);

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
        assertTrue("teleport-ship to extreme Y must succeed: " + tpY, tpY.contains("\"ok\":true"));
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
                stayed.contains("\"managed\":true"));
        String unparked = exec("artest vs unpark-by-id " + cellDim + " " + shipId);
        assertTrue("the teleport leaves the ship PARKED by VS's own recipe, and a parked ship cannot"
                + " be flown — the unpark must take: " + unparked, unparked.contains("\"ok\":true"));
        bot().waitTicks(10);
        String serverInfoAfterTp = shipInfoById();
        scenario().requireArranged("the teleported ship must still be loaded, or there is no server "
                        + "pose for the rider to be compared against: " + serverInfoAfterTp,
                serverInfoAfterTp.contains("\"managed\":true"));

        // THE CONTRACT, and it names no coordinate: a rider is glued to his ship, so wherever the
        // ship ends up the client must render him THERE. Asserting he reached a particular altitude
        // instead would pin the arrangement's own request — and did: the old form compared him to a
        // hard-coded destination, so it could fail either because the rider came adrift or because
        // the ship never went where it was sent, and the message could not tell the two apart.
        double shipYAfterTp = readDouble(serverInfoAfterTp, POS_Y);
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
        com.google.gson.JsonObject ridingAfterTp = bot().reportRidingEntity();
        assertTrue("the pilot must still be ABOARD after his craft is rigid-teleported to the top of"
                        + " the pose band — riders are carried by the move, so a client that is not"
                        + " riding here is the finding, not a detail. client=" + ridingAfterTp
                        + "; the client's own mounts across the teleport: "
                        + clientEvents().since(riderMark, "mount")
                        + "; its dismounts: " + clientEvents().since(riderMark, "dismount")
                        + "; the server's dismounts: " + events().since(riderServerMark, "dismount")
                        // The first run of this assertion answered the question it was asked and
                        // then posed a bigger one: the server's dismount carried the caller trail
                        // `PlayerList.playerLoggedOut`, at y=3.19993e+07. The rider did not come
                        // adrift — the CONNECTION went, and the un-seating is what a logout does on
                        // the way out. So the two records that say WHY are read here too.
                        + "; the client's own disconnect: "
                        + clientEvents().since(riderMark, "client_disconnected")
                        + "; the server's logout: "
                        + events().since(riderServerMark, "player_logged_out")
                        // Who threw him out, and for what. This type ABSENT while he is gone says
                        // the server did not kick him at all, which is a different failure from any
                        // kick — and the vanilla kick to expect here is `multiplayer.disconnect.
                        // flying`, armed against every seated pilot on a craft whose deck lives in
                        // subspace (see the harness's `allow-flight`).
                        + "; the server's kicks: "
                        + events().since(riderServerMark, "server_kicked_player"),
                ridingAfterTp.has("posY"));
        double riderY = ridingAfterTp.get("posY").getAsDouble();
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
                Math.abs(shipYAfterTp - EXTREME_Y) < 200);
        climbLeg("extreme Y");

        // The extreme-|X| leg is NOT automated yet — see the class javadoc: after a SECOND
        // relocation the ship's physics goes inert (neither the pilot key nor the push-ship
        // velocity setpoint moves it) and the pilot-key path dies after a dismount->re-seat across
        // the map. Both are relocation-sequence findings, not coordinate-regime ones; the XZ
        // precision leg stays an open follow-up until they are resolved.

        exec("artest player dismount");
        exec("artest vs permaload false");
    }

    /**
     * One controllability measurement at the ship's current location: hold the REAL vertical-up key,
     * the server ship must climb, and the CLIENT-rendered rider must climb WITH it (tracking within
     * the same tolerance the ordinary-coordinates pilot e2e uses — a precision breakdown at extreme
     * coordinates shows up here as divergence).
     */
    private void climbLeg(String label) throws Exception {
        double yBefore = shipY();
        double riderYBefore = bot().reportRidingEntity().get("posY").getAsDouble();
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
        double riderDelta = bot().reportRidingEntity().get("posY").getAsDouble() - riderYBefore;
        // Third witness on divergence: the SERVER-side player position separates "the seat glue died
        // server-side" (server player static too) from "the client stopped tracking" (server player
        // climbed, client did not).
        String serverPlayer = exec("artest player health");
        assertTrue("[" + label + "] the CLIENT rider must track the server ship's climb (client="
                + riderDelta + " server=" + serverDelta + "); server player: " + serverPlayer,
                Math.abs(riderDelta - serverDelta) < 3.0);
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
     * The server ship's posY, tolerant of unrelated console lines interleaving with the probe's JSON
     * reply (at extreme coordinates a VS collision mixin spams STDERR lines, which can arrive inside
     * the captured console window) — retry until a parseable reply comes back.
     *
     * <p>A ship that has UNLOADED answers {@code managed:false} and carries no {@code posY}, so it
     * exhausts the retries and fails naming the reply. That is the intended report: "this ship is
     * not loaded" is a different fact from "the ship near this point moved", and the positional form
     * this replaced could not tell them apart.</p>
     */
    private double shipY() throws Exception {
        String last = "";
        for (int i = 0; i < 10; i++) {
            last = shipInfoById();
            Matcher m = POS_Y.matcher(last);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
            bot().waitTicks(2);
        }
        throw new AssertionError("ship-info never returned a parseable posY for ship " + shipId
                + "; last reply: " + last);
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private String assembleFixture(int dim, int baseX, int baseY, int baseZ, String variant)
            throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup " + dim + " " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill " + dim + " " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket " + dim + " " + baseX + " " + baseY + " "
                + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble " + dim + " " + bp.group(1) + " " + bp.group(2)
                + " " + bp.group(3));
    }
}
