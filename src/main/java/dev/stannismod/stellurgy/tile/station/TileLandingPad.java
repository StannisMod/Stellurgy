package dev.stannismod.stellurgy.tile.station;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.api.RocketEvent;
import dev.stannismod.stellurgy.api.RocketEvent.RocketDismantleEvent;
import dev.stannismod.stellurgy.api.RocketEvent.RocketLandedEvent;
import dev.stannismod.stellurgy.api.RocketEvent.RocketPreLaunchEvent;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.util.StationLandingLocation;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesItems;
import dev.stannismod.stellurgy.libvulpes.interfaces.ILinkableTile;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IGuiCallback;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleTextBox;
import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.IMultiblock;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileInventoryHatch;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import dev.stannismod.stellurgy.api.*;

public class TileLandingPad extends TileInventoryHatch implements ILinkableTile, IGuiCallback, INetworkMachine {

    ModuleTextBox moduleNameTextbox;
    String name;
    private List<HashedBlockPosition> blockPos;

    public TileLandingPad() {
        super(1);
        inventory.setCanInsertSlot(0, true);
        inventory.setCanExtractSlot(0, true);
        blockPos = new LinkedList<>();
        moduleNameTextbox = new ModuleTextBox(this, 40, 30, 60, 12, 9);
        name = "";
    }

    // The pad listens on the process-wide bus only while it stands in a world: registered when it is
    // added to one, released by whichever of invalidate / chunk unload / world unload comes first.
    // World unload is the one a server stop sends - it never unloads chunks, so onChunkUnload alone
    // left every loaded pad, and its world, on the bus for the rest of the JVM.
    private boolean registeredBus = false;

    @Override
    public void onLoad() {
        super.onLoad();
        if (!registeredBus) {
            MinecraftForge.EVENT_BUS.register(this);
            registeredBus = true;
        }
    }

    private void unregisterFromBus() {
        if (registeredBus) {
            MinecraftForge.EVENT_BUS.unregister(this);
            registeredBus = false;
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() == world) {
            unregisterFromBus();
        }
    }

    /** Rocket events are posted for every world on one bus; a pad answers only its own. */
    private boolean isInMyWorld(RocketEvent event) {
        return world != null && event.getEntity().world == world;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        unregisterFromBus();
        for (HashedBlockPosition pos : blockPos) {
            TileEntity tile = world.getTileEntity(pos.getBlockPos());
            if (tile instanceof IMultiblock)
                ((IMultiblock) tile).setIncomplete();
        }
    }

    @Override
    public void onModuleUpdated(ModuleBase module) {
        if (module == moduleNameTextbox) {
            name = moduleNameTextbox.getText();
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
        }
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        modules.add(new ModuleText(40, 20, LibVulpes.proxy.getLocalizedString("msg.label.name") + ":", 0x2f2f2f));
        modules.add(moduleNameTextbox);
        return modules;
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        unregisterFromBus();
    }

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity,
                               EntityPlayer player, World world) {
        ItemLinker.setMasterCoords(item, getPos());
        ItemLinker.setDimId(item, world.provider.getDimension());
        return true;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockLandingPad.getLocalizedName();
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity,
                                  EntityPlayer player, World world) {

        TileEntity tile = world.getTileEntity(ItemLinker.getMasterCoords(item));

        if (tile instanceof IInfrastructure) {
            HashedBlockPosition pos = new HashedBlockPosition(tile.getPos());
            if (!blockPos.contains(pos)) {
                blockPos.add(pos);
            }

            AxisAlignedBB bbCache = new AxisAlignedBB(this.getPos().add(-1, 0, -1), this.getPos().add(1, 2, 1));


            List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, bbCache);
            for (EntityRocketBase rocket : rockets) {
                rocket.linkInfrastructure((IInfrastructure) tile);
            }


            if (!world.isRemote) {
                player.sendMessage(new TextComponentTranslation("msg.linker.success"));

                if (tile instanceof IMultiblock)
                    ((IMultiblock) tile).setMasterBlock(getPos());
            }

            ItemLinker.resetPosition(item);
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public void onRocketLand(RocketLandedEvent event) {
        if (!isInMyWorld(event)) return;
        EntityRocketBase rocket = (EntityRocketBase) event.getEntity();

        AxisAlignedBB bbCache = new AxisAlignedBB(this.getPos().add(-1, 0, -1), this.getPos().add(1, 2, 1));

        if (bbCache.intersects(rocket.getEntityBoundingBox())) {
            if (!event.world.isRemote)
                for (IInfrastructure infrastructure : getConnectedInfrastructure()) {
                    rocket.linkInfrastructure(infrastructure);
                }
            ItemStack stack = getStackInSlot(0);
            if (stack.getItem() == LibVulpesItems.itemLinker && ItemLinker.getDimId(stack) != Constants.INVALID_PLANET && event.getEntity() instanceof EntityRocket) {
                ((EntityRocket) rocket).setOverriddenCoords(ItemLinker.getDimId(stack), ItemLinker.getMasterX(stack) + 0.5f, arrivalHeightIn(ItemLinker.getDimId(stack)), ItemLinker.getMasterZ(stack) + 0.5f);
            }
        }
    }

    /**
     * The height a rocket linked to {@code dimId} comes into it at — its transfer line, the same answer
     * the rocket itself gives on arrival (which announces a world with no line).
     */
    private static float arrivalHeightIn(int dimId) {
        return dev.stannismod.stellurgy.dimension.DimensionManager.getInstance().transferLineOf(dimId)
                .orElse(dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y);
    }

    @SubscribeEvent
    public void onRocketLaunch(RocketPreLaunchEvent event) {
        if (!isInMyWorld(event)) return;

        ItemStack stack = getStackInSlot(0);
        if (stack.getItem() == LibVulpesItems.itemLinker && ItemLinker.getDimId(stack) != Constants.INVALID_PLANET) {

            EntityRocketBase rocket = (EntityRocketBase) event.getEntity();
            AxisAlignedBB bbCache = new AxisAlignedBB(this.getPos().add(-1, 0, -1), this.getPos().add(1, 2, 1));

            if (bbCache.intersects(rocket.getEntityBoundingBox())) {
                if (event.getEntity() instanceof EntityRocket) {
                    ((EntityRocket) rocket).setOverriddenCoords(ItemLinker.getDimId(stack),
                            ItemLinker.getMasterX(stack) + 0.5f, arrivalHeightIn(ItemLinker.getDimId(stack)), ItemLinker.getMasterZ(stack) + 0.5f);
                }
            }
        }
    }

    @SubscribeEvent
    public void onRocketDismantle(RocketDismantleEvent event) {
        if (world == null || world.isRemote || world.provider == null || !isInMyWorld(event)) return;
        if (world.provider.getDimension() != StellurgyConfiguration.getCurrentConfig().spaceDimId) return;

        // Make sure this is actually a rocket
        if (!(event.getEntity() instanceof EntityRocketBase)) return;

        EntityRocketBase rocket = (EntityRocketBase) event.getEntity();
        AxisAlignedBB rocketBB = rocket.getEntityBoundingBox();
        if (rocketBB == null) return; // don't explode if some mod messes with AABBs

        AxisAlignedBB bbCache = new AxisAlignedBB(
            this.getPos().add(-1, 0, -1),
            this.getPos().add(1, 2, 1)
        );

        if (!bbCache.intersects(rocketBB)) return;

        ISpaceObject spaceObj =
            SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);

        if (spaceObj instanceof SpaceStationObject) {
            ((SpaceStationObject) spaceObj).setPadStatus(pos, false);
        }
    }


    public void registerTileWithStation(World world, BlockPos pos) {
        if (!world.isRemote && world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
            ISpaceObject spaceObj = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);

            if (spaceObj instanceof SpaceStationObject) {
                ((SpaceStationObject) spaceObj).addLandingPad(pos, name);

                AxisAlignedBB bbCache = new AxisAlignedBB(this.getPos().add(-1, 0, -1), this.getPos().add(1, 2, 1));
                List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, bbCache);

                if (!rockets.isEmpty())
                    ((SpaceStationObject) spaceObj).setPadStatus(pos, true);
            }
        }
    }

    public void setAllowAutoLand(World world, BlockPos pos, boolean allow) {
        if (!world.isRemote && world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
            ISpaceObject spaceObj = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);

            if (spaceObj instanceof SpaceStationObject) {
                ((SpaceStationObject) spaceObj).setLandingPadAutoLandStatus(pos, allow);
            }
        }
    }

    public void unregisterTileWithStation(World world, BlockPos pos) {
        if (!world.isRemote && world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
            ISpaceObject spaceObj = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
            if (spaceObj instanceof SpaceStationObject)
                ((SpaceStationObject) spaceObj).removeLandingPad(pos);
        }
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);

        if (!stack.isEmpty()) {
            setAllowAutoLand(world, pos, false);

            AxisAlignedBB bbCache = new AxisAlignedBB(this.getPos().add(-1, 0, -1), this.getPos().add(1, 2, 1));
            List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, bbCache);

            for (EntityRocketBase rocket : rockets) {
                if (rocket instanceof EntityRocket) {
                    if (stack.getItem() == LibVulpesItems.itemLinker && ItemLinker.getDimId(stack) != Constants.INVALID_PLANET) {
                        ((EntityRocket) rocket).setOverriddenCoords(ItemLinker.getDimId(stack),
                                ItemLinker.getMasterX(stack) + 0.5f, arrivalHeightIn(ItemLinker.getDimId(stack)), ItemLinker.getMasterZ(stack) + 0.5f);
                    } else
                        ((EntityRocket) rocket).setOverriddenCoords(Constants.INVALID_PLANET, 0, 0, 0);
                }
            }
        } else {
            setAllowAutoLand(world, pos, true);

            AxisAlignedBB bbCache = new AxisAlignedBB(this.getPos().add(-1, 0, -1), this.getPos().add(1, 2, 1));
            List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, bbCache);

            for (EntityRocketBase rocket : rockets) {
                if (rocket instanceof EntityRocket) {
                    ((EntityRocket) rocket).setOverriddenCoords(Constants.INVALID_PLANET, 0, 0, 0);
                }
            }
        }

    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        return stack.getItem() == LibVulpesItems.itemLinker;
    }

    public List<IInfrastructure> getConnectedInfrastructure() {
        List<IInfrastructure> infrastructure = new LinkedList<>();

        Iterator<HashedBlockPosition> iter = blockPos.iterator();

        while (iter.hasNext()) {
            HashedBlockPosition position = iter.next();
            TileEntity tile = world.getTileEntity(position.getBlockPos());
            if (tile instanceof IInfrastructure) {
                infrastructure.add((IInfrastructure) tile);
            } else
                iter.remove();
        }

        return infrastructure;
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == 0) {
            PacketBuffer buff = new PacketBuffer(out);
            buff.writeInt(name.length());
            buff.writeString(name);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        int len = in.readInt();
        PacketBuffer buff = new PacketBuffer(in);
        nbt.setString("id", buff.readString(len));
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, getBlockMetadata(), writeToNBT(new NBTTagCompound()));
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        super.onDataPacket(net, pkt);
        readFromNBT(pkt.getNbtCompound());
        moduleNameTextbox.setText(name);
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        moduleNameTextbox.setText(name);
    }


    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == 0) {
            name = nbt.getString("id");
            if (!world.isRemote && world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                ISpaceObject spaceObj = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);

                if (spaceObj instanceof SpaceStationObject) {
                    StationLandingLocation loc = ((SpaceStationObject) spaceObj).getPadAtLocation(new HashedBlockPosition(pos));
                    if (loc != null)
                        ((SpaceStationObject) spaceObj).setPadName(this.world, new HashedBlockPosition(pos), name);
                    else
                        ((SpaceStationObject) spaceObj).addLandingPad(pos, name);
                }
            }
        }
        markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        blockPos.clear();
        if (nbt.hasKey("infrastructureLocations")) {
            int[] array = nbt.getIntArray("infrastructureLocations");

            for (int counter = 0; counter < array.length; counter += 3) {
                blockPos.add(new HashedBlockPosition(array[counter], array[counter + 1], array[counter + 2]));
            }
        }
        name = nbt.getString("name");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        if (!blockPos.isEmpty()) {
            int[] array = new int[blockPos.size() * 3];
            int counter = 0;
            for (HashedBlockPosition pos : blockPos) {
                array[counter] = pos.x;
                array[counter + 1] = pos.y;
                array[counter + 2] = pos.z;
                counter += 3;
            }

            nbt.setIntArray("infrastructureLocations", array);
        }

        if (name != null && !name.isEmpty())
            nbt.setString("name", name);

        return nbt;
    }

}
