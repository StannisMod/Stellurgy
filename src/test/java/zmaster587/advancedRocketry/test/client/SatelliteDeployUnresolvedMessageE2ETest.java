package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertTrue;

/**
 * Repro for finding C151 (LOW) — the
 * player-visible client side.
 *
 * <p>On orbit reach {@code EntityRocket.unpackSatellites} deploys each satellite
 * hatch. A hatch holding a chassis whose type no longer resolves
 * ({@code getSatellite()} returns null and it is not a station) fell through
 * every branch — the satellite was silently not deployed and the pilot got no
 * feedback.</p>
 *
 * <p>The natural trigger (a rocket reaching orbit with such a chassis) is not
 * producible in a stable single-config game, so the deploy path is driven one
 * hatch at a time via the {@code artest satellite deploy-unresolved} probe: it
 * mounts this real client on a rocket and calls the extracted, public
 * {@code EntityRocket.deploySatelliteFromHatch} with a bare (unresolvable)
 * satellite chassis.</p>
 *
 * <p><b>Corrected contract, pinned here (C151 fix, Path B)</b>: the pilot is
 * told the satellite could not be deployed (instead of silence). That is TWO
 * links, one per side, and they fail differently: production choosing to send
 * the notice ({@code chat_message_sent}, server log, carrying the translation
 * key it chose) and the pilot's own client being handed the resolved line
 * ({@code client_chat_received}, client log, carrying the text the player
 * reads). A notice that was composed and never delivered, and one that was
 * never composed, are different defects; the poll of the chat log this replaces
 * reported both as "the message never arrived".</p>
 */
public class SatelliteDeployUnresolvedMessageE2ETest extends AbstractClientE2ETest {

    /** The message production commits to when a hatch's type no longer resolves. */
    private static final String DEPLOY_FAILED_KEY = "msg.rocket.satelliteDeployFailed";

    /** What the pilot actually reads, once the client has resolved the key
     *  (en_us: "A satellite could not be deployed: its type is no longer available…"). */
    private static final String DEPLOY_FAILED_TEXT = "could not be deployed";

    /** A deadline for two discrete hand-offs — compose, send, decode, display — not a settling
     *  value. The loop this replaces allowed 80 ticks and described them as 8000. */
    private static final int MESSAGE_BUDGET_TICKS = 100;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void unresolvedSatelliteDeployNotifiesPilot() throws Exception {
        // ARRANGEMENT GATE (harness): the probe mounts THIS client on the rocket, so the client
        // must be in a world before the stimulus is sent.
        bot().waitForWorld();

        // Both marks BEFORE the stimulus. The notice is a hand-off that is over between two
        // samples; a mark taken first is what makes "it already happened" impossible to miss.
        // markInstrumented, not mark: the server link is recorded by a test-only MIXIN, so an empty
        // log has a second silent cause — the coremod never queued the mixin configuration — and
        // both must be ruled out before a silence is allowed to mean anything.
        Events events = new Events(this::exec, bot()::waitTicks);
        long serverMark = events.markInstrumented();
        long clientMark = clientMark();

        String resp = exec("artest satellite deploy-unresolved");
        assertTrue("deploy-unresolved probe must succeed: " + resp, resp.contains("\"ok\":true"));
        assertTrue("the probe must have mounted the pilot: " + resp, resp.contains("\"mounted\":true"));

        // Link 1 (server): production chose to tell the pilot, and told him THIS message.
        awaitServerRecord(events, serverMark, "chat_message_sent",
                "\"key\":\"" + DEPLOY_FAILED_KEY + "\"",
                "an unresolvable satellite chassis must make the rocket TELL its pilot rather than"
                        + " fail silently (C151)", MESSAGE_BUDGET_TICKS);

        // Link 2 (client): the pilot's own client was handed the line, i18n already resolved — the
        // player-visible half, and the one that also proves the key has a translation at all.
        awaitClientEvent(clientMark, "client_chat_received", DEPLOY_FAILED_TEXT,
                "the notice production sent must reach the pilot's chat as readable text",
                MESSAGE_BUDGET_TICKS);
    }

    // ---- the two logs, reached from a class with no shared base to put these on -----------------

    /**
     * The client log's sequence, taken BEFORE the action under test — the client half of
     * {@link Events#mark()}, including its honesty check: {@code recording} false means the harness
     * never armed the log, and an empty log after such a mark would say "nobody was listening".
     */
    private long clientMark() throws Exception {
        JsonObject reply = bot().eventMark();
        assertTrue("the CLIENT event recorder is not armed, so an empty client log below would mean"
                        + " nothing: " + reply,
                reply.has("recording") && reply.get("recording").getAsBoolean());
        return reply.get("seq").getAsLong();
    }

    /**
     * Wait for a record of {@code type} carrying {@code needle} on the SERVER log.
     *
     * <p>{@link Events#await} waits for the TYPE; here the type alone is not the link — a chat line
     * was sent, but this test is about one particular notice, and any other message in the window
     * would satisfy a type-only wait. Lives in this class because it extends the harness base
     * rather than an AR shared base.</p>
     */
    private String awaitServerRecord(Events events, long mark, String type, String needle,
                                     String what, int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, type);
            if (Events.countRecords(reply, needle) > 0) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying " + needle + " was recorded"
                + " on the SERVER log within " + tickBudget + " ticks. Records of that type: "
                + reply + " | everything the server recorded since the mark: " + events.since(mark));
    }

    /**
     * The same, on the CLIENT log, which is reached through the bot rather than through the probe.
     * The reply it prints carries both honesty flags a silence needs: {@code recording} (the log was
     * armed at all) and {@code instruments} (which observation points have actually executed).
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
        throw new AssertionError(what + " — no `" + type + "` carrying \"" + needle + "\" was"
                + " recorded on the CLIENT log within " + tickBudget + " ticks. Matching records: "
                + reply + " | everything the client recorded since the mark: "
                + bot().eventsSince(mark, null));
    }
}
