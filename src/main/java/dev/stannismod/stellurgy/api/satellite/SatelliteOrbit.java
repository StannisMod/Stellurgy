package dev.stannismod.stellurgy.api.satellite;

import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.Trajectory;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import net.minecraft.nbt.NBTTagCompound;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A satellite's circular orbit about its body: radius, inclination, the longitude of the ascending node
 * and the tick it was put there. Only these are held; where the satellite is along the orbit — its phase
 * — is computed from them and the clock, so nothing ticks.
 *
 * <p>Positions are in the body-centred frame whose axes do not turn with the body, z along the spin
 * axis — the frame the environment is asked in.</p>
 */
public final class SatelliteOrbit implements Trajectory {

    /** Earth's standard gravitational parameter, m³/s² (IERS conventions). A body's is this times its mass in Earth masses. */
    public static final double EARTH_MU_M3_S2 = 3.986_004_418e14d;

    private final double radiusM;
    private final double inclinationRad;
    private final double nodeRad;
    private final double muM3S2;
    private final long epochTick;

    private SatelliteOrbit(double radiusM, double inclinationRad, double nodeRad, double muM3S2, long epochTick) {
        if (!(radiusM > 0d) || !(muM3S2 > 0d)) {
            throw new IllegalArgumentException("an orbit needs a positive radius and body: r=" + radiusM + " mu=" + muM3S2);
        }
        this.radiusM = radiusM;
        this.inclinationRad = inclinationRad;
        this.nodeRad = nodeRad;
        this.muM3S2 = muM3S2;
        this.epochTick = epochTick;
    }

    /**
     * The orbit a satellite released at {@code tick} is put on: radius {@code radiusM} from the body's
     * centre, inclined by {@code inclinationRad}, about a body of gravitational parameter {@code muM3S2}.
     * Its ascending node lies under the body's meridian 0 at that tick and the satellite starts at the
     * node: a planet-side world gives a launch pad no latitude or longitude to start above.
     */
    public static SatelliteOrbit releasedAt(double radiusM, double inclinationRad, double muM3S2,
                                            BodyRotation rotation, long tick) {
        return new SatelliteOrbit(radiusM, inclinationRad, rotation.angleAt(tick), muM3S2, tick);
    }

    public double radiusM() {
        return radiusM;
    }

    public double inclinationRad() {
        return inclinationRad;
    }

    public long epochTick() {
        return epochTick;
    }

    /** Angular rate along the orbit, radians per tick. */
    private double meanMotionPerTick() {
        return Math.sqrt(muM3S2 / (radiusM * radiusM * radiusM)) * AstronomicalBodyHelper.SECONDS_PER_TICK;
    }

    /** Where along the orbit the satellite is at {@code tick}: the angle from the ascending node, in {@code [0, 2π)}. */
    public double phaseAt(long tick) {
        double turns = meanMotionPerTick() * (tick - epochTick) / (2d * Math.PI);
        return 2d * Math.PI * (turns - Math.floor(turns));
    }

    @Override
    public Vector3dc positionAt(long tick) {
        double u = phaseAt(tick);
        double cosU = Math.cos(u);
        double sinU = Math.sin(u);
        double cosO = Math.cos(nodeRad);
        double sinO = Math.sin(nodeRad);
        double cosI = Math.cos(inclinationRad);
        return new Vector3d(cosO * cosU - sinO * sinU * cosI,
                sinO * cosU + cosO * sinU * cosI,
                sinU * Math.sin(inclinationRad)).mul(radiusM);
    }

    @Override
    public long periodTicks() {
        return Math.max(1L, Math.round(2d * Math.PI / meanMotionPerTick()));
    }

    /**
     * Where the satellite stands in its body's cell, metres from the body's centre in the same frame: its
     * place on the orbit at release. The cell's frame flies the orbit, so this does not move with the phase.
     */
    public Vector3dc standingOffset() {
        return positionAt(epochTick);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setDouble("radius", radiusM);
        nbt.setDouble("inclination", inclinationRad);
        nbt.setDouble("node", nodeRad);
        nbt.setDouble("mu", muM3S2);
        nbt.setLong("epoch", epochTick);
    }

    public static SatelliteOrbit readFromNBT(NBTTagCompound nbt) {
        return new SatelliteOrbit(nbt.getDouble("radius"), nbt.getDouble("inclination"), nbt.getDouble("node"),
                nbt.getDouble("mu"), nbt.getLong("epoch"));
    }
}
