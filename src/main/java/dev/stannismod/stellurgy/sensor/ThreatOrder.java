package dev.stannismod.stellurgy.sensor;

import dev.stannismod.stellurgy.api.sensor.TargetTrack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;

/**
 * Which of the contacts a sensor holds its battery is handed: a threat assessment, by strict
 * priority, with no weights.
 *
 * <h3>The order</h3>
 * <ol>
 *   <li>a contact the battery can LOCK before one it cannot — a battery that has to choose chooses the
 *       one it can actually hit, however threatening the other;</li>
 *   <li>(reserved: a contact this picture has seen ACT before one it has not. Nothing observes an act
 *       yet, so nothing feeds this stage and it is not written; when acts are observed it goes
 *       here, above everything below);</li>
 *   <li>the one that will ARRIVE first — the smaller time to contact, range over closing speed. A
 *       contact that is not closing has no arrival, and every such contact compares equal here;</li>
 *   <li>the nearer;</li>
 *   <li>the better held;</li>
 *   <li>the smaller identity, so that two sweeps over the same world never reorder equals.</li>
 * </ol>
 *
 * <h3>What it never reads</h3>
 * <p>The KIND of the contact — a creature and a hull with the same lock, range and motion rank the
 * same. Its size or its signature: the spread between a mob and a battleship is orders of magnitude,
 * so any presence term turns this into "hull always first". And anything about whose it is: owner,
 * faction, crew, credential. Who is a friend was decided before a contact existed; this only orders
 * what is left. Until it acts, a contact capable of intent is UNCERTAIN, and nothing kinematic is
 * read as hostile — so before the act stage exists, the battery serves whatever arrives first.</p>
 *
 * <h3>Closing speed, and its floor</h3>
 * <p>The rate at which the range shrinks: the contact's velocity relative to the sensor's own,
 * projected on the line of sight to the point the range is measured to (an entity's body centre, the
 * nearest point of a hull's extent). Positive is closing; below {@link #CLOSING_FLOOR_BLOCKS_PER_TICK}
 * a contact counts as not closing.</p>
 */
public final class ThreatOrder {

    /**
     * The closing speed, in blocks per tick, below which a contact is at rest for this order.
     *
     * <p>Two bounds, and the lower one decides because breaching it is a defect. LOWER: what a body
     * at rest reads. A creature's velocity is the difference of two positions the engine wrote a tick
     * apart and reads exactly 0 standing still, but a PARKED hull reads the physics substrate's
     * numerical residue — measured 2026-10-20 on the server tier, a hull with physics off reporting
     * 1.5e-17 blocks/tick, pointed at the sensor it was parked beside. With a floor of zero that
     * residue is an arrival time whenever it happens to point inward, and a parked hull would be
     * handed over before a nearer creature at rest. UPPER: the slowest motion that is real, whose
     * breach only orders a creeping contact by range instead of arrival. This floor sits eight orders
     * above the residue, and a contact closing at it covers one block in about thirty years of game
     * time, so nothing that is moving is mistaken for rest.</p>
     */
    static final double CLOSING_FLOOR_BLOCKS_PER_TICK = 1.0E-9D;

    private ThreatOrder() {
    }

    /**
     * The order for one sweep, best first.
     *
     * @param origin      the sensor, in WORLD coordinates
     * @param ownVelocity the sensor's own motion, world frame, blocks per tick — zero on the ground
     * @param lockFloor   the quality a contact must reach before a gun may fire at it
     */
    public static Comparator<TargetTrack> of(Vec3d origin, Vec3d ownVelocity, double lockFloor) {
        Vec3d own = ownVelocity == null ? Vec3d.ZERO : ownVelocity;
        return Comparator.<TargetTrack>comparingInt(t -> t.isLocked(lockFloor) ? 0 : 1)
                .thenComparingDouble(t -> timeToContact(t, origin, own))
                .thenComparingDouble(TargetTrack::getDistance)
                .thenComparingDouble(t -> -t.getQuality())
                .thenComparing(TargetTrack::getSubjectId);
    }

    /**
     * Ticks until the contact reaches the sensor at its present closing speed; positive infinity for
     * one that is not closing, zero for one already there.
     */
    private static double timeToContact(TargetTrack track, Vec3d origin, Vec3d ownVelocity) {
        double range = track.getDistance();
        if (range <= 0.0D) {
            return 0.0D;
        }
        double closing = closingSpeed(track, origin, ownVelocity);
        return closing > CLOSING_FLOOR_BLOCKS_PER_TICK ? range / closing : Double.POSITIVE_INFINITY;
    }

    /** Blocks per tick at which the contact's range is shrinking; negative when it is opening. */
    private static double closingSpeed(TargetTrack track, Vec3d origin, Vec3d ownVelocity) {
        Vec3d point = rangePoint(track, origin);
        Vec3d sight = point.subtract(origin);
        double length = sight.lengthVector();
        if (length <= 0.0D) {
            return 0.0D;
        }
        Vec3d relative = track.getVelocity().subtract(ownVelocity == null ? Vec3d.ZERO : ownVelocity);
        return -relative.dotProduct(sight) / length;
    }

    /** The point the contact's range is measured to: its position, or the nearest point of its extent. */
    private static Vec3d rangePoint(TargetTrack track, Vec3d origin) {
        AxisAlignedBB extent = track.getExtent();
        return extent == null ? track.getPosition() : nearestPoint(extent, origin);
    }

    /** The point of {@code box} nearest {@code point}; the point itself when it is inside. */
    static Vec3d nearestPoint(AxisAlignedBB box, Vec3d point) {
        return new Vec3d(clamp(point.x, box.minX, box.maxX), clamp(point.y, box.minY, box.maxY),
                clamp(point.z, box.minZ, box.maxZ));
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
