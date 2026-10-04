package dev.stannismod.stellurgy.universe;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * How a body's physics is drawn from its cell — the second half of a world model, and the half a
 * player meets on the ground.
 *
 * <p><b>Why this is an interface at all.</b> A schema version is only worth having if an old save can
 * still be derived the old way, and the derivation is exactly where a later version wants to move: new
 * world types, a different mass law, another climate band. The generator seam alone could not carry
 * that — it says WHERE things are, not WHAT they are.
 *
 * <p><b>And why it costs nothing to thread.</b> The derivation has one real consumer, the generator,
 * which is already the object a schema hands out. So a version selects a derivation by selecting a
 * generator, and everything else reaches it through {@link IGalaxyGenerator#derivation()} rather than
 * through a static call that no version can intercept.
 *
 * <p>Every method must be a pure, deterministic function of its arguments, for the same reason
 * {@link IGalaxyGenerator}'s are: a scan and a later landing have to agree.
 */
public interface IBodyDerivation {

    /** The parent star's metal content relative to Sol, drawn once per system. */
    double metallicityOf(long seed, GalacticCoord anchor);

    /** The orbital distance a body of {@code star}'s system sits at, in Stellurgy distance units. */
    long referenceDistance(StellarBody star);

    /** Where body {@code index} of {@code count} sits around {@code star}. */
    long orbitalDistanceOf(long seed, GalacticCoord anchor, int index, int count, StellarBody star);

    /** The innermost orbit a body may hold around {@code star}. */
    double innerOrbit(StellarBody star);

    /** The outermost orbit a body may hold around {@code star}. */
    double outerOrbit(StellarBody star);

    /** The equilibrium temperature at {@code orbitalDistance}, before any atmosphere. */
    int bareTemperature(StellarBody star, long orbitalDistance);

    /** Whether a body at {@code orbitalDistance} keeps one face to its star. */
    boolean tidallyLockedAt(StellarBody star, long orbitalDistance);

    /** Whether body {@code index} accreted enough hydrogen to be a giant. */
    boolean isGiantAt(long seed, GalacticCoord anchor, int index, int bareTemperatureK);

    /** The full profile of a body BOUND to a star; {@code reports} is the deriving galaxy's. */
    BodyProfile derive(long seed, GalacticCoord anchor, GalacticCoord bodyCell, int variant,
                       StellarBody star, boolean moon, long orbitalDistance, ReportOnce reports);

    /** The full profile of an UNBOUND body — no star, no orbit, no insolation. */
    BodyProfile deriveRogue(long seed, GalacticCoord bodyCell, int variant, double giantFraction,
                            ReportOnce reports);

    /** What a body of this bulk still radiates with no star to warm it, in kelvin. */
    int residualTemperature(double massEarths, double radiusEarths);
}
