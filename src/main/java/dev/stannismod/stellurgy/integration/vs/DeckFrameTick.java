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
        /**
         * When this hold began, on the travel resolver's install clock - so a deck point the server
         * sends can tell a hold that predates it from one taken during its window. A hold that takes
         * over the resolver's capture on the same craft continues that capture and keeps its stamp.
         */
        long installEpoch;
        /** Opened by a server-sent deck point (or continues a capture that was): a re-sent copy of
         *  that point must not put the body back on it. */
        boolean seedAnchored;

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
            // A player is where his client says he is; re-seating him anywhere but on that client would
            // be a second writer.
            if (!(entity instanceof DeckHeld) || entity.isDead || entity.isRiding()
                    || (entity instanceof EntityPlayer && !isLocalPlayer(entity))) {
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

    /**
     * Whether {@code entity} is being updated in its deck's frame right now - so its position is a
     * point in the craft's shipyard, not in the world. What its update's handlers read there is the
     * craft's ENCLOSURE (its roof, its sealed rooms); a fact about the WORLD (weather, biome, the
     * world's own blocks over it) is asked at {@link #worldPositionOf} instead.
     */
    public static boolean inDeckFrame(Entity entity) {
        Episode episode = episodeOf(entity);
        return episode != null && episode.writing;
    }

    /**
     * Where {@code entity} is in its world: its own position, or, while {@link #inDeckFrame}, its
     * deck point mapped through the craft's pose. {@code null} only for a body in the deck frame of
     * a craft that no longer maps - a position that names nowhere, never a stand-in.
     */
    public static net.minecraft.util.math.BlockPos worldPositionOf(Entity entity) {
        Episode episode = episodeOf(entity);
        if (episode == null || !episode.writing) {
            return entity.getPosition();
        }
        double[] w = VSIntegration.toWorldFrameFor(entity.world, episode.shipId,
                entity.posX, entity.posY, entity.posZ);
        return w == null ? null : new net.minecraft.util.math.BlockPos(w[0], w[1], w[2]);
    }

    static Episode episodeOf(Entity entity) {
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
            handOverFlyer(player, episode);
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
        // Where on the deck his client last said he is (claimArrived), unless somebody else has written
        // his position since. Never re-derived from his world position as a matter of course: that was
        // laid down against the pose his packet was handled under, and the world tick has moved the
        // craft since. Measured 2026-10-05 at the onset of a roll: claims declared exactly on the floor
        // arrived here 0.08 to 0.15 above it, and his replayed update ended in the air.
        boolean mappedIn = !episode.held || episode.worldWritten;
        double[] local = mappedIn
                ? VSIntegration.toShipFrameFor(world, shipId, player.posX, player.posY, player.posZ)
                : new double[]{episode.localX, episode.localY, episode.localZ};
        episode.worldWritten = false;
        if (mappedIn && local != null) {
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
     * The network handler's write of a server player back onto where his packet left him, right after
     * his update - for a held player exactly the position {@link #updatePlayer} has just restored, so
     * it is the deck's own bookkeeping and not a placement. Counted as one, it threw away his declared
     * deck point every tick and his next update re-derived it from the world after the craft had moved.
     * (A write cannot be told by its VALUE: vanilla's teleports assign the position fields first and
     * then write those same values.)
     */
    public static void restoreAfterUpdate(EntityPlayerMP player, double x, double y, double z,
                                          float yaw, float pitch) {
        Episode episode = episodeOf(player);
        boolean own = episode != null && !episode.writing;
        if (own) {
            episode.writing = true;
        }
        try {
            player.setPositionAndRotation(x, y, z, yaw, pitch);
        } finally {
            if (own) {
                episode.writing = false;
            }
        }
    }

    /**
     * A movement packet from a player a deck holds has just been handled: where it left him in the
     * world is his client's claim, and it is taken onto the deck NOW, through the pose the packet was
     * handled under - his next update comes after the world tick has moved the craft.
     */
    public static void claimArrived(EntityPlayerMP player) {
        Episode episode = episodeOf(player);
        if (episode == null) {
            return;
        }
        double[] local = VSIntegration.toShipFrameFor(player.world, episode.shipId,
                player.posX, player.posY, player.posZ);
        if (local == null) {
            return;
        }
        clearOfMappingNoise(player, local);
        episode.localX = local[0];
        episode.localY = local[1];
        episode.localZ = local[2];
        episode.held = true;
        episode.worldWritten = false;
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
        // The velocity crosses too, by rotation alone: a move zeroes the velocity along every axis it
        // clipped on, and here those are the deck's axes - the deck's floor must zero the velocity into
        // the deck, not the world's vertical.
        double[] motion = VSIntegration.rotateToShipFrameFor(world, shipId,
                entity.motionX, entity.motionY, entity.motionZ);
        if (from == null || to == null || motion == null) {
            return false;
        }
        // Both ends arrive from the world through the same transform, so both carry its noise: a
        // standing player's zero step otherwise clips the floor by a few ulps and is taken for a landing.
        clearOfMappingNoise(entity, from);
        clearOfMappingNoise(entity, to);
        double worldX = entity.posX, worldY = entity.posY, worldZ = entity.posZ;
        double motionX = entity.motionX, motionY = entity.motionY, motionZ = entity.motionZ;
        episode.writing = true;
        try {
            entity.setPosition(from[0], from[1], from[2]);
            entity.motionX = motion[0];
            entity.motionY = motion[1];
            entity.motionZ = motion[2];
            entity.move(type, to[0] - from[0], to[1] - from[1], to[2] - from[2]);
            double[] out = VSIntegration.toWorldFrameFor(world, shipId, entity.posX, entity.posY, entity.posZ);
            double[] outMotion = VSIntegration.rotateToWorldFrameFor(world, shipId,
                    entity.motionX, entity.motionY, entity.motionZ);
            if (out == null || outMotion == null) {
                // The craft went away during the move: put the body back where the world had it and
                // let the world make the step, rather than leave it standing in the shipyard.
                entity.setPosition(worldX, worldY, worldZ);
                entity.motionX = motionX;
                entity.motionY = motionY;
                entity.motionZ = motionZ;
                trace(entity, "moveHeld shipGoneDuringMove");
                return false;
            }
            entity.setPosition(out[0], out[1], out[2]);
            entity.motionX = outMotion[0];
            entity.motionY = outMotion[1];
            entity.motionZ = outMotion[2];
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
        // A player's world-tick update is never the one moved into the deck's frame: on the server his
        // living update runs from his network handler (updatePlayer), and on his own client the world
        // tick's update also SENDS his position, after his living update - so there only the living
        // update is moved (updateLocalPlayerLiving), and the send sees him back in the world.
        if (entity instanceof EntityPlayer) {
            return false;
        }
        return runInDeckFrame(entity, entity::onUpdate);
    }

    /**
     * Run the living update of the player THIS client plays in his deck's frame, if a deck holds him
     * or takes him now. Called for the {@code onLivingUpdate} inside his entity update, so everything
     * after it - the step his limbs swing by, the position his client sends - reads him in the world.
     *
     * @return {@code true} when his living update was run here and the caller must not run it again
     */
    public static boolean updateLocalPlayerLiving(EntityLivingBase body) {
        if (!isLocalPlayer(body)) {
            return false;
        }
        return runInDeckFrame(body, () -> livingUpdateOnDeckHeading(body));
    }

    /**
     * His living update, steered by the heading he holds ON THE DECK. Vanilla walks a body along its
     * {@code rotationYaw}, and his is a WORLD yaw - the projection of where he looks on the deck, skewed
     * on a rolled craft and degenerate on a vertical one. Measured 2026-10-04 on a ~90-degree deck: the
     * mouse held a 90-degree deck heading and he walked 5.9 degrees. The world yaw is put back after,
     * so nothing outside the update sees the deck one.
     */
    private static void livingUpdateOnDeckHeading(EntityLivingBase body) {
        Episode episode = episodeOf(body);
        float worldYaw = body.rotationYaw;
        if (episode != null) {
            body.rotationYaw = ShipFrameTravel.deckYawDeg(body, episode.shipId);
        }
        try {
            body.onLivingUpdate();
        } finally {
            body.rotationYaw = worldYaw;
        }
    }

    /**
     * A player held by a deck took to the air on it: flight aboard is the travel resolver's, so the
     * deck is handed over to it at the point he is at - opened there FIRST, so no instant exists in
     * which nothing holds him - and this episode ends without a release. The same on both sides.
     */
    private static void handOverFlyer(EntityPlayer player, Episode episode) {
        double[] at = VSIntegration.toShipFrameFor(player.world, episode.shipId,
                player.posX, player.posY, player.posZ);
        if (at != null) {
            ShipFrameTravel.takeOverFlyer(player, episode.shipId, at);
        }
        ((DeckHeld) player).stellurgy$setDeckEpisode(null);
        trace(player, "handOver flying");
    }

    /** The player this client plays: the one body on a client whose movement the client owns. */
    private static boolean isLocalPlayer(Entity entity) {
        return entity.world != null && entity.world.isRemote && entity instanceof EntityPlayer
                && ((EntityPlayer) entity).isUser();
    }

    private static boolean runInDeckFrame(Entity entity, Runnable ownUpdate) {
        World world = entity.world;
        if (world == null || !(entity instanceof DeckHeld)) {
            return false;
        }
        DeckHeld slot = (DeckHeld) entity;
        Episode episode = slot.stellurgy$deckEpisode();
        if (episode != null && entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying) {
            handOverFlyer((EntityPlayer) entity, episode);
            return false;
        }
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
                ownUpdate.run();
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
        if (isLocalPlayer(entity)) {
            // The position his client sends next is this deck point; sent as one, the server maps it
            // through its own pose. Sent as the world position just computed through THIS side's pose,
            // it arrived mapped elsewhere on the deck - measured 2026-10-05 on a rolling deck, 0.1 to
            // 0.2 above the floor, and a standing player's claims alternating 0.004 apart.
            VSIntegration.declareMovementClaim(entity, shipId, episode.localX, episode.localY, episode.localZ);
        }
        // The substrate would otherwise go on dragging a body it still thinks is standing on its
        // hull, on top of the carry the deck has just given it.
        VSIntegration.suppressShipDrag(entity);
        noteHeldTick(entity, shipId, episode.localX, episode.localY, episode.localZ,
                v == null ? 0.0 : v[0] * TICK_SECONDS, v == null ? 0.0 : v[1] * TICK_SECONDS,
                v == null ? 0.0 : v[2] * TICK_SECONDS, entity.onGround);
    }

    /**
     * Seam: a deck has just updated {@code entity} in its frame and put it back in the world - at deck
     * point {@code local*}, carried by the deck's own velocity at that point ({@code carry*}, blocks
     * per tick), and standing on something after the update or not ({@code grounded}). Empty in
     * production.
     */
    private static void noteHeldTick(Entity entity, String shipId, double localX, double localY,
                                     double localZ, double carryX, double carryY, double carryZ,
                                     boolean grounded) {
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
        // On the server, and on a client only the player it plays. A client moves a mob by
        // interpolating toward the world positions the server sends, so its update run in the deck's
        // frame would walk it toward a world point read as a deck point - measured 2026-09-29, a cow
        // carried on a rolled deck was never drawn by the client once its update ran here. The local
        // player is the one body a client moves by its own update.
        return entity instanceof EntityLivingBase && (!entity.world.isRemote || isLocalPlayer(entity))
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
        ShipFrameTravel.stampEpisode(entity, episode);
        ((DeckHeld) entity).stellurgy$setDeckEpisode(episode);
        return episode;
    }

    /**
     * A deck point the server sent for the player this client plays - his dismount spot, or where a
     * relog put him back: the deck holds him there, at rest relative to it. Refused for any other
     * body and for one the deck would not take, which the caller then hands to the travel resolver.
     *
     * @param world the deck point mapped into the world through the pose the caller read it at
     * @return whether the deck now holds him
     */
    static boolean holdSeeded(Entity entity, String shipId, double subX, double subY, double subZ,
                              double[] world) {
        if (!isLocalPlayer(entity) || !admissible(entity)) {
            return false;
        }
        Episode episode = open(entity, shipId);
        episode.seedAnchored = true;
        episode.localX = subX;
        episode.localY = subY;
        episode.localZ = subZ;
        episode.held = true;
        episode.writing = true;
        try {
            entity.setPositionAndUpdate(world[0], world[1], world[2]);
            entity.motionX = 0.0;
            entity.motionY = 0.0;
            entity.motionZ = 0.0;
            entity.fallDistance = 0.0f;
        } finally {
            episode.writing = false;
        }
        trace(entity, "hold seeded ship=" + shipId);
        return true;
    }

    /** Stop holding {@code entity}; from its next update the world moves it again. */
    private static void release(Entity entity, String reason) {
        Episode episode = ((DeckHeld) entity).stellurgy$deckEpisode();
        noteReleased(entity, reason);
        ((DeckHeld) entity).stellurgy$setDeckEpisode(null);
        forgetPath(entity);
        if (episode != null && entity instanceof EntityPlayerMP) {
            keepCarry(entity, episode.shipId);
        }
        trace(entity, "release " + reason);
    }

    /**
     * A server player leaves the deck with the craft's motion at his point, as his client does: there
     * his held velocity always carried it, while his update on the server hands his velocity back by
     * rotation alone. Without it the two sides part at the edge of the deck - his client moves on with
     * the craft's momentum and the server's speed check, which judges him again once nothing holds
     * him, measures that against a velocity that never had it.
     */
    private static void keepCarry(Entity entity, String shipId) {
        double[] v = VSIntegration.shipVelocityAtPointFor(entity.world, shipId,
                entity.posX, entity.posY, entity.posZ);
        if (v != null) {
            entity.motionX += v[0] * TICK_SECONDS;
            entity.motionY += v[1] * TICK_SECONDS;
            entity.motionZ += v[2] * TICK_SECONDS;
        }
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
            // A craft DECLARED for him is boarded by the declaration, not by where his feet happen
            // to be: it is judged by what keeps a held body (a deck below in the craft's frame, or
            // its enclosure), not by the standing probe. Measured 2026-10-04: a crossing pinned a
            // crew member 0.5 above his deck, this probe refused him every tick, and the hold that
            // pinned him re-teleported him every tick waiting for a deck that would never take him.
            if (shipId.equals(declared) && ShipFrameTravel.deckMayKeep(entity, shipId, local)) {
                return shipId;
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
