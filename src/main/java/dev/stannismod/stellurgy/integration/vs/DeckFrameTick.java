package dev.stannismod.stellurgy.integration.vs;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Updates an entity a deck holds with its own, unmodified code, run in the deck's frame.
 *
 * <p>An entity moves itself in two halves: kinematics on world axes - gravity along {@code -Y}, drag
 * per axis, its input basis - then one {@code move} that collides it. On a tilted deck all of it is
 * wrong, and the kinematics differ per class. But the craft's blocks also stand, axis-aligned, in its
 * subspace. So for the length of its update the entity is placed THERE: its position and motion are
 * expressed in the deck's frame, the update runs as written - gravity is deck-down, drag lands on the
 * deck's axes, {@code move} collides against the deck's real blocks - and the result is mapped back
 * to the world, where everything else sees the entity.</p>
 *
 * <p>Velocity crosses the boundary relative to the deck: the carry added when the entity is mapped
 * out is subtracted exactly when it is mapped in next tick, so an impulse the world gave it between
 * the two survives and the craft's own acceleration does not leak into it.</p>
 *
 * <p>Admits {@link EntityItem} only, for now.</p>
 */
public final class DeckFrameTick {

    private DeckFrameTick() {}

    /** One tick, in seconds: turns the craft's velocity into a per-tick carry. */
    private static final double TICK_SECONDS = 0.05;

    /** How far below the feet, in the deck's frame, a floor must be for the deck to take the entity. */
    private static final double SUPPORT_PROBE = 0.30;

    /** A floor's top may sit this far above the mapped feet and still be stood on rather than inside. */
    private static final double STANDING_TOLERANCE = 0.05;

    /** Margin around the craft's own block region, in subspace, inside which a held entity stays held. */
    private static final double STAY_REGION_MARGIN = 4.0;

    /**
     * A deck's hold on one entity: which craft, the deck point its last update left it at, and the
     * carry last added to its world motion.
     */
    public static final class Episode {
        final String shipId;
        /** Whether {@code local*} has been written by an update yet. */
        boolean held;
        double localX, localY, localZ;
        /** True while this class, or the entity's own update in the deck frame, writes its position. */
        boolean writing;
        /** Somebody else wrote the entity's position since this class last did: adopt it. */
        boolean worldWritten;
        double carryX, carryY, carryZ;

        Episode(String shipId) {
            this.shipId = shipId;
        }

        /** The craft whose deck holds the entity. */
        public String shipId() {
            return shipId;
        }
    }

    /**
     * Put every entity a deck holds in {@code world} back on its deck point, through the pose its
     * craft holds NOW.
     *
     * <p>An entity's update maps its deck point to the world through the pose of the moment, and the
     * substrate advances the poses after the entities have moved. Without this the next update would
     * re-derive the deck point from a world position laid down against the previous pose, and every
     * step the craft takes would become a step of the entity along the deck.</p>
     *
     * <p>A rider is left to its vehicle.</p>
     */
    public static void followShipPoses(World world) {
        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof DeckHeld) || entity.isDead || entity.isRiding()) {
                continue;
            }
            Episode episode = ((DeckHeld) entity).stellurgy$deckEpisode();
            if (episode == null) {
                continue;
            }
            double[] pos = VSIntegration.toWorldFrameFor(world, episode.shipId,
                    episode.localX, episode.localY, episode.localZ);
            if (pos != null && !episode.worldWritten) {
                episode.writing = true;
                try {
                    entity.setPosition(pos[0], pos[1], pos[2]);
                } finally {
                    episode.writing = false;
                }
            }
        }
    }

    /**
     * Update {@code entity} in its deck's frame if a deck holds it or takes it now.
     *
     * @return {@code true} when the entity was updated here and the caller must not update it again
     */
    public static boolean update(Entity entity) {
        World world = entity.world;
        if (world == null || !(entity instanceof DeckHeld)) {
            return false;
        }
        DeckHeld slot = (DeckHeld) entity;
        Episode episode = slot.stellurgy$deckEpisode();
        if (episode == null) {
            String shipId = admissionFor(entity);
            if (shipId == null) {
                return false;
            }
            trace(entity, "admit ship=" + shipId);
            episode = new Episode(shipId);
            // A body arriving from the world moves with its real world velocity, of which the
            // craft's own motion at that point is the part the deck already accounts for.
            double[] v = VSIntegration.shipVelocityAtPointFor(world, shipId,
                    entity.posX, entity.posY, entity.posZ);
            if (v != null) {
                episode.carryX = v[0] * TICK_SECONDS;
                episode.carryY = v[1] * TICK_SECONDS;
                episode.carryZ = v[2] * TICK_SECONDS;
            }
            slot.stellurgy$setDeckEpisode(episode);
        }
        String shipId = episode.shipId;

        // WHERE ON THE DECK the entity is. The deck point this class last left it at, unless somebody
        // else has written its position since, in which case that write is where it is (and is
        // expressed in the world, so it is mapped in).
        //
        // Never re-derived from the world position as a matter of course. Measured 2026-09-29: an
        // item resting on a hovering craft slewing to 150 deg of roll moved 0.53 blocks along the deck
        // in 200 ticks when its deck point was re-derived here every tick, and 7e-9 when the held
        // point was used - the craft's pose moves between the end-of-tick re-image and this read,
        // and a re-derivation turns every such step into the entity moving.
        boolean mappedIn = !episode.held || episode.worldWritten;
        double[] local = mappedIn
                ? VSIntegration.toShipFrameFor(world, shipId, entity.posX, entity.posY, entity.posZ)
                : new double[]{episode.localX, episode.localY, episode.localZ};
        episode.worldWritten = false;
        if (mappedIn && local != null) {
            clearOfMappingNoise(entity, local);
        }
        AxisAlignedBB stay = VSIntegration.subspaceStayRegion(world, shipId, STAY_REGION_MARGIN);
        if (local == null || stay == null || !stay.contains(new Vec3d(local[0], local[1], local[2]))) {
            slot.stellurgy$setDeckEpisode(null);
            trace(entity, "release " + (local == null || stay == null ? "shipUnloaded" : "leftShipRegion"));
            return false;
        }
        double[] motion = VSIntegration.rotateToShipFrameFor(world, shipId,
                entity.motionX - episode.carryX,
                entity.motionY - episode.carryY,
                entity.motionZ - episode.carryZ);
        if (motion == null) {
            slot.stellurgy$setDeckEpisode(null);
            trace(entity, "release shipUnloaded");
            return false;
        }

        // Where the world put the entity before this update; the entity's own update overwrites its
        // previous-position fields with deck coordinates, which nothing outside may ever see.
        double lastX = entity.lastTickPosX, lastY = entity.lastTickPosY, lastZ = entity.lastTickPosZ;

        episode.writing = true;
        try {
            entity.setPosition(local[0], local[1], local[2]);
            entity.motionX = motion[0];
            entity.motionY = motion[1];
            entity.motionZ = motion[2];
            try {
                entity.onUpdate();
            } finally {
                mapOut(entity, episode, lastX, lastY, lastZ);
            }
        } finally {
            episode.writing = false;
        }
        return true;
    }

    /**
     * A position write reached a held entity. Called from {@code Entity.setPosition}, which every
     * result write in vanilla goes through - a teleport, a command, another mod placing the entity.
     * The writes this class makes itself, and the ones the entity's own update makes while it is in
     * the deck's frame, are not results but the deck's own bookkeeping and are ignored.
     */
    public static void noteWrite(Entity entity) {
        Episode episode = ((DeckHeld) entity).stellurgy$deckEpisode();
        if (episode != null && !episode.writing) {
            episode.worldWritten = true;
        }
    }

    /** Put an entity updated in the deck's frame back into the world, whatever its update did. */
    private static void mapOut(Entity entity, Episode episode, double lastX, double lastY, double lastZ) {
        World world = entity.world;
        String shipId = episode.shipId;
        double[] pos = VSIntegration.toWorldFrameFor(world, shipId, entity.posX, entity.posY, entity.posZ);
        double[] motion = VSIntegration.rotateToWorldFrameFor(world, shipId,
                entity.motionX, entity.motionY, entity.motionZ);
        if (pos == null || motion == null) {
            // The craft went away during the update. The entity cannot stay in deck coordinates,
            // and the only world position still known for it is where the world last had it.
            ((DeckHeld) entity).stellurgy$setDeckEpisode(null);
            trace(entity, "release shipGoneDuringUpdate");
            entity.setPosition(lastX, lastY, lastZ);
            entity.motionX = 0.0;
            entity.motionY = 0.0;
            entity.motionZ = 0.0;
            restorePrevious(entity, lastX, lastY, lastZ);
            return;
        }
        episode.localX = entity.posX;
        episode.localY = entity.posY;
        episode.localZ = entity.posZ;
        episode.held = true;
        double[] v = VSIntegration.shipVelocityAtPointFor(world, shipId, pos[0], pos[1], pos[2]);
        episode.carryX = v == null ? 0.0 : v[0] * TICK_SECONDS;
        episode.carryY = v == null ? 0.0 : v[1] * TICK_SECONDS;
        episode.carryZ = v == null ? 0.0 : v[2] * TICK_SECONDS;
        entity.setPosition(pos[0], pos[1], pos[2]);
        entity.motionX = motion[0] + episode.carryX;
        entity.motionY = motion[1] + episode.carryY;
        entity.motionZ = motion[2] + episode.carryZ;
        restorePrevious(entity, lastX, lastY, lastZ);
        // The substrate would otherwise go on dragging a body it still thinks is standing on its
        // hull, on top of the carry the deck has just given it.
        VSIntegration.suppressShipDrag(entity);
    }

    /**
     * How many ulps of the largest deck coordinate a point may be moved by a trip through the
     * craft's transform.
     *
     * <p>Measured 2026-09-29, a round trip deck -> world -> deck over 3645 points of a craft's block
     * region at 0 deg, 30 deg, 60 deg, 150 deg, 180 deg of roll and while spinning: at most 1.49e-8, which is exactly
     * 4 ulps of the largest coordinate met (1.92e7, where an ulp is 3.7e-9) - the shipyard lies far
     * from the origin, so its coordinates carry that coarse a grain. The bound is twice the worst
     * reading. It is a property of double arithmetic on these coordinates, not a tunable.</p>
     */
    private static final int MAPPING_NOISE_ULPS = 8;

    /**
     * Lift an entity out of any block its deck point overlaps by no more than the transform's own
     * error - and nothing deeper.
     *
     * <p>A position that arrives from the world (a teleport, or the world position the entity had when
     * the deck took it) is mapped into the deck's frame with that error in it. A write onto a surface
     * therefore lands inside the surface as often as above it, and vanilla then treats the entity as
     * embedded: an item placed exactly on a deck was ejected by {@code pushOutOfBlocks} and rolled
     * 0.85 blocks, where the same write on the ground is exact and nothing moves. Any overlap deeper
     * than the error is a real one - the writer put the entity inside a block - and is left to
     * vanilla, as it would be on the ground.</p>
     */
    private static void clearOfMappingNoise(Entity entity, double[] local) {
        double tolerance = MAPPING_NOISE_ULPS * Math.ulp(
                Math.max(Math.abs(local[0]), Math.max(Math.abs(local[1]), Math.abs(local[2]))));
        double half = entity.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(local[0] - half, local[1], local[2] - half,
                local[0] + half, local[1] + entity.height, local[2] + half);
        for (AxisAlignedBB block : entity.world.getCollisionBoxes(entity, box)) {
            if (!block.intersects(box)) {
                continue;
            }
            // The shallowest way out, per axis and direction; only an overlap within the error moves.
            double[] out = {block.maxX - box.minX, -(box.maxX - block.minX),
                    block.maxY - box.minY, -(box.maxY - block.minY),
                    block.maxZ - box.minZ, -(box.maxZ - block.minZ)};
            int best = 0;
            for (int i = 1; i < out.length; i++) {
                if (Math.abs(out[i]) < Math.abs(out[best])) {
                    best = i;
                }
            }
            if (Math.abs(out[best]) > tolerance) {
                continue;
            }
            local[best / 2] += out[best];
            box = box.offset(best / 2 == 0 ? out[best] : 0.0, best / 2 == 1 ? out[best] : 0.0,
                    best / 2 == 2 ? out[best] : 0.0);
        }
    }

    /** One line per deck-episode edge, test mode only. */
    private static void trace(Entity entity, String what) {
        if (dev.stannismod.stellurgy.command.test.TestProbeCommandRegistration.isTestMode()) {
            dev.stannismod.stellurgy.Stellurgy.logger.info("[DECK-TICK] " + what
                    + " id=" + entity.getEntityId() + " remote=" + entity.world.isRemote
                    + " pos=(" + entity.posX + "," + entity.posY + "," + entity.posZ + ")");
        }
    }

    private static void restorePrevious(Entity entity, double x, double y, double z) {
        entity.prevPosX = x;
        entity.prevPosY = y;
        entity.prevPosZ = z;
    }

    /**
     * The craft whose deck the entity is lying on now, measured in that craft's own frame, or
     * {@code null}. Never an entity on world terrain, and only the classes admitted so far.
     */
    private static String admissionFor(Entity entity) {
        if (!(entity instanceof EntityItem) || entity.isDead
                || entity.isInWater() || entity.isInLava()) {
            return null;
        }
        World world = entity.world;
        AxisAlignedBB box = entity.getEntityBoundingBox();
        java.util.List<String> candidates = VSIntegration.shipIdsAt(world, entity.posX, entity.posY, entity.posZ);        if (!world.getCollisionBoxes(entity, new AxisAlignedBB(box.minX, box.minY - SUPPORT_PROBE,
                box.minZ, box.maxX, box.minY, box.maxZ)).isEmpty()) {
            if (!candidates.isEmpty()) {
                trace(entity, "decline worldTerrain ships=" + candidates);
            }
            return null;
        }
        for (String shipId : candidates) {
            double[] local = VSIntegration.toShipFrameFor(world, shipId,
                    entity.posX, entity.posY, entity.posZ);
            double[] motion = VSIntegration.rotateToShipFrameFor(world, shipId,
                    entity.motionX, entity.motionY, entity.motionZ);
            if (local == null || motion == null) {
                continue;
            }
            double reach = SUPPORT_PROBE + Math.max(0.0, -motion[1]);
            double half = entity.width / 2.0;
            AxisAlignedBB underFeet = new AxisAlignedBB(local[0] - half, local[1] - reach, local[2] - half,
                    local[0] + half, local[1], local[2] + half);
            for (AxisAlignedBB floor : world.getCollisionBoxes(entity, underFeet)) {
                if (floor.maxY <= local[1] + STANDING_TOLERANCE) {
                    return shipId;
                }
            }
            trace(entity, "decline noFloor ship=" + shipId + " local=(" + local[0] + "," + local[1]
                    + "," + local[2] + ") reach=" + reach);
        }
        return null;
    }
}
