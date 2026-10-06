package dev.stannismod.stellurgy.util;

import net.minecraft.block.BlockTorch;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.AreaBlob;
import dev.stannismod.stellurgy.api.util.IBlobHandler;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.network.PacketAirParticle;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AtmosphereBlob extends AreaBlob implements Runnable {

    /**
     * The executor the threaded blob fill runs on: one per server ({@code ServerState}), shut down
     * when it stops, so a fill queued at stop never runs against the next world. Its threads are
     * created on first use, so a server that keeps the fill on its own thread pays nothing.
     */
    public static ThreadPoolExecutor newFillPool() {
        return new ThreadPoolExecutor(2, 16, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32));
    }

    /** A flood fill is in flight. Set on the server thread, cleared by the fill pool's thread. */
    private volatile boolean executing;
    /**
     * What the last finished fill found: closed, open, or {@code null} when there is no answer —
     * none has run since the zone was last cleared from outside, or one is still running. Written
     * before {@link #executing} is cleared, so a reader that sees the fill done sees its answer.
     */
    @Nullable
    private volatile Boolean lastFillClosed;
    private HashedBlockPosition blockPos;
    private List<AreaBlob> nearbyBlobs;
    /** The gases filling this zone. Starts sea-level breathable, which is the pressure this
     *  method reported as a constant before zones had contents. */
    private AirState airState = AirState.earthLike();

    public AtmosphereBlob(@Nonnull IBlobHandler blobHandler) {
        super(blobHandler);
        executing = false;
    }

    @Nonnull
    public AirState getAirState() {
        return airState;
    }

    /**
     * Whether a flood fill started for this zone has not finished. While it runs, an empty zone means
     * "not measured yet", not "open".
     */
    public boolean isFilling() {
        return executing;
    }

    /** What the last finished fill found, or {@code null} while one runs or since the zone was cleared. */
    @Nullable
    public Boolean lastFillClosed() {
        return executing ? null : lastFillClosed;
    }

    public void setAirState(@Nonnull AirState airState) {
        this.airState = airState;
    }

    public int getPressure() {
        return airState.getPressureCentiAtm();
    }

    /**
     * Called when a block can no longer be filled with air
     */
    @Override
    public void removeBlock(@Nonnull HashedBlockPosition blockPos) {

        synchronized (graph) {
            graph.remove(blockPos);

            for (EnumFacing direction : EnumFacing.values()) {

                HashedBlockPosition newBlock = blockPos.getPositionAtOffset(direction);
                if (graph.contains(newBlock) && !graph.doesPathExist(newBlock, blobHandler.getRootPosition()))
                    runEffectOnWorldBlocks(blobHandler.getWorldObj(), graph.removeAllNodesConnectedTo(newBlock));
            }
        }
    }

    @Override
    public boolean isPositionAllowed(@Nonnull World world, @Nonnull HashedBlockPosition pos, List<AreaBlob> otherBlobs) {
        for (AreaBlob blob : otherBlobs) {
            if (blob.contains(pos) && blob != this)
                return false;
        }

        return !SealableBlockHandler.INSTANCE.isBlockSealed(world, pos.getBlockPos());
    }

    @Override
    public void addBlock(@Nonnull HashedBlockPosition blockPos, List<AreaBlob> nearbyBlobs) {

        if (blobHandler.canFormBlob()) {

            if (!this.contains(blockPos) &&
                    (this.graph.size() == 0 || this.contains(blockPos.getPositionAtOffset(EnumFacing.UP)) || this.contains(blockPos.getPositionAtOffset(EnumFacing.DOWN)) ||
                            this.contains(blockPos.getPositionAtOffset(EnumFacing.EAST)) || this.contains(blockPos.getPositionAtOffset(EnumFacing.WEST)) ||
                            this.contains(blockPos.getPositionAtOffset(EnumFacing.NORTH)) || this.contains(blockPos.getPositionAtOffset(EnumFacing.SOUTH)))) {
                if (!executing) {
                    this.nearbyBlobs = nearbyBlobs;
                    this.blockPos = blockPos;
                    lastFillClosed = null;
                    executing = true;
                    if ((StellurgyConfiguration.getCurrentConfig().atmosphereHandleBitMask & 1) == 1)
                        try {
                            Stellurgy.serverState().atmosphereFillPool.execute(this);
                        } catch (RejectedExecutionException e) {
                            Stellurgy.logger.warn("Atmosphere calculation at " + this.getRootPosition() + " aborted due to oversize queue!");
                        }
                    else
                        this.run();
                }
            }
        }
    }


    @Override
    public void run() {

        //Nearby Blobs


        Stack<HashedBlockPosition> stack = new Stack<>();
        stack.push(blockPos);

        final int maxSize = (StellurgyConfiguration.getCurrentConfig().atmosphereHandleBitMask & 2) != 0 ? (int) (Math.pow(this.getBlobMaxRadius(), 3) * ((4f / 3f) * Math.PI)) : this.getBlobMaxRadius();
        final HashSet<HashedBlockPosition> addableBlocks = new HashSet<>();

        //Breadth first search; non recursive
        while (!stack.isEmpty()) {
            HashedBlockPosition stackElement = stack.pop();
            addableBlocks.add(stackElement);

            for (EnumFacing dir2 : EnumFacing.values()) {
                HashedBlockPosition searchNextPosition = stackElement.getPositionAtOffset(dir2);

                //Don't path areas we have already scanned
                if (!graph.contains(searchNextPosition) && !addableBlocks.contains(searchNextPosition)) {

                    boolean sealed;

                    try {

                        sealed = !isPositionAllowed(blobHandler.getWorldObj(), searchNextPosition, nearbyBlobs);//SealableBlockHandler.INSTANCE.isBlockSealed(blobHandler.getWorldObj(), searchNextPosition.getBlockPos());

                        if (blobHandler.getTraceDistance() > 0 && blobHandler.getWorldObj().getTotalWorldTime() % 20 == 0) {
                            if ((int) searchNextPosition.getDistance(this.getRootPosition()) == blobHandler.getTraceDistance()) {
                                PacketHandler.sendToNearby(new PacketAirParticle(searchNextPosition), blobHandler.getWorldObj().provider.getDimension(), blobHandler.getRootPosition().getBlockPos(), 128);
                            }

                        }


                        if (!sealed) {
                            if (((StellurgyConfiguration.getCurrentConfig().atmosphereHandleBitMask & 2) == 0 && searchNextPosition.getDistance(this.getRootPosition()) <= maxSize) ||
                                    ((StellurgyConfiguration.getCurrentConfig().atmosphereHandleBitMask & 2) != 0 && addableBlocks.size() <= maxSize)) {
                                stack.push(searchNextPosition);
                                addableBlocks.add(searchNextPosition);
                            } else {
                                //Failed to seal, void
                                clearBlob();
                                lastFillClosed = Boolean.FALSE;
                                executing = false;
                                return;
                            }
                        }
                    } catch (Exception e) {
                        //Catches errors with additional information
                        Stellurgy.logger.info("Error: AtmosphereBlob has failed to form correctly due to an error. \nCurrentBlock: " + stackElement + "\tNextPos: " + searchNextPosition + "\tDir: " + dir2 + "\tStackSize: " + stack.size());
                        e.printStackTrace();
                        //Failed to seal, void
                        clearBlob();
                        lastFillClosed = Boolean.FALSE;
                        executing = false;
                        return;
                    }
                }
            }
        }

        //only one instance can editing this at a time because this will not run again b/c "worker" is not null
        synchronized (graph) {
            for (HashedBlockPosition blockPos2 : addableBlocks) {
                super.addBlock(blockPos2, nearbyBlobs);
            }
        }

        lastFillClosed = Boolean.TRUE;
        executing = false;
    }


    /**
     * @param world
     * @param blocks Collection containing affected locations
     */
    protected void runEffectOnWorldBlocks(@Nonnull World world, @Nonnull Collection<HashedBlockPosition> blocks) {
        AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(world);

        if (atmhandler != null && !atmhandler.getDefaultAtmosphereType().allowsCombustion()) {

            List<HashedBlockPosition> list;

            synchronized (graph) {
                list = new LinkedList<>(blocks);
            }


            for (HashedBlockPosition pos : list) {
                IBlockState state = world.getBlockState(pos.getBlockPos());
                if (state.getBlock() == Blocks.TORCH) {
                    world.setBlockState(pos.getBlockPos(), StellurgyBlocks.blockUnlitTorch.getDefaultState().withProperty(BlockTorch.FACING, state.getValue(BlockTorch.FACING)));
                } else if (StellurgyConfiguration.getCurrentConfig().torchBlocks.contains(state.getBlock())) {
                    EntityItem item = new EntityItem(world, pos.x, pos.y, pos.z, new ItemStack(state.getBlock()));
                    world.setBlockToAir(pos.getBlockPos());
                    world.spawnEntity(item);
                }
            }
        }
    }

    @Override
    public void clearBlob() {
        World world = blobHandler.getWorldObj();

        runEffectOnWorldBlocks(world, getLocations());

        // Whatever an earlier fill found is about a zone that is no longer there.
        lastFillClosed = null;
        super.clearBlob();
    }
}
