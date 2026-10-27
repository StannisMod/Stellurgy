package dev.stannismod.stellurgy.probe;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;

/**
 * What a direction-finding receiver reads at a place: the bearing of the strongest transmitter it hears and
 * that transmitter's signal strength. A receiver hears strength; it has no reading of distance, and none is
 * given here.
 *
 * <p>Every transmitter is the probe's kind: a one-watt beacon on 108 MHz — the band the first satellites'
 * tracking beacons sent on — radiating alike in every direction, its signal falling with distance by the
 * free-space loss.</p>
 */
public final class RadioReading {

    /** The transmitter's power, dBm: one watt. */
    static final double TRANSMIT_DBM = 30d;
    /** Its frequency, Hz. */
    static final double FREQUENCY_HZ = 108e6d;
    /**
     * The free-space loss's constant for metres and hertz, dB: 20·log10(4π/c). Effectively final, process
     * lifetime: built once at class initialisation.
     */
    private static final double FREE_SPACE_DB = 20d * Math.log10(4d * Math.PI / 299_792_458d);
    /** The nearest a receiver is taken to be to a transmitter, metres: its own antenna's size. */
    private static final double NEAREST_M = 1d;

    /** The bearing of the strongest transmitter, radians from +x toward +z, as {@code atan2(dz, dx)}. */
    public final double bearingRad;
    /** Its signal strength at the receiver, dBm. */
    public final double strengthDbm;

    private RadioReading(double bearingRad, double strengthDbm) {
        this.bearingRad = bearingRad;
        this.strengthDbm = strengthDbm;
    }

    /**
     * What a receiver at ({@code x}, {@code y}, {@code z}) reads from {@code transmitters}, each sending from
     * its block's centre; empty when there is none to hear.
     */
    public static Optional<RadioReading> at(double x, double y, double z, List<BlockPos> transmitters) {
        BlockPos strongest = null;
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos t : transmitters) {
            double d2 = sq(t.getX() + 0.5d - x) + sq(t.getY() + 0.5d - y) + sq(t.getZ() + 0.5d - z);
            if (d2 < best) {
                best = d2;
                strongest = t;
            }
        }
        if (strongest == null) {
            return Optional.empty();
        }
        double distance = Math.max(NEAREST_M, Math.sqrt(best));
        double loss = 20d * Math.log10(distance) + 20d * Math.log10(FREQUENCY_HZ) + FREE_SPACE_DB;
        return Optional.of(new RadioReading(Math.atan2(strongest.getZ() + 0.5d - z, strongest.getX() + 0.5d - x),
                TRANSMIT_DBM - loss));
    }

    private static double sq(double v) {
        return v * v;
    }
}
