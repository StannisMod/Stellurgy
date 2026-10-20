package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * TileDockingPort persistence + network packet schema.
 *
 * <p>{@link dev.stannismod.stellurgy.tile.station.TileDockingPort}
 * holds two strings ({@code myIdStr}, {@code targetIdStr}) that
 * uniquely identify a station's docking port plus its pairing
 * target. Both strings drive the cross-station docking lookup; if
 * either is lost on save/load or scrambled by the network packet
 * layer, the docking pair silently breaks and players can't dock.
 * No test in the suite touches this tile pre-this-class.</p>
 *
 * <h2>Contracts pinned</h2>
 *
 * <ol>
 *   <li><b>NBT empty-string handling</b> — empty strings are NOT
 *       written ({@code if (!.isEmpty())} gates in
 *       {@code writeToNBT}).</li>
 *   <li><b>Network packet schema</b> — packet id 0 ships
 *       {@code myIdStr}, packet id 1 ships {@code targetIdStr}; the
 *       wire format writes a length-prefixed string and the reader
 *       expects exactly that schema.</li>
 * </ol>
 *
 * <p>Position-isolated at x=9000. Uses
 * {@link AbstractSharedServerTest} for one cold-start per class.</p>
 */
public class DockingPortNbtAndPacketTest extends AbstractSharedServerTest {

    /**
     * The smallest a packet carrying a string may be, in bytes.
     *
     * <p>The TEST'S OWN, and it is arithmetic rather than a tuning: a length prefix plus at least
     * one character cannot fit in four bytes, so a packet at or under this wrote nothing.</p>
     */
    private static final int MIN_STRING_PACKET_BYTES = 4;

    private static final int BASE_X = 9000;
    /**
     * The open-air band, not terrain. This was a hard-coded 64 until 2026-09-14 and the scenarios
     * never wanted ground: the subjects are an NBT round-trip and a packet schema, neither of which
     * can see what is under the block. What 64 bought was whatever the pinned seed rolled at
     * x=9000, unsurveyed and unasserted either way, so the port may have been standing in air or
     * replacing a block of the landscape and no reading here could tell. Nothing failed because of
     * it, which is precisely why it could stand: the landscape was never in the story. In the band
     * there is nothing to be inside of.
     */
    private static final int BASE_Y = FixtureSite.OPEN_AIR_Y;
    private static final int BASE_Z = 9000;

    private static final String MY_ID = "myId";
    private static final String TARGET_ID = "targetId";
    private static final String HAS_MY_ID_KEY = "hasMyIdKey";
    private static final String HAS_TARGET_ID_KEY = "hasTargetIdKey";
    private static final String DECODED_ID = "decodedId";
    private static final String PACKET_BYTES = "bytes";

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private void warmup(int blockX, int blockZ) throws Exception {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        String resp = join(client().arrange(
                "stellurgytest chunk warmup 0 " + (cx - 1) + " " + (cz - 1)
                        + " " + (cx + 1) + " " + (cz + 1)));
        assertTrue("chunk warmup failed: " + resp,
                Reply.of(resp).ok());
    }

    /** Place a TileDockingPort at the given coords. The block is
     *  registered as {@code stationMarker} (per Stellurgy's Stellurgy
     *  init), not {@code dockingPort} — the registry name and the
     *  tile-entity class name don't have to match in Forge. */
    private void placeDockingPort(int x, int y, int z) throws Exception {
        String resp = join(client().arrange(
                "stellurgytest place 0 " + x + " " + y + " " + z
                        + " stellurgy:stationMarker"));
        assertTrue("stationMarker place failed at (" + x + "," + y + "," + z
                        + "): " + resp,
                Reply.of(resp).bool("placed"));
    }

    private static String extract(String src, String field) {
        String value = Reply.of(src).text(field);
        return value;
    }

    private static boolean extractBool(String src, String field) {
        return Reply.of(src).bool(field);
    }

    @Test
    public void freshDockingPortOmitsEmptyStringKeysFromNbt() throws Exception {
        // A freshly-placed tile has myIdStr="" and targetIdStr="" (ctor
        // defaults). writeToNBT gates the NBT writes on !isEmpty, so the
        // keys must NOT appear.
        int x = BASE_X + 20;
        int y = BASE_Y;
        int z = BASE_Z;
        warmup(x, z);
        placeDockingPort(x, y, z);

        String rt = join(client().read(
                "stellurgytest docking-port nbt-roundtrip 0 " + x + " " + y + " " + z));
        assertTrue("nbt-roundtrip must succeed: " + rt,
                Reply.of(rt).ok());

        assertEquals("empty myIdStr must NOT be written to NBT",
                false, extractBool(rt, HAS_MY_ID_KEY));
        assertEquals("empty targetIdStr must NOT be written to NBT",
                false, extractBool(rt, HAS_TARGET_ID_KEY));
    }

    @Test
    public void networkPacketIdZeroShipsMyIdString() throws Exception {
        int x = BASE_X + 40;
        int y = BASE_Y;
        int z = BASE_Z;
        warmup(x, z);
        placeDockingPort(x, y, z);

        // Set myId so the packet has something to encode.
        assertTrue(Reply.of(join(client().arrange(
                "stellurgytest docking-port set-ids 0 " + x + " " + y + " " + z
                        + " gamma omega"))).ok());

        String rt = join(client().read(
                "stellurgytest docking-port packet-roundtrip 0 " + x + " " + y + " "
                        + z + " 0"));
        assertTrue("packet-roundtrip id=0 must succeed: " + rt,
                Reply.of(rt).ok());
        assertEquals("packet id=0 must carry myIdStr",
                "gamma", extract(rt, DECODED_ID));
        // The wire is length-prefixed: int (4 bytes) + utf8 bytes for "gamma" (5).
        // Pin "more than 4 bytes consumed" so we know the length prefix +
        // payload actually flowed.
        assertTrue("packet id=0 must consume > 4 bytes (length prefix + chars): "
                + rt, Integer.parseInt(extract(rt, PACKET_BYTES)) > MIN_STRING_PACKET_BYTES);
    }

    @Test
    public void networkPacketIdOneShipsTargetIdString() throws Exception {
        int x = BASE_X + 60;
        int y = BASE_Y;
        int z = BASE_Z;
        warmup(x, z);
        placeDockingPort(x, y, z);

        assertTrue(Reply.of(join(client().arrange(
                "stellurgytest docking-port set-ids 0 " + x + " " + y + " " + z
                        + " alpha beta"))).ok());

        String rt = join(client().read(
                "stellurgytest docking-port packet-roundtrip 0 " + x + " " + y + " "
                        + z + " 1"));
        assertTrue("packet-roundtrip id=1 must succeed: " + rt,
                Reply.of(rt).ok());
        assertEquals("packet id=1 must carry targetIdStr (not myIdStr)",
                "beta", extract(rt, DECODED_ID));
    }
}
