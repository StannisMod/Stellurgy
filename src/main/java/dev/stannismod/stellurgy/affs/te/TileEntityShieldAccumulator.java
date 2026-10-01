package dev.stannismod.stellurgy.affs.te;

import dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem;
import dev.stannismod.stellurgy.affs.config.ModConfig;
import dev.stannismod.stellurgy.affs.world.shield.ShieldNetworkManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.energy.EnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemSink;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemSource;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;

/**
 * Bulk shield-energy reserve. It is BOTH an {@link ISubsystemSource} and an {@link ISubsystemSink}: it fills
 * when the network has spare supply and drains when the network is under load. The shield network's
 * max-flow solve drives both roles (there is no per-tick self-logic here). Its own storage imposes no
 * per-tick throttle — the emitter coil's intake rate and the cables are the throttles; the accumulator
 * is a store of duration, not a rate.
 */
public class TileEntityShieldAccumulator extends TileEntity implements ISubsystemSource, ISubsystemSink {

    private final ShieldEnergyStorage storage =
            new ShieldEnergyStorage(ModConfig.accumulatorBuffer, ModConfig.accumulatorBuffer, ModConfig.accumulatorBuffer);
    private int shieldReceivedThisTick = 0;
    private int shieldExtractedThisTick = 0;

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).register(this);
            SubsystemNetworkManager.of(world).markDirty(ShieldNetworkManager.DOMAIN, world);
        }
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).unregister(this);
            SubsystemNetworkManager.of(world).markDirty(ShieldNetworkManager.DOMAIN, world);
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).unregister(this);
            SubsystemNetworkManager.of(world).markDirty(ShieldNetworkManager.DOMAIN, world);
        }
        super.onChunkUnload();
    }

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return ShieldNetworkManager.DOMAIN;
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    @Override
    public net.minecraft.world.World getNodeWorld() {
        return world;
    }

    // --- source: hand stored energy to the network under load --------------------------------------

    @Override
    public int getAvailable() {
        return storage.getEnergyStored();
    }

    @Override
    public int extract(int amount) {
        if (world == null || world.isRemote || amount <= 0) {
            return 0;
        }
        int extracted = storage.extractEnergy(amount, false);
        if (extracted > 0) {
            shieldExtractedThisTick += extracted;
            markDirty();
        }
        return extracted;
    }

    // --- sink: soak up spare supply ----------------------------------------------------------------

    @Override
    public int getRequested() {
        return getFreeCapacity();
    }

    @Override
    public int getFreeCapacity() {
        return Math.max(0, getEffectiveMaxShieldStored() - storage.getEnergyStored());
    }

    @Override
    public int receive(int amount) {
        if (world == null || world.isRemote || amount <= 0) {
            return 0;
        }
        int accepted = storage.receiveEnergy(amount, false);
        if (accepted > 0) {
            shieldReceivedThisTick += accepted;
            markDirty();
        }
        return accepted;
    }

    // --- Probe / read accessors ------------------------------------------------------------------

    public int getShieldStored() {
        return storage.getEnergyStored();
    }

    public int getMaxShieldStored() {
        return storage.getMaxEnergyStored();
    }

    /**
     * The reserve this accumulator can actually hold in the condition it is in — the rated capacity
     * scaled by its own damage stage. A battered bank stops accepting sooner, so a fight that damages
     * the storage shortens how long the shield can be held up afterwards.
     *
     * <p>What is already inside is not destroyed by the shrink: energy that was banked before the hit
     * is still there to spend, it simply cannot be topped back up to where it was.</p>
     */
    public int getEffectiveMaxShieldStored() {
        return dev.stannismod.stellurgy.affs.world.shield.ShieldCondition.derate(world, pos,
                storage.getMaxEnergyStored());
    }

    public int getShieldReceivedThisTick() {
        return shieldReceivedThisTick;
    }

    public int getShieldExtractedThisTick() {
        return shieldExtractedThisTick;
    }

    // --- NBT (symmetric) -------------------------------------------------------------------------

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("shield", storage.getEnergyStored());
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        storage.setEnergyStored(Math.max(0, Math.min(storage.getMaxEnergyStored(), compound.getInteger("shield"))));
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    private static final class ShieldEnergyStorage extends EnergyStorage {
        ShieldEnergyStorage(int capacity, int maxReceive, int maxExtract) {
            super(capacity, maxReceive, maxExtract);
        }

        void setEnergyStored(int value) {
            this.energy = value;
        }
    }
}
