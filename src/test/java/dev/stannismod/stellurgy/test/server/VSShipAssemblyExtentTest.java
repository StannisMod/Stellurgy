package dev.stannismod.stellurgy.test.server;

import org.junit.Test;
import org.valkyrienskies.mod.common.ships.block_relocation.SpatialDetector;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * How large a hull the physics substrate turns into a ship: what it copies into the ship's shipyard,
 * and what it refuses instead.
 *
 * <p>NEW-GROUP: VS assembly by EXTENT — a hull's height above its flight computer and its reach to the
 * side, against the shipyard the substrate gives it. No server group holds VS assembly; the server
 * tier has no group classes yet, and the existing VS classes each own one crossing or flight path.</p>
 *
 * <p>The subject is {@code WorldServerShipManager#spawnNewShips} and the two decisions inside it: where
 * the found blocks land in the shipyard ({@code shipyardOffset}, with the claim sized by
 * {@code VSChunkClaim#RADIUS}), and whether the structure is refused ({@code spawnRefusal}). Each
 * scenario stands in its own empty space-pool world, because a hull reaching the full detection reach
 * is wider than any plot, and an empty world is where a crossing assembles a ship in play.</p>
 *
 * <p>What it observes: the substrate's own records, {@code ship_spawned} and {@code ship_spawn_refused},
 * for the spawn's outcome; and the blocks that physically stand in the ship's claim chunks
 * ({@code space yard-blocks}) for what was copied. It does NOT read the registry's block list for that,
 * because a block can be in that list without having been copied — which is the defect the second
 * scenario exists for.</p>
 *
 * <p>What it does not see: a crossing. The hull is handed to the assembly by probe, the way the rocket
 * assembler hands it, so the path by which a crossing reaches the same assembly is not walked here.</p>
 */
public class VSShipAssemblyExtentTest extends AbstractSharedServerTest {

    /** Long enough for the ship manager's next pass, which drains the spawn queue, with room to spare. */
    private static final int SPAWN_BUDGET_TICKS = 100;

    /** The height the flight computer stands at in every scenario; the world is empty around it. */
    private static final int DECK_Y = 100;

    /**
     * A hull standing higher above its flight computer than the shipyard's default placement leaves
     * room for is assembled, and every one of its blocks is copied.
     *
     * <p>Contract: this fails if {@code WorldServerShipManager#shipyardOffset} stops fitting a hull
     * into the world's height when it reaches more than half of it above its anchor.</p>
     *
     * <p>red-witnessed: with {@code WorldServerShipManager#shipyardOffset} at {@code if (minY <= maxY)}
     * made never to apply, the server's world tick threw {@code IllegalArgumentException: Cannot store
     * block position at <19200000,268,51200>} from {@code spawnNewShips}, the server crashed and the wait
     * failed with "Server bridge exchange failed" (2026-10-06).</p>
     */
    @Test
    public void aHullStandingFarAboveItsComputerIsAssembledWhole() throws Exception {
        int dim = emptyWorld();
        int top = 240; // 140 blocks above the computer: past the 128 the default placement leaves room for
        arrange("stellurgytest place " + dim + " 0 " + DECK_Y + " 0 stellurgy:advancedFlightComputer");
        arrange("stellurgytest fill " + dim + " 0 " + (DECK_Y + 1) + " 0 0 " + top + " 0 minecraft:stone");

        Assembled craft = assemble(dim, 0, DECK_Y, 0, 0, top, 0);

        assertSpawned(craft, "a hull 140 blocks tall above its flight computer must become a ship");
        assertEquals("every block of the hull must stand in the ship's shipyard after assembly — "
                        + "a shortfall is a block the copy lost",
                craft.built, yardBlocks(dim, craft.ship));
    }

    /**
     * A hull reaching the full detection reach to either side of its flight computer is assembled with
     * every block copied, none falling outside the ship's claim.
     *
     * <p>Contract: this fails if the claim {@code WorldServerShipManager#spawnNewShips} fills stops
     * covering everything {@code SpatialDetector} can find, i.e. if {@code VSChunkClaim#RADIUS} and
     * {@code SpatialDetector#MAX_REACH} come apart.</p>
     *
     * <p>red-witnessed: with {@code VSChunkClaim#RADIUS} at {@code (org.valkyrienskies.mod.common.ships.block_relocation.SpatialDetector.MAX_REACH + 15) / 16;}
     * lowered by one (back to the old 7), this fails with "expected:&lt;257&gt; but was:&lt;240&gt;" — the
     * 16 blocks at x = -128..-113 and the one at x = 128 copied outside the claim (2026-10-06).</p>
     */
    @Test
    public void aHullReachingTheFullDetectionReachKeepsEveryBlock() throws Exception {
        int dim = emptyWorld();
        int reach = SpatialDetector.MAX_REACH;
        arrange("stellurgytest place " + dim + " 0 " + DECK_Y + " 0 stellurgy:advancedFlightComputer");
        arrange("stellurgytest fill " + dim + " " + (-reach) + " " + DECK_Y + " 0 -1 " + DECK_Y
                + " 0 minecraft:stone");
        arrange("stellurgytest fill " + dim + " 1 " + DECK_Y + " 0 " + reach + " " + DECK_Y
                + " 0 minecraft:stone");

        Assembled craft = assemble(dim, -reach, DECK_Y, 0, reach, DECK_Y, 0);

        assertSpawned(craft, "a hull reaching " + reach + " blocks to each side must become a ship");
        assertEquals("every block of the hull must stand in the ship's shipyard after assembly — "
                        + "a shortfall is a block copied into a chunk the ship does not own",
                craft.built, yardBlocks(dim, craft.ship));
    }

    /**
     * A hull continuing past the detection reach is refused as a whole, and stays standing where it
     * was built — it is never cut down to the part the detection could see.
     *
     * <p>Contract: this fails if {@code WorldServerShipManager#spawnRefusal} stops refusing a structure
     * whose detection reported {@code SpatialDetector#reachExceeded}.</p>
     *
     * <p>red-witnessed: with {@code WorldServerShipManager#spawnRefusal} at {@code if (detector.reachExceeded)}
     * made never to refuse, this fails with "must be REFUSED by the substrate" and the outcome is a
     * {@code ship_spawned} record for the craft (2026-10-06).</p>
     */
    @Test
    public void aHullContinuingPastTheReachIsRefusedAndLeftStanding() throws Exception {
        int dim = emptyWorld();
        int past = SpatialDetector.MAX_REACH + 1;
        arrange("stellurgytest place " + dim + " 0 " + DECK_Y + " 0 stellurgy:advancedFlightComputer");
        arrange("stellurgytest fill " + dim + " 1 " + DECK_Y + " 0 " + past + " " + DECK_Y
                + " 0 minecraft:stone");

        Assembled craft = assemble(dim, 0, DECK_Y, 0, past, DECK_Y, 0);

        assertTrue("a hull reaching " + past + " blocks from its flight computer must be REFUSED by the"
                        + " substrate, not built from the part of it the detection reached; outcome: "
                        + craft.outcome,
                craft.refused());
        String reason = Events.text(craft.outcome, "reason");
        assertTrue("the refusal must say why: " + craft.outcome, reason != null && !reason.isEmpty());
        // The refusal record above is the decision being taken; a registration of the same craft
        // would be written by the same pass, so the window since the mark is the one to read.
        String window = craft.log.since(craft.mark);
        Events.assertInstrumentRan(window, "ship_spawn_pass", "the refused hull was not also registered");
        assertFalse("a refused hull must not ALSO be registered as a ship: " + window,
                Events.anyRecordHasAll(window, "type", "ship_spawned", "vsShip", craft.ship));
        for (int x : new int[]{1, past}) {
            assertEquals("the refused hull's block at x=" + x + " must still stand where it was built",
                    "minecraft:stone",
                    ask("stellurgytest space get-block " + dim + " " + x + " " + DECK_Y + " 0").text("block"));
        }
    }

    // ---- the chain every scenario shares ---------------------------------------------------------

    /** What came of handing a hull to the assembly: its name, how many blocks it was, and the record. */
    private static final class Assembled {
        final String ship;
        final int built;
        final long mark;
        final Events log;
        /** The {@code ship_spawned} or {@code ship_spawn_refused} record naming this craft. */
        final String outcome;

        Assembled(String ship, int built, long mark, Events log, String outcome) {
            this.ship = ship;
            this.built = built;
            this.mark = mark;
            this.log = log;
            this.outcome = outcome;
        }

        boolean refused() {
            return "ship_spawn_refused".equals(Events.text(outcome, "type"));
        }
    }

    /**
     * Hand the box to the assembly and wait for the substrate to decide: the first record naming this
     * craft as spawned or as refused closes the wait, so a refusal is read the moment it is made
     * rather than after the spawn budget runs out.
     */
    private Assembled assemble(int dim, int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        Events log = new Events(this::exec,
                ticks -> GameTicks.advance(client(), GameTicks.world(dim), ticks), evictionReports());
        long mark = log.markInstrumented();
        Reply handed = arrange("stellurgytest space assemble-box " + dim + " " + x1 + " " + y1 + " " + z1
                + " " + x2 + " " + y2 + " " + z2);
        String ship = handed.textOr("ship", null);
        assertNotNull("the box must hold a flight computer for the assembly to take it: " + handed, ship);
        String reply = log.awaitMatching(mark, null,
                since -> Events.anyRecordHasAll(since, "type", "ship_spawned", "vsShip", ship)
                        || Events.anyRecordHasAll(since, "type", "ship_spawn_refused", "vsShip", ship),
                "naming ship " + ship + " as spawned or refused",
                "the substrate must decide whether the hull handed to it becomes a ship",
                SPAWN_BUDGET_TICKS);
        Events.assertInstrumentRan(reply, "ship_spawn_pass", "the spawn pass decided about this hull");
        String outcome = null;
        for (String type : new String[]{"ship_spawned", "ship_spawn_refused"}) {
            java.util.List<String> named = Events.recordsWhereAll(reply, "type", type, "vsShip", ship);
            if (!named.isEmpty()) {
                outcome = named.get(named.size() - 1);
            }
        }
        return new Assembled(ship, handed.integer("built"), mark, log, outcome);
    }

    private static void assertSpawned(Assembled craft, String what) {
        assertFalse(what + " — the substrate REFUSED it: " + craft.outcome, craft.refused());
    }

    /** The blocks physically standing in the ship's claim chunks. */
    private int yardBlocks(int dim, String ship) throws Exception {
        Reply yard = arrange("stellurgytest space yard-blocks " + dim + " " + ship);
        assertTrue("the registry must know the ship it just announced: " + yard, yard.bool("known"));
        return yard.integer("yardBlocks");
    }

    /** A fresh, empty space-pool world of this scenario's own. */
    private int emptyWorld() throws Exception {
        int dim = arrange("stellurgytest space pool-register 1").intArray("dims")[0];
        Reply loaded = arrange("stellurgytest space load " + dim + " extent_" + scenarioName.getMethodName());
        assertTrue("the scenario's empty world must be loaded: " + loaded, loaded.bool("present"));
        return dim;
    }
}
