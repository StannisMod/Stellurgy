package zmaster587.advancedRocketry.universe;

/**
 * Schema version 0 ("0.1", the ALPHA) — the clustered galaxy field as first released: nested galaxy and star lattices,
 * cluster sub-lattices, seated nebulae, the unbound population out in the void, and the body
 * derivation those systems are filled with.
 *
 * <p>Deliberately thin. A schema version exists to be NAMED and found again, not to hold logic; the
 * behaviour lives in the classes it selects, and this class is the record that this particular set of
 * them was once shipped.
 *
 * <p><b>The zero is a promise about maturity, not a placeholder.</b> This model may be replaced outright
 * in a later release rather than extended, so a world generated under it is not guaranteed a future —
 * and the player is told exactly that when he loads one.
 *
 * <h2>What has moved UNDER this version, and what that costs a save</h2>
 *
 * <p>That promise is exercised rather than theoretical: this version is edited in place instead of
 * being frozen and succeeded. A save keeps what has been TOUCHED and re-derives the rest, so a change
 * here moves systems nobody has visited in worlds that already exist. Each such change is recorded
 * beside the version, because a player's world is not explained by a file's history:</p>
 *
 * <ul>
 *   <li><b>2026-08-24 — the lunar scale, and the law that reads it.</b> A moon's period was anchored
 *       at "8 days at 100 units", a reference that reads a moon's distance in ASTRONOMICAL units
 *       while the layout writes it in 200-block moon-units; the two only ever agreed because the
 *       shipped Moon carried a distance 51 times too small. The law is now anchored on the Moon
 *       itself — 27.32 days at 7 688 units — and the Moon sits at its real 384 400 km. A moon lifted
 *       by the minimum-orbit floor also stopped keeping the period of the orbit it was AUTHORED on
 *       rather than the one it is on. <b>Measured blast radius: 34 bodies in the golden corpus,
 *       every one of them a MOON</b>; no planet, star or belt moved.</li>
 * </ul>
 */
public final class UniverseSchemaV0 implements UniverseSchema {

    public static final int VERSION = 0;

    /** The alpha, and its leading zero says so. */
    public static final String LABEL = "0.1";

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public IUniverseLaws laws() {
        return UniverseLawsV0.INSTANCE;
    }

    @Override
    public IGalaxyGenerator generator(GalaxyGenConfig config) {
        return (config == null)
                ? new EmptyGalaxyGenerator()
                : new ClusteredGalaxyGenerator(config, BodyDerivationV0.INSTANCE, laws());
    }
}
