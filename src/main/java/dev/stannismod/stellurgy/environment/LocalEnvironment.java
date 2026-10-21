package dev.stannismod.stellurgy.environment;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What is physically present at one point at one moment — the single bundle a sample returns.
 *
 * <p>A member is EMPTY when this place has none of it, never a stand-in zero: a body with no magnetic
 * field reports no field and no trapped dose, which is a fact about the body, while a field-bearing
 * body's trapped dose of zero is a reading. Immutable.</p>
 */
public final class LocalEnvironment {

    private final Vector3d magneticField;
    private final TrappedDose trappedDose;

    private LocalEnvironment(Vector3d magneticField, TrappedDose trappedDose) {
        this.magneticField = magneticField;
        this.trappedDose = trappedDose;
    }

    static LocalEnvironment nothing() {
        return new LocalEnvironment(null, null);
    }

    static LocalEnvironment of(Vector3dc magneticField, TrappedDose trappedDose) {
        return new LocalEnvironment(new Vector3d(magneticField), trappedDose);
    }

    /** The magnetic field here, tesla, in the frame the sample was asked in; empty where there is none. */
    public Optional<Vector3dc> magneticField() {
        return magneticField == null ? Optional.empty() : Optional.of(new Vector3d(magneticField));
    }

    /**
     * The trapped-particle dose rate here, Gy/s in silicon behind the reference shield, both belts
     * together; empty where no field traps anything.
     */
    public OptionalDouble trappedDoseRate() {
        return trappedDose == null ? OptionalDouble.empty() : OptionalDouble.of(trappedDose.totalGyPerS());
    }

    /** The trapped-particle dose rate here, per belt; empty where no field traps anything. */
    public Optional<TrappedDose> trappedDose() {
        return Optional.ofNullable(trappedDose);
    }
}
