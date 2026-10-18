package dev.stannismod.stellurgy.ship.mass;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * Turns a pile of {@link MassContributor}s into a {@link ShipMassFrame}.
 *
 * <p>Accumulates in one pass and finishes in another, so the caller may hand over contributors in
 * any order and from any source — the hull scan, a tank's fluid, a seated crewman — without knowing
 * what came before. Adding is commutative and associative to within floating-point rounding, which
 * matters because the order blocks arrive in is an accident of chunk iteration and must not be
 * something the ship's handling depends on.</p>
 *
 * <p><b>Why the inertia is accumulated the way it is.</b> The tensor must end up expressed about the
 * centre of mass, but the centre of mass is not known until every contributor has been seen. Rather
 * than making two passes over the contributors, this class accumulates the second moments about the
 * ORIGIN and shifts the whole result to the centre of mass once at the end, which is the parallel-axis
 * theorem applied in reverse and is exact, not an approximation.</p>
 *
 * <p>Each contributor also carries its own box inertia. Without it, a hull whose blocks are collinear
 * — a mast, a girder, a one-block-wide antenna — produces a tensor with a zero eigenvalue about the
 * line, and inverting that is a division by zero on the physics thread. With it, every non-empty
 * ship has a positive-definite tensor by construction.</p>
 */
public final class ShipMassFrameBuilder {

    private double structuralMass;
    private double contentMass;
    private double crewMass;
    private double reactionMass;

    // Mass-weighted position sum, about the origin.
    private double sx;
    private double sy;
    private double sz;

    // Second moments about the ORIGIN, shifted to the centre of mass in build().
    private double ixx;
    private double iyy;
    private double izz;
    private double ixy;
    private double ixz;
    private double iyz;

    public ShipMassFrameBuilder add(MassContributor contributor) {
        if (contributor == null) {
            return this;
        }
        double m = contributor.getMass();
        if (m <= 0.0D) {
            return this;
        }

        switch (contributor.getKind()) {
            case STRUCTURAL:
                structuralMass += m;
                break;
            case CONTENT:
                contentMass += m;
                break;
            case REACTION:
                reactionMass += m;
                break;
            case CREW:
            default:
                crewMass += m;
                break;
        }

        double x = contributor.getX();
        double y = contributor.getY();
        double z = contributor.getZ();
        sx += m * x;
        sy += m * y;
        sz += m * z;

        // A solid box of side s about its own centre: m*(s^2 + s^2)/12 on each diagonal entry.
        double s = contributor.getExtent();
        double own = m * (s * s) / 6.0D;

        ixx += own + m * (y * y + z * z);
        iyy += own + m * (x * x + z * z);
        izz += own + m * (x * x + y * y);
        ixy -= m * x * y;
        ixz -= m * x * z;
        iyz -= m * y * z;

        return this;
    }

    public ShipMassFrameBuilder addAll(Iterable<MassContributor> contributors) {
        if (contributors != null) {
            for (MassContributor contributor : contributors) {
                add(contributor);
            }
        }
        return this;
    }

    public ShipMassFrame build() {
        double total = structuralMass + contentMass + crewMass + reactionMass;
        if (total <= 0.0D) {
            return ShipMassFrame.empty();
        }

        double cx = sx / total;
        double cy = sy / total;
        double cz = sz / total;

        // Shift the second moments from the origin to the centre of mass. Doing it here rather than
        // per contributor is why one pass suffices.
        double dxx = ixx - total * (cy * cy + cz * cz);
        double dyy = iyy - total * (cx * cx + cz * cz);
        double dzz = izz - total * (cx * cx + cy * cy);
        double dxy = ixy + total * cx * cy;
        double dxz = ixz + total * cx * cz;
        double dyz = iyz + total * cy * cz;

        // JOML's Matrix3d takes columns; the tensor is symmetric, so the two readings agree.
        Matrix3d inertia = new Matrix3d(
                dxx, dxy, dxz,
                dxy, dyy, dyz,
                dxz, dyz, dzz);

        return new ShipMassFrame(structuralMass, contentMass, crewMass, reactionMass,
                new Vector3d(cx, cy, cz), inertia);
    }

    /**
     * The body left when {@code taken} leaves a body of {@code mass} kilograms whose centre is
     * {@code centre} and whose inertia about that centre is {@code inertia} — exactly the body built
     * without them, by the same box rule {@link #add} uses (C10 STAT-40). It is how a burn moves the
     * physics record by the mass it drained, every tick, without re-walking the hull.
     *
     * <p>Worked about the old centre, so a centre millions of blocks out loses no precision.</p>
     *
     * @return {@code {mass, cx, cy, cz, Ixx, Iyy, Izz, Ixy, Ixz, Iyz}}; {@code null} when nothing would be
     *         left
     */
    public static double[] remove(double mass, org.joml.Vector3dc centre, org.joml.Matrix3dc inertia,
                                  Iterable<MassContributor> taken) {
        // Second moments about the OLD centre: the frame's own tensor, then each removal's own box and arm.
        double ixx = inertia.m00(), iyy = inertia.m11(), izz = inertia.m22();
        double ixy = inertia.m10(), ixz = inertia.m20(), iyz = inertia.m21();
        double left = mass;
        double sx = 0, sy = 0, sz = 0;
        for (MassContributor c : taken) {
            double m = c.getMass();
            if (m <= 0.0D) {
                continue;
            }
            double x = c.getX() - centre.x(), y = c.getY() - centre.y(), z = c.getZ() - centre.z();
            double own = m * (c.getExtent() * c.getExtent()) / 6.0D;
            ixx -= own + m * (y * y + z * z);
            iyy -= own + m * (x * x + z * z);
            izz -= own + m * (x * x + y * y);
            ixy += m * x * y;
            ixz += m * x * z;
            iyz += m * y * z;
            sx -= m * x;
            sy -= m * y;
            sz -= m * z;
            left -= m;
        }
        if (!(left > 0.0D)) {
            return null;
        }
        double cx = sx / left, cy = sy / left, cz = sz / left;
        return new double[] {left, centre.x() + cx, centre.y() + cy, centre.z() + cz,
                ixx - left * (cy * cy + cz * cz), iyy - left * (cx * cx + cz * cz), izz - left * (cx * cx + cy * cy),
                ixy + left * cx * cy, ixz + left * cx * cz, iyz + left * cy * cz};
    }
}
