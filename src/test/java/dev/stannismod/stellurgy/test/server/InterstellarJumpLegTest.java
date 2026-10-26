package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.*;

/**
 * Does a jump to ANOTHER STAR SYSTEM fly, and how long does it take?
 *
 * <p>Every jump ever flown here has been a hop of one sector. The distance between two real systems
 * is three orders of magnitude larger — the generator partitions space into 512-cell super-cells and
 * a cell is {@link dev.stannismod.stellurgy.space.GalacticCoord#CELL} blocks — so the interstellar
 * leg has never been exercised: not the integrator over thousands of ticks, not the arrival into a
 * cell that far out, not the ledger address it settles at. Nothing in the gate refuses it; nobody
 * had flown it.</p>
 *
 * <p><i>This said "a cell is 4M blocks" until 2026-09-08, by which time the constant was 32M. The
 * number is linked rather than quoted now: a size written out in prose is one that will be wrong
 * again, and this one had already made the test cost eight times what its own speed constant
 * claimed.</i></p>
 *
 * <p><b>The control is in the run.</b> The same ship jumps one sector first. That leg must arrive
 * almost immediately — a hop is four ticks at the baseline speed — and it establishes that the
 * arrangement (entry stack, ledger, arrival, the poll that reads them) works at all. Only then does
 * the far leg get to speak: a red near leg indicts the scaffolding and says nothing about distance.
 * The two legs also stand as each other's measurement — the same ship, the same stack, the same
 * instrument, one variable changed.</p>
 *
 * <p>What is asserted is SHAPE, never a duration: that the far leg arrives at the cell it was aimed
 * at, and that it takes strictly longer than the hop. The tick counts it prints are balance
 * readings — speed, spacing and cell size are all `tunable` — and a test that pinned one would go
 * red on a rebalance with nothing broken.</p>
 */
public class InterstellarJumpLegTest extends AbstractSharedServerTest {

    /** Where the craft is built — a loaded overworld region well clear of the other space suites. */
    private static final int SRC_X = 6800, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 6800;

    /**
     * Sectors to the neighbouring system. Measured, not invented: over 20 seeds of the default
     * generator the nearest other system sits 443-591 cells away (median 537), which is what one
     * super-cell edge of 512 works out to once two anchors sit in adjacent cubes.
     */
    private static final int INTERSTELLAR_SECTORS = 537;

    /**
     * Blocks per tick the legs are flown at. The DISTANCE is the real one; the speed is not, and the
     * difference is deliberate. What this test pins is that a target a whole system away arrives at
     * the cell it was aimed at and costs more time than a hop — a shape that holds at any speed —
     * while the speed a jump is flown at is supplied by the caller here, never derived, so flying
     * slowly would buy realism the probe path cannot deliver anyway.
     *
     * <p><b>Re-derived 2026-09-08, because the old figure had gone stale by 8x with nothing saying
     * so.</b> This was {@code 5_000_000} under a note promising the suite ~20 s. The leg actually
     * cost <b>3 452 ticks (172.6 s)</b> — the largest single wait in the whole server tier. The
     * speed was not the problem: {@link dev.stannismod.stellurgy.space.GalacticCoord#CELL} is
     * 32 000 000 blocks and this class's own prose still said 4M, so the DISTANCE grew eightfold
     * under a constant tuned before it did.
     *
     * <p>At 40 000 000 the far leg is 537 × 32M / 40M = <b>430 ticks (~21 s)</b> — what the old note
     * promised. Two bounds keep the test meaning what it means, and both hold with room: the far leg
     * must stay longer than {@code ShipTransitManager.DIRECT_CROSSING_MAX_TICKS} (160) or it stops
     * being a FLIGHT and becomes a single crossing (430 clears it), and it must stay longer than the
     * hop, which at this speed is under one tick.
     *
     * <p>The original MEASUREMENT was taken at the baseline drive's own {@code 1_000_000}
     * blocks/tick, when a cell was 4M: 537 sectors took 2 167 ticks (108 s) against 2 148 predicted.
     * <b>That prediction still holds</b> — 3 452 observed against 3 437 predicted at 5M with a 32M
     * cell — so the arithmetic was never wrong, only the number it was being applied to. Set this
     * back to the drive's own speed to re-measure.</p>
     */
    private static final long FLIGHT_SPEED = 40_000_000L;


    /** Server ticks the CLIMB into space is given. */
    private static final int SETTLE_TICKS = 600;

    /** The same, for a ship becoming loadable in its slot - the old 40 x 250 ms. */
    /**
     * Poll iterations for a ARRIVAL, one second apart. The far leg is thousands of ticks of real
     * server time by design, so this budget is sized from the leg itself — 537 sectors x 4M blocks
     * at 1M blocks/tick is ~2 150 ticks ~ 108 s — with room for the arrival's own retries on top.
     */
    private static final int ARRIVAL_TICKS = 6000;

    @Test
    public void aJumpToAnotherStarSystemArrivesAndCostsMoreTimeThanAHop() throws Exception {

        String setup = arrange("stellurgytest space entry-setup 2").raw();
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        // A ship that reached space the way a ship does: built, assembled, flown past the ceiling.
        int[] bp = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-seat");
        buildMark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        builtDurable = ShipIdentity.nameFromAssembly(asm);
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, bp, asm, SHIP_LINK_TICKS,
                "the craft that flies the two legs");
        flyBothLegs(setup, birth);
    }

    /**
     * Where the skip scenario's craft is built: the open-air band, a site of its own beside the legs' one.
     */
    private static final int SKIP_X = 7600, SKIP_Z = 7600;

    /** The skip, in ticks: well under the far leg's flight at {@link #FLIGHT_SPEED}, so it lands mid-flight. */
    private static final int SKIP_TICKS = 100;

    /** Server ticks this craft's entry into space is given, as a deadline for the record that says it did. */
    private static final int ENTRY_TICKS = 600;
    /**
     * C16 CLOCK-7, the transit half (space INV-SPC-21): an operator's time skip carries a ship in flight through
     * the skipped span. The ship is a real one, flown into space and jumped to another system on the server's own
     * transit manager; around one {@code /time add} its flight's remaining distance and the space clock are read
     * together, twice. The skip's side moves the clock; the transit's side answers with distance. With the skip
     * wired to the transit, the distance falls by the flight's speed times everything the clock moved — the skip
     * and the ticks between the reads alike. Unwired, it falls only by the ticks.
     *
     * <p>What it does not see: a crew's online gate (no crew is aboard), and a skip that reaches the arrival.</p>
     */
    @Pins("INV-SPC-21")
    @RedWitness(verdict = "C16 CLOCK-7", member = "TimeSkip#skip", at = "space.transit.carryThrough(ticks);",
            replacedBy = "space.transit.carryThrough(0L);", fails = "the skipped ones included", on = "2026-10-26")
    @Wait(shape = Wait.Shape.WINDOW, at = "arrange(\"time add \" + SKIP_TICKS);",
            why = "the measurement is the change in the flight's remaining distance against the change in the space"
                    + " clock between two reads, across one skip; each read carries both values, and their two"
                    + " differences are the comparison")
    @Test
    public void aSkipCarriesAShipInFlightThroughTheSkippedSpan() throws Exception {
        arrange("stellurgytest space entry-setup 2").requireOk();
        String what = "the craft a skip catches in flight";
        FixtureSite site = FixtureSite.openAir(0, SKIP_X, SKIP_Z);
        buildMark = events.mark();
        Reply fixture = AssembledCraft.lay(site, c -> arrange(c).raw(), "with-pilot-seat", 6, 16, what);
        AssembledCraft craft = AssembledCraft.assemble(site, c -> act(c).raw(), events, fixture, what);
        builtDurable = craft.durable;

        // Into space the way a ship gets there: above the entry ceiling, handed back to physics.
        ShipInfo onThePad = ShipInfo.byId(c -> read(c).raw(), 0, craft.physicsId);
        // A hull put above the entry ceiling is taken into space by the entry on-ramp from where it stands — the
        // crossing begins on the teleport itself (measured: `entry_decided` and `cell_crossing_begun` follow
        // `ship_pose_teleported` with the craft still parked), so the mark precedes the teleport.
        long entryMark = events.mark();
        arrange("stellurgytest vs teleport-ship-by-id 0 " + craft.physicsId + " " + onThePad.x + " "
                + OrbitLine.of(c -> read(c).raw(), 0).aboveEntryCeiling() + " " + onThePad.z).requireOk();
        WorldCommandFixtures.awaitEnteredSpace(events, entryMark, craft.durable, what + " must enter space",
                ENTRY_TICKS, null);
        EntryStatus inSpace = EntryStatus.forShip(c -> read(c).raw(), craft.durable);
        dev.stannismod.stellurgy.space.GalacticCoord origin =
                dev.stannismod.stellurgy.space.GalacticCoord.fromCellKey(inSpace.cellKey);
        requireArranged(what + " — its ledger row must name a cell: " + inSpace.raw(), origin != null);

        Reply jump = act("stellurgytest space jump id " + craft.durable + " "
                + (origin.sectorX() + INTERSTELLAR_SECTORS) + " " + origin.sectorY() + " " + origin.sectorZ() + " "
                + inSpace.slotDim + " " + FLIGHT_SPEED);
        requireArranged(what + " — the jump must begin: " + jump, jump.bool("began"));

        Reply before = read("stellurgytest space transit-progress " + craft.durable).requireOk();
        requireArranged(what + " — it must be in flight with more than the skip left to fly: " + before,
                before.bool("inTransit") && before.number("remaining") > (double) FLIGHT_SPEED * SKIP_TICKS * 2);
        arrange("time add " + SKIP_TICKS);
        Reply after = read("stellurgytest space transit-progress " + craft.durable).requireOk();
        requireArranged(what + " — it must still be in flight after the skip: " + after, after.bool("inTransit"));

        long clockMoved = after.longInteger("spaceClock") - before.longInteger("spaceClock");
        double flown = before.number("remaining") - after.number("remaining");
        assertTrue("the skip must move the space clock by at least its own span: before=" + before + " after=" + after,
                clockMoved >= SKIP_TICKS);
        assertEquals("C16 CLOCK-7: a ship in flight is carried through the skip — it flies its speed for every tick the"
                        + " space clock moved, the skipped ones included; before=" + before + " after=" + after,
                (double) FLIGHT_SPEED * clockMoved, flown, 0.0);
    }

    /** The mark taken before this scenario's build, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    /** Server ticks for this build's hull to be named and become usable: one or two ticks away. */
    private static final int SHIP_LINK_TICKS = 200;

    private void flyBothLegs(String setup, ShipBirth birth) throws Exception {
        assertTrue("an AFC-bearing build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());

        // The ship's own name, from the assembler that minted it, and the physics id it maps to. Every
        // call below is addressed to one of the two: the overworld this class builds in is shared, so
        // "the ship near the pad" is a question with more than one true answer.
        String durableId = birth.durable;
        String shipId = ArrangementFailure.arranged(
                () -> birth.awaitUsable(c -> read(c).raw(), events, SHIP_LINK_TICKS));

        ShipInfo src = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        int sx = (int) src.x;
        int sy = (int) src.y;
        int sz = (int) src.z;
        String held = arrange("stellurgytest vs ff-input-by-id 0 " + shipId + " 0 1 0 0 0 0").raw();
        assertTrue("the held input must reach this ship's flight computer: " + held,
                Reply.of(held).bool("afcResolved"));
        assertTrue("climb teleport failed", Reply.of(arrange("stellurgytest vs teleport-ship-by-id 0 " + shipId + " "
                + sx + " " + OrbitLine.of(c -> read(c).raw(), 0).aboveEntryCeiling() + " " + sz).raw()).ok());
        // Marked BEFORE the unpark, because the unpark is what starts the entry and the settle is
        // announced once.
        long entryMark = events.mark();
        arrange("stellurgytest vs unpark-by-id 0 " + shipId);

        EntryStatus status = awaitEntered(entryMark, setup, SETTLE_TICKS, durableId);
        assertTrue("precondition: the ship never entered space, so there is nothing to jump; last="
                + status.raw(), status.settled());
        int slotDim = status.slotDim;
        String originCell = status.cellKey;
        // Decoded by the type that WRITES the key. `GalacticCoord.fromCellKey` is the exact inverse
        // of `cellKey()` and handles the ZONED form (`zone + '.' + sx_sy_sz`) that a cell inside a
        // body's lattice carries; the `^(-?\d+)_(-?\d+)_(-?\d+)$` that stood here matched none of
        // those, and would have blamed the producer for "no decodable origin cell key".
        dev.stannismod.stellurgy.space.GalacticCoord origin =
                dev.stannismod.stellurgy.space.GalacticCoord.fromCellKey(originCell);
        assertNotNull("entry-status reported no decodable origin cell key: " + status.raw(), origin);
        long osx = origin.sectorX();
        String osy = String.valueOf(origin.sectorY()), osz = String.valueOf(origin.sectorZ());

        // ---- CONTROL LEG: one sector over. Four ticks of flight; it proves the arrangement. -------
        long hopTicks = flyTo(osx + 1, osy, osz, slotDim, originCell, setup, "hop", durableId);
        requireArranged("a one-sector hop must arrive, or nothing below is about distance."
                + " Fix the scaffolding before reading the far leg.", hopTicks >= 0);

        EntryStatus afterHop = EntryStatus.forShip(c -> read(c).raw(), durableId);
        String hopCell = afterHop.cellKey;
        int hopSlot = afterHop.slotDim;
        dev.stannismod.stellurgy.space.GalacticCoord hopOrigin =
                dev.stannismod.stellurgy.space.GalacticCoord.fromCellKey(hopCell);
        assertNotNull("no decodable cell key after the hop: " + afterHop.raw(), hopOrigin);

        // ---- THE SUBJECT: the same ship, the same stack, 537 sectors instead of one. ---------------
        long farTicks = flyTo(hopOrigin.sectorX() + INTERSTELLAR_SECTORS,
                String.valueOf(hopOrigin.sectorY()), String.valueOf(hopOrigin.sectorZ()),
                hopSlot, hopCell, setup, "interstellar", durableId);

        System.out.println("[interstellar-leg] hop=" + hopTicks + " ticks, interstellar(" + INTERSTELLAR_SECTORS
                + " sectors)=" + farTicks + " ticks = " + (farTicks / 20.0D) + " s"
                + " at " + FLIGHT_SPEED + " blocks/tick");

        assertTrue("a jump to another star system must ARRIVE — the leg the game is built around has"
                + " never been flown, and if it cannot complete, M2's script has no third step."
                + " hop=" + hopTicks + " far=" + farTicks, farTicks >= 0);
        assertTrue("...and it must cost more time than a one-sector hop, or the transit is not"
                + " integrating distance at all: hop=" + hopTicks + " far=" + farTicks,
                farTicks > hopTicks);
    }

    /**
     * Fly the settled ship to {@code (tsx,tsy,tsz)} and return how many server ticks the leg took,
     * or {@code -1} when it never arrived. The jump is fired at the baseline speed and nothing pumps
     * the manager: the live Ticker advances the transit every tick, which is what makes the returned
     * number the flight's own duration rather than the poll loop's.
     */
    private long flyTo(long tsx, String tsy, String tsz, int slotDim, String fromCell,
                       String setup, String label, String durableId) throws Exception {
        // Marked before the jump command: the arrival is announced once, and a mark taken after the
        // command has already been given is a window the record can have passed through.
        long jumpMark = events.mark();
        String jump = act("stellurgytest space jump id " + durableId + " "
                + tsx + " " + tsy + " " + tsz + " " + slotDim + " " + FLIGHT_SPEED).raw();
        assertTrue("[" + label + "] the jump probe found no settled ship to move: " + jump,
                Reply.of(jump).bool("began"));
        assertEquals("[" + label + "] the jump named a different ship than this scenario's: " + jump,
                durableId, extractString(jump, "shipId"));
        String targetCell = extractString(jump, "toCell");
        assertTrue("[" + label + "] jump reported no target cell: " + jump, targetCell != null);
        assertTrue("[" + label + "] CONTROL: target must differ from origin, else arrival proves"
                + " nothing: " + fromCell + " -> " + targetCell, !targetCell.equals(fromCell));

        long departed = clock();
        try {
            awaitArrival(jumpMark, targetCell, setup, ARRIVAL_TICKS, durableId);
        } catch (AssertionError neverArrived) {
            // A leg that does not arrive is REPORTED, not failed here: which of the two legs is
            // silent is the finding, and only this method's caller knows which one it asked for.
            System.out.println("[interstellar-leg] " + label + " NEVER ARRIVED after "
                    + (clock() - departed) + " ticks; " + neverArrived.getMessage()
                    + " ledger=" + EntryStatus.forShip(c -> read(c).raw(), durableId).raw()
                    + " subsystem=" + SubsystemStatus.read(c -> read(c).raw()).raw());
            return -1L;
        }
        long elapsed = clock() - departed;
        assertEquals("[" + label + "] nothing may still be in transit once the ledger reports arrival",
                0, SubsystemStatus.read(c -> read(c).raw()).transits);
        return elapsed;
    }

    /** The space clock the transit is priced on — the same counter production integrates against. */
    private long clock() throws Exception {
        Reply mReply = Reply.of(read("stellurgytest space frame 0 0 0").raw());
        return mReply.has("clock") ? (long) mReply.number("clock") : -1L;
    }

    /**
     * Wait for THIS craft to finish ENTERING space, then read its ledger row.
     *
     * <p>One of the two questions the single {@code waitForState} poll used to serve, and they are
     * different questions with different records — which is why it had to be split before either
     * could be linked. This one ends on {@code ship_left_planet}, published on the line after
     * {@code ledger.settle}, so the record and the row the poll read are the same moment rather
     * than two things that usually agree.</p>
     *
     * <p>The mark is the caller's and precedes the unpark that starts the entry.</p>
     */
    private EntryStatus awaitEntered(long mark, String setup, int budgetTicks, String durableId)
            throws Exception {
        WorldCommandFixtures.awaitEnteredSpace(events, mark, durableId,
                "the ship must finish entering space", budgetTicks, null);
        return EntryStatus.forShip(c -> read(c).raw(), durableId);
    }

    /**
     * Wait for THIS craft's jump to ARRIVE at {@code cell}, then read its ledger row.
     *
     * <p>The crossing's own {@code ship_transit_ended} carries both the craft and the destination
     * cell, so the wait is narrowed by both: a transit that ended somewhere else is not this jump
     * arriving, and — because one record has to carry both fields — two craft cannot satisfy the two
     * halves between them. The poll it replaces asked the ledger the same two things, but as a
     * SNAPSHOT: a row that reached the target and was written over by the next leg between two reads
     * was invisible to it.</p>
     *
     * <p><b>What this does to the leg's measured duration, said out loud because a measurement is
     * what the caller does with it</b>: nothing to its definition. The duration is still the space
     * clock read before the jump subtracted from the space clock read after this returns, and this
     * returns on the same 5-tick read granularity the poll had. What changes is only what ends the
     * wait — production announcing the arrival instead of a reading catching the row afterwards.</p>
     *
     * <p>Throws when nothing arrives; the caller decides what a silence MEANS, because the hop's
     * silence and the far leg's silence are different findings and only the caller knows which leg
     * it is flying.</p>
     */
    private String awaitArrival(long mark, String cell, String setup, int budgetTicks,
                                String durableId) throws Exception {
        return events.awaitMatching(mark, "ship_transit_ended",
                reply -> Events.anyRecordHasAll(reply, "ship", durableId, "destination", cell),
                "carrying ship = " + durableId + " and destination = " + cell,
                "the jump must arrive at the cell it was aimed at", budgetTicks);
    }

    /** The craft goes first, wherever its latest naming put it; the entry stack after it. */
    @After
    public void cleanup() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
        arrange("stellurgytest space entry-clear");
    }

    // --- helpers (mirror VSShipEntryTest) --------------------------------------------------------

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());


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
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, 0.0);
    }

    private static String extractString(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).textOr(key, null);
    }
}
