package dev.stannismod.stellurgy.entity;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.RocketEvent;
import dev.stannismod.stellurgy.event.PlanetEventHandler;
import dev.stannismod.stellurgy.tile.multiblock.TileSpaceElevator;
import dev.stannismod.stellurgy.util.DimensionBlockPosition;
import dev.stannismod.stellurgy.util.TransitionEntity;
import dev.stannismod.stellurgy.world.util.BasicTeleporter;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.interfaces.INetworkEntity;
import dev.stannismod.stellurgy.libvulpes.network.PacketEntity;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.MachineReach;
import net.minecraft.entity.player.EntityPlayerMP;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
public class EntityElevatorCapsule extends Entity implements INetworkEntity {

    public static final double MAX_STANDTIME = 200;
    protected static final DataParameter<Byte> motionDir = EntityDataManager.createKey(EntityElevatorCapsule.class, DataSerializers.BYTE);
    protected static final DataParameter<Integer> standTimeCounter = EntityDataManager.createKey(EntityElevatorCapsule.class, DataSerializers.VARINT);
    private static final byte PACKET_WRITE_DST_INFO = 0;
    private static final byte PACKET_LAUNCH_EVENT = 2;
    private static final byte PACKET_DEORBIT = 3;
    private static final byte PACKET_WRITE_SRC_INFO = 4;
    byte motion;
    int standTime, idleTime;
    /** Whether this capsule has already said a world it transfers through has no orbit line. */
    private boolean noOrbitLineReported = false;
    DimensionBlockPosition dstTilePos, srcTilePos;

    public EntityElevatorCapsule(World worldIn) {
        super(worldIn);
        setSize(3, 3);
        motion = 0;
        ignoreFrustumCheck = true;
    }

    /**
     * {@link dev.stannismod.stellurgy.dimension.DimensionManager#transferLineOf} for a capsule that
     * must come out somewhere: the top of the block band where a world has no line, said once.
     */
    private int transferHeightIn(int dimId) {
        java.util.OptionalInt line = dev.stannismod.stellurgy.dimension.DimensionManager.getInstance()
                .transferLineOf(dimId);
        if (line.isPresent()) {
            return line.getAsInt();
        }
        if (!noOrbitLineReported) {
            noOrbitLineReported = true;
            Stellurgy.logger.warn("[ElevatorCapsule] dim {} has no orbit line (no radius, no stated "
                    + "<orbitHeight>); the capsule transfers at the top of the block band, Y {}, which "
                    + "is not that world's atmosphere", dimId,
                    dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y);
        }
        return dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y;
    }

    public boolean isAscending() {
        return dataManager.get(motionDir) > 0;
    }

    public boolean isDescending() {
        return dataManager.get(motionDir) < 0;
    }

    public boolean isInMotion() {
        return dataManager.get(motionDir) != 0;
    }

    public void setCapsuleMotion(int motion) {
        this.dataManager.set(motionDir, (byte) motion);
        this.motion = (byte) motion;
    }

    public int getStandTime() {
        return (standTime = this.dataManager.get(standTimeCounter));
    }

    public void setStandTime(int time) {
        this.dataManager.set(standTimeCounter, standTime);
    }

    public int decrStandTime() {

        this.dataManager.set(standTimeCounter, (standTime = getStandTime() - 1));
        return standTime;
    }

    @Override
    protected void entityInit() {
        this.dataManager.register(motionDir, motion);
        this.dataManager.register(standTimeCounter, standTime);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        setCapsuleMotion(nbt.getByte("motionDir"));

        if (nbt.hasKey("dstDimid")) {
            dstTilePos = new DimensionBlockPosition(Constants.INVALID_PLANET, null);
            dstTilePos.dimid = nbt.getInteger("dstDimid");
            int[] loc = nbt.getIntArray("dstLoc");
            dstTilePos.pos = new HashedBlockPosition(loc[0], loc[1], loc[2]);
        } else
            dstTilePos = null;

        if (nbt.hasKey("srcDimid")) {
            srcTilePos = new DimensionBlockPosition(Constants.INVALID_PLANET, null);
            srcTilePos.dimid = nbt.getInteger("srcDimid");
            int[] loc = nbt.getIntArray("srcLoc");
            srcTilePos.pos = new HashedBlockPosition(loc[0], loc[1], loc[2]);
        } else
            srcTilePos = null;
    }

    /**
     * A player who starts seeing the capsule is told where it is bound. The tracker calls this after
     * it has sent him the spawn, on the same connection, so the capsule exists on his side when this
     * arrives.
     */
    @Override
    public void addTrackingPlayer(EntityPlayerMP player) {
        super.addTrackingPlayer(player);
        PacketHandler.sendToPlayer(new PacketEntity(this, PACKET_WRITE_DST_INFO), player);
    }

    @Override
    public boolean canBeUsedBy(EntityPlayer player) {
        return MachineReach.reaches(player, this);
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setByte("motionDir", motion);
        if (dstTilePos != null) {
            nbt.setInteger("dstDimid", dstTilePos.dimid);
            nbt.setIntArray("dstLoc", new int[]{dstTilePos.pos.x, dstTilePos.pos.y, dstTilePos.pos.z});
        }

        if (srcTilePos != null) {
            nbt.setInteger("srcDimid", srcTilePos.dimid);
            nbt.setIntArray("srcLoc", new int[]{srcTilePos.pos.x, srcTilePos.pos.y, srcTilePos.pos.z});
        }
    }

    public boolean shouldRiderSit() {
        return false;
    }

    public void setDst(DimensionBlockPosition location) {
        this.dstTilePos = location;
        if (!world.isRemote)
            PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, PACKET_WRITE_DST_INFO), this);
    }

    public void setSourceTile(DimensionBlockPosition location) {
        this.srcTilePos = location;
        if (!world.isRemote)
            PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, PACKET_WRITE_SRC_INFO), this);
    }

    @Override
    public Entity changeDimension(int newDimId) {
        return changeDimension(newDimId, this.posX, transferHeightIn(newDimId), this.posZ);
    }

    public void copyDataFromOld(Entity entityIn) {
        NBTTagCompound nbttagcompound = entityIn.writeToNBT(new NBTTagCompound());
        nbttagcompound.removeTag("Dimension");
        nbttagcompound.removeTag("Passengers");
        this.readFromNBT(nbttagcompound);
        this.timeUntilPortal = entityIn.timeUntilPortal;
    }

    @Nullable
    public Entity changeDimension(int dimensionIn, double posX, double y, double posZ) {
        if (!this.world.isRemote && !this.isDead) {
            float yaw = this.rotationYaw;
            float pitch = this.rotationPitch;
            DimensionBlockPosition destination = this.dstTilePos;

            List<Entity> passengers = getPassengers();
            int i = this.dimension;
            MinecraftServer minecraftserver = this.getServer();
            WorldServer worldserver = minecraftserver.getWorld(i);
            WorldServer worldserver1 = minecraftserver.getWorld(dimensionIn);
            this.setPosition(posX, y, posZ);

            ITeleporter teleporter = new BasicTeleporter(getPosition());
            Entity entity = changeDimension(dimensionIn, teleporter);

            if (entity == null)
                return null;

            entity.setPositionAndRotation(posX, y, posZ, yaw, pitch);
            ((EntityElevatorCapsule) entity).dstTilePos = destination;

            int timeOffset = 1;
            for (Entity e : passengers) {
                dev.stannismod.stellurgy.Stellurgy.serverState().planetEvents.addDelayedTransition(new TransitionEntity(worldserver.getTotalWorldTime() + ++timeOffset, e, dimensionIn, new BlockPos(posX, y, posZ), entity));
            }
            return entity;
        }
        return null;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return getEntityBoundingBox().grow(posX, 2000, posZ);
    }

    @Override
    public void onEntityUpdate() {
        super.onEntityUpdate();

        //Make sure to update client
        if (!world.isRemote && this.ticksExisted == 5) {
            if (dstTilePos != null)
                setDst(dstTilePos);

            if (srcTilePos != null)
                setSourceTile(srcTilePos);
        }

        if (isAscending()) {

            if (this.posY > 255)
                this.motionY = 5.85;
            else
                this.motionY = 1.85;

            if (!world.isRemote) {
                List<EntityPlayer> list = world.getEntitiesWithinAABB(EntityPlayer.class, getEntityBoundingBox());
                for (Entity ent : list) {
                    if (this.getRidingEntity() == null)
                        ent.startRiding(this);
                }

                if (this.posY > transferHeightIn(world.provider.getDimension())) {
                    setCapsuleMotion(1);
                    double landingLocX, landingLocZ;
                    World world;

                    if ((world = DimensionManager.getWorld(dstTilePos.dimid)) == null) {
                        DimensionManager.initDimension(dstTilePos.dimid);
                        world = DimensionManager.getWorld(dstTilePos.dimid);
                    }

                    if (world != null) {
                        TileEntity tile = world.getTileEntity(dstTilePos.pos.getBlockPos());

                        if (tile instanceof TileSpaceElevator) {
                            landingLocX = ((TileSpaceElevator) tile).getLandingLocationX();
                            landingLocZ = ((TileSpaceElevator) tile).getLandingLocationZ();
                        } else {
                            setDead();
                            return;
                        }
                    } else {
                        dstTilePos = srcTilePos;
                        world = this.getEntityWorld();

                        TileEntity tile = world.getTileEntity(dstTilePos.pos.getBlockPos());

                        if (tile instanceof TileSpaceElevator) {
                            landingLocX = ((TileSpaceElevator) tile).getLandingLocationX();
                            landingLocZ = ((TileSpaceElevator) tile).getLandingLocationZ();
                        } else {
                            setDead();
                            return;
                        }
                    }

                    changeDimension(dstTilePos.dimid, landingLocX, 10, landingLocZ);

                    MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketDeOrbitingEvent(this));
                }
            }

            if (dstTilePos != null && dstTilePos.pos != null && this.posY >= dstTilePos.pos.y - 4 && world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                setCapsuleMotion(0);


                setPosition(dstTilePos.pos.x, dstTilePos.pos.y - 5, dstTilePos.pos.z);

                TileEntity e;

                if ((e = world.getTileEntity(dstTilePos.pos.getBlockPos())) instanceof TileSpaceElevator) {
                    ((TileSpaceElevator) e).notifyLanded(this);
                    standTime = 0;
                } else
                    this.setDead();

                //Dismount rider after being put in final place
                for (Entity ent : this.getPassengers()) {
                    ent.dismountRidingEntity();
                }
            }

            this.move(MoverType.SELF, 0, this.motionY, 0);
        } else if (isDescending()) {

            this.onGround = false;

            if (this.posY > 255)
                this.motionY = -5.85;
            else
                this.motionY = -1.85;

            if (!world.isRemote) {

                //Send packet to player for deorbit a bit delayed
                if (this.ticksExisted == 20)
                    PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, PACKET_DEORBIT), this);

                List<EntityPlayer> list = world.getEntitiesWithinAABB(EntityPlayer.class, getEntityBoundingBox());
                for (Entity ent : list) {
                    if (this.getRidingEntity() == null)
                        ent.startRiding(this);
                    ent.fallDistance = 0;   // entityRocket.java to prevent fall damage
                }
                this.fallDistance = 0; // I have no idea what I am doing I just copied this from

                if (this.posY <= dstTilePos.pos.y + 1 && world.provider.getDimension() != StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                    setCapsuleMotion(0);


                    setPosition(dstTilePos.pos.x, dstTilePos.pos.y + 1, dstTilePos.pos.z);

                    TileEntity e;

                    if ((e = world.getTileEntity(dstTilePos.pos.getBlockPos())) instanceof TileSpaceElevator) {
                        ((TileSpaceElevator) e).notifyLanded(this);
                        standTime = 0;
                    } else
                        this.setDead();

                    //Dismount rider after being put in final place
                    for (Entity ent : this.getPassengers()) {
                        ent.dismountRidingEntity();
                    }
                } else if (this.posY <= 15 && world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                    setCapsuleMotion(-1);
                    double landingLocX, landingLocZ;
                    World world;

                    if ((world = DimensionManager.getWorld(dstTilePos.dimid)) == null) {
                        DimensionManager.initDimension(dstTilePos.dimid);
                        world = DimensionManager.getWorld(dstTilePos.dimid);
                    }

                    if (world != null) {
                        TileEntity tile = world.getTileEntity(dstTilePos.pos.getBlockPos());

                        if (tile instanceof TileSpaceElevator) {
                            landingLocX = ((TileSpaceElevator) tile).getLandingLocationX();
                            landingLocZ = ((TileSpaceElevator) tile).getLandingLocationZ();
                        } else {
                            setDead();
                            return;
                        }
                    } else {
                        dstTilePos = srcTilePos;
                        world = this.getEntityWorld();

                        TileEntity tile = world.getTileEntity(dstTilePos.pos.getBlockPos());

                        if (tile instanceof TileSpaceElevator) {
                            landingLocX = ((TileSpaceElevator) tile).getLandingLocationX();
                            landingLocZ = ((TileSpaceElevator) tile).getLandingLocationZ();
                        } else {
                            setDead();
                            return;
                        }
                    }

                    changeDimension(dstTilePos.dimid, landingLocX, transferHeightIn(dstTilePos.dimid), landingLocZ);

                    MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketDeOrbitingEvent(this));
                } else
                    this.move(MoverType.SELF, 0, this.motionY, 0);
            } else
                this.move(MoverType.SELF, 0, this.motionY, 0);
        } else {
            List<EntityPlayer> list = world.getEntitiesWithinAABB(EntityPlayer.class, getEntityBoundingBox());

            if (!world.isRemote) {

                TileEntity srcTile = null;
                if (list.isEmpty())
                    standTime = 0;
                else if (dstTilePos != null && TileSpaceElevator.isDestinationValid(dstTilePos.dimid, dstTilePos, new HashedBlockPosition(getPosition()), world.provider.getDimension()))
                    standTime++;

                if (srcTilePos != null && srcTilePos.pos != null)
                    srcTile = world.getTileEntity(srcTilePos.pos.getBlockPos());


                if (srcTile instanceof TileSpaceElevator && !((TileSpaceElevator) srcTile).getMachineEnabled())
                    standTime = 0;

                setStandTime(standTime);

                //Begin ascending
                if (standTime > MAX_STANDTIME) {

                    if (srcTilePos != null && srcTilePos.pos != null) {
                        srcTile = world.getTileEntity(srcTilePos.pos.getBlockPos());

                        if (srcTile instanceof TileSpaceElevator && ((TileSpaceElevator) srcTile).attemptLaunch()) {

                            if (world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                                setCapsuleMotion(-1);
                            } else {
                                setCapsuleMotion(1);
                            }
                            //Make sure we mount player before takeoff
                            List<EntityPlayer> list2 = world.getEntitiesWithinAABB(EntityPlayer.class, getEntityBoundingBox());

                            for (Entity ent : list2) {
                                if (this.getRidingEntity() == null)
                                    ent.startRiding(this);
                            }
                            MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketLaunchEvent(this));
                            PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, PACKET_LAUNCH_EVENT), this);
                        }
                    }
                }
            } else if (!list.isEmpty()) {
                TileEntity srcTile = null;
                if (srcTilePos != null && srcTilePos.pos != null)
                    srcTile = world.getTileEntity(srcTilePos.pos.getBlockPos());


                if (srcTile instanceof TileSpaceElevator && !((TileSpaceElevator) srcTile).getMachineEnabled())
                    Stellurgy.proxy.displayMessage(LibVulpes.proxy.getLocalizedString("msg.spaceElevator.turnedOff"), 5);
                else if (dstTilePos != null)
                    Stellurgy.proxy.displayMessage(LibVulpes.proxy.getLocalizedString("msg.spaceElevator.ascentReady") + ": " + (int) ((MAX_STANDTIME - getStandTime()) / 20) + "\nDST " + dstTilePos, 5);
                else
                    Stellurgy.proxy.displayMessage(LibVulpes.proxy.getLocalizedString("msg.label.noneSelected"), 5);
            }
        }

        //setDead();
    }

    @Override
    public double getMountedYOffset() {
        return 0.3;
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox() {
        AxisAlignedBB aabb = new AxisAlignedBB(getEntityBoundingBox().minX, getEntityBoundingBox().minY, getEntityBoundingBox().minZ, getEntityBoundingBox().maxX, getEntityBoundingBox().maxY - 3, getEntityBoundingBox().maxZ);
        return isAscending() || isDescending() ? null : aabb;
    }

    @SideOnly(Side.CLIENT)
    public boolean isInRangeToRenderDist(double par1) {
        //double d1 = this.boundingBox.getAverageEdgeLength();
        //d1 *= 4096.0D * this.renderDistanceWeight;
        return par1 < 16777216D;
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == PACKET_WRITE_DST_INFO) {
            out.writeBoolean(dstTilePos != null);

            if (dstTilePos != null) {
                out.writeInt(dstTilePos.dimid);
                out.writeInt(dstTilePos.pos.x);
                out.writeInt(dstTilePos.pos.y);
                out.writeInt(dstTilePos.pos.z);
            }
        } else if (id == PACKET_WRITE_SRC_INFO) {
            out.writeBoolean(dstTilePos != null);

            if (srcTilePos != null) {
                out.writeInt(srcTilePos.dimid);
                out.writeInt(srcTilePos.pos.x);
                out.writeInt(srcTilePos.pos.y);
                out.writeInt(srcTilePos.pos.z);
            }
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {

        if (packetId == PACKET_WRITE_DST_INFO || packetId == PACKET_WRITE_SRC_INFO) {
            if (in.readBoolean()) {
                nbt.setInteger("dimid", in.readInt());
                nbt.setInteger("x", in.readInt());
                nbt.setInteger("y", in.readInt());
                nbt.setInteger("z", in.readInt());
            }
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == PACKET_WRITE_DST_INFO && world.isRemote) {
            if (nbt.hasKey("dimid")) {
                dstTilePos = new DimensionBlockPosition(nbt.getInteger("dimid"), new HashedBlockPosition(nbt.getInteger("x"), nbt.getInteger("y"), nbt.getInteger("z")));
            } else dstTilePos = null;
        } else if (id == PACKET_WRITE_SRC_INFO && world.isRemote) {
            if (nbt.hasKey("dimid")) {
                srcTilePos = new DimensionBlockPosition(nbt.getInteger("dimid"), new HashedBlockPosition(nbt.getInteger("x"), nbt.getInteger("y"), nbt.getInteger("z")));
            } else srcTilePos = null;
        } else if (id == PACKET_LAUNCH_EVENT && world.isRemote) {
            List<EntityPlayer> list = world.getEntitiesWithinAABB(EntityPlayer.class, getEntityBoundingBox());
            for (Entity ent : list) {
                if (this.getRidingEntity() == null)
                    ent.startRiding(this);
            }

            MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketLaunchEvent(this));
        } else if (id == PACKET_DEORBIT && world.isRemote) {
            MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketDeOrbitingEvent(this));
        }
    }
}
