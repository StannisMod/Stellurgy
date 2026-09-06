package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import zmaster587.advancedRocketry.test.Events;

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
        clientEvents().awaitCarrying(soundMark, "client_sound_played",
                "\"location\":\"" + COMBUSTION + "\"",
                "a registered AR sound played by the server must reach the real client's"
                        + " SoundManager (this type also carries vanilla ambience and music, so a"
                        + " non-zero droppedByType entry for it means the ring turned over)",
                SOUND_BUDGET_TICKS);

        // And the round-trip must leave the client in the world: the pre-fix symptom of an
        // unresolvable sound was an NPE on the packet thread, and a client that has left the world
        // is the loud half of that. (This used to assert the bridge reply was non-null, which it
        // cannot be — ClientBot.reportState throws on a failed reply rather than returning null.)
        JsonObject state = bot().reportState();
        assertTrue("the client must still be in-world after the sound packet: " + state,
                state.get("worldReady").getAsBoolean());
    }

    // ---- the client event log ------------------------------------------------------------------
    //
    // This class extends the harness base rather than an AR shared one, so it reaches the adapter
    // directly instead of through the shared base's clientEvents().

    private Events clientEvents() {
        return ClientEvents.of(bot());
    }

    /** The client log's sequence, taken BEFORE the action under test — {@link Events#mark} refuses
     *  it unless a recorder is subscribed, so an empty log afterwards cannot read as "it never
     *  happened" when the truth is "nobody was listening". */
    private long clientMark() throws Exception {
        return clientEvents().mark();
    }

}
