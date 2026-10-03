package dev.stannismod.stellurgy.integration.vs;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
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
 * <p>Velocity is held in the deck's frame, like the deck point. What the world adds to the entity's
 * world motion between two updates - an impulse, a knockback - is exactly the difference from the
 * world motion this class wrote, and that difference is taken into the deck's frame and added. So an
 * impulse survives whole, and neither the craft's own acceleration nor its turning leaks into the
 * velocity the entity has relative to its deck.</p>
 *
 * <p>Admits items and living bodies; a player only on the server, where his living update is run
 * in the deck's frame from his network handler ({@link #updatePlayer}) and never moves him.</p>
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
     * A deck's hold on one entity: which craft, the deck point and deck-frame velocity its last update
     * left it with, and the world motion this class last wrote into it.
     */
    public static final class Episode {
        final String shipId;
        /** Whether {@code local*} has been written by an update yet. */
        boolean held;
        double localX, localY, localZ;
        /** The entity's velocity relative to its deck, on the deck's axes, as its last update left it. */
        double localMotionX, localMotionY, localMotionZ;
        /** True while this class, or the entity's own update in the deck frame, writes its position. */
        boolean writing;
        /** Somebody else wrote the entity's position since this class last did: adopt it. */
        boolean worldWritten;
        /**
         * The world motion this class last wrote into the entity: its deck-frame velocity on world
         * axes plus the deck's own carry at its point. Whatever the entity's world motion differs from
         * this by, the world added.
         */
        double writtenX, writtenY, writtenZ;

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
            // A player is where his client says he is; re-seating him here would be a second writer.
            if (!(entity instanceof DeckHeld) || entity.isDead || entity.isRiding()
                    || entity instanceof EntityPlayer) {
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
     * Whether a deck holds {@code entity} now. While it does, the deck's frame is the only place its
     * movement is resolved, so every other mechanism that would move it must stand down.
     */
    public static boolean holds(Entity entity) {
        return episodeOf(entity) != null;
    }

    /** The craft whose deck holds {@code entity}, or {@code null} when none does. */
    public static String heldShipId(Entity entity) {
        Episode episode = episodeOf(entity);
        return episode == null ? null : episode.shipId;
    }

    /**
     * Where on its deck {@code entity} is held, in the craft's subspace, or {@code null} when no deck
     * holds it or its first update in the deck's frame has not run yet.
     */
    public static double[] heldDeckPoint(Entity entity) {
        Episode episode = episodeOf(entity);
        return episode == null || !episode.held
                ? null : new double[]{episode.localX, episode.localY, episode.localZ};
    }

    private static Episode episodeOf(Entity entity) {
        return entity instanceof DeckHeld ? ((DeckHeld) entity).stellurgy$deckEpisode() : null;
    }

    /**
     * Declare that the deck of {@code shipId} holds {@code entity} at subspace point
     * {@code (subX, subY, subZ)}, at rest relative to that deck - the placement a crossing makes when
     * it puts a carried body back down. Refused for a body the deck would not take.
     *
     * @return whether the deck now holds it
     */
    public static boolean holdAt(Entity entity, String shipId, double subX, double subY, double subZ) {
        if (!(entity instanceof DeckHeld) || shipId == null || entity instanceof EntityPlayer
                || !admissible(entity)) {
            return false;
        }
        forgetPath(entity);
        Episode episode = open(entity, shipId);
        episode.localX = subX;
        episode.localY = subY;
        episode.localZ = subZ;
        episode.held = true;
        // At rest relative to the deck: no velocity of its own, and the world motion the caller left
        // it (zero) counts as what the deck wrote, so none of it is taken for an impulse.
        episode.writtenX = entity.motionX;
        episode.writtenY = entity.motionY;
        episode.writtenZ = entity.motionZ;
        trace(entity, "hold ship=" + shipId);
        return true;
    }

    /**
     * Run a server player's living update in his deck's frame, if a deck holds him or takes him now.
     *
     * <p>A player's position is not the server's to move: his client resolves every step and sends
     * the result, and the network handler puts him back where that result left him as soon as his
     * update is over. So the deck point is taken from where the client says he is, every time, and
     * what his update does to his position is thrown away here as it would be anyway. What the
     * update in the deck's frame buys is everything ELSE it does: the handlers that read where he is
     * read his place on the craft, and the travel resolver never takes him a second time.</p>
     *
     * <p>His velocity crosses by rotation alone, with no carry: on the server it is the network
     * handler's record of how far his client moved him, and its length is what the speed check
     * reads.</p>
     *
     * @return {@code true} when the update was run here and the caller must not run it again
     */
    public static boolean updatePlayer(EntityPlayerMP player) {
        World world = player.world;
        if (world == null || world.isRemote) {
            return false;
        }
        DeckHeld slot = (DeckHeld) player;
        Episode episode = slot.stellurgy$deckEpisode();
        if (episode != null && player.capabilities.isFlying) {
            // He took to the air on the deck: flight aboard is the travel resolver's, so the deck is
            // handed over to it at the point he is at - opened there FIRST, so no instant exists in
            // which nothing holds him - and this episode ends without a release.
            double[] at = VSIntegration.toShipFrameFor(world, episode.shipId,
                    player.posX, player.posY, player.posZ);
            if (at != null) {
                ShipFrameTravel.takeOverFlyer(player, episode.shipId, at);
            }
            slot.stellurgy$setDeckEpisode(null);
            trace(player, "handOver flying");
            return false;
        }
        if (episode != null && !admissible(player)) {
            release(player, "excludedState");
            return false;
        }
        // A hold that names ANOTHER craft for him outranks the episode this deck opened by where he
        // stood: let go, and he is taken again, declared craft first.
        String declared = DeckHold.heldShipId(player);
        if (episode != null && declared != null && !declared.equals(episode.shipId)) {
            release(player, "declaredElsewhere");
            episode = null;
        }
        if (episode == null) {
            String shipId = admissionFor(player);
            if (shipId == null) {
                return false;
            }
            trace(player, "admit ship=" + shipId);
            episode = open(player, shipId);
        }
        String shipId = episode.shipId;
        double[] local = VSIntegration.toShipFrameFor(world, shipId, player.posX, player.posY, player.posZ);
        if (local != null) {
            clearOfMappingNoise(player, local);
        }
        AxisAlignedBB stay = VSIntegration.subspaceStayRegion(world, shipId, STAY_REGION_MARGIN);
        if (local == null || stay == null || !stay.contains(new Vec3d(local[0], local[1], local[2]))) {
            release(player, local == null || stay == null ? "shipUnloaded" : "leftShipRegion");
            return false;
        }
        if (!ShipFrameTravel.deckMayKeep(player, shipId, local)) {
            release(player, "noDeckBelow");
            return false;
        }
        double[] motion = VSIntegration.rotateToShipFrameFor(world, shipId,
                player.motionX, player.motionY, player.motionZ);
        if (motion == null) {
            release(player, "shipUnloaded");
            return false;
        }
        double worldX = player.posX, worldY = player.posY, worldZ = player.posZ;
        double prevX = player.prevPosX, prevY = player.prevPosY, prevZ = player.prevPosZ;
        episode.localX = local[0];
        episode.localY = local[1];
        episode.localZ = local[2];
        episode.held = true;
        episode.worldWritten = false;
        episode.writing = true;
        try {
            player.setPosition(local[0], local[1], local[2]);
            player.motionX = motion[0];
            player.motionY = motion[1];
            player.motionZ = motion[2];
            try {
                player.onUpdateEntity();
            } finally {
                double[] out = VSIntegration.rotateToWorldFrameFor(world, shipId,
                        player.motionX, player.motionY, player.motionZ);
                player.setPosition(worldX, worldY, worldZ);
                if (out != null) {
                    player.motionX = out[0];
                    player.motionY = out[1];
                    player.motionZ = out[2];
                }
                restorePrevious(player, prevX, prevY, prevZ);
            }
        } finally {
            episode.writing = false;
        }
        VSIntegration.suppressShipDrag(player);
        return true;
    }

    /**
     * Replay a displacement of a body the deck holds, made from OUTSIDE its own update, against the
     * deck's blocks in the deck's frame instead of the world's.
     *
     * <p>Only a player's own movement, for now: the server replays the step his client already took
     * on the deck, and against the world's blocks and the craft's hull polygons that step collides
     * with things that are not where the deck is. A push from a piston or a shulker is expressed in
     * the frame of whatever pushes, which is not decided yet, and is left to the world.</p>
     *
     * @return {@code true} when the move was made here and the caller must not make it again
     */
    public static boolean moveHeld(Entity entity, MoverType type, double dx, double dy, double dz) {
        Episode episode = episodeOf(entity);
        if (episode == null || episode.writing || type != MoverType.PLAYER) {
            return false;
        }
        World world = entity.world;
        String shipId = episode.shipId;
        double[] from = VSIntegration.toShipFrameFor(world, shipId, entity.posX, entity.posY, entity.posZ);
        double[] to = VSIntegration.toShipFrameFor(world, shipId,
                entity.posX + dx, entity.posY + dy, entity.posZ + dz);
        if (from == null || to == null) {
            return false;
        }
        clearOfMappingNoise(entity, from);
        double worldX = entity.posX, worldY = entity.posY, worldZ = entity.posZ;
        episode.writing = true;
        try {
            entity.setPosition(from[0], from[1], from[2]);
            entity.move(type, to[0] - from[0], to[1] - from[1], to[2] - from[2]);
            double[] out = VSIntegration.toWorldFrameFor(world, shipId, entity.posX, entity.posY, entity.posZ);
            if (out == null) {
                // The craft went away during the move: put the body back where the world had it and
                // let the world make the step, rather than leave it standing in the shipyard.
                entity.setPosition(worldX, worldY, worldZ);
                trace(entity, "moveHeld shipGoneDuringMove");
                return false;
            }
            entity.setPosition(out[0], out[1], out[2]);
        } finally {
            episode.writing = false;
        }
        return true;
    }

    /**
     * Update {@code entity} in its deck's frame if a deck holds it or takes it now.
     *
     * @return {@code true} when the entity was updated here and the caller must not update it again
     */
    public static boolean update(Entity entity) {
        World world = entity.world;
        // A player's world-tick update is not his living update (that one runs from his network
        // handler, see updatePlayer), so it is never the one moved into the deck's frame.
        if (world == null || !(entity instanceof DeckHeld) || entity instanceof EntityPlayer) {
            return false;
        }
        DeckHeld slot = (DeckHeld) entity;
        Episode episode = slot.stellurgy$deckEpisode();
        if (episode != null && !admissible(entity)) {
            release(entity, "excludedState");
            return false;
        }
        if (episode == null) {
            String shipId = admissionFor(entity);
            if (shipId == null) {
                return false;
            }
            trace(entity, "admit ship=" + shipId);
            forgetPath(entity);
            episode = open(entity, shipId);
            // A body arriving from the world moves with its real world velocity, of which the
            // craft's own motion at that point is the part the deck already accounts for: it enters
            // with no velocity of its own on the deck, and everything beyond the carry counts as
            // what the world gave it.
            double[] v = VSIntegration.shipVelocityAtPointFor(world, shipId,
                    entity.posX, entity.posY, entity.posZ);
            if (v != null) {
                episode.writtenX = v[0] * TICK_SECONDS;
                episode.writtenY = v[1] * TICK_SECONDS;
                episode.writtenZ = v[2] * TICK_SECONDS;
            }
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
            release(entity, local == null || stay == null ? "shipUnloaded" : "leftShipRegion");
            return false;
        }
        // Off the deck's edge, or out on the outer hull: the world's, not the deck's.
        if (!ShipFrameTravel.deckMayKeep(entity, shipId, local)) {
            release(entity, "noDeckBelow");
            return false;
        }
        // WHAT THE ENTITY IS DOING ON THE DECK: the velocity its last update left it, plus whatever the
        // world has added since, taken into the deck's frame. Never the whole world motion rotated in.
        // Measured 2026-09-29: an armor stand resting on a craft slewing to 150 deg of roll moved 0.41
        // blocks along the deck when its velocity crossed through the world every tick - a body at rest
        // keeps gravity's last step as a velocity into the deck, and handing it out through one pose
        // and back in through the next turns every step of the craft's rotation into a push along it.
        double[] added = VSIntegration.rotateToShipFrameFor(world, shipId,
                entity.motionX - episode.writtenX,
                entity.motionY - episode.writtenY,
                entity.motionZ - episode.writtenZ);
        if (added == null) {
            release(entity, "shipUnloaded");
            return false;
        }
        double[] motion = {episode.localMotionX + added[0], episode.localMotionY + added[1],
                episode.localMotionZ + added[2]};

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
            if (dev.stannismod.stellurgy.command.test.TestProbeCommandRegistration.isTestMode()) {
                StackTraceElement[] at = Thread.currentThread().getStackTrace();
                StringBuilder by = new StringBuilder();
                for (int i = 3; i < Math.min(at.length, 9); i++) {
                    by.append(' ').append(at[i].getClassName().replaceAll(".*\\.", ""))
                            .append('.').append(at[i].getMethodName()).append(':').append(at[i].getLineNumber());
                }
                trace(entity, "worldWrite by" + by);
            }
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
            release(entity, "shipGoneDuringUpdate");
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
        episode.localMotionX = entity.motionX;
        episode.localMotionY = entity.motionY;
        episode.localMotionZ = entity.motionZ;
        episode.held = true;
        double[] v = VSIntegration.shipVelocityAtPointFor(world, shipId, pos[0], pos[1], pos[2]);
        episode.writtenX = motion[0] + (v == null ? 0.0 : v[0] * TICK_SECONDS);
        episode.writtenY = motion[1] + (v == null ? 0.0 : v[1] * TICK_SECONDS);
        episode.writtenZ = motion[2] + (v == null ? 0.0 : v[2] * TICK_SECONDS);
        entity.setPosition(pos[0], pos[1], pos[2]);
        entity.motionX = episode.writtenX;
        entity.motionY = episode.writtenY;
        entity.motionZ = episode.writtenZ;
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
     * Whether a deck may hold {@code entity} at all, as it is now - asked before taking it and on
     * every update while holding it, so the two can never disagree.
     *
     * <p>A living body is the deck's in exactly the states the travel resolver would hold it in, by
     * asking the resolver's own predicate, and only on the server; one that is riding or ridden
     * belongs to its vehicle. A player is held on the server only, and not while he flies: flight
     * aboard has no counterpart here yet and stays with the travel resolver. An item in water or lava
     * is left to the world - which states exclude a body that is not living is an open question, and
     * this is only where the first admission put the line.</p>
     */
    private static boolean admissible(Entity entity) {
        if (entity.isDead || entity.isBeingRidden()) {
            return false;
        }
        if (entity instanceof EntityItem) {
            return !entity.isInWater() && !entity.isInLava();
        }
        if (entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying) {
            return false;
        }
        // Only on the server. A client moves a mob by interpolating toward the world positions the
        // server sends, so its update run in the deck's frame would walk it toward a world point read
        // as a deck point - measured 2026-09-29, a cow carried on a rolled deck was never drawn by the
        // client once its update ran here; and a client's own player is still the travel resolver's.
        return entity instanceof EntityLivingBase && !entity.world.isRemote
                && !ShipFrameTravel.isExcludedFromCapture((EntityLivingBase) entity);
    }

    /**
     * Seam: a deck has just taken {@code entity}, on craft {@code shipId}; {@code from} is the craft
     * that held it the moment before, by either mechanism, or {@code null}. Empty in production -
     * the test build records the edge here - and kept as a call because this is the one place that
     * knows both the new holder and the old one at the instant they change.
     */
    private static void noteEntered(Entity entity, String shipId, String from) {
    }

    /** Seam: the deck frame is about to let {@code entity} go, for {@code reason}. Empty in production. */
    private static void noteReleased(Entity entity, String reason) {
    }

    /** Open an episode on {@code shipId} for {@code entity}, announcing the edge. */
    private static Episode open(Entity entity, String shipId) {
        noteEntered(entity, shipId, ShipFrameTravel.capturedShipId(entity));
        Episode episode = new Episode(shipId);
        ((DeckHeld) entity).stellurgy$setDeckEpisode(episode);
        return episode;
    }

    /** Stop holding {@code entity}; from its next update the world moves it again. */
    private static void release(Entity entity, String reason) {
        noteReleased(entity, reason);
        ((DeckHeld) entity).stellurgy$setDeckEpisode(null);
        forgetPath(entity);
        trace(entity, "release " + reason);
    }

    /**
     * Drop a mob's planned path when it crosses between the world and a deck: the path is a list of
     * block positions in the frame it was planned in, and in the other frame it names somewhere
     * else entirely.
     */
    private static void forgetPath(Entity entity) {
        if (entity instanceof EntityLiving) {
            ((EntityLiving) entity).getNavigator().clearPath();
        }
    }

    /**
     * The craft whose deck the entity is lying on now, measured in that craft's own frame, or
     * {@code null}. Never an entity on world terrain.
     */
    private static String admissionFor(Entity entity) {
        if (!admissible(entity)) {
            return null;
        }
        World world = entity.world;
        AxisAlignedBB box = entity.getEntityBoundingBox();
        java.util.List<String> candidates = new java.util.ArrayList<>(
                VSIntegration.shipIdsAt(world, entity.posX, entity.posY, entity.posZ));
        // A DECLARED craft is asked first, as the travel resolver asks it: an arrival or a relog has
        // already said which craft this body belongs to, and where two hulls overlap a spatial pick
        // and that declaration differ - measured 2026-09-30, a crew member arriving from hyperspace
        // was taken by a hull that merely overlapped his, and the hold waiting for his own expired.
        String declared = DeckHold.heldShipId(entity);
        if (declared != null) {
            candidates.remove(declared);
            candidates.add(0, declared);
        }
        if (!world.getCollisionBoxes(entity, new AxisAlignedBB(box.minX, box.minY - SUPPORT_PROBE,
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
