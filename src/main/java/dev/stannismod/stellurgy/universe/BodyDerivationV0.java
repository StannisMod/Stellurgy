package dev.stannismod.stellurgy.universe;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * Schema version 0's body derivation — every law exactly as {@link PlanetDerivation} states it.
 *
 * <p>A pure forwarder, and deliberately so: the arithmetic stays in one place, where its constants are
 * documented next to the observations they come from, and this class is only the handle a schema holds
 * it by. A version 2 is a second implementation of {@link IBodyDerivation}, not an edit here.
 *
 * <p>It carries one thing, the planet-type table its worlds are typed from, because that table is the
 * save's own (authored in its planet file). {@link #INSTANCE} carries the code-shipped table — the
 * derivation of a generator that has no save to read one from.
 */
public final class BodyDerivationV0 implements IBodyDerivation {

    /**
     * Effectively final, client/dedicated-server lifetime: built at class initialisation from the
     * code-shipped table, which is immutable, and never written again.
     */
    public static final BodyDerivationV0 INSTANCE = new BodyDerivationV0(PlanetTypes.stock());

    private final PlanetTypes types;

    /** Version 0's laws, typing worlds from {@code types}. */
    public BodyDerivationV0(PlanetTypes types) {
        this.types = types;
    }

    @Override
    public double metallicityOf(long seed, GalacticCoord anchor) {
        return PlanetDerivation.metallicityOf(seed, anchor);
    }

    @Override
    public long referenceDistance(StellarBody star) {
        return PlanetDerivation.referenceDistance(star);
    }

    @Override
    public long orbitalDistanceOf(long seed, GalacticCoord anchor, int index, int count,
                                 StellarBody star) {
        return PlanetDerivation.orbitalDistanceOf(seed, anchor, index, count, star);
    }

    @Override
    public double innerOrbit(StellarBody star) {
        return PlanetDerivation.innerOrbit(star);
    }

    @Override
    public double outerOrbit(StellarBody star) {
        return PlanetDerivation.outerOrbit(star);
    }

    @Override
    public int bareTemperature(StellarBody star, long orbitalDistance) {
        return PlanetDerivation.bareTemperature(star, orbitalDistance);
    }

    @Override
    public boolean tidallyLockedAt(StellarBody star, long orbitalDistance) {
        return PlanetDerivation.tidallyLockedAt(star, orbitalDistance);
    }

    @Override
    public boolean isGiantAt(long seed, GalacticCoord anchor, int index, int bareTemperatureK) {
        return PlanetDerivation.isGiantAt(seed, anchor, index, bareTemperatureK);
    }

    @Override
    public BodyProfile derive(long seed, GalacticCoord anchor, GalacticCoord bodyCell, int variant,
                              StellarBody star, boolean moon, long orbitalDistance) {
        return PlanetDerivation.derive(seed, anchor, bodyCell, variant, star, moon, orbitalDistance, types);
    }

    @Override
    public BodyProfile deriveRogue(long seed, GalacticCoord bodyCell, int variant,
                                   double giantFraction) {
        return PlanetDerivation.deriveRogue(seed, bodyCell, variant, giantFraction, types);
    }

    @Override
    public int residualTemperature(double massEarths, double radiusEarths) {
        return PlanetDerivation.residualTemperature(massEarths, radiusEarths);
    }
}
