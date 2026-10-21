package dev.stannismod.stellurgy.environment;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A body's magnetic field, modelled as a dipole that may be TILTED from the spin axis and OFFSET from
 * the body's centre, fixed to the body and turning with it.
 *
 * <p>A trapped particle drifts all the way round the body on its field shell and is lost wherever its
 * mirror point dips into the atmosphere. With the dipole offset, that dip is deepest on the side where
 * the dipole sits furthest from the surface, so the shell's survivors are set THERE — and there, at a
 * given altitude, the field is weak enough to hold them. One low orbit therefore crosses a high-dose
 * segment over the weak-field side and almost nothing elsewhere: the mechanism of Earth's South Atlantic
 * Anomaly, and nothing names the region.</p>
 *
 * <p>All quantities SI. Positions are in the BODY-FIXED frame: origin at the body's centre, z along the
 * spin axis. Immutable.</p>
 */
public final class PlanetaryDipole {

    /** μ0 / 4π, in T·m/A — the dipole field's coupling constant. */
    private static final double MU0_OVER_4PI = 1e-7d;
    /** μ0, in T·m/A. */
    private static final double MU0 = 4d * Math.PI * MU0_OVER_4PI;
    /**
     * Halvings of the latitude interval when locating a field line's foot: 40 take a quarter turn below
     * 1e-12 rad, far under any change in the field a dose figure can resolve. A bound on work, not a
     * physical quantity.
     */
    private static final int FOOT_BISECTIONS = 40;
    /**
     * Longitudes a drift shell is sampled at to find where it dips deepest: 5° apart, finer than the
     * offset's own footprint on the surface. A bound on work, not a physical quantity.
     */
    private static final int DRIFT_LONGITUDES = 72;
    /**
     * Shells tabulated between L = 1 and the magnetopause, log-spaced; between two entries the cutoff
     * changes by well under one percent for any offset up to 0.55 R. A bound on work.
     */
    private static final int SHELL_TABLE_SIZE = 256;

    private final double momentAm2;
    private final Vector3d axis;
    private final Vector3d offset;
    private final double bodyRadiusM;
    private final double absorbingRadiusM;
    private final double magnetopauseL;
    /** |B| at which each tabulated shell's particles are lost, tesla; 0 where the shell meets the absorber. */
    private final double[] shellCutoff;

    private PlanetaryDipole(double momentAm2, Vector3d axis, Vector3d offset, double bodyRadiusM,
                            double absorbingRadiusM, double magnetopauseL) {
        this.momentAm2 = momentAm2;
        this.axis = axis;
        this.offset = offset;
        this.bodyRadiusM = bodyRadiusM;
        this.absorbingRadiusM = absorbingRadiusM;
        this.magnetopauseL = magnetopauseL;
        this.shellCutoff = magnetopauseL > 1d ? tabulateCutoffs() : new double[0];
    }

    /**
     * A dipole stated by its physical parameters.
     *
     * @param momentAm2          magnetic moment, A·m²; must be positive (a body with no field has no dipole)
     * @param axis               direction of the moment in the body-fixed frame; normalised here
     * @param offsetM            the dipole's centre relative to the body's centre, body-fixed, metres
     * @param bodyRadiusM        the body's radius, metres
     * @param absorbingAltitudeM the altitude below which trapped particles are lost — the top of the
     *                           absorbing atmosphere, or zero for an airless surface
     * @param windPressurePa     the stellar wind's dynamic pressure at the body, which bounds the
     *                           magnetosphere (Chapman–Ferraro stand-off)
     */
    public static PlanetaryDipole of(double momentAm2, Vector3dc axis, Vector3dc offsetM, double bodyRadiusM,
                                     double absorbingAltitudeM, double windPressurePa) {
        if (!(momentAm2 > 0d) || !(bodyRadiusM > 0d) || !(windPressurePa > 0d) || axis.lengthSquared() == 0d) {
            throw new IllegalArgumentException("a dipole needs a positive moment, radius, wind pressure and an axis");
        }
        double surfaceField = MU0_OVER_4PI * momentAm2 / (bodyRadiusM * bodyRadiusM * bodyRadiusM);
        // Chapman–Ferraro: the field at the nose is compressed to twice the dipole's, and balances the wind.
        double standoff = Math.pow(2d * surfaceField * surfaceField / (MU0 * windPressurePa), 1d / 6d);
        return new PlanetaryDipole(momentAm2, new Vector3d(axis).normalize(), new Vector3d(offsetM), bodyRadiusM,
                bodyRadiusM + Math.max(0d, absorbingAltitudeM), standoff);
    }

    /** Field at a body-fixed point, tesla, written into {@code out}. */
    Vector3d fieldAt(Vector3dc bodyFixed, Vector3d out) {
        Vector3d r = new Vector3d(bodyFixed).sub(offset);
        double d = r.length();
        double mDotR = axis.dot(r) / d;
        double k = MU0_OVER_4PI * momentAm2 / (d * d * d);
        return out.set(r).div(d).mul(3d * mDotR * k).fma(-k, axis);
    }

    /**
     * The trapped-particle dose rate at a body-fixed point, per belt, Gy/s in silicon behind the reference
     * shield, or zero where no particle can be trapped.
     */
    TrappedDose trappedDoseRate(Vector3dc bodyFixed) {
        if (bodyFixed.length() <= absorbingRadiusM) {
            return TrappedDose.ZERO;
        }
        Vector3d r = new Vector3d(bodyFixed).sub(offset);
        double d = r.length();
        double sinLat = axis.dot(r) / d;
        double cos2Lat = 1d - sinLat * sinLat;
        if (cos2Lat <= 0d) {
            return TrappedDose.ZERO;
        }
        double shell = d / (bodyRadiusM * cos2Lat);
        double cut = cutoffOnShell(shell);
        if (!(cut > 0d)) {
            return TrappedDose.ZERO;
        }
        return RadiationBelts.doseRate(shell, fieldMagnitude(d, sinLat) / cut);
    }

    /** |B| of the dipole at distance {@code d} from its centre and magnetic latitude with sine {@code sinLat}. */
    private double fieldMagnitude(double d, double sinLat) {
        return MU0_OVER_4PI * momentAm2 / (d * d * d) * Math.sqrt(1d + 3d * sinLat * sinLat);
    }

    /** The loss field of {@code shell}, interpolated from the table; 0 outside L = 1 .. magnetopause. */
    private double cutoffOnShell(double shell) {
        if (shell < 1d || shell >= magnetopauseL || shellCutoff.length == 0) {
            return 0d;
        }
        double at = Math.log(shell) / Math.log(magnetopauseL) * (SHELL_TABLE_SIZE - 1);
        int i = Math.min((int) at, SHELL_TABLE_SIZE - 2);
        double f = at - i;
        double lo = shellCutoff[i];
        double hi = shellCutoff[i + 1];
        return lo > 0d && hi > 0d ? lo + f * (hi - lo) : 0d;
    }

    /**
     * For each tabulated shell, the weakest field at which its field lines reach the absorbing sphere,
     * over every drift longitude and both hemispheres — the lowest mirror point on the drift path. A
     * shell whose equator passes inside the absorber anywhere keeps nothing.
     */
    private double[] tabulateCutoffs() {
        Vector3d e1 = Math.abs(axis.x) < 0.9d ? new Vector3d(1d, 0d, 0d) : new Vector3d(0d, 1d, 0d);
        e1.fma(-e1.dot(axis), axis).normalize();
        Vector3d e2 = new Vector3d(axis).cross(e1);
        double[] table = new double[SHELL_TABLE_SIZE];
        for (int i = 0; i < SHELL_TABLE_SIZE; i++) {
            double shell = Math.pow(magnetopauseL, (double) i / (SHELL_TABLE_SIZE - 1));
            double weakest = Double.POSITIVE_INFINITY;
            for (int k = 0; k < DRIFT_LONGITUDES && weakest > 0d; k++) {
                double phi = 2d * Math.PI * k / DRIFT_LONGITUDES;
                Vector3d perp = new Vector3d(e1).mul(Math.cos(phi)).fma(Math.sin(phi), e2);
                if (distanceFromBodyCentre(shell, perp, 0d) <= absorbingRadiusM) {
                    weakest = 0d;
                } else {
                    weakest = Math.min(weakest, Math.min(footField(shell, perp, +1d), footField(shell, perp, -1d)));
                }
            }
            table[i] = weakest;
        }
        return table;
    }

    /**
     * |B| where the field line {@code shell} at drift direction {@code perp} reaches the absorbing
     * sphere, searching from its equator toward the pole of sign {@code hemisphere}. The absorbing sphere
     * is centred on the BODY, the field line on the DIPOLE, which is what makes the offset matter.
     */
    private double footField(double shell, Vector3dc perp, double hemisphere) {
        double lo = 0d;
        double hi = hemisphere * (Math.PI / 2d);
        for (int i = 0; i < FOOT_BISECTIONS; i++) {
            double mid = 0.5d * (lo + hi);
            if (distanceFromBodyCentre(shell, perp, mid) > absorbingRadiusM) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double lat = 0.5d * (lo + hi);
        double cos = Math.cos(lat);
        return fieldMagnitude(shell * bodyRadiusM * cos * cos, Math.sin(lat));
    }

    private double distanceFromBodyCentre(double shell, Vector3dc perp, double lat) {
        double cos = Math.cos(lat);
        double rad = shell * bodyRadiusM * cos * cos;
        return new Vector3d(perp).mul(rad * cos).fma(rad * Math.sin(lat), axis).add(offset).length();
    }
}
