package dev.stannismod.stellurgy.environment;

/**
 * A body's day as its turn and its star make it: the sidereal turn its solar day implies, and the local
 * solar time at a longitude at a tick of the space clock.
 *
 * <p>The star's place on the body's sky is set by the orbit that carries the body round its star — a
 * planet's own, a moon's planet's. In the body frame of {@link BodyRotation} that orbit's angle θ appears as
 * −θ, so the star stands at azimuth π − θ. Meridian 0 is at the frame's x axis at tick zero, and a place at
 * longitude λ (east positive, east being the way the body turns) is at {@code angle − λ}. Its hour angle is
 * that minus the star's azimuth; local solar time is the hour angle reversed, so it grows with time and is
 * zero at noon.</p>
 *
 * <p>Time of day is given in vanilla's world-time convention so everything that reads one keeps reading it:
 * a day is {@code solarDayTicks} long, 0 is sunrise and a quarter of the day is noon. Because the solar rate
 * is one turn per solar day by construction, a turning body's time of day advances exactly one tick per tick
 * of the space clock — it is the space tick plus a constant of the place. A body locked to its star has no
 * solar rate: its time of day is that constant.</p>
 */
public final class SolarDay {

    private static final double TURN = 2d * Math.PI;

    private final double solarDayTicks;
    private final boolean lockedToStar;
    private final double starOrbitRadPerTick;
    private final double starThetaAtZero;

    private SolarDay(double solarDayTicks, boolean lockedToStar, double starOrbitRadPerTick, double starThetaAtZero) {
        this.solarDayTicks = solarDayTicks;
        this.lockedToStar = lockedToStar;
        this.starOrbitRadPerTick = starOrbitRadPerTick;
        this.starThetaAtZero = starThetaAtZero;
    }

    /**
     * @param solarDayTicks    the body's solar day — its {@code rotationalPeriod}; for a body locked to its
     *                         star, only the scale its constant time of day is written in
     * @param lockedToStar     whether the body keeps one face to its star
     * @param starOrbitTicks   the period of the orbit that carries the body round its star; infinite when
     *                         there is none
     * @param retrograde       whether that orbit's angle falls with time
     * @param starThetaAtZero  that orbit's angle at tick zero, radians, as the ephemeris states it
     */
    public static SolarDay of(double solarDayTicks, boolean lockedToStar, double starOrbitTicks, boolean retrograde,
                              double starThetaAtZero) {
        if (!(solarDayTicks > 0d) || Double.isInfinite(solarDayTicks)) {
            throw new IllegalArgumentException("solar day must be a positive length: " + solarDayTicks);
        }
        double rate = starOrbitTicks > 0d && !Double.isInfinite(starOrbitTicks)
                ? (retrograde ? -TURN : TURN) / starOrbitTicks
                : 0d;
        return new SolarDay(solarDayTicks, lockedToStar, rate, starThetaAtZero);
    }

    /**
     * How the body turns: the sidereal rate is the solar rate plus the rate the star moves round its sky,
     * so a prograde body's sidereal period is shorter than its solar day; locked, it turns with its orbit.
     */
    public BodyRotation rotation() {
        double sidereal = solarRadPerTick() + starOrbitRadPerTick;
        return BodyRotation.of(sidereal == 0d ? Double.POSITIVE_INFINITY : TURN / sidereal, 0d);
    }

    /**
     * The solar day of a moon that keeps one face to its planet: its turn against the stars equals its orbit
     * round the planet ({@code moonOrbitTicks}), and its star moves round its sky with its planet's orbit
     * ({@code planetOrbitTicks}), both prograde — so 1/solar = 1/orbit − 1/planet's orbit. Infinite when the
     * two orbits take the same time.
     */
    public static double solarDayOfAMoonFacingItsPlanet(double moonOrbitTicks, double planetOrbitTicks) {
        double rate = 1d / moonOrbitTicks - 1d / planetOrbitTicks;
        return rate == 0d ? Double.POSITIVE_INFINITY : 1d / rate;
    }

    /** The time of day at {@code longitudeRad} at space tick {@code tick}, in vanilla world-time ticks. */
    public long timeOfDayAt(long tick, double longitudeRad) {
        double solarTimeAtZero = longitudeRad + Math.PI - starThetaAtZero;
        double offset = solarDayTicks * (solarTimeAtZero / TURN + 0.25d);
        offset = ((offset % solarDayTicks) + solarDayTicks) % solarDayTicks;
        return lockedToStar ? Math.round(offset) : tick + Math.round(offset);
    }

    private double solarRadPerTick() {
        return lockedToStar ? 0d : TURN / solarDayTicks;
    }
}
