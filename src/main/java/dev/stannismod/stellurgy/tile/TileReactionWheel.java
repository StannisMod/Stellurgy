package dev.stannismod.stellurgy.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * The durable half of a reaction wheel: how much angular momentum it holds on each of its three
 * axes.
 *
 * <p>A wheel that has spun itself up turning the hull keeps that spin when the world is saved, when
 * its chunk unloads, and when the ship is cut out of one world and pasted into another — the tile's
 * NBT rides every one of those. Without it, a relog would be a free desaturation.</p>
 *
 * <p>The flight computer owns the live bookkeeping while the ship flies and writes it back here; this
 * tile only keeps the numbers.</p>
 */
public class TileReactionWheel extends TileEntity {

    private static final String NBT_MOMENTUM = "storedMomentum";

    /** N·m·s given to the hull along the ship frame's X, Y and Z. */
    private final double[] momentum = new double[3];

    public double momentum(int axis) {
        return momentum[axis];
    }

    public void setMomentum(int axis, double value) {
        if (momentum[axis] != value) {
            momentum[axis] = value;
            markDirty();
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setDouble(NBT_MOMENTUM + "X", momentum[0]);
        nbt.setDouble(NBT_MOMENTUM + "Y", momentum[1]);
        nbt.setDouble(NBT_MOMENTUM + "Z", momentum[2]);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        momentum[0] = nbt.getDouble(NBT_MOMENTUM + "X");
        momentum[1] = nbt.getDouble(NBT_MOMENTUM + "Y");
        momentum[2] = nbt.getDouble(NBT_MOMENTUM + "Z");
    }
}
