package dev.stannismod.stellurgy.api.sensor;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * One contact, as a sensor currently holds it: what it is, where it is, where it is going, and how
 * well it is being held.
 *
 * <h3>Position AND velocity, because a point cannot be led</h3>
 * <p>A gun handed a point misses a moving target by however far it moves while the round is in the
 * air. That is not a gun problem — a gun has no way to know a target is moving — so the velocity
 * travels with the contact, and the mount that consumes it works out the intercept using its own
 * muzzle speed.</p>
 *
 * <h3>Quality is not confidence</h3>
 * <p>{@link #getQuality()} is how well the target is RESOLVED, on 0..1. It is what separates
 * "something is out there" from "I can put a round on it": below the installation's lock threshold a
 * battery may track a contact all day and still not fire at it. Where the number comes from depends
 * on the mode that produced it — the target's own radiance when listening, the sensor's own
 * illumination when lit.</p>
 *
 * <h3>Two kinds of subject, told apart by name</h3>
 * <p>A contact is either a living thing, named by its entity uuid, or a HULL, named by the physics
 * substrate's id for the ship and carrying the ship's world bounds as its extent. The two are built
 * by two named factories, {@link #ofEntity} and {@link #ofHull}, because a consumer that took a
 * ship's id for an entity's would look the entity up, find nothing, and aim at a stale point without
 * a word. A hull's {@link #getPosition()} is the middle of its extent — a point inside the envelope,
 * not necessarily on matter — and {@link #getDistance()} is measured to the NEAREST point of the
 * extent, so a large hull whose bow is close is close.</p>
 *
 * <p>Immutable. A track is a snapshot of a moment, not a handle onto a target that keeps changing
 * underneath its reader.</p>
 */
public final class TargetTrack {

    private final UUID entity;
    private final String ship;
    private final AxisAlignedBB extent;
    private final Vec3d position;
    private final Vec3d velocity;
    private final double quality;
    private final SensorMode mode;
    private final double radianceWattsPerSquareMetre;
    private final double distance;

    private TargetTrack(UUID entity, String ship, AxisAlignedBB extent, Vec3d position, Vec3d velocity,
                        double quality, SensorMode mode, double radianceWattsPerSquareMetre,
                        double distance) {
        this.entity = entity;
        this.ship = ship;
        this.extent = extent;
        this.position = position;
        this.velocity = velocity == null ? Vec3d.ZERO : velocity;
        this.quality = Math.max(0.0D, Math.min(1.0D, quality));
        this.mode = mode;
        this.radianceWattsPerSquareMetre = radianceWattsPerSquareMetre;
        this.distance = distance;
    }

    /** A living contact: an entity by its uuid, held at {@code position} (its body centre). */
    public static TargetTrack ofEntity(UUID entity, Vec3d position, Vec3d velocity, double quality,
                                       SensorMode mode, double radianceWattsPerSquareMetre,
                                       double distance) {
        return new TargetTrack(entity, null, null, position, velocity, quality, mode,
                radianceWattsPerSquareMetre, distance);
    }

    /**
     * A hull contact: the ship {@code shipId} (the physics substrate's id), whose WORLD bounds are
     * {@code extent}. Its position is the middle of the extent; {@code distance} is to the extent's
     * nearest point. A hull radiates nothing a sensor can read yet, so its radiance is NaN — no
     * signature, which is a different answer from a signature of zero.
     */
    public static TargetTrack ofHull(String shipId, AxisAlignedBB extent, Vec3d velocity, double quality,
                                     SensorMode mode, double distance) {
        Vec3d centre = new Vec3d((extent.minX + extent.maxX) * 0.5D, (extent.minY + extent.maxY) * 0.5D,
                (extent.minZ + extent.maxZ) * 0.5D);
        return new TargetTrack(null, shipId, extent, centre, velocity, quality, mode, Double.NaN, distance);
    }

    /** The entity this contact is, or null for a hull. */
    public UUID getEntity() {
        return entity;
    }

    /** The ship this contact is, by the physics substrate's id, or null for an entity. */
    public String getShip() {
        return ship;
    }

    /** Whether this contact is a hull rather than a living thing. */
    public boolean isHull() {
        return ship != null;
    }

    /** A hull's world bounds when it was taken, or null for an entity, which is held as a point. */
    public AxisAlignedBB getExtent() {
        return extent;
    }

    /**
     * The contact's identity as text — the entity's uuid or the ship's id — the one key two contacts
     * of either kind can be told apart and ordered by.
     */
    public String getSubjectId() {
        return ship != null ? ship : String.valueOf(entity);
    }

    /** Where it was when the scan saw it, in WORLD coordinates — never a ship's subspace. */
    public Vec3d getPosition() {
        return position;
    }

    /** How it was moving, in blocks per tick, world frame. Zero for something that was not. */
    public Vec3d getVelocity() {
        return velocity;
    }

    /** How well it is resolved, 0..1. */
    public double getQuality() {
        return quality;
    }

    /** Which channel produced this: what was heard, or what was lit. */
    public SensorMode getMode() {
        return mode;
    }

    /**
     * The target's own radiance, W/m² — the passive channel's actual input. NaN for a hull, whose
     * signature no sensor can read yet.
     */
    public double getRadianceWattsPerSquareMetre() {
        return radianceWattsPerSquareMetre;
    }

    /**
     * How far the contact was from the sensor when it was taken, in blocks: to an entity's body
     * centre, to the nearest point of a hull's extent.
     */
    public double getDistance() {
        return distance;
    }

    /**
     * Whether this contact is resolved well enough to shoot at. A contact that is detected but not
     * locked is a real and useful state: the battery knows something is there, and cannot hit it.
     */
    public boolean isLocked(double qualityFloor) {
        return quality >= qualityFloor;
    }
}
