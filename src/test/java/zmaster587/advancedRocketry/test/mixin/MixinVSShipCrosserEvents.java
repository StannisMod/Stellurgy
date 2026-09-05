package zmaster587.advancedRocketry.test.mixin;

import java.util.List;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.command.test.CrossingDiag;
import zmaster587.advancedRocketry.command.test.TestEventLog;
import zmaster587.advancedRocketry.space.CrewTransfer;
import zmaster587.advancedRocketry.space.HyperspaceTiles;
import zmaster587.advancedRocketry.space.ShipCrossingService;
import zmaster587.advancedRocketry.space.VSShipCrosser;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * What a hyperspace jump does to the HULL and to the PEOPLE, as events, at the seams where the
 * crosser answers for each.
 *
 * <h2>The chain a piloted jump is</h2>
 *
 * <pre>
 *   crew_captured -> hyperspace_depart_cut -> transit_departed -> crew_boarded_parked_hull
 *     -> hyperspace_arrival_cut -> crew_reseated -> transit_settled
 * </pre>
 *
 * <p>The two cuts are where a hull physically leaves one world and appears in another; the three
 * crew records are where the same people are picked up, put on the parked hull for the flight, and
 * put back at the far end. Every one of them is read off the crosser's own RETURN — the crossed
 * record, the captured list, the placement's boolean — so nothing here re-resolves what production
 * decided.</p>
 *
 * <h2>A placement that is not done yet is recorded when its REASON changes</h2>
 *
 * <p>Both placements are retried every tick until the re-assembled ship offers a seat, so a per-tick
 * record would drown the chain. A refusal is recorded the first time and whenever the placement's own
 * account of what it is waiting on ({@link CrewTransfer#lastReseatBlock()}) differs from the last one
 * recorded — that is the diagnosis a stalled arrival needs, and it is one line per change rather than
 * one per tick.</p>
 */
@Mixin(VSShipCrosser.class)
public abstract class MixinVSShipCrosserEvents {

    private static final String INSTRUMENT = "crossing_events";

    @Inject(method = "captureCrew", at = @At("RETURN"))
    private void arTest$crewCaptured(int srcSlotDim, BlockPos srcAnchor, String shipId,
                                     CallbackInfoReturnable<List<UUID>> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        List<UUID> crew = cir.getReturnValue();
        // `crew`, not `count`: a payload must never reuse an envelope key of the `events since` reply.
        arTest$record("crew_captured", "\"ship\":\"" + shipId + "\",\"srcSlotDim\":" + srcSlotDim
                + ",\"crew\":" + (crew == null ? 0 : crew.size()));
    }

    /** The hull left its cell for the lane — or did not, which is the record's {@code ok}. */
    @Inject(method = "departToHyperspace", at = @At("RETURN"))
    private void arTest$departCut(int srcSlotDim, BlockPos srcAnchor, String shipId,
                                  HyperspaceTiles.Tile tile,
                                  CallbackInfoReturnable<ShipCrossingService.Crossed> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        arTest$record("hyperspace_depart_cut", "\"ship\":\"" + shipId + "\",\"srcSlotDim\":"
                + srcSlotDim + "," + arTest$crossed(cir.getReturnValue()));
    }

    /** The hull left the lane for its destination cell — or did not. */
    @Inject(method = "arriveFromHyperspace", at = @At("RETURN"))
    private void arTest$arrivalCut(String shipId, HyperspaceTiles.Tile tile, BlockPos hyperAnchor,
                                   int targetSlotDim,
                                   CallbackInfoReturnable<ShipCrossingService.Crossed> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        arTest$record("hyperspace_arrival_cut", "\"ship\":\"" + shipId + "\",\"targetSlotDim\":"
                + targetSlotDim + "," + arTest$crossed(cir.getReturnValue()));
    }

    /** The departure-side placement: the crew seated on the hull parked in the lane. */
    @Inject(method = "boardCrew", at = @At("RETURN"))
    private void arTest$boarded(int parkedDim, BlockPos anchor, String shipId, UUID vsShipUuid,
                                CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        arTest$placement("boarding", "crew_boarded_parked_hull", "crew_boarding_blocked",
                parkedDim, shipId, cir.getReturnValueZ());
    }

    /** The arrival-side placement: the crew put back on the hull that has just landed. */
    @Inject(method = "reseatCrew", at = @At("RETURN"))
    private void arTest$reseated(int targetSlotDim, BlockPos arrivalAnchor, String shipId,
                                 UUID vsShipUuid, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        arTest$placement("reseat", "crew_reseated", "crew_reseat_blocked",
                targetSlotDim, shipId, cir.getReturnValueZ());
    }

    private static void arTest$placement(String leg, String doneType, String blockedType,
                                         int dim, String shipId, boolean done) {
        String key = leg + ":" + shipId;
        if (done) {
            CrossingDiag.clear(key);
            arTest$record(doneType, "\"ship\":\"" + shipId + "\",\"dim\":" + dim);
            return;
        }
        String block = CrewTransfer.lastReseatBlock();
        if (CrossingDiag.noteBlocked(key, block)) {
            arTest$record(blockedType, "\"ship\":\"" + shipId + "\",\"dim\":" + dim
                    + ",\"block\":\"" + TestTrace.json(block) + "\"");
        }
    }

    private static String arTest$crossed(ShipCrossingService.Crossed crossed) {
        if (crossed == null) {
            return "\"ok\":false";
        }
        BlockPos a = crossed.anchor;
        return "\"ok\":true,\"anchor\":\"" + (a == null ? "null"
                : a.getX() + "," + a.getY() + "," + a.getZ()) + "\",\"vsShip\":\""
                + crossed.vsShipUuid + "\"";
    }

    private static void arTest$record(String type, String payload) {
        TestEventLog.record("server", arTest$serverTick(), type, payload);
    }

    private static long arTest$serverTick() {
        WorldServer overworld = DimensionManager.getWorld(0);
        return overworld == null ? 0L : overworld.getTotalWorldTime();
    }
}
