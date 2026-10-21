package dev.stannismod.stellurgy.environment;

import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Optional;

/**
 * The environment about one body: its magnetic field and the radiation its field traps, sampled at a
 * point and a moment, or accrued along a path.
 *
 * <p>Asked in the body-centred frame whose axes do not turn with the body, z along the spin axis. The
 * field is fixed to the body, so the body's rotation carries it round: the same point reads differently
 * an hour later. A query, never a store — nothing here ticks.</p>
 */
public final class PlanetEnvironment {

    /**
     * Samples taken per period of the path when accruing dose. A low orbit crosses its high-dose
     * segment in about a tenth of a period, so 64 put six or more samples inside it. A bound on work,
     * not a physical quantity.
     */
    private static final int SAMPLES_PER_PERIOD = 64;

    /** The body's field, or {@code null} for a body that keeps none. */
    private final PlanetaryDipole dipole;
    private final BodyRotation rotation;

    private PlanetEnvironment(PlanetaryDipole dipole, BodyRotation rotation) {
        this.dipole = dipole;
        this.rotation = rotation;
    }

    /**
     * The environment of a body with this field — or with none, when {@code dipole} is empty — turning
     * once every {@code rotationPeriodTicks}, at rotation angle {@code rotationPhaseRad} at tick zero.
     */
    public static PlanetEnvironment of(Optional<PlanetaryDipole> dipole, long rotationPeriodTicks,
                                       double rotationPhaseRad) {
        return new PlanetEnvironment(dipole.orElse(null), BodyRotation.of(rotationPeriodTicks, rotationPhaseRad));
    }

    /**
     * What is present at {@code position} (metres from the body's centre) at {@code tick}. About a body
     * with no field, nothing: no field and no trapped dose.
     */
    public LocalEnvironment sample(Vector3dc position, long tick) {
        if (dipole == null) {
            return LocalEnvironment.nothing();
        }
        double angle = rotationAngle(tick);
        Vector3d bodyFixed = new Vector3d(position).rotateZ(-angle);
        Vector3d field = dipole.fieldAt(bodyFixed, new Vector3d()).rotateZ(angle);
        return LocalEnvironment.of(field, dipole.trappedDoseRate(bodyFixed));
    }

    /**
     * The trapped-particle dose taken along {@code path} from {@code fromTick} to {@code toTick}, Gy in
     * silicon behind the reference shield. Each period of the path is resolved by a fixed number of
     * samples, so the work grows with the interval and never with the tick rate. About a body with no
     * field nothing is trapped, so nothing is accrued: zero, as the physical dose it is.
     */
    public double accruedDose(Trajectory path, long fromTick, long toTick) {
        if (dipole == null) {
            return 0d;
        }
        long step = Math.max(1L, path.periodTicks() / SAMPLES_PER_PERIOD);
        double dose = 0d;
        for (long t = fromTick; t < toTick; t += step) {
            long span = Math.min(step, toTick - t);
            long mid = t + span / 2L;
            Vector3d bodyFixed = new Vector3d(path.positionAt(mid)).rotateZ(-rotationAngle(mid));
            dose += dipole.trappedDoseRate(bodyFixed).totalGyPerS() * span * AstronomicalBodyHelper.SECONDS_PER_TICK;
        }
        return dose;
    }

    private double rotationAngle(long tick) {
        return rotation.angleAt(tick);
    }
}
