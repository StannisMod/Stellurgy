package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.*;
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

    @Test
    public void entryCrossesSettlesInLedgerOutsideTheDescentRadius() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

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
        assertTrue("ring distance " + distance + " must exceed the descent radius",
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
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

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
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ops.failCross = true;
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

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
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ops.teleportFailCount = 2; // re-assembly queryable on the third tick
        ops.reseatFailCount = 1;   // seats resolve one tick after that
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

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
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

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
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

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
     * How far an entry was put from where its body stands, in blocks, at {@code tick} — read through the
     * same frames the placement was given, so the verdict is about WHICH POINT was chosen in the cell.
     */
    private static double entryToBody(GalacticCoord entry, SystemBody body, CellFrames frames, long tick) {
        return frames.absoluteOf(entry, tick).distanceTo(body.absoluteAt(tick));
    }

    /** The galactic cell every placement below is about, and the name its bodies share. An input. */
    private static final long CELL_SECTOR = 19L;

    /** A dimension that is no body's surface: a star's, and a stranger's launch. An input. */
    private static final int NO_SURFACE = -1;

    /**
     * The star's radius, Earth radii: the Sun's, 109 — so its own ring is millions of blocks, as at the
     * address a launch planet shares with its star. An input.
     */
    private static final double STAR_RADIUS_EARTHS = 109.0D;

    /** The launch planet's radius, Earth radii: Earth's. An input. */
    private static final double PLANET_RADIUS_EARTHS = 1.0D;

    /**
     * How far, blocks, an entry may lie off its ring and still be ON it: the arrival radius of a
     * {@code GO_TO}, the maintainer's acceptance of 2026-10-21 ("ends within 2 blocks of it") — an entry
     * is on its body's orbit when a craft put there has arrived at that orbit.
     */
    private static final double ON_THE_RING = 2.0D;

    /**
     * An orbit's distance unit, blocks, and the two orbits drawn in it: the ridden planet 4 million blocks
     * from its cell's name — the order the overworld was measured to stand from its own (2026-10-23) — and
     * the offset planet one million from its still cell's centre, at a base angle and inclined, so it lies
     * off the cell's plane. Inputs.
     */
    private static final long ORBIT_UNIT_BLOCKS = 1_000_000L;
    private static final double RIDDEN_ORBIT_UNITS = 4.0D;
    private static final double OFFSET_ORBIT_UNITS = 1.0D;
    private static final double OFFSET_BASE_ANGLE = 0.3D;
    private static final double OFFSET_INCLINATION_DEGREES = 10.0D;

    /**
     * How many of the planet's rings apart counts as FAR, for the arrangement's two premises — the planet
     * from its cell's name, the star's ring against the planet's: an order of magnitude, so a placement that
     * took the wrong point or the wrong ring cannot land within {@link #ON_THE_RING} of the right one.
     */
    private static final long FAR = 10L;

    /** How many bearings a ring seed can select: one per value of its low byte. An input domain, swept whole. */
    private static final int BEARINGS = 256;

    /** An orbit's period, ticks, and a quarter of it: a tick at which an orbiting body stands elsewhere
     *  than at the epoch, and not back where it began. Inputs. */
    private static final double PERIOD_TICKS = 1000D;
    private static final long A_QUARTER_ON = 250L;

    /** The star at {@code name}: still, and with a ring far wider than any planet's. */
    private static SystemBody star(GalacticCoord name) {
        return new SystemBody(name, CellFrame.staticAt(name), BodyEphemeris.STATIC, SystemBodyKind.STAR,
                NO_SURFACE, 0, SystemBody.ORBIT_UNKNOWN, STAR_RADIUS_EARTHS);
    }

    /** A planet named at {@code name} on {@code frame}, standing {@code inCell} off that frame's origin. */
    private static SystemBody planet(GalacticCoord name, CellFrame frame, BodyEphemeris inCell, int dimId) {
        return new SystemBody(name, frame, inCell, SystemBodyKind.PLANET, dimId, 0, SystemBody.ORBIT_UNKNOWN,
                PLANET_RADIUS_EARTHS);
    }

    /**
     * This test fails if production breaks the contract that an entering ship is put in the cell its launch
     * body's address names, on that body's OWN entry ring about where the body stands in that cell's frame
     * (cells-and-crossings INV-SPC-19). It visits: a cell that rides the launch planet while the planet's
     * absolute position is millions of blocks from its cell's name; a still cell in which the planet stands
     * at an inclined in-cell offset; both at the epoch and a quarter-period on, at every one of the 256
     * bearings a ship id can select, with a star sharing the address whose ring is far wider; and an
     * address with no body, or with bodies none of which is the launch body. What it does not see: that
     * production hands it the registry's bodies and frames at the space clock's tick — that wiring is seen
     * where a taken-off craft arrives at its orbit (ship-guidance INV-GDN-17).
     */
    @dev.stannismod.stellurgy.test.contract.Pins("INV-SPC-19")
    @dev.stannismod.stellurgy.test.contract.RedWitness(verdict = "in its cell",
            member = "ShipEntryController#entryPoint", at = "GalacticCoord cell = address.cellCentre();",
            replacedBy = "GalacticCoord cell = address.cellCentre().plusLocal(GalacticCoord.CELL, 0L, 0L).cellCentre();",
            fails = "an entry is put in the cell its launch body's address names", on = "2026-10-23")
    @dev.stannismod.stellurgy.test.contract.RedWitness(verdict = "about the body in the cell's frame",
            member = "ShipEntryController#entryPoint", at = "frames.positionInCell(cell, b.absoluteAt(tick), tick)",
            replacedBy = "CellFrames.STATIC.positionInCell(cell, b.absoluteAt(tick), tick)",
            fails = "an entry is put on its launch body's own ring", on = "2026-10-23")
    @dev.stannismod.stellurgy.test.contract.RedWitness(verdict = "at the body's own ring",
            member = "ShipEntryController#entryPoint", at = "entryRingAround(b), seed);",
            replacedBy = "entryRingAround(java.util.Collections.max(atAddress,"
                    + " java.util.Comparator.comparingLong(ShipEntryController::entryRingAround))), seed);",
            fails = "an entry is put on its launch body's own ring", on = "2026-10-23")
    @dev.stannismod.stellurgy.test.contract.RedWitness(verdict = "off the name, with no body",
            member = "ShipEntryController#entryPoint",
            at = "return StandoffRing.pointAround(address, ENTRY_RING_BLOCKS, seed);", replacedBy = "return address;",
            fails = "with no launch body at the address, an entry is put the flat ring off the name", on = "2026-10-23")
    @Test
    public void anEntryIsPutOnItsLaunchBodysOwnRingInTheCellItEnters() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(CELL_SECTOR, 0L, 0L, 0L, 0L, 0L);
        SystemBody star = star(name);

        // A cell that RIDES its launch planet: the planet orbits millions of blocks from the cell's name, and
        // the cell's frame origin goes with it.
        SystemBody ridden = planet(name, CellFrame.of(AbsolutePos.ofCellName(name),
                BodyEphemeris.orbit(RIDDEN_ORBIT_UNITS, 0.0D, 0.0D, false, PERIOD_TICKS, ORBIT_UNIT_BLOCKS)),
                BodyEphemeris.STATIC, LAUNCH_DIM);
        CellFrames ridesThePlanet = (cell, tick) -> cell.sameCell(name)
                ? ridden.frame().originAt(tick) : AbsolutePos.ofCellName(cell);
        // A STILL cell in which the launch planet stands at an inclined offset from the centre.
        SystemBody offset = planet(name, CellFrame.staticAt(name),
                BodyEphemeris.orbit(OFFSET_ORBIT_UNITS, OFFSET_BASE_ANGLE, OFFSET_INCLINATION_DEGREES, false,
                        PERIOD_TICKS, ORBIT_UNIT_BLOCKS), LAUNCH_DIM);

        for (long tick : new long[]{0L, A_QUARTER_ON}) {
            for (Object[] arm : new Object[][]{{ridden, ridesThePlanet}, {offset, CellFrames.STATIC}}) {
                SystemBody planet = (SystemBody) arm[0];
                CellFrames frames = (CellFrames) arm[1];
                List<SystemBody> atAddress = new ArrayList<>();
                atAddress.add(star);
                atAddress.add(planet);
                long ring = ShipEntryController.entryRingAround(planet);
                dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                        "the planet must stand far from its cell's name, and the star at its address must ring far "
                                + "wider than it (ring " + ring + ", tick " + tick + "): " + planet,
                        planet.absoluteAt(tick).distanceTo(AbsolutePos.ofCellName(name)) > FAR * ring
                                && ShipEntryController.entryRingAround(star) > FAR * ring);
                for (int seed = 0; seed < BEARINGS; seed++) {
                    GalacticCoord entry = ShipEntryController.entryPoint(atAddress, LAUNCH_DIM, tick,
                            planet.addressAt(tick), frames, seed);
                    String which = planet + " tick " + tick + " seed " + seed + " -> " + entry;
                    assertTrue("an entry is put in the cell its launch body's address names: " + which,
                            entry.sameCell(name));
                    double distance = entryToBody(entry, planet, frames, tick);
                    assertTrue("an entry is put on its launch body's own ring, " + ring + " blocks (within "
                                    + ON_THE_RING + "), not " + distance + ": " + which,
                            Math.abs(distance - ring) <= ON_THE_RING);
                }
            }
        }

        // No body at the address — an unplaced launch, the config home anchor — or bodies none of which is the
        // launch body: the name is the only place there is, and the ship is put the flat ring off it.
        List<SystemBody> strangers = new ArrayList<>();
        strangers.add(star);
        for (List<SystemBody> nobody : java.util.Arrays.asList(new ArrayList<SystemBody>(), strangers)) {
            GalacticCoord entry = ShipEntryController.entryPoint(nobody, LAUNCH_DIM, A_QUARTER_ON, name,
                    CellFrames.STATIC, 0);
            double distance = entry.staticFrameDistanceTo(name);
            assertTrue("with no launch body at the address, an entry is put the flat ring off the name, "
                            + ShipEntryController.ENTRY_RING_BLOCKS + " blocks, not " + distance + " (" + nobody + ")",
                    Math.abs(distance - ShipEntryController.ENTRY_RING_BLOCKS) <= ON_THE_RING);
        }
    }
}
