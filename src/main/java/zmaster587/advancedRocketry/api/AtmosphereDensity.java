package zmaster587.advancedRocketry.api;

import net.minecraft.world.World;

import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

/**
 * How much air a world has, in atmospheres, for anything that has to charge a craft for moving
 * through it.
 *
 * <p>This exists so the two flight tiers cannot disagree. A rocket already asked this question its
 * own way and a ship's physics substrate did not ask it at all — it damped every craft's velocity
 * and spin by a flat per-tick constant everywhere, vacuum included. One answer, one place, and both
 * tiers read it.</p>
 *
 * <p><b>Absent means VACUUM, deliberately.</b> A dimension Advanced Rocketry knows nothing about has
 * no atmosphere as far as this is concerned. That is the same reading the rocket tier has always
 * used, and it is the safe one for the thing this governs: the failure it prevents is a craft in
 * space being slowed by air that is not there, which is a bug players can see. The opposite default
 * would quietly re-create it for every dimension a third-party mod adds.</p>
 */
public final class AtmosphereDensity {

    private AtmosphereDensity() { }

    /** Vacuum: no medium, so nothing may take a craft's momentum for free. */
    public static final double VACUUM = 0.0;

    /**
     * {@code world}'s atmospheric density in atmospheres, {@code 0.0} when it has none (or is not a
     * world this mod describes).
     *
     * <p>Reads the planet's {@code atmosphereDensity}, which is stored on a 0–100 scale, and returns
     * it as a fraction of one atmosphere. Altitude is deliberately NOT applied here: the rocket tier
     * has always used the whole-planet figure, and having the two tiers agree matters more than the
     * profile. Adding the altitude term (`getAtmosphereDensityAtHeight`) is a later, shared change.</p>
     */
    public static double inAtmospheres(World world) {
        if (world == null) {
            return VACUUM;
        }
        DimensionProperties properties = DimensionManager.getInstance()
                .getDimensionPropertiesOrNull(world.provider.getDimension());
        return properties == null ? VACUUM : properties.getAtmosphereDensity() / 100.0;
    }
}
