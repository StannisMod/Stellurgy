package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.space.AboardBodies;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A crossing PUT ITS CARGO BACK — the moment a body that was riding a deck is a body in a world
 * again.
 *
 * <h2>Why this seam and not the callers'</h2>
 *
 * <p>{@code AboardBodies.release} is the one place a stowed body re-enters a world, and it has two
 * callers that are two different mechanics: the hyperspace jump's arrival
 * ({@code VSShipCrosser}) and the cell-seam carry's ({@code VSShipCrossingOps}). Three scenarios
 * across those two mechanics polled for the same fact — "is the body down yet, and is it aboard" —
 * by asking a probe once per step. One seam serves all three, and it is the seam that DECIDES
 * rather than a reading taken after it.</p>
 *
 * <h2>Why every call is recorded, including the ones that place nothing</h2>
 *
 * <p>The release is a RETRY: both callers ask again each tick until the arriving ship is rebuilt
 * enough to map a point on it, and until then this answers {@code 0} and the bodies stay out of the
 * world. Recording only the success would make the interesting failure silent — a carry that picked
 * the cargo up and never put it down looks, from outside, exactly like one that never picked it up.
 * So each attempt says how many it was HOLDING and how many it PLACED, and a scenario that times
 * out prints a trail of {@code placed:0} instead of an empty log.</p>
 *
 * <p>A call with nothing to place is not an attempt and is not recorded: the method answers {@code 0}
 * for an empty list before it looks at anything, and a ship that carried no cargo has no release to
 * report.</p>
 *
 * <h2>Side and silence</h2>
 *
 * <p>Server-only by the signature — the destination is a {@code WorldServer} — and routed by that
 * world, so the record is filed against the world the bodies land in rather than the one they were
 * taken from. SILENT about: the STOW half (a body taken out of the source world; the callers' own
 * logs carry that, and the cargo stash probe answers what is held), WHICH bodies were placed (the
 * count is what the waits ask for; a scenario that needs identity asks {@code loose-body-find} once
 * the record has arrived, which is what they now do), and whether a placed body STAYS aboard on the
 * ticks after — that is the deck pass's, and it is what each scenario's own assertion reads.</p>
 */
@Mixin(AboardBodies.class)
public abstract class MixinAboardBodiesEvents {

    private static final String INSTRUMENT = "aboard_bodies_events";

    @Inject(method = "release", at = @At("RETURN"))
    private static void arTest$released(WorldServer dstWorld, BlockPos afcPos,
                                        List<AboardBodies.Stowed> bodies,
                                        CallbackInfoReturnable<Integer> cir) {
        if (dstWorld == null || afcPos == null || bodies == null || bodies.isEmpty()) {
            return; // nothing to place is not an attempt to place
        }
        TestTrace.instrument(dstWorld, INSTRUMENT);
        TestTrace.record(dstWorld, "aboard_bodies_released",
                "\"dim\":" + dstWorld.provider.getDimension()
                        + ",\"afc\":\"" + afcPos.getX() + "," + afcPos.getY() + ","
                        + afcPos.getZ() + "\""
                        + ",\"stowed\":" + bodies.size()
                        + ",\"placed\":" + cir.getReturnValue());
    }
}
