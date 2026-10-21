package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The non-tickable spyTelescope. The quantity each data satellite collects is a law of its constructor
 * (`satellite` INV-SAT-34, pinned in the fast tier); how much it collects, with what power, is the lazy
 * accrual's (`satellite` MECH-SAT-24, pinned in the fast tier).
 */
public class ScanningSatelliteTickContractTest extends AbstractSharedServerTest {

    private static final String ID = "id";
    private static final String PRE_STORED = "preStored";
    private static final String POST_STORED = "postStored";
    private static final String CAN_TICK = "canTick";

    /**
     * Pin: SpyTelescope's {@code canTick()} returns false AND its
     * (empty) {@code tickEntity} body produces no observable state
     * change even when invoked directly.
     *
     * <p>Defense-in-depth complement to {@link
     * SatelliteCoverageGapsTest#satelliteWithCanTickFalseIsNotAddedToTickingList}.
     * That test pins the registration gate (the satellite stays out of
     * {@code tickingSatellites}); this one pins the inner contract that
     * the {@code tickEntity} body itself is also a no-op — so any
     * future regression that bypasses the registration gate (mod-compat
     * hook calling tickEntity directly, etc.) doesn't smuggle in state
     * changes.</p>
     */
    @Test
    public void spyTelescopeCannotTickAndDirectTickEntityIsNoOp() throws Exception {
        String createResp = String.join("\n", client().arrange(
                "stellurgytest satellite create-spy-telescope 0"));
        assertTrue("create-spy-telescope failed: " + createResp,
                Reply.of(createResp).ok());
        Reply mReply = Reply.of(createResp);
        assertTrue("could not extract id from create response: " + createResp,
                mReply.has(ID));
        long spyId = Long.parseLong(mReply.text(ID));
        assertEquals("spyTelescope MUST report canTick=false; " + createResp,
                "false", stringField(CAN_TICK, createResp, "canTick"));

        // Directly invoke tickEntity 10 times via the probe — the
        // probe doesn't gate on canTick (it tests the production
        // tickEntity body unconditionally). SpyTelescope's body is
        // empty (does NOT call super.tickEntity), so battery must not
        // change.
        String tickResp = String.join("\n", client().act(
                "stellurgytest satellite tick 0 " + spyId + " 10"));
        assertTrue("tick probe failed: " + tickResp,
                Reply.of(tickResp).ok());
        long preStored = longField(PRE_STORED, tickResp, "preStored");
        long postStored = longField(POST_STORED, tickResp, "postStored");
        assertEquals("spyTelescope tickEntity is an empty body — even when "
                        + "called directly via the probe (bypassing the "
                        + "canTick=false registration gate) it must produce "
                        + "no battery change; preStored=" + preStored
                        + " postStored=" + postStored,
                preStored, postStored);
    }

    // -- helpers ----------------------------------------------------------

    private long longField(String field, String src, String name) {
        Reply reply = Reply.of(src);
        assertTrue("field " + name + " missing in: " + src, reply.has(field));
        return (long) reply.integer(field);
    }

    private String stringField(String field, String src, String name) {
        Reply reply = Reply.of(src);
        assertTrue("field " + name + " missing in: " + src, reply.has(field));
        return reply.text(field);
    }
}
