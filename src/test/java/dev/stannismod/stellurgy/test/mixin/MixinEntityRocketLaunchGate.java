package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.stannismod.stellurgy.api.StatsRocket;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The quantities a tier-1 rocket's weight-based launch gate is about to compare, recorded at the one
 * call that asks it.
 *
 * <p>{@code rocket_launch_gate} is written as {@code EntityRocket.launch} hands its argument to
 * {@code StatsRocket.canLaunch(float)} — its only caller — so the stats it carries are the ones the
 * gate reads: the launch has already re-derived thrust and, with the weight system on, the dry weight
 * from the craft's blocks, and the fuel the launch was given is in the tanks. The gravity is the
 * argument production passes, taken as it passes (a {@code @ModifyArg} that returns it unchanged),
 * never recomputed here. The OUTCOME is not here; it is the next record for the same entity, a
 * {@code rocket_aborted} carrying {@code error.rocket.tooHeavy} or a launch that went on past the
 * gate. Keeping the outcome out is what lets a test say that the gate decided, and not this mixin.</p>
 *
 * <p>Fields: {@code e} the entity; {@code gravity} the gravitational multiplier the launch passed;
 * {@code twr} the thrust-to-weight ratio at that gravity
 * ({@code StatsRocket.getThrustToWeightRatio(float)}, a {@code float} written as the EXACT
 * {@code double} it widens to, so a reader can hand the very number back as a config value and land
 * on the boundary without a rounding step); {@code thrust} ({@code getThrust()}, after the thrust
 * multiplier); {@code weight} ({@code getWeight()}, wet, before gravity); {@code minLaunchTWR} and
 * {@code weightSystem}, the two config values the gate reads.</p>
 *
 * <p>SILENT about a launch that is refused before the gate is reached (no destination, outside the
 * planetary system, worn parts) — no record is written for it — and about every other caller of
 * {@code canLaunch}: the assemblers ask it of a full-tank copy of the stats, and that verdict is
 * read off the scan status, not here. The extra reads of the stats are pure getters and change
 * nothing.</p>
 */
@Mixin(EntityRocket.class)
public abstract class MixinEntityRocketLaunchGate {

    private static final String INSTRUMENT = "rocket_launch_gate";

    @ModifyArg(method = "launch",
            at = @At(value = "INVOKE",
                    target = "Ldev/stannismod/stellurgy/api/StatsRocket;canLaunch(F)Z"),
            index = 0,
            require = 1)
    private float stellurgyTest$launchGateAsked(float gravity) {
        EntityRocket self = (EntityRocket) (Object) this;
        TestTrace.instrument(self, INSTRUMENT);
        if (self.world == null) {
            return gravity;
        }
        StatsRocket stats = self.stats;
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        TestTrace.record(self, "rocket_launch_gate", "\"e\":" + self.getEntityId()
                + ",\"gravity\":" + Double.toString((double) gravity)
                + ",\"twr\":" + Double.toString((double) stats.getThrustToWeightRatio(gravity))
                + ",\"thrust\":" + stats.getThrust()
                + ",\"weight\":" + Double.toString((double) stats.getWeight())
                + ",\"minLaunchTWR\":" + Double.toString(cfg.minLaunchTWR)
                + ",\"weightSystem\":" + cfg.advancedWeightSystem);
        return gravity;
    }
}
