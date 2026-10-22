package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.AssembledCraft;
import dev.stannismod.stellurgy.test.CellInfo;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.OwnShip;
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
 * over and arrives too; a hull with no thrust at all is refused; a target in another world is refused as
 * needing a crossing and the craft hovers; an order whose course crosses a cell face survives the
 * crossing; and on a planet a craft sent to a point of the surface lands there (ship-guidance INV-GDN-01,
 * -03, -07..10).
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
 * open-air band, so the ground it lands on is ground the test knows the height of. What this does NOT
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
     * The cells the space scenarios fly in, one each (the crossing scenario takes the next one too):
     * sectors far from every body of the shipped universe, each asserted to hold no body before it is
     * used.
     */
    private static final long CELL_ARRIVES = 40_021L;
    private static final long CELL_TURNS_OVER = 40_023L;
    private static final long CELL_CROSSES = 40_025L;
    private static final long CELL_NO_THRUST = 40_027L;
    private static final long CELL_ELSEWHERE = 40_029L;

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
            at = "return Refusal.NEEDS_A_CROSSING;", replacedBy = "return Refusal.TARGET_NOT_FOUND;",
            fails = "INV-GDN-09 reason", on = "2026-10-21")
    @RedWitness(verdict = "within", member = "MotionSlot#fly",
            at = "shortTicks >= REFUSAL_WINDOW_TICKS", replacedBy = "shortTicks >= 10 * REFUSAL_WINDOW_TICKS",
            fails = "INV-GDN-09 within", on = "2026-10-21")
    @RedWitness(verdict = "hovers", member = "MotionSlot#isFlying",
            at = "return state != State.EMPTY;", replacedBy = "return state == State.ACTIVE;",
            fails = "INV-GDN-09 hovers", on = "2026-10-21")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window.over(",
            why = "a hover is the absence of motion over time; the refusal record says the order was refused,"
                    + " not what the craft does afterwards, and no record is published per tick of hovering")
    public void aTargetInAnotherWorldIsRefusedForACrossingAndTheCraftHovers() throws Exception {
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
            assertEquals("INV-GDN-09 reason: refused as needing a crossing: " + refused, "NEEDS_A_CROSSING",
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
    @RedWitness(verdict = "crossed", member = "WorldSurroundings#ship",
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
    @RedWitness(verdict = "on the ground", member = "GuidanceTarget#placeIn",
            at = "- MotionSlot.TOUCHDOWN / 2.0D", replacedBy = "+ 3.0D",
            fails = "INV-GDN-10 on the ground", on = "2026-10-21")
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
     * two reaches (the hulls apart), and slower than the arrival speed. The craft and the target are read
     * once each.
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
        double speed = Math.sqrt(end.velX * end.velX + end.velY * end.velY + end.velZ * end.velZ);
        assertTrue(anchor + " rest: the craft is still moving at " + speed + " blocks/s, not below "
                + ARRIVED_SLOWER_THAN + ": " + end, speed < ARRIVED_SLOWER_THAN);
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
