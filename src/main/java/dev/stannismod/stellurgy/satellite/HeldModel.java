package dev.stannismod.stellurgy.satellite;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The model of a field the player holds: what the dose rate is expected to be at a place, from the readings
 * he has downloaded. Pure.
 *
 * <p>Until a region holds a reading, the model is a uniform field — one dose rate for the whole orbit, the
 * mean of every reading held. A region that holds readings expects their mean. A reading's residual is its
 * value less that expectation, and is reported with the standard error of the readings behind it. Nothing
 * stores an explanation: holding the readings IS the model's term, so a reading where readings are already
 * held finds a residual within what they scatter by.</p>
 */
public final class HeldModel {

    /**
     * A region's side, radians of latitude and of longitude: the arc of orbit one segment spans
     * ({@link SatelliteAccrual#SEGMENTS_PER_PERIOD}), which is as fine as one reading tells places apart.
     */
    public static final double REGION_RAD = 2d * Math.PI / SatelliteAccrual.SEGMENTS_PER_PERIOD;

    private HeldModel() {
    }

    /**
     * A cell of a body's surface, counted from the south pole and from 180° west. The rows of cells are centred
     * on the equator, so an equatorial orbit — a release with no orbit stated — flies along the middle of one
     * row rather than along an edge between two. Immutable.
     */
    public static final class Region {
        public final int north;
        public final int east;

        Region(int north, int east) {
            this.north = north;
            this.east = east;
        }

        /** The cell's centre, degrees north. */
        public double centreNorthDeg() {
            return Math.toDegrees((north - ROWS / 2) * REGION_RAD);
        }

        /** The cell's centre, degrees east. */
        public double centreEastDeg() {
            return Math.toDegrees((east + 0.5d) * REGION_RAD - Math.PI);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Region && ((Region) o).north == north && ((Region) o).east == east;
        }

        @Override
        public int hashCode() {
            return Objects.hash(north, east);
        }

        @Override
        public String toString() {
            return "Region[" + north + "," + east + "]";
        }
    }

    /** Rows of cells from pole to pole: one on the equator and half the segments' worth on either side. */
    private static final int ROWS = SatelliteAccrual.SEGMENTS_PER_PERIOD / 2 + 1;

    /** The region holding {@code point}. */
    public static Region regionOf(Downlink.GroundPoint point) {
        int cells = SatelliteAccrual.SEGMENTS_PER_PERIOD;
        int north = (int) Math.floor(point.northRad / REGION_RAD + 0.5d) + ROWS / 2;
        int east = (int) Math.floor((point.eastRad + Math.PI) / REGION_RAD);
        // A pole sits on its row's far edge and belongs to it, as 180° east belongs to the last column.
        return new Region(Math.min(Math.max(north, 0), ROWS - 1), Math.min(Math.max(east, 0), cells - 1));
    }

    /** One held reading: where it was taken and the dose rate it read, Gy/s. Immutable. */
    public static final class Sample {
        public final Region region;
        public final double gyPerS;

        public Sample(Region region, double gyPerS) {
            this.region = region;
            this.gyPerS = gyPerS;
        }
    }

    /** What the model expects of a reading. */
    public enum Basis {
        /** The reading's own region held readings: their mean. */
        REGION,
        /** It held none: the uniform field, the mean of every reading held. */
        ORBIT
    }

    /** An expectation and how much it is known to. Immutable. */
    public static final class Expectation {
        public final Basis basis;
        public final double gyPerS;
        /** How many readings it is the mean of. */
        public final int readings;
        private final double standardError;

        Expectation(Basis basis, double gyPerS, int readings, double standardError) {
            this.basis = basis;
            this.gyPerS = gyPerS;
            this.readings = readings;
            this.standardError = standardError;
        }

        /** The standard error of the mean, Gy/s; empty behind fewer than two readings, where there is no scatter to take. */
        public OptionalDouble standardError() {
            return readings < 2 ? OptionalDouble.empty() : OptionalDouble.of(standardError);
        }

        /** A reading's residual against this expectation, Gy/s. */
        public double residualOf(double gyPerS) {
            return gyPerS - this.gyPerS;
        }
    }

    /**
     * What a reading in {@code region} is expected to read, from {@code held} — the OTHER readings held, never
     * the one being judged. Empty when nothing is held.
     */
    public static Optional<Expectation> expect(Region region, List<Sample> held) {
        Optional<Expectation> here = meanOf(Basis.REGION, region, held);
        return here.isPresent() ? here : meanOf(Basis.ORBIT, null, held);
    }

    private static Optional<Expectation> meanOf(Basis basis, Region only, List<Sample> held) {
        int n = 0;
        double sum = 0d;
        for (Sample s : held) {
            if (only == null || only.equals(s.region)) {
                n++;
                sum += s.gyPerS;
            }
        }
        if (n == 0) {
            return Optional.empty();
        }
        double mean = sum / n;
        double squares = 0d;
        for (Sample s : held) {
            if (only == null || only.equals(s.region)) {
                squares += (s.gyPerS - mean) * (s.gyPerS - mean);
            }
        }
        double error = n < 2 ? 0d : Math.sqrt(squares / (n - 1)) / Math.sqrt(n);
        return Optional.of(new Expectation(basis, mean, n, error));
    }
}
