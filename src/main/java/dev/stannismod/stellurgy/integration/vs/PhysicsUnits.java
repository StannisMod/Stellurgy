package dev.stannismod.stellurgy.integration.vs;

import dev.stannismod.stellurgy.api.StatsRocket;

/**
 * The one conversion between the flight model's SI and the physics engine's own units.
 *
 * <p>Both agree on the metre and the kilogram — a block is a metre, the mass table is in kilograms.
 * They disagree on the second. The engine integrates against WALL seconds, twenty ticks each, while
 * the game's own physics is SI at one tick = 90.3 ms: vanilla gravity, 0.08 blocks per tick², is 9.81
 * m/s² exactly at that tick. So one engine second is 1.806 SI seconds, and an acceleration in engine
 * units is the SI one times {@code 1.806² = 3.26} — the same factor that makes the engine's gravity
 * 32 and not 9.81.</p>
 *
 * <p>Derived from the two constants that already define it, so it cannot drift from them.</p>
 */
public final class PhysicsUnits {

    private PhysicsUnits() {}

    /** Vanilla ticks per wall second — what the engine's timestep is measured against. */
    private static final double TICKS_PER_WALL_SECOND = 20.0D;

    /** Engine acceleration per SI acceleration (linear or angular alike): 32 / 9.81. */
    public static final double ACCELERATION = StatsRocket.GRAVITY_BLOCKS_PER_TICK_SQUARED
            * TICKS_PER_WALL_SECOND * TICKS_PER_WALL_SECOND / StatsRocket.STANDARD_GRAVITY;

    /** SI seconds per engine second: 1.806. */
    public static final double SECONDS = Math.sqrt(ACCELERATION);
}
