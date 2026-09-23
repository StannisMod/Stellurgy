package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.test.trace.SeatDeliveryWindow;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * Names the gate that ate a pilot's input, without the seat keeping a single static — into every
 * open {@link SeatDeliveryWindow} on the seat's side.
 *
 * <h2>Three facts read from production's own calls</h2>
 *
 * <p>The verdict used to be a STRING composed inside the packet handler out of two locals. Nothing
 * here recomposes it from a parallel resolution. Each fact is taken where production itself answers
 * it: the guard at the return of {@code isPilotOf}, the resolve at the return of
 * {@code getFlightComputer}, delivery at the call that hands the input to the computer. The verdict
 * line is composed afterwards, at the handler's tail.</p>
 *
 * <p>Both resolvers are also asked by the HUD and the key context, many times a tick, so the two gate
 * hooks record only while a packet is in scope — opened at the handler's head, closed at its tail.
 * The scope is a field of THE SEAT handling the packet, which is the object the handler, the guard
 * and the resolve are all called on.</p>
 *
 * <h2>The resolver's own reading</h2>
 *
 * <p>{@code forRider} is the single oracle behind every "is this player piloting" check, and its
 * silent {@code null} was unattributable. The description is rebuilt at its RETURN from the same
 * PURE lookups it just made — a bound-seat getter, a tile lookup, a linked flag off the returned
 * seat. Nothing is re-resolved: the answers cannot differ from production's, because they are reads
 * of the same state in the same instant.</p>
 */
@Mixin(TilePilotSeat.class)
public abstract class MixinTilePilotSeatDiag {

    /** Whether a pilot-input packet is being handled by this seat right now, and what its two gates
     *  have answered so far. */
    @Unique
    private boolean arTest$inPacket;
    @Unique
    private boolean arTest$packetGuard;
    @Unique
    private boolean arTest$packetAfcResolved;

    @Inject(method = "useNetworkData", at = @At("HEAD"))
    private void arTest$packetArrived(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                      CallbackInfo ci) {
        TilePilotSeat self = (TilePilotSeat) (Object) this;
        if (self.getWorld() == null) {
            return;
        }
        if (id == TilePilotSeat.PACKET_PILOT_INPUT) {
            SeatDeliveryWindow.pilotInputArrived(self.getWorld());
            arTest$inPacket = true;
            arTest$packetGuard = false;
            arTest$packetAfcResolved = false;
        } else if (id == TilePilotSeat.PACKET_FLIGHT_ASSIST_TOGGLE
                || id == TilePilotSeat.PACKET_AUTO_TAKEOFF_TOGGLE
                || id == TilePilotSeat.PACKET_JUMP) {
            SeatDeliveryWindow.commandArrived(self.getWorld());
        }
    }

    @Inject(method = "useNetworkData", at = @At("TAIL"))
    private void arTest$packetHandled(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                      CallbackInfo ci) {
        if (!arTest$inPacket) {
            return;
        }
        arTest$inPacket = false;
        TilePilotSeat self = (TilePilotSeat) (Object) this;
        if (self.getWorld() != null) {
            SeatDeliveryWindow.pilotInputHandled(self.getWorld(),
                    "seat=" + arTest$xyz(self.getPos()) + " pilotGuard=" + arTest$packetGuard
                            + " afcResolved=" + arTest$packetAfcResolved);
        }
    }

    @Inject(method = "isPilotOf", at = @At("RETURN"))
    private void arTest$pilotGuard(EntityPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (arTest$inPacket) {
            arTest$packetGuard = cir.getReturnValue();
        }
    }

    @Inject(method = "getFlightComputer", at = @At("RETURN"))
    private void arTest$afcResolved(CallbackInfoReturnable<TileAdvancedFlightComputer> cir) {
        if (arTest$inPacket) {
            arTest$packetAfcResolved = cir.getReturnValue() != null;
        }
    }

    @Inject(method = "useNetworkData",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/tile/TileAdvancedFlightComputer;"
                            + "setPilotInput(Lzmaster587/advancedRocketry/api/FreeFlightInput;)V"))
    private void arTest$inputDelivered(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                       CallbackInfo ci) {
        TilePilotSeat self = (TilePilotSeat) (Object) this;
        if (self.getWorld() != null) {
            SeatDeliveryWindow.pilotInputDelivered(self.getWorld());
        }
    }

    @Inject(method = "forRider", at = @At("RETURN"))
    private static void arTest$riderResolved(Entity riding, World world,
                                             CallbackInfoReturnable<TilePilotSeat> cir) {
        if (!(riding instanceof EntityDummy) || world == null) {
            return; // production returned before resolving anything; there is nothing to describe
        }
        BlockPos bound = ((EntityDummy) riding).getSeatPos();
        BlockPos seatPos = bound != null ? bound : new BlockPos(riding);
        TileEntity te = world.getTileEntity(seatPos);
        TilePilotSeat seat = cir.getReturnValue();
        SeatDeliveryWindow.riderResolved(world, "bound=" + (bound == null ? "null" : arTest$xyz(bound))
                + " lookup=" + arTest$xyz(seatPos)
                + " tile=" + (te == null ? "null" : te.getClass().getSimpleName())
                + " linked=" + (seat != null && seat.isLinked())
                + " remote=" + world.isRemote);
    }

    private static String arTest$xyz(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }
}
