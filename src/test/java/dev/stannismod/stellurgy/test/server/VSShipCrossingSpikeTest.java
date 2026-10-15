package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SPIKE — does a per-ship "crossing": snapshot ONE Valkyrien Skies
 * ship's subspace shipyard blocks (+ its TileEntities) with {@code StorageChunk}, deregister it, paste
 * the blocks elsewhere and re-assemble them into a fresh VS ship — preserve the ship's linked-TE state
 * and re-VS, carrying an aboard rider? This is the GO/NO-GO the transit subsystem is gated on: it decides
 * whether a jump can move a live piloted ship, or whether we fall back to whole-slot rebind.
 *
 * <p>Subject chosen to FALSIFY: a {@code with-pilot-seat} ship whose pilot seat is linked
 * to an Advanced Flight Computer at a fixed RELATIVE offset — cross-TE state that a scrambled pack/paste
 * would break. Witness: the seat still resolves its AFC (`afcResolved`) at the SAME relative offset after
 * the crossing. CONTROL: the seat probe reports {@code seatFound:false} before any ship exists,
 * proving the witness can report a negative.</p>
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise. This is
 * a spike test — if it goes GREEN the crossing is GO and its contract should be promoted into the transit
 * subsystem's own e2e; if it goes RED it records a NO-GO (fall back to whole-slot rebind).</p>
 */
public class VSShipCrossingSpikeTest extends AbstractSharedServerTest {

    /** World a ship is given to become loadable - the old 40 x 250 ms. */


    /** Where the piloted ship is built, and where it is crossed to (well separated, same loaded region). */
    private static final int SRC_X = 5000, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 5000;
    // Destination is up in clear sky (well above terrain) so the re-assembled ship is isolated from the
    // ground — VS's FIND_ALL_BLOCKS flood-fill must grab only the ship, not connect it to terrain.
    private static final int DST_X = 5064, DST_Y = 150, DST_Z = 5000;

    @Test
    public void aPilotedVsShipSurvivesAPerShipPackPasteCrossing() throws Exception {

        // A headless server has no player to hold a ship loaded, so a freshly assembled ship auto-unloads
        // between probe calls (its physics object drops out of the loaded set). Pin ships loaded so the
        // observations below are stable; reset in @After. (This is the permanentlyLoaded lever.)

        // CONTROL: asked of an id that names no craft, the seat witness must report a negative. This
        // proves a later "afcResolved:true" is a real observation, not a stuck-on witness.
        String nobody = java.util.UUID.randomUUID().toString();
        String control = arrange("stellurgytest vs seat-input-by-id 0 " + nobody + " 0 0 0 0 0 0").raw();
        assertTrue("witness sensitivity control — the seat probe must report no seat for a craft that"
                + " does not exist: " + control, !Reply.of(control).boolOr("seatFound", false));

        // Build a piloted ship (pilot seat linked to an AFC) at the source and assemble it into a VS ship.
        int[] bp = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-seat");
        buildMark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, bp, asm, USABLE_TICKS,
                "the piloted craft that crosses");
        builtDurable = birth.durable;
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());

        // The SOURCE ship, by the durable name its assembler minted. The crossing below re-assembles
        // the craft at the destination, so the destination's physics identity is read afresh; the
        // durable name is the one thing that spans them, and it is what the destination is found by.
        String durableId = birth.durable;
        String srcShipId = ArrangementFailure.arranged(
                () -> birth.awaitUsable(c -> read(c).raw(), events, USABLE_TICKS));
        assertTrue("source ship not managed by VS before crossing",
                ShipInfo.loadedIn(c -> read(c).raw(), 0, srcShipId));

        // BASELINE: the seat resolves its flight computer, and we record the RELATIVE offset between them
        // (invariant under any rigid relocation — the number the crossing must preserve).
        String pre = arrange("stellurgytest vs seat-input-by-id 0 " + srcShipId + " 0 0 0 0 0 0").raw();
        assertTrue("pre-crossing: seat must be found: " + pre, Reply.of(pre).bool("seatFound"));
        assertTrue("pre-crossing: seat must be linked to its AFC: " + pre, Reply.of(pre).bool("seatLinked"));
        assertTrue("pre-crossing: seat must resolve its AFC: " + pre, Reply.of(pre).bool("afcResolved"));
        int[] preOffset = seatToAfcOffset(pre);

        // Put a rider aboard (an EntityDummy bound to the pilot seat).
        SeatMount mount = SeatMount.onShip(c -> arrange(c).raw(), 0, srcShipId);
        assertTrue("could not seat a rider on the source ship: " + mount.raw(), mount.seatFound);

        // Locate the ship's live world position, by identity, then cross it to the destination.
        ShipInfo srcLive = ShipInfo.byId(c -> read(c).raw(), 0, srcShipId);
        double sx = srcLive.x, sy = srcLive.y, sz = srcLive.z;

        // The crossing CUTS a ship, so it is told which one; the source pose still travels with the
        // call because the riders aboard are gathered around it, but it no longer decides whose
        // blocks are taken.
        String cross = act("stellurgytest vs ship-repack 0 id " + srcShipId + " "
                + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + DST_X + " " + DST_Y + " " + DST_Z).raw();
        assertTrue("crossing failed (NO-GO signal): " + cross, Reply.of(cross).ok());
        // No assertion on HOW the source stops being a ship. This used to require the crossing to have
        // deregistered it itself, which pinned the mechanism rather than the promise - and the mechanism
        // it pinned was the one that leaked a ship per crossing. What the crossing owes is that the world
        // it left does not keep the ship it moved, and that is asserted where it belongs, in
        // VSCrossingLeavesNoShipBehindTest.
        assertTrue("crossing did not carry the aboard rider: " + cross,
                extractInt(cross, "ridersCarried") >= 1);

        // The ARRIVED ship, found by the durable name that crossed with it and linked on its own
        // announcement that it is usable at the destination: the name in the flight computer's NBT
        // rode across verbatim, so the destination is identified rather than approached.
        String dstShipId = ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events, 0, durableId,
                USABLE_TICKS);
        assertTrue("re-assembled ship is not managed by VS at the destination (crossing did not"
                + " re-VS); id=" + dstShipId, ShipInfo.loadedIn(c -> read(c).raw(), 0, dstShipId));

        // POST: the seat still resolves its AFC, at the SAME relative offset — the linked-TE state and the
        // ship's internal geometry survived the pack/paste round-trip. Asked of the ARRIVED ship by its
        // own id: the crossing mints a new one, so this is deliberately not srcShipId.
        String post = arrange("stellurgytest vs seat-input-by-id 0 " + dstShipId + " 0 0 0 0 0 0").raw();
        assertTrue("post-crossing: seat must be found: " + post, Reply.of(post).bool("seatFound"));
        assertTrue("post-crossing: seat must still be linked to its AFC: " + post,
                Reply.of(post).bool("seatLinked"));
        assertTrue("post-crossing: seat must still resolve its AFC: " + post, Reply.of(post).bool("afcResolved"));
        int[] postOffset = seatToAfcOffset(post);
        assertEquals("seat->AFC relative offset X changed across the crossing (geometry scrambled); pre="
                + java.util.Arrays.toString(preOffset) + " post=" + java.util.Arrays.toString(postOffset),
                preOffset[0], postOffset[0]);
        assertEquals("seat->AFC relative offset Y changed across the crossing", preOffset[1], postOffset[1]);
        assertEquals("seat->AFC relative offset Z changed across the crossing", preOffset[2], postOffset[2]);
    }

    /** Server ticks for the craft to be named and become usable, on either side of the crossing. */
    private static final int USABLE_TICKS = 200;

    /** The mark taken before this scenario's build, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
    }

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

    private static int[] seatToAfcOffset(String json) {
        return new int[]{
                extractInt(json, "afcX") - extractInt(json, "seatX"),
                extractInt(json, "afcY") - extractInt(json, "seatY"),
                extractInt(json, "afcZ") - extractInt(json, "seatZ"),
        };
    }

    /**
     * A string field of a probe reply. Fails loudly rather than answering with a placeholder: an
     * id that silently came back empty would be passed to a {@code -by-id} verb and read as "that
     * ship is not loaded", which is a different fact from "the reply carried no id".
     */
    private static String extractString(String json, String key) {
        String value = Reply.of(json).text(key);
        assertTrue("\"" + key + "\" came back empty in: " + json, !value.isEmpty());
        return value;
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }

    private static double extractDouble(String json, String key) {
        return Reply.of(json).number(key);
    }
}
