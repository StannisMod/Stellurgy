package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.space.CellSeam;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * E2E: a ship flown out through its cell's face ARRIVES IN THE NEIGHBOUR, and its ledger row names the
 * cell it is actually in.
 *
 * <p>Before the seam existed, such a ship was neither stopped nor carried: the pose kept going while
 * the ledger report saturated at the boundary, leaving the ship in one place and named in another —
 * unable to descend, refused its jumps, and no longer protecting the cell it was really in.</p>
 *
 * <p>The arrangement uses the REAL on-ramp to get a ship legitimately settled in a cell (assemble,
 * hold a throttle, climb past the ceiling, let the flight computer's own tick call entry), then moves
 * it past the face and drives {@code SpaceSubsystem.cellCrossings().requestCarry()} — production code, through
 * a probe verb. The crossing itself is the shared one every other crossing uses.</p>
 *
 * <p><b>What this test does NOT cover, stated rather than implied:</b> the trigger wiring inside
 * {@code TileAdvancedFlightComputer}. A headless slot world has no player and no ticking chunks, so
 * its tiles do not tick and no e2e here can observe that call — the same limit the descent e2e has
 * (it drives {@code space descent-begin} and says so). WHEN a carry fires is pinned deterministically
 * by {@code CellSeamTest}; the one link neither covers is the two lines in the tile that join them.</p>
 *
 * <p>Witnesses, in order: the ledger names the +X neighbour and no other cell; the ship arrives
 * {@code REENTRY_DEPTH} INSIDE that neighbour's opposite face rather than on it; and it stays there —
 * a ship placed on the face would be one drift away from crossing straight back, which is the whole
 * content of the hysteresis. CONTROL: the pre-move ledger read is asserted to name the source cell, so
 * the later change is a real observation rather than a first reading.</p>
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSShipCellSeamE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern CELL_KEY = Pattern.compile("^(-?\\d+)_(-?\\d+)_(-?\\d+)$");

    /** Where this test builds its ship — its own region, clear of the entry/descent legs. */
    private static final int SRC_X = 6800, SRC_Y = 80, SRC_Z = 6800;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;

    /**
     * How much WORLD an async settle is allowed, in server ticks — thirty seconds of game time.
     *
     * <p>The fork multiplier this carried was deleted rather than re-tuned: it said how much of the
     * machine the test was sharing, and a crossing does not care. It needs a number of controller
     * ticks and gets them whenever the server runs them.</p>
     */
    private static final int SETTLE_TICKS = 600;

    /** The same, for waiting on a ship to become loadable in its slot. */
    private static final int LOAD_TICKS = 200;

    /** Ticks of the carried ship's own world between ping-pong readings. */
    private static final int PING_PONG_TICKS_BETWEEN = 5;

    /**
     * How far past the face the ship is placed: comfortably beyond the carry margin, so the test is
     * not sitting on the decision boundary — that is {@code CellSeamTest}'s job, on the pure layer,
     * where a one-block question can be asked without a physics engine in the way.
     */
    private static final long PAST_THE_FACE =
            GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 2_000L;

    /**
     * Tolerance on the arrival position. The contract is "inside the face, not on it", and the two
     * candidates are {@code REENTRY_DEPTH} apart (16 000 blocks), so a few hundred blocks of settle
     * slop cannot confuse them.
     */
    private static final double ARRIVAL_TOLERANCE = 2_000d;

    @Test
    public void aShipFlownPastItsCellFaceIsCarriedIntoTheNeighbourAndStaysThere() throws Exception {
        ShipPastItsFace arranged = arrangeAShipPastItsFace();
        String setup = arranged.setup;
        String arShipId = arranged.arShipId;
        String sourceCell = arranged.sourceCell;
        int sourceSlot = arranged.sourceSlot;
        double mx = arranged.x;

        // Drive the production carry. NOT the flight computer's own tick: a headless slot world has
        // no player and no ticking chunks, so its tiles do not tick — an earlier revision of this test
        // waited 30 s for a trigger that cannot fire here and reported the CROSSING as broken. This is
        // the same split the descent e2e already uses (`space descent-begin`): WHEN a carry fires is
        // pinned deterministically by `CellSeamTest`, and what a carry DOES is pinned here, on a real
        // ship. `wouldCarry` is production's own reading of the live pose, so the arrangement is
        // witnessed by the code under test rather than only by this test's arithmetic.
        String carry = exec("artest space seam-carry " + sourceSlot);
        assertTrue("production does not agree the ship has left its cell (its own predicate on the "
                + "live pose): " + carry, carry.contains("\"wouldCarry\":true"));
        assertTrue("the carry did not start — the reason is in the reply: " + carry,
                carry.contains("\"started\":true"));

        // --- Assert: carried into the neighbour ---------------------------------------------------
        final String[] afterMove = {""};
        final String source = sourceCell;
        boolean carriedOver = GameTicks.until(client(), GameTicks.server(), SETTLE_TICKS,
                () -> {
                    afterMove[0] = exec("artest space ledger-get " + arShipId);
                    String cell = extractString(afterMove[0], "cell");
                    return cell != null && !source.equals(cell)
                            && "SETTLED".equals(extractString(afterMove[0], "state"));
                },
                () -> loadAllEntrySlots(setup));
        assertTrue("the carry started but the ship never settled in the neighbour; source="
                + sourceCell + " shipX=" + mx + " carry=" + carry + " last ledger=" + afterMove[0],
                carriedOver);
        String carriedCell = extractString(afterMove[0], "cell");

        long[] from = cellSectors(sourceCell);
        long[] to = cellSectors(carriedCell);
        assertEquals("carried into the +X neighbour and no other", from[0] + 1L, to[0]);
        assertEquals("the Y sector must not move — the ship crossed one face", from[1], to[1]);
        assertEquals("the Z sector must not move — the ship crossed one face", from[2], to[2]);

        // It arrived INSIDE the neighbour's opposite face, not on it. This is the hysteresis as the
        // world sees it: the expected world X is the local offset itself (XZ realize directly).
        int carriedSlot = extractInt(afterMove[0], "slotDim");
        assertTrue("the carried ship has no bound slot: " + afterMove[0],
                carriedSlot > Integer.MIN_VALUE);
        assertTrue("the neighbour's cell world never came up", waitForLoadedShip(carriedSlot) >= 1);
        String arrived = arrivedShip(carriedSlot, arShipId);
        double ax = extractDouble(arrived, "posX");
        assertFalse("the arrived ship's pose could not be read: " + arrived, Double.isNaN(ax));
        double expectedX = -(double) GalacticCoord.HALF_CELL + CellSeam.REENTRY_DEPTH;
        assertEquals("the ship must arrive the re-entry depth inside the face it came in by, not on it",
                expectedX, ax, ARRIVAL_TOLERANCE);

        // And it STAYS there: a ship parked on the face would cross straight back.
        // Watched on the CARRIED SLOT's clock, because the crossing that would take it back is decided
        // by the seam check running in that world. Eight readings 250 ms apart covered fewer of those
        // ticks on a loaded box, so a ping-pong slower than the sampling was simply not looked at —
        // and an observation window that goes blind reports stability, which is the silent direction.
        GameTicks.observe(client(), GameTicks.world(carriedSlot), 8, PING_PONG_TICKS_BETWEEN, () -> {
            String held = exec("artest space ledger-get " + arShipId);
            assertEquals("the carried ship bounced back across the face (ping-pong): " + held,
                    carriedCell, extractString(held, "cell"));
        });
    }

    /**
     * E2E: a body standing on the deck when the ship crosses a cell seam ARRIVES WITH IT, still
     * aboard, in the neighbour's slot world.
     *
     * <p>The seam carry stows every aboard body to NBT, kills it, and re-creates it on the far side
     * ({@code AboardBodies}, through the shared crossing every other crossing uses). What that leaves
     * open is whether it happens on THIS crossing: the seam is the one caller whose e2e flew an empty
     * ship, so a body left behind here would look exactly like a body left behind nowhere.</p>
     *
     * <p><b>An ITEM, not a mob and not a player.</b> A player is client-authoritative and belongs to
     * the crew scenarios; a mob has AI that could walk itself out of the ship between the arrange and
     * the assert, which would read as the carry dropping it. An item that was placed at rest moved
     * because something moved it.</p>
     *
     * <p><b>The identity is the body's UUID.</b> A crossing re-creates the entity, so its int
     * entityId is re-minted and following it by that id would report every successful carry as a
     * loss.</p>
     *
     * <p><b>CONTROL, and it is the point of the scenario.</b> The body is looked up BEFORE the carry
     * and asserted to be in the SOURCE slot. Without it, "found in the neighbour" cannot be told from
     * an instrument that answers about whatever world it likes — and the previous attempt at this
     * scenario died on exactly that: {@code loose-body-count} walks chunks, no chunk is loaded at a
     * pose 16M blocks out, and it answered 0 for a body production itself had just called aboard.</p>
     */
    @Test
    public void aBodyOnTheDeckIsCarriedAcrossTheSeamWithItsShip() throws Exception {
        ShipPastItsFace arranged = arrangeAShipPastItsFace();

        // The SOURCE ship's VS id, captured by the arrangement while the ship was still at its settle
        // pose, and used only here: the crossing replaces the VS body, so this id names nothing on
        // the far side.
        String settledVsId = arranged.settledVsId;

        // HOLD the deck's chunks first. A tier-2 ship's blocks are in a subspace shipyard, so its
        // WORLD pose — where a body standing on its deck actually is — is backed by nothing: in play
        // the pilot holds those chunks, headless nobody does, and vanilla removes the entity with the
        // chunk on the next sweep. Measured: a body production had just called aboard was absent from
        // its world one command later, with the world up and the ship still resolving.
        String heldSrc = exec("artest chunk hold " + arranged.sourceSlot + " "
                + (long) arranged.x + " " + (long) arranged.y + " " + (long) arranged.z);
        assertTrue("the deck's chunks could not be held, so the body would be swept away before "
                + "anything could carry it: " + heldSrc, heldSrc.contains("\"ok\":true"));

        // Dropped at the ship's own pose: inside the hull box, which is what the stay region judges.
        // Whole blocks deliberately — "on the deck" is a question about a volume thousands of blocks
        // wide, and a fractional offset here would only look precise.
        String drop = exec("artest space loose-body " + arranged.sourceSlot + " "
                + (long) arranged.x + " " + (long) arranged.y + " " + (long) arranged.z
                + " " + settledVsId);
        assertTrue("the body could not be dropped: " + drop, drop.contains("\"ok\":true"));
        assertTrue("PRODUCTION's own aboard predicate says this body is not on the ship, so the carry "
                + "is under no obligation to take it and this scenario would pin nothing: " + drop,
                drop.contains("\"aboard\":true"));
        String bodyId = extractString(drop, "uuid");
        assertTrue("the drop reported no uuid to follow the body by: " + drop, bodyId != null);

        // CONTROL: the instrument can see this body WHERE IT IS, on the ship it was dropped on,
        // before anything moves it. Without this the later reading measures the instrument.
        String beforeCarry = exec("artest space loose-body-find " + bodyId + " "
                + arranged.sourceSlot + " " + settledVsId);
        assertTrue("the body cannot be found in the world it was just dropped into: " + beforeCarry,
                beforeCarry.contains("\"found\":true"));
        assertTrue("before the carry the body must be ABOARD the source ship, or what follows is not "
                + "about a carry at all: " + beforeCarry, beforeCarry.contains("\"aboard\":true"));

        String carry = exec("artest space seam-carry " + arranged.sourceSlot);
        assertTrue("production does not agree the ship has left its cell: " + carry,
                carry.contains("\"wouldCarry\":true"));
        assertTrue("the carry did not start — the reason is in the reply: " + carry,
                carry.contains("\"started\":true"));

        // HOLD THE ARRIVAL DECK NOW, before the ship gets there. The crossing puts back what it
        // carried the moment the ship is rebuilt on the far side, and an unheld chunk is swept with
        // everything standing in it — so a hold placed after the ledger moves is a hold placed after
        // the only moment that mattered. The carry acquires its destination cell before it cuts, so
        // the neighbour's slot is already bound and askable here; the arrival X is the seam's own
        // deterministic re-entry depth inside the opposite face, and Z is carried across unchanged.
        long[] src = cellSectors(arranged.sourceCell);
        String destSlotReply = exec("artest space cell-slot " + (src[0] + 1) + " " + src[1] + " " + src[2]);
        int destSlot = extractInt(destSlotReply, "slotDim");
        assertTrue("the carry did not bind the neighbour cell to a slot, so there is nowhere to hold "
                + "the arrival deck: " + destSlotReply, destSlot > Integer.MIN_VALUE);
        String heldDst = exec("artest chunk hold " + destSlot + " "
                + (long) (-(double) GalacticCoord.HALF_CELL + CellSeam.REENTRY_DEPTH) + " "
                + (long) arranged.y + " " + (long) arranged.z + " 2");
        assertTrue("the arrival deck's chunks could not be held: " + heldDst,
                heldDst.contains("\"ok\":true"));

        final String[] afterMove = {""};
        final String source = arranged.sourceCell;
        final String setup = arranged.setup;
        boolean carriedOver = GameTicks.until(client(), GameTicks.server(), SETTLE_TICKS,
                () -> {
                    afterMove[0] = exec("artest space ledger-get " + arranged.arShipId);
                    String cell = extractString(afterMove[0], "cell");
                    return cell != null && !source.equals(cell)
                            && "SETTLED".equals(extractString(afterMove[0], "state"));
                },
                () -> loadAllEntrySlots(setup));
        assertTrue("the ship itself never settled in the neighbour, so nothing can be concluded about "
                + "what it was carrying; last ledger=" + afterMove[0], carriedOver);
        int carriedSlot = extractInt(afterMove[0], "slotDim");
        assertTrue("the carried ship has no bound slot: " + afterMove[0],
                carriedSlot > Integer.MIN_VALUE);
        assertTrue("the neighbour's cell world never came up", waitForLoadedShip(carriedSlot) >= 1);

        // The ARRIVED ship's VS id — a new body, so a new id, traded for the durable one. Asking with
        // the source's would answer "not aboard" for a body sitting perfectly on the deck.
        String arrived = arrivedShip(carriedSlot, arranged.arShipId);
        String dstVsId = extractString(arrived, "id");
        assertTrue("the arrived ship reported no VS id: " + arrived, dstVsId != null);

        assertEquals("the carry bound a different slot than the one whose deck was held before it, so "
                + "the body was never protected where it landed", destSlot, carriedSlot);

        // The release is on the crossing's own retry loop (it waits for the ship to be rebuilt in the
        // destination), so the body can land a few ticks after the ledger has moved.
        // WAITED ON IN FULL: found AND aboard. Waiting on "found" alone samples a moment rather than
        // an outcome — the body is spawned during the arrival's own retry loop, and a reading taken on
        // the tick it lands can catch the ship mid-settle and answer `aboard:false` about a body that
        // is sitting exactly where it should be. Measured: the same scenario passes alone and fails in
        // a full-class run at `x=-15984000`, which IS the arrival pose.
        final String[] found = {""};
        boolean carried = GameTicks.until(client(), GameTicks.world(carriedSlot), SETTLE_TICKS,
                () -> {
                    found[0] = exec("artest space loose-body-find " + bodyId + " "
                            + carriedSlot + " " + dstVsId);
                    return found[0].contains("\"found\":true")
                            && found[0].contains("\"aboard\":true");
                });
        // The two failure modes are separated on the way out, because they mean different things: a
        // body that never arrived is a crossing that dropped its cargo; a body that arrived and is not
        // aboard is a crossing that put it down beside the deck.
        assertTrue("the ship crossed the seam and left its cargo behind: the body was aboard in slot "
                        + arranged.sourceSlot + " and never appeared in the neighbour's slot "
                        + carriedSlot + "; last find=" + found[0],
                carried || found[0].contains("\"found\":true"));
        // The SHIP's pose is read again HERE, beside the body's, because "not aboard" has two very
        // different causes and one number cannot separate them: the body was put down away from the
        // deck, or the deck moved after it was put down. The two positions side by side say which.
        assertTrue("the body arrived in the right world but never came to rest ON the ship — "
                        + "production's own aboard predicate still refuses it after "
                        + SETTLE_TICKS + " ticks. body=" + found[0]
                        + " ship-now=" + arrivedShip(carriedSlot, arranged.arShipId)
                        + " ship-at-arrival=" + arrived,
                carried);
    }

    /**
     * What one arrangement hands its assertions: a ship settled in a cell and then moved past that
     * cell's +X face, with everything needed to name it afterwards.
     *
     * <p>Extracted so a second scenario can put something ON that ship before the carry without
     * repeating sixty lines of on-ramp — and so both scenarios are demonstrably arranged the same
     * way, which is what makes the second one's extra witness attributable to the body rather than to
     * a difference in how its ship got there.</p>
     */
    private static final class ShipPastItsFace {
        /** The `entry-setup` reply; its slot dims are what {@link #loadAllEntrySlots} pumps. */
        final String setup;
        /** AR's DURABLE ship id — what the ledger is keyed by, and what survives the crossing. */
        final String arShipId;
        final String sourceCell;
        final int sourceSlot;
        /** The ship's live pose in its slot world, already past the face. */
        final double x, y, z;
        /**
         * The PHYSICS mod's id for this ship AS IT STANDS IN ITS CELL — captured at the settle pose,
         * where the slot holds exactly one ship and the count says so.
         *
         * <p>Not the id it was assembled under: entry is itself a crossing, so the craft that reached
         * the cell is already a different VS body from the one built in dim 0. And not good past the
         * seam either, for the same reason.</p>
         */
        final String settledVsId;

        ShipPastItsFace(String setup, String arShipId, String sourceCell, int sourceSlot,
                        double x, double y, double z, String settledVsId) {
            this.setup = setup;
            this.arShipId = arShipId;
            this.sourceCell = sourceCell;
            this.sourceSlot = sourceSlot;
            this.x = x;
            this.y = y;
            this.z = z;
            this.settledVsId = settledVsId;
        }
    }

    /** Build a ship, fly it into space through the production on-ramp, and move it past its +X face. */
    private ShipPastItsFace arrangeAShipPastItsFace() throws Exception {
        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // --- Arrangement: get a ship into a cell through the production on-ramp ------------------
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));

        // THIS TEST'S OWN SHIP, named by the assembler that built it. Everything downstream is asked
        // about THIS id and no other. The identity is not looked up — a lookup ("the first ledgered
        // ship", "the ship nearest the cell centre") answers with whatever it reaches first, which
        // stops being this craft the moment a second scenario shares the boot, and reads identically
        // when it does.
        String arShipId = extractString(asm, "shipId");
        assertTrue("the assembler did not name the ship it built, so this scenario has no way to "
                + "refer to its own craft: " + asm, arShipId != null);
        assertEquals("the pad carried more than one flight computer, so the id names one of several "
                + "craft: " + asm, 1, extractInt(asm, "afcCount"));

        // CONTROL: this ship is not in the ledger before it climbs, so the settle read below is a
        // real observation and not a first reading of something that was already there.
        String before = exec("artest space ledger-get " + arShipId);
        assertTrue("this ship is ledgered before it has flown: " + before,
                before.contains("\"found\":false"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        // TWO IDENTITIES, deliberately kept apart, and NEITHER of them is searched for. The DURABLE
        // id came from the assembler that built this craft. The PHYSICS id is minted asynchronously by
        // the physics mod and is replaced by every crossing, so it cannot be known in advance — but it
        // is TRANSLATED from the durable one through the registry, never found by proximity. Asking
        // either side with the other's id answers "not found" and reads exactly like the mechanic
        // being broken.
        String srcVsId = vsIdOf(0, arShipId);
        String srcInfo = exec("artest vs ship-info 0 id " + srcVsId);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");

        String heldInput = exec("artest vs ff-input-by-id 0 " + srcVsId + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                heldInput.contains("\"afcResolved\":true"));
        assertTrue("climb teleport failed",
                exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                        + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz)
                        .contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);

        // Waited on BY ID: the ledger is asked about this craft, not about how many ships it holds.
        final String[] status = {""};
        boolean settled = GameTicks.until(client(), GameTicks.server(), SETTLE_TICKS,
                () -> {
                    status[0] = exec("artest space ledger-get " + arShipId);
                    return status[0].contains("\"found\":true")
                            && "SETTLED".equals(extractString(status[0], "state"));
                },
                () -> loadAllEntrySlots(setup));
        assertTrue("the ship never reached space through the entry path; last ledger=" + status[0],
                settled);
        String sourceCell = extractString(status[0], "cell");
        assertTrue("the settled ship names no cell: " + status[0], sourceCell != null);
        int sourceSlot = extractInt(status[0], "slotDim");
        assertTrue("settled ship has no bound slot: " + status[0], sourceSlot > Integer.MIN_VALUE);
        assertTrue("the settled ship's cell world is not live", waitForLoadedShip(sourceSlot) >= 1);

        // LET GO OF THE STICK. The climb held full up to get past the entry ceiling, and the flight
        // computer RETAINS a cruise setpoint — so without this the craft is still under thrust for
        // everything that follows. Measured: it crosses the seam climbing at 0.667 blocks a tick, and
        // a body put down on its deck is 143 blocks below it by the time anything asks, which reads
        // as "the crossing dropped the cargo" and is nothing of the kind. This scenario is about what
        // a carry does with what is aboard; an accelerating deck is a different subject.
        String settledVsId = vsIdOf(sourceSlot, arShipId);
        String released = exec("artest vs ff-input-by-id " + sourceSlot + " " + settledVsId
                + " 0 0 0 0 0 0");
        assertTrue("the throttle could not be released, so the ship stays under power: " + released,
                released.contains("\"afcResolved\":true"));

        // --- Act: put the ship past the +X face of its cell --------------------------------------
        // The SAME durable craft, under the physics id translated above: entry is itself a crossing,
        // so the body that reached the cell is not the one that was built.
        String inCell = exec("artest vs ship-info " + sourceSlot + " id " + settledVsId);
        assertTrue("the settled ship is not managed in its slot: " + inCell,
                inCell.contains("\"managed\":true"));
        double cx = extractDouble(inCell, "posX"), cy = extractDouble(inCell, "posY"),
                cz = extractDouble(inCell, "posZ");
        assertFalse("the ship's in-cell pose could not be read: " + inCell,
                Double.isNaN(cx) || Double.isNaN(cy) || Double.isNaN(cz));

        String outward = exec("artest vs teleport-ship " + sourceSlot + " "
                + (long) cx + " " + (long) cy + " " + (long) cz + " "
                + PAST_THE_FACE + " " + (long) cy + " " + (long) cz);
        assertTrue("the move past the cell face failed: " + outward, outward.contains("\"ok\":true"));
        exec("artest vs unpark " + sourceSlot + " " + PAST_THE_FACE + " " + (long) cy + " " + (long) cz);

        // THE ARRANGEMENT IS ASSERTED, not assumed. "the probe returned ok" is not "the ship is past
        // the face": a clamp, a refused transform or a Y-limit would all report ok and leave the ship
        // inside its cell, and the carry would then be correctly not firing — a green mechanic
        // reported as a red one.
        String moved = exec("artest vs ship-info " + sourceSlot + " id " + settledVsId);
        double mx = extractDouble(moved, "posX");
        assertFalse("the moved ship's pose could not be read: " + moved, Double.isNaN(mx));
        assertTrue("the ship is not actually past the cell face after the move — it is at x=" + mx
                        + ", and the carry threshold is " + (GalacticCoord.HALF_CELL
                        + CellSeam.CARRY_MARGIN) + "; the test moved nothing: " + moved,
                mx > GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN);

        return new ShipPastItsFace(setup, arShipId, sourceCell, sourceSlot,
                mx, extractDouble(moved, "posY"), extractDouble(moved, "posZ"), settledVsId);
    }

    @After
    public void cleanup() throws Exception {
        exec("artest chunk release");
        exec("artest space entry-clear");
        exec("artest vs permaload false");
    }

    /** The three sector indices of a {@code sx_sy_sz} cell key. */
    private static long[] cellSectors(String cellKey) {
        Matcher m = CELL_KEY.matcher(cellKey);
        assertTrue("unparsable cell key: " + cellKey, m.matches());
        return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                Long.parseLong(m.group(3))};
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /**
     * The PHYSICS id of the craft this test built, in {@code slotDim} — translated from the durable
     * id the assembler handed back, never searched for.
     *
     * <p>This replaced a "the one ship in this slot, asked positionally and guarded by a count"
     * helper. The guard was honest as far as it went — a proximity answer IS an identity while there
     * is only one candidate — but the premise stops holding the moment a second scenario shares the
     * boot, and a cell-seam arrival is DETERMINISTIC, so scenarios pile up in exactly the same
     * places. A test that built its own craft never has to rely on being alone.</p>
     */
    /**
     * The ship this test built, as it stands in the slot it arrived in.
     *
     * <p>The durable id is the one thing about the craft that survives a crossing — the physics body
     * does not — so it is translated afresh in whichever world the question is about, and the report
     * is then asked BY that id. Nothing here is a search: no anchor, no radius, no "the one ship in
     * this slot".</p>
     */
    private String arrivedShip(int slotDim, String durableShipId) throws Exception {
        String vsId = vsIdOf(slotDim, durableShipId);
        String info = exec("artest vs ship-info " + slotDim + " id " + vsId);
        assertTrue("the arrived ship " + vsId + " is not managed in slot " + slotDim + ": " + info,
                info.contains("\"managed\":true"));
        return info;
    }

    private String vsIdOf(int slotDim, String durableShipId) throws Exception {
        String reply = exec("artest vs ship-uuid " + slotDim + " " + durableShipId);
        assertTrue("no physics ship in dim " + slotDim + " carries this test's durable id "
                + durableShipId + " — the craft is not there, or not assembled yet: " + reply,
                reply.contains("\"found\":true"));
        String vsId = extractString(reply, "id");
        assertTrue("the translation returned no id: " + reply, vsId != null);
        return vsId;
    }

    private void loadAllEntrySlots(String setup) throws Exception {
        Matcher m = Pattern.compile("\"dims\":\\[(-?\\d+),(-?\\d+)]").matcher(setup);
        if (m.find()) {
            exec("artest vs load-ships " + m.group(1));
            exec("artest vs load-ships " + m.group(2));
        }
    }

    private int waitForLoadedShip(int dim) throws Exception {
        final int[] loaded = {0};
        // Budgeted on the SERVER's clock, not on dim's: the world being asked about is precisely the
        // one that may not have started ticking yet, and budgeting against it would measure the wait
        // with the thing the wait is waiting for.
        GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") < 1) {
                return false;
            }
            exec("artest vs load-ships " + dim);
            loaded[0] = extractInt(exec("artest vs ship-count " + dim), "count");
            return loaded[0] >= 1;
        });
        return loaded[0];
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " "
                + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " "
                + (baseZ - 4) + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    /**
     * A number out of a probe reply, exponent form included — a coordinate past 10⁷ prints as
     * {@code 1.6000256E7}, and an extractor that cannot read that silently compares 1.6 against
     * sixteen million. NaN when absent, deliberately: the alternative (0.0) is a legal coordinate and
     * would make a missing field read as "the ship is at the origin".
     */
    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)")
                .matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
