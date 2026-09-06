package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Repro for findings C002 and
 * C155 (HIGH) — the MANDATORY player-visible client side.
 *
 * <p>{@code PacketSatellite.readClient} ({@code @SideOnly(CLIENT)}) is the
 * satellite-sync wire handler: it calls {@code SatelliteRegistry
 * .createFromNBT(nbt)} and catches only {@code IOException}
 * (PacketSatellite.java:50-55). When the packet carries a dataType the
 * client's registry does not know (a save/join with a different mod set —
 * the real cross-modpack case), {@code getNewSatellite} returns null and
 * {@code createFromNBT} NPEs; the NPE escapes {@code readClient}, propagates
 * up the netty pipeline, and disconnects/crashes the client.</p>
 *
 * <p>Stimulus is the REAL client wire path: the server broadcasts a
 * {@code PacketSatellite} with an unregistered dataType via the
 * {@code artest satellite announce-unknown} probe, and the client's own
 * {@code readClient} deserializes it.</p>
 *
 * <p><b>Corrected contract, pinned here (C002/C155 fix, Path B — drop)</b>:
 * the client stays in-world — {@code createFromNBT} returns null for the
 * unknown type and {@code readClient} skips it (explicit null-guard), so no
 * NPE escapes the packet handler and the connection survives. This test
 * previously pinned the disconnect (polarity flipped when the fix landed).
 * Recorded as a known defect.</p>
 *
 * <h2>The absence is only worth as much as the presence beside it</h2>
 *
 * <p>"The client did not disconnect" is satisfied by a client that was never
 * handed the packet at all — a broadcast that went nowhere, a channel that
 * dropped it, a decode that never ran. So the client's own registry lookup is
 * asserted FIRST ({@code satellite_nbt_resolved} for the bogus type, with
 * {@code resolved:false}): production looked this type up on THIS client and
 * came back empty, which is the branch the contract is about. Only then is the
 * silence read — and it is read as a count of {@code client_disconnected}
 * records, not as a sampled {@code worldReady}, because a disconnect and a
 * reconnect between two samples used to be indistinguishable from a client
 * that never left.</p>
 */
public class SatelliteUnknownTypeClientE2ETest extends AbstractClientE2ETest {

    /** The type the probe puts on the wire when none is given — nothing is registered under it. */
    private static final String BOGUS_TYPE = "advancedrocketry:unregistered.satellite.type.repro";

    /** A deadline for one packet's round trip and decode, not a settling value. */
    private static final int DECODE_BUDGET_TICKS = 120;

    /** How long the connection is watched AFTER the decode. The failure this pins is an NPE
     *  escaping the packet handler on the netty thread, so the disconnect follows the decode by a
     *  tick or two; this is a settle window, not a search. */
    private static final int SETTLE_TICKS = 60;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void unknownSatelliteTypeOnWireDoesNotCrashClient() throws Exception {
        // ARRANGEMENT GATE (harness): the subject is a client that is IN a world and must still be
        // in it afterwards, so it has to be in one first.
        bot().waitForWorld();

        JsonObject start = bot().reportState();
        assertTrue("client must begin in-world (worldReady=true): " + start,
                start.get("worldReady").getAsBoolean());

        // The mark goes before the broadcast. Both facts read below — the lookup that happened and
        // the disconnect that did not — are events, and an unmarked reader could not tell either
        // from one that fell between two samples. The mark also asserts the client log is armed,
        // which on this side is set from the harness coremod having queued its mixin configuration:
        // the same flag therefore rules out both silences (nobody listening, no mixin woven).
        long clientMark = clientMark();

        // Server announces a satellite whose type is not in the registry.
        String announce = exec("artest satellite announce-unknown 0");
        assertTrue("announce-unknown probe must succeed: " + announce,
                announce.contains("\"ok\":true"));

        // PRESENCE: the packet reached this client and its registry lookup came back empty — the
        // exact branch the contract is about. Without this the absence below is also satisfied by a
        // packet that was never delivered or never decoded.
        awaitClientEvent(clientMark, "satellite_nbt_resolved",
                "\"dataType\":\"" + BOGUS_TYPE + "\",\"resolved\":false",
                "an unknown satellite type must be LOOKED UP on the client and come back"
                        + " unresolved — that is the branch readClient must then drop",
                DECODE_BUDGET_TICKS);

        // PACING: give the failure its window. The pre-fix NPE escaped on the packet thread and the
        // disconnect followed within a tick or two of the decode above.
        bot().waitTicks(SETTLE_TICKS);

        // ABSENCE: production dropped the satellite instead of NPEing, so the client was never
        // disconnected. The log was proved to be listening twice over — its `recording` flag at the
        // mark, and the mixin-recorded lookup above landing in this very log inside this very
        // window — so an empty result here is the client's answer and not the instrument's.
        JsonObject disconnects = bot().eventsSince(clientMark, "client_disconnected");
        assertEquals("an unknown satellite type on the wire must NOT disconnect the client after the"
                        + " fix — PacketSatellite.readClient's createFromNBT returns null for the"
                        + " unresolved type and readClient skips it instead of NPEing. The client"
                        + " recorded a disconnection: " + disconnects,
                0, disconnects.get("count").getAsInt());

        // And the state the player would see agrees: still in a world, no disconnect screen.
        JsonObject end = bot().reportState();
        String screen = end.has("screen") ? end.get("screen").getAsString() : "";
        assertTrue("the client left the world after the unknown-type packet: " + end,
                end.get("worldReady").getAsBoolean()
                        && !screen.toLowerCase().contains("disconnect"));
    }

    // ---- the client event log, reached from a class with no shared base to put this on ----------

    /**
     * The client log's sequence, taken BEFORE the action under test — the client half of
     * {@code Events.mark()}, including its honesty check: {@code recording} false means the harness
     * never armed the log, and an empty log after such a mark would say "nobody was listening",
     * which is the one answer an instrument must not be able to fake.
     */
    private long clientMark() throws Exception {
        JsonObject reply = bot().eventMark();
        assertTrue("the CLIENT event recorder is not armed, so neither the lookup nor the absence of"
                        + " a disconnect below would mean anything: " + reply,
                reply.has("recording") && reply.get("recording").getAsBoolean());
        return reply.get("seq").getAsLong();
    }

    /**
     * Wait for one record of {@code type} carrying {@code needle} on the CLIENT log, or fail naming
     * everything that log DID record since the mark. Lives here because this class extends the
     * harness base rather than an AR shared base, and the client log is reached through the bot
     * rather than through the server probe {@code Events} speaks to.
     */
    private String awaitClientEvent(long mark, String type, String needle, String what,
                                    int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = String.valueOf(bot().eventsSince(mark, type));
            if (reply.contains(needle)) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying " + needle + " was recorded"
                + " on the CLIENT log within " + tickBudget + " ticks. An empty log here has three"
                + " causes and the reply tells them apart: `recording` false = the harness never"
                + " armed the log, the instrument missing from `instruments` = the observation point"
                + " never ran, and neither = it ran and saw nothing. Matching records: " + reply
                + " | everything the client recorded since the mark: "
                + bot().eventsSince(mark, null));
    }
}
