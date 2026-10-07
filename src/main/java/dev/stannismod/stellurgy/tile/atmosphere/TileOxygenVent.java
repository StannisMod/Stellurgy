package dev.stannismod.stellurgy.tile.atmosphere;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyFluids;
import dev.stannismod.stellurgy.api.AreaBlob;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.IToggleableMachine;
import dev.stannismod.stellurgy.libvulpes.block.BlockTile;
import dev.stannismod.stellurgy.libvulpes.client.RepeatingSound;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.TileInventoriedRFConsumerTank;
import dev.stannismod.stellurgy.libvulpes.util.FluidUtils;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.IAdjBlockUpdate;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils.RedstoneState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Tier 1: a room's zone, and a tank of oxygen it pays into that room.
 *
 * <p>The zone is its {@link SealedZone}: live while the room is sealed, whatever the tank holds. The
 * tank is one SUPPLY among several: it tops the room's oxygen up to sea level and pays for exactly
 * what it admits, so a room that lacks nothing costs nothing and a vent that runs dry leaves a room
 * its crew go on breathing down. The ventilation network does not reach this block — its port is
 * {@link TileVentilationPort}, which has no tank.</p>
 *
 * <p>Tier 2 is the {@link TileCO2Scrubber} beside it: the vent keeps the room, the scrubbers take its
 * carbon dioxide out, and the vent powers each of them while it works.</p>
 */
public class TileOxygenVent extends TileInventoriedRFConsumerTank implements IZonePort, IModularInventory, INetworkMachine, IAdjBlockUpdate, IToggleableMachine, IButtonInventory, IToggleButton {

    private final static byte PACKET_REDSTONE_ID = 2;
    private final static byte PACKET_TRACE_ID = 3;

    private final SealedZone zone = new SealedZone(this, this::markDirty);
    /** The seal as the server last reported it; the client keeps no zone of its own. */
    private boolean clientSealed;
    /** The tank holds oxygen, as of this vent's last operation. */
    private boolean hasFluid;
    private boolean soundInit;
    private boolean allowTrace;
    private boolean blockUpdated;
    /** The scrubbers that absorbed CO2 in their last second, each of which this vent powers. */
    private int workingScrubbers;
    private List<TileCO2Scrubber> scrubbers;
    private int radius = 0;
    private RedstoneState state;
    private ModuleRedstoneOutputButton redstoneControl;
    private ModuleToggleSwitch traceToggle;
    /** How often an unsealed vent widens its diagnostic trace by one block. */
    private static final int TRACE_STEP_TICKS = 10;
    /** Scrubbers work once a second, because their rate is stated per second. */
    private static final int SCRUB_TICKS = 20;
    /** The vent's own flow per block of room per tick, before the oxygenVentConsumptionMultiplier. */
    private static final float FLOW_PER_BLOCK = 0.01f;
    /** Power one scrubber's work costs the vent, per tick, before the OxygenVentPowerMultiplier. */
    private static final int POWER_PER_SCRUBBER = 10;

    /**
     * Periodic jobs each run on their OWN counter rather than on a world-clock modulo: a modulo fires
     * every vent in the world on one tick, and a tile the harness force-ticks sees one world time
     * across all of its ticks.
     */
    private int ticksSinceTraceStep;
    private int ticksSinceScrub;


    public TileOxygenVent() {
        super(1000, 2, 2000);
        hasFluid = true;
        soundInit = false;
        allowTrace = false;
        scrubbers = new LinkedList<>();
        state = RedstoneState.ON;
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, PACKET_REDSTONE_ID, "", this);
        traceToggle = new ModuleToggleSwitch(80, 20, PACKET_TRACE_ID, LibVulpes.proxy.getLocalizedString("msg.vent.trace"), this, TextureResources.buttonGeneric, 80, 18, false);
    }

    @Override
    public boolean canPerformFunction() {
        return AtmosphereHandler.hasAtmosphereHandler(this.world);
    }

    @Override
    public World getWorldObj() {
        return getWorld();
    }

    @Override
    public void onAdjacentBlockUpdated() {
        blockUpdated = true; // the performFunction will take it from here
    }

    private void activateAdjBlocks() {
        toggleAdjBlock(pos.add(1, 0, 0), true);
        toggleAdjBlock(pos.add(-1, 0, 0), true);
        toggleAdjBlock(pos.add(0, 1, 0), true);
        toggleAdjBlock(pos.add(0, -1, 0), true);
        toggleAdjBlock(pos.add(0, 0, 1), true);
        toggleAdjBlock(pos.add(0, 0, -1), true);
    }

    private void deactivateAdjBlocks() {
        toggleAdjBlock(pos.add(1, 0, 0), false);
        toggleAdjBlock(pos.add(-1, 0, 0), false);
        toggleAdjBlock(pos.add(0, 1, 0), false);
        toggleAdjBlock(pos.add(0, -1, 0), false);
        toggleAdjBlock(pos.add(0, 0, 1), false);
        toggleAdjBlock(pos.add(0, 0, -1), false);
    }

    private boolean toggleAdjBlock(BlockPos pos, boolean on) {
        IBlockState state = this.world.getBlockState(pos);
        Block block = state.getBlock();
        if (block == StellurgyBlocks.blockCO2Scrubber) {
            ((BlockTile) block).setBlockState(world, state, pos, on);

            return true;
        }
        return false;
    }

    private void unregisterAtmosphereBlob() {
        if (world == null || world.isRemote) {
            return;
        }

        AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(world);
        if (atmhandler != null) {
            atmhandler.unregisterBlob(this);
        }
    }

    @Override
    public void invalidate() {
        unregisterAtmosphereBlob();
        deactivateAdjBlocks();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        unregisterAtmosphereBlob();
        super.onChunkUnload();
    }

    /** One for the vent's fan, plus one scrubber's for each scrubber that is absorbing. */
    @Override
    public int getPowerPerOperation() {
        return powerWith(workingScrubbers);
    }

    /** What a tick costs with {@code scrubbers} of them absorbing. */
    private int powerWith(int scrubbers) {
        return (int) ((scrubbers * POWER_PER_SCRUBBER + 1)
                * StellurgyConfiguration.getCurrentConfig().oxygenVentPowerMultiplier);
    }

    @Override
    public boolean canFill(Fluid fluid) {
        return FluidUtils.areFluidsSameType(fluid, StellurgyFluids.fluidOxygen) && super.canFill(fluid);
    }

    public boolean isTurnedOn() {
        if (state == RedstoneState.OFF)
            return true;

        boolean state2 = world.isBlockIndirectlyGettingPowered(pos) > 0;

        if (state == RedstoneState.INVERTED)
            state2 = !state2;
        return state2;
    }

    /** Whether the tank holds the oxygen this vent supplies; see {@link #hasFluid}. */
    public boolean hasFluid() {
        return hasFluid;
    }

    @Override
    public boolean isSealed() {
        return world != null && world.isRemote ? clientSealed : zone.isSealed();
    }

    @Override
    public boolean checkSealNow() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        if (handler == null || !zone.isRegistered()) {
            return false;
        }
        boolean was = zone.isSealed();
        boolean sealed = zone.checkSeal(handler, pos);
        if (was != sealed) {
            sealChanged(was);
        }
        return sealed;
    }

    /** The zone is live while sealed: life support may act on its gases whatever the tank holds. */
    @Override
    public boolean isMaintainingAtmosphere() {
        return zone.isSealed();
    }

    private void keepZone() {
        AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(world);
        if (atmhandler == null)
            return;
        if (!zone.isRegistered()) {
            zone.register(atmhandler, pos);
            onAdjacentBlockUpdated();
        }
        boolean was = zone.isSealed();
        if (zone.tick(atmhandler, pos, isTurnedOn() && hasEnoughEnergy(getPowerPerOperation())))
            sealChanged(was);
    }

    private void sealChanged(boolean wasSealed) {
        markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
        if (wasSealed)
            radius = -1;
        if (zone.isSealed()) {
            activateAdjBlocks();
        } else {
            deactivateAdjBlocks();
            workingScrubbers = 0;
        }
    }

    @Override
    public void performFunction() {

        if (blockUpdated) { // this was moved from onAdjacentBlockUpdated(); to prevent crash
            if (isSealed())
                activateAdjBlocks();
            scrubbers.clear();
            TileEntity[] tiles = new TileEntity[6];
            tiles[0] = world.getTileEntity(pos.add(1, 0, 0));
            tiles[1] = world.getTileEntity(pos.add(-1, 0, 0));
            tiles[2] = world.getTileEntity(pos.add(0, 1, 0));
            tiles[3] = world.getTileEntity(pos.add(0, -1, 0));
            tiles[4] = world.getTileEntity(pos.add(0, 0, 1));
            tiles[5] = world.getTileEntity(pos.add(0, 0, -1));


            for (TileEntity tile : tiles) {
                if (tile instanceof TileCO2Scrubber && world.getBlockState(tile.getPos()).getBlock() == StellurgyBlocks.blockCO2Scrubber)
                    scrubbers.add((TileCO2Scrubber) tile);
            }
            blockUpdated = false;
        }

        if (world.isRemote)
            return;
        AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(this.world);
        if (atmhandler == null)
            return;

        if (!zone.isSealed()) {
            if (isTurnedOn() && allowTrace && ++ticksSinceTraceStep >= TRACE_STEP_TICKS) {
                ticksSinceTraceStep = 0;
                radius++;
                if (radius > 128)
                    radius = 0;
            }
            return;
        }

        scrub(atmhandler);
        supplyOxygen(atmhandler);
        hasFluid = tank.getFluidAmount() > 0;
    }

    /** Once a second every scrubber beside this vent absorbs CO2 from its room. */
    private void scrub(AtmosphereHandler atmhandler) {
        if (++ticksSinceScrub < SCRUB_TICKS)
            return;
        ticksSinceScrub = 0;
        AirState air = atmhandler.getAirState(this);
        if (air == null)
            return;
        int volume = atmhandler.getBlobSize(this);
        int working = 0;
        for (TileCO2Scrubber scrubber : scrubbers) {
            // Paid before it works: the tick's price rises with every scrubber that absorbs, and the
            // gate in update() was taken at the price before this scrub. A scrubber the store cannot
            // also pay for is left idle rather than paid for out of what the fan needed.
            if (!hasEnoughEnergy(powerWith(working + 1)))
                break;
            if (scrubber.absorb(air, volume))
                working++;
        }
        workingScrubbers = working;
        if (working > 0) {
            atmhandler.refreshDerivedAtmosphere(this);
            markDirty();
        }
    }

    /**
     * The tank pays oxygen into the room: as much as the room lacks of sea level, at the one exchange
     * rate the rest of life support uses, and no more than the tank holds. A room that lacks nothing
     * costs nothing.
     *
     * <p>It tops up to SEA LEVEL and stops there, rather than to the safe band's ceiling: enriching a
     * breathable room walks it toward the toxic and fire-prone end, which a player asks the separator
     * for on purpose and never the vent behind his back. Gas arrives at the temperature it was stored
     * at, so a room that needed nothing is not quietly chilled either.</p>
     *
     * <p>The cost is rounded UP to whole millibuckets, so a sliver of missing oxygen still costs one:
     * the tank never admits gas it did not pay for. And it flows no faster than the vent's own rate,
     * {@code ceil(volume * FLOW_PER_BLOCK)} millibuckets a tick, so a vent refills a room over time
     * rather than in one tick. Scrubbers do not touch that rate: they work on the room's CO2.</p>
     */
    private void supplyOxygen(AtmosphereHandler atmhandler) {
        AirState air = atmhandler.getAirState(this);
        int volume = Math.max(1, atmhandler.getBlobSize(this));
        long perAtm = (long) volume * StellurgyConfiguration.getCurrentConfig().lifeSupportFluidPerAtmBlock;
        if (air == null || perAtm <= 0L)
            return;
        long missing = Math.min(AirState.earthLike().getOxygen() - air.getOxygen(), air.oxygenHeadroom());
        if (missing <= 0L)
            return;
        long rate = (long) Math.ceil(volume * FLOW_PER_BLOCK
                * StellurgyConfiguration.getCurrentConfig().oxygenVentConsumptionMult);
        long cost = Math.min(rate, (missing * perAtm + AirState.ONE_ATM - 1) / AirState.ONE_ATM);
        if (cost <= 0L)
            return;
        FluidStack paid = this.drain((int) Math.min(Integer.MAX_VALUE, cost), true);
        if (paid == null || paid.amount <= 0)
            return;
        long admitted = Math.min((long) paid.amount * AirState.ONE_ATM / perAtm, missing);
        if (admitted > 0L) {
            air.addOxygen(admitted, AirState.ambientKelvin());
            atmhandler.refreshDerivedAtmosphere(this);
            markDirty();
        }
    }

    @Override
    public int getTraceDistance() {
        return allowTrace ? radius : -1;
    }

    @Override
    public void update() {
        if (canPerformFunction()) {
            // The zone is kept whatever the power: a breached room loses its air without electricity,
            // and a vent that cannot run is a room that stops being held.
            if (!world.isRemote)
                keepZone();
            if (hasEnoughEnergy(getPowerPerOperation())) {
                performFunction();
                if (!world.isRemote && zone.isSealed()) this.energy.extractEnergy(getPowerPerOperation(), false);
            }
        } else
            radius = -1;
        if (!soundInit && world.isRemote) {
            LibVulpes.proxy.playSound(new RepeatingSound(AudioRegistry.airHissLoop, SoundCategory.BLOCKS, this));
        }
        soundInit = true;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, getBlockMetadata(), getUpdateTag());

    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setBoolean("isSealed", isSealed());

        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        clientSealed = tag.getBoolean("isSealed");

        if (clientSealed) {
            activateAdjBlocks();
        }
    }

    @Override
    public void notEnoughEnergyForFunction() {
        if (!world.isRemote) {
            AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(this.world);
            if (handler != null && zone.isSealed()) {
                zone.drop(handler);
                sealChanged(true);
            }
        }
    }


    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[]{};
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack itemStack) {
        return false;
    }

    @Override
    public boolean canBlobsOverlap(HashedBlockPosition blockPosition, AreaBlob blob) {
        return false;
    }

    @Override
    public int getMaxBlobRadius() {
        return StellurgyConfiguration.getCurrentConfig().oxygenVentSize;
    }

    @Override
    @Nonnull
    public HashedBlockPosition getRootPosition() {
        return new HashedBlockPosition(pos);
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        ArrayList<ModuleBase> modules = new ArrayList<>();

        modules.add(new ModuleSlotArray(52, 20, this, 0, 1));
        modules.add(new ModuleSlotArray(52, 57, this, 1, 2));
        modules.add(new ModulePower(18, 20, this));
        modules.add(new ModuleLiquidIndicator(32, 20, this));
        modules.add(redstoneControl);
        modules.add(traceToggle);
        return modules;
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);

        while (FluidUtils.attemptDrainContainerIInv(inventory, this.tank, getStackInSlot(0), 0, 1)) ;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockOxygenVent.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public boolean canFormBlob() {
        return isTurnedOn();
    }

    @Override
    public boolean isRunning() {
        return isSealed();
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == PACKET_REDSTONE_ID) {
            state = redstoneControl.getState();
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_REDSTONE_ID));
        }
        if (buttonId == PACKET_TRACE_ID) {
            allowTrace = traceToggle.getState();
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_TRACE_ID));
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == PACKET_REDSTONE_ID)
            out.writeByte(state.ordinal());
        else if (id == PACKET_TRACE_ID)
            out.writeBoolean(allowTrace);
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == PACKET_REDSTONE_ID)
            nbt.setByte("state", in.readByte());
        else if (packetId == PACKET_TRACE_ID)
            nbt.setBoolean("trace", in.readBoolean());
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == PACKET_REDSTONE_ID)
            state = RedstoneState.values()[nbt.getByte("state")];
        else if (id == PACKET_TRACE_ID) {
            allowTrace = nbt.getBoolean("trace");
            if (!allowTrace)
                radius = -1;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        state = RedstoneState.values()[nbt.getByte("redstoneState")];
        redstoneControl.setRedstoneState(state);
        allowTrace = nbt.getBoolean("allowtrace");
        zone.readFromNBT(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setByte("redstoneState", (byte) state.ordinal());
        nbt.setBoolean("allowtrace", allowTrace);
        zone.writeToNBT(nbt, world != null && !world.isRemote ? AtmosphereHandler.getOxygenHandler(world) : null);
        return nbt;
    }

    @Override
    public boolean isEmpty() {
        return inventory.isEmpty();
    }

    @Override
    public void stateUpdated(ModuleBase module) {
        if (module.equals(traceToggle)) {
            allowTrace = ((ModuleToggleSwitch) module).getState();
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_TRACE_ID));
        }
    }
}
