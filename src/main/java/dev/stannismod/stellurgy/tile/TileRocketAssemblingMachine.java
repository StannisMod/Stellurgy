package dev.stannismod.stellurgy.tile;

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
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.ITickable;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.RocketEvent.RocketLandedEvent;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.item.ItemPackedStructure;
import dev.stannismod.stellurgy.network.PacketInvalidLocationNotify;
import dev.stannismod.stellurgy.tile.TileRocketAssemblingMachine.ErrorCodes;
import dev.stannismod.stellurgy.tile.hatch.TileSatelliteHatch;
import dev.stannismod.stellurgy.util.StorageChunk;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.block.RotatableBlock;
import dev.stannismod.stellurgy.libvulpes.client.util.ProgressBarImage;
import dev.stannismod.stellurgy.libvulpes.interfaces.ILinkableTile;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;
import dev.stannismod.stellurgy.libvulpes.network.PacketEntity;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.IMultiblock;
import dev.stannismod.stellurgy.libvulpes.tile.TileEntityRFConsumer;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.IconResource;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import dev.stannismod.stellurgy.api.*;
import dev.stannismod.stellurgy.block.*;
import dev.stannismod.stellurgy.util.NuclearEngineLimit;

/**
 * Purpose: validate the rocket structure as well as give feedback to the player as to what needs to be
 * changed to complete the rocket structure
 * Also will be used to "build" the rocket components from the placed frames, control fuel flow etc
 *
 *
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
public class TileRocketAssemblingMachine extends TileEntityRFConsumer implements ITickable, IButtonInventory, INetworkMachine, IDataSync, IModularInventory, IProgressBar, ILinkableTile,
        dev.stannismod.stellurgy.network.IShipReadoutReceiver {

    protected static final ResourceLocation backdrop = new ResourceLocation("stellurgy", "textures/gui/rocketBuilder.png");
    protected static final ProgressBarImage verticalProgressBar = new ProgressBarImage(76, 93, 8, 52, 176, 15, 2, 38, 3, 2, EnumFacing.UP, backdrop);
    private final static int MAXSCANDELAY = 10;
    private final static int ENERGYFOROP = 100;
    private final static int MAX_SIZE = 16;
    private final static int MAX_SIZE_Y = 128;
    private final static int MIN_SIZE = 3;
    private final static int MIN_SIZE_Y = 4;
    private static final ProgressBarImage horizontalProgressBar = new ProgressBarImage(89, 9, 81, 17, 176, 0, 80, 15, 0, 2, EnumFacing.EAST, backdrop);
    private static final Block[] viableBlocks = {StellurgyBlocks.blockLaunchpad, StellurgyBlocks.blockLandingPad};
    protected ModuleText errorText;
    protected StatsRocket stats;
    protected AxisAlignedBB bbCache;
    /**
     * World position of an Advanced Flight Computer found in the last scan, or
     * {@code null} if none. Transient build-routing state (scan &rarr; assemble within
     * one tick): its presence makes the build a tier-2 ship instead of a rocket.
     */
    private BlockPos scannedFlightComputerPos;
    /**
     * World position of a pilot seat found in the most recent scan, or {@code null} if none.
     * On a tier-2 build it is linked to {@link #scannedFlightComputerPos} at assembly so the
     * seat can route pilot input to the flight computer once the craft becomes a ship.
     */
    private BlockPos scannedPilotSeatPos;

    /**
     * The navigation computer found in the build, if any. A ship without one flies perfectly well —
     * it simply cannot jump — so this is recorded, never required.
     */
    private BlockPos scannedNavComputerPos;

    /**
     * Every ship machine in the build that has to know which ship it belongs to — the field
     * generator, its capacitors, the hull emitters, the dampeners. They are collected as one list
     * rather than one field each because they are all linked the same way and for the same reason:
     * a machine that cannot name its own ship is a machine another ship can borrow.
     */
    private final java.util.List<BlockPos> scannedShipMachines = new java.util.ArrayList<>();

    /**
     * The readout of the ship on the pad at the last scan, or {@code null} when the build is not a
     * ship. Server side it gates assembly; client side it is what the Scan shows. Not saved: a scan
     * is a snapshot, and the next one replaces it.
     */
    private dev.stannismod.stellurgy.ship.control.ShipReadout tier2Readout = null;

    /** Who pressed Scan or Build last; the one player a ship readout is sent to. */
    private java.util.UUID scanRequester = null;

    /**
     * The thrust-to-weight a pilot was warned about, so the next press on the SAME build assembles
     * it. A build that changed in between is a different craft and is warned about afresh.
     */
    private double warnedThrustToWeight = Double.NaN;

    private void warnRequesterLowThrust(double thrustToWeight) {
        if (scanRequester == null || world.getMinecraftServer() == null) {
            return;
        }
        net.minecraft.entity.player.EntityPlayerMP player =
                world.getMinecraftServer().getPlayerList().getPlayerByUUID(scanRequester);
        if (player != null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
                    "msg.rocketbuilder.lowtwr", String.format(java.util.Locale.ROOT, "%.2f", thrustToWeight)));
        }
    }

    private void sendTier2Readout() {
        if (tier2Readout == null || scanRequester == null || world.getMinecraftServer() == null) {
            return;
        }
        net.minecraft.entity.player.EntityPlayerMP player =
                world.getMinecraftServer().getPlayerList().getPlayerByUUID(scanRequester);
        if (player != null) {
            PacketHandler.sendToPlayer(new dev.stannismod.stellurgy.network.PacketShipReadout(
                    getPos(), tier2Readout, false, 0.0D), player);
        }
    }

    /** Client side: the Scan's ship readout arrived. */
    @Override
    public void acceptReadout(dev.stannismod.stellurgy.ship.control.ShipReadout readout, boolean saturated,
                              double wheelFill) {
        this.tier2Readout = readout;
        updateText();
    }

    /** This build's ship readout at the last scan; {@code null} when it is not a ship or was not scanned. */
    public dev.stannismod.stellurgy.ship.control.ShipReadout tier2Readout() {
        return tier2Readout;
    }
    protected ErrorCodes status;
    private ModuleText thrustText, weightText, fuelText, accelerationText;
    private int totalProgress;
    private int progress; // How long until scan is finished from 0 -> num blocks
    private int prevProgress; // Used for client/server sync
    private boolean building; //True is rocket is being built, false if only scanning or otherwise
    private int lastRocketID;
    private List<HashedBlockPosition> blockPos;
    private int relinkRetries = 0;           // how many relinking tries left
    private long nextRelinkAttempt = 0L;     // world time for next try

    public TileRocketAssemblingMachine() {
        super(100000);

        blockPos = new LinkedList<>();

        status = ErrorCodes.UNSCANNED;
        stats = new StatsRocket();
        building = false;
        prevProgress = 0;
    }

    private boolean registeredBus = false;

    @Override
    public void onLoad() {
        if (!world.isRemote && !registeredBus) {
            MinecraftForge.EVENT_BUS.register(this);
            registeredBus = true;
        }
        if (!world.isRemote) {
            relinkRetries = 15; // give it time
            nextRelinkAttempt = world.getTotalWorldTime() + 20;
            tryRelinkNow(); // best-effort first shot
        }
        if (world.isRemote) return;

        // Recompute pad bounds and relink infra to any rockets already on the pad
        bbCache = getRocketPadBounds(world, pos);
        if (bbCache != null) {
            final AxisAlignedBB box = bbCache.grow(1.0E-4, 1.0E-4, 1.0E-4);
            List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, box);
            if (!rockets.isEmpty()) {
                for (IInfrastructure infra : getConnectedInfrastructure()) {
                    for (EntityRocketBase r : rockets) {
                        if (infra instanceof dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) {
                            ((dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) infra)
                                    .markRocketFromAssembler(r);
                        }
                        r.linkInfrastructure(infra);
                    }
                }
            }
        }
    }  

    @Override
    public void invalidate() {
        super.invalidate();
        unregisterFromBus();
        relinkRetries = 0;
        nextRelinkAttempt = 0L;
        // Notify linked multiblocks BEFORE clearing (server only)
        if (world != null && !world.isRemote) {
            for (HashedBlockPosition p : blockPos) {
                TileEntity te = world.getTileEntity(p.getBlockPos());
                if (te instanceof IMultiblock) {
                    ((IMultiblock) te).setIncomplete();
                }
            }
        }

        // Clear caches
        bbCache = null;
        stats.reset();
        blockPos.clear();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        unregisterFromBus();
        relinkRetries = 0;
        nextRelinkAttempt = 0L;
        // Clear caches
        bbCache = null;
        stats.reset();
        blockPos.clear();
    }


    private void unregisterFromBus() {
        if (registeredBus) {
            MinecraftForge.EVENT_BUS.unregister(this);
            registeredBus = false;
        }
    }

    // A server stop unloads worlds without unloading their chunks, so onChunkUnload never runs then;
    // without this the machine, and its world, stayed on the process-wide bus after the server.
    @SubscribeEvent
    public void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (event.getWorld() == world) {
            unregisterFromBus();
        }
    }

    public ErrorCodes getStatus() {
        return status;
    }

    public void setStatus(int value) {
        status = errorCodeFromOrdinal(value);
    }

    /** Decode a persisted/synced {@link ErrorCodes} ordinal defensively: an
     *  out-of-range value (corrupt NBT, or a save written by a build with more
     *  enum constants and then downgraded) maps to the neutral idle verdict
     *  instead of throwing ArrayIndexOutOfBoundsException. The persisted format
     *  stays an ordinal int, so this is fully save/wire read-compatible. */
    private static ErrorCodes errorCodeFromOrdinal(int value) {
        ErrorCodes[] all = ErrorCodes.values();
        return (value >= 0 && value < all.length) ? all[value] : ErrorCodes.UNSCANNED;
    }

    public StatsRocket getRocketStats() {
        return stats;
    }

    public AxisAlignedBB getBBCache() {
        return bbCache;
    }

    public int getTotalProgress() {
        return totalProgress;
    }

    public void setTotalProgress(int scanTotalBlocks) {
        this.totalProgress = scanTotalBlocks;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int scanTime) {
        this.progress = scanTime;
    }

    public double getNormallizedProgress() {
        return progress / (double) (totalProgress * MAXSCANDELAY);
    }

    public float getAcceleration(float gravitationalMultiplier) {
        return stats.getAcceleration(gravitationalMultiplier);
    }

    /** Wet mass of the scanned rocket, kilograms. */
    public float getMass() {
        return stats.getMass();
    }

    public int getThrust() {
        return stats.getThrust();
    }

    /**
     * The launch gate's own verdict on the craft this assembler last scanned, asked of the craft as
     * it will stand when fully fuelled ({@code StatsRocket#withTanksFull}) at the gravity of the world
     * it is being assembled in. A craft the assembler builds can therefore launch from here full.
     */
    public boolean canLaunchFullFromHere() {
        return stats.withTanksFull().canLaunch(getGravityMultiplier());
    }

    /** The thrust-to-weight ratio the launch gate judges here: tanks full, this world's gravity. */
    public float getThrustToWeightRatio() {
        return stats.withTanksFull().getThrustToWeightRatio(getGravityMultiplier());
    }

    public boolean hasEnoughFuel(@Nonnull FuelType fuelType) {
        // rocketRequireFuel=false means fuel is not needed to fly, so assembly
        // must never gate on fuel adequacy. Returning early here is required:
        // getBaseFuelRate() is 0 by design when fuel isn't required, which the
        // guard below would otherwise read as "can't reach orbit" -> NOFUEL.
        if (!StellurgyConfiguration.getCurrentConfig().rocketRequireFuel) {
            return true;
        }
        if (stats.getBaseFuelRate(fuelType) <= 0) {
            return false;
        }
        float g = getGravityMultiplier();
        // Acceleration grows as fuel burns off (wet -> dry), so integrate over the burn using the
        // average of the full-tank and empty-tank accelerations rather than the (often near-zero)
        // full-tank value alone.
        float aAvg = (getAcceleration(g) + stats.getDryAcceleration(g)) / 2f;
        if (aAvg <= 0) {
            return false;
        }
        float fueltime = (float) stats.getFuelCapacity(fuelType) / stats.getBaseFuelRate(fuelType);
        float s_can = aAvg / 2f * fueltime * fueltime;
        // The climb a launch from HERE needs; a world with no line has no orbit to reach.
        java.util.OptionalInt line = DimensionManager.getInstance().transferLineOf(world.provider.getDimension());
        if (!line.isPresent()) {
            return false;
        }
        float target_s = line.getAsInt() - this.getPos().getY();
        return s_can > target_s;
    }

    public float getGravityMultiplier() {
        return DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension()).getGravitationalMultiplier();
    }

    public int getFuel(@Nullable FuelType fuelType) {
        return (int) (stats.getFuelCapacity(fuelType) * StellurgyConfiguration.getCurrentConfig().fuelCapacityMultiplier);
    }

    public boolean isBuilding() {
        return building;
    }

    public void setBuilding(boolean building) {
        this.building = building;
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return pass == 1;
    }

    @Override
    public int getPowerPerOperation() {
        return ENERGYFOROP;
    }

    @Override
    public void performFunction() {

        if (!isScanning()) return; 
        if (progress >= (totalProgress * MAXSCANDELAY)) {
            if (!world.isRemote) {
                if (building)
                    assembleRocket();
                else
                    scanRocket(world, pos, bbCache);
            }
            totalProgress = -1;
            progress = 0;
            prevProgress = 0;
            building = false; //Done building

            //TODO call function instead
            if (thrustText != null)
                updateText();

        }

        progress++;

        if (!this.world.isRemote && this.energy.getUniversalEnergyStored() < getPowerPerOperation() && progress - prevProgress > 0) {
            prevProgress = progress;
            PacketHandler.sendToNearby(new PacketMachine(this, (byte) 2), this.world.provider.getDimension(), this.getPos(), 32);
        }

    }

    @Override
    public boolean canPerformFunction() {
        return isScanning();
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        if (isScanning() && bbCache != null) {
            return bbCache;
        }
        return super.getRenderBoundingBox();
    }

    public boolean isScanning() {
        return totalProgress > 0;
    }

    public AxisAlignedBB scanRocket(World world, BlockPos pos2, AxisAlignedBB bb) {

        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);

        stats = new StatsRocket(); // reset stats
        scannedFlightComputerPos = null; // reset tier-2 routing state each scan
        scannedPilotSeatPos = null;
        scannedNavComputerPos = null;
        scannedShipMachines.clear();
        tier2Readout = null;

        //if already a rocket exists, output their stats

        if (getBBCache() == null) {
            bbCache = getRocketPadBounds(world, pos);
        }

        if (getBBCache() != null) {
            double buffer = 0.0001;
            AxisAlignedBB bufferedBB = bbCache.grow(buffer, buffer, buffer);
            List<EntityRocket> rockets = world.getEntitiesWithinAABB(EntityRocket.class, bufferedBB);
            if (rockets.size() == 1){
                rockets.get(0).recalculateStats();
                this.stats = rockets.get(0).stats;
                status = ErrorCodes.ALREADY_ASSEMBLED;
                return null;
            }
        }


        long thrustMonopropellant = 0;
        long thrustBipropellant = 0;
        long thrustNuclearNozzleLimit = 0;
        long thrustNuclearReactorLimit = 0;
        int thrustNuclearTotalLimit;
        int monopropellantfuelUse = 0;
        int bipropellantfuelUse = 0;
        int nuclearWorkingFluidUseMax = 0;
        int fuelCapacityMonopropellant = 0;
        int fuelCapacityBipropellant = 0;
        int fuelCapacityOxidizer = 0;
        int fuelCapacityNuclearWorkingFluid = 0;

        float drillPower = 0f;
        stats.reset();

        int actualMinX = (int) bb.maxX,
                actualMinY = (int) bb.maxY,
                actualMinZ = (int) bb.maxZ,
                actualMaxX = (int) bb.minX,
                actualMaxY = (int) bb.minY,
                actualMaxZ = (int) bb.minZ;


        for (int xCurr = (int) bb.minX; xCurr <= bb.maxX; xCurr++) {
            for (int zCurr = (int) bb.minZ; zCurr <= bb.maxZ; zCurr++) {
                for (int yCurr = (int) bb.minY; yCurr <= bb.maxY; yCurr++) {

                    BlockPos currBlockPos = new BlockPos(xCurr, yCurr, zCurr);
                    IBlockState state = world.getBlockState(currBlockPos);
                    Block block = state.getBlock();

                    if (!world.isAirBlock(currBlockPos)) {
                        if (xCurr < actualMinX)
                            actualMinX = xCurr;
                        if (yCurr < actualMinY)
                            actualMinY = yCurr;
                        if (zCurr < actualMinZ)
                            actualMinZ = zCurr;
                        if (xCurr > actualMaxX)
                            actualMaxX = xCurr;
                        if (yCurr > actualMaxY)
                            actualMaxY = yCurr;
                        if (zCurr > actualMaxZ)
                            actualMaxZ = zCurr;
                    }
                }
            }
        }

        boolean hasSatellite = false;
        boolean hasGuidance = false;
        boolean invalidBlock = false;
        // Tier-2 control blocks are counted, not just located: a craft may carry at most ONE
        // Advanced Flight Computer and ONE pilot seat (the "last scanned wins" slot assignment
        // below would otherwise silently pick one and leave the others as live hazards — a second
        // computer ticks and fights the linked one for the ship, a second seat is silently dead).
        int flightComputerCount = 0;
        int pilotSeatCount = 0;
        float mass = 0;

        if (verifyScan(bb, world)) {
            for (int yCurr = (int) bb.minY; yCurr <= bb.maxY; yCurr++) {
                for (int xCurr = (int) bb.minX; xCurr <= bb.maxX; xCurr++) {
                    for (int zCurr = (int) bb.minZ; zCurr <= bb.maxZ; zCurr++) {

                        BlockPos currBlockPos = new BlockPos(xCurr, yCurr, zCurr);
                        BlockPos abovePos = new BlockPos(xCurr, yCurr + 1, zCurr);
                        BlockPos belowPos = new BlockPos(xCurr, yCurr - 1, zCurr);

                        if (!world.isAirBlock(currBlockPos)) {
                            IBlockState state = world.getBlockState(currBlockPos);
                            Block block = state.getBlock();

                            if (StellurgyConfiguration.getCurrentConfig().blackListRocketBlocks.contains(block)) {
                                if (!block.isReplaceable(world, currBlockPos)) {
                                    invalidBlock = true;
                                    if (!world.isRemote)
                                        PacketHandler.sendToNearby(new PacketInvalidLocationNotify(new HashedBlockPosition(xCurr, yCurr, zCurr)), world.provider.getDimension(), getPos(), 64);
                                }
                                continue;
                            }

                            if (StellurgyConfiguration.getCurrentConfig().advancedWeightSystem) {
                                mass += dev.stannismod.stellurgy.Stellurgy.weights().getWeight(world, currBlockPos);
                            } else {
                                // Weight system off: every block counts as one unit of mass.
                                mass += 1;
                            }

                            //If rocketEngine increaseThrust
                            final float x = xCurr - actualMinX - ((actualMaxX - actualMinX) / 2f);
                            final float z = zCurr - actualMinZ - ((actualMaxZ - actualMinZ) / 2f);
                            if (block instanceof IRocketEngine && (world.getBlockState(belowPos).getBlock().isAir(world.getBlockState(belowPos), world, belowPos) || world.getBlockState(belowPos).getBlock() instanceof BlockLandingPad || world.getBlockState(belowPos).getBlock() == StellurgyBlocks.blockLaunchpad)) {
                                if (block instanceof BlockNuclearRocketMotor) {
                                    nuclearWorkingFluidUseMax += ((IRocketEngine) block).getFuelConsumptionRate(world, xCurr, yCurr, zCurr);
                                    thrustNuclearNozzleLimit += ((IRocketEngine) block).getThrust(world, currBlockPos);
                                } else if (block instanceof BlockBipropellantRocketMotor) {
                                    bipropellantfuelUse += ((IRocketEngine) block).getFuelConsumptionRate(world, xCurr, yCurr, zCurr);
                                    thrustBipropellant += ((IRocketEngine) block).getThrust(world, currBlockPos);
                                } else if (block instanceof BlockRocketMotor) {
                                    monopropellantfuelUse += ((IRocketEngine) block).getFuelConsumptionRate(world, xCurr, yCurr, zCurr);
                                    thrustMonopropellant += ((IRocketEngine) block).getThrust(world, currBlockPos);
                                }
                                stats.addEngineLocation(x + 0.5f, yCurr - actualMinY + 0.5f, z + 0.5f);
                            }

                            if (block instanceof IFuelTank) {
                                if (block instanceof BlockBipropellantFuelTank) {
                                    fuelCapacityBipropellant += (((IFuelTank) block).getMaxFill(world, currBlockPos, state) * StellurgyConfiguration.getCurrentConfig().fuelCapacityMultiplier);
                                } else if (block instanceof BlockOxidizerFuelTank) {
                                    fuelCapacityOxidizer += (((IFuelTank) block).getMaxFill(world, currBlockPos, state) * StellurgyConfiguration.getCurrentConfig().fuelCapacityMultiplier);
                                } else if (block instanceof BlockNuclearFuelTank) {
                                    fuelCapacityNuclearWorkingFluid += (((IFuelTank) block).getMaxFill(world, currBlockPos, state) * StellurgyConfiguration.getCurrentConfig().fuelCapacityMultiplier);
                                } else if (block instanceof BlockFuelTank) {
                                    fuelCapacityMonopropellant += (((IFuelTank) block).getMaxFill(world, currBlockPos, state) * StellurgyConfiguration.getCurrentConfig().fuelCapacityMultiplier);
                                }
                            }

                            if (block instanceof IRocketNuclearCore && ((world.getBlockState(belowPos).getBlock() instanceof IRocketNuclearCore) || (world.getBlockState(belowPos).getBlock() instanceof IRocketEngine))) {
                                thrustNuclearReactorLimit += ((IRocketNuclearCore) block).getMaxThrust(world, currBlockPos);
                            }

                            if (block instanceof BlockSeat && world.getBlockState(abovePos).getBlock().isPassable(world, abovePos)) {
                                stats.addPassengerSeat((int) Math.floor(x), yCurr - actualMinY, (int) Math.floor(z));
                            }

                            if (block instanceof IMiningDrill) {
                                drillPower += ((IMiningDrill) block).getMiningSpeed(world, currBlockPos);
                            }

                            TileEntity tile = world.getTileEntity(currBlockPos);
                            if (tile instanceof TileSatelliteHatch) {
                                hasSatellite = true;
                                if (StellurgyConfiguration.getCurrentConfig().advancedWeightSystem) {
                                    TileSatelliteHatch hatch = (TileSatelliteHatch) tile;
                                    if (hatch.getSatellite() != null) {
                                        mass += hatch.getSatellite().getProperties().getWeight();
                                    } else if (hatch.getStackInSlot(0).getItem() instanceof ItemPackedStructure) {
                                        ItemPackedStructure struct = (ItemPackedStructure) hatch.getStackInSlot(0).getItem();
                                        mass += struct.getStructure(hatch.getStackInSlot(0)).getWeight();
                                    }
                                }
                            } else if (tile instanceof TileGuidanceComputer) {
                                hasGuidance = true;
                            } else if (tile instanceof TileAdvancedFlightComputer) {
                                scannedFlightComputerPos = currBlockPos;
                                flightComputerCount++;
                            } else if (tile instanceof TilePilotSeat) {
                                scannedPilotSeatPos = currBlockPos;
                                pilotSeatCount++;
                            } else if (tile instanceof TileNavigationComputer) {
                                scannedNavComputerPos = currBlockPos;
                            } else if (tile instanceof TileShipComponent) {
                                scannedShipMachines.add(currBlockPos);
                            }
                        }
                    }
                }
            }

            NuclearEngineLimit nuclear = NuclearEngineLimit.derive(
                    thrustNuclearNozzleLimit, thrustNuclearReactorLimit, nuclearWorkingFluidUseMax);
            int nuclearWorkingFluidUse = nuclear.workingFluidUse;
            thrustNuclearTotalLimit = nuclear.thrust;

            // Set fuel stats
            // Thrust depending on rocket type
            stats.setBaseFuelRate(FuelType.LIQUID_MONOPROPELLANT, monopropellantfuelUse);
            stats.setBaseFuelRate(FuelType.LIQUID_BIPROPELLANT,   bipropellantfuelUse);
            stats.setBaseFuelRate(FuelType.LIQUID_OXIDIZER,       bipropellantfuelUse);
            stats.setBaseFuelRate(FuelType.NUCLEAR_WORKING_FLUID, nuclearWorkingFluidUse);

            stats.setFuelRate(FuelType.LIQUID_MONOPROPELLANT, monopropellantfuelUse);
            stats.setFuelRate(FuelType.LIQUID_BIPROPELLANT,   bipropellantfuelUse);
            stats.setFuelRate(FuelType.LIQUID_OXIDIZER,       bipropellantfuelUse);
            stats.setFuelRate(FuelType.NUCLEAR_WORKING_FLUID, nuclearWorkingFluidUse);

            // Fuel storage depending on rocket type
            stats.setFuelCapacity(FuelType.LIQUID_MONOPROPELLANT,      fuelCapacityMonopropellant);
            stats.setFuelCapacity(FuelType.LIQUID_BIPROPELLANT,        fuelCapacityBipropellant);
            stats.setFuelCapacity(FuelType.LIQUID_OXIDIZER,            fuelCapacityOxidizer);
            stats.setFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID,      fuelCapacityNuclearWorkingFluid);

            //Non-fuel stats
            stats.setMass(mass);
            stats.setThrust(Math.max(Math.max(thrustMonopropellant, thrustBipropellant), thrustNuclearTotalLimit));
            stats.setDrillingPower(drillPower);

            //Total stats, used to check if the user has tried to apply two or more types of thrust/fuel
            int totalFuel = fuelCapacityBipropellant + fuelCapacityNuclearWorkingFluid + fuelCapacityMonopropellant;
            int totalFuelUse = bipropellantfuelUse + nuclearWorkingFluidUse + monopropellantfuelUse;
            //System.out.println("rocket fuel use:"+totalFuelUse);

            // Biprop requirement: if any bipropellant thrust exists, require both tanks.
            // Skipped entirely when fuel isn't required (rocketRequireFuel=false) — no
            // tanks of any kind are needed to assemble then.
            if (scannedFlightComputerPos == null
                    && StellurgyConfiguration.getCurrentConfig().rocketRequireFuel && thrustBipropellant > 0) {
                if (fuelCapacityBipropellant <= 0 || fuelCapacityOxidizer <= 0) {
                    status = ErrorCodes.NOFUEL;
                    return new AxisAlignedBB(actualMinX, actualMinY, actualMinZ, actualMaxX, actualMaxY, actualMaxZ);
                }
            }

            //Set status
            if (invalidBlock) {
                status = ErrorCodes.INVALIDBLOCK;

            } else if (scannedFlightComputerPos == null
                    && (((fuelCapacityBipropellant > 0 && totalFuel > fuelCapacityBipropellant)
                    || (fuelCapacityMonopropellant > 0 && totalFuel > fuelCapacityMonopropellant)
                    || (fuelCapacityNuclearWorkingFluid > 0 && totalFuel > fuelCapacityNuclearWorkingFluid))
                    ||
                    ((thrustBipropellant > 0 && totalFuelUse > bipropellantfuelUse)
                    || (thrustMonopropellant > 0 && totalFuelUse > monopropellantfuelUse)
                    || (thrustNuclearTotalLimit > 0 && totalFuelUse > nuclearWorkingFluidUse)))) {
                status = ErrorCodes.COMBINEDTHRUST;

            } else if (flightComputerCount > 1) {
                // One craft — one command authority. A second Advanced Flight Computer would tick
                // and steer against the linked one (both are physics force controllers), so a
                // multi-computer build is rejected at the scan, before anything can assemble.
                status = ErrorCodes.MULTIPLEFLIGHTCOMPUTERS;

            } else if (scannedFlightComputerPos != null && pilotSeatCount > 1) {
                // One craft — one command seat. Only the last-scanned pilot seat would be linked;
                // a pilot in any other seat would have silently dead controls. Passenger seats
                // (the plain seat block) are unrestricted — this counts only pilot seats.
                status = ErrorCodes.MULTIPLEPILOTSEATS;

            } else if (scannedFlightComputerPos != null) {
                // A SHIP is not gated by a rocket's thrust and fuel checks. Its engines consume
                // nothing yet, and whether it can lift itself is a soft verdict, not a refusal: a
                // ship is finished in place, so an under-thrusted one is a craft to build onto. The
                // readout below says so, and assembly asks for a second press (assembleRocket).
                status = ErrorCodes.SUCCESS;

            } else if (!hasGuidance && !hasSatellite && scannedFlightComputerPos == null) {
                // An Advanced Flight Computer is the tier-2 ship's own flight computer, so it
                // satisfies the "computer with instructions" requirement. This used to ask whether
                // the physics substrate was present as well, for a build that could not become a
                // ship; the substrate is compiled into this jar, so the only build that asks is
                // one somebody removed it from.
                status = ErrorCodes.NOGUIDANCE;

            } else if (getThrust() <= 0 || !canLaunchFullFromHere()) {
                status = ErrorCodes.NOENGINES;

            } else if (StellurgyConfiguration.getCurrentConfig().rocketRequireFuel && thrustBipropellant > 0
                    && (fuelCapacityBipropellant <= 0 || fuelCapacityOxidizer <= 0)) {
                // Biprop engines require BOTH bipropellant AND oxidizer capacity
                status = ErrorCodes.NOFUEL;

            } else if (scannedFlightComputerPos == null
                    && (((thrustBipropellant > 0)      && !hasEnoughFuel(FuelType.LIQUID_BIPROPELLANT))
                    || ((thrustMonopropellant > 0)    && !hasEnoughFuel(FuelType.LIQUID_MONOPROPELLANT))
                    || ((thrustNuclearTotalLimit > 0) && !hasEnoughFuel(FuelType.NUCLEAR_WORKING_FLUID)))) {
                // "Can its tanks carry it to orbit" is asked of a ROCKET only. A rocket's one flight
                // is a climb to its world's orbit line, so a build that cannot make it is no rocket;
                // a ship with a flight computer is flown, and is a legitimate craft for flights that
                // never leave the planet.
                status = ErrorCodes.NOFUEL;

            } else {
                status = ErrorCodes.SUCCESS;
            }
        }
        
        // Normalize integer mins/maxes first
        int minXi = Math.min(actualMinX, actualMaxX);
        int minYi = Math.min(actualMinY, actualMaxY);
        int minZi = Math.min(actualMinZ, actualMaxZ);
        int maxXi = Math.max(actualMinX, actualMaxX);
        int maxYi = Math.max(actualMaxY, actualMinY);
        int maxZi = Math.max(actualMinZ, actualMaxZ);

        // A ship's readout: the flight model of the blocks on the pad, exactly as the assembled ship
        // will be surveyed, in the field of the world it stands in. Sent to whoever asked for the scan.
        if (scannedFlightComputerPos != null && status == ErrorCodes.SUCCESS && !world.isRemote) {
            dev.stannismod.stellurgy.integration.vs.HullSurvey survey =
                    dev.stannismod.stellurgy.integration.vs.HullSurvey.ofBox(world,
                            new BlockPos(minXi, minYi, minZi), new BlockPos(maxXi, maxYi, maxZi));
            if (survey != null) {
                tier2Readout = dev.stannismod.stellurgy.ship.control.ShipFlightModel.solve(0L,
                        survey.mass(), survey.design(), survey.live(),
                        dev.stannismod.stellurgy.ship.control.ControlFrame.HELM)
                        .readout(TileAdvancedFlightComputer.localGravity(world));
                sendTier2Readout();
            }
        }

        // use BlockPos ctor so the AABB is [min, max+1) in block space
        return new AxisAlignedBB(
            new BlockPos(minXi, minYi, minZi),
            new BlockPos(maxXi, maxYi, maxZi)
        );
    }

    protected void removeReplaceableBlocks(AxisAlignedBB bb) {
        for (int yCurr = (int) bb.minY; yCurr <= bb.maxY; yCurr++) {
            for (int xCurr = (int) bb.minX; xCurr <= bb.maxX; xCurr++) {
                for (int zCurr = (int) bb.minZ; zCurr <= bb.maxZ; zCurr++) {

                    BlockPos currBlockPos = new BlockPos(xCurr, yCurr, zCurr);

                    if (!world.isAirBlock(currBlockPos)) {
                        IBlockState state = world.getBlockState(currBlockPos);
                        Block block = state.getBlock();
                        if (StellurgyConfiguration.getCurrentConfig().blackListRocketBlocks.contains(block) && block.isReplaceable(world, currBlockPos)) {
                            if (!world.isRemote)
                                world.setBlockToAir(currBlockPos);
                        }
                    }
                }
            }
        }
    }

    private static boolean isEmptyAABB(@Nullable AxisAlignedBB b) {
        return b == null || b.maxX < b.minX || b.maxY < b.minY || b.maxZ < b.minZ;
    }


    private static AxisAlignedBB normalize(AxisAlignedBB b) {
        double minX = Math.min(b.minX, b.maxX);
        double minY = Math.min(b.minY, b.maxY);
        double minZ = Math.min(b.minZ, b.maxZ);
        double maxX = Math.max(b.minX, b.maxX);
        double maxY = Math.max(b.minY, b.maxY);
        double maxZ = Math.max(b.minZ, b.maxZ);
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }


    public void assembleRocket() {
        // server only + need a pad cache
        if (world.isRemote || bbCache == null) return;

        // Re-scan to get a tight non-air AABB and fresh stats/status
        final AxisAlignedBB scanBB = scanRocket(world, pos, bbCache);
        if (status != ErrorCodes.SUCCESS || scanBB == null) return;

        // Normalize and defensively guard against degenerate boxes
        final AxisAlignedBB rocketBB = normalize(scanBB);
        if (isEmptyAABB(rocketBB)) {
            status = ErrorCodes.FAIL_CUT;
            return;
        }

        // Tier-2 fork: an Advanced Flight Computer routes the build to a movable
        // ship (real blocks relocated into a physics-driven ship) rather than an
        // EntityRocket. Only when the optional integration is installed; otherwise
        // the computer is inert and the ordinary rocket is built below.
        //
        // The scanned structure is copied, not moved: the snapshot is the ship, the
        // physics mod's block search is bounded to its footprint, and the mod relocates
        // the blocks itself — so a craft resting on its pad takes neither the pad nor
        // anything touching it.
        if (scannedFlightComputerPos != null) {
            // A ship that cannot hold itself up here is built only when asked twice. Not refused: a
            // ship is finished in place, and an extra engine is one block away. The first press
            // warns and remembers what it warned about; a press on the same build assembles it.
            if (tier2Readout != null && !tier2Readout.canHover(
                    dev.stannismod.stellurgy.ship.control.ShipReadout.View.LIVE)) {
                double twr = tier2Readout.thrustToWeight(
                        dev.stannismod.stellurgy.ship.control.ShipReadout.View.LIVE);
                if (Double.compare(twr, warnedThrustToWeight) != 0) {
                    warnedThrustToWeight = twr;
                    warnRequesterLowThrust(twr);
                    return;
                }
            }
            warnedThrustToWeight = Double.NaN;
            removeReplaceableBlocks(rocketBB);
            // The craft is left where it was built and handed over AS IT STANDS: the assembly fits the
            // scanned region to the blocks still standing after the clean-up above and bounds the
            // substrate's block search to that, so nothing on or beside the pad joins the craft, and the
            // substrate relocates the real blocks itself. Until 2026-10-06 the craft was CUT out and
            // pasted a block higher, because that air gap was then the only separation from the pad; the
            // round trip killed every item lying near the pad and rebuilt every tile from NBT.
            //
            // Asked BEFORE the craft is handed over, because after that the substrate decides a tick
            // later and, refusing, drops it silently: the press read "finished" and there was no ship.
            // Nothing has been moved, so a refusal leaves the world exactly as it was.
            VSIntegration.AssemblyRefusal refusal = VSIntegration.builtTier2ShipRefusal(world, rocketBB);
            if (refusal != null) {
                switch (refusal) {
                    case TOO_LARGE:
                        status = ErrorCodes.SHIP_TOO_LARGE;
                        break;
                    case NO_FLIGHT_COMPUTER:
                        status = ErrorCodes.FAIL_CUT;
                        break;
                    default:
                        throw new IllegalStateException("an assembly refusal with no status: " + refusal);
                }
                return;
            }
            BlockPos shipAnchor = scannedFlightComputerPos;
            // Link a pilot seat (if the build has one) to the flight computer, before the
            // physics mod relocates the craft: the seat stores the computer's offset, which the
            // rigid relocation preserves, so the seated pilot's input reaches the computer.
            if (scannedPilotSeatPos != null) {
                TileEntity seatTe = world.getTileEntity(scannedPilotSeatPos);
                if (seatTe instanceof TilePilotSeat) {
                    ((TilePilotSeat) seatTe).linkToFlightComputer(shipAnchor);
                }
            }
            // Same relative-offset link for the navigation computer: the jump gate finds it from the
            // flight computer, and the offset is what survives the ship's relocation into subspace.
            if (scannedNavComputerPos != null) {
                TileEntity navTe = world.getTileEntity(scannedNavComputerPos);
                if (navTe instanceof TileNavigationComputer) {
                    ((TileNavigationComputer) navTe).linkToFlightComputer(shipAnchor);
                }
            }
            // Same link again for every hyperdrive-family machine in the build. Without it a
            // generator or a capacitor is just a block standing in space: the jump gate finds a
            // ship's machines by asking each one which flight computer it answers to, so an
            // unlinked one is invisible to its own ship and available to none.
            for (BlockPos machinePos : scannedShipMachines) {
                TileEntity machineTe = world.getTileEntity(machinePos);
                if (machineTe instanceof TileShipComponent) {
                    ((TileShipComponent) machineTe).linkToFlightComputer(shipAnchor);
                }
            }
            // Mint the ship's durable identity at assembly (before the physics mod relocates the
            // craft): tile NBT rides the relocation and every later crossing verbatim, so this id
            // is the one stable key for the ship (the physics mod's own UUID is re-minted per
            // re-assembly and must never key durable state).
            TileEntity afcTe = world.getTileEntity(shipAnchor);
            java.util.UUID durableShipId = null;
            if (afcTe instanceof TileAdvancedFlightComputer) {
                durableShipId = ((TileAdvancedFlightComputer) afcTe).getOrCreateShipId();
                // Record how big the craft is, while something still knows. After this the blocks
                // are a physics body and its extent is only recoverable by walking it; the jump
                // window has to be checked against the hull, and the assembler is the one place
                // that measured the hull in the first place. Offsets from the computer, so they
                // survive every relocation the ship will make.
                ((TileAdvancedFlightComputer) afcTe).setHullExtent(
                        (int) rocketBB.minX - scannedFlightComputerPos.getX(),
                        (int) rocketBB.minY - scannedFlightComputerPos.getY(),
                        (int) rocketBB.minZ - scannedFlightComputerPos.getZ(),
                        (int) rocketBB.maxX - scannedFlightComputerPos.getX(),
                        (int) rocketBB.maxY - scannedFlightComputerPos.getY(),
                        (int) rocketBB.maxZ - scannedFlightComputerPos.getZ());
            }
            // The name is NOT handed in, and that is the point. This assembly is anchored on the very
            // flight computer that carries the durable id, so the ship is asked what it is called
            // rather than told — one source of truth, the tile's own NBT, and no call site that can
            // forget. It went unbound here for exactly that reason: the id above was minted and the
            // value dropped, so a craft that had not yet crossed could not be found by its own name.
            // The REGION of the craft, not a point: the assembly fits it to the blocks, finds the flight
            // computer inside and takes the ship's identity off that tile. `shipAnchor` above is still
            // this build's computer and is still what the seat links to; it is not handed to the
            // assembly, because a caller that can pass an anchor can pass the wrong one. Nor is an
            // extent computed here: the fit is the assembly's, so there is no arithmetic of ours for the
            // flight computer's layer to fall outside of (measured once: a width derived here from
            // `rocketBB` reded all five ground-flight scenarios — no ship was ever spawned).
            VSIntegration.assembleBuiltTier2Ship(world, rocketBB);
            // A pilot who took the seat BEFORE assembly is riding a mount bound to the seat's
            // build-time position, which the relocation is about to vacate - once the blocks relocate
            // into the ship's subspace nothing in his control chain resolves and the ship ignores
            // him. Queue the rebind that re-expresses his boarding on the relocated seat; queued,
            // not done inline, because the relocation is asynchronous.
            if (scannedPilotSeatPos != null) {
                for (dev.stannismod.stellurgy.entity.EntityDummy mount :
                        world.getEntitiesWithinAABB(dev.stannismod.stellurgy.entity.EntityDummy.class,
                                rocketBB.grow(2.0))) {
                    BlockPos bound = mount.getSeatPos();
                    if (bound == null || !bound.equals(scannedPilotSeatPos)) {
                        continue; // an ordinary (passenger) seat mount, or someone else's seat
                    }
                    for (net.minecraft.entity.Entity passenger : mount.getPassengers()) {
                        if (passenger instanceof net.minecraft.entity.player.EntityPlayerMP) {
                            dev.stannismod.stellurgy.Stellurgy.spaceSubsystem().crewRebind.enqueue(
                                    (net.minecraft.world.WorldServer) world,
                                    (net.minecraft.entity.player.EntityPlayerMP) passenger,
                                    mount.getEntityId(), shipAnchor,
                                    shipAnchor.getX() - scannedPilotSeatPos.getX(),
                                    shipAnchor.getY() - scannedPilotSeatPos.getY(),
                                    shipAnchor.getZ() - scannedPilotSeatPos.getZ(),
                                    durableShipId);
                        }
                    }
                }
            }
            stats.reset();
            this.status = ErrorCodes.FINISHED;
            this.markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            return;
        }

        // Remove replaceable/blacklisted blocks *inside the tightened bounds*
        removeReplaceableBlocks(rocketBB);

        // Cut the world using the tightened box (avoid pad air)
        final StorageChunk storageChunk;
        try {
            storageChunk = StorageChunk.cutWorldBB(world, rocketBB);
        } catch (Throwable t) { // cover NegativeArraySizeException & other edge errors
            status = ErrorCodes.FAIL_CUT;
            return;
        }

        // Center spawn on tightened AABB
        final double cx = rocketBB.minX + (rocketBB.maxX - rocketBB.minX) / 2.0 + 0.5;
        final double cz = rocketBB.minZ + (rocketBB.maxZ - rocketBB.minZ) / 2.0 + 0.5;
        final double cy = this.getPos().getY();

        EntityRocket rocket = new EntityRocket(world, storageChunk, stats.copy(), cx, cy, cz);
        world.spawnEntity(rocket);

        NBTTagCompound nbtdata = new NBTTagCompound();
        rocket.writeToNBT(nbtdata);
        PacketHandler.sendToNearby(new PacketEntity(rocket, (byte) 0, nbtdata),
                rocket.world.provider.getDimension(), this.pos, 64);

        // Finish & link as before
        stats.reset();
        this.status = ErrorCodes.FINISHED;
        this.markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);

        for (IInfrastructure infrastructure : getConnectedInfrastructure()) {
            if (infrastructure instanceof dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) {
                ((dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) infrastructure)
                        .markRocketFromAssembler(rocket);
            }
            rocket.linkInfrastructure(infrastructure);
        }


        // Rescan so UI immediately reflects the post-build state
        scanRocket(world, pos, bbCache);
    }

    /**
     * Does not make sure the structure is complete, only gets max bounds!
     *
     * @param world the world
     * @param pos   coords to evaluate from
     * @return AxisAlignedBB bounds of structure if valid  otherwise null
     */
    public AxisAlignedBB getRocketPadBounds(World world, BlockPos pos) {
        EnumFacing direction = RotatableBlock.getFront(world.getBlockState(pos)).getOpposite();
        int xMin, zMin, xMax, zMax;
        int yCurrent = pos.getY() - 1;
        int xCurrent = pos.getX() + direction.getFrontOffsetX();
        int zCurrent = pos.getZ() + direction.getFrontOffsetZ();
        xMax = xMin = xCurrent;
        zMax = zMin = zCurrent;
        int xSize, zSize;

        BlockPos currPos = new BlockPos(xCurrent, yCurrent, zCurrent);

        if (world.isRemote)
            return null;

        //Get min and maximum Z/X bounds
        if (direction.getFrontOffsetX() != 0) {
            xSize = ZUtils.getContinuousBlockLength(world, direction, currPos, MAX_SIZE, viableBlocks);
            zMin = ZUtils.getContinuousBlockLength(world, EnumFacing.NORTH, currPos, MAX_SIZE, viableBlocks);
            zMax = ZUtils.getContinuousBlockLength(world, EnumFacing.SOUTH, currPos.add(0, 0, 1), MAX_SIZE - zMin, viableBlocks);
            zSize = zMin + zMax;

            zMin = zCurrent - zMin + 1;
            zMax = zCurrent + zMax;

            if (direction.getFrontOffsetX() > 0) {
                xMax = xCurrent + xSize - 1;
            }

            if (direction.getFrontOffsetX() < 0) {
                xMin = xCurrent - xSize + 1;
            }
        } else {
            zSize = ZUtils.getContinuousBlockLength(world, direction, currPos, MAX_SIZE, viableBlocks);
            xMin = ZUtils.getContinuousBlockLength(world, EnumFacing.WEST, currPos, MAX_SIZE, viableBlocks);
            xMax = ZUtils.getContinuousBlockLength(world, EnumFacing.EAST, currPos.add(1, 0, 0), MAX_SIZE - xMin, viableBlocks);
            xSize = xMin + xMax;

            xMin = xCurrent - xMin + 1;
            xMax = xCurrent + xMax;

            if (direction.getFrontOffsetZ() > 0) {
                zMax = zCurrent + zSize - 1;
            }

            if (direction.getFrontOffsetZ() < 0) {
                zMin = zCurrent - zSize + 1;
            }
        }


        int maxTowerSize = 0;
        //Check perimeter for structureBlocks and get the size
        for (int i = xMin; i <= xMax; i++) {
            if (world.getBlockState(new BlockPos(i, yCurrent, zMin - 1)).getBlock() == StellurgyBlocks.blockStructureTower) {
                maxTowerSize = Math.max(maxTowerSize, ZUtils.getContinuousBlockLength(world, EnumFacing.UP, new BlockPos(i, yCurrent, zMin - 1), MAX_SIZE_Y, StellurgyBlocks.blockStructureTower));
            }

            if (world.getBlockState(new BlockPos(i, yCurrent, zMax + 1)).getBlock() == StellurgyBlocks.blockStructureTower) {
                maxTowerSize = Math.max(maxTowerSize, ZUtils.getContinuousBlockLength(world, EnumFacing.UP, new BlockPos(i, yCurrent, zMax + 1), MAX_SIZE_Y, StellurgyBlocks.blockStructureTower));
            }
        }

        for (int i = zMin; i <= zMax; i++) {
            if (world.getBlockState(new BlockPos(xMin - 1, yCurrent, i)).getBlock() == StellurgyBlocks.blockStructureTower) {
                maxTowerSize = Math.max(maxTowerSize, ZUtils.getContinuousBlockLength(world, EnumFacing.UP, new BlockPos(xMin - 1, yCurrent, i), MAX_SIZE_Y, StellurgyBlocks.blockStructureTower));
            }

            if (world.getBlockState(new BlockPos(xMax + 1, yCurrent, i)).getBlock() == StellurgyBlocks.blockStructureTower) {
                maxTowerSize = Math.max(maxTowerSize, ZUtils.getContinuousBlockLength(world, EnumFacing.UP, new BlockPos(xMax + 1, yCurrent, i), MAX_SIZE_Y, StellurgyBlocks.blockStructureTower));
            }
        }

        //if tower does not meet criteria then reutrn null
        if (maxTowerSize < MIN_SIZE_Y || xSize < MIN_SIZE || zSize < MIN_SIZE) {
            return null;
        }

        return new AxisAlignedBB(new BlockPos(xMin, yCurrent + 1, zMin), new BlockPos(xMax, yCurrent + maxTowerSize - 1, zMax));
    }

    protected boolean verifyScan(AxisAlignedBB bb, World world) {
        boolean whole = true;

        boundLoop:
        for (int xx = (int) bb.minX; xx <= (int) bb.maxX; xx++) {
            for (int zz = (int) bb.minZ; zz <= (int) bb.maxZ; zz++) {
                Block blockAtSpot = world.getBlockState(new BlockPos(xx, (int) bb.minY - 1, zz)).getBlock();
                boolean contained = false;
                for (Block b : viableBlocks) {
                    if (blockAtSpot == b) {
                        contained = true;
                        break;
                    }
                }

                if (!contained) {
                    whole = false;
                    break boundLoop;
                }
            }
        }

        return whole;
    }

    public int getVolume(World world, AxisAlignedBB bb) {
        return (int) ((bb.maxX - bb.minX) * (bb.maxY - bb.minY) * (bb.maxZ - bb.minZ));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        stats.writeToNBT(nbt);
        nbt.setInteger("scanTime", progress);
        nbt.setInteger("scanTotalBlocks", totalProgress);
        nbt.setBoolean("building", building);
        nbt.setInteger("status", status.ordinal());

        if (bbCache != null) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setDouble("minX", bbCache.minX);
            tag.setDouble("minY", bbCache.minY);
            tag.setDouble("minZ", bbCache.minZ);
            tag.setDouble("maxX", bbCache.maxX);
            tag.setDouble("maxY", bbCache.maxY);
            tag.setDouble("maxZ", bbCache.maxZ);

            nbt.setTag("bb", tag);
        }


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
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        stats.readFromNBT(nbt);

        prevProgress = progress = nbt.getInteger("scanTime");
        totalProgress = nbt.getInteger("scanTotalBlocks");
        // A save predating status persistence has no "status" key; getInteger
        // would default to 0 = SUCCESS, so fall back to the neutral idle verdict
        // instead of loading a spurious success.
        status = nbt.hasKey("status")
                ? errorCodeFromOrdinal(nbt.getInteger("status"))
                : ErrorCodes.UNSCANNED;

        building = nbt.getBoolean("building");
        if (nbt.hasKey("bb")) {

            NBTTagCompound tag = nbt.getCompoundTag("bb");
            bbCache = new AxisAlignedBB(tag.getDouble("minX"),
                    tag.getDouble("minY"), tag.getDouble("minZ"),
                    tag.getDouble("maxX"), tag.getDouble("maxY"), tag.getDouble("maxZ"));

        }

        blockPos.clear();
        if (nbt.hasKey("infrastructureLocations")) {
            int[] array = nbt.getIntArray("infrastructureLocations");

            for (int counter = 0; counter < array.length; counter += 3) {
                blockPos.add(new HashedBlockPosition(array[counter], array[counter + 1], array[counter + 2]));
            }
        }
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        super.getUpdatePacket();
        NBTTagCompound nbt = new NBTTagCompound();

        writeToNBT(nbt);

        return new SPacketUpdateTileEntity(pos, 0, nbt);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        //Used to sync clinet/server
        if (id == 2) {
            out.writeInt(energy.getUniversalEnergyStored());
            out.writeInt(this.progress);
        } else if (id == 3) {
            out.writeInt(lastRocketID);
        }

    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte id,
                                    NBTTagCompound nbt) {

        if (id == 2) {
            nbt.setInteger("pwr", in.readInt());
            nbt.setInteger("tik", in.readInt());
        } else if (id == 3) {
            nbt.setInteger("id", in.readInt());
        }

    }

    public boolean canScan() {
        return bbCache != null;
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if ((id == 0 || id == 1) && player != null) {
            scanRequester = player.getUniqueID();
        }
        if (id == 0) {

            bbCache = getRocketPadBounds(world, pos);
            if (!canScan())
                return;

            totalProgress = (int) (StellurgyConfiguration.getCurrentConfig().buildSpeedMultiplier * this.getVolume(world, bbCache) / 10);
            this.markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        } else if (id == 1) {

            if (isScanning())
                return;

            building = true;

            bbCache = getRocketPadBounds(world, pos);
            if (!canScan())
                return;

            totalProgress = (int) (StellurgyConfiguration.getCurrentConfig().buildSpeedMultiplier * this.getVolume(world, bbCache) / 10);
            this.markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);

        } else if (id == 2) {
            energy.setEnergyStored(nbt.getInteger("pwr"));
            this.progress = nbt.getInteger("tik");
        } else if (id == 3) {
            EntityRocket rocket = (EntityRocket) world.getEntityByID(nbt.getInteger("id"));
            for (IInfrastructure infrastructure : getConnectedInfrastructure()) {
                rocket.linkInfrastructure(infrastructure);
            }
        }
    }

    protected void updateText() {
        if (thrustText == null || weightText == null || fuelText == null || accelerationText == null || errorText == null) {
            return;
        }
        thrustText.setText(isScanning() ? (LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.thrust") + ": ???") : String.format("%s: %.1fkN", LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.thrust"), getThrust() / 1000f));
        weightText.setText(isScanning() ? (LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.weight") + ": ???") : String.format("%s: %.1fkN", LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.weight"), stats.getWeightNewtons(getGravityMultiplier()) / 1000f));
        fuelText.setText(isScanning() ? (LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.fuel") + ": ???") : String.format("%s: %dmb/s", LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.fuel"), 20* getRocketStats().getFuelRate((stats.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT) > 0) ? FuelType.LIQUID_MONOPROPELLANT : (stats.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID) > 0) ? FuelType.NUCLEAR_WORKING_FLUID : FuelType.LIQUID_BIPROPELLANT)));
        accelerationText.setText(isScanning() ? (LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.acc") + ": ???") : String.format("%s: %.2fm/s\u00b2 (TWR %.2f)", LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.acc"), getAcceleration(getGravityMultiplier()) * 20f, getThrustToWeightRatio()));
        if (!world.isRemote) {
            if (getRocketPadBounds(world, pos) == null)
                setStatus(ErrorCodes.INCOMPLETESTRCUTURE.ordinal());
            else if (ErrorCodes.INCOMPLETESTRCUTURE.equals(getStatus()))
                setStatus(ErrorCodes.UNSCANNED.ordinal());
        }

        errorText.setText(getStatus().getErrorCode());
        if (tier2Readout != null && !isScanning()) {
            // A ship's figures are its flight model's, not a rocket's: thrust-to-weight from the
            // holdable upward authority of the actuators on the pad, in this world's field.
            dev.stannismod.stellurgy.ship.control.ShipReadout.View live =
                    dev.stannismod.stellurgy.ship.control.ShipReadout.View.LIVE;
            accelerationText.setText(LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.shiptwr")
                    + String.format(java.util.Locale.ROOT, " %.2f", tier2Readout.thrustToWeight(live)));
            if (!tier2Readout.canHover(live)) {
                errorText.setText(LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.lowtwr.gui"));
            }
        }
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {

        // Automatically set status to unscanned if no rocket is present when opening GUI
        if (!world.isRemote && status == ErrorCodes.ALREADY_ASSEMBLED) {
            AxisAlignedBB box = (bbCache != null) ? bbCache : getRocketPadBounds(world, pos);
            if (box == null || world.getEntitiesWithinAABB(EntityRocket.class, box).isEmpty()) {
                status = ErrorCodes.UNSCANNED;
                markDirty();
            }
        }


        List<ModuleBase> modules = new LinkedList<>();

        modules.add(new ModulePower(160, 90, this));

        if (world.isRemote)
            modules.add(new ModuleImage(4, 9, new IconResource(4, 9, 168, 74, backdrop)));

        modules.add(new ModuleProgress(89, 47, 0, horizontalProgressBar, this));
        modules.add(new ModuleProgress(89, 66, 1, horizontalProgressBar, this));
        modules.add(new ModuleProgress(89, 28, 3, horizontalProgressBar, this));
        modules.add(new ModuleProgress(89, 9, 4, horizontalProgressBar, this));

        modules.add(new ModuleProgress(149, 90, 2, verticalProgressBar, this));


        modules.add(new ModuleButton(5, 94, 0, LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.scan"), this, dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonScan));

        ModuleButton buttonBuild;
        modules.add(buttonBuild = new ModuleButton(5, 120, 1, LibVulpes.proxy.getLocalizedString("msg.rocketbuilder.build"), this, dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonBuild));
        buttonBuild.setColor(0xFFFF2222);

        modules.add(thrustText = new ModuleText(8, 15, "", 0xFF22FF22));
        modules.add(weightText = new ModuleText(8, 34, "", 0xFF22FF22));
        modules.add(fuelText = new ModuleText(8, 52, "", 0xFF22FF22));
        modules.add(accelerationText = new ModuleText(8, 71, "", 0xFF22FF22));
        modules.add(errorText = new ModuleText(5, 84, "", 0xFFFFFF22));

        updateText();

        for (int i = 0; i < 15; i++)
            modules.add(new ModuleSync(i, this));


        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return "";
    }

    @Override
    public float getNormallizedProgress(int id) {

        if (isScanning() && id != 2)
            return 0f;

        switch (id) {
            case 0:
                FuelType fuelType = (stats.getBaseFuelRate(FuelType.LIQUID_MONOPROPELLANT) > 0) ? FuelType.LIQUID_MONOPROPELLANT : (stats.getBaseFuelRate(FuelType.NUCLEAR_WORKING_FLUID) > 0) ? FuelType.NUCLEAR_WORKING_FLUID : FuelType.LIQUID_BIPROPELLANT;
                return (this.getAcceleration(getGravityMultiplier()) > 0) ? MathHelper.clamp(0.5f + 0.5f * ((float) (this.getFuel(fuelType) - this.stats.getFuelCapacity(fuelType)) / this.stats.getFuelCapacity(fuelType)), 0f, 1f) : 0;
            case 1:
                return MathHelper.clamp(0.5f + this.getAcceleration(getGravityMultiplier()) * 10, 0f, 1f);
            case 2:
                return (float) this.getNormallizedProgress();
            case 3:
                return this.getMass() > 0 ? 0.5f : 0f;
            case 4:
                return this.getThrust() > 0 ? 0.9f : 0f;
        }

        return 0f;
    }

    @Override
    public void setProgress(int id, int progress) {
        if (id == 2)
            setProgress(progress);
    }

    @Override
    public int getProgress(int id) {
        if (id == 2)
            return getProgress();
        return 0;
    }

    @Override
    public int getTotalProgress(int id) {
        if (id == 2)
            return getTotalProgress();
        return 0;
    }

    @Override
    public void setTotalProgress(int id, int progress) {
        if (id == 2) {
            setTotalProgress(progress);
            updateText();
        }
    }

    @Override
    public void setData(int id, int value) {
        switch (id) {
            case 0:
                getRocketStats().setMass(value);
                break;
            case 1:
                getRocketStats().setThrust(value);
                break;
            case 2:
                setStatus(value);
                break;


            case 3:
                getRocketStats().setBaseFuelRate(FuelType.LIQUID_MONOPROPELLANT, value);
                break;
            case 4:
                getRocketStats().setFuelAmount(FuelType.LIQUID_MONOPROPELLANT, value);
                break;
            case 5:
                getRocketStats().setFuelCapacity(FuelType.LIQUID_MONOPROPELLANT, value);
                break;
            case 6:
                getRocketStats().setFuelRate(FuelType.LIQUID_MONOPROPELLANT, value);
                break;

            case 7:
                getRocketStats().setFuelRate(FuelType.LIQUID_BIPROPELLANT, value);
                break;
            case 8:
                getRocketStats().setFuelAmount(FuelType.LIQUID_BIPROPELLANT, value);
                break;
            case 9:
                getRocketStats().setFuelRate(FuelType.LIQUID_BIPROPELLANT, value);
                break;
            case 10:
                getRocketStats().setFuelRate(FuelType.LIQUID_BIPROPELLANT, value);
                break;

            case 11:
                getRocketStats().setFuelRate(FuelType.NUCLEAR_WORKING_FLUID, value);
                break;
            case 12:
                getRocketStats().setFuelAmount(FuelType.NUCLEAR_WORKING_FLUID, value);
                break;
            case 13:
                getRocketStats().setFuelRate(FuelType.NUCLEAR_WORKING_FLUID, value);
                break;
            case 14:
                getRocketStats().setFuelRate(FuelType.NUCLEAR_WORKING_FLUID, value);
                break;


        }
        updateText();
    }

    @Override
    public int getData(int id) {
        switch (id) {

            case 0:
                return Math.round(getRocketStats().getDryMass());
            case 1:
                return getRocketStats().getThrust();
            case 2:
                return getStatus().ordinal();


            case 3:
                return getRocketStats().getBaseFuelRate(FuelType.LIQUID_MONOPROPELLANT);
            case 4:
                return getRocketStats().getFuelAmount(FuelType.LIQUID_MONOPROPELLANT);
            case 5:
                return getRocketStats().getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT);
            case 6:
                return getRocketStats().getFuelRate(FuelType.LIQUID_MONOPROPELLANT);

            case 7:
                return getRocketStats().getBaseFuelRate(FuelType.LIQUID_BIPROPELLANT);
            case 8:
                return getRocketStats().getFuelAmount(FuelType.LIQUID_BIPROPELLANT);
            case 9:
                return getRocketStats().getFuelCapacity(FuelType.LIQUID_BIPROPELLANT);
            case 10:
                return getRocketStats().getFuelRate(FuelType.LIQUID_BIPROPELLANT);

            case 11:
                return getRocketStats().getBaseFuelRate(FuelType.NUCLEAR_WORKING_FLUID);
            case 12:
                return getRocketStats().getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID);
            case 13:
                return getRocketStats().getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID);
            case 14:
                return getRocketStats().getFuelRate(FuelType.NUCLEAR_WORKING_FLUID);


        }
        return 0;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        PacketHandler.sendToServer(new PacketMachine(this, (byte) (buttonId)));
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public boolean canConnectEnergy(EnumFacing arg0) {
        return true;
    }

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity,
                               EntityPlayer player, World world) {
        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity,
                                  EntityPlayer player, World world) {
        TileEntity tile = world.getTileEntity(ItemLinker.getMasterCoords(item));
        float maxlinkDistance = 15;

        if (tile instanceof IInfrastructure) {
            HashedBlockPosition pos = new HashedBlockPosition(tile.getPos());

            if (pos.getDistance(new HashedBlockPosition(this.pos)) > maxlinkDistance) {
                if (!world.isRemote)
                    player.sendMessage(new TextComponentTranslation("the machine is too far away to be linked"));
                return false;
            }

            if (!blockPos.contains(pos))
                blockPos.add(pos);

            if (getBBCache() == null) {
                bbCache = getRocketPadBounds(world, getPos());
            }

            if (getBBCache() != null) {

                List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, bbCache);
                for (EntityRocketBase rocket : rockets) {
                    rocket.linkInfrastructure((IInfrastructure) tile);
                }
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

    public void removeConnectedInfrastructure(TileEntity tile) {
        blockPos.remove(new HashedBlockPosition(tile.getPos()));

        if (getBBCache() == null) {
            bbCache = getRocketPadBounds(world, this.getPos());
        }

        if (getBBCache() != null) {
            List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, bbCache);

            for (EntityRocketBase rocket : rockets) {
                rocket.unlinkInfrastructure((IInfrastructure) tile);
            }
        }

    }

    public List<IInfrastructure> getConnectedInfrastructure() {
        List<IInfrastructure> list = new LinkedList<>();
        for (HashedBlockPosition position : blockPos) {
            TileEntity te = world.getTileEntity(position.getBlockPos());
            if (te instanceof IInfrastructure) {
                list.add((IInfrastructure) te);
            }
        }
        return list;
    }

    @SubscribeEvent
    public void onRocketLand(RocketLandedEvent e) {
        // Server/world guard
        if (e.world.isRemote || e.world != this.world) return;

        // Ensure we have pad bounds
        bbCache = getRocketPadBounds(world, pos);
        if (bbCache == null) return;

        // Make sure the event entity is a rocket
        final net.minecraft.entity.Entity ent = e.getEntity();
        if (!(ent instanceof EntityRocketBase)) return;
        final EntityRocketBase landed = (EntityRocketBase) ent;

        // Quick membership test with tiny epsilon
        final AxisAlignedBB box = bbCache.grow(1.0E-4, 1.0E-4, 1.0E-4);
        if (!landed.getEntityBoundingBox().intersects(box)) return;

        // Track rocket id and (re)link infra
        lastRocketID = landed.getEntityId();
        for (IInfrastructure infra : getConnectedInfrastructure()) {
            if (infra instanceof dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) {
                ((dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) infra)
                        .markRocketFromAssembler(landed);
            }
            landed.linkInfrastructure(infra);
        }


        // only fast-path when exactly one rocket in the pad
        List<EntityRocket> rockets = world.getEntitiesWithinAABB(EntityRocket.class, box);
        if (rockets.size() == 1) {
            EntityRocket r = rockets.get(0);
            r.recalculateStats();
            this.stats = r.stats.copy();
            this.status = ErrorCodes.ALREADY_ASSEMBLED;
            markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        } else {
            // Fallback: rescan if something odd happens
            scanRocket(world, pos, bbCache);
        }
        PacketHandler.sendToPlayersTrackingEntity(new PacketMachine(this, (byte)3), landed);
    }


    protected enum ErrorCodes {
        SUCCESS("msg.rocketbuilder.success"),
        NOFUEL("msg.rocketbuilder.nofuel"),
        NOSEAT("msg.rocketbuilder.noseat"),
        NOENGINES("msg.rocketbuilder.noengines"),
        NOGUIDANCE("msg.rocketbuilder.noguidance"),
        UNSCANNED("msg.rocketbuilder.unscanned"),
        SUCCESS_STATION("msg.rocketbuilder.success_station"),
        EMPTY("msg.rocketbuilder.empty"),
        FINISHED("msg.rocketbuilder.finished"),
        INCOMPLETESTRCUTURE("msg.rocketbuilder.incompletestructure"),
        NOSATELLITEHATCH("msg.rocketbuilder.nosatellitehatch"),
        NOSATELLITECHIP("msg.rocketbuilder.nosatellitechip"),
        OUTPUTBLOCKED("msg.rocketbuilder.outputblocked"),
        INVALIDBLOCK("msg.rocketbuild.invalidblock"),
        COMBINEDTHRUST("msg.rocketbuild.combinedthrust"),
        ALREADY_ASSEMBLED("msg.rocketbuilder.alreadyassembled"),
        UNSCANNED_STATION("msg.rocketbuilder.unscanned_station"),
        FAIL_CUT("msg.rocketbuilder.fail_cut"),
        NOINTAKE("msg.rocketbuilder.nointake"),
        NOTANK("msg.rocketbuilder.notank"),
        MULTIPLEFLIGHTCOMPUTERS("msg.rocketbuilder.multipleflightcomputers"),
        MULTIPLEPILOTSEATS("msg.rocketbuilder.multiplepilotseats"),
        // Appended, never inserted: the status travels and saves as its ordinal.
        SHIP_TOO_LARGE("msg.rocketbuilder.shiptoolarge");

        /** Effectively final, process lifetime: set once when the object is built. */
        private final String translationKey;

        ErrorCodes(String translationKey) {
            this.translationKey = translationKey;
        }

        /** Translated at every call, so a language change shows on the next status line. */
        public String getErrorCode() {
            return LibVulpes.proxy.getLocalizedString(translationKey);
        }
    }

    @Override
    public void update() {
        super.update(); 
        if (world.isRemote) return;

        if (relinkRetries > 0 && world.getTotalWorldTime() >= nextRelinkAttempt) {
            if (tryRelinkNow()) {
                relinkRetries = 0;
            } else {
                relinkRetries--;
                nextRelinkAttempt = world.getTotalWorldTime() + 20; // 1s
            }
        }
    }

    private boolean tryRelinkNow() {
        if (bbCache == null) bbCache = getRocketPadBounds(world, pos);
        if (bbCache == null) return false;

        AxisAlignedBB box = bbCache.grow(1.0e-4,1.0e-4,1.0e-4);
        java.util.List<EntityRocketBase> rockets = world.getEntitiesWithinAABB(EntityRocketBase.class, box);
        if (rockets.isEmpty()) return false;

        java.util.List<IInfrastructure> infraNow = getConnectedInfrastructure();
        if (infraNow.isEmpty()) return false;

        for (EntityRocketBase r : rockets) {
            for (IInfrastructure i : infraNow) {
                if (i instanceof dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) {
                    ((dev.stannismod.stellurgy.tile.infrastructure.TileRocketMonitoringStation) i)
                            .markRocketFromAssembler(r);
                }
                r.linkInfrastructure(i);
            }
        }
        return true;
    }
}
