package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — EntityRocket SENDPLANETDATA reproduction + regression
 * guard (panel-surfaced pack-2 finding).
 *
 * <p>Two defects on the {@code EntityRocket} planet-selection wire path:</p>
 * <ul>
 *   <li><b>Bug A (server null deref)</b>: the SENDPLANETDATA server handler in
 *       {@code useNetworkData} dereferences {@code storage.getGuidanceComputer()}
 *       with no null guard. A satellite-only rocket (which legitimately has no
 *       guidance computer) NPEs when a destination is confirmed — reachable in
 *       ordinary play.</li>
 *   <li><b>Bug B (reader underflow)</b>: the server writer emits its planet-id
 *       int only when a chip is present, while the reader unconditionally
 *       {@code readInt()}s — a short/empty payload underflows the buffer
 *       (per-packet FML slice framing makes it throw rather than misread).</li>
 * </ul>
 *
 * <p>Post-fix a null guard makes the chipless server handler a no-op, and a
 * reader length guard tolerates an empty payload — neither changes the wire
 * format.</p>
 */
public class RocketSendPlanetDataNullGuidanceTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    private static final String THROWN = "thrown";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 760);
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
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];
        ok(client().execute("artest rocket assemble 0 " + bx + " " + by + " " + bz));
        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    /** Bug A: confirming a destination on a rocket with no guidance computer
     *  must not NPE the server. */
    @Test
    public void sendPlanetDataWithNullGuidanceDoesNotCrash() throws Exception {
        int rid = buildAndAssembleRocket(9500);

        String strip = ok(client().execute("artest rocket strip-guidance " + rid));
        assertTrue("strip-guidance failed: " + strip, Reply.of(strip).ok());
        assertTrue("guidance computer must be gone: " + strip,
                (!Reply.of(strip).bool("hasGuidanceComputer")));

        String resp = ok(client().execute("artest rocket send-planet-data " + rid + " 0"));
        assertTrue("send-planet-data failed: " + resp, Reply.of(resp).ok());

        Reply mReply = Reply.of(resp);
        assertTrue("thrown field missing: " + resp, mReply.has(THROWN));
        assertTrue("a SENDPLANETDATA packet for a guidance-computer-less rocket "
                        + "must not throw (Bug A); got " + mReply.reported(THROWN) + ": " + resp,
                "null".equals(mReply.text(THROWN)));
    }

    /** Bug B: the SENDPLANETDATA reader must tolerate an empty payload without
     *  underflowing the buffer. */
    @Test
    public void sendPlanetDataReaderToleratesEmptyPayload() throws Exception {
        int rid = buildAndAssembleRocket(9550);

        String resp = ok(client().execute("artest rocket planet-data-read-empty " + rid));
        assertTrue("planet-data-read-empty failed: " + resp, Reply.of(resp).ok());

        Reply mReply = Reply.of(resp);
        assertTrue("thrown field missing: " + resp, mReply.has(THROWN));
        assertTrue("reading a SENDPLANETDATA packet with an empty payload must not "
                        + "underflow the buffer (Bug B); got " + mReply.reported(THROWN) + ": " + resp,
                "null".equals(mReply.text(THROWN)));
    }
}
