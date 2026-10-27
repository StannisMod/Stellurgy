package dev.stannismod.stellurgy.probe;

/**
 * A body falling through an atmosphere onto its planet: gravity toward the planet's centre and drag in an
 * air that thins exponentially with height, integrated in the plane of the fall from a point above the
 * surface until it meets the ground. Sampled once per server tick; nothing here touches a world.
 *
 * <p>The fall is integrated about a ROUND planet — gravity μ/r² toward its centre — and read back as a
 * downrange distance along the surface and a height above it, the two numbers a flat world can lay out. A
 * flat-plane integration would drop the one effect a re-entry is made of: an orbital speed that holds the
 * body up until drag takes it away.</p>
 *
 * <p>Time is the game's own: {@link #SECONDS_PER_TICK} seconds of flight to a tick, so a fall takes as long
 * to watch as it takes to happen.</p>
 *
 * <p>Precision: where nothing takes energy, the integration keeps the fall's specific orbital energy to a
 * billionth of its magnitude over {@link #MAX_TICKS}. Fourth-order steps of {@code 1/160} s against an orbit
 * of an hour or more leave a far smaller error; the stated bound is what a caller may rely on.</p>
 */
public final class EntryTrajectory {

    /** Seconds of flight per server tick: twenty ticks to the second, the rate everything that moves is drawn at. */
    public static final double SECONDS_PER_TICK = 0.05d;

    /**
     * Integration steps per tick. At the deepest drag a probe meets — its terminal fall near the ground —
     * drag halves its excess speed in about {@code 2β/(ρ·v)} seconds, seconds for any probe this class is
     * given, so a step of 1/160 s resolves it hundreds of times over. A bound on work.
     */
    private static final int STEPS_PER_TICK = 8;

    /**
     * The longest fall this class integrates, ticks: an hour. A fall that has not reached the ground by then
     * is refused rather than cut short, because a cut fall would land somewhere it never reached.
     */
    public static final int MAX_TICKS = 72_000;

    /** The planet and its air, the falling body, and the state it starts the fall in. Immutable. */
    public static final class Entry {
        final double bodyRadiusM;
        final double muM3S2;
        final double surfaceDensityKgM3;
        final double scaleHeightM;
        final double ballisticKgM2;
        final double startAltitudeM;
        final double startSpeedMS;
        final double startDescentRad;

        /**
         * @param bodyRadiusM        the planet's radius, metres, positive
         * @param surfaceGravityMS2  gravity at its surface, m/s², positive
         * @param surfaceDensityKgM3 its air's density at the surface, kg/m³; zero for a planet with none
         * @param scaleHeightM       the height over which that density falls by e, metres, positive
         * @param ballisticKgM2      the falling body's mass over its drag area times drag coefficient, kg/m², positive
         * @param startAltitudeM     the height the fall starts at, metres above the surface, positive
         * @param startSpeedMS       its speed there, m/s
         * @param startDescentRad    how far below the local horizontal it is moving there, radians
         */
        public Entry(double bodyRadiusM, double surfaceGravityMS2, double surfaceDensityKgM3, double scaleHeightM,
                     double ballisticKgM2, double startAltitudeM, double startSpeedMS, double startDescentRad) {
            if (!(bodyRadiusM > 0d) || !(surfaceGravityMS2 > 0d) || !(surfaceDensityKgM3 >= 0d)
                    || !(scaleHeightM > 0d) || !(ballisticKgM2 > 0d) || !(startAltitudeM > 0d)
                    || !(startSpeedMS >= 0d) || Double.isNaN(startDescentRad)) {
                throw new IllegalArgumentException("not a fall: radius " + bodyRadiusM + ", g " + surfaceGravityMS2
                        + ", density " + surfaceDensityKgM3 + ", scale height " + scaleHeightM + ", ballistic "
                        + ballisticKgM2 + ", altitude " + startAltitudeM + ", speed " + startSpeedMS
                        + ", descent " + startDescentRad);
            }
            this.bodyRadiusM = bodyRadiusM;
            this.muM3S2 = surfaceGravityMS2 * bodyRadiusM * bodyRadiusM;
            this.surfaceDensityKgM3 = surfaceDensityKgM3;
            this.scaleHeightM = scaleHeightM;
            this.ballisticKgM2 = ballisticKgM2;
            this.startAltitudeM = startAltitudeM;
            this.startSpeedMS = startSpeedMS;
            this.startDescentRad = startDescentRad;
        }

        /** The planet's gravitational parameter, m³/s²: its surface gravity times its radius squared. */
        public double muM3S2() {
            return muM3S2;
        }

        public double bodyRadiusM() {
            return bodyRadiusM;
        }

        public double surfaceGravityMS2() {
            return muM3S2 / (bodyRadiusM * bodyRadiusM);
        }

        public double surfaceDensityKgM3() {
            return surfaceDensityKgM3;
        }

        public double scaleHeightM() {
            return scaleHeightM;
        }

        public double ballisticKgM2() {
            return ballisticKgM2;
        }

        public double startAltitudeM() {
            return startAltitudeM;
        }

        public double startSpeedMS() {
            return startSpeedMS;
        }
    }

    private final double[] downrangeM;
    private final double[] altitudeM;
    private final double[] speedMS;

    private EntryTrajectory(double[] downrangeM, double[] altitudeM, double[] speedMS) {
        this.downrangeM = downrangeM;
        this.altitudeM = altitudeM;
        this.speedMS = speedMS;
    }

    /**
     * The fall from {@code entry} to the ground.
     *
     * @throws IllegalStateException when it has not reached the ground within {@link #MAX_TICKS}
     */
    public static EntryTrajectory fly(Entry entry) {
        double r0 = entry.bodyRadiusM + entry.startAltitudeM;
        // The plane of the fall: the planet's centre at the origin, the start straight up the y axis, downrange +x.
        double[] s = {0d, r0, entry.startSpeedMS * StrictMath.cos(entry.startDescentRad),
                -entry.startSpeedMS * StrictMath.sin(entry.startDescentRad)};
        double h = SECONDS_PER_TICK / STEPS_PER_TICK;
        double[] down = new double[1024];
        double[] alt = new double[1024];
        double[] speed = new double[1024];
        int n = 0;
        down[0] = 0d;
        alt[0] = entry.startAltitudeM;
        speed[0] = entry.startSpeedMS;
        while (true) {
            for (int step = 0; step < STEPS_PER_TICK; step++) {
                double[] next = rk4(entry, s, h);
                double altNext = radius(next) - entry.bodyRadiusM;
                if (altNext <= 0d) {
                    // The ground lies inside this step: take the point on it the step crosses, by its share of the step.
                    double altNow = radius(s) - entry.bodyRadiusM;
                    double f = altNow / (altNow - altNext);
                    for (int i = 0; i < 4; i++) {
                        next[i] = s[i] + (next[i] - s[i]) * f;
                    }
                    n++;
                    down = grow(down, n);
                    alt = grow(alt, n);
                    speed = grow(speed, n);
                    down[n] = downrange(entry, next);
                    alt[n] = 0d;
                    speed[n] = StrictMath.hypot(next[2], next[3]);
                    return new EntryTrajectory(trim(down, n), trim(alt, n), trim(speed, n));
                }
                s = next;
            }
            n++;
            if (n > MAX_TICKS) {
                throw new IllegalStateException("a fall from " + entry.startAltitudeM + " m at " + entry.startSpeedMS
                        + " m/s has not reached the ground in " + MAX_TICKS + " ticks; it is at "
                        + (radius(s) - entry.bodyRadiusM) + " m");
            }
            down = grow(down, n);
            alt = grow(alt, n);
            speed = grow(speed, n);
            down[n] = downrange(entry, s);
            alt[n] = radius(s) - entry.bodyRadiusM;
            speed[n] = StrictMath.hypot(s[2], s[3]);
        }
    }

    /** The ticks the fall takes: the last sample is on the ground. */
    public int ticks() {
        return altitudeM.length - 1;
    }

    /** Distance along the surface from under the start to under the body at {@code tick}, metres. */
    public double downrangeAt(int tick) {
        return downrangeM[tick];
    }

    /** Height above the surface at {@code tick}, metres; zero at the last tick. */
    public double altitudeAt(int tick) {
        return altitudeM[tick];
    }

    /** Speed at {@code tick}, m/s, in the frame the planet does not turn in. */
    public double speedAt(int tick) {
        return speedMS[tick];
    }

    /** Distance along the surface from under the start to where it lands, metres. */
    public double landingDownrange() {
        return downrangeM[ticks()];
    }

    private static double radius(double[] s) {
        return StrictMath.hypot(s[0], s[1]);
    }

    private static double downrange(Entry entry, double[] s) {
        return entry.bodyRadiusM * StrictMath.atan2(s[0], s[1]);
    }

    /** One classical Runge–Kutta step of {@code h} seconds over (x, y, vx, vy). */
    private static double[] rk4(Entry e, double[] s, double h) {
        double[] k1 = derivative(e, s);
        double[] k2 = derivative(e, offset(s, k1, h / 2d));
        double[] k3 = derivative(e, offset(s, k2, h / 2d));
        double[] k4 = derivative(e, offset(s, k3, h));
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = s[i] + h / 6d * (k1[i] + 2d * k2[i] + 2d * k3[i] + k4[i]);
        }
        return out;
    }

    private static double[] offset(double[] s, double[] k, double h) {
        return new double[]{s[0] + k[0] * h, s[1] + k[1] * h, s[2] + k[2] * h, s[3] + k[3] * h};
    }

    private static double[] derivative(Entry e, double[] s) {
        double r = radius(s);
        double gravity = -e.muM3S2 / (r * r * r);
        double v = StrictMath.hypot(s[2], s[3]);
        double height = Math.max(0d, r - e.bodyRadiusM);
        double rho = e.surfaceDensityKgM3 * StrictMath.exp(-height / e.scaleHeightM);
        double drag = -rho * v / (2d * e.ballisticKgM2);
        return new double[]{s[2], s[3], gravity * s[0] + drag * s[2], gravity * s[1] + drag * s[3]};
    }

    private static double[] grow(double[] a, int index) {
        if (index < a.length) {
            return a;
        }
        double[] bigger = new double[a.length * 2];
        System.arraycopy(a, 0, bigger, 0, a.length);
        return bigger;
    }

    private static double[] trim(double[] a, int last) {
        double[] out = new double[last + 1];
        System.arraycopy(a, 0, out, 0, last + 1);
        return out;
    }
}
