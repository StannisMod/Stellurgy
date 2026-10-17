package dev.stannismod.stellurgy.api.propulsion;

import dev.stannismod.stellurgy.api.StatsRocket;

/**
 * The tier-1 classic ascent, one tick at a time: the climb the flight adds to its motion, and the
 * forecast that runs that same climb — with the drain law taking mass off every tick — to a line
 * (C10 STAT-33, STAT-34). The flight's own climb is {@link #climbPerTick}, so the forecast and the
 * flight are one law.
 *
 * <p>Motion is in blocks per tick, as the entity keeps it; one block is one metre and one tick is
 * {@link StatsRocket#TICK_SECONDS} of the flight's physics.</p>
 */
public final class ClassicAscent {

    private ClassicAscent() {
    }

    /** What a climb from a height does with the propellant aboard. */
    public static final class Forecast {
        /** Whether the climb reaches the line. */
        public final boolean reaches;
        /** The highest world Y it gets to; the line when it reaches. */
        public final double apexY;
        /** The line it was asked about. */
        public final double lineY;
        /** Its upward speed when the propellant ran out, m/s; the speed at the line if it got there burning. */
        public final double burnoutSpeed;

        Forecast(boolean reaches, double apexY, double lineY, double burnoutSpeed) {
            this.reaches = reaches;
            this.apexY = apexY;
            this.lineY = lineY;
            this.burnoutSpeed = burnoutSpeed;
        }
    }

    /**
     * The upward change of motion one tick of full thrust gives, blocks/tick per tick: the net specific
     * force {@code (F − m·g₀·gravity)/m} in standard gravities, times what one standard gravity is worth
     * per tick — so a craft with no thrust falls exactly as fast as a player.
     */
    public static double climbPerTick(double thrust, double massKg, double weightGravity) {
        if (massKg <= 0) {
            return 0;
        }
        double net = thrust - massKg * StatsRocket.STANDARD_GRAVITY * weightGravity;
        return net / massKg / StatsRocket.STANDARD_GRAVITY * StatsRocket.GRAVITY_BLOCKS_PER_TICK_SQUARED;
    }

    /**
     * Runs the climb from {@code fromY}: each burning tick throws out {@code thrust·τ/v_e} of the
     * propellant and then climbs on the lighter craft (the flight's order), and once dry the craft
     * coasts, losing {@code 0.08·fallGravity} blocks/tick of motion a tick, until it reaches
     * {@code lineY} or stops rising. A craft that cannot climb while burning stops at once.
     *
     * @param weightGravity the gravity its weight is taken at (the launch gate's), standard gravities
     * @param fallGravity   the gravity it coasts under, standard gravities
     */
    public static Forecast run(double fromY, double lineY, double thrust, double exhaustVelocity,
                               double massKg, double propellantKg, double weightGravity, double fallGravity) {
        double perTick = StatsRocket.ejectedMassPerTick(thrust, exhaustVelocity);
        double propellant = Math.max(0, propellantKg);
        double mass = massKg;
        double y = fromY;
        double motion = 0;
        double burnout = -1;
        double fall = StatsRocket.GRAVITY_BLOCKS_PER_TICK_SQUARED * fallGravity;
        while (y < lineY) {
            if (propellant > 0 && perTick > 0) {
                double burnt = Math.min(propellant, perTick);
                propellant -= burnt;
                mass -= burnt;
                motion += climbPerTick(thrust, mass, weightGravity);
                if (motion <= 0) {
                    return new Forecast(false, y, lineY, 0);
                }
            } else {
                if (burnout < 0) {
                    burnout = motion / StatsRocket.TICK_SECONDS;
                }
                motion -= fall;
                if (motion <= 0) {
                    return new Forecast(false, y, lineY, burnout);
                }
                if (!(fall > 0)) {
                    // No gravity to stop a rising craft: it gets there.
                    return new Forecast(true, lineY, lineY, burnout);
                }
            }
            y += motion;
        }
        return new Forecast(true, lineY, lineY, burnout < 0 ? motion / StatsRocket.TICK_SECONDS : burnout);
    }
}
