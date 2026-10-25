package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.AssembledCraft;
import dev.stannismod.stellurgy.test.CellInfo;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.OwnShip;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.Window;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A craft flying the motion intent its flight computer was handed, in a running game: it arrives
 * alongside the ship it was sent to and stops, outside both hulls; a hull with no reverse thrust turns
 * over and arrives too; a hull with no thrust at all is refused; a target in another system is refused
 * as needing a jump and the craft hovers; an order whose course crosses a cell face survives the
 * crossing; on a planet a craft sent to a point of the surface lands there, a hull that pushes along
 * heave alone by leaning into its thrust; and an order for another world of the system is flown across
 * the crossings between — up from the overworld to a ship in space, and down from space to a point of
 * the overworld (ship-guidance INV-GDN-01, -03, -07..10, -14, -20, -21).
 *
 * <p>NEW-GROUP: guidance — the flight computer flying an ORDER with nobody at the stick. No existing
 * group holds it: the flight-model group measures what a hull can do under a command a probe writes
 * straight into the controller, and the crossing groups move a craft by their own hand; here the
 * command is guidance's, and the subject is the decision it makes from the order.</p>
 *
 * <h2>Where they fly, and why there</h2>
 *
 * <p>The space scenarios each take a cell of their own with no body standing in it — asserted before
 * anything is built — and build their crafts there. Such a cell has no primary to ride, so its frame
 * stands still, and a space cell's field is zero: no terrain under the approach and no weight to hold
 * while braking. The landing scenario flies in the overworld, over a platform it lays itself in the
 * open-air band, so the ground it lands on is ground the test knows the height of. The crossing scenarios
 * take off from the overworld, under an orbit line they lower and restore. What this does NOT
 * see: a planet's air; a craft crossing a ZONE's sphere rather than a galactic face; a target ship that
 * moves while it is approached; a hull refused for its weight; and any player — the order enters
 * through the computer's own entry by probe, because no player surface issues one yet.</p>
 *
 * <p>The order is ARRANGED (the slot is filled through the computer's own {@code issueMotion}); the act
 * under test is the computer's tick, which nothing here calls.</p>
 */
public class MotionIntentGroupTest extends AbstractSharedServerTest {

    /**
     * How far past the launchpad's footprint a craft's site reaches: the fixture stands its structure
     * tower one block west of the pad and its assembler one block north (the fixture verb's own layout).
     */
    private static final int HALO = 1;

    /** How far above the site the fixture reaches: its structure tower, six courses above the pad. */
    private static final int HEIGHT = 7;

    /**
     * The deadline of the arrival and refusal LINKS, in server ticks. Expiry means the record never
     * came. A two-hundred-block flight at the craft's 40 blocks per engine second, a turnover and the
     * braking included, is under fifteen engine seconds; the physics clock keeps wall time while the
     * game's may lag behind it, so in game ticks the flight is at most three hundred. Four times that.
     */
    private static final int FLIGHT_LINK_TICKS = 1200;

    /** The same for a flight that crosses a cell face, which adds the cut, the paste and the settle. */
    private static final int CROSSING_LINK_TICKS = 2400;

    /**
     * Where the target ship is put from where the craft starts, blocks: 200 blocks off, as the
     * acceptance states it — mostly along +X and partly up, so the craft leaves its launchpad by rising
     * away from it rather than sliding over it (192² + 56² = 200²).
     */
    private static final long ALONG_X = 192L;
    private static final long UP = 56L;

    /** The arrival a player is promised: "within 2 blocks ... at below 0.2 blocks/s" (ruling 2026-10-21);
     *  the radius is also what the standoff keeps past the two hulls (same day). */
    private static final double ARRIVED_WITHIN = 2.0D;
    private static final double ARRIVED_SLOWER_THAN = 0.2D;

    /** How soon an order that cannot be flown must be refused: "within 10 s" (ruling 2026-10-21), in
     *  server ticks at twenty a second. */
    private static final long REFUSED_WITHIN_TICKS = 200L;

    /** Server ticks in one second of the game's clock, for a speed read off a window of ticks. */
    private static final double TICKS_PER_SECOND = 20.0D;

    /**
     * The window a hover is judged over, server ticks: two seconds, in which the cruise the pilot left
     * (an input, {@link #LEFT_CRUISE}) would carry a craft not told to hover twenty blocks.
     */
    private static final int HOVER_WINDOW_TICKS = 40;

    /**
     * The window "at rest" is judged over, server ticks: one second of the game's clock. The physics
     * substrate steps several times per game tick, so a rocking on a contact or on the actuators — a few
     * steps per swing — averages over many of its own periods; and a craft really moving at the arrival
     * speed covers a fifth of a block in it, far above what a pose read resolves.
     */
    private static final int AT_REST_WINDOW_TICKS = 20;

    /** The forward cruise, blocks per second, the craft's pilot is arranged to have left it; an input. */
    private static final double LEFT_CRUISE = 10.0D;

    /** How far the target ship stands beyond the point the seam sets a carried craft down; an input. */
    private static final long BEYOND_THE_SEAM = 150L;

    /**
     * How near its own surface a landed hull's lowest point must be, blocks: in the layer directly on
     * top of the ground. Half a block is the world's own resolution — a lowest point within it of the
     * ground's top is nearer that top than any other block face.
     */
    private static final double ON_THE_GROUND = 0.5D;

    /** The landing platform, an input: its half-width, how far below the craft's site it lies, how far
     *  out along X it stands, and where the craft is put to start from — above it and off to one side. */
    private static final int PLATFORM_HALF = 5;
    private static final int PLATFORM_DROP = 12;
    private static final int PLATFORM_OUT = 30;
    private static final int START_BACK = 16;
    private static final int START_ABOVE = 30;

    /**
     * The heave-only landing, its inputs. Sideways: how far off along X the platform's column stands from
     * the craft's site — several hull lengths, all of it only a lean can cover, and inside the plot (whose
     * fixture stands {@code Plot.FIXTURE_INSET} in from one edge of {@code Plot.SIZE}). Below: how far
     * under the site the platform lies, so the last of the way is down onto it. Half: the platform's
     * half-width — the arrival radius plus the half-width of the hull's footprint, so a craft landing
     * anywhere it may count as there stands on stone. Park above: where the craft is lifted to before the
     * order, clear of its own pad and tower (six courses) by ten blocks.
     */
    private static final int LEAN_SIDEWAYS = 24;
    private static final int LEAN_PLATFORM_BELOW = 8;
    private static final int LEAN_PLATFORM_HALF = 5;
    private static final int LEAN_PARK_ABOVE = 16;

    /**
     * The deadline of the heave-only landing's link, server ticks; expiry means the record never came.
     * Measured 2026-10-22 (logs/t402b-server16-trace.txt, the craft's pose read every 40 ticks): it overshot its
     * column by six blocks, crept back, and was over the column letting down 1 320 ticks after the order.
     * At 6 000 the link expired once in seven runs (logs/t402b-server-witness-replay.log, under an inversion
     * that cannot act before the arrival); loosened to twice that — and expired once more at 12 000
     * (logs/t402b-inv-rest2.log, the same kind of inversion), so the expiry is not a slow box but a landing
     * that sometimes does not come. Traced 2026-10-22 over six runs (logs/hv1-1..6-trace.txt): the physics
     * substrate integrates no rotation slower than 0.0316 rad/s, so the hull's lean freezes wherever its
     * last larger turn stopped, and the craft creeps across its column at the speed that frozen lean
     * gives — 0.010 to 0.064 blocks/s in the runs that landed, 0.125 in the one that crossed the column
     * before it was down and cycled about it. A defect of the substrate, not of this budget. Fixed in the
     * substrate 2026-10-23 (it now turns a hull at any non-zero rate): twenty runs since, ten with guidance
     * rounding its leans and ten without, every one landed (logs/t846a-summary.txt, logs/t846b-summary.txt).
     * The number itself was not re-sized from those runs.
     */
    private static final int LEAN_LINK_TICKS = 12000;

    /**
     * The cells the space scenarios fly in, one each (the crossing scenario takes the next one too):
     * sectors far from every body of the shipped universe, each asserted to hold no body before it is
     * used.
     */
    private static final long CELL_ARRIVES = 40_021L;
    private static final long CELL_TURNS_OVER = 40_023L;
    private static final long CELL_CROSSES = 40_025L;
    private static final long CELL_NO_THRUST = 40_027L;

    /**
     * The cell a craft is sent from to a planet of ANOTHER system: a sector past the neighbourhood of the
     * overworld's system, which reaches half a system spacing ({@code UniverseScale.DEFAULT_SPACING_CELLS})
     * to each side of its anchor — the cells above lie inside it (measured 2026-10-24: a craft in sector
     * 40 029 sent to the overworld was not refused, logs/t616-server1.log; sector 100 000 029 resolved to
     * a procedural system, logs/t616-server2.log). An input: that its system is not the overworld's is
     * asserted before it is used.
     */
    private static final long CELL_ELSEWHERE = 100_000_029L;

    /**
     * How far apart, along one axis, the two sites of one plot stand when each is {@code FIXTURE_INSET} in
     * from its own edge: the room the plot leaves, 64 − 2 × 20 = 24 blocks — several of a fixture's
     * {@code FixtureSite.PAD}-wide footprints.
     */
    private static final int SITES_APART = Plot.SIZE - 2 * Plot.FIXTURE_INSET;

    /**
     * The deadline of a climb's LINKS, in server ticks: from the open-air band (y 150) past the lowest
     * line production accepts (255) — some 115 blocks, a few engine seconds at the craft's 40 blocks a
     * second — then the entry's cut, paste and settle, and, for an order there, its arrival where the
     * entry put the craft. Under 600 ticks; four times that. Expiry means the record never came.
     */
    private static final int CLIMB_LINK_TICKS = 2400;

    /**
     * The deadline of the link from a descent to the landing, in server ticks: the descent sets a craft
     * down in the air over the world's spawn and the platform is in this class's plot, on the default lane
     * some 4 000 blocks out along both axes — under 6 000 blocks at 40 a second, 150 engine seconds, 3 000
     * ticks if the game keeps the physics clock's pace. Four times that. Expiry means no landing came.
     */
    private static final int DESCENT_LINK_TICKS = 12_000;

    /**
     * How far outside its body's descent shell the craft sent down from space is put, blocks: the 200
     * blocks off the maintainer stated for a GO_TO (2026-10-21) — a flight guidance must accelerate,
     * close and slow on its own.
     */
    private static final double OUTSIDE_THE_SHELL = 200.0D;

    /** How far beside where the craft entered the target is moved, blocks: the same 200 blocks. */
    private static final double BESIDE_THE_ENTRY = 200.0D;

    /**
     * How near the ring the ledger may read a craft the entry has just put on it, blocks: the arrival radius
     * (2) within which its orbit order holds it there, plus the ledger's whole-block
     * rounding of the craft's position and of the body's address, under √3 each (3.46).
     */
    private static final double RING_READ_WITHIN = 6.0D;

    @Test
    @Pins("INV-GDN-01")
    @RedWitness(verdict = "arrival", member = "TileAdvancedFlightComputer#flyMotionIntent",
            at = "if (step.event() == dev.stannismod.stellurgy.ship.guidance.MotionSlot.Event.ARRIVED) {",
            replacedBy = "if (step.event() == null) {", fails = "INV-GDN-01 arrival", on = "2026-10-21")
    @RedWitness(verdict = "outside both hulls", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "clearance",
            fails = "INV-GDN-01 outside both hulls", on = "2026-10-21")
    @RedWitness(verdict = "standoff", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "placed.keepOut() + 3.0D * clearance",
            fails = "INV-GDN-01 standoff", on = "2026-10-21")
    @RedWitness(verdict = "rest", member = "MotionSlot#fly",
            at = "magnitude(craft.velocity) <= ARRIVAL_SPEED", replacedBy = "magnitude(craft.velocity) >= 0.0D",
            fails = "INV-GDN-01 rest", on = "2026-10-21")
    public void anOrdinaryHullToldToGoToAShipEndsAlongsideItAtRest() throws Exception {
        // R11-OK: MotionSlot#fly's approach and arrival, asserted in assertAlongside.
        flyToAShipBeside(CELL_ARRIVES, "with-pilot-seat", "INV-GDN-01");
    }

    @Test
    @Pins("INV-GDN-07")
    @RedWitness(verdict = "arrival", member = "Plan#of",
            at = "Heading brake = Heading.best(craft, negate(u));",
            replacedBy = "Heading brake = new Heading(close.attitude, reach(craft.live, close.attitude, negate(u), craft.gravity));",
            fails = "INV-GDN-07 arrival", on = "2026-10-21")
    @RedWitness(verdict = "outside both hulls", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "clearance",
            fails = "INV-GDN-07 outside both hulls", on = "2026-10-21")
    @RedWitness(verdict = "standoff", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "placed.keepOut() + 3.0D * clearance",
            fails = "INV-GDN-07 standoff", on = "2026-10-21")
    @RedWitness(verdict = "rest", member = "MotionSlot#fly",
            at = "magnitude(craft.velocity) <= ARRIVAL_SPEED", replacedBy = "magnitude(craft.velocity) >= 0.0D",
            fails = "INV-GDN-07 rest", on = "2026-10-21")
    public void aHullWithNoReverseThrustTurnsOverAndArrives() throws Exception {
        // R11-OK: MotionSlot#fly's turnover plan and arrival, asserted in assertAlongside.
        flyToAShipBeside(CELL_TURNS_OVER, "no-reverse-hull", "INV-GDN-07");
    }

    @Test
    @Pins("INV-GDN-08")
    @RedWitness(verdict = "refused at all", member = "TileAdvancedFlightComputer#flyMotionIntent",
            at = "} else if (step.event() == dev.stannismod.stellurgy.ship.guidance.MotionSlot.Event.REFUSED) {",
            replacedBy = "} else if (step.event() == null) {", fails = "INV-GDN-08 refused at all", on = "2026-10-21")
    @RedWitness(verdict = "reason", member = "Plan#of",
            at = "if (!(close.reach > 0.0D)) {", replacedBy = "if (false) {",
            fails = "INV-GDN-08 reason", on = "2026-10-21")
    @RedWitness(verdict = "within", member = "MotionSlot#fly",
            at = "shortTicks >= REFUSAL_WINDOW_TICKS", replacedBy = "shortTicks >= 10 * REFUSAL_WINDOW_TICKS",
            fails = "INV-GDN-08 within", on = "2026-10-21")
    @RedWitness(verdict = "marked refused", member = "MotionSlot#fly",
            at = "state = State.REFUSED;", replacedBy = "state = State.ACTIVE;",
            fails = "INV-GDN-08 marked refused", on = "2026-10-21")
    public void aHullWithNoThrustIsRefused() throws Exception {
        int slotDim = emptyCell(CELL_NO_THRUST, "the cell a hull with no thrust is ordered in");
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        try {
            AssembledCraft craft = build(plot().inDimension(slotDim).site(), "hull-without-actuators",
                    "a hull with no thrust");
            built.add(craft.durable);
            Reply directions = Reply.of(Reply.of(read("stellurgytest vs flight-model-by-id " + slotDim + " "
                    + craft.physicsId).object("model")).object("directions"));
            for (String d : new String[]{"SURGE", "SWAY", "HEAVE"}) {
                for (String sense : new String[]{"_POSITIVE", "_NEGATIVE"}) {
                    Reply live = Reply.of(Reply.of(directions.object(d + sense)).object("live"));
                    requireArranged("the hull must have no thrust in any direction: " + d + sense + " " + live,
                            live.number("sustained") == 0.0D && live.number("burst") == 0.0D);
                }
            }
            AssembledCraft target = build(plot().inDimension(slotDim).siteAt(20, 44), "with-pilot-seat",
                    "the ship it is sent to");
            built.add(target.durable);
            ShipInfo start = ShipInfo.byId(c -> read(c).raw(), slotDim, craft.physicsId);
            park(target, Math.round(start.x) + ALONG_X, Math.round(start.y) + UP, Math.round(start.z));

            long mark = events.mark();
            Reply issued = arrange("stellurgytest vs motion-go-to-ship-by-id " + slotDim + " " + craft.physicsId
                    + " " + target.durable);
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));
            long issuedAt = issued.longInteger("worldTick");

            String refused = events.awaitRecordWithFields(mark, "motion_refused",
                    "INV-GDN-08 refused at all — the order must be refused", FLIGHT_LINK_TICKS, "ship",
                    craft.durable);
            assertEquals("INV-GDN-08 reason: refused for want of thrust toward the target: " + refused,
                    "NO_APPROACH_AUTHORITY", Events.text(refused, "reason"));
            long took = (long) Events.number(refused, "tick") - issuedAt;
            assertTrue("INV-GDN-08 within: refused " + took + " ticks after the order, not within "
                    + REFUSED_WITHIN_TICKS + ": " + refused, took <= REFUSED_WITHIN_TICKS);
            Reply slot = read("stellurgytest vs motion-read-by-id " + slotDim + " " + craft.physicsId);
            assertEquals("INV-GDN-08 marked refused: the slot holds the order marked refused: " + slot,
                    "REFUSED", slot.text("state"));
        } finally {
            dispose(events, buildMark, built);
        }
    }

    @Test
    @Pins("INV-GDN-09")
    @RedWitness(verdict = "refused at all", member = "TileAdvancedFlightComputer#flyMotionIntent",
            at = "} else if (step.event() == dev.stannismod.stellurgy.ship.guidance.MotionSlot.Event.REFUSED) {",
            replacedBy = "} else if (step.event() == null) {", fails = "INV-GDN-09 refused at all", on = "2026-10-21")
    @RedWitness(verdict = "reason", member = "MotionSlot#refusalFor",
            at = "return Refusal.NEEDS_A_JUMP;", replacedBy = "return Refusal.TARGET_NOT_FOUND;",
            fails = "INV-GDN-09 reason", on = "2026-10-24")
    @RedWitness(verdict = "of another system", member = "WorldSurroundings#sphereAbout",
            at = "if (!systemOf(body.get().name()).equals(systemHere())) {", replacedBy = "if (false) {",
            fails = "INV-GDN-09 refused at all", on = "2026-10-24")
    @RedWitness(verdict = "within", member = "MotionSlot#fly",
            at = "shortTicks >= REFUSAL_WINDOW_TICKS", replacedBy = "shortTicks >= 10 * REFUSAL_WINDOW_TICKS",
            fails = "INV-GDN-09 within", on = "2026-10-21")
    @RedWitness(verdict = "hovers", member = "MotionSlot#holdsAnIntent",
            at = "return state != State.EMPTY;", replacedBy = "return state == State.ACTIVE;",
            fails = "INV-GDN-09 hovers", on = "2026-10-21")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window.over(",
            why = "a hover is the absence of motion over time; the refusal record says the order was refused,"
                    + " not what the craft does afterwards, and no record is published per tick of hovering")
    public void aTargetInAnotherWorldIsRefusedForACrossingAndTheCraftHovers() throws Exception {
        // A cell of another system than the overworld's: reaching the overworld from it needs the jump, the
        // leg not flown. (The name predates the legs: the crossing it is refused for is that jump.)
        CellInfo elsewhere = CellInfo.atSector(c -> read(c).raw(), CELL_ELSEWHERE, 0L, 0L);
        Reply launch = read("stellurgytest space launch-cell 0").requireOk("read the overworld's cell");
        CellInfo home = CellInfo.atKey(c -> read(c).raw(), launch.text("cellKey"));
        requireArranged("the cell must be of another system than the overworld's: " + elsewhere + " | " + home,
                home.anchor != null && !home.anchor.equals(elsewhere.anchor));
        int slotDim = emptyCell(CELL_ELSEWHERE, "the cell a craft is sent to a planet's surface from");
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        try {
            AssembledCraft craft = build(plot().inDimension(slotDim).site(), "with-pilot-seat",
                    "a craft sent to another world");
            built.add(craft.durable);

            long mark = events.mark();
            Reply issued = arrange("stellurgytest vs motion-go-to-surface-by-id " + slotDim + " " + craft.physicsId
                    + " 0 100 100");
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));
            long issuedAt = issued.longInteger("worldTick");
            // The cruise its pilot left it: what the craft would fly if a refusal handed it back to its
            // unmanned hold.
            Reply cruise = arrange("stellurgytest vs ff-cruise-by-id " + slotDim + " " + craft.physicsId + " "
                    + LEFT_CRUISE + " 0 0");
            requireArranged("the craft must be left a cruise: " + cruise,
                    cruise.bool("afcResolved") && cruise.number("cruiseForward") == LEFT_CRUISE);

            String refused = events.awaitRecordWithFields(mark, "motion_refused",
                    "INV-GDN-09 refused at all — the order must be refused", FLIGHT_LINK_TICKS, "ship",
                    craft.durable);
            assertEquals("INV-GDN-09 reason: refused as needing a jump: " + refused, "NEEDS_A_JUMP",
                    Events.text(refused, "reason"));
            long took = (long) Events.number(refused, "tick") - issuedAt;
            assertTrue("INV-GDN-09 within: refused " + took + " ticks after the order, not within "
                    + REFUSED_WITHIN_TICKS + ": " + refused, took <= REFUSED_WITHIN_TICKS);

            Reply kept = read("stellurgytest vs ff-cruise-read-by-id " + slotDim + " " + craft.physicsId);
            requireArranged("the cruise the pilot left must still be there to be flown: " + kept,
                    kept.bool("afcResolved") && kept.number("cruiseForward") == LEFT_CRUISE);
            Window<ShipInfo> hover = Window.over(() -> ShipInfo.byId(c -> read(c).raw(), slotDim, craft.physicsId),
                    client(), GameTicks.server(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks),
                    HOVER_WINDOW_TICKS);
            double vx = hover.rate(s -> s.x);
            double vy = hover.rate(s -> s.y);
            double vz = hover.rate(s -> s.z);
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz) * TICKS_PER_SECOND;
            assertTrue("INV-GDN-09 hovers: the refused craft moved at " + speed + " blocks/s, not below "
                    + ARRIVED_SLOWER_THAN + ": " + hover, speed < ARRIVED_SLOWER_THAN);
        } finally {
            dispose(events, buildMark, built);
        }
    }

    @Test
    @Pins("INV-GDN-03")
    @RedWitness(verdict = "arrival", member = "TileAdvancedFlightComputer#writeToNBT",
            at = "nbt.setTag(NBT_MOTION, slot);", replacedBy = "",
            fails = "INV-GDN-03 arrival", on = "2026-10-21")
    @RedWitness(verdict = "crossed", member = "WorldSurroundings#inSpaceAt",
            at = "origin[0] + fromHere.dx()",
            replacedBy = "origin[0] + fromHere.dx() - dev.stannismod.stellurgy.space.CellSeam.REENTRY_DEPTH",
            fails = "INV-GDN-03 crossed", on = "2026-10-21")
    @RedWitness(verdict = "outside both hulls", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "clearance",
            fails = "INV-GDN-03 outside both hulls", on = "2026-10-21")
    @RedWitness(verdict = "standoff", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "placed.keepOut() + 3.0D * clearance",
            fails = "INV-GDN-03 standoff", on = "2026-10-21")
    @RedWitness(verdict = "rest", member = "MotionSlot#fly",
            at = "magnitude(craft.velocity) <= ARRIVAL_SPEED", replacedBy = "magnitude(craft.velocity) >= 0.0D",
            fails = "INV-GDN-03 rest", on = "2026-10-21")
    public void aGoToAShipInTheNeighbouringCellCrossesAndArrives() throws Exception {
        int slotDim = emptyCell(CELL_CROSSES, "the cell a craft leaves through its +X face");
        int beyondDim = emptyCell(CELL_CROSSES + 1L, "the neighbour beyond the +X face, where the target is");
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        try {
            AssembledCraft craft = build(plot().inDimension(slotDim).site(), "with-pilot-seat",
                    "a craft about to cross a cell face");
            built.add(craft.durable);
            AssembledCraft target = build(plot().inDimension(beyondDim).site(), "with-pilot-seat",
                    "the ship beyond the face");
            built.add(target.durable);
            settle(craft, CELL_CROSSES, slotDim);
            settle(target, CELL_CROSSES + 1L, beyondDim);

            ShipInfo built0 = ShipInfo.byId(c -> read(c).raw(), slotDim, craft.physicsId);
            long y = Math.round(built0.y);
            long z = Math.round(built0.z);
            double craftReach = hullReach(slotDim, craft.physicsId, "the craft");
            // The target a stated distance past where the seam sets a carried craft down, as a position of
            // the neighbour's own world.
            long targetX = GalacticCoord.HALF_CELL + CellSeam.REENTRY_DEPTH + BEYOND_THE_SEAM - GalacticCoord.CELL;
            park(target, targetX, y, z);
            double targetReach = hullReach(beyondDim, target.physicsId, "the target");

            // Into the band past the +X face where the seam has not yet carried it: a hundred blocks short
            // of the carry margin.
            long poised = GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN - 100L;
            park(craft, poised, y, z);
            ShipInfo start = ShipInfo.byId(c -> read(c).raw(), slotDim, craft.physicsId);
            requireArranged("the craft must stand past the face and short of the carry: " + start,
                    start.x > GalacticCoord.HALF_CELL && start.x < GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN);

            long mark = events.mark();
            Reply issued = arrange("stellurgytest vs motion-go-to-ship-by-id " + slotDim + " " + craft.physicsId
                    + " " + target.durable);
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));

            String arrived = events.awaitRecordWithFields(mark, "motion_arrived",
                    "INV-GDN-03 arrival — the craft must report it got alongside the ship it was sent to",
                    CROSSING_LINK_TICKS, "ship", craft.durable);
            int arrivedIn = (int) Events.number(arrived, "dim");
            assertNotEquals("INV-GDN-03 crossed: the craft arrived without leaving its cell's world: "
                    + arrived, slotDim, arrivedIn);
            String naming = OwnShip.latestNaming(events, buildMark, craft.durable);
            requireArranged("the craft must have been named in the world it arrived in: " + naming,
                    naming != null && (int) Events.number(naming, "dim") == arrivedIn);
            assertAlongside("INV-GDN-03", arrivedIn, Events.text(naming, "ship"), craftReach, target, targetReach,
                    arrived);
        } finally {
            dispose(events, buildMark, built);
        }
    }

    @Test
    @Pins("INV-GDN-10")
    @RedWitness(verdict = "arrival", member = "TileAdvancedFlightComputer#flyMotionIntent",
            at = "if (step.event() == dev.stannismod.stellurgy.ship.guidance.MotionSlot.Event.ARRIVED) {",
            replacedBy = "if (step.event() == null) {", fails = "INV-GDN-10 arrival", on = "2026-10-21")
    @RedWitness(verdict = "on the ground", member = "MotionSlot#rest",
            at = "return FlightCommand.of(null, zeroRate(), quatArray(attitude));",
            replacedBy = "return FlightCommand.of(new double[]{0.0D, 10.0D, 0.0D}, zeroRate(), quatArray(attitude));",
            fails = "INV-GDN-10 on the ground", on = "2026-10-22")
    @RedWitness(verdict = "there", member = "GuidanceTarget#placeIn",
            at = "new double[]{x, ground", replacedBy = "new double[]{x + 5.0D, ground",
            fails = "INV-GDN-10 there", on = "2026-10-21")
    @RedWitness(verdict = "at rest", member = "MotionSlot#rest",
            at = "return FlightCommand.of(null, zeroRate(), quatArray(attitude));",
            replacedBy = "return FlightCommand.of(new double[]{3.0D, 0.0D, 0.0D}, zeroRate(), quatArray(attitude));",
            fails = "INV-GDN-10 at rest", on = "2026-10-21")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<ShipInfo> resting = Window.over(",
            why = "resting is the absence of motion over time after the landing; the arrival record says the"
                    + " craft was let down, not that it stays where it was let down")
    public void aGoToASurfacePointLandsThere() throws Exception {
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        try {
            FixtureSite site = site();
            AssembledCraft craft = build(site, "with-pilot-seat", "a craft that lands on a platform");
            built.add(craft.durable);
            int px = site.x + PLATFORM_OUT;
            int pz = site.z;
            int py = site.y - PLATFORM_DROP;
            int side = 2 * PLATFORM_HALF + 1;
            Reply platform = arrange("stellurgytest fill " + site.dim + " " + (px - PLATFORM_HALF) + " " + py + " "
                    + (pz - PLATFORM_HALF) + " " + (px + PLATFORM_HALF) + " " + py + " " + (pz + PLATFORM_HALF)
                    + " minecraft:stone");
            requireArranged("the landing platform must be laid whole, in open air: " + platform,
                    platform.ok() && platform.integer("placed") == side * side);
            park(craft, px - START_BACK, py + START_ABOVE, pz);

            long mark = events.mark();
            double columnX = px + 0.5D;
            double columnZ = pz + 0.5D;
            Reply issued = arrange("stellurgytest vs motion-go-to-surface-by-id " + site.dim + " " + craft.physicsId
                    + " " + site.dim + " " + columnX + " " + columnZ);
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));

            String arrived = events.awaitRecordWithFields(mark, "motion_arrived",
                    "INV-GDN-10 arrival — the craft must report it landed", FLIGHT_LINK_TICKS, "ship", craft.durable);
            ShipInfo end = ShipInfo.byId(c -> read(c).raw(), site.dim, craft.physicsId);
            Reply bounds = bounds(site.dim, craft.physicsId);
            double groundTop = py + 1.0D;
            double gap = bounds.number("minY") - groundTop;
            assertTrue("INV-GDN-10 on the ground: the hull's lowest point is " + gap + " blocks from the"
                    + " platform's top, not within " + ON_THE_GROUND + ": " + bounds + " | " + arrived,
                    Math.abs(gap) < ON_THE_GROUND);
            double off = Math.hypot(end.x - columnX, end.z - columnZ);
            assertTrue("INV-GDN-10 there: the craft landed " + off + " blocks from the column it was sent to,"
                    + " beyond " + ARRIVED_WITHIN + ": " + end, off <= ARRIVED_WITHIN);
            // Settled is a statement about time: the craft let down onto the ground rocks on its contact for
            // a moment, which one velocity read can catch; what it must not do is go anywhere.
            Window<ShipInfo> resting = Window.over(() -> ShipInfo.byId(c -> read(c).raw(), site.dim,
                    craft.physicsId), client(), GameTicks.server(),
                    ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), HOVER_WINDOW_TICKS);
            double vx = resting.rate(s -> s.x);
            double vy = resting.rate(s -> s.y);
            double vz = resting.rate(s -> s.z);
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz) * TICKS_PER_SECOND;
            assertTrue("INV-GDN-10 at rest: the landed craft moved at " + speed + " blocks/s after it landed: "
                    + resting, speed < ARRIVED_SLOWER_THAN);
        } finally {
            dispose(events, buildMark, built);
        }
    }

    @Test
    @Pins("INV-GDN-14")
    @RedWitness(verdict = "arrival", member = "Plan#approach",
            at = "Lean lean = Lean.of(craft);", replacedBy = "Lean lean = null;",
            fails = "INV-GDN-14 arrival", on = "2026-10-22")
    @RedWitness(verdict = "on the ground", member = "MotionSlot#rest",
            at = "return FlightCommand.of(null, zeroRate(), quatArray(attitude));",
            replacedBy = "return FlightCommand.of(new double[]{0.0D, 10.0D, 0.0D}, zeroRate(), quatArray(attitude));",
            fails = "INV-GDN-14 on the ground", on = "2026-10-22")
    @RedWitness(verdict = "there", member = "GuidanceTarget#placeIn",
            at = "new double[]{x, ground", replacedBy = "new double[]{x + 5.0D, ground",
            fails = "INV-GDN-14 there", on = "2026-10-22")
    @RedWitness(verdict = "at rest", member = "MotionSlot#fly",
            at = "return new Step(rest(heldOr(craft.attitude)), Event.NONE);",
            replacedBy = "return new Step(FlightCommand.of(new double[]{0.0D, 0.3D, 0.0D}, zeroRate(), quatArray(heldOr(craft.attitude))), Event.NONE);",
            fails = "INV-GDN-14 at rest", on = "2026-10-22")
    public void aHeaveOnlyHullLeansIntoItsThrustAndLandsAtAPointToItsSide() throws Exception {
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        try {
            FixtureSite site = site();
            AssembledCraft craft = build(site, "heave-only-hull", "a hull that pushes along heave alone");
            built.add(craft.durable);
            // The precondition the whole scenario rests on: level, this hull can push nowhere across the
            // vertical, so nothing but a lean carries it to the side — and its lift holds its weight with
            // room to lean.
            Reply model = Reply.of(read("stellurgytest vs flight-model-by-id " + site.dim + " " + craft.physicsId)
                    .object("model"));
            Reply directions = Reply.of(model.object("directions"));
            for (String across : new String[]{"SURGE_POSITIVE", "SURGE_NEGATIVE", "SWAY_POSITIVE", "SWAY_NEGATIVE"}) {
                Reply live = Reply.of(Reply.of(directions.object(across)).object("live"));
                requireArranged("the hull must push nowhere across the vertical: " + across + " " + live,
                        live.number("sustained") == 0.0D);
            }
            for (String turn : new String[]{"ROLL_POSITIVE", "ROLL_NEGATIVE", "PITCH_POSITIVE", "PITCH_NEGATIVE"}) {
                Reply live = Reply.of(Reply.of(directions.object(turn)).object("live"));
                requireArranged("the hull must hold a lean either way for as long as it leans: " + turn + " "
                        + live, live.number("sustained") > 0.0D);
            }
            Reply lift = Reply.of(Reply.of(directions.object("HEAVE_POSITIVE")).object("live"));
            double weight = Reply.of(model.object("mass")).number("totalKg") * model.number("gravity");
            requireArranged("the hull must lift more than its weight, " + weight + " N, to have room to lean: " + lift,
                    lift.number("sustained") > weight);

            // Its platform off to the hull's side — along X, the helm's sway axis on an unrotated hull — and
            // below its pad, laid by the test so the ground's height is known; the craft parked high above
            // its pad first, so its way there crosses nothing.
            int px = site.x + LEAN_SIDEWAYS;
            int pz = site.z;
            int py = site.y - LEAN_PLATFORM_BELOW;
            int side = 2 * LEAN_PLATFORM_HALF + 1;
            Reply platform = arrange("stellurgytest fill " + site.dim + " " + (px - LEAN_PLATFORM_HALF) + " " + py
                    + " " + (pz - LEAN_PLATFORM_HALF) + " " + (px + LEAN_PLATFORM_HALF) + " " + py + " "
                    + (pz + LEAN_PLATFORM_HALF) + " minecraft:stone");
            requireArranged("the platform must be laid whole, in open air: " + platform,
                    platform.ok() && platform.integer("placed") == side * side);
            park(craft, site.x, site.y + LEAN_PARK_ABOVE, site.z);

            long mark = events.mark();
            double columnX = px + 0.5D;
            double columnZ = pz + 0.5D;
            Reply issued = arrange("stellurgytest vs motion-go-to-surface-by-id " + site.dim + " " + craft.physicsId
                    + " " + site.dim + " " + columnX + " " + columnZ);
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));

            String arrived = events.awaitRecordWithFields(mark, "motion_arrived",
                    "INV-GDN-14 arrival — the heave-only craft must report it landed", LEAN_LINK_TICKS, "ship",
                    craft.durable);
            ShipInfo end = ShipInfo.byId(c -> read(c).raw(), site.dim, craft.physicsId);
            Reply box = bounds(site.dim, craft.physicsId);
            double gap = box.number("minY") - (py + 1.0D);
            assertTrue("INV-GDN-14 on the ground: the hull's lowest point is " + gap + " blocks from the"
                    + " platform's top, not within " + ON_THE_GROUND + ": " + box + " | " + arrived,
                    Math.abs(gap) < ON_THE_GROUND);
            double off = Math.hypot(end.x - columnX, end.z - columnZ);
            assertTrue("INV-GDN-14 there: the craft landed " + off + " blocks from the column it was sent to,"
                    + " beyond " + ARRIVED_WITHIN + ": " + end, off <= ARRIVED_WITHIN);
            Window<ShipInfo> after = restWindow(site.dim, craft.physicsId);
            double speed = restRate(after);
            assertTrue("INV-GDN-14 at rest: the landed craft moved at " + speed + " blocks/s on average after it"
                    + " landed: " + after, speed < ARRIVED_SLOWER_THAN);
        } finally {
            dispose(events, buildMark, built);
        }
    }

    /**
     * INV-GDN-20: a craft on the overworld told {@code GO_TO} a ship in space climbs, enters space with
     * the order in its slot, and arrives alongside the ship there, as INV-GDN-01 measures an arrival.
     *
     * <p>The target gets into space by its own take-off (the pilot's key, the probe's {@code space
     * auto-takeoff}), and is then taken back by its pilot and left holding station, so it stays where it
     * is put. Both crafts enter onto their body's entry ring at a bearing each one's own name picks — up to
     * a whole ring, a hundred thousand blocks, apart — so once the craft has entered, the target is moved
     * beside it: that flight in space would be INV-GDN-01's, at a length no scenario can afford. What is
     * ARRANGED: the overworld's orbit line, lowered to the lowest one production accepts so the climbs are
     * short; the target's place in space. What is the subject: the craft's computer flying one order across
     * the line, through the entry, to the ship — nothing here touches it after the order.</p>
     */
    @Test
    @Pins("INV-GDN-20")
    @RedWitness(verdict = "enters", member = "GuidanceTarget#placeIn",
            at = "if (seen.isInSpace()) {",
            replacedBy = "if (seen.isInSpace()) { return Placement.unplaced(Unplaced.NOT_FOUND); } if (false) {",
            fails = "INV-GDN-20 enters", on = "2026-10-24")
    @RedWitness(verdict = "arrival", member = "TileAdvancedFlightComputer#writeToNBT",
            at = "nbt.setTag(NBT_MOTION, slot);", replacedBy = "",
            fails = "INV-GDN-20 arrival", on = "2026-10-24")
    @RedWitness(verdict = "outside both hulls", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "clearance",
            fails = "INV-GDN-20 outside both hulls", on = "2026-10-24")
    @RedWitness(verdict = "standoff", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "placed.keepOut() + 3.0D * clearance",
            fails = "INV-GDN-20 standoff", on = "2026-10-24")
    @RedWitness(verdict = "rest", member = "MotionSlot#fly",
            at = "magnitude(craft.velocity) <= ARRIVAL_SPEED", replacedBy = "magnitude(craft.velocity) >= 0.0D",
            fails = "INV-GDN-20 rest", on = "2026-10-24")
    public void aCraftOnAPlanetToldToGoToAShipInSpaceClimbsEntersAndArrives() throws Exception {
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        String lineToRestore = null;
        try {
            AssembledCraft craft = build(site(), "with-pilot-seat", "a craft on the overworld sent to a ship in space");
            built.add(craft.durable);
            AssembledCraft target = build(plot().siteAt(Plot.FIXTURE_INSET, Plot.FIXTURE_INSET + SITES_APART),
                    "with-pilot-seat", "the ship it is sent to, put in space first");
            built.add(target.durable);
            double craftReach = hullReach(craft.dim, craft.physicsId, "the craft");
            double targetReach = hullReach(target.dim, target.physicsId, "the target");
            lineToRestore = lowerTheOrbitLine(craft.dim);

            long upMark = events.mark();
            Reply pressed = act("stellurgytest space auto-takeoff " + target.dim + " id " + target.physicsId);
            requireArranged("the target's take-off must be in its slot: " + pressed,
                    pressed.bool("afcResolved") && "ACTIVE".equals(pressed.text("state")));
            String up = events.awaitRecordWithFields(upMark, "ship_left_planet",
                    "the target must have entered space", CLIMB_LINK_TICKS, "ship", target.durable);
            int spaceDim = (int) Events.number(up, "cellDim");
            String targetNaming = OwnShip.latestNaming(events, buildMark, target.durable);
            requireArranged("the target must be named in the world it entered: " + targetNaming,
                    targetNaming != null && (int) Events.number(targetNaming, "dim") == spaceDim);
            String targetId = Events.text(targetNaming, "ship");
            holdStation(spaceDim, targetId);

            long mark = events.mark();
            Reply issued = arrange("stellurgytest vs motion-go-to-ship-by-id " + craft.dim + " " + craft.physicsId
                    + " " + target.durable);
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));

            String entered = events.awaitRecordWithFields(mark, "ship_left_planet",
                    "INV-GDN-20 enters — the craft must climb across the line into space", CLIMB_LINK_TICKS,
                    "ship", craft.durable);
            requireArranged("the craft must have entered the world the target is in: " + entered,
                    (int) Events.number(entered, "cellDim") == spaceDim);
            String craftNaming = OwnShip.latestNaming(events, buildMark, craft.durable);
            requireArranged("the craft must be named in space: " + craftNaming,
                    craftNaming != null && (int) Events.number(craftNaming, "dim") == spaceDim);
            String craftId = Events.text(craftNaming, "ship");
            ShipInfo there = ShipInfo.byId(c -> read(c).raw(), spaceDim, craftId);
            Reply moved = arrange("stellurgytest vs teleport-ship-by-id " + spaceDim + " " + targetId + " "
                    + Math.round(there.x + BESIDE_THE_ENTRY) + " " + Math.round(there.y) + " " + Math.round(there.z));
            requireArranged("the target must be moved beside where the craft entered: " + moved, moved.bool("ok"));
            arrange("stellurgytest vs unpark-by-id " + spaceDim + " " + targetId);

            String arrived = events.awaitRecordWithFields(mark, "motion_arrived",
                    "INV-GDN-20 arrival — the craft must report it got alongside the ship in space",
                    CLIMB_LINK_TICKS + FLIGHT_LINK_TICKS, "ship", craft.durable);
            assertEquals("INV-GDN-20 arrival in space: " + arrived, spaceDim, (int) Events.number(arrived, "dim"));
            ShipInfo end = ShipInfo.byId(c -> read(c).raw(), spaceDim, craftId);
            ShipInfo them = ShipInfo.byId(c -> read(c).raw(), spaceDim, targetId);
            double apart = end.distanceTo(them.x, them.y, them.z);
            double standoff = craftReach + targetReach + ARRIVED_WITHIN;
            assertTrue("INV-GDN-20 outside both hulls: the craft ended " + apart + " blocks from the ship, inside"
                    + " the " + (craftReach + targetReach) + " the two hulls reach: " + end + " | " + them,
                    apart >= craftReach + targetReach);
            assertTrue("INV-GDN-20 standoff: the craft ended " + apart + " blocks from the ship, not within "
                    + ARRIVED_WITHIN + " of the standoff " + standoff + ": " + end + " | " + them + " | " + arrived,
                    Math.abs(apart - standoff) <= ARRIVED_WITHIN);
            double speed = restRate(restWindow(spaceDim, craftId));
            assertTrue("INV-GDN-20 rest: the craft moved at " + speed + " blocks/s on average after it arrived",
                    speed < ARRIVED_SLOWER_THAN);
        } finally {
            dispose(events, buildMark, built);
            restoreTheOrbitLine(lineToRestore);
        }
    }

    /**
     * INV-GDN-21: a craft in space told {@code GO_TO} a point of a planet's surface flies into that
     * planet's descent shell, is descended onto the planet with the order in its slot, and lands at the
     * point, as INV-GDN-10 measures a landing.
     *
     * <p>The craft gets into space by its own take-off, onto the overworld's entry ring, and is then moved
     * toward the body to {@link #OUTSIDE_THE_SHELL} blocks outside its shell — the ring is twice the shell
     * out, some 26 000 blocks of a flight in space INV-GDN-01 measures, at a length no scenario affords.
     * Where the body is from the craft is read off the ledger ({@code space bodies}), and asserted to put
     * the craft on the ring before it is trusted. The descent sets the craft down in the air over the
     * world's spawn; the platform it is sent to is in this scenario's plot, laid by the test so its height
     * is known, and the flight there is the order's own. What is ARRANGED: the overworld's orbit line,
     * lowered as INV-GDN-20's is, which also brings the descent's arrival down to just above the block band;
     * the craft's place in space. What is the subject: one order flown into the shell, through the descent,
     * to the ground.</p>
     */
    @Test
    @Pins("INV-GDN-21")
    @RedWitness(verdict = "descends", member = "GuidanceTarget#placeIn",
            at = "return intoTheShell(dimension, craft, around);",
            replacedBy = "return Placement.unplaced(Unplaced.NOT_FOUND);",
            fails = "INV-GDN-21 descends", on = "2026-10-24")
    @RedWitness(verdict = "arrival", member = "TileAdvancedFlightComputer#writeToNBT",
            at = "nbt.setTag(NBT_MOTION, slot);", replacedBy = "",
            fails = "INV-GDN-21 arrival", on = "2026-10-24")
    @RedWitness(verdict = "on the ground", member = "MotionSlot#rest",
            at = "return FlightCommand.of(null, zeroRate(), quatArray(attitude));",
            replacedBy = "return FlightCommand.of(new double[]{0.0D, 10.0D, 0.0D}, zeroRate(), quatArray(attitude));",
            fails = "INV-GDN-21 on the ground", on = "2026-10-24")
    @RedWitness(verdict = "there", member = "GuidanceTarget#placeIn",
            at = "new double[]{x, ground + craft.hullDepthBelow - MotionSlot.TOUCHDOWN / 2.0D, z}, new double[]{x, clear, z}",
            replacedBy = "new double[]{x + 2.5D, ground + craft.hullDepthBelow - MotionSlot.TOUCHDOWN / 2.0D, z}, new double[]{x + 2.5D, clear, z}",
            fails = "INV-GDN-21 there", on = "2026-10-24")
    @RedWitness(verdict = "at rest", member = "MotionSlot#fly",
            at = "return new Step(rest(heldOr(craft.attitude)), Event.NONE);",
            replacedBy = "return new Step(FlightCommand.of(new double[]{0.0D, 0.3D, 0.0D}, zeroRate(), quatArray(heldOr(craft.attitude))), Event.NONE);",
            fails = "INV-GDN-21 at rest", on = "2026-10-24")
    public void aCraftInSpaceToldToGoToAPointOfAPlanetDescendsAndLandsThere() throws Exception {
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        String lineToRestore = null;
        try {
            FixtureSite site = site();
            AssembledCraft craft = build(site, "with-pilot-seat", "a craft sent from space to a point of the overworld");
            built.add(craft.durable);
            // The platform: on the plot's far side from the craft's own pad, at the site's height, wide enough that
            // a craft landing anywhere it may count as there stands on it — the arrival radius plus the
            // hull's own half-width, measured.
            Reply box = bounds(craft.dim, craft.physicsId);
            ShipInfo start = ShipInfo.byId(c -> read(c).raw(), craft.dim, craft.physicsId);
            double halfWidth = Math.max(Math.max(box.number("maxX") - start.x, start.x - box.number("minX")),
                    Math.max(box.number("maxZ") - start.z, start.z - box.number("minZ")));
            int half = (int) Math.ceil(ARRIVED_WITHIN + halfWidth);
            int px = site.x + SITES_APART;
            int pz = site.z;
            int py = site.y;
            int side = 2 * half + 1;
            Reply platform = arrange("stellurgytest fill " + site.dim + " " + (px - half) + " " + py + " "
                    + (pz - half) + " " + (px + half) + " " + py + " " + (pz + half) + " minecraft:stone");
            requireArranged("the landing platform must be laid whole, in open air: " + platform,
                    platform.ok() && platform.integer("placed") == side * side);
            lineToRestore = lowerTheOrbitLine(craft.dim);

            long upMark = events.mark();
            Reply pressed = act("stellurgytest space auto-takeoff " + craft.dim + " id " + craft.physicsId);
            requireArranged("the craft's take-off must be in its slot: " + pressed,
                    pressed.bool("afcResolved") && "ACTIVE".equals(pressed.text("state")));
            String up = events.awaitRecordWithFields(upMark, "ship_left_planet",
                    "the craft must have entered space", CLIMB_LINK_TICKS, "ship", craft.durable);
            int spaceDim = (int) Events.number(up, "cellDim");
            String inSpace = OwnShip.latestNaming(events, buildMark, craft.durable);
            requireArranged("the craft must be named in the world it entered: " + inSpace,
                    inSpace != null && (int) Events.number(inSpace, "dim") == spaceDim);
            String spaceId = Events.text(inSpace, "ship");

            Reply body = bodyFrom(craft.durable, site.dim);
            double shell = body.number("shellRadius");
            double[] bearing = {body.arrayNumber("bearing", 0), body.arrayNumber("bearing", 1),
                    body.arrayNumber("bearing", 2)};
            double range = Math.sqrt(bearing[0] * bearing[0] + bearing[1] * bearing[1] + bearing[2] * bearing[2]);
            requireArranged("the body must read where the entry put the craft — its ring, twice its shell out: "
                    + body, Math.abs(range - 2.0D * shell) <= RING_READ_WITHIN);
            ShipInfo onTheRing = ShipInfo.byId(c -> read(c).raw(), spaceDim, spaceId);
            double s = 1.0D - (shell + OUTSIDE_THE_SHELL) / range;
            Reply moved = arrange("stellurgytest vs teleport-ship-by-id " + spaceDim + " " + spaceId + " "
                    + Math.round(onTheRing.x + bearing[0] * s) + " " + Math.round(onTheRing.y + bearing[1] * s) + " "
                    + Math.round(onTheRing.z + bearing[2] * s));
            requireArranged("the craft must be moved toward the body: " + moved, moved.bool("ok"));
            arrange("stellurgytest vs unpark-by-id " + spaceDim + " " + spaceId);
            Reply near = bodyFrom(craft.durable, site.dim);
            requireArranged("the craft must stand outside the shell, near it: " + near,
                    near.number("distance") > shell
                            && near.number("distance") <= shell + OUTSIDE_THE_SHELL + RING_READ_WITHIN);

            long mark = events.mark();
            double columnX = px + 0.5D;
            double columnZ = pz + 0.5D;
            Reply issued = arrange("stellurgytest vs motion-go-to-surface-by-id " + spaceDim + " " + spaceId + " "
                    + site.dim + " " + columnX + " " + columnZ);
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));

            String descended = events.awaitRecordWithFields(mark, "ship_entered_planet",
                    "INV-GDN-21 descends — the craft must be descended onto the planet", CROSSING_LINK_TICKS,
                    "ship", craft.durable);
            assertEquals("INV-GDN-21 descends: onto the planet the point is on: " + descended, site.dim,
                    (int) Events.number(descended, "planetDim"));
            String arrived = events.awaitRecordWithFields(mark, "motion_arrived",
                    "INV-GDN-21 arrival — the craft must report it landed", DESCENT_LINK_TICKS, "ship",
                    craft.durable);
            assertEquals("INV-GDN-21 arrival on the planet: " + arrived, site.dim, (int) Events.number(arrived, "dim"));
            String down = OwnShip.latestNaming(events, buildMark, craft.durable);
            requireArranged("the craft must be named on the planet: " + down,
                    down != null && (int) Events.number(down, "dim") == site.dim);
            String landedId = Events.text(down, "ship");
            ShipInfo end = ShipInfo.byId(c -> read(c).raw(), site.dim, landedId);
            Reply landedBox = bounds(site.dim, landedId);
            double gap = landedBox.number("minY") - (py + 1.0D);
            assertTrue("INV-GDN-21 on the ground: the hull's lowest point is " + gap + " blocks from the"
                    + " platform's top, not within " + ON_THE_GROUND + ": " + landedBox + " | " + arrived,
                    Math.abs(gap) < ON_THE_GROUND);
            double off = Math.hypot(end.x - columnX, end.z - columnZ);
            assertTrue("INV-GDN-21 there: the craft landed " + off + " blocks from the column it was sent to,"
                    + " beyond " + ARRIVED_WITHIN + ": " + end, off <= ARRIVED_WITHIN);
            Window<ShipInfo> resting = restWindow(site.dim, landedId);
            double speed = restRate(resting);
            assertTrue("INV-GDN-21 at rest: the landed craft moved at " + speed + " blocks/s after it landed: "
                    + resting, speed < ARRIVED_SLOWER_THAN);
        } finally {
            dispose(events, buildMark, built);
            restoreTheOrbitLine(lineToRestore);
        }
    }

    /**
     * The body whose surface is the planet world {@code dim}, as the ledger row of the craft
     * {@code durable} reads it: its bearing from the craft, its distance and its descent shell's radius.
     */
    private Reply bodyFrom(String durable, int dim) throws Exception {
        Reply bodies = read("stellurgytest space bodies id " + durable).requireOk("read the bodies about the craft");
        Reply[] ships = bodies.elements("ships");
        requireArranged("the ledger must hold the craft once: " + bodies, ships.length == 1);
        for (Reply b : ships[0].elements("cellBodies")) {
            if (b.has("dim") && b.integer("dim") == dim) {
                requireArranged("the body must have a bearing from the craft: " + b, !b.has("bearingRefused"));
                return b;
            }
        }
        throw new AssertionError("not arranged: no body of world " + dim + " in the craft's cell: " + bodies);
    }

    /**
     * Lower the orbit line of the planet world {@code dim} to the lowest one production accepts, so a climb
     * from the open-air band crosses it in a few seconds; answers what restores the line as it was.
     */
    private String lowerTheOrbitLine(int dim) throws Exception {
        Reply before = read("stellurgytest planet orbit-line " + dim).requireOk("read the orbit line");
        String restore = before.bool("stated") ? String.valueOf(before.integer("line")) : "unset";
        Reply lowered = arrange("stellurgytest planet state-orbit-line " + dim + " "
                + dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y);
        requireArranged("the line must be lowered: " + lowered,
                lowered.ok() && lowered.integer("line") == dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y);
        return dim + " " + restore;
    }

    private void restoreTheOrbitLine(String dimAndLine) throws Exception {
        if (dimAndLine != null) {
            arrange("stellurgytest planet state-orbit-line " + dimAndLine).requireOk("restore the orbit line");
        }
    }

    /** Take the craft {@code shipId} of world {@code dim} back from its slot and leave it holding station. */
    private void holdStation(int dim, String shipId) throws Exception {
        requireArranged("Flight Assist must be on for the craft to hold station",
                arrange("stellurgytest vs fa-by-id " + dim + " " + shipId + " true").bool("flightAssist"));
        Reply taken = arrange("stellurgytest vs ff-input-by-id " + dim + " " + shipId + " 0 0 0 0 0 0");
        requireArranged("a pilot's input must reach the craft: " + taken, "SET".equals(taken.text("input")));
        Reply left = arrange("stellurgytest vs ff-input-by-id " + dim + " " + shipId);
        requireArranged("the pilot must have let go: " + left, "null".equals(left.text("input")));
        requireArranged("the craft's slot must be empty",
                "EMPTY".equals(read("stellurgytest vs motion-read-by-id " + dim + " " + shipId).text("state")));
    }

    /**
     * Build an ordinary target and a craft of {@code variant} in an empty cell, put the target 200 blocks
     * off, order the craft to it, and judge where it ends: {@link #assertAlongside}.
     */
    private void flyToAShipBeside(long sector, String variant, String anchor) throws Exception {
        int slotDim = emptyCell(sector, "the cell " + anchor + " flies in");
        Events events = events();
        long buildMark = events.mark();
        List<String> built = new ArrayList<>();
        try {
            AssembledCraft craft = build(plot().inDimension(slotDim).site(), variant, "a " + variant + " craft");
            built.add(craft.durable);
            if ("no-reverse-hull".equals(variant)) {
                Reply directions = Reply.of(Reply.of(read("stellurgytest vs flight-model-by-id " + slotDim + " "
                        + craft.physicsId).object("model")).object("directions"));
                Reply reverse = Reply.of(Reply.of(directions.object("SURGE_NEGATIVE")).object("live"));
                Reply forward = Reply.of(Reply.of(directions.object("SURGE_POSITIVE")).object("live"));
                requireArranged("the hull must have NO reverse authority, sustained or burst, and some forward: "
                                + "reverse " + reverse + ", forward " + forward,
                        reverse.number("sustained") == 0.0D && reverse.number("burst") == 0.0D
                                && forward.number("sustained") > 0.0D);
            }
            AssembledCraft target = build(plot().inDimension(slotDim).siteAt(20, 44), "with-pilot-seat",
                    "the ship it is sent to");
            built.add(target.durable);
            double craftReach = hullReach(slotDim, craft.physicsId, "the craft");
            ShipInfo start = ShipInfo.byId(c -> read(c).raw(), slotDim, craft.physicsId);
            park(target, Math.round(start.x) + ALONG_X, Math.round(start.y) + UP, Math.round(start.z));
            double targetReach = hullReach(slotDim, target.physicsId, "the target");

            long mark = events.mark();
            Reply issued = arrange("stellurgytest vs motion-go-to-ship-by-id " + slotDim + " " + craft.physicsId
                    + " " + target.durable);
            requireArranged("the order must be in the slot: " + issued,
                    issued.bool("afcResolved") && "ACTIVE".equals(issued.text("state")));

            String arrived = events.awaitRecordWithFields(mark, "motion_arrived",
                    anchor + " arrival — the craft must report it got alongside the ship it was sent to",
                    FLIGHT_LINK_TICKS, "ship", craft.durable);
            assertAlongside(anchor, slotDim, craft.physicsId, craftReach, target, targetReach, arrived);
        } finally {
            dispose(events, buildMark, built);
        }
    }

    /**
     * The verdict of an arrival alongside a ship: the craft's position at the standoff from the target's —
     * the two hulls' reaches plus the arrival radius — within the arrival radius, never nearer than the
     * two reaches (the hulls apart), each hull read once; and at rest, judged as {@link #restRate} judges
     * it.
     */
    private void assertAlongside(String anchor, int dim, String craftId, double craftReach, AssembledCraft target,
                                 double targetReach, String arrived) throws Exception {
        ShipInfo end = ShipInfo.byId(c -> read(c).raw(), dim, craftId);
        ShipInfo them = ShipInfo.byId(c -> read(c).raw(), dim, target.physicsId);
        double apart = end.distanceTo(them.x, them.y, them.z);
        double standoff = craftReach + targetReach + ARRIVED_WITHIN;
        assertTrue(anchor + " outside both hulls: the craft ended " + apart + " blocks from the ship, inside"
                + " the " + (craftReach + targetReach) + " the two hulls reach: " + end + " | " + them,
                apart >= craftReach + targetReach);
        assertTrue(anchor + " standoff: the craft ended " + apart + " blocks from the ship, not within "
                + ARRIVED_WITHIN + " of the standoff " + standoff + ": " + end + " | " + them + " | " + arrived,
                Math.abs(apart - standoff) <= ARRIVED_WITHIN);
        Window<ShipInfo> after = restWindow(dim, craftId);
        double speed = restRate(after);
        assertTrue(anchor + " rest: the craft moved at " + speed + " blocks/s on average after it arrived, not"
                + " below " + ARRIVED_SLOWER_THAN + ": " + after, speed < ARRIVED_SLOWER_THAN);
    }

    /**
     * "At rest" is a statement about TIME: a craft held where it is rocks on its actuators, or on a contact,
     * for a moment, and one velocity read can catch that moment. So the craft's position is read twice,
     * {@link #AT_REST_WINDOW_TICKS} apart on the server's clock, and the verdict is how far it went
     * per tick that actually passed.
     */
    @Wait(shape = Wait.Shape.WINDOW, at = "return Window.over(",
            why = "rest is the absence of motion over time after the arrival; the arrival record says the craft"
                    + " was at rest when it was posted, not that it stays so, and nothing is published per tick")
    private Window<ShipInfo> restWindow(int dim, String craftId) throws Exception {
        return Window.over(() -> ShipInfo.byId(c -> read(c).raw(), dim, craftId), client(), GameTicks.server(),
                ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), AT_REST_WINDOW_TICKS);
    }

    /** The mean speed over {@code window}, blocks per second of the game's clock. */
    private static double restRate(Window<ShipInfo> window) {
        double vx = window.rate(s -> s.x);
        double vy = window.rate(s -> s.y);
        double vz = window.rate(s -> s.z);
        return Math.sqrt(vx * vx + vy * vy + vz * vz) * TICKS_PER_SECOND;
    }

    /**
     * How far a ship's hull reaches from its position: the radius of the smallest sphere about the
     * position holding its whole box, measured on an UNROTATED hull — its world box is then its own box,
     * so the farthest corner of it is the reach. Asserted unrotated before it is read.
     */
    private double hullReach(int dim, String shipId, String what) throws Exception {
        ShipInfo pose = ShipInfo.byId(c -> read(c).raw(), dim, shipId);
        requireArranged(what + " must be unrotated for its world box to be its own: " + pose,
                Math.abs(pose.qw) > 1.0D - 1.0e-6D);
        Reply box = bounds(dim, shipId);
        double reach = 0.0D;
        for (int corner = 0; corner < 8; corner++) {
            double dx = ((corner & 1) == 0 ? box.number("minX") : box.number("maxX")) - pose.x;
            double dy = ((corner & 2) == 0 ? box.number("minY") : box.number("maxY")) - pose.y;
            double dz = ((corner & 4) == 0 ? box.number("minZ") : box.number("maxZ")) - pose.z;
            reach = Math.max(reach, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        requireArranged(what + " must have a hull that reaches past its position: " + box + " | " + pose,
                reach > 0.0D);
        return reach;
    }

    private Reply bounds(int dim, String shipId) throws Exception {
        Reply box = read("stellurgytest vs ship-bounds " + dim + " " + shipId);
        requireArranged("the registry must know the ship's box: " + box, box.ok() && box.bool("known"));
        return box;
    }

    /** Move {@code craft} rigidly to {@code (x, y, z)} of its world and let its physics run there. */
    private void park(AssembledCraft craft, long x, long y, long z) throws Exception {
        Reply moved = arrange("stellurgytest vs teleport-ship-by-id " + craft.dim + " " + craft.physicsId + " "
                + x + " " + y + " " + z);
        requireArranged("the craft must be moved: " + moved, moved.bool("ok"));
        arrange("stellurgytest vs unpark-by-id " + craft.dim + " " + craft.physicsId);
    }

    /** Settle {@code craft} in the ledger at the cell of sector {@code (sx, 0, 0)}, its world {@code dim}. */
    private void settle(AssembledCraft craft, long sx, int dim) throws Exception {
        Reply settled = arrange("stellurgytest space ledger-settle " + craft.durable + " " + sx + " 0 0 0 0 0");
        requireArranged("the craft must be settled in its cell, or neither the seam nor the ledger knows it: "
                + settled, settled.ok() && settled.integer("slotDim") == dim);
    }

    /** Materialize the cell at sector {@code (sx, 0, 0)} into a slot world, refusing a cell any body
     *  stands in; answers the slot world's dimension. */
    private int emptyCell(long sx, String what) throws Exception {
        CellInfo cell = CellInfo.atSector(c -> read(c).raw(), sx, 0L, 0L);
        requireArranged(what + " must have no body standing in it: " + cell,
                cell.bodiesAtCount == 0 && cell.cellBodies.isEmpty());
        Reply occupied = arrange("stellurgytest space occupy " + sx + " 0 0");
        requireArranged(what + " must be materialized into a loaded slot world: " + occupied,
                occupied.ok() && occupied.bool("worldLoaded"));
        return occupied.integer("slotDim");
    }

    /** Lay {@code variant} at {@code site} and assemble it. */
    private AssembledCraft build(FixtureSite site, String variant, String what) throws Exception {
        Reply fixture = AssembledCraft.lay(site, c -> arrange(c).raw(), variant, HALO, HEIGHT, what);
        // The press is the assembler's own entry, a TRIGGER: production decides what it builds.
        AssembledCraft craft = AssembledCraft.assemble(site, c -> act(c).raw(), events(), fixture, what);
        // A craft whose physics has not started sits as still as a held one, and its pose is the one it
        // was assembled at: every reading below is of a craft the engine steps.
        String usable = ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events(), site.dim, craft.durable,
                AssembledCraft.LINK_BUDGET_TICKS);
        requireArranged(what + " — the craft that became usable must be the one assembled: " + usable
                + " / " + craft, craft.physicsId.equals(usable));
        return craft;
    }

    private void dispose(Events events, long buildMark, List<String> built) throws Exception {
        for (String durable : built) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, durable);
        }
    }

    /** The server's ordered log, read in the READ role, advanced on the server's clock. */
    private Events events() {
        return new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks),
                evictionReports());
    }
}
