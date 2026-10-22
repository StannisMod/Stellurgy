package dev.stannismod.stellurgy.tile.satellite;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.api.DataStorage.DataType;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.satellite.IDataHandler;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.inventory.modules.ModuleData;
import dev.stannismod.stellurgy.inventory.modules.ModuleSatellite;
import dev.stannismod.stellurgy.inventory.modules.ModuleSatelliteReadout;
import dev.stannismod.stellurgy.inventory.modules.ModuleSatelliteTerminal;
import dev.stannismod.stellurgy.item.IDataItem;
import dev.stannismod.stellurgy.item.ItemSatelliteIdentificationChip;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiHandler;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.TileInventoriedRFConsumer;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.satellite.GroundContact;
import dev.stannismod.stellurgy.satellite.SatelliteData;
import dev.stannismod.stellurgy.satellite.SatelliteLife;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import dev.stannismod.stellurgy.util.IDataInventory;
import dev.stannismod.stellurgy.util.PlanetaryTravelHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Satellite Control Center: a ground terminal that receives from the satellite on its chip while the
 * satellite's dish can see it. It tries every {@link #POLL_PERIOD_TICKS} ticks, and when its player asks;
 * in a window it takes the satellite's whole data buffer and the journal entries and readings recorded
 * since its last contact, and outside one it says why nothing came.
 */
public class TileSatelliteTerminal extends TileInventoriedRFConsumer
        implements INetworkMachine, IModularInventory, IButtonInventory, IDataInventory, IDataHandler {

    /**
     * Ticks between two tries. A low orbit's window over one place lasts about a hundred ticks, so a try
     * this often catches every window. Each terminal tries on its own phase, so N terminals spread N tries
     * over the period instead of stacking them on one tick.
     */
    static final int POLL_PERIOD_TICKS = 16;

    /** Button ids; each is sent to the server as packet id 100 + the button. */
    private static final int BUTTON_CONNECT = 0;
    private static final int BUTTON_ERASE = 1;
    private static final int BUTTON_READOUT = 2;
    private static final int BUTTON_BACK = 3;

    /**
     * What the terminal's last try came to — what its screen says. The order is the wire: the status a
     * client is sent is the ordinal.
     */
    public enum Outcome {
        NO_SATELLITE,
        NO_POWER,
        OUT_OF_RANGE,
        NOT_IN_ORBIT,
        NO_PLACE,
        OUT_OF_SIGHT,
        /** In sight, but the satellite's primary function has stopped: it sends nothing. */
        SILENT,
        NOTHING_NEW,
        RECEIVED
    }

    private final DataStorage data;
    private final TerminalReadout readout = new TerminalReadout();
    private Outcome outcome = Outcome.NO_SATELLITE;
    /** Whether the reason this terminal has no place to see from has been logged; not persisted. */
    private boolean placeRefusalLogged;

    public TileSatelliteTerminal() {
        super(10000, 2);
        data = new DataStorage();
        data.setMaxData(1000);
    }

    public final DataStorage getDataObject() {
        return data;
    }

    /** What the last try came to. */
    public Outcome outcome() {
        return outcome;
    }

    /** What has been downloaded, and what the last contact brought. */
    public TerminalReadout readout() {
        return readout;
    }

    /** Effectively final, process lifetime: built once at class initialisation. */
    private static final int[] NO_SLOTS = new int[0];

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) { return NO_SLOTS; }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockSatelliteControlCenter.getLocalizedName();
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (slot == 0) return stack.getItem() instanceof ItemSatelliteIdentificationChip;
        if (slot == 1) return stack.getItem() instanceof IDataItem;
        return false;
    }

    @Override
    public boolean canPerformFunction() {
        if (world == null || world.isRemote || getStackInSlot(0).isEmpty()) return false;
        return Math.floorMod(world.getTotalWorldTime() + phase(), POLL_PERIOD_TICKS) == 0;
    }

    /** This terminal's own phase on the poll period, from its place. */
    private long phase() {
        return Math.floorMod(pos.hashCode(), POLL_PERIOD_TICKS);
    }

    @Override
    public int getPowerPerOperation() { return 1; }

    /** The poll, its power already spent by the caller. */
    @Override
    public void performFunction() {
        if (world == null || world.isRemote) return;
        settle(tryContact());
    }

    @Override
    public void notEnoughEnergyForFunction() {
        if (world != null && !world.isRemote) settle(Outcome.NO_POWER);
    }

    /** A try a player asked for: it costs the same power as a poll. */
    private void contactNow() {
        if (getUniversalEnergyStored() < getPowerPerOperation()) {
            settle(Outcome.NO_POWER);
            return;
        }
        energy.extractEnergy(getPowerPerOperation(), false);
        settle(tryContact());
    }

    /** Try the satellite on the chip: the first thing that stands in the way is what the screen says. */
    private Outcome tryContact() {
        SatelliteBase sat = getSatelliteFromSlot(0);
        if (sat == null) {
            return Outcome.NO_SATELLITE;
        }
        int hereDim = DimensionManager.getEffectiveDimId(world, pos).getId();
        if (!PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(sat.getDimensionId(), hereDim)) {
            return Outcome.OUT_OF_RANGE;
        }
        long now = SpaceSubsystem.spaceClock();
        UniverseRegistry reg = UniverseRegistry.get(world.getMinecraftServer());
        GroundContact.Answer sight = GroundContact.sightOf(reg, world.provider.getDimension(), pos, sat, now);
        switch (sight.sight) {
            case NOT_IN_ORBIT:
                return Outcome.NOT_IN_ORBIT;
            case NO_PLACE:
                if (!placeRefusalLogged) {
                    placeRefusalLogged = true;
                    Stellurgy.logger.warn("Satellite terminal at {} in world {} has no place to see from: {}",
                            pos, world.provider.getDimension(), sight.reason);
                }
                return Outcome.NO_PLACE;
            case HIDDEN:
                return Outcome.OUT_OF_SIGHT;
            default:
                break;
        }
        sat.bringUpToNow();
        if (!SatelliteLife.functioning(sat)) {
            return Outcome.SILENT;
        }
        int received = 0;
        int aboard = 0;
        int capacity = 0;
        if (sat instanceof SatelliteData) {
            SatelliteData dataSat = (SatelliteData) sat;
            int before = data.getData();
            dataSat.performAction(null, world, pos);
            received = data.getData() - before;
            aboard = dataSat.data.getData();
            capacity = dataSat.data.getMaxData();
        }
        TerminalReadout.Contact contact = readout.take(sat.getId(), sat.lifeProgress().journal(), sat.doseReadings(),
                received, (int) Math.round(sat.currentPowerPerTick()), aboard, capacity);
        return contact.broughtAnything() ? Outcome.RECEIVED : Outcome.NOTHING_NEW;
    }

    /** Keep {@code next} and, when the screen or the rows changed, save and tell the clients watching. */
    private void settle(Outcome next) {
        boolean changed = next != outcome || next == Outcome.RECEIVED;
        outcome = next;
        if (changed) {
            markDirty();
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte packetId) { }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) { }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == -1) {
            storeData(1);
        } else if (id == -2) {
            loadData(1);
        } else if (id == 100 + BUTTON_CONNECT) {
            if (!world.isRemote) contactNow();
        } else if (id == 100 + BUTTON_ERASE) {
            if (!world.isRemote) erase();
        } else if (id == 100 + BUTTON_READOUT || id == 100 + BUTTON_BACK) {
            if (!world.isRemote && player != null) {
                GuiHandler.guiId page = id == 100 + BUTTON_READOUT ? GuiHandler.guiId.MODULARNOINV : GuiHandler.guiId.MODULAR;
                player.openGui(Stellurgy.instance, page.ordinal(), world, pos.getX(), pos.getY(), pos.getZ());
            }
        }
    }

    /** Remove the chip's satellite from its world and blank the chip. */
    private void erase() {
        ItemStack stack = getStackInSlot(0);
        if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
            ItemSatelliteIdentificationChip idchip = (ItemSatelliteIdentificationChip) stack.getItem();

            SatelliteBase sat = idchip.getSatellite(stack);
            if (sat != null) {
                DimensionManager.getInstance()
                        .getDimensionProperties(sat.getDimensionId())
                        .removeSatellite(sat.getId());
            }

            idchip.erase(stack);
            setInventorySlotContents(0, stack);
        }
    }

    public SatelliteBase getSatelliteFromSlot(int slot) {
        ItemStack stack = getStackInSlot(slot);
        if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
            return ItemSatelliteIdentificationChip.getSatellite(stack);
        }
        return null;
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
        // A chip put in is tried at once, so the screen never shows the outcome of the chip before it.
        if (slot == 0 && world != null && !world.isRemote) {
            if (getSatelliteFromSlot(0) == null) {
                settle(Outcome.NO_SATELLITE);
            } else {
                contactNow();
            }
        }
    }

    /**
     * The screen: {@link GuiHandler.guiId#MODULAR} is the terminal with its slots, and
     * {@link GuiHandler.guiId#MODULARNOINV} the downloaded rows. A page is the viewer's own, since it is
     * the id his screen was opened with.
     */
    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>(8);
        if (ID == GuiHandler.guiId.MODULARNOINV.ordinal()) {
            modules.add(new ModuleSatelliteReadout(8, 22, 0x404040, this));
            modules.add(new ModuleButton(8, 3, BUTTON_BACK,
                    LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.back"), this,
                    dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonBuild, 40, 16));
            return modules;
        }

        modules.add(new ModulePower(18, 20, this.energy) {
            @Override public int numberOfChangesToSend() { return 2; }
        });

        modules.add(new ModuleButton(116, 70, BUTTON_CONNECT,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.connect"),
            this,
            dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonBuild,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.connect_hint")
        ));

        modules.add(new ModuleButton(60, 70, BUTTON_READOUT,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.readout"),
            this,
            dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonBuild,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.readout_hint")
        ));

        modules.add(new ModuleButton(173, 3, BUTTON_ERASE, "",
            this, TextureResources.buttonKill,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.destroysat"), 24, 24));

        modules.add(new ModuleData(28, 20, 1, this, data) {
            @Override public int numberOfChangesToSend() { return 2; }
        });

        modules.add(new ModuleSatellite(152, 10, this, 0) {
            @Override public int numberOfChangesToSend() { return 0; }
        });

        modules.add(new ModuleSatelliteTerminal(60, 20, 0x404040, this, this));

        return modules;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        PacketHandler.sendToServer(new PacketMachine(this, (byte) (100 + buttonId)));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagCompound dataTag = new NBTTagCompound();
        this.data.writeToNBT(dataTag);
        nbt.setTag("data", dataTag);
        NBTTagCompound readoutTag = new NBTTagCompound();
        readout.writeToNBT(readoutTag);
        nbt.setTag("readout", readoutTag);
        nbt.setByte("outcome", (byte) outcome.ordinal());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        this.data.readFromNBT(nbt.getCompoundTag("data"));
        readout.readFromNBT(nbt.getCompoundTag("readout"));
        int ordinal = nbt.getByte("outcome");
        outcome = ordinal >= 0 && ordinal < Outcome.values().length ? Outcome.values()[ordinal] : Outcome.NO_SATELLITE;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public void loadData(int slotId) {
        if (world == null || world.isRemote) {
            // Client triggers server action
            PacketHandler.sendToServer(new PacketMachine(this, (byte) -2));
            return;
        }

        ItemStack stack = getStackInSlot(slotId);
        if (stack.isEmpty() || !(stack.getItem() instanceof IDataItem)) return;

        IDataItem dataItem = (IDataItem) stack.getItem();
        DataStorage itemStore = dataItem.getDataStorage(stack);

        int available = itemStore.getData();
        if (available <= 0) return;

        DataType type = itemStore.getDataType();

        // How much room does the terminal buffer have?
        int room = data.getMaxData() - data.getData();
        if (room <= 0) return;

        int toMove = Math.min(available, room);

        // Add to terminal first (authoritative return value)
        int added = data.addData(toMove, type, true);

        if (added > 0) {
            // Remove only what actually got accepted
            dataItem.removeData(stack, added, type);
            setInventorySlotContents(slotId, stack);
            markDirty();
        }
    }

    @Override
    public void storeData(int slotId) {
        if (world == null || world.isRemote) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) -1));
            return;
        }

        ItemStack stack = getStackInSlot(slotId);
        if (stack.isEmpty() || !(stack.getItem() instanceof IDataItem)) return;

        if (data.getData() <= 0 || data.getDataType() == DataType.UNDEFINED) return;

        IDataItem dataItem = (IDataItem) stack.getItem();

        int moved = dataItem.addData(stack, data.getData(), data.getDataType());

        if (moved > 0) {
            data.removeData(moved, true);
            setInventorySlotContents(slotId, stack);
            markDirty();
        }
    }

    @Override
    public int extractData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        if (type != data.getDataType() && data.getDataType() != DataType.UNDEFINED) return 0;
        if (!commit) return Math.min(maxAmount, data.getData());
        int toGive = Math.min(maxAmount, data.getData());
        return (toGive > 0) ? data.removeData(toGive, true) : 0;
    }

    @Override
    public int addData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        return data.addData(maxAmount, type, commit);
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) { return true; }
}
