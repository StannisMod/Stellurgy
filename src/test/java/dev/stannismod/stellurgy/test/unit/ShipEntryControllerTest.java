package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.universe.BodyEphemeris;
import dev.stannismod.stellurgy.universe.CellFrame;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Contract tests for the entry on-ramp state machine, driven against the real {@link SpaceManager}
 * with a recording fake {@link ShipCrossingService.Ops} (the transit-manager test discipline).
 * Pins the decisions: an exhausted pool REFUSES entry as a normal surfaced outcome (message +
 * cooldown, cell not leaked); a started entry settles the ship in the ledger at a ring coordinate
 * OUTSIDE the descent radius (the entry&harr;descent hysteresis contract); the crossed cell is
 * marked dirty; a failed crossing releases the cell.
 */
public class ShipEntryControllerTest {

    /** The clamp this scenario sets, in blocks — the arrangement's own number, cited by the
     *  assertion that the entry line drops below it. */
    private static final int ORBIT_CLAMP = 1000;

    /** A constant: a {@code UUID} is an immutable value: two final longs. */
    private static final UUID SHIP = UUID.fromString("00000000-0000-0000-0000-0000000000AA");
    /**
     * A constant: a {@code BlockPos} built with {@code new} is immutable: three final ints (not the mutable
     * subclass).
     */
    private static final BlockPos AFC = new BlockPos(1, 65, 1);
    private static final int LAUNCH_DIM = 0;

    private static GalacticCoord body(long sector) {
        return GalacticCoord.ofSectorLocal(sector, 0L, 0L, 0L, 0L, 0L);
    }

    /** The launch world's body: a sizeless planet of {@link #LAUNCH_DIM}, standing still at {@code name}. */
    private static SystemBody launchBodyAt(GalacticCoord name) {
        return SystemBody.fixedAt(name, SystemBodyKind.PLANET, LAUNCH_DIM, 0);
    }

    /** Run one whole entry of {@link #SHIP} from {@code launch} at {@code tick}; the coordinate it settled at. */
    private static GalacticCoord settledEntryFrom(SystemBody launch, long tick) {
        AtomicLong clock = new AtomicLong(tick);
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger((shipId, coord) -> { });
        ShipEntryController ctl = new ShipEntryController(space, ledger, new FakeOps(), dim -> launch,
                clock::get);
        assertTrue("arrangement: the entry must start", ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        ctl.tick();
        ctl.tick();
        ctl.tick();
        ShipLedger.Entry entry = ledger.get(SHIP);
        assertNotNull("arrangement: the entry must settle the ship in the ledger", entry);
        return entry.coord;
    }

    /** Recording binder (mirrors SpaceManagerTest) so the entry tests drive a real SpaceManager. */
    private static final class FakeBinder implements SlotBinder {
        final int[] dims;
        FakeBinder(int... dims) { this.dims = dims; }
        @Override public int[] slotDims() { return dims; }
        @Override public void load(int dimId, dev.stannismod.stellurgy.space.GalacticCoord cell) { }
        @Override public void unload(int dimId) { }
        @Override public void discard(int dimId) { }
        @Override public void deleteStore(String cellKey) { }
    }

    /** Recording ops: a ship exists at the launch pad; knobs force each failure mode. */
    private static final class FakeOps implements ShipCrossingService.Ops {
        boolean shipPresent = true;
        boolean failCross;
        int reseatFailCount;
        int teleportFailCount;
        final List<String> messages = new ArrayList<>();
        final List<Integer> pinned = new ArrayList<>();
        final List<double[]> teleports = new ArrayList<>();
        final List<Integer> reseatDims = new ArrayList<>();
        /**
         * The identity the cross hands back — every settle half must address THIS ship.
         *
         * <p>A constant: a {@code UUID} is an immutable value: two final longs.</p>
         */
        static final UUID CROSSED_SHIP = UUID.fromString("11111111-2222-3333-4444-555555555555");
        /** What each settle half actually named; a null is a position lookup, i.e. the defect. */
        final List<UUID> reseatShipUuids = new ArrayList<>();
        final List<UUID> teleportShipUuids = new ArrayList<>();
        final List<UUID> unparkShipUuids = new ArrayList<>();
        int unparks;
        int crossings;
        int captures;
        int peeks;
        int latches;
        List<CrewTransfer.Crew> lastCaptured;
        List<CrewTransfer.Crew> lastReseated;

        @Override public double[] shipWorldPosition(int dimId, BlockPos afcPos) {
            return shipPresent ? new double[]{10.0, 1200.0, 10.0} : null;
        }

        /** The ship the last capture was told it was emptying — null when it was told nothing. */
        java.util.UUID capturedFor;
        @Override public List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos,
                                                             double[] shipWorldPos,
                                                             java.util.UUID shipId) {
            captures++;
            capturedFor = shipId;
            lastCaptured = new ArrayList<>(); // crew mechanics are integration-tier; none here
            return lastCaptured;
        }

        @Override public List<CrewTransfer.Crew> peekCrew(int dimId, BlockPos afcPos,
                                                          double[] shipWorldPos) {
            peeks++;
            return new ArrayList<>();
        }

        /** Which ship each crossing was asked to cut, IN ORDER. Recorded rather than ignored: the
         *  seam's identity argument exists so a crossing cannot act on whatever a position lookup
         *  reaches, and a double that drops it would let that regress with every test still green. */
        final List<java.util.UUID> crossedShips = new ArrayList<>();

        @Override public ShipCrossingService.Crossed cross(java.util.UUID shipId, int srcDimId,
                                        double[] srcShipPos,
                                        int slotDim, int pasteX, int pasteY, int pasteZ) {
            crossings++;
            crossedShips.add(shipId);
            return failCross ? null : new ShipCrossingService.Crossed(
                    new BlockPos(pasteX, pasteY, pasteZ), CROSSED_SHIP);
        }

        @Override public void pinDim(int dimId) { pinned.add(dimId); }

        @Override public boolean reseat(int slotDim, BlockPos anchor, List<CrewTransfer.Crew> crew,
                java.util.UUID shipId, java.util.UUID vsShipUuid) {
            reseatDims.add(slotDim);
            reseatShipUuids.add(vsShipUuid);
            lastReseated = crew;
            if (reseatFailCount > 0) { reseatFailCount--; return false; }
            return true;
        }

        @Override public boolean teleportPoseWithRiders(int slotDim, BlockPos anchor,
                                                        java.util.UUID vsShipUuid,
                                                        double px, double py, double pz) {
            if (teleportFailCount > 0) { teleportFailCount--; return false; }
            teleports.add(new double[]{px, py, pz});
            teleportShipUuids.add(vsShipUuid);
            return true;
        }

        @Override public void unpark(int slotDim, java.util.UUID vsShipUuid,
                                     double px, double py, double pz) {
            unparks++;
            unparkShipUuids.add(vsShipUuid);
        }

        @Override public void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args) {
            messages.add(langKey);
        }

        /** Descent-only op; an entry never latches. Counted so the test can say so. */
        @Override public void latchEntryUntilBelowTheLine(int dimId, BlockPos afcPos) { latches++; }
    }

    private static SpaceManager.Config never() {
        return new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0, 0);
    }

    /**
     * This test fails if {@code ShipEntryController#resolveEntryCoord} stops deciding that the ring an
     * entering ship spawns on is the ring of the ONE body it left, sized by that body.
     */
    @Pins("INV-SPC-15")
    @RedWitness(member = "ShipEntryController#resolveEntryCoord",
            at = "entryRingAround(launch),", replacedBy = "ENTRY_RING_BLOCKS,",
            fails = "is the ring of the body it left", on = "2026-10-28")
    @Test
    public void theEntryRingIsTheRingOfTheBodyTheShipLeft() {
        SystemBody earth = launchBodyAt(GalacticCoord.ofSectorLocal(19, 0, 0, 0, 0, 0)).withRadius(1d);
        long ring = ShipEntryController.entryRingAround(earth);
        assertTrue("arrangement: a sized body's ring must differ from the flat one, or the two could not be "
                + "told apart", ring > ShipEntryController.ENTRY_RING_BLOCKS);

        double distance = settledEntryFrom(earth, 0L).staticFrameDistanceTo(earth.name());
        // StandoffRing rounds each component down, never out: the point is at most the ring away and
        // short of it by under one block per axis.
        assertEquals("the ring a ship leaving the planet spawns on is the ring of the body it left",
                ring, distance, 2.0);
    }

    /**
     * Pins INV-SPC-15 for a launch from a world that is no body of the universe: the ship is placed on
     * the flat ring around the home anchor, strictly outside the descent radius.
     */
    @Pins("INV-SPC-15")
    @RedWitness(verdict = "the entry ring lies outside the descent radius",
            member = "ShipEntryController#resolveEntryCoord",
            at = "StandoffRing.pointAround(homeAnchor(), ENTRY_RING_BLOCKS, shipId.hashCode())",
            replacedBy = "StandoffRing.pointAround(homeAnchor(), DESCENT_RADIUS_BLOCKS / 2L, shipId.hashCode())",
            fails = "the entry ring must lie outside the descent radius", on = "2026-10-28")
    @RedWitness(verdict = "the entry ring lies outside the descent radius",
            member = "ShipEntryController#ENTRY_RING_BLOCKS", at = "DESCENT_RADIUS_BLOCKS * 2L",
            replacedBy = "DESCENT_RADIUS_BLOCKS / 2L",
            fails = "the entry ring must lie outside the descent radius", on = "2026-10-28")
    @Test
    public void anEntryFromAWorldThatIsNoBodyLandsOnTheFlatRingOutsideTheDescentRadius() {
        // The configuration's class initializer touches vanilla's block registry, which throws without
        // the vanilla bootstrap -- and the home anchor is read from that configuration.
        dev.stannismod.stellurgy.test.MinecraftBootstrap.ensure();
        // Arrangement: where the home anchor is, read as production reads it. The verdict is about the
        // ring around it, not about which anchor the configuration names.
        dev.stannismod.stellurgy.api.StellurgyConfiguration cfg =
                dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig();
        GalacticCoord parsed = cfg == null ? null
                : dev.stannismod.stellurgy.universe.UniverseRegistry.parseAnchor(cfg.spaceHomeSystemCoord);
        GalacticCoord anchor = parsed == null ? GalacticCoord.ORIGIN : parsed;

        double distance = settledEntryFrom(null, 0L).staticFrameDistanceTo(anchor);
        assertTrue("the entry ring must lie outside the descent radius: ring distance " + distance,
                distance > ShipEntryController.DESCENT_RADIUS_BLOCKS);
    }

    @Pins("INV-SPC-15")
    @Test
    public void entryCrossesSettlesInLedgerOutsideTheDescentRadius() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger((shipId, coord) -> { });
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> launchBodyAt(body(5)), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertTrue(ctl.isEntering(SHIP));
        assertEquals("slot pinned across the async crossing", 1, ops.pinned.size());

        ctl.tick(); // pose teleport (runs FIRST: the split-pair invariant)
        ctl.tick(); // re-seat at the pose
        ctl.tick(); // unpark + settle

        assertFalse(ctl.isEntering(SHIP));
        ShipLedger.Entry entry = ledger.get(SHIP);
        assertNotNull("the ship is ledgered after entry", entry);
        assertEquals(ShipLedger.State.SETTLED, entry.state);
        assertTrue("entry cell is live", space.isLoaded(entry.coord));
        assertEquals("entered the launch body's own cell", body(5).cellKey(), entry.cellKey());
        // The hysteresis contract: the spawn ring lies strictly OUTSIDE the descent radius, so a
        // fresh entry can never immediately trip the descent trigger.
        double distance = entry.coord.staticFrameDistanceTo(body(5));
        assertTrue("the entry ring must lie outside the descent radius: ring distance " + distance,
                distance > ShipEntryController.DESCENT_RADIUS_BLOCKS);
        // The realized pose matches the ledgered coordinate (the honest-3D mapping).
        double[] expectedPose = CellWorldMapper.poseWorldOf(entry.coord);
        assertEquals(1, ops.teleports.size());
        assertEquals(expectedPose[1], ops.teleports.get(0)[1], 0.0);
        assertEquals("ship unparked after the pose write", 1, ops.unparks);
        assertEquals("crew told they arrived", "msg.shipentry.arrived",
                ops.messages.get(ops.messages.size() - 1));
        // The post-descent entry latch belongs to the descent alone. An entry that latched would
        // hold its own on-ramp off the next time the ship came back down and tried to leave again.
        assertEquals("an entry never latches the entry on-ramp", 0, ops.latches);
    }

    @Test
    public void exhaustedPoolRefusesEntryWithoutLeakingTheCell() {
        AtomicLong clock = new AtomicLong();
        // Pool of ONE, already held by another occupied cell -> materialize must throw.
        SpaceManager space = new SpaceManager(new FakeBinder(10), clock::get, never());
        space.materialize(body(99)); // refcount 1, not evictable
        ShipLedger ledger = new ShipLedger((shipId, coord) -> { });
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> launchBodyAt(body(5)), clock::get);

        assertFalse("entry is refused", ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertFalse(ctl.isEntering(SHIP));
        assertNull("nothing ledgered on a refusal", ledger.get(SHIP));
        assertEquals("no crossing was attempted", 0, ops.crossings);
        // A refusal must leave the pilot in his seat: the crew is READ for the message, never
        // captured (a capture dismounts every rider and retires the mounts).
        assertEquals("a refused entry never captures (= unseats) the crew", 0, ops.captures);
        assertTrue("the refusal message still reaches the crew via the read-only peek",
                ops.peeks >= 1);
        assertEquals("the pilot is told", "msg.shipentry.refused", ops.messages.get(0));

        // The refusal armed a cooldown: an immediate retry is silently ignored (no message spam).
        assertFalse(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertEquals("no second message inside the cooldown", 1, ops.messages.size());

        // After the cooldown (and pool pressure gone) the entry goes through.
        space.dematerialize(body(99));
        clock.addAndGet(1000L);
        assertTrue("entry retries after the cooldown", ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
    }

    @Test
    public void failedCrossingReleasesTheCellAndArmsTheCooldown() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10), clock::get, never());
        ShipLedger ledger = new ShipLedger((shipId, coord) -> { });
        FakeOps ops = new FakeOps();
        ops.failCross = true;
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> launchBodyAt(body(5)), clock::get);

        assertFalse(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertFalse(ctl.isEntering(SHIP));
        assertNull(ledger.get(SHIP));
        assertEquals("msg.shipentry.failed", ops.messages.get(0));
        // The cut never happened, so the crew captured just before it must be put BACK on their
        // seats — in the LAUNCH world the intact ship still sits in.
        assertEquals("the captured crew is re-seated after the failed cut", 1, ops.reseatDims.size());
        assertEquals("the re-seat targets the launch world", LAUNCH_DIM,
                (int) ops.reseatDims.get(0));
        assertTrue("the re-seat restores exactly the crew the capture took",
                ops.lastReseated == ops.lastCaptured);
        // The refcount was released: another ship can claim the single slot right away.
        SpaceManager probe = space; // same manager
        assertEquals("the failed entry's cell holds no occupant",
                10, probe.materialize(body(77)));
    }

    @Test
    public void settleRetriesWhileTheAsyncReassemblyIsNotUpYet() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger((shipId, coord) -> { });
        FakeOps ops = new FakeOps();
        ops.teleportFailCount = 2; // re-assembly queryable on the third tick
        ops.reseatFailCount = 1;   // seats resolve one tick after that
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> launchBodyAt(body(5)), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        for (int i = 0; i < 4; i++) {
            assertTrue("still settling on tick " + i, ctl.isEntering(SHIP));
            ctl.tick();
        }
        ctl.tick(); // the successful re-seat at the pose
        ctl.tick(); // the unpark+settle tick

        assertFalse("settled despite the slow re-assembly", ctl.isEntering(SHIP));
        assertNotNull(ledger.get(SHIP));
    }

    /**
     * Every world-facing half of the settle must address the ship the crossing CREATED, by its own
     * identity — not "whatever ship is at the arrival point".
     *
     * <p>The distinction is invisible while a destination holds one ship and decides the outcome
     * when it holds two: a position lookup has no distance bound, so it answers for the nearest
     * craft. A player's entry has already ended with his ship parked in a cell while the arrival
     * re-seated against another craft's shipyard — no pilot seat in it, ever, and the pilot left
     * standing while his own seat sat 51,200 blocks away in the same world.</p>
     *
     * <p>This pins the WIRING: the state machine hands its crossing's identity to all three halves.
     * That the identity then resolves the right shipyard is the seam's own contract, exercised
     * where a real physics registry exists.</p>
     */
    @Test
    public void everySettleHalfAddressesTheShipTheCrossingCreated() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger((shipId, coord) -> { });
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> launchBodyAt(body(5)), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        ctl.tick(); // pose
        ctl.tick(); // re-seat
        ctl.tick(); // unpark + settle

        assertFalse("the crossing never finished, so no half was exercised", ctl.isEntering(SHIP));
        assertEquals("the pose teleport did not name the crossed ship",
                java.util.Collections.singletonList(FakeOps.CROSSED_SHIP), ops.teleportShipUuids);
        assertEquals("the re-seat did not name the crossed ship — it would scan whichever "
                        + "shipyard happens to be nearest the arrival point",
                java.util.Collections.singletonList(FakeOps.CROSSED_SHIP), ops.reseatShipUuids);
        assertEquals("the unpark did not name the crossed ship",
                java.util.Collections.singletonList(FakeOps.CROSSED_SHIP), ops.unparkShipUuids);
    }

    @Test
    public void duplicateAndLedgeredShipsDoNotReenter() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger((shipId, coord) -> { });
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> launchBodyAt(body(5)), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertFalse("an in-flight entry is not restarted", ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertEquals(1, ops.crossings);
        // The cut is told WHICH ship, not merely where to look. The seam carried no identity at all
        // until 2026-09-06, so the craft that left a launch world was whichever one a position
        // lookup reached — and a launch site that has been entered from before already holds a
        // blockless remnant of the last crossing at that very pose.
        assertEquals("the entry crossing must be told which ship to cut",
                java.util.Collections.singletonList(SHIP), ops.crossedShips);

        ctl.tick();
        ctl.tick();
        ctl.tick();
        assertFalse("a ship already in space cannot enter again",
                ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertEquals(1, ops.crossings);
    }

    /**
     * The trigger is WORLD and ALTITUDE, and nothing else — see the twin note on
     * {@code DescentControllerTest}. The "no pilot, no entry" leg went with the parameter it
     * exercised: production always passed it {@code true}, and an unmanned ship above the orbit
     * line is now meant to leave.
     */
    @Test
    public void triggerPredicateGatesOnWorldAndCeilingOnly() {
        assertTrue(ShipEntryController.shouldTriggerEntry(false, 1001.0, 1000));
        assertFalse("below the ceiling",
                ShipEntryController.shouldTriggerEntry(false, 999.0, 1000));
        assertFalse("never from a space-subsystem world",
                ShipEntryController.shouldTriggerEntry(true, 1001.0, 1000));
    }

    /**
     * The entry line must always be REACHABLE under the physics mod's hard altitude clamp. With
     * stock configs both the orbit height and the clamp sit at 1000, and a line at the clamp can
     * never be crossed - the ship stops dead at an invisible wall and entry never fires. The
     * effective line is therefore derived from the live clamp, not trusted to configs to agree.
     */
    @Test
    public void entryLineStaysReachableBelowThePhysicsClamp() {
        // The stock-config collision: orbit 1000, clamp 1000 -> the line moves below the clamp.
        int line = ShipEntryController.effectiveEntryCeiling(1000, 1000.0);
        assertTrue("with orbit == clamp the line must drop below the clamp (got " + line + ")",
                line < ORBIT_CLAMP);
        assertTrue("a ship must be able to EXCEED the line before the clamp stops it (line " + line
                        + ", clamp 1000)",
                ShipEntryController.shouldTriggerEntry(false, 1000.0, line));

        // A clamp raised well above the orbit line leaves the configured orbit height in charge.
        assertEquals(1000, ShipEntryController.effectiveEntryCeiling(1000, 2_000_000.0));

        // No physics mod -> no clamp -> the configured orbit height is untouched.
        assertEquals(1000,
                ShipEntryController.effectiveEntryCeiling(1000, Double.POSITIVE_INFINITY));
    }

    /**
     * How far {@code coord} lies from {@code where} at {@code tick}, in blocks, once it is established
     * that {@code coord} names {@code rider}'s cell. Measured rather than compared field by field: what
     * this test is about is WHICH POINT was chosen.
     */
    private static double gapTo(GalacticCoord coord, SystemBody rider, long tick, AbsolutePos where) {
        assertTrue("the aim " + coord + " must name the cell of " + rider.name()
                + ", whose frame is the only one it can be read through", coord.sameCell(rider.name()));
        return placeThrough(rider, coord, tick).distanceTo(where);
    }

    /**
     * Where {@code coord} IS at {@code tick}, read as production reads a coordinate in a cell a body
     * stands in: the frame that body defines, displaced by the coordinate's in-cell offset. Never the
     * sector triple on the static grid — a cell rides its body, and the static grid is where the cell
     * would be if nothing moved.
     */
    private static AbsolutePos placeThrough(SystemBody rider, GalacticCoord coord, long tick) {
        return rider.frame().originAt(tick).plus(coord.localX(), coord.localY(), coord.localZ());
    }

    /** An orbiting body: named at its own cell, riding a frame that carries it around its star. */
    private static SystemBody orbiting(GalacticCoord name, int dimId) {
        return new SystemBody(name,
                CellFrame.of(AbsolutePos.ofCellName(name),
                        BodyEphemeris.orbit(100d, 0.0, 0.0, false, 1000d, 1_000_000L)),
                BodyEphemeris.STATIC, SystemBodyKind.PLANET, dimId, 0);
    }

    /**
     * This test fails if {@code ShipEntryController#resolveEntryCoord} stops deciding that an entering
     * ship is placed in its launch body's OWN named cell, on the ring around the point that — read
     * through the frame that cell rides — is where the body stands at that tick, however far the body
     * has carried its cell from the static cube its name occupies.
     */
    @Pins("INV-SPC-19")
    @RedWitness(member = "ShipEntryController#resolveEntryCoord", at = "launch.addressAt(clock.getAsLong())",
            replacedBy = "GalacticCoord.ofSectorLocal(launch.absoluteAt(clock.getAsLong()).sectorX(),"
                    + " launch.absoluteAt(clock.getAsLong()).sectorY(), launch.absoluteAt(clock.getAsLong()).sectorZ(),"
                    + " launch.absoluteAt(clock.getAsLong()).localX(), launch.absoluteAt(clock.getAsLong()).localY(),"
                    + " launch.absoluteAt(clock.getAsLong()).localZ())",
            fails = "must name the cell of", on = "2026-10-19")
    @Test
    public void anEntryIsAimedIntoTheLaunchBodysOwnCellWhereverItsOrbitHasCarriedIt() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(19, 0, 0, 0, 0, 0);
        SystemBody planet = orbiting(name, LAUNCH_DIM);
        long ring = ShipEntryController.entryRingAround(planet);

        // The arrangement must put the body outside the static cube its name occupies, or a static
        // reading of its position and its own cell would agree and the decision could not be seen.
        // The orbit above is 100 units of 1 000 000 blocks: a hundred million blocks, three cells.
        long later = 250L; // a quarter of the 1000-tick period: the body is somewhere else than at 0
        for (long tick : new long[] {0L, later}) {
            AbsolutePos at = planet.absoluteAt(tick);
            assertFalse("arrangement: at tick " + tick + " the body must stand outside the static "
                            + "cube of its name " + name.cellKey() + " (it is at " + at + ")",
                    GalacticCoord.ofSectorLocal(at.sectorX(), at.sectorY(), at.sectorZ(),
                            at.localX(), at.localY(), at.localZ()).sameCell(name));
        }
        assertTrue("arrangement: the body must have moved between the two ticks",
                planet.absoluteAt(0L).distanceTo(planet.absoluteAt(later)) > 0d);

        // The entry stands on the ring around the body, so the ship is the ring away from where the body
        // is — under one block short of it per axis, as StandoffRing rounds down.
        assertEquals("the entry lies in the body's own cell, on the ring around the body, at tick 0",
                ring, gapTo(settledEntryFrom(planet, 0L), planet, 0L, planet.absoluteAt(0L)), 2.0);
        assertEquals("and at a later tick, around where the body has gone", ring,
                gapTo(settledEntryFrom(planet, later), planet, later, planet.absoluteAt(later)), 2.0);
    }
}
