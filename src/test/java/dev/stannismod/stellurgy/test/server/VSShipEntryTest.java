package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.CellWorldMapper;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.awaitEnteredSpace;
import static org.junit.Assert.*;

/**
 * E2E: does the tier-2 ENTRY ON-RAMP take a piloted ship from a planet dimension into space through the
 * REAL gameplay path? A {@code with-pilot-seat} ship is assembled in the overworld, the entry stack is
 * installed, and a pilot presence + a climb PAST the dimension's orbit ceiling are arranged. The
 * <b>flight computer's own server tick</b> then detects the crossing and calls
 * {@code SpaceSubsystem.entry().requestEntry()} — production code, not the probe — which materializes the
 * launch body's cell, crosses the ship into it, and settles it in the {@code ShipLedger}.
 *
 * <p>Witnesses: the ship becomes ledgered as {@code SETTLED} at the SAME cell the production launch-coord
 * resolver answers for the launch dimension (gen-agnostic — no pinned coordinates), and its settled cell
 * world is live. CONTROL: {@code entry-status} reports zero ledgered ships before the climb, proving a
 * later "settled" is a real observation. This is the "real gameplay path calls materialize" acceptance of
 * the entry design; it composes the proven per-ship crossing with the entry state machine (pinned
 * deterministically by {@code ShipEntryControllerTest}).</p>
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSShipEntryTest extends AbstractSharedServerTest {


    /**
     * How much WORLD an async crossing is allowed in order to finish settling, in server ticks.
     * <p>
     * Thirty seconds of game time. Deliberately NOT scaled by the build's fork count: the number of
     * forks says how much of the machine this test is sharing, and the crossing does not care — it
     * needs a certain number of controller ticks and gets them whenever the server runs them. A
     * budget in seconds DID care, which is why this used to carry that multiplier and still turned
     * red under load.
     */
    private static final int SETTLE_TICKS = 600;

    /** The same, for waiting on a ship to become loadable in its slot. Ten seconds of game time. */

    /**
     * How long the arrived ship's address is watched for drift, and how far apart the readings are.
     *
     * <p>In ticks of the ship's OWN SLOT WORLD, not of the server: what drifts is driven by the
     * ship's flight computer, which ticks in that world, so those are the ticks the window has to be
     * measured in. Sampling on a wall clock would quietly shrink the window on a busy machine and let
     * a drift through unseen — and a server-clock window would run its whole length even if the slot
     * world had stopped ticking, reporting stability about a ship nothing ever asked to move.</p>
     */
    private static final int DRIFT_SAMPLES = 8;
    private static final int DRIFT_TICKS_BETWEEN_SAMPLES = 5;

    /** Where the piloted ship is built (a loaded overworld region, well clear of other tests). */
    private static final int SRC_X = 6000, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 6000;
    /** The jump leg builds its own ship, well clear of the entry leg's region (shared server, both run). */
    private static final int JUMP_SRC_X = 6400, JUMP_SRC_Z = 6400;
    /**
     * A cell key is {@code sx_sy_sz}. The jump target is derived from the ORIGIN, one sector over: the
     * integrator steps {@code speed} blocks per tick, so the time in hyperspace is distance/speed, and a
     * sector is enormous. An absolute far-away target (first attempt: sector 9001 from an origin at 25)
     * leaves the ship legitimately IN_TRANSIT for thousands of ticks and the test reds on its own poll
     * window while production is working correctly.
     */

    /**
     * A world's takeoff line is its BODY's atmosphere unless its planet file states one.
     *
     * <p>This fails if production breaks the contract that <b>{@code DimensionProperties#orbitLine}
     * answers the planet file's {@code <orbitHeight>} when there is one and otherwise the line the
     * body's radius puts there</b> — the joint between the registered world and
     * {@code DescentShell#orbitLineWorldY}, whose own law is pinned by {@code DescentShellTest}. The
     * overworld is read as the server holds it (its radius from the reply, never assumed), then given a
     * stated line, then cleared again so the body's own applies.</p>
     */
    @LegacyWitness(prose = "2026-10-05, with {@code DimensionProperties#orbitLine} at {@code return OptionalInt.of(orbitHeight);}"
            + " replaced by the body's line (the stated line ignored), this fails with \"a stated line must be"
            + " the line the world answers\".")
    @Test
    public void aWorldsTakeoffLineIsItsBodysAtmosphereUnlessItsFileStatesOne() throws Exception {
        OrbitLine asHeld = OrbitLine.of(c -> read(c).raw(), 0);
        requireArranged("the overworld must not carry a stated line before this scenario: " + asHeld,
                !asHeld.stated);
        double radius = asHeld.radius();
        requireArranged("the overworld must be a body with a radius: " + asHeld, radius > 0d);
        assertEquals("with no stated line, the world's line must be its body's atmosphere: " + asHeld,
                dev.stannismod.stellurgy.space.DescentShell.orbitLineWorldY(radius), asHeld.line());

        // A line well clear of the body's own, so the two answers cannot coincide.
        int stated = asHeld.line() / 2;
        try {
            assertEquals("a stated line must be the line the world answers",
                    stated, OrbitLine.state(c -> arrange(c).raw(), 0, stated).line());
        } finally {
            OrbitLine cleared = OrbitLine.unstate(c -> arrange(c).raw(), 0);
            assertEquals("and clearing it must give the body's own line back: " + cleared,
                    asHeld.line(), cleared.line());
        }
    }

    @Test
    public void aPilotedShipClimbingPastTheCeilingEntersSpaceViaTheFlightComputerTick() throws Exception {

        // Headless: pin ships loaded so a freshly assembled/crossed ship does not auto-unload between calls.
        // Install the entry stack into SpaceSubsystem so the PRODUCTION trigger path runs under the harness.
        String setup = arrange("stellurgytest space entry-setup 2").raw();
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        // Build a piloted tier-2 ship in the overworld and assemble it into a VS ship.
        ShipBirth birth = build(SRC_X, SRC_Z);

        // CONTROL: this craft is not ledgered before the climb — a later "settled" is then a real
        // observation.
        EntryStatus control = EntryStatus.forShip(c -> read(c).raw(), birth.durable);
        assertTrue("witness sensitivity control — this craft must not be ledgered before the climb: "
                + control, !control.found);

        // The cell the production resolver answers for the launch dimension — the entry MUST land here.
        String launch = read("stellurgytest space launch-cell 0").raw();
        assertTrue("launch-cell resolve failed: " + launch, Reply.of(launch).ok());
        String expectedCell = extractString(launch, "cellKey");
        assertTrue("launch dim resolved to no cell: " + launch, expectedCell != null);

        // NAME the ship, then arrange the entry preconditions: a pilot (the static FF input channel makes
        // the AFC tick see "someone is flying") and a climb PAST the ceiling (a rigid teleport above the
        // line the server reports for the overworld).
        // The name comes from the assembler, which minted it — not from asking the shared overworld what
        // stands near the pad, which answers with a neighbouring scenario's craft just as readily.
        String durableId = birth.durable;
        String shipId = ArrangementFailure.arranged(
                () -> birth.awaitUsable(c -> read(c).raw(), events, USABLE_TICKS));

        ShipInfo src = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        double sx = src.x, sy = src.y, sz = src.z;

        // A held throttle on THIS ship's own flight computer => a pilot is flying. Addressed by ship,
        // and the resolution is asserted: an input that reached nothing would leave the climb below
        // reading as unpiloted while claiming to be the piloted leg.
        String heldInput = arrange("stellurgytest vs ff-input-by-id 0 " + shipId + " 0 1 0 0 0 0").raw();
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                Reply.of(heldInput).bool("afcResolved"));
        String tp = arrange("stellurgytest vs teleport-ship-by-id 0 " + shipId + " "
                + (int) sx + " " + OrbitLine.of(c -> read(c).raw(), 0).aboveEntryCeiling() + " " + (int) sz).raw();
        assertTrue("climb teleport failed: " + tp, Reply.of(tp).ok());
        // Marked BEFORE the unpark, which is the act that lets the entry start: the arrival is
        // announced once and a mark taken after it would be waiting for a second one.
        long entryMark = events.mark();
        arrange("stellurgytest vs unpark-by-id 0 " + shipId);

        // Linked on the record production publishes at the settle — `ShipEntryController` posts
        // LeftPlanet on the line after `ledger.settle(...)`. The ledger poll this replaced asked the
        // same question one reading at a time, so its verdict moved with how often the box let it
        // look; the record is written by the subject, at the instant it happens.
        awaitEnteredSpace(events, entryMark, durableId,
                "the flight-computer tick must carry this craft out of the atmosphere", SETTLE_TICKS,
                null);
        EntryStatus status = EntryStatus.forShip(c -> read(c).raw(), durableId).requireFound(
                "the arrival was announced, so the ledger must hold this craft's row");

        // The entry landed in the launch body's OWN cell — the C-1 resolution, matched gen-agnostically.
        assertEquals("entry settled in a different cell than the launch resolver answers", expectedCell,
                status.cellKey);
        int slotDim = status.slotDim;
        assertTrue("settled slot dim not reported: " + status, slotDim > Integer.MIN_VALUE);
        // The settled craft's cell world is live in its slot: linked on THIS craft becoming usable there.
        ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events, slotDim, durableId, USABLE_TICKS);
    }

    /**
     * The leg AFTER the on-ramp: a ship that reached space through the production entry path can then JUMP
     * to another cell on the SAME live stack, driven only by the server tick.
     *
     * <p>Why this is not already covered: {@code VSShipTransitTest} proves the transit state machine
     * on a FIXTURE cell pair of its own — hard-coded origin and target it never flew to by entering
     * space. Nothing joined the two halves, so a ship that actually FLEW into space had never been
     * jumped. The join is exactly where the previous hands-on tier-2 session found its blockers.</p>
     *
     * <p><i>This said that stack was ISOLATED — "its own {@code SpaceManager}", "advanced by manual
     * {@code transit-tick} calls" — until 2026-09-08, when the fixture moved onto the server's own
     * subsystem and the manual driving went with it. Both halves of that sentence had become false,
     * and a reason a test exists is the worst place to leave a stale one.</i></p>
     *
     * <p>The contract asserted here is the join, not the transit internals: a ship SETTLED by the entry path
     * departs its cell when a jump begins, and settles in the requested target cell without anything pumping
     * the manager by hand. CONTROL: the pre-jump cell is read from the ledger and asserted DIFFERENT from
     * the target, so "settled at the target" cannot pass by never having moved.</p>
     */
    @Test
    public void aShipThatEnteredSpaceCanJumpToAnotherCellOnTheLiveStack() throws Exception {

        String setup = arrange("stellurgytest space entry-setup 2").raw();
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        ShipBirth birth = build(JUMP_SRC_X, JUMP_SRC_Z);
        String durableId = birth.durable;
        String shipId = ArrangementFailure.arranged(
                () -> birth.awaitUsable(c -> read(c).raw(), events, USABLE_TICKS));

        ShipInfo src = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        double sx = src.x, sy = src.y, sz = src.z;
        String heldInput = arrange("stellurgytest vs ff-input-by-id 0 " + shipId + " 0 1 0 0 0 0").raw();
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                Reply.of(heldInput).bool("afcResolved"));
        assertTrue("climb teleport failed", Reply.of(arrange("stellurgytest vs teleport-ship-by-id 0 " + shipId + " "
                + (int) sx + " " + OrbitLine.of(c -> read(c).raw(), 0).aboveEntryCeiling() + " " + (int) sz).raw()).ok());
        long entryMark = events.mark();
        arrange("stellurgytest vs unpark-by-id 0 " + shipId);

        awaitEnteredSpace(events, entryMark, durableId,
                "precondition: the ship must enter space, or there is nothing to jump", SETTLE_TICKS,
                null);
        EntryStatus status = EntryStatus.forShip(c -> read(c).raw(), durableId).requireFound(
                "the arrival was announced, so the ledger must hold this craft's row");
        int slotDim = status.slotDim;
        String originCell = status.cellKey;
        ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events, slotDim,
                durableId, USABLE_TICKS));

        // Jump it ONE sector over. The slot dim is passed explicitly: the console sender's own world is
        // the overworld, and the default would read that instead of the cell the ship is in.
        // Decoded by the type that WRITES the key, not by a regex beside it: a ZONED key
        // (`zone + '.' + sx_sy_sz`) is a real cell name and matched no `^(-?\d+)_(-?\d+)_(-?\d+)$`.
        dev.stannismod.stellurgy.space.GalacticCoord origin =
                dev.stannismod.stellurgy.space.GalacticCoord.fromCellKey(originCell);
        assertNotNull("entry-status reported no decodable origin cell key: " + status, origin);
        // Marked before the jump is commanded, for the reason the entry above is: the arrival is
        // announced once, and a mark taken after it would be waiting for a second jump.
        long jumpMark = events.mark();
        String jump = act("stellurgytest space jump id " + durableId + " "
                + (origin.sectorX() + 1)
                + " " + origin.sectorY() + " " + origin.sectorZ() + " " + slotDim).raw();
        assertTrue("the jump probe found no settled ship to move: " + jump, Reply.of(jump).bool("began"));
        // THIS ship departed. Without the id the verb jumps the cell's first settled row, so a cell
        // holding a second craft would carry that one away and report a successful jump.
        assertEquals("the jump named a different ship: " + jump,
                durableId, extractString(jump, "shipId"));
        String targetCell = extractString(jump, "toCell");
        assertTrue("jump reported no target cell: " + jump, targetCell != null);
        // CONTROL: a target equal to the origin would make the arrival assert vacuous.
        assertTrue("the jump target must differ from the origin cell, else arrival proves nothing: "
                + originCell + " -> " + targetCell, !targetCell.equals(originCell));

        // A completed jump leaves the ship AT the cell it was aimed at — the WHOLE key, not just the
        // axis the jump asked to move. Everything downstream of a jump reads that address: the
        // descent proximity check looks up the bodies of the ship's own cell, so an address in the
        // wrong cell means the destination system is not there at all.
        // Nobody rides a jump today, so the destination cell has NO observer — and VS loads a ship
        // only for one. Drop the headless keep-loaded crutch for the whole arrival: with it on, the
        // pasted ship becomes live for free and the arrival's own readiness gate is never tested,
        // which is how this leg stayed green while the same jump, flown by hand, gave up in the
        // paste lane. From here the arrival must make its own ship loadable.

        // Nothing below pumps the manager either: the live Ticker advances the transit every tick.
        //
        // Linked on the arrival production announces — `ShipTransitManager.announceTransitEnded`
        // posts `TransitEnded`, whose record carries the craft and the destination CELL. Narrowed by
        // both: a transit that ended somewhere else is not this jump arriving, and the ledger poll
        // this replaced could only ask "is the row SETTLED at that key yet" one reading at a time.
        events.awaitRecordWithFields(jumpMark, "ship_transit_ended",
                "the ship must arrive at the cell the jump was aimed at; origin=" + originCell
                        + " requested=" + targetCell, SETTLE_TICKS,
                "ship", durableId, "destination", targetCell);
        String arrived = read("stellurgytest space entry-status id " + durableId).raw();
        assertEquals("the arrival was announced for " + targetCell
                + ", so the ledger must carry that cell; last status=" + arrived
                + " subsystem=" + SubsystemStatus.read(c -> read(c).raw()).raw(),
                targetCell, extractString(arrived, "cellKey"));

        // A ledger row written by the arrival itself proves only what the arrival BELIEVES. The
        // player's reading comes later, when he walks up to his ship: his presence loads it, its
        // flight computer starts ticking, and the address he sees is inverted from the ship's REAL
        // pose. An arrival that gave up in the paste lane still stamps the requested cell above and
        // only slips a sector once that first tick lands. So bring the observer in now — after the
        // jump, never during it — and read the address again.
        int arrivedSlot = extractInt(arrived, "slotDim");
        // The jump RE-ASSEMBLED the hull, so the physics id it had in the origin cell may name nothing
        // here; the durable id is the one thing that crossed. Ask the ledger's own bridge for the
        // arrival's id, refuse to go on without it, and ask for THAT craft to be loaded.
        String arrivedVsId = ShipIdentity.physicsIdOf(c -> read(c).raw(), arrivedSlot, durableId);
        arrange("stellurgytest vs load-ship " + arrivedSlot + " " + arrivedVsId).requireOk("ask for the arrival");
        final String[] pose = {""};
        // The ledger's own coordinate for this craft, kept from the last sample: the pose witness
        // below compares the ship against WHERE THE LEDGER SAYS IT IS, and both are read inside the
        // same window.
        final String[] ledgerRow = {""};
        // Sampled on the SLOT WORLD's clock, not the server's. What drifts is the ship, and the ship
        // drifts because its own flight computer ticks in that world - so the window has to be
        // measured in the ticks the subject runs on. The two clocks are not interchangeable here: a
        // slot world that had stopped ticking would let a server-clock window run its whole length
        // and report stability about a ship that was never asked to move.
        GameTicks.observe(client(), GameTicks.world(arrivedSlot),
                DRIFT_SAMPLES, DRIFT_TICKS_BETWEEN_SAMPLES, () -> {
                    arrange("stellurgytest vs load-ship " + arrivedSlot + " " + arrivedVsId);
                    // THE POSE FIRST, with nothing between it and the load above: the ship unloaded
                    // inside an extra probe round-trip often enough to red a healthy run (measured).
                    //
                    // Asked BY NAME, so the reply is about this craft or about nothing: the cell's
                    // ship is unloaded and reloaded between samples, and the reply's own `managed`
                    // says which kind of sample this was.
                    String poseNow = read("stellurgytest vs ship-info " + arrivedSlot + " id " + arrivedVsId).raw();
                    pose[0] = poseNow;
                    String held = read("stellurgytest space entry-status id " + durableId).raw();
                    ledgerRow[0] = held;
                    assertEquals("the arrived ship's address drifted out of the cell it flew to once"
                                    + " its flight computer began self-reporting its position;"
                                    + " status=" + held + " ship=" + pose[0],
                            targetCell, extractString(held, "cellKey"));
                });
        // And the same fact read off the SHIP rather than off the ledger — the two compared where
        // they must agree: the ship's realized pose against the realization of the coordinate the
        // ledger holds for it.
        //
        // This used to ask whether the ship's Y had cleared HALF_CELL, on the reasoning that a cell
        // realized its contents megablocks up while an unsettled arrival was left at the staging
        // band's ordinary block Y. That discriminator was an artefact of the Y shift: centring the
        // pose band on 2026-09-11 put a settled ship at its cell's centre on world Y 0, which no
        // magnitude test can tell from anything. Comparing against the ledger's own coordinate is
        // what the check meant all along, and it survives the next change of mapping too.
        assertTrue("the arrived ship was never loaded in its destination cell: " + pose[0],
                ShipInfo.isLoaded(pose[0]));
        ShipInfo arrivedPose = ShipInfo.of(pose[0]);
        double[] expected = CellWorldMapper.poseWorldOf(GalacticCoord.ofSectorLocal(0L, 0L, 0L,
                (long) extractDouble(ledgerRow[0], "lx"),
                (long) extractDouble(ledgerRow[0], "ly"),
                (long) extractDouble(ledgerRow[0], "lz")));
        double[] actual = {arrivedPose.x, arrivedPose.y, arrivedPose.z};
        for (int axis = 0; axis < 3; axis++) {
            // CARRY_MARGIN as the tolerance, because it is production's OWN answer to "still at this
            // coordinate as far as the cell is concerned" — the distance a craft may sit past a face
            // before the seam carries it. A settle that landed further off than that has not landed.
            assertTrue("the arrived ship is not where its own ledger row says it is, so it never"
                            + " reached the coordinate the jump was aimed at. axis " + axis
                            + ": ship " + actual[axis] + " vs ledger " + expected[axis]
                            + " (tolerance " + CellSeam.CARRY_MARGIN + "). ship=" + pose[0]
                            + " ledger=" + ledgerRow[0],
                    Math.abs(actual[axis] - expected[axis]) <= CellSeam.CARRY_MARGIN);
        }
        assertEquals("nothing may still be in transit once the ledger reports arrival", 0,
                SubsystemStatus.read(c -> read(c).raw()).transits);
    }

    /** Server ticks for a craft to be named and become usable where it is: one or two ticks away. */
    private static final int USABLE_TICKS = 200;

    /** The mark taken before this scenario's build, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    /** The craft goes first, wherever its latest naming put it; the entry stack after it. */
    @After
    public void cleanup() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
        arrange("stellurgytest space entry-clear");
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    /** Lay and press a piloted craft at {@code (x, z)}; the craft is this scenario's from here on. */
    private ShipBirth build(int x, int z) throws Exception {
        int[] bp = placeFixture(FixtureSite.openAir(0, x, z), "with-pilot-seat");
        buildMark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, bp, asm, USABLE_TICKS,
                "the craft this scenario flies into space");
        builtDurable = birth.durable;
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());
        return birth;
    }


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
    private int[] placeFixture(FixtureSite site, String variant) throws Exception {
        return RocketFixture.placeAt(site, c -> arrange(c).raw(), variant, 4, 12,
                "the craft this scenario builds stands in this volume");
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }

    private static double extractDouble(String json, String key) {
        return Reply.of(json).number(key);
    }

    private static String extractString(String json, String key) {
        return Reply.of(json).text(key);
    }
}
