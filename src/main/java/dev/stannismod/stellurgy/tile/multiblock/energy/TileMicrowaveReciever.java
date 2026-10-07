package dev.stannismod.stellurgy.tile.multiblock.energy;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.client.TooltipInjector;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.item.ItemSatelliteIdentificationChip;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.util.PlanetaryTravelHelper;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.IUniversalEnergyTransmitter;
import dev.stannismod.stellurgy.libvulpes.block.BlockMeta;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiBlock;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiPowerProducer;
import dev.stannismod.stellurgy.libvulpes.util.Vector3F;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;


/**
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
public class TileMicrowaveReciever extends TileMultiPowerProducer implements ITickable {

    static final BlockMeta iron_block = new BlockMeta(StellurgyBlocks.blockSolarPanel);
    static final Object[][][] structure = new Object[][][]{
            {
                    {iron_block, '*', '*', '*', iron_block},
                    {'*', iron_block, iron_block, iron_block, '*'},
                    {'*', iron_block, 'c', iron_block, '*'},
                    {'*', iron_block, iron_block, iron_block, '*'},
                    {iron_block, '*', '*', '*', iron_block},
            }};

    List<Long> connectedSatellites;
    boolean initialCheck;
    double insolationPowerMultiplier;
    int powerSourceDimensionID;
    int powerMadeLastTick, prevPowerMadeLastTick;
    ModuleText textModule;

    public TileMicrowaveReciever() {
        connectedSatellites = new LinkedList<>();
        initialCheck = false;
        insolationPowerMultiplier = 0;
        textModule = new ModuleText(40, 20, LibVulpes.proxy.getLocalizedString("msg.microwaverec.notgenerating"), 0x2b2b2b);
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        modules.add(textModule);

        return modules;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return super.getRenderBoundingBox().grow(0, 2000, 0).offset(0, 1000, 0);
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        return false;
    }

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public List<BlockMeta> getAllowableWildCardBlocks() {
        List<BlockMeta> blocks = super.getAllowableWildCardBlocks();

        blocks.addAll(TileMultiBlock.getMapping('I'));
        blocks.add(iron_block);
        blocks.addAll(TileMultiBlock.getMapping('p'));

        return blocks;
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockMicrowaveReciever.getLocalizedName();
    }

    public int getPowerMadeLastTick() {
        return powerMadeLastTick;
    }

    @Override
    public void onInventoryUpdated() {
        super.onInventoryUpdated();

        List<Long> list = new LinkedList<>();

        if (itemInPorts != null) {
            for (IInventory inv : itemInPorts) {
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
                        list.add(SatelliteRegistry.getSatelliteId(stack));
                    }
                }
            }
        }
        connectedSatellites = new LinkedList<>(new LinkedHashSet<>(list));
    }

    private List<Long> getConnectedSatellitesLive() {
        if (itemInPorts == null) return java.util.Collections.emptyList();

        // refresh TE references (libVulpes replaces TEs during multiblock build/load)
        List<IInventory> ports = getItemInPorts();

        java.util.LinkedHashSet<Long> set = new java.util.LinkedHashSet<>();
        for (IInventory inv : ports) {
            if (inv == null) continue;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
                    set.add(SatelliteRegistry.getSatelliteId(stack));
                }
            }
        }
        return new java.util.ArrayList<>(set);
    }

    @Override
    public void update() {

        if (!initialCheck && !world.isRemote) {
            completeStructure = attemptCompleteStructure(world.getBlockState(pos));
            onInventoryUpdated();
            initialCheck = true;
        }

        //Checks whenever a station changes dimensions or when the multiblock is intialized - ie any time the multipler could concieveably change
        final int curDim = world.provider.getDimension();
        final int spaceDim = StellurgyConfiguration.getCurrentConfig().spaceDimId;

        // Cache station once; can be null
        final dev.stannismod.stellurgy.stations.SpaceStationObject station =
            (curDim == spaceDim)
                ? (dev.stannismod.stellurgy.stations.SpaceStationObject)
                    dev.stannismod.stellurgy.stations.SpaceObjectManager.getSpaceManager()
                        .getSpaceStationFromBlockCoords(this.pos)
                : null;

        // Recompute when uninitialized OR (in space AND orbiting planet changed and station exists)
        final boolean needRecompute =
            (insolationPowerMultiplier == 0)
            || (curDim == spaceDim && station != null && powerSourceDimensionID != station.getOrbitingPlanetId());

        if (needRecompute) {
            if (curDim == spaceDim && station != null) {
                insolationPowerMultiplier = station.getInsolationMultiplier();
                powerSourceDimensionID = station.getOrbitingPlanetId();
            } else {
                final dev.stannismod.stellurgy.dimension.DimensionProperties props =
                    dev.stannismod.stellurgy.dimension.DimensionManager.getInstance()
                        .getDimensionProperties(curDim);
                insolationPowerMultiplier = (props != null)
                    ? props.getPeakInsolationMultiplierWithoutAtmosphere()
                    : 1.0; // safe fallback
                powerSourceDimensionID = curDim;
            }
        }
        // If we're in space but station==null (early ticks), keep previous multiplier and carry on.

        if (!isComplete())
            return;

        //Periodically check for obstructing blocks above the panel
        if (!world.isRemote && getPowerMadeLastTick() > 0 && world.getTotalWorldTime() % 100 == 0) {
            Vector3F<Integer> offset = getControllerOffset(getStructure());


            List<Entity> entityList = world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(this.getPos().getX() - offset.x, this.getPos().getY(), this.getPos().getZ() - offset.z, this.getPos().getX() - offset.x + getStructure()[0][0].length, 256, this.getPos().getZ() - offset.z + getStructure()[0].length));

            for (Entity e : entityList) {
                e.setFire(powerMadeLastTick / 10);
            }

            for (int x = 0; x < getStructure()[0][0].length; x++) {
                for (int z = 0; z < getStructure()[0].length; z++) {

                    BlockPos pos2;
                    IBlockState state = world.getBlockState(pos2 = (world.getHeight(pos.add(x - offset.x, 128, z - offset.z)).add(0, -1, 0)));

                    if (pos2.getY() > this.getPos().getY()) {
                        if (!world.isAirBlock(pos2.add(0, 1, 0))) {
                            world.setBlockToAir(pos2);
                            world.playSound(pos2.getX(), pos2.getY(), pos2.getZ(), new SoundEvent(new ResourceLocation("fire.fire")), SoundCategory.BLOCKS, 1f, 3f, false);
                        }
                    }
                }
            }
        }

        final int dimId = world.provider.getDimension();
        final boolean dimOk = DimensionManager.getInstance().isDimensionCreated(dimId) || dimId == 0;

        if (!world.isRemote && dimOk) {
            // If we’re on a station, prefer its orbiting planet; otherwise use the local dim props
            final SpaceStationObject stationHere = (dimId == StellurgyConfiguration.getCurrentConfig().spaceDimId)
                    ? (SpaceStationObject) SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.pos)
                    : null;

            final DimensionProperties props = (stationHere != null)
                    ? stationHere.getOrbitingPlanet()
                    : DimensionManager.getInstance().getDimensionProperties(dimId);

            int energyReceived = 0;

            final List<Long> sats = enabled && props != null ? getConnectedSatellitesLive() : java.util.Collections.emptyList();

            if (!sats.isEmpty()) {
                for (long lng : sats) {
                    final SatelliteBase sat = props.getSatellite(lng);
                    if (sat == null) continue;

                    final int satDim = sat.getDimensionId();
                    final int hereDim = DimensionManager.getEffectiveDimId(world, pos).getId();
                    if (!PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(satDim, hereDim)) continue;

                    if (sat instanceof IUniversalEnergyTransmitter) {
                        energyReceived += ((IUniversalEnergyTransmitter) sat).transmitEnergy(EnumFacing.UP, false);
                    }
                }

                // scale by insolation (your existing logic)
                energyReceived = (int)Math.round(energyReceived * (2 * insolationPowerMultiplier));
            }


            powerMadeLastTick = energyReceived;

            if (powerMadeLastTick != prevPowerMadeLastTick) {
                prevPowerMadeLastTick = powerMadeLastTick;
                PacketHandler.sendToNearby(new PacketMachine(this, (byte) 1),
                        world.provider.getDimension(), pos, 128);
            }
            producePower(powerMadeLastTick);
        }

        if (world.isRemote) {
            textModule.setText(
                LibVulpes.proxy.getLocalizedString("msg.microwaverec.generating") + " " +
                powerMadeLastTick + " " +
                LibVulpes.proxy.getLocalizedString("msg.powerunit.rfpertick"));
        }
    }    


    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setBoolean("canRender", canRender);
        nbt.setInteger("amtPwr", powerMadeLastTick);
        writeNetworkData(nbt);
        return new SPacketUpdateTileEntity(pos, 0, nbt);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        NBTTagCompound nbt = pkt.getNbtCompound();

        canRender = nbt.getBoolean("canRender");
        powerMadeLastTick = nbt.getInteger("amtPwr");
        readNetworkData(nbt);
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setBoolean("canRender", canRender);
        nbt.setInteger("amtPwr", powerMadeLastTick);
        writeToNBT(nbt);
        return nbt;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound nbt) {
        powerMadeLastTick = nbt.getInteger("amtPwr");
        canRender = nbt.getBoolean("canRender");
        readNetworkData(nbt);
    }


    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        super.writeDataToNetwork(out, id);

        if (id == 1) {
            out.writeInt(powerMadeLastTick);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        super.readDataFromNetwork(in, packetId, nbt);

        if (packetId == 1) {
            nbt.setInteger("amtPwr", in.readInt());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        super.useNetworkData(player, side, id, nbt);

        if (id == 1) {
            powerMadeLastTick = nbt.getInteger("amtPwr");
        }
    }

}
