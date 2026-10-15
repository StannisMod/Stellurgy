package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.StellurgyTestConstants.DIRECT_JUMP_SPEED;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * E2E: a jump short enough to have no cruise moves a real VS ship between two cells in ONE crossing.
 *
 * <p>The arrival acceptance here is deliberately the SAME body for both mechanisms
 * ({@link #arrivesInTheTargetCell}), run once at a speed that selects the direct crossing and once at a
 * speed that selects a hyperspace flight. Two mechanisms with two copies of "did it arrive" drift apart
 * within weeks, and the copy that stops being maintained is the one whose mechanism nobody is changing
 * — which is the one that will break silently.</p>
 *
 * <p>What is asserted about the direct path beyond arriving: it never reports a flight in progress.
 * That is the whole claim — no lane, no park, no mid-flight for a restart to resume — and it is read
 * off the probe's own {@code inTransit}/{@code crossing} pair rather than off how long anything
 * took.</p>
 */
public class VSShortJumpCrossesDirectlyTest extends AbstractSharedServerTest {

    /**
     * How much WORLD a jump gets to complete in: 400 server ticks, the twenty seconds the old
     * 80 x 250 ms poll loop meant on an idle box, with no fork multiplier.
     */
    private static final int ARRIVAL_TICKS = 400;

    /** The same, for a ship becoming loadable in its slot. */

    @Test
    public void aShortJumpArrivesWithoutEverBeingInFlight() throws Exception {

        TransitSetup setup = setUpPilotedShip();
        int originDim = setup.originDim;

        // Marked BEFORE the command whose effect is awaited.
        long jumpMark = events.mark();
        String begin = act("stellurgytest space transit-begin " + originDim + " 1 64 1 "
                + DIRECT_JUMP_SPEED).raw();
        assertTrue("the short jump must begin: " + begin, Reply.of(begin).bool("began"));
        assertEquals("a direct crossing is not a flight — nothing may be in transit the moment it "
                        + "starts, because there is no flight to be in the middle of: " + begin,
                0, extractInt(begin, "inTransit"));

        // The arrival names the route it was flown by, so this leg asserts the MECHANISM rather
        // than inferring it from an in-transit count that happens to read zero. A jump that went
        // through hyperspace no longer satisfies it.
        String arrived = arrivesInTheTargetCell(jumpMark, setup.requireDurableId(), "DIRECT");
        assertEquals("and nothing was ever in transit while it settled: " + arrived,
                0, extractInt(begin, "inTransit"));
    }

    /**
     * The control leg, and it is not decoration: it is what makes the assertion above mean "the SPEED
     * chose this" rather than "this fixture always does this". Same ship, same cells, same acceptance —
     * only the drive is slower, and the jump becomes a flight with a lane under it.
     */
    @Test
    public void theSameJumpFlownSlowlyStillGoesThroughHyperspace() throws Exception {

        TransitSetup setup = setUpPilotedShip();
        int originDim = setup.originDim;

        // Marked BEFORE the command whose effect is awaited.
        long jumpMark = events.mark();
        String begin = act("stellurgytest space transit-begin " + originDim + " 1 64 1 "
                + HYPERSPACE_JUMP_SPEED).raw();
        assertTrue("the jump must begin: " + begin, Reply.of(begin).bool("began"));
        assertEquals("a slow jump IS a flight, and reports one: " + begin,
                1, extractInt(begin, "inTransit"));

        arrivesInTheTargetCell(jumpMark, setup.requireDurableId(), "HYPERSPACE");
    }

    /**
     * A short jump AIMED AT A BODY comes out standing OFF it, and stays in space.
     *
     * <p>An arrival lands on the coordinate it was sent to unless something moves it, and a body's
     * address is exactly such a coordinate — so an unmoved arrival sits at distance zero, inside the
     * descent radius, and the flight computer's own proximity trigger takes the ship down on its
     * first settled tick with nobody at the controls. The standoff ring exists to prevent exactly that,
     * and this pins it on the path a SHORT jump takes: the direct crossing.</p>
     *
     * <p>The claim is read off the ledger rather than off the absence of a descent: the ship must
     * still HAVE a row (a descent removes it — the ship is no longer in space), and every
     * descend-target body of the cell it stands in must be farther than the trigger's radius, by the
     * same centre distance the trigger compares. A cell with no body in it would make both trivially
     * true, so the body being there is required first.</p>
     */
    @LegacyWitness(prose = "with {@code SpaceSubsystem#SpaceSubsystem} at"
            + " {@code arrivalStandoff(shipId, target, useClock.getAsLong())} — the direct crosser's argument to"
            + " {@code requestDirectJump} — replaced by the raw {@code target}, this failed with \"Nearest descend-target centre: 0\" — the ship"
            + " SETTLED in cell 19_0_0 at bearing [0,0,0] from the planet; 2026-09-23.")
    @LegacyWitness(prose = "2026-10-01, with {@code SpaceSubsystem#arrivalStandoff} at"
            + " {@code clearance = Math.max(clearance, DescentShell.radiusAround(body))} and its ring line both"
            + " dropped — the flat 1 024 ring the standoff used before it took the bodies' shells — this fails"
            + " with \"Tightest body … \"distance\":1023,\"boundaryDistance\":0,\"shellRadius\":25913\".")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advanceWorld(client(), slotDim, STANDOFF_WATCH_TICKS);",
            why = "from the arrival record to the ledger read below, STANDOFF_WATCH_TICKS of the arrival cell's"
                    + " own clock — the descent trigger runs on its flight computer's tick there, and a ship"
                    + " inside the radius is taken down on the first of them.")
    public void aShortJumpAtABodyArrivesOnItsStandoffRingAndStaysInSpace() throws Exception {

        TransitSetup setup = setUpPilotedShip();
        String durableId = setup.requireDurableId();

        long jumpMark = events.markInstrumented();
        // `body 0` aims at the home planet's own address; the speed makes any distance one tick of
        // flight, so the route is the direct crossing — asserted below off the arrival record.
        String begin = act("stellurgytest space transit-begin " + setup.originDim + " 1 64 1 "
                + ONE_TICK_JUMP_SPEED + " body 0").raw();
        assertTrue("the short jump at the home planet must begin: " + begin,
                Reply.of(begin).bool("began"));
        String arrived = events.awaitRecordWithFields(jumpMark, "ship_transit_ended",
                "the jump at the home planet never ended; the durable record reads "
                        + read("stellurgytest space transit-export").raw(),
                ARRIVAL_TICKS, "ship", durableId, "route", "DIRECT");
        int slotDim = extractInt(arrived, "dim");

        GameTicks.advanceWorld(client(), slotDim, STANDOFF_WATCH_TICKS);
        // THIS craft's ledger row, asked for by its durable id: absent when the craft is no longer in
        // space.
        String bodies = read("stellurgytest space bodies id " + durableId).raw();
        String[] rows = Reply.of("stellurgytest space bodies", bodies).objectArray("ships");
        String row = rows.length == 1 && durableId.equals(Reply.of(rows[0]).text("ship")) ? rows[0] : null;
        assertTrue("a ship that jumped AT a body must still be in space afterwards — its ledger row is"
                        + " gone, which is what a descent does. Descents since the jump: "
                        + events.since(jumpMark, "descent_requested") + " | arrival=" + arrived
                        + " | bodies=" + bodies,
                row != null);
        // The margin by which the ship clears each descend-target body's OWN descent shell — the
        // boundary the arrival standoff is built to stand outside (it takes the widest shell of the
        // target cell's bodies as its clearance). A flat radius was compared here until 2026-10-01,
        // and the standoff then sat at 1 024 blocks inside an Earth shell of 25 913 unseen.
        long tightestMargin = Long.MAX_VALUE;
        String tightestBody = null;
        int descendTargets = 0;
        for (String body : Reply.of("one ship's cell", row).objectArray("cellBodies")) {
            Reply b = Reply.of("one cell body", body);
            // Read bare: the producer always writes `descendTarget` on every cell body.
            if (b.bool("descendTarget")) {
                descendTargets++;
                long margin = (long) b.number("distance") - (long) b.number("shellRadius");
                if (margin < tightestMargin) {
                    tightestMargin = margin;
                    tightestBody = body;
                }
            }
        }
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the cell the jump ended in must hold the body it was aimed at, or nothing here could"
                        + " have been stood off from: " + row, descendTargets > 0);
        assertTrue("a short jump aimed at a body must come out OFF it — outside that body's own descent"
                        + " shell. Tightest body (distance and shellRadius): " + tightestBody
                        + " | row=" + row, tightestMargin > 0);
    }

    /**
     * A jump speed that makes ANY distance one tick of flight — so the route is always the direct
     * crossing, whatever the distance from the fixture's origin to the body aimed at.
     *
     * <p>A constant: a final {@code long} computed from literals.</p>
     */
    private static final long ONE_TICK_JUMP_SPEED = 1L << 40;

    /** Ticks of the arrival cell watched after the arrival: the trigger fires on the first one a
     *  ship inside the radius spends settled there, and forty is that many times over. */
    private static final int STANDOFF_WATCH_TICKS = 40;

    /**
     * The shared acceptance: wait for the arrival production announces, then require the ship to be
     * VS-managed at the target cell's pose. Returns the arrival record so a caller can assert on the
     * mechanism too.
     *
     * <p><b>The route is a parameter because it is the subject.</b> This class exists to show that
     * the SPEED chooses the route, and the arrival event carries which one was taken — so the
     * acceptance now asserts the mechanism directly instead of inferring it from an in-transit count
     * that happens to be zero. A jump that went the other way no longer satisfies the other leg.</p>
     *
     * <p>No pump. The fixture runs on the server's own subsystem, so the jump is advanced by the
     * server tick like any other — and if it stops being, this fails.</p>
     */
    private String arrivesInTheTargetCell(long mark, String durableId, String route)
            throws Exception {
        // Narrowed by the CRAFT as well as the route: a route alone is a description, and a
        // sibling scenario arriving by the same route in the same window satisfies it.
        String arrived = events.awaitRecordWithFields(mark, "ship_transit_ended",
                "the ship never reached the target cell by the " + route + " route; the durable"
                        + " record now reads " + read("stellurgytest space transit-export").raw(),
                ARRIVAL_TICKS, "ship", durableId, "route", route);
        int targetDim = extractInt(arrived, "dim");
        assertTrue("the arrival was announced but names no dimension: " + arrived, targetDim >= 0);
        // Identified by the craft this scenario BUILT, which is what the wait above is keyed on, and
        // linked on that craft's own announcement that it is usable in the target cell.
        String arrivedId = ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events, targetDim,
                durableId.trim(), USABLE_TICKS);
        assertTrue("the arrived ship is not VS-managed in the target cell; id=" + arrivedId,
                ShipInfo.loadedIn(c -> read(c).raw(), targetDim, arrivedId));
        return arrived;
    }

    private TransitSetup setUpPilotedShip() throws Exception {
        buildMark = events.mark();
        TransitSetup setup = TransitSetup.piloted(c -> arrange(c).raw());
        int originDim = setup.originDim;
        // A crossing resolves its ship by IDENTITY, never by the anchor every transit fixture
        // shares, so a setup that minted no durable name cannot be crossed at all.
        builtDurable = setup.requireDurableId();
        // ONE SHIP, ONE IDENTITY — production's rule, now pinned on the fixture that used to break
        // it. This build was once assembled off a STONE block of its own deck, so the computer's
        // durable name was never found and the substrate minted a second id; the reply then carried
        // two values for one craft, and a wait keyed on the wrong one expired with the log full of
        // records about the right one. The footprint form of the assembly fixed it by making the
        // mistake inexpressible — it finds the computer inside the pasted extent — and this
        // assertion is what keeps that true rather than leaving it to a comment.
        assertEquals("the fixture's two ids must be ONE value: a craft assembled off its own flight "
                        + "computer is named by it, and two different ids here mean the assembly "
                        + "found no computer and took a substrate-minted name instead: "
                        + setup.raw(),
                setup.durableId, setup.shipId);
        ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events,
                originDim, setup.requireDurableId(), USABLE_TICKS));
        return setup;
    }

    /** Server ticks for the craft to become usable where it was built or has arrived. */
    private static final int USABLE_TICKS = 200;

    /** The mark taken before this scenario's setup, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
    }

    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    private static String extractString(String json, String key) {
        return Reply.of(json).text(key);
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }
}
