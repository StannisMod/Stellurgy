package dev.stannismod.stellurgy.sensor;

import dev.stannismod.stellurgy.affs.util.CodeUtils;
import dev.stannismod.stellurgy.api.sensor.SensorMode;
import dev.stannismod.stellurgy.api.sensor.TargetTrack;
import dev.stannismod.stellurgy.integration.vs.ShipFrameTravel;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.weapon.HullAllegiance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One sweep: everything a sensor can currently hold, in the order it would shoot at them.
 *
 * <h3>Classification happens HERE, not at the trigger</h3>
 * <p>A friend is not something a gun declines to fire at — a friend never becomes a contact in the
 * first place. That is a stronger statement than it looks: a battery cannot be talked into shelling
 * its own crew by a race, a stale target or a console left pointing at the wrong thing, because the
 * name of the friendly was never written down anywhere a gun could read it. A creature is excluded by
 * the credential it carries, by being aboard the ship the sensor is bolted to, or by being aboard a
 * hull the installation's rule vouches for; a hull is excluded by being the sensor's own ship, or by
 * that rule.</p>
 *
 * <h3>Two sources of candidates</h3>
 * <p>The living world — every creature in the envelope — and the loaded hulls. A hull is in range when
 * the NEAREST point of its world bounds is, and it is held at the quality of that distance: measured
 * to its centre, a long ship whose bow is a few blocks away would be out of range. A hull is held
 * only by ILLUMINATING: what a listening sensor would hear of one is its heat, and no sensor can read
 * a hull's heat by the hull's identity yet, so a listening sensor hears no hull at all rather than a
 * guess at where its radiators might be.</p>
 *
 * <h3>World frame, always</h3>
 * <p>Entities live in the world's coordinates and a ship's blocks live in the ship's, so a sensor
 * aboard a hull converts its OWN position out to the world before looking around, and the tracks it
 * produces are in world coordinates. A gun consuming them converts back, once, using the same seam
 * it already uses for every other target.</p>
 */
public final class TacticalScan {

    private TacticalScan() {
    }

    /**
     * Look around and answer what is out there, best contact first ({@link ThreatOrder}).
     *
     * @param world          the server world the sensor sits in
     * @param origin         the sensor's own position, in WORLD coordinates
     * @param ownShipId      the ship the sensor is bolted to, or null for a ground installation
     * @param radiusBlocks   how far the device can look at all
     * @param mode           listening, or illuminating
     * @param activePlateau  the quality an illuminated contact is held at inside the envelope
     * @param friendlyCode   the installation's access code; anything carrying it is not a contact
     * @param hullRule       how the installation tells a friendly hull, or null for a sensor on no
     *                       network — which then has no way to tell one, and holds no hull at all
     * @param hostilesOnly   whether harmless livestock and villagers may be acquired
     * @param lockFloor      the quality a contact must reach before a gun may fire at it
     * @param maxTracks      how many contacts the device can hold at once
     * @return the contacts, possibly none; or EMPTY when the sensor could not look at all — its own
     *         ship has a pose but no measurable motion (registered, its physics not yet loaded), so
     *         every arrival time would be taken against a motion nobody knows. "Looked and saw
     *         nothing" and "could not look" are different answers, and the caller must say which.
     */
    public static Optional<List<TargetTrack>> sweep(World world, Vec3d origin, String ownShipId,
                                                    double radiusBlocks, SensorMode mode,
                                                    double activePlateau, String friendlyCode,
                                                    HullAllegiance hullRule, boolean hostilesOnly,
                                                    double lockFloor, int maxTracks) {
        if (world == null || world.isRemote || origin == null || radiusBlocks <= 0.0D || maxTracks <= 0) {
            return Optional.of(Collections.emptyList());
        }
        Vec3d ownVelocity = Vec3d.ZERO;
        if (ownShipId != null) {
            double[] carried = VSIntegration.shipVelocityAtPointFor(world, ownShipId, origin.x, origin.y,
                    origin.z);
            if (carried == null) {
                return Optional.empty();
            }
            ownVelocity = new Vec3d(carried[0], carried[1], carried[2]);
        }

        List<TargetTrack> tracks = new ArrayList<>();
        List<String> vouched = new ArrayList<>();
        for (Map.Entry<String, AxisAlignedBB> hull : VSIntegration.loadedShipBoxes(world).entrySet()) {
            String shipId = hull.getKey();
            if (shipId.equals(ownShipId)) {
                // Our own ship, by identity, before any credential is asked about: a ship with no
                // transponder aboard never acquires itself.
                continue;
            }
            if (hullRule == null) {
                continue;
            }
            if (hullRule.isFriend(world, shipId, friendlyCode)) {
                vouched.add(shipId);
                continue;
            }
            TargetTrack track = hullTrackOf(world, shipId, hull.getValue(), origin, radiusBlocks, mode,
                    activePlateau);
            if (track != null) {
                tracks.add(track);
            }
        }

        AxisAlignedBB envelope = new AxisAlignedBB(
                origin.x - radiusBlocks, origin.y - radiusBlocks, origin.z - radiusBlocks,
                origin.x + radiusBlocks, origin.y + radiusBlocks, origin.z + radiusBlocks);
        for (EntityLivingBase candidate : world.getEntitiesWithinAABB(EntityLivingBase.class, envelope)) {
            TargetTrack track = trackOf(candidate, origin, ownShipId, vouched, radiusBlocks, mode,
                    activePlateau, friendlyCode, hostilesOnly);
            if (track != null) {
                tracks.add(track);
            }
        }

        tracks.sort(ThreatOrder.of(origin, ownVelocity, lockFloor));
        return Optional.of(tracks.size() <= maxTracks ? tracks : new ArrayList<>(tracks.subList(0, maxTracks)));
    }

    /** One hull, or null if it is not a contact: out of range, not lit, or not measurable. */
    private static TargetTrack hullTrackOf(World world, String shipId, AxisAlignedBB extent, Vec3d origin,
                                           double radiusBlocks, SensorMode mode, double activePlateau) {
        if (mode != SensorMode.ACTIVE || extent == null) {
            return null;
        }
        double distance = ThreatOrder.nearestPoint(extent, origin).distanceTo(origin);
        if (distance > radiusBlocks) {
            return null;
        }
        double quality = SignatureModel.activeQuality(distance, radiusBlocks, activePlateau);
        if (quality <= 0.0D) {
            return null;
        }
        Vec3d centre = new Vec3d((extent.minX + extent.maxX) * 0.5D, (extent.minY + extent.maxY) * 0.5D,
                (extent.minZ + extent.maxZ) * 0.5D);
        double[] motion = VSIntegration.shipVelocityAtPointFor(world, shipId, centre.x, centre.y, centre.z);
        if (motion == null) {
            // Not loaded on this side after all: neither where it is going nor when it arrives is known.
            return null;
        }
        return TargetTrack.ofHull(shipId, extent, new Vec3d(motion[0], motion[1], motion[2]), quality, mode,
                distance);
    }

    /** One creature, or null if it is not a contact at all. */
    private static TargetTrack trackOf(EntityLivingBase candidate, Vec3d origin, String ownShipId,
                                       List<String> vouched, double radiusBlocks, SensorMode mode,
                                       double activePlateau, String friendlyCode, boolean hostilesOnly) {
        if (candidate == null || candidate.isDead) {
            return null;
        }
        if (hostilesOnly && !(candidate instanceof IMob) && !(candidate instanceof EntityPlayer)) {
            // A defensive battery that opens up on passing livestock is a battery a player switches
            // off, and a switched-off battery defends nothing.
            return null;
        }
        if (CodeUtils.entityHasMatchingCode(candidate, friendlyCode)) {
            return null;
        }
        if (ShipFrameTravel.isOnShip(candidate, ownShipId)) {
            // Standing on our own deck. The crew of a ship carry no credential by default and are
            // not going to acquire one mid-boarding-action; being aboard IS the credential.
            return null;
        }
        for (String friend : vouched) {
            if (ShipFrameTravel.isOnShip(candidate, friend)) {
                // Aboard a hull the installation calls a friend: the same reasoning, one ship over. A
                // round at him would land in that friendly hull first.
                return null;
            }
        }

        Vec3d position = bodyCentre(candidate);
        double distance = position.distanceTo(origin);
        if (distance > radiusBlocks) {
            // The bounding box is a cube and the envelope is a sphere.
            return null;
        }

        double temperature = SignatureModel.estimatedTemperatureKelvin(candidate);
        double area = SignatureModel.estimatedAreaSquareMetres(candidate);
        double radiance = SignatureModel.radiance(temperature);

        double quality;
        if (mode == SensorMode.ACTIVE) {
            quality = SignatureModel.activeQuality(distance, radiusBlocks, activePlateau);
        } else {
            if (distance > SignatureModel.detectionRangeBlocks(temperature, area)) {
                // Not heard at all: a listening sensor's reach is the target's own output, not the
                // device's rating. Silence never makes a thing invisible, but it does move this line.
                return null;
            }
            quality = SignatureModel.passiveQuality(temperature, distance);
        }
        if (quality <= 0.0D) {
            return null;
        }

        return TargetTrack.ofEntity(candidate.getUniqueID(), position, velocityOf(candidate), quality, mode,
                radiance, distance);
    }

    /** The middle of the body: a round at foot height passes under everything on uneven ground. */
    private static Vec3d bodyCentre(Entity entity) {
        return entity.getPositionVector().addVector(0.0D, entity.height * 0.5D, 0.0D);
    }

    /**
     * How fast it is actually going, in blocks per tick, taken from where it WAS rather than from
     * its motion fields — a player's motion is decided on their own client and a mob's is spent
     * before it is read, while the distance covered since the last tick is true for both.
     */
    private static Vec3d velocityOf(Entity entity) {
        return new Vec3d(entity.posX - entity.lastTickPosX, entity.posY - entity.lastTickPosY,
                entity.posZ - entity.lastTickPosZ);
    }
}
