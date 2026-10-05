package dev.stannismod.stellurgy.tile.heat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import dev.stannismod.stellurgy.api.capability.CapabilityHeatEmitter;
import dev.stannismod.stellurgy.subsystem.heat.WasteHeat;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine;

/**
 * A recipe machine whose work leaves heat behind.
 *
 * <p>The same two lines of plumbing as {@link TileWasteHeatPowerConsumer}, against the other parent:
 * {@code TileMultiblockMachine} is itself a {@code TileMultiPowerConsumer}, but Java's single
 * inheritance means the shared code cannot be shared, only repeated. It is repeated here rather than
 * in each of the ten machines that need it.
 */
public abstract class TileWasteHeatMachine extends TileMultiblockMachine {

    private final WasteHeat wasteHeat = new WasteHeat();

    @Override
    public void useEnergy(int amt) {
        super.useEnergy(amt);
        wasteHeat.spend(amt);
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityHeatEmitter.HEAT_EMITTER) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityHeatEmitter.HEAT_EMITTER) {
            return CapabilityHeatEmitter.HEAT_EMITTER.cast(wasteHeat);
        }
        return super.getCapability(capability, facing);
    }
}
