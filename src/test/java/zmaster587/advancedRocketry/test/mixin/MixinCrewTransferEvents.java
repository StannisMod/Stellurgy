package zmaster587.advancedRocketry.test.mixin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.space.CrewTransfer;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The two places a tier-2 crew is put BACK on a ship that was rebuilt underneath it, as events —
 * read off the transfer's own RETURN, on the server, where the transfer lives.
 *
 * <h2>{@code crew_transfer_reseated}</h2>
 *
 * <p>{@code CrewTransfer.reseat} is the one re-seat every crossing ends in: the hyperspace arrival,
 * the cell crossing's arrival and the login restore all hand it a captured crew and an anchor on the
 * re-assembled hull, and it answers whether the WHOLE crew is back aboard. The record carries the
 * answer ({@code seated}), the crew it was asked about by name, the destination dimension, the
 * durable ship id the seats were filtered on ({@code ship}, {@code "null"} when the caller had none),
 * production's own account of what held a refusal up ({@code block} — {@code lastReseatBlock()},
 * written at the end of the same call), and the {@code caller} trail, because the same seam serves
 * three paths and a chain wants to know which one it is on.</p>
 *
 * <p>A crossing retries the re-seat every tick until it succeeds or the settle gives up (200
 * attempts), so a per-tick record would drown the chain. A success is always recorded; a refusal is
 * recorded the first time and whenever its {@code block} differs from the last one recorded for the
 * same {@code dim:ship:vsShip}, and a success forgets that key so the next crossing of the same ship
 * starts fresh. Same shape as the crosser's {@code crew_reseat_blocked}.</p>
 *
 * <h2>{@code crew_rebind_decided}</h2>
 *
 * <p>{@code CrewTransfer.rebindAcrossAssembly} re-expresses a PRE-ASSEMBLY boarding across the
 * assembly relocation: a pilot who sat down while his craft was still loose blocks rides a mount
 * bound to vacated coordinates, and this swaps it for one bound to the relocated seat. Its return is
 * the tri-state the pending queue debounces and retries on ({@code REBOUND}, {@code NOT_READY},
 * {@code NOT_ON_STALE_MOUNT}); the record carries that {@code outcome}, the {@code anchor} the ship
 * was assembled at, the player ({@code who}) and the dimension.</p>
 *
 * <p>Polled every server tick per pending pilot, so this too records an EDGE: the outcome is kept per
 * {@code who:staleDummy:anchor} and recorded when it differs from the last one recorded for that key;
 * a {@code REBOUND} is terminal for the entry and forgets the key. The stale dummy's id is in the key
 * because a new pre-assembly boarding mints a new dummy, which is what separates one episode from the
 * next.</p>
 *
 * <h2>What this is silent about</h2>
 *
 * <p>It cannot see the retry BUDGET — how many ticks a refusal was repeated before the caller gave up
 * is the caller's count, not this seam's — nor the debounce that turns a run of
 * {@code NOT_ON_STALE_MOUNT} into a cancelled entry. It says nothing about the client: a re-seat that
 * the server considers done is a mount the client has yet to be told about. And {@code reseat}'s
 * early return for an empty crew is recorded as a success with {@code crew:[]}; on that path the
 * {@code block} read is the previous call's and is therefore reported as {@code ""}.</p>
 *
 * <p>Both targets are {@code public static} and return a value, so both handlers are static and take
 * {@code CallbackInfoReturnable}; both fire at every return and read only arguments and the return
 * value (no local capture), which is what makes a multi-exit RETURN safe here.</p>
 */
@Mixin(CrewTransfer.class)
public abstract class MixinCrewTransferEvents {

    private static final String INSTRUMENT = "crew_transfer_events";

    /** {@code dim:ship:vsShip} → the last {@code block} recorded for a refused re-seat. */
    private static final Map<String, String> LAST_RESEAT_BLOCK = new HashMap<String, String>();

    /** {@code who:staleDummy:anchor} → the last outcome recorded for a pending rebind. */
    private static final Map<String, CrewTransfer.RebindOutcome> LAST_REBIND_OUTCOME =
            new HashMap<String, CrewTransfer.RebindOutcome>();

    @Inject(method = "reseat", at = @At("RETURN"))
    private static void arTest$reseated(WorldServer dstWorld, BlockPos anchor,
                                        List<CrewTransfer.Crew> crew, UUID expectedShipId,
                                        UUID vsShipUuid, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        boolean seated = cir.getReturnValueZ();
        int dim = dstWorld == null ? 0 : dstWorld.provider.getDimension();
        String key = dim + ":" + expectedShipId + ":" + vsShipUuid;
        String block = "";
        if (seated) {
            LAST_RESEAT_BLOCK.remove(key);
        } else {
            block = CrewTransfer.lastReseatBlock();
            if (block == null) {
                block = "";
            }
            if (block.equals(LAST_RESEAT_BLOCK.get(key))) {
                return; // same refusal as last tick — an edge is what the chain reads
            }
            LAST_RESEAT_BLOCK.put(key, block);
        }
        TestTrace.recordServer("crew_transfer_reseated", "\"dim\":" + dim
                + ",\"ship\":\"" + expectedShipId + "\",\"vsShip\":\"" + vsShipUuid
                + "\",\"crew\":" + arTest$names(crew)
                + ",\"seated\":" + seated
                + ",\"block\":\"" + TestTrace.json(block)
                + "\",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    @Inject(method = "rebindAcrossAssembly", at = @At("RETURN"))
    private static void arTest$rebindDecided(WorldServer world, BlockPos anchor,
                                             EntityPlayerMP player, int staleDummyId,
                                             int afcDx, int afcDy, int afcDz, UUID expectedShipId,
                                             CallbackInfoReturnable<CrewTransfer.RebindOutcome> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        CrewTransfer.RebindOutcome outcome = cir.getReturnValue();
        String who = player == null ? "" : player.getName();
        String key = who + ":" + staleDummyId + ":" + arTest$xyz(anchor);
        if (outcome == CrewTransfer.RebindOutcome.REBOUND) {
            LAST_REBIND_OUTCOME.remove(key); // terminal: the pending entry is dropped on it
        } else {
            if (outcome == LAST_REBIND_OUTCOME.get(key)) {
                return; // still waiting on the same thing as last tick
            }
            LAST_REBIND_OUTCOME.put(key, outcome);
        }
        int dim = world == null ? 0 : world.provider.getDimension();
        TestTrace.recordServer("crew_rebind_decided", "\"outcome\":\"" + outcome
                + "\",\"anchor\":\"" + arTest$xyz(anchor) + "\",\"who\":\"" + TestTrace.json(who)
                + "\",\"dim\":" + dim + ",\"ship\":\"" + expectedShipId + "\"");
    }

    /** The crew by name, as a JSON array — names, not a count, so a chain can say WHO was carried. */
    private static String arTest$names(List<CrewTransfer.Crew> crew) {
        StringBuilder sb = new StringBuilder("[");
        if (crew != null) {
            for (CrewTransfer.Crew rider : crew) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                String name = rider == null || rider.player == null ? "" : rider.player.getName();
                sb.append('"').append(TestTrace.json(name)).append('"');
            }
        }
        return sb.append(']').toString();
    }

    private static String arTest$xyz(BlockPos pos) {
        return pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
