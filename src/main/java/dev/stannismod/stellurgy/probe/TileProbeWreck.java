package dev.stannismod.stellurgy.probe;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * What remains of the first probe on the ground: its record, until the wreck is broken open and the record
 * comes out as a flight recorder.
 */
public class TileProbeWreck extends TileEntity {

    private NBTTagCompound record = new NBTTagCompound();

    /** The record this wreck holds. A copy. */
    public NBTTagCompound record() {
        return record.copy();
    }

    void hold(NBTTagCompound record) {
        this.record = record.copy();
        markDirty();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("record", record.copy());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        record = nbt.getCompoundTag("record");
    }
}
