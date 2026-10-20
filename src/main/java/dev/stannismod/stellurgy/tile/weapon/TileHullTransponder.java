package dev.stannismod.stellurgy.tile.weapon;

import dev.stannismod.stellurgy.affs.item.ItemCodeDevice;
import dev.stannismod.stellurgy.affs.util.CodeUtils;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleLimitedSlotArray;
import dev.stannismod.stellurgy.libvulpes.util.MachineReach;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkNode;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.weapon.HullTransponderDomain;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * A ship's credential, as a block: one slot that holds one code device.
 *
 * <p>A hull carries no credential of its own, so under the hull rule
 * {@link dev.stannismod.stellurgy.weapon.HullAllegianceRule#TRANSPONDER} a ship is a friend when a
 * transponder aboard it holds a device carrying the asking installation's code. The credential stays
 * a physical object: take the device out, or the block, and the accreditation goes with it.</p>
 *
 * <p>The code is read off the device each time somebody asks, never copied onto this tile, so a code
 * changed on the device changes the hull's accreditation with no other step. It needs no power, no
 * cable and no tick — a transponder that could run flat would turn a supply fault into a ship its own
 * side shoots at.</p>
 */
public class TileHullTransponder extends TileEntity implements IInventory, IModularInventory, ISubsystemNetworkNode {

    private static final String NBT_DEVICE = "device";

    private ItemStack device = ItemStack.EMPTY;

    /**
     * The code the held device carries, normalised; empty when it holds none. Read off the device now —
     * the one place the credential lives.
     */
    public String getCarriedCode() {
        return device.getItem() instanceof ItemCodeDevice ? CodeUtils.getCode(device) : "";
    }

    // ---- an index entry, so the hull rule can find what is loaded

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).register(this);
        }
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).unregister(this);
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).unregister(this);
        }
        super.onChunkUnload();
    }

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return HullTransponderDomain.INSTANCE;
    }

    @Override
    public World getNodeWorld() {
        return world;
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    // ---- the one slot

    @Override
    public int getSizeInventory() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return device.isEmpty();
    }

    @Override
    @Nonnull
    public ItemStack getStackInSlot(int index) {
        return index == 0 ? device : ItemStack.EMPTY;
    }

    @Override
    @Nonnull
    public ItemStack decrStackSize(int index, int count) {
        if (index != 0 || device.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = device.splitStack(count);
        if (device.isEmpty()) {
            device = ItemStack.EMPTY;
        }
        markDirty();
        return taken;
    }

    @Override
    @Nonnull
    public ItemStack removeStackFromSlot(int index) {
        if (index != 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = device;
        device = ItemStack.EMPTY;
        markDirty();
        return taken;
    }

    @Override
    public void setInventorySlotContents(int index, @Nonnull ItemStack stack) {
        if (index != 0) {
            return;
        }
        device = stack;
        markDirty();
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean isUsableByPlayer(@Nonnull EntityPlayer player) {
        return MachineReach.reaches(player, this);
    }

    @Override
    public void openInventory(@Nonnull EntityPlayer player) {
    }

    @Override
    public void closeInventory(@Nonnull EntityPlayer player) {
    }

    /** A code device, and nothing else: the slot holds a credential, not storage. */
    @Override
    public boolean isItemValidForSlot(int index, @Nonnull ItemStack stack) {
        return index == 0 && stack.getItem() instanceof ItemCodeDevice;
    }

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {
    }

    @Override
    public int getFieldCount() {
        return 0;
    }

    @Override
    public void clear() {
        device = ItemStack.EMPTY;
        markDirty();
    }

    @Override
    @Nonnull
    public String getName() {
        return getModularInventoryName();
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Override
    @Nonnull
    public ITextComponent getDisplayName() {
        return new TextComponentString(getName());
    }

    // ---- GUI

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>();
        modules.add(new ModuleLimitedSlotArray(80, 30, this, 0, 1));
        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockHullTransponder.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return isUsableByPlayer(entity);
    }

    // ---- persistence

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        if (!device.isEmpty()) {
            nbt.setTag(NBT_DEVICE, device.writeToNBT(new NBTTagCompound()));
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        device = nbt.hasKey(NBT_DEVICE) ? new ItemStack(nbt.getCompoundTag(NBT_DEVICE)) : ItemStack.EMPTY;
    }
}
