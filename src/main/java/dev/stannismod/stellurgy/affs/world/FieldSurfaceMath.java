package dev.stannismod.stellurgy.affs.world;

import dev.stannismod.stellurgy.affs.te.TileEntityFieldGenerator;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public final class FieldSurfaceMath {

    public static final double FIELD_THICKNESS = 1.0D;
    public static final double FIELD_HALF_THICKNESS = FIELD_THICKNESS * 0.5D;
    private static final double SMOOTH_UNION_K = 0.75D;

    private FieldSurfaceMath() {
    }

    /**
     * Signed distance from {@code point} to the VOLUME the field encloses: negative inside, positive
     * in the gap outside, the per-emitter spheres blended by a smooth minimum so neighbouring emitters
     * read as one body. This is the "is it under the shield" question.
     *
     * <p>The other question a shield is asked — "is this box touching the membrane" — is
     * {@link #intersectsCompositeShell}. The two are different predicates and neither answers for the
     * other: a block in the middle of a bubble is deep inside the volume and nowhere near the membrane.</p>
     */
    public static double compositeHullDistance(List<? extends FieldSource> generators, Vec3d point) {
        if (generators.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = solidDistance(generators.get(0), point);
        for (int i = 1; i < generators.size(); i++) {
            distance = smoothMin(distance, solidDistance(generators.get(i), point), SMOOTH_UNION_K);
        }
        return distance;
    }

    /**
     * Does {@code box} touch the MEMBRANE — any one emitter's sphere surface, {@link #FIELD_THICKNESS}
     * thick — rather than lie in the volume? It is what a body crossing the field meets. Each emitter's
     * membrane is tested on its own, without the volume's smooth blend: a body deep inside a bubble
     * answers {@code false} here and negative from {@link #compositeHullDistance}.
     */
    public static boolean intersectsCompositeShell(World world, AxisAlignedBB box) {
        return intersectsCompositeShell(getActiveGenerators(world), box);
    }

    public static boolean intersectsCompositeShell(List<? extends FieldSource> generators, AxisAlignedBB box) {
        for (FieldSource generator : generators) {
            if (intersectsShell(generator, box)) {
                return true;
            }
        }
        return false;
    }

    /** The generators in {@code world} that are projecting a shell now. Empty for a client world. */
    public static List<TileEntityFieldGenerator> getActiveGenerators(World world) {
        List<TileEntityFieldGenerator> generators = new ArrayList<>();
        for (TileEntityFieldGenerator generator : TileEntityFieldGenerator.loadedIn(world)) {
            if (generator.isInvalid()) {
                continue;
            }
            if (!generator.isFieldPowered()) {
                continue;
            }
            // A ship-frame emitter whose ship is not loaded on this side cannot resolve its world
            // centre; it contributes no shell (Q4 fail-open) rather than projecting one at the wrong place.
            if (!generator.isFrameReady()) {
                continue;
            }
            generators.add(generator);
        }
        return generators;
    }

    /**
     * Nearest forward distance at which a ray entering from OUTSIDE crosses this source's world-space
     * shell sphere (mid-shell radius = {@link FieldSource#getRadius()}), within {@code [0, maxDist]}.
     * Returns {@code -1} when the ray does not cross the shell inward in range. Used by the cooperative
     * strike service and the residual ray hook to find where an incoming beam meets the field.
     */
    public static double rayShellEntry(FieldSource source, Vec3d origin, Vec3d dir, double maxDist) {
        return raySphereEntry(source.getWorldCenter(), source.getRadius(), origin, dir, maxDist);
    }

    /**
     * Nearest forward distance at which a ray crosses the given sphere from OUTSIDE, within
     * {@code [0, maxDist]}; {@code dir} must be unit length. Returns {@code -1} when the origin is
     * already inside the sphere (an outgoing / interior ray is never intercepted — a shooter is not
     * billed by its own shield and interaction among blocks inside the shell is unaffected), the ray
     * points away, or the crossing is beyond {@code maxDist}.
     */
    public static double raySphereEntry(Vec3d center, double radius, Vec3d origin, Vec3d dir, double maxDist) {
        if (center == null || origin == null || dir == null) {
            return -1.0D;
        }
        double mx = origin.x - center.x;
        double my = origin.y - center.y;
        double mz = origin.z - center.z;
        double mm = mx * mx + my * my + mz * mz;
        double r2 = radius * radius;
        if (mm <= r2) {
            return -1.0D; // origin inside the shell — never intercept an outgoing/interior ray
        }
        double b = mx * dir.x + my * dir.y + mz * dir.z; // m . dir (dir is unit, so a == 1)
        double disc = b * b - (mm - r2);
        if (disc < 0.0D) {
            return -1.0D;
        }
        double t = -b - Math.sqrt(disc); // near root: the outside->inside crossing
        if (t < 0.0D || t > maxDist) {
            return -1.0D;
        }
        return t;
    }

    public static AxisAlignedBB influenceBox(TileEntityFieldGenerator generator) {
        // Broad-phase search box in WORLD coordinates around the field's world centre — entities are
        // always world-frame, so on a ship this box must follow the hull, not sit at the shipyard.
        Vec3d c = generator.getWorldCenter();
        double half = generator.getRadius() + FIELD_THICKNESS + 0.5D;
        return new AxisAlignedBB(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half);
    }

    private static double smoothMin(double a, double b, double k) {
        double h = clamp01(0.5D + 0.5D * (b - a) / k);
        return mix(b, a, h) - k * h * (1.0D - h);
    }

    private static double solidDistance(FieldSource source, Vec3d point) {
        Vec3d c = source.getWorldCenter();
        double dx = point.x - c.x;
        double dy = point.y - c.y;
        double dz = point.z - c.z;
        double distanceToCenter = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distanceToCenter - source.getRadius();
    }

    private static boolean intersectsShell(FieldSource source, AxisAlignedBB box) {
        Vec3d c = source.getWorldCenter();
        double cx = c.x;
        double cy = c.y;
        double cz = c.z;

        double minDistanceSq = distanceSqPointToAabb(cx, cy, cz, box);
        double maxDistanceSq = maxDistanceSqPointToAabb(cx, cy, cz, box);
        double innerRadius = Math.max(0.0D, source.getRadius() - FIELD_HALF_THICKNESS);
        double outerRadius = source.getRadius() + FIELD_HALF_THICKNESS;

        double innerRadiusSq = innerRadius * innerRadius;
        double outerRadiusSq = outerRadius * outerRadius;
        return minDistanceSq <= outerRadiusSq && maxDistanceSq >= innerRadiusSq;
    }

    private static double distanceSqPointToAabb(double x, double y, double z, AxisAlignedBB box) {
        double dx = clampAxisDistance(x, box.minX, box.maxX);
        double dy = clampAxisDistance(y, box.minY, box.maxY);
        double dz = clampAxisDistance(z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double maxDistanceSqPointToAabb(double x, double y, double z, AxisAlignedBB box) {
        double dx = farthestAxisDistance(x, box.minX, box.maxX);
        double dy = farthestAxisDistance(y, box.minY, box.maxY);
        double dz = farthestAxisDistance(z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clampAxisDistance(double point, double min, double max) {
        if (point < min) {
            return min - point;
        }
        if (point > max) {
            return point - max;
        }
        return 0.0D;
    }

    private static double farthestAxisDistance(double point, double min, double max) {
        return Math.max(Math.abs(point - min), Math.abs(point - max));
    }

    private static double mix(double a, double b, double t) {
        return a * (1.0D - t) + b * t;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public static Vec3d reflect(Vec3d velocity, Vec3d normal) {
        if (velocity == null || normal == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        Vec3d unitNormal = normalizeOrZero(normal);
        if (vectorLength(unitNormal) <= 1.0E-8D) {
            return velocity;
        }
        double normalVelocity = dotProduct(velocity, unitNormal);
        return subtract(velocity, scale(unitNormal, 2.0D * normalVelocity));
    }

    public static Vec3d normalizeOrZero(Vec3d vector) {
        if (vector == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        double length = vectorLength(vector);
        if (length <= 1.0E-8D) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        return scale(vector, 1.0D / length);
    }

    public static Vec3d sphereOutwardNormal(Vec3d center, Vec3d point, Vec3d fallback) {
        Vec3d normal = normalizeOrZero(point.subtract(center));
        if (vectorLength(normal) > 1.0E-8D) {
            return normal;
        }
        normal = normalizeOrZero(fallback);
        if (vectorLength(normal) > 1.0E-8D) {
            return normal;
        }
        return new Vec3d(1.0D, 0.0D, 0.0D);
    }

    public static Vec3d boxOutwardNormal(AxisAlignedBB box, Vec3d point, Vec3d fallback) {
        if (box == null || point == null) {
            return normalizeOrZero(fallback);
        }

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double dx = point.x - centerX;
        double dy = point.y - centerY;
        double dz = point.z - centerZ;
        double sx = Math.abs(box.maxX - box.minX);
        double sy = Math.abs(box.maxY - box.minY);
        double sz = Math.abs(box.maxZ - box.minZ);

        if (sx <= sy && sx <= sz) {
            return axisNormal(dx, fallback == null ? 0.0D : fallback.x, 0);
        }
        if (sy <= sz) {
            return axisNormal(dy, fallback == null ? 0.0D : fallback.y, 1);
        }
        return axisNormal(dz, fallback == null ? 0.0D : fallback.z, 2);
    }

    private static Vec3d axisNormal(double delta, double fallbackComponent, int axis) {
        double sign = Math.abs(delta) > 1.0E-8D ? Math.signum(delta) : Math.abs(fallbackComponent) > 1.0E-8D ? Math.signum(fallbackComponent) : 1.0D;
        if (axis == 0) {
            return new Vec3d(sign, 0.0D, 0.0D);
        }
        if (axis == 1) {
            return new Vec3d(0.0D, sign, 0.0D);
        }
        return new Vec3d(0.0D, 0.0D, sign);
    }

    public static double inwardNormalSpeedSq(Vec3d velocity, Vec3d outwardNormal) {
        if (velocity == null || outwardNormal == null) {
            return 0.0D;
        }
        Vec3d unitNormal = normalizeOrZero(outwardNormal);
        if (vectorLength(unitNormal) <= 1.0E-8D) {
            return 0.0D;
        }
        double inwardSpeed = -dotProduct(velocity, unitNormal);
        if (inwardSpeed <= 0.0D) {
            return 0.0D;
        }
        return inwardSpeed * inwardSpeed;
    }

    public static double vectorLength(Vec3d vector) {
        if (vector == null) {
            return 0.0D;
        }
        return Math.sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z);
    }

    public static Vec3d scale(Vec3d vector, double factor) {
        if (vector == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        return new Vec3d(vector.x * factor, vector.y * factor, vector.z * factor);
    }

    public static Vec3d subtract(Vec3d left, Vec3d right) {
        if (left == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        if (right == null) {
            return new Vec3d(left.x, left.y, left.z);
        }
        return new Vec3d(left.x - right.x, left.y - right.y, left.z - right.z);
    }

    public static double dotProduct(Vec3d left, Vec3d right) {
        if (left == null || right == null) {
            return 0.0D;
        }
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

}
