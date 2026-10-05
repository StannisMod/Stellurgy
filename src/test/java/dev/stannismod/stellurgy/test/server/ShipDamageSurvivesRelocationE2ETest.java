package dev.stannismod.stellurgy.test.server;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.WarShip;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A damaged ship that is RELOCATED stays damaged, and leaves nothing of its damage behind.
 *
 * <p>The stage of a block with no tile of its own is held by position, in the world the blocks
 * occupy. A ship's blocks live at fixed addresses in a shipyard subspace, and a relocation does not
 * move them — it cuts them out, pastes copies at fresh coordinates and assembles those into a new
 * ship at a new subspace address. Every one of those steps is a place where a position-keyed record
 * can be left behind, and a hull that arrives pristine is a repair the player did not pay for.</p>
 *
 * <h3>Why the count, and not one block's stage</h3>
 * <p>The subject is what the STRUCTURE carries. So the reading is the whole record set of the ship's
 * own yard, compared as a multiset of stages before and after — position-independent on purpose, since
 * the whole point is that the positions change.</p>
 *
 * <h3>The controls</h3>
 * <p>Two, and the test is worthless without them. The ship's subspace address must genuinely CHANGE
 * across the relocation, or both readings are of the same box and nothing was proven. And the source
 * yard must be EMPTY afterwards: a reading that only checks the destination cannot tell a carry from
 * a copy.</p>
 *
 * <p>The craft is built and addressed by identity ({@link WarShip}); the relocation names it by id,
 * and its arrival is the substrate's own registration of the re-assembled hull under the same durable
 * name. The class used to carry an {@code Assume} on a {@code vs available} verb that no longer exists,
 * so it had been SKIPPED on every run since that verb was removed.</p>
 */
public class ShipDamageSurvivesRelocationE2ETest extends AbstractSharedServerTest {

    /** Build site, clear of the other ship scenarios on this shared server. */
    private static final int SRC_X = 7600, SRC_Z = 7200;
    /** Where the relocation puts the ship down. Far enough that its new yard cannot be the old one. */
    private static final int DST_X = 7600, DST_Y = 96, DST_Z = 7400;
    /** A second build site, for the scenario that mines a block out of a ship instead of moving it. */
    private static final int AFC_X = 7800, AFC_Z = 7200;

    /**
     * Half-width of the box a yard is read through, around a block known to belong to the ship.
     * Derived from the fixture (~20 blocks across) against the separation between shipyards, which
     * are a chunk claim apart.
     */
    private static final int YARD_PROBE_RADIUS = 64;

    /** How much WORLD the re-assembled hull's registration is given — the tier's budget for it. */
    private static final int LINK_TICKS = 200;

    /**
     * The subject block, and it has to be ADDED: the rocket fixture is built entirely out of machines,
     * every one of which carries its own wear in its own tile NBT. A plain block has nowhere to put a
     * stage, which is the whole reason the map exists, so the craft is given one — one step east of
     * the pilot seat ({@code rocket = base + (3,1,3)}, seat at {@code rocket + (0,4,0)}), so the
     * assembly flood-fill welds it into the ship.
     */
    private static final String PLAIN_SUBJECT_BLOCK = "minecraft:iron_block";

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** A craft left behind goes on ticking in the world the next scenario runs in. */
    @Before
    public void disposeOfEarlierCraft() throws Exception {
        System.out.println("[reset] craft cleared: " + ShipReadiness.clearCraftFrom(this::exec, 0));
    }

    /**
     * red-witnessed: with {@code BlockDamageSavedData#move} at {@code if (from == null || to == null || from.equals(to))}'s {@code move} made a no-op, this fails
     * with "the relocated ship does not carry the damage it left with ... expected:&lt;[0/true/
     * stellurgy:advrocketmotor, ...4/true/minecraft:iron_block]&gt; but was:&lt;[]&gt;". The
     * left-behind verdict is held by TWO lines: {@code BlockDamageSavedData#move} at {@code Entry entry = entries.remove(from.toLong());} copying instead of
     * moving stayed GREEN on its own, because {@code StorageChunk#cutWorldBB} at {@code if (!worldObj.isRemote)}'s clear of the cut region
     * still emptied the old yard; with both broken it fails with "the relocation left 4 damage records
     * at the vacated subspace address 19200001,129,51200". 2026-09-30.
     */
    @Test
    public void aRelocatedShipCarriesItsDamageAndLeavesNoneBehind() throws Exception {
        ask("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        FixtureSite site = FixtureSite.openAir(0, SRC_X, SRC_Z);
        WarShip ship = WarShip.build(events, this::exec, site, builder -> addPlainSubjectBlock(site),
                "the craft whose damage is carried");

        // A block of this ship whose subspace address we know, used only as the anchor the yard is
        // read around. The shot below is aimed away from it so that it survives to be that anchor.
        int[] seat = ship.seat();

        Reply result = shootThePlainBlock(ship, seat, 77101);
        requireArranged("the impact spent nothing, so there is no damage to carry:\n" + result,
                result.integer("spent") > 0);

        List<String> before = yardDamage(seat);
        requireArranged("the shot left no record in the ship's yard, so this run measures nothing: it "
                + "struck only blocks that carry their own wear, or none at all.\n" + result,
                !before.isEmpty());
        Reply beforeRaw = rawYardDamage(seat);

        // THE RELOCATION — production's own crossing recipe: cut, paste, re-assemble. The hull that
        // comes out of it is a NEW physics object under the same durable name.
        Reply pose = ship.info();
        long moved = events.mark();
        Reply repack = ask("stellurgytest vs ship-repack 0 id " + ship.vsShip + " " + (int) pose.number("posX")
                + " " + (int) pose.number("posY") + " " + (int) pose.number("posZ") + " " + DST_X + " "
                + DST_Y + " " + DST_Z).requireOk("relocate the ship");
        String spawned = events.awaitRecordWithField(moved, "ship_spawned", "stellurgyShip", ship.durable,
                "the relocated ship never re-registered: " + repack, LINK_TICKS);
        String movedShip = Events.text(spawned, "vsShip");
        Reply movedSeat = Reply.of(exec("stellurgytest vs find-seat 0 id " + movedShip));
        assertTrue("could not locate the relocated ship's seat: " + movedSeat, movedSeat.bool("seatFound"));
        int[] newSeat = {movedSeat.integer("seatX"), movedSeat.integer("seatY"), movedSeat.integer("seatZ")};

        // ARRANGEMENT CONTROL. If the ship came back at the same subspace address, both readings are
        // of the same box and this test would pass with every carry deleted.
        requireArranged("the relocated ship kept its old subspace address (" + seat[0] + "," + seat[1]
                + "," + seat[2] + "): both readings are of the same box, so nothing here is evidence",
                newSeat[0] != seat[0] || newSeat[1] != seat[1] || newSeat[2] != seat[2]);

        List<String> after = yardDamage(newSeat);
        assertEquals("the relocated ship does not carry the damage it left with."
                + "\n  seat subspace before: " + seat[0] + "," + seat[1] + "," + seat[2]
                + "   after: " + newSeat[0] + "," + newSeat[1] + "," + newSeat[2]
                + "\n  records before: " + beforeRaw
                + "\n  records now in the new yard: " + rawYardDamage(newSeat)
                + "\n  records now in the OLD yard: " + rawYardDamage(seat), before, after);

        // CONTROL. Carried, not copied: what stayed behind would be inherited by the next ship built
        // at those coordinates.
        List<String> leftBehind = yardDamage(seat);
        assertTrue("the relocation left " + leftBehind.size() + " damage records at the vacated subspace"
                + " address " + seat[0] + "," + seat[1] + "," + seat[2] + ": " + leftBehind, leftBehind.isEmpty());
    }

    /**
     * No single block is the custodian of the hull's condition — specifically not the flight computer,
     * the one block a player can always reach and replace. Every step is a synchronous probe call on
     * the server thread, so each read follows the step it is about.
     *
     * <p>red-witnessed: with a line inserted at {@code TileAdvancedFlightComputer#invalidate} at {@code super.invalidate();} clearing
     * the damage map for 64 blocks round the computer when its tile is invalidated (the computer as the
     * hull's custodian), this fails with "replacing the flight computer changed the hull's damage
     * expected:&lt;[0/true/stellurgy:advrocketmotor, ...]&gt; but was:&lt;[]&gt;". 2026-09-30.</p>
     */
    @Test
    public void breakingAndReplacingTheFlightComputerDoesNotRepairTheHull() throws Exception {
        ask("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        FixtureSite site = FixtureSite.openAir(0, AFC_X, AFC_Z);
        WarShip ship = WarShip.build(events, this::exec, site, builder -> addPlainSubjectBlock(site),
                "the craft whose flight computer is swapped");
        int[] seat = ship.seat();

        // The fixture puts the flight computer one block west of and one below the seat, and an
        // assembly shifts every block of the craft by the same offset.
        int afcX = seat[0] - 1, afcY = seat[1] - 1, afcZ = seat[2];
        String afcBlock = stage(afcX, afcY, afcZ).text("block");
        // ARRANGEMENT CONTROL: without this the test would happily break a hull plate and prove nothing.
        requireArranged("the derived offset does not point at the flight computer but at " + afcBlock
                + " — the fixture layout this test assumes has changed",
                afcBlock.toLowerCase(java.util.Locale.ROOT).contains("flightcomputer"));

        Reply result = shootThePlainBlock(ship, seat, 77102);
        requireArranged("the impact spent nothing, so there is no damage to preserve:\n" + result,
                result.integer("spent") > 0);

        List<String> before = yardDamage(seat);
        requireArranged("the shot left no record, so this run measures nothing:\n" + result,
                !before.isEmpty());

        // Mine the flight computer out, and put an identical one back — the whole exploit, in two calls.
        String afcPos = afcX + " " + afcY + " " + afcZ + " " + afcX + " " + afcY + " " + afcZ;
        ask("stellurgytest fill 0 " + afcPos + " minecraft:air").requireOk("remove the flight computer");
        String removed = stage(afcX, afcY, afcZ).text("block");
        requireArranged("the flight computer is still standing (" + removed + "), so nothing was removed",
                "minecraft:air".equals(removed));
        ask("stellurgytest fill 0 " + afcPos + " " + afcBlock).requireOk("put the flight computer back");
        String replaced = stage(afcX, afcY, afcZ).text("block");
        requireArranged("the replacement flight computer is not there (" + replaced + ")",
                afcBlock.equals(replaced));

        assertEquals("replacing the flight computer changed the hull's damage", before, yardDamage(seat));
    }

    /** Weld one plain block onto the craft, face-adjacent to the pilot seat, before it is assembled. */
    private void addPlainSubjectBlock(FixtureSite site) throws Exception {
        int x = site.x + 4, y = site.y + 5, z = site.z + 3;   // rocket+(1,4,0) — one east of the seat
        ask("stellurgytest fill 0 " + x + " " + y + " " + z + " " + x + " " + y + " " + z + " "
                + PLAIN_SUBJECT_BLOCK).requireOk("add the plain subject block");
    }

    /**
     * Fire straight down through the plain block welded beside the seat, and confirm the shot reached
     * the ship. Aimed at that column rather than the seat's so the seat survives to be the anchor.
     */
    private Reply shootThePlainBlock(WarShip ship, int[] seat, int impactId) throws Exception {
        int subjX = seat[0] + 1, subjY = seat[1], subjZ = seat[2];
        // ARRANGEMENT CONTROL: if this is not the block we welded on, the shot below is aimed at
        // whatever the fixture happens to put there.
        String subject = stage(subjX, subjY, subjZ).text("block");
        requireArranged("the cell east of the seat is " + subject + ", not the plain block this test"
                + " welded on — the fixture layout it assumes has changed",
                PLAIN_SUBJECT_BLOCK.equals(subject));
        double[] world = ship.toWorld(subjX, subjY, subjZ);
        Reply result = ask("stellurgytest damage impact 0 " + world[0] + " " + (world[1] + 6.0D) + " "
                + world[2] + " 0 -1 0 200000 KINETIC " + impactId).requireOk("shoot the plain block");
        requireArranged("the impact resolved to no ship, so nothing on this hull was damaged:\n" + result,
                result.bool("onShip"));
        return result;
    }

    /**
     * What the ship's yard holds, as a sorted multiset of "stage/destroyed/block" readings —
     * deliberately without positions, because the positions are what a relocation changes.
     */
    private List<String> yardDamage(int[] anchor) throws Exception {
        Reply records = rawYardDamage(anchor);
        List<String> readings = new ArrayList<>();
        for (String entry : records.objectArray("entries")) {
            Reply one = Reply.of("a damage record", entry);
            readings.add(one.integer("stage") + "/" + one.bool("wasDestroyed") + "/" + one.text("destroyedBlock"));
        }
        assertEquals("the entry list and the count disagree: " + records, records.integer("count"),
                readings.size());
        Collections.sort(readings);
        return readings;
    }

    /** The same reading with its POSITIONS intact — for failure messages, where they are the evidence. */
    private Reply rawYardDamage(int[] anchor) throws Exception {
        return ask("stellurgytest damage records 0 " + (anchor[0] - YARD_PROBE_RADIUS) + " "
                + Math.max(0, anchor[1] - YARD_PROBE_RADIUS) + " " + (anchor[2] - YARD_PROBE_RADIUS) + " "
                + (anchor[0] + YARD_PROBE_RADIUS) + " " + (anchor[1] + YARD_PROBE_RADIUS) + " "
                + (anchor[2] + YARD_PROBE_RADIUS)).requireOk("read the yard's damage records");
    }

    private Reply stage(int x, int y, int z) throws Exception {
        return ask("stellurgytest damage stage 0 " + x + " " + y + " " + z).requireOk("read a stage");
    }
}
