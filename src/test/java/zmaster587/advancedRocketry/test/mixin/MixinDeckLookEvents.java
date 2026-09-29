package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.DeckLook;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The deck look, as a per-tick RECORD instead of three fields read across the socket.
 *
 * <h2>What this replaces, and why moving the fields would not have been enough</h2>
 *
 * <p>Seven assertions used to reach into this class with
 * {@code clientDouble("…client.DeckLook", "deckPitchDeg")} — the harness reading a private static by
 * name, reflectively, from another JVM. Two things are wrong with that and only one of them is the
 * static: <b>a reflective read answers with the value at the instant the socket happened to ask</b>.
 * A pitch that moved and came back between two polls never existed as far as the test is concerned,
 * and "the deck look was engaged during my roll" cannot be asked at all — only "it is engaged now".</p>
 *
 * <p>So this does not move the fields. It records what production DERIVED, once per client tick, on
 * the body it derived it for; a reader takes a mark and asks for its own window. That is the same
 * shape every other recorder in this package uses.</p>
 *
 * <p>The yaw and pitch are SHADOWED FIELDS, not accessors: production had two public getters whose
 * only callers were a test-mode log line in the camera handler and this mixin. The log line is gone,
 * and a getter kept public for a test is a test's surface in shipping code, so the getters went with
 * it. A mixin is merged into the class it targets, so it reads the private fields as that class
 * would.</p>
 *
 * <p>Injected at the RETURN of {@code clientTick}, which is where the tick's state has settled: both
 * branches of {@code sync} have run, and {@code derive} — the only writer of the yaw and pitch — is
 * the last call on the engaged path. The record therefore carries a consistent triple rather than
 * one taken mid-update.</p>
 *
 * <p>Client-only by construction: {@code clientTick} is called from the client tick path, and a
 * dedicated server never reaches this class.</p>
 */
@Mixin(DeckLook.class)
public abstract class MixinDeckLookEvents {

    /** Whether the deck look is engaged — production's own public accessor, which production itself
     *  calls; where an accessor exists, it is the contract. */
    @Shadow
    public static boolean isActive() {
        throw new AssertionError();
    }

    /** The held deck-frame yaw and pitch — the fields themselves; see the class note. */
    @Shadow
    private static volatile double deckYawDeg;
    @Shadow
    private static volatile double deckPitchDeg;

    @Inject(method = "clientTick", at = @At("RETURN"), remap = false)
    private static void arTest$deckLookTick(Entity player, CallbackInfo ci) {
        if (player == null || player.world == null || !player.world.isRemote) {
            return;
        }
        TestTrace.instrument(player, "deck_look_events");
        // Recorded every tick, engaged or not: "the look was never engaged during my window" and
        // "this tick did not run at all" are different answers, and only a record on both branches
        // can tell them apart. The ring turns over in about thirteen seconds at this cadence, which
        // is longer than any window that asks about a single manoeuvre.
        TestTrace.record(player, "deck_look",
                "\"e\":" + player.getEntityId()
                        + ",\"active\":" + isActive()
                        + ",\"deckYawDeg\":" + deckYawDeg
                        + ",\"deckPitchDeg\":" + deckPitchDeg);
    }
}
