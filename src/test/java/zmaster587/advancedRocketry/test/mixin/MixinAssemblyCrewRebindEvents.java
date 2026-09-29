package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.space.AssemblyCrewRebind;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The pre-assembly rebind QUEUE's own bookkeeping: an entry taken, and an entry let go.
 *
 * <h2>What this answers that four counters could not</h2>
 *
 * <p>The queue carries a seated pilot across an asynchronous ship assembly, retrying every server
 * tick until the relocated seat resolves or a ~60 s budget runs out. Production used to publish
 * {@code enqueuedCount}/{@code reboundCount}/{@code expiredCount}/{@code cancelledCount} and the
 * text of the most recent outcome, and every one of them was a total over the SERVER PROCESS: no
 * player, no stale mount, no queue entry. On a shared server a delta across one scenario's stimulus
 * therefore says only "the queue gave up on somebody", and the post-mortem string cannot separate
 * two entries at all when both scenarios build the same fixture at the same coordinates — the anchor
 * it prints is identical. *Measured on the 2026-09-06 gate: a hard assertion red on
 * {@code expired anchor=BlockPos{x=2802, y=69, z=2803}}, which was an earlier method's pilot
 * expiring inside this method's wait loop, while the entry the assertion was about had not been
 * queued long enough to expire at all.*</p>
 *
 * <p>Each record here carries {@code who} and {@code staleMount} — the pair that IDENTIFIES a queue
 * entry — plus the anchor and how many ticks it had been retried. That is what lets a scenario read
 * the give-ups of its own pilot instead of the server's.</p>
 *
 * <h2>Policy: PER-OCCURRENCE, and why that is affordable here</h2>
 *
 * <p>One record when an entry is queued and one when it ends, so at most two per pilot per assembly
 * — nothing like the per-tick retry. The retry itself is the neighbouring
 * {@code crew_rebind_decided}, which is deliberately EDGE-collapsed on its outcome because it
 * re-decides every tick. Both live beside each other on purpose: the decision log says what the
 * queue is waiting on, this says whether the entry still exists.</p>
 *
 * <p>Server tick only — the queue runs nowhere else — so the record is stamped with the overworld's
 * clock through {@link TestTrace#recordServer}. Test source set: absent from a released jar.</p>
 */
@Mixin(AssemblyCrewRebind.class)
public abstract class MixinAssemblyCrewRebindEvents {

    private static final String INSTRUMENT = "crew_rebind_queue_events";

    @Inject(method = "noteRebindQueue", at = @At("HEAD"))
    private static void arTest$rebindQueue(String outcome, UUID playerId, int staleDummyId,
                                           BlockPos anchor, int attempts, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordServer("crew_rebind_queue",
                "\"outcome\":\"" + TestTrace.json(String.valueOf(outcome)) + "\""
                        + ",\"who\":\"" + playerId + "\""
                        + ",\"staleMount\":" + staleDummyId
                        + ",\"anchor\":\"" + arTest$xyz(anchor) + "\""
                        + ",\"attempts\":" + attempts);
    }

    /** {@code "x,y,z"}, or {@code ""} for no position — the same shape the neighbouring rebind
     *  records use, so a reader comparing the two logs is comparing the same text. */
    private static String arTest$xyz(BlockPos pos) {
        return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
