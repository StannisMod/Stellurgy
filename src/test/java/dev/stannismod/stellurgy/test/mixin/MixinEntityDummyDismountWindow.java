package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * {@code dismount_window_ended}: how the server's dismount window for a pilot closed — at the seam
 * production calls on every close, {@code EntityDummy#noteWindowEnded}.
 *
 * <p>The window is the server half of standing up from a linked pilot seat: for a short while it asks
 * the pilot's client to stand him on the seat's deck point. Its ends are the facts a scenario about
 * that window reads — each ends once, and says how: ran out, voided by a teleport, the pilot gone,
 * excluded, or no ship seat. Server side only; the window never runs on a client.</p>
 *
 * <p>Silent about the requests the window sent while open — those are the client's
 * {@code ship_frame_seed} outcomes.</p>
 */
@Mixin(value = EntityDummy.class, remap = false)
public abstract class MixinEntityDummyDismountWindow {

    @Inject(method = "noteWindowEnded", at = @At("HEAD"), require = 1)
    private static void stellurgyTest$windowEnded(Entity pilot, String how, CallbackInfo ci) {
        if (pilot == null || pilot.world == null || pilot.world.isRemote) {
            return;
        }
        TestTrace.instrument(pilot, "dismount_window_events");
        TestTrace.record(pilot, "dismount_window_ended",
                "\"e\":" + pilot.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(pilot.getName()) + "\""
                        + ",\"how\":\"" + TestTrace.json(how) + "\"");
    }
}
