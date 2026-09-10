package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.world.TimeSkipPolicy;

/**
 * Whether a world's clock may be SKIPPED, as the policy itself decides it — the mechanic behind
 * "you wake in the dark on a planet".
 *
 * <p><b>Why this event and not the sentence it produces.</b> The player learns of this rule from a
 * chat line, and the test that used to check it polled his chat for a fragment of that line thirty
 * times. A line is a RENDERING of this decision: it moves when the language file moves, it says
 * nothing about WHICH world was asked, and a client that has scrolled past it reads the same as one
 * that was never sent. The decision is the fact.</p>
 *
 * <h2>The seam, and why it is the right one</h2>
 *
 * <p>{@code allows(World)} is the LIVE decision — the overload that reads the config and the
 * provider — and production calls it from exactly two places: the sleep-skip redirect in
 * {@code MixinWorldServer} (so a record with that caller means a sleep actually completed AND the
 * policy answered) and {@code TimeCommandGuard}. Recording its RETURN reads the verdict where it is
 * made, with no local to capture. The four-argument overload beside it is pure arithmetic with no
 * world to name, and is unit-tested directly; it is deliberately NOT the seam.</p>
 *
 * <p>{@code caller} is what separates the two call paths, and it is a payload field rather than a
 * filter: a scenario that means the bed asks for the bed's trail, and one that means {@code /time}
 * asks for the guard's, but neither has to guess which arrived first.</p>
 *
 * <p>Not chatty: this is asked once per completed sleep and once per {@code /time}, so it is
 * recorded per occurrence with no edge filter and its ring holds far more history than any scenario
 * needs.</p>
 */
@Mixin(TimeSkipPolicy.class)
public abstract class MixinTimeSkipPolicyEvents {

    private static final String INSTRUMENT = "time_skip_policy_events";

    @Inject(method = "allows(Lnet/minecraft/world/World;)Z", at = @At("RETURN"))
    private static void arTest$decided(World world, CallbackInfoReturnable<Boolean> cir) {
        // Routed by the world when there is one: the policy is asked on the server, but a null world
        // is a real argument here (it answers permissively) and has no side to route by.
        if (world == null) {
            TestTrace.instrumentHere(INSTRUMENT);
            TestTrace.recordHere("time_skip_decided",
                    "\"dim\":" + Integer.MIN_VALUE + ",\"allowed\":" + cir.getReturnValue()
                            + ",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
            return;
        }
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("time_skip_decided",
                "\"dim\":" + world.provider.getDimension()
                        + ",\"allowed\":" + cir.getReturnValue()
                        + ",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }
}
