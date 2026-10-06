package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.api.capability.IPartWear;
import dev.stannismod.stellurgy.damage.RepairOutcome;
import dev.stannismod.stellurgy.libvulpes.util.EmbeddedInventory;
import dev.stannismod.stellurgy.tile.infrastructure.TileRocketServiceStation;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The two decisions of a rocket service station's own repair that nothing else announces, as events.
 *
 * <ul>
 *   <li>{@code service_station_outcome} — at the HEAD of {@code TileRocketServiceStation#settle},
 *       the one place the station's answer changes: {@code station} is its position, {@code from}
 *       what it said before and {@code to} what it says now ({@code none} for no answer). Recorded
 *       only when the two differ, so a station that keeps giving one answer writes it once.</li>
 *   <li>{@code service_station_restaged} — at the RETURN of {@code TileRocketServiceStation#restage},
 *       one stage taken off a part of the linked rocket: {@code part} is the part's position in the
 *       rocket's own storage, {@code stage} its stage now, {@code reserve} how many items the
 *       station's reserve holds and {@code energy} what its battery holds, both as the stage lands —
 *       so after whatever the step drew.</li>
 * </ul>
 *
 * <p>Server log, filed against the station's world. Read by the service station methods of
 * {@code RepairWelderE2ETest}.</p>
 */
@Mixin(value = TileRocketServiceStation.class, remap = false)
public abstract class MixinTileRocketServiceStationEvents {

    private static final String INSTRUMENT = "service_station_events";

    @Shadow
    private RepairOutcome outcome;

    @Shadow
    @Final
    private EmbeddedInventory repairInventory;

    @Inject(method = "settle", at = @At("HEAD"), require = 1)
    private void stellurgyTest$settled(RepairOutcome next, CallbackInfo ci) {
        TileEntity self = (TileEntity) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        if (outcome == next) {
            return;
        }
        TestTrace.record(world, "service_station_outcome", "\"dim\":" + world.provider.getDimension()
                + ",\"station\":\"" + at(self.getPos()) + "\""
                + ",\"from\":\"" + (outcome == null ? "none" : outcome.name()) + "\""
                + ",\"to\":\"" + (next == null ? "none" : next.name()) + "\"");
    }

    @Inject(method = "restage", at = @At("RETURN"), require = 1)
    private void stellurgyTest$restaged(TileEntity part, IPartWear wear, CallbackInfoReturnable<Boolean> cir) {
        TileEntity self = (TileEntity) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        IItemHandler reserve = repairInventory;
        int held = 0;
        for (int i = 0; i < reserve.getSlots(); i++) {
            held += reserve.getStackInSlot(i).getCount();
        }
        IEnergyStorage battery = self.getCapability(CapabilityEnergy.ENERGY, null);
        TestTrace.record(world, "service_station_restaged", "\"dim\":" + world.provider.getDimension()
                + ",\"station\":\"" + at(self.getPos()) + "\""
                + ",\"part\":\"" + at(part.getPos()) + "\""
                + ",\"stage\":" + wear.getStage()
                + ",\"reserve\":" + held
                + ",\"energy\":" + (battery == null ? -1 : battery.getEnergyStored()));
    }

    private static String at(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
