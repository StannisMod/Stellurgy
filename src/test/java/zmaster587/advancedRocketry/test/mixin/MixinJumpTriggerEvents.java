package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.hyperdrive.JumpSpool;
import zmaster587.advancedRocketry.hyperdrive.JumpTrigger;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * What one press of the helm's jump key DID — the trigger's own verdict, as an event.
 *
 * <p><b>Why this and not the line the pilot reads.</b> A press has eight possible outcomes and
 * production names them ({@code SPOOLING}, {@code WARNED}, {@code NOT_ARMED}, {@code REFUSED},
 * {@code COMMITTED}, {@code ABORTED}, {@code FAILED}, {@code NOT_ON_SHIP}), each paired with a
 * message key. A test that read the CHAT to find out which branch a run took was reading a rendering
 * of this verdict: it moves when the language file moves, it cannot see a line the client's ring has
 * dropped, and two outcomes whose wording happens to overlap are one answer to it. The milestone did
 * exactly that to decide whether to press a second time.</p>
 *
 * <p>Both entry points are recorded, and they answer DIFFERENT questions: {@code press} is free and
 * may be repeated — it is what tells a pilot where he stands — while {@code commit} is the end of
 * the spool and is where a charge can be spent. {@code phase} says which, so a scenario walking the
 * confirm-then-commit path can tell the advisory press from the one that carried the ship.</p>
 *
 * <p>{@code msg} rides along as a payload FIELD and is never the thing to match on: it is the key
 * production paired with this outcome, which is worth having in a failure and is not the contract.</p>
 *
 * <p>Per occurrence, with no edge filter — a press is a player action rather than a per-tick sample,
 * so the ring holds far more of them than any scenario needs. Recorded on whichever side runs the
 * trigger; production drives it from the server.</p>
 */
@Mixin(JumpTrigger.class)
public abstract class MixinJumpTriggerEvents {

    private static final String INSTRUMENT = "jump_trigger_events";

    @Inject(method = "press", at = @At("RETURN"))
    private static void arTest$pressed(World world, BlockPos flightComputerPos, UUID shipId,
                                       JumpSpool spool, long now,
                                       CallbackInfoReturnable<JumpTrigger.Result> cir) {
        arTest$record("press", world, flightComputerPos, shipId, cir.getReturnValue());
    }

    @Inject(method = "commit", at = @At("RETURN"))
    private static void arTest$committed(World world, BlockPos flightComputerPos, UUID shipId,
                                         JumpSpool spool, long now,
                                         CallbackInfoReturnable<JumpTrigger.Result> cir) {
        arTest$record("commit", world, flightComputerPos, shipId, cir.getReturnValue());
    }

    private static void arTest$record(String phase, World world, BlockPos pos, UUID shipId,
                                      JumpTrigger.Result result) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("jump_press_decided",
                "\"phase\":\"" + phase + "\""
                        + ",\"outcome\":\"" + (result == null ? "null" : result.outcome()) + "\""
                        + ",\"msg\":\"" + TestTrace.json(result == null ? "" : result.langKey()) + "\""
                        + ",\"ship\":\"" + shipId + "\""
                        + ",\"dim\":" + (world == null
                                ? Integer.MIN_VALUE : world.provider.getDimension())
                        + ",\"afc\":\"" + pos + "\"");
    }
}
