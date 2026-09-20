package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — C033 reproduction +
 * regression guard.
 *
 * <p>Contract under test: {@link zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine}
 * persists its scan/build status as a bare {@code ErrorCodes.ordinal()} and
 * decodes it with {@code ErrorCodes.values()[nbt.getInteger("status")]}. Two
 * defects on the read path are player-visible on load:</p>
 * <ul>
 *   <li><b>missing key → SUCCESS</b>: an absent {@code "status"} tag decodes to
 *       {@code getInteger}'s default 0, and {@code ErrorCodes[0] == SUCCESS} — a
 *       legacy/imported save loads a spurious SUCCESS verdict rather than the
 *       neutral idle state;</li>
 *   <li><b>out-of-range → crash</b>: a corrupt or mod-downgrade ordinal
 *       {@code >= values().length} throws {@code ArrayIndexOutOfBoundsException}
 *       inside {@code readFromNBT} → tile/chunk load abort.</li>
 * </ul>
 *
 * <p>The probe writes the live tile to NBT, mutates the {@code "status"} tag to
 * simulate those saves, reads it back into a fresh peer tile, and reports the
 * decoded status / any throwable. Post-fix a missing key defaults to
 * {@code UNSCANNED} and an out-of-range ordinal is clamped to {@code UNSCANNED}
 * without throwing; the wire/save format (an ordinal int) is unchanged.</p>
 */
public class AssemblerStatusNbtRoundtripTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String THREW = "threw";
    private static final String PEER_STATUS = "peerStatus";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int[] placeAssembler(int baseX) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 740);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");
        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        return new int[]{
                bp[0],
                bp[1],
                bp[2]};
    }

    private static String field(String field, String src, String name) {
        Reply reply = Reply.of(src);
        assertTrue("could not parse " + name + ": " + src, reply.has(field));
        return reply.text(field);
    }

    /** A save with no persisted status must load the neutral idle verdict
     *  (UNSCANNED), never a spurious SUCCESS. */
    @Test
    public void missingStatusKeyDecodesToUnscannedNotSuccess() throws Exception {
        int[] pos = placeAssembler(9600);
        String resp = ok(client().execute("artest assembler nbt-roundtrip 0 "
                + pos[0] + " " + pos[1] + " " + pos[2] + " dropStatus"));
        assertTrue("nbt-roundtrip failed: " + resp, Reply.of(resp).ok());

        assertTrue("no throw expected for a missing status key: " + resp,
                "null".equals(field(THREW, resp, "threw")));
        String peer = field(PEER_STATUS, resp, "peerStatus");
        assertTrue("a missing \"status\" key must decode to UNSCANNED, not the "
                        + "ordinal-0 SUCCESS default (C033); got " + peer + ": " + resp,
                "UNSCANNED".equals(peer));
    }

    /** An out-of-range persisted ordinal must clamp to UNSCANNED, not throw
     *  AIOOBE inside readFromNBT (which would abort tile/chunk load). */
    @Test
    public void outOfRangeStatusOrdinalDoesNotCrashLoad() throws Exception {
        int[] pos = placeAssembler(9700);
        String resp = ok(client().execute("artest assembler nbt-roundtrip 0 "
                + pos[0] + " " + pos[1] + " " + pos[2] + " setStatus=999"));
        assertTrue("nbt-roundtrip failed: " + resp, Reply.of(resp).ok());

        assertTrue("an out-of-range status ordinal must not throw on load "
                        + "(no ArrayIndexOutOfBoundsException) (C033): " + resp,
                "null".equals(field(THREW, resp, "threw")));
        String peer = field(PEER_STATUS, resp, "peerStatus");
        assertTrue("an out-of-range ordinal must clamp to UNSCANNED; got "
                        + peer + ": " + resp,
                "UNSCANNED".equals(peer));
    }

    /** Counter-test: an unmutated round-trip must still decode a valid status
     *  without throwing — the read guard must not break the happy path. */
    @Test
    public void plainRoundtripPreservesStatus() throws Exception {
        int[] pos = placeAssembler(9800);
        String resp = ok(client().execute("artest assembler nbt-roundtrip 0 "
                + pos[0] + " " + pos[1] + " " + pos[2]));
        assertTrue("nbt-roundtrip failed: " + resp, Reply.of(resp).ok());

        assertTrue("plain round-trip must not throw: " + resp,
                "null".equals(field(THREW, resp, "threw")));
        String peer = field(PEER_STATUS, resp, "peerStatus");
        assertTrue("plain round-trip must decode a non-empty status: " + resp,
                peer != null && !peer.isEmpty());
    }
}
