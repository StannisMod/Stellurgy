package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration;

/**
 * Keep every server world ticking its entities and tile entities while the harness is running.
 *
 * <h2>What vanilla does, and why headless walks into it</h2>
 *
 * <p>{@code WorldServer.updateEntities} returns BEFORE {@code super.updateEntities()} — before a
 * single entity or tile entity in that world is ticked — whenever the world holds no player and no
 * persistent chunk, once {@code updateEntityTick} has reached 300. Fifteen seconds. It is vanilla's
 * own idling rule and in play it is almost invisible: a world nobody is in and nothing is holding
 * has nothing to simulate.</p>
 *
 * <p>A headless harness has NO PLAYER ANYWHERE, ever. So every world here is on that clock from the
 * moment it boots, and a scenario that reaches its subject more than fifteen seconds into a shared
 * server is testing a world that stopped. Nothing announces it. The tile is still there, still in
 * the ticking list, still in a loaded chunk, still inside the border — it is simply never asked.</p>
 *
 * <p><b>Measured 2026-09-12</b>, which is why this exists: three identical arrangements in one class
 * assembled a craft at overworld ticks ~7, ~116 and ~383. The first two flew to orbit through the
 * production entry on-ramp. The third's flight computer was given <b>zero</b> ticks, so nothing ever
 * called the on-ramp; the craft free-fell from y=1200 while the gate's own readout said
 * {@code wouldTrigger:true} three readings running. The red that came out said "the ship never
 * reached space through the entry path" and was diagnosed for a day as a broken entry path, a
 * capacity limit, and an exhausted slot pool — in that order.</p>
 *
 * <h2>Why a reset and not a cancel</h2>
 *
 * <p>This injects at the HEAD and calls production's own {@link WorldServer#resetUpdateEntityTick()}
 * — the very thing vanilla's {@code else} branch calls when a player or a ticket IS present. So the
 * gate below it evaluates normally and simply never trips; no branch is duplicated here, and if the
 * rule ever changes upstream this seam changes with it instead of disagreeing with it.</p>
 *
 * <p>The alternative considered and rejected was a Forge chunk ticket per world, which also resets
 * the counter — but a ticket FORCE-LOADS chunks, and chunk residency is a thing this tier tests
 * (bodies swept with their chunk, holds taken and released around a crossing). Buying a ticking
 * world with a changed chunk-loading regime trades one divergence from production for a larger one.
 * This changes exactly one thing.</p>
 *
 * <h2>THE DIVERGENCE THIS BUYS, stated rather than left to be discovered</h2>
 *
 * <p>With this on, the stand no longer reproduces an idle world. Anything whose subject IS the quiet
 * world — an unattended craft that should or should not keep station-keeping with nobody aboard and
 * nothing holding its chunks, a dimension left to go dormant — cannot be observed here, and a green
 * about it would be a green about this mixin. Such a scenario needs this seam made switchable and
 * turned OFF for its duration; there is no such scenario today, so no switch is invented for one.</p>
 *
 * <p>Gated on {@code isTestMode()} rather than on the mixin merely being present, so a dev client
 * that happens to load the test configuration does not silently acquire always-ticking worlds.</p>
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServerAlwaysTicks {

    @Inject(method = "updateEntities", at = @At("HEAD"))
    private void arTest$neverIdle(CallbackInfo ci) {
        if (!TestProbeCommandRegistration.isTestMode()) {
            return;
        }
        ((WorldServer) (Object) this).resetUpdateEntityTick();
    }
}
