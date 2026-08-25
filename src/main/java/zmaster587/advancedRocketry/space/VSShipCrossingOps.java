package zmaster587.advancedRocketry.space;

import java.util.List;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production {@link ShipCrossingService.Ops}: carries a crossing state machine's decisions out
 * against live worlds — the proven per-ship crossing, the rider-carrying rigid pose teleport, and
 * the crew re-seat. Shared by the entry on-ramp ({@link ShipEntryController}) and the planet
 * descent ({@link DescentController}); mirrors {@link VSShipCrosser}'s role for the transit state
 * machine. Safe no-ops (nulls/false) when VS is absent or a world is missing, so a crossing aborts
 * cleanly.
 */
public final class VSShipCrossingOps implements ShipCrossingService.Ops {

    /** Rider-carry box half-width around a ship pose — the proven probe recipe's range. */
    private static final double RIDER_RANGE = 8.0;

    @Override
    public double[] shipWorldPosition(int dimId, BlockPos afcPos) {
        WorldServer world = DimensionManager.getWorld(dimId);
        return world == null ? null : VSIntegration.getShipWorldPosition(world, afcPos);
    }

    /**
     * What each crossing took off its ship that was NOT crew, keyed by the ship's durable id and
     * waiting for {@link #reseat} to put it back. Empty except between a capture and its arrival.
     *
     * <p>Keyed by the SHIP and not by the destination: a crossing is identified by the craft making
     * it, and two craft can be crossing into the same slot world at once.</p>
     */
    private final java.util.Map<java.util.UUID, List<AboardBodies.Stowed>> bodyStash =
            new java.util.HashMap<>();

    /**
     * Ships whose crew is already back in its seats but whose arrival is not finished — it is still
     * waiting on the bodies. Dropped the moment both halves are down.
     *
     * <p>Both this and {@link #bodyStash} are emptied by a completed arrival and by nothing else, so
     * a crossing that exhausts the settle loop's attempts leaves an entry behind. That is the same
     * bound the transit path's own stashes carry, it costs a handful of bytes per abandoned crossing,
     * and the alternative — clearing on the give-up path — would need this object to be told about a
     * failure it is not currently part of.</p>
     */
    private final java.util.Set<java.util.UUID> crewAlreadySeated = new java.util.HashSet<>();

    @Override
    public List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos, double[] shipWorldPos,
                                               java.util.UUID shipId) {
        WorldServer world = DimensionManager.getWorld(dimId);
        if (world == null) {
            return new java.util.ArrayList<CrewTransfer.Crew>();
        }
        // Everything that is NOT crew comes out first, and ahead of the crew capture for the same
        // reason the transit path does it in that order: these need no negotiation with a client, so
        // they are stowed rather than held, and a crewless ship must still take its cargo with it.
        //
        // This is the call that was missing. `AboardBodies` was driven from ONE place — the transit
        // path's own crosser — so a JUMP carried the mob on the deck while an entry, a descent and a
        // cell-seam carry silently left it behind, in a world the ship had just been cut out of.
        if (shipId != null) {
            List<AboardBodies.Stowed> bodies = AboardBodies.capture(world, afcPos);
            if (!bodies.isEmpty()) {
                bodyStash.put(shipId, bodies);
            }
        }
        return CrewTransfer.capture(world, afcPos, shipWorldPos);
    }

    @Override
    public List<CrewTransfer.Crew> peekCrew(int dimId, BlockPos afcPos, double[] shipWorldPos) {
        WorldServer world = DimensionManager.getWorld(dimId);
        return world == null
                ? new java.util.ArrayList<CrewTransfer.Crew>()
                : CrewTransfer.peek(world, afcPos, shipWorldPos);
    }

    @Override
    public void latchEntryUntilBelowTheLine(int dimId, BlockPos afcPos) {
        WorldServer world = DimensionManager.getWorld(dimId);
        if (world == null || afcPos == null) {
            return;
        }
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(afcPos);
        if (te instanceof zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) {
            ((zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) te)
                    .latchEntryUntilBelowTheLine();
        }
    }

    @Override
    public ShipCrossingService.Crossed cross(int srcDimId, double[] srcShipPos, int destDim,
                                             int pasteX, int pasteY, int pasteZ) {
        WorldServer src = DimensionManager.getWorld(srcDimId);
        WorldServer dst = DimensionManager.getWorld(destDim);
        if (src == null || dst == null || srcShipPos == null) {
            return null;
        }
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                src, srcShipPos[0], srcShipPos[1], srcShipPos[2], dst, pasteX, pasteY, pasteZ);
        return res.ok() ? new ShipCrossingService.Crossed(res.anchor, res.shipUuid) : null;
    }

    @Override
    public void pinDim(int dimId) {
        // LOAD it, then hold it. The flag alone pins nothing: Forge's keepDimensionLoaded only stops
        // a LOADED world being queued for unload, and a planet with nobody on it is unloaded within
        // seconds of the last player leaving. A destination that is already down therefore stayed
        // down, and every read of it afterwards - the descent's own arrival resolve above all -
        // answered "no such world" forever, because nothing on that path ever asked for it.
        // Init only when it is actually absent: initDimension on a live dimension reloads it.
        if (DimensionManager.getWorld(dimId) == null) {
            DimensionManager.initDimension(dimId);
        }
        DimensionManager.keepDimensionLoaded(dimId, true);
    }

    @Override
    public boolean reseat(int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew,
            java.util.UUID shipId, java.util.UUID vsShipUuid) {
        WorldServer world = DimensionManager.getWorld(destDim);
        if (world == null) {
            return false; // target world not up yet — retry next tick
        }
        // BOTH, reported as one verdict, on the same retry loop: the crossing is not finished while a
        // mob that was standing on the deck is still in a map on this side.
        //
        // THE CREW IS ATTEMPTED EVERY TICK REGARDLESS, and its success is remembered rather than
        // re-performed. This is the transit path's shape and it is copied on purpose: the two halves
        // can succeed on different ticks, and without the memo a body that lands late would drive a
        // second `CrewTransfer.reseat` over crew that is already sitting down. Ordering the crew
        // behind the bodies would avoid that too, but it would also let a body that cannot be placed
        // keep a pilot standing in a world his ship has left — a far worse trade than a lost item.
        boolean bodiesPlaced = releaseStowed(world, anchor, shipId);
        boolean crewSeated = shipId != null && crewAlreadySeated.contains(shipId);
        if (!crewSeated && CrewTransfer.reseat(world, anchor, crew, shipId, vsShipUuid)) {
            crewSeated = true;
            if (shipId != null) {
                crewAlreadySeated.add(shipId);
            }
        }
        if (bodiesPlaced && crewSeated) {
            crewAlreadySeated.remove(shipId);
            return true;
        }
        return false;
    }

    /**
     * Put the bodies stowed for {@code shipId} back on the ship that arrived at {@code anchor}, and
     * drop the stash once they are down.
     *
     * <p>{@code true} with nothing stowed — a ship that carried no loose body has nothing to wait
     * for. {@code false} means the ship is not rebuilt here yet, which is the same "come back next
     * tick" the crew placement answers with.</p>
     */
    private boolean releaseStowed(WorldServer world, BlockPos anchor, java.util.UUID shipId) {
        List<AboardBodies.Stowed> bodies = shipId == null ? null : bodyStash.get(shipId);
        if (bodies == null || bodies.isEmpty()) {
            return true;
        }
        BlockPos afcPos = VSIntegration.flightComputerAt(world, anchor.getX() + 0.5,
                anchor.getY() + 0.5, anchor.getZ() + 0.5);
        if (afcPos == null || AboardBodies.release(world, afcPos, bodies) == 0) {
            return false;
        }
        bodyStash.remove(shipId);
        return true;
    }

    @Override
    public boolean teleportPoseWithRiders(int destDim, BlockPos anchor, java.util.UUID vsShipUuid,
                                          double px, double py, double pz) {
        WorldServer world = DimensionManager.getWorld(destDim);
        if (world == null) {
            return false;
        }
        // Readiness gate. The thing that must be true before the pose is written is that the physics
        // mod has finished relocating the blocks we pasted into the ship's subspace shipyard —
        // writing the transform mid-relocation re-maps the remaining blocks against the moved pose.
        // That is a statement about THIS crossing's progress, and it is readable at the one point we
        // own: the anchor we pasted on and seeded the assembly with. The assembly deletes every block
        // it claims from this world (the anchor is always in its found set, it is the seed), so the
        // anchor going to air is exactly "my ship has been claimed" — an exact test on one position,
        // not a nearest-ship lookup, and independent of whether the ship happens to be loaded.
        //
        // It deliberately does NOT ask whether a ship is loaded. Loadedness is re-decided every tick
        // from player proximity: with nobody aboard and nobody nearby, an unmanned arrival is exactly
        // the case such a gate can never satisfy on its own, and it only ever passed because the
        // settle force-loaded the ship itself — a coin flip against the unload the physics mod queues
        // on the same tick, not a readiness check.
        if (!world.isAirBlock(anchor)) {
            return false;
        }
        double sx = anchor.getX() + 0.5, sy = anchor.getY() + 0.5, sz = anchor.getZ() + 0.5;
        // Capture riders at the CURRENT pose before the write, then carry them by the same delta
        // (the proven teleport-ship recipe; a carried dummy's seated player follows as passenger).
        List<EntityDummy> riders = world.getEntitiesWithinAABB(EntityDummy.class,
                new AxisAlignedBB(sx, sy, sz, sx, sy, sz).grow(RIDER_RANGE));
        // BY IDENTITY when the crossing minted one. The position form moves whatever ship is nearest
        // to the anchor, and a destination that already holds another craft can hand back that
        // craft — which then sits at OUR arrival pose while our ship stays where it was assembled,
        // and every later lookup at the pose answers for the stranger.
        boolean moved = vsShipUuid != null
                ? VSIntegration.teleportShipToByUuid(world, vsShipUuid, px, py, pz)
                : VSIntegration.teleportShipTo(world, sx, sy, sz, px, py, pz);
        if (!moved) {
            return false;
        }
        for (EntityDummy d : riders) {
            d.setPositionAndUpdate(d.posX + (px - sx), d.posY + (py - sy), d.posZ + (pz - sz));
            // Safety net: a mount must never leave a seated player behind (a rider split from his
            // mount by more than tracking range is unrecoverable client-side). The settle re-seats
            // AFTER this teleport so no crew normally rides through it, but probe mounts and any
            // future caller with a live rider are carried by the same delta.
            for (net.minecraft.entity.Entity p : d.getPassengers()) {
                if (p instanceof net.minecraft.entity.player.EntityPlayerMP) {
                    p.setPositionAndUpdate(p.posX + (px - sx), p.posY + (py - sy), p.posZ + (pz - sz));
                }
            }
        }
        return true;
    }

    @Override
    public String settleDiagnostics() {
        return CrewTransfer.lastReseatBlock();
    }

    @Override
    public void unpark(int destDim, java.util.UUID vsShipUuid, double px, double py, double pz) {
        WorldServer world = DimensionManager.getWorld(destDim);
        if (world == null) {
            return;
        }
        // Identity first, for the same reason the pose teleport uses it: giving physics back to
        // whatever ship is nearest to the arrival point can un-park a stranger and leave the ship
        // that actually crossed frozen.
        if (vsShipUuid == null || !VSIntegration.unparkShip(world, vsShipUuid)) {
            VSIntegration.unparkShipAt(world, px, py, pz);
        }
    }

    @Override
    public void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args) {
        for (CrewTransfer.Crew rider : crew) {
            if (!rider.player.hasDisconnected()) {
                rider.player.sendMessage(new TextComponentTranslation(langKey, args));
            }
        }
    }
}
