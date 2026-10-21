package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.LocalEnvironment;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.environment.TrappedDose;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SplittableRandom;

/**
 * What a satellite on its orbit gains and suffers over time, computed when it is asked for and never
 * ticked: the power its panels give while the star is in view, the data its instrument collects with that
 * power, the dose its parts take from the field it passes through, the data points that dose destroys,
 * the parts it kills, and what a dosimeter reads.
 *
 * <p><b>Time is cut into a fixed grid</b> — {@link #SEGMENTS_PER_PERIOD} segments per orbit, counted from
 * the orbit's epoch — and only whole segments are ever taken. Each segment is evaluated at its middle,
 * and its chance is drawn from a generator seeded by the satellite and the segment's index. So the
 * satellite's state at a tick does not depend on how often anyone asked: one catch-up over a month and a
 * month of catch-ups every second give the same answer.</p>
 */
public final class SatelliteAccrual {

    /**
     * Segments per period of the orbit. A low orbit crosses its high-dose segment in about a tenth of a
     * period, so 64 put six or more segments inside it. A bound on work, not a physical quantity.
     */
    public static final int SEGMENTS_PER_PERIOD = 64;
    /** What every satellite spends on keeping itself alive, per tick, before any instrument: its upkeep. */
    public static final double UPKEEP_PER_TICK = 1d;
    /**
     * The share of its arrays' power an instrument draws while it collects. A sizing rule, not a
     * measurement: an array is sized so its orbit average covers the instrument through the longest
     * eclipse of a low orbit (about 40 % of the period), which leaves the sunlit half to charge the battery.
     */
    public static final double INSTRUMENT_SHARE_OF_GENERATION = 0.5d;
    /**
     * Ticks between the star directions an eclipse is judged against. The star moves about a degree a day
     * across a planet's sky, so a tenth of a day keeps it within a tenth of a degree; the grid is fixed,
     * so the answer does not depend on when it is asked. A bound on work.
     */
    static final long STAR_STEP_TICKS = AstronomicalBodyHelper.TICKS_PER_DAY / 10L;

    private SatelliteAccrual() {
    }

    /** What a part is to the dose. */
    public enum PartKind {
        /** Stops past its budget; the primary function's death stops the satellite. */
        ELECTRONICS,
        /** Its power falls with dose. */
        POWER_GENERATION,
        /** Thins the dose reaching what is inside the chassis; takes no wear itself. */
        SHIELD,
        /** Takes no wear: the battery. */
        INERT
    }

    /** One part as the dose sees it. Its dose is the only thing that changes. */
    public static final class Part {
        final int slot;
        final PartKind kind;
        final boolean primary;
        final double nominalPowerPerTick;
        final int dataCapacity;
        double doseGy;

        public Part(int slot, PartKind kind, boolean primary, double nominalPowerPerTick, int dataCapacity, double doseGy) {
            this.slot = slot;
            this.kind = kind;
            this.primary = primary;
            this.nominalPowerPerTick = nominalPowerPerTick;
            this.dataCapacity = dataCapacity;
            this.doseGy = doseGy;
        }

        public int slot() {
            return slot;
        }

        public double doseGy() {
            return doseGy;
        }

        /** Whether this part still does its job under {@code rules}. */
        public boolean works(Rules rules) {
            if (!rules.wearEnabled) {
                return true;
            }
            switch (kind) {
                case ELECTRONICS:
                    return RadiationWear.electronicsWork(doseGy);
                case POWER_GENERATION:
                    return RadiationWear.panelPowerFraction(doseGy) > 0d;
                default:
                    return true;
            }
        }

        /** The power this part gives in full light, per tick, under {@code rules}. */
        public double powerPerTick(Rules rules) {
            if (kind != PartKind.POWER_GENERATION) {
                return 0d;
            }
            return rules.wearEnabled ? nominalPowerPerTick * RadiationWear.panelPowerFraction(doseGy) : nominalPowerPerTick;
        }
    }

    /** Whether the dose wears parts at all, and how fast. */
    public static final class Rules {
        final boolean wearEnabled;
        final double wearScale;

        public Rules(boolean wearEnabled, double wearScale) {
            this.wearEnabled = wearEnabled;
            this.wearScale = wearScale;
        }
    }

    /** Toward the star from the body's centre, a unit vector in the orbit's frame; empty where no star shines. */
    public interface StarDirection {
        Optional<Vector3dc> at(long tick);
    }

    /** What the satellite is about: its orbit, its body's environment, its star, the body's size. */
    public static final class Body {
        final SatelliteOrbit orbit;
        /** Empty when the body's environment could not be derived; a body with no field is a present environment that holds nothing. */
        final Optional<PlanetEnvironment> environment;
        final StarDirection star;
        final double radiusM;

        public Body(SatelliteOrbit orbit, Optional<PlanetEnvironment> environment, StarDirection star, double radiusM) {
            this.orbit = orbit;
            this.environment = environment;
            this.star = star;
            this.radiusM = radiusM;
        }
    }

    /** What the primary function does with power. */
    public static final class Payload {
        final boolean collectsData;
        final boolean readsDose;
        /** Ticks of full-power collection per data point. */
        final double collectionTicks;

        public Payload(boolean collectsData, boolean readsDose, double collectionTicks) {
            this.collectsData = collectsData;
            this.readsDose = readsDose;
            this.collectionTicks = collectionTicks;
        }
    }

    /** The satellite's running state: what the grid has reached and what it holds. */
    public static final class State {
        long segmentsDone;
        double energy;
        double energyCap;
        long data;
        double collectionCarry;
        final FailureJournal journal;
        /** {@code null} for a satellite that carries no dosimeter. */
        final DoseReadings readings;

        public State(long segmentsDone, double energy, double energyCap, long data, double collectionCarry,
                     FailureJournal journal, DoseReadings readings) {
            this.segmentsDone = segmentsDone;
            this.energy = energy;
            this.energyCap = energyCap;
            this.data = data;
            this.collectionCarry = collectionCarry;
            this.journal = journal;
            this.readings = readings;
        }

        public long segmentsDone() {
            return segmentsDone;
        }

        public double energy() {
            return energy;
        }

        public long data() {
            return data;
        }

        public double collectionCarry() {
            return collectionCarry;
        }

        public FailureJournal journal() {
            return journal;
        }

        /** {@code null} for a satellite that carries no dosimeter. */
        public DoseReadings readings() {
            return readings;
        }
    }

    /** The length of one segment of {@code orbit}, ticks. */
    public static long segmentTicks(SatelliteOrbit orbit) {
        return Math.max(1L, orbit.periodTicks() / SEGMENTS_PER_PERIOD);
    }

    /**
     * Take every whole segment that has ended by {@code now}. Returns whether a part stopped working, so
     * the caller re-sums what the satellite's parts add up to.
     */
    public static boolean advance(Body body, List<Part> parts, Payload payload, State state, Rules rules,
                                  long satelliteId, long now) {
        SatelliteOrbit orbit = body.orbit;
        long step = segmentTicks(orbit);
        double seconds = step * AstronomicalBodyHelper.SECONDS_PER_TICK;
        boolean shielded = false;
        for (Part part : parts) {
            shielded |= part.kind == PartKind.SHIELD;
        }
        boolean partStopped = false;
        long starTick = Long.MIN_VALUE;
        Optional<Vector3dc> star = Optional.empty();

        while (orbit.epochTick() + (state.segmentsDone + 1L) * step <= now) {
            long mid = orbit.epochTick() + state.segmentsDone * step + step / 2L;
            double phase = orbit.phaseAt(mid);
            Vector3dc position = orbit.positionAt(mid);
            long quantized = Math.floorDiv(mid, STAR_STEP_TICKS) * STAR_STEP_TICKS;
            if (quantized != starTick) {
                starTick = quantized;
                star = body.star.at(quantized);
            }
            Optional<LocalEnvironment> here = body.environment.map(env -> env.sample(position, mid));
            Optional<TrappedDose> dose = here.flatMap(LocalEnvironment::trappedDose);

            Optional<Part> primary = primaryOf(parts);
            boolean functioning = primary.isPresent() && primary.get().works(rules);
            long dataCap = dataCapacity(parts, rules);

            // Losses fall on what was buffered when the segment began.
            double protonGy = 0d;
            double electronGy = 0d;
            if (rules.wearEnabled && dose.isPresent()) {
                protonGy = dose.get().protonGyPerS() * seconds * rules.wearScale;
                electronGy = dose.get().electronGyPerS() * seconds * rules.wearScale;
            }
            double memoryProtons = RadiationWear.insideProtons(protonGy, shielded);
            if (state.data > 0L && memoryProtons > 0d) {
                int lost = binomial(new SplittableRandom(seedOf(satelliteId, state.segmentsDone)),
                        state.data, RadiationWear.pointLossProbability(memoryProtons));
                if (lost > 0) {
                    state.data -= lost;
                    state.journal.record(mid, phase, FailureJournal.Kind.DATA_LOST, lost, -1);
                }
            }

            // Power and collection, from the parts as they stood when the segment began.
            double generation = sunlit(position, star, body.radiusM) ? generation(parts, rules) : 0d;
            double available = state.energy + (generation - UPKEEP_PER_TICK) * step;
            boolean collecting = functioning && payload.collectsData && state.data < dataCap;
            double collectedTicks = 0d;
            if (collecting) {
                double need = INSTRUMENT_SHARE_OF_GENERATION * nominalGeneration(parts, rules) * step;
                if (available >= need) {
                    collectedTicks = step;
                    available -= need;
                } else {
                    collectedTicks = need > 0d ? step * Math.max(0d, available) / need : 0d;
                    available = 0d;
                }
            }
            state.energy = Math.min(state.energyCap, Math.max(0d, available));
            if (collectedTicks > 0d && payload.collectionTicks > 0d) {
                state.collectionCarry += collectedTicks;
                long points = (long) Math.floor(state.collectionCarry / payload.collectionTicks);
                state.collectionCarry -= points * payload.collectionTicks;
                state.data = Math.min(dataCap, state.data + points);
            }

            if (functioning && payload.readsDose && here.isPresent()) {
                OptionalDouble rate = here.get().trappedDoseRate();
                state.readings.record(mid, phase, rate);
            }

            // Wear, and the parts it stops.
            if (protonGy > 0d || electronGy > 0d) {
                double inside = memoryProtons + RadiationWear.insideElectrons(electronGy, shielded);
                for (Part part : parts) {
                    boolean worked = part.works(rules);
                    if (part.kind == PartKind.ELECTRONICS) {
                        part.doseGy += inside;
                    } else if (part.kind == PartKind.POWER_GENERATION) {
                        part.doseGy += protonGy + electronGy;
                    }
                    if (worked && !part.works(rules)) {
                        partStopped = true;
                        state.journal.record(mid, phase, FailureJournal.Kind.PART_FAILED, 1, part.slot);
                    }
                }
                long newCap = dataCapacity(parts, rules);
                if (state.data > newCap) {
                    state.journal.record(mid, phase, FailureJournal.Kind.DATA_OVERFLOWED, (int) (state.data - newCap), -1);
                    state.data = newCap;
                }
            }
            state.segmentsDone++;
        }
        return partStopped;
    }

    /** Whether a point {@code position} from the body's centre sees the star: outside the body's cylindrical shadow. */
    static boolean sunlit(Vector3dc position, Optional<Vector3dc> star, double radiusM) {
        if (!star.isPresent()) {
            return false;
        }
        double along = position.dot(star.get());
        if (along >= 0d) {
            return true;
        }
        Vector3d across = new Vector3d(star.get()).mul(-along).add(position);
        return across.length() >= radiusM;
    }

    private static Optional<Part> primaryOf(List<Part> parts) {
        for (Part part : parts) {
            if (part.primary) {
                return Optional.of(part);
            }
        }
        return Optional.empty();
    }

    private static double generation(List<Part> parts, Rules rules) {
        double sum = 0d;
        for (Part part : parts) {
            sum += part.powerPerTick(rules);
        }
        return sum;
    }

    /** What the working panels were built to give: the instrument is sized against it, and their fading does not shrink it. */
    private static double nominalGeneration(List<Part> parts, Rules rules) {
        double sum = 0d;
        for (Part part : parts) {
            if (part.kind == PartKind.POWER_GENERATION && part.works(rules)) {
                sum += part.nominalPowerPerTick;
            }
        }
        return sum;
    }

    private static long dataCapacity(List<Part> parts, Rules rules) {
        long sum = 0L;
        for (Part part : parts) {
            if (part.dataCapacity > 0 && part.works(rules)) {
                sum += part.dataCapacity;
            }
        }
        return sum;
    }

    private static long seedOf(long satelliteId, long segment) {
        return satelliteId * 0x9E3779B97F4A7C15L + segment * 0xC2B2AE3D27D4EB4FL;
    }

    /**
     * A binomial draw of {@code n} trials at {@code p}: by inversion while the expected count is small, by
     * the normal approximation once it is large enough for that to hold.
     */
    static int binomial(SplittableRandom random, long n, double p) {
        if (p <= 0d || n <= 0L) {
            return 0;
        }
        if (p >= 1d) {
            return (int) Math.min(Integer.MAX_VALUE, n);
        }
        double mean = n * p;
        if (mean > 30d) {
            double sd = Math.sqrt(mean * (1d - p));
            double u1 = random.nextDouble();
            double u2 = random.nextDouble();
            double gauss = Math.sqrt(-2d * Math.log(1d - u1)) * Math.cos(2d * Math.PI * u2);
            return (int) Math.max(0L, Math.min(n, Math.round(mean + sd * gauss)));
        }
        double u = random.nextDouble();
        double pmf = Math.pow(1d - p, n);
        double cdf = pmf;
        int k = 0;
        double ratio = p / (1d - p);
        // Past the mean a vanished probability can only be rounding: the tail beyond it is nothing.
        while (u > cdf && k < n && !(pmf == 0d && k > mean)) {
            pmf *= (n - k) * ratio / (k + 1);
            k++;
            cdf += pmf;
        }
        return k;
    }
}
