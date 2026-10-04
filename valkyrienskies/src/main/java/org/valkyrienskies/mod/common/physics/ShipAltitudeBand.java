package org.valkyrienskies.mod.common.physics;

import net.minecraft.world.World;
import org.valkyrienskies.mod.common.config.VSConfig;

import dev.stannismod.stellurgy.world.WorldRuntime;

/**
 * The world-frame altitude band a world's ships are clamped to each physics step: the configured
 * band ({@link VSConfig#shipLowerLimit} / {@link VSConfig#shipUpperLimit}), widened by whatever the
 * server has declared this world needs.
 *
 * <p>Owned by the world ({@link WorldRuntime}), not written into the config. The config is the
 * operator's setting; a widening is a fact about the worlds a particular server runs, and written
 * into the config it outlived that server — the next world a single-player client opened ran with
 * the previous one's band. Kept here it ends with the world, and a config edit still takes effect.</p>
 *
 * <p>Written by the server thread, read by the physics thread: the declared ends are volatile and
 * only ever widen.</p>
 */
public final class ShipAltitudeBand {

    private volatile double declaredFloor = Double.POSITIVE_INFINITY;
    private volatile double declaredCeiling = Double.NEGATIVE_INFINITY;

    public static ShipAltitudeBand of(World world) {
        return WorldRuntime.of(world, ShipAltitudeBand.class, ShipAltitudeBand::new);
    }

    /** Widen this world's band to cover at least {@code [floor, ceiling]}; never narrows it. */
    public void cover(double floor, double ceiling) {
        if (floor < declaredFloor) {
            declaredFloor = floor;
        }
        if (ceiling > declaredCeiling) {
            declaredCeiling = ceiling;
        }
    }

    public double lower() {
        return Math.min(VSConfig.shipLowerLimit, declaredFloor);
    }

    public double upper() {
        return Math.max(VSConfig.shipUpperLimit, declaredCeiling);
    }
}
