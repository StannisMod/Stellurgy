package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * C035 — a declared AR sound played by the server must reach the
 * real client's {@code SoundManager}.
 *
 * <p><b>What this validates end-to-end</b>: the server plays
 * {@code AudioRegistry.combustionRocket} at the player's feet via the
 * {@code artest sound play} probe — the same static-field
 * {@code world.playSound} call the production sites use (rocket engine,
 * railgun, machine loops). Vanilla encodes the event's registry id into
 * {@code SPacketSoundEffect}; the client decodes it and asks its
 * {@code SoundManager} to play. The harness records that hand-off as the
 * client event {@code client_sound_played} — the honest "did it reach the
 * player's speakers" surface, and a LINK rather than a value, which is why it
 * is awaited from a mark rather than sampled.</p>
 *
 * <p>An UNREGISTERED SoundEvent encodes as registry id -1, decodes to
 * {@code null}, and the client's scheduled-task executor swallows the
 * resulting NPE — the sound silently never plays (and the client must stay
 * connected). That is today's player-facing symptom for 14 of the 15
 * declared AR sounds.</p>
 *
 * <p>Repro history: pre-fix this test pinned the wrong behaviour
 * ({@code combustionRocket} unregistered, the client SoundManager never asked
 * to play it, client surviving the null-sound packet); flipped to the
 * corrected contract with the C035 fix (Path B - the caller drops it).</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class AudioRegistrySoundReachesClientE2ETest extends AbstractClientE2ETest {

    /** ResourceLocation lowercases paths in this MC build, so the client-side
     *  observation (ISound.getSoundLocation) is all-lowercase regardless of the
     *  mixed-case sounds.json key. */
    private static final String COMBUSTION = "advancedrocketry:combustionrocket";

    /** A deadline for a discrete hand-off, not a guess at how long a value takes to settle: the
     *  packet leaves on the tick the probe runs and is decoded on one of the next few the client
     *  reads. */
    private static final int SOUND_BUDGET_TICKS = 100;

    @Test
    public void serverPlayedArSoundReachesClientSoundManager() throws Exception {
        // ARRANGEMENT: pin the player at a known spot so the 16-block sound broadcast
        // radius trivially covers the play position.
        serverClient().execute("tp @a 8.5 79 8.5");
        bot().waitTicks(5);

        // ARRANGEMENT GATE, and it is about the HOST, not about the subject: PlaySoundEvent only
        // fires once the client sound system initialised (SoundManager.loaded). Without an audio
        // device the recorder's seam is never reached at all, so the silence below would be the
        // host's and not the registry's — skip instead of misdiagnosing it as a regression.
        org.junit.Assume.assumeTrue(
                "client sound system not loaded (no audio device?) — PlaySoundEvent "
                        + "cannot be observed on this host",
                bot().reportSounds().get("managerLoaded").getAsBoolean());

        // The mark is taken BEFORE the stimulus. A play request is over between two samples: the
        // ring this used to poll could only ever answer "is it in the last 256 sounds now", which
        // cannot tell "it already happened" from "it never happened".
        long soundMark = clientMark();

        String played = String.join("\n", serverClient().execute(
                "artest sound play 0 8 79 8 combustionRocket"));
        assertTrue("sound play probe failed: " + played, played.contains("\"ok\":true"));
        assertTrue("combustionRocket must be present in ForgeRegistries at send time: "
                + played, played.contains("\"registered\":true"));

        // Contract: the sound reaches the real client's SoundManager — the client records the play
        // request it was handed, and a failure prints everything the client DID play since the mark
        // (and which observation points ran) instead of one stale ring read.
        awaitClientEvent(soundMark, "client_sound_played", "\"location\":\"" + COMBUSTION + "\"",
                "a registered AR sound played by the server must reach the real client's"
                        + " SoundManager", SOUND_BUDGET_TICKS);

        // And the round-trip must leave the client in the world: the pre-fix symptom of an
        // unresolvable sound was an NPE on the packet thread, and a client that has left the world
        // is the loud half of that. (This used to assert the bridge reply was non-null, which it
        // cannot be — ClientBot.reportState throws on a failed reply rather than returning null.)
        JsonObject state = bot().reportState();
        assertTrue("the client must still be in-world after the sound packet: " + state,
                state.get("worldReady").getAsBoolean());
    }

    // ---- the client event log, reached from a class with no shared base to put this on ----------

    /**
     * The client log's sequence, taken BEFORE the action under test — the client half of
     * {@code Events.mark()}, including its honesty check: a reply whose {@code recording} is false
     * means the harness never armed the log, and an empty log after such a mark would say "nobody
     * was listening", not "nothing happened".
     */
    private long clientMark() throws Exception {
        JsonObject reply = bot().eventMark();
        assertTrue("the CLIENT event recorder is not armed, so an empty client log below would mean"
                        + " nothing: " + reply,
                reply.has("recording") && reply.get("recording").getAsBoolean());
        return reply.get("seq").getAsLong();
    }

    /**
     * Wait for one record of {@code type} carrying {@code needle} on the CLIENT log, or fail naming
     * everything that log DID record since the mark.
     *
     * <p>{@code Events} speaks to the server probe; the client log is reached through the bot, and
     * this class extends the harness base rather than an AR shared base, so the helper lives here.
     * The reply it prints carries the log's own {@code recording} flag and its {@code instruments}
     * list, which is what keeps "the sound was never played" apart from "the recorder never ran".</p>
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
                + " on the CLIENT log within " + tickBudget + " ticks. An empty result here has four"
                + " causes and the reply tells them apart: `recording` false = the harness never"
                + " armed the log, the instrument missing from `instruments` = the observation point"
                + " never ran, a non-zero entry in `droppedByType` = the ring turned over (this type"
                + " also carries vanilla ambience and music), and none of those = it ran and the"
                + " sound never arrived. Matching records: " + reply
                + " | everything the client recorded since the mark: "
                + bot().eventsSince(mark, null));
    }
}
