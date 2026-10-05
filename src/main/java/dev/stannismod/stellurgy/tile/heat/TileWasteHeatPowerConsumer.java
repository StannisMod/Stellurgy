package dev.stannismod.stellurgy.tile.heat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import dev.stannismod.stellurgy.api.capability.CapabilityHeatEmitter;
import dev.stannismod.stellurgy.subsystem.heat.WasteHeat;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiPowerConsumer;

/**
 * A powered machine whose work leaves heat behind.
 *
 * <p>It exists because a machine already has a parent and Java gives it only one: the accrual is a
 * {@link WasteHeat} component, and this class is the two lines of plumbing that let a machine hold
 * one without every machine repeating them. Everything a subclass has to do is extend this instead
 * of {@code TileMultiPowerConsumer}.
 *
 * <p>Spending is caught at {@code useEnergy}, which is the one place energy actually leaves a
 * machine's buffer. Catching it anywhere else — at a recipe, at a progress tick — would count work
 * that was planned rather than work that was done.
 */
public abstract class TileWasteHeatPowerConsumer extends TileMultiPowerConsumer {

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
