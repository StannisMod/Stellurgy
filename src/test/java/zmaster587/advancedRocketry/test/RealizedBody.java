package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space realize <cell> [variant]} — the world a cell's body was
 * realized into, and the properties it was given.
 *
 * <p>Measured 2026-09-17: three classes read this reply and between them spell eighteen of its
 * field names, which is the widest vocabulary of any producer left in the tier.</p>
 *
 * <h2>{@code ok:false} carries a REASON, and the four reasons are four different worlds</h2>
 *
 * <p>A malformed cell key, a variant the cell does not have, a cell with nothing landable in it, and
 * a dimension realized without properties are all {@code ok:false} — and only the first is a test
 * calling the verb wrongly. So {@link #of} refuses with the producer's own reason in the message
 * rather than leaving a caller to read {@code dim} out of a reply that has none.</p>
 *
 * <h2>{@code gravity} is not a multiplier here, and {@code moon} is half of an answer</h2>
 *
 * <p>The producer sends {@code Math.round(multiplier * 100)}, so this field is a PERCENTAGE of
 * Earth's — a reader that compared it against {@code 1.0} would be wrong by two orders of magnitude,
 * and the number looks plausible either way. And {@link #moon} is reported beside {@link #parent}
 * because a moon whose parent had no world was once written down as a planet at the parent's own
 * orbit: either field alone answers "ok" for a world that is wrong in the one way that matters.</p>
 */
public final class RealizedBody {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The dimension the body was realized into. */
    public final int dim;
    /** Its name, as AR holds it. */
    public final String name;
    /**
     * Where it sits, how big and how heavy it is, in AR's own units.
     *
     * <p>{@code mass} and {@code radius} are DOUBLES, and were {@code long} until the server gate
     * went red on 2026-09-17: {@code DimensionProperties.getMass()} / {@code getRadius()} are
     * {@code double}, and a small barren body masses {@code 0.0079} at radius {@code 0.27} — both
     * of which a {@code long} reads as ZERO. The test that caught it compares the realized body
     * against the scan that described it and reported <i>"expected 0.007887010583939688 but was
     * 0.0"</i> while BOTH replies in its own message carried the right number.</p>
     */
    public final int orbitalDist;
    public final double mass;
    public final double radius;
    /** Surface gravity as a PERCENTAGE of Earth's — see the class note. */
    public final int gravityPercent;
    /** Atmosphere density in AR units, and the average surface temperature. */
    public final int pressure;
    public final int temperature;
    /** Whether the atmosphere is breathable, and whether the body always shows one face to its star. */
    public final boolean oxygen;
    public final boolean tidallyLocked;
    /**
     * Ore richness, and whether the body is a gas giant (which has no surface to stand on).
     *
     * <p>{@code metallicity} is a FRACTION — {@code BodyProfile.metallicity()} is a {@code double}
     * and a real value is {@code 0.68} — so it was read as {@code 0} for the whole tier until the
     * same red exposed it.</p>
     */
    public final double metallicity;
    public final boolean gasGiant;
    /** Which generator the body asked for: {@code NATIVE}, {@code TEMPLATE}, {@code MOD_WORLDTYPE}. */
    public final String terrainSource;
    /** Whether it is a moon, and the dimension of the planet it hangs off. See the class note. */
    public final boolean moon;
    public final int parent;
    /** Whether a descent from orbit lands on THIS body of the cell's family. */
    public final boolean descendTarget;
    /** The star this body orbits. */
    public final int starId;

    private final String raw;

    private RealizedBody(Reply reply, String raw) {
        this.raw = raw;
        this.dim = reply.integer("dim");
        this.name = reply.text("name");
        this.orbitalDist = reply.integer("orbitalDist");
        this.mass = reply.number("mass");
        this.radius = reply.number("radius");
        this.gravityPercent = reply.integer("gravity");
        this.pressure = reply.integer("pressure");
        this.temperature = reply.integer("temperature");
        this.oxygen = reply.bool("oxygen");
        this.tidallyLocked = reply.bool("locked");
        this.metallicity = reply.number("metallicity");
        this.gasGiant = reply.bool("gasGiant");
        this.terrainSource = reply.text("terrainSource");
        this.moon = reply.bool("moon");
        this.parent = reply.integer("parent");
        this.descendTarget = reply.bool("descendTarget");
        this.starId = reply.integer("starId");
    }

    /**
     * Read one {@code space realize} reply, or refuse.
     *
     * <p>Refuses every {@code ok:false} shape, naming the producer's own reason: none of them
     * carries the properties below, and a caller reading {@code dim} out of one would be reading an
     * absence as a dimension id.</p>
     */
    public static RealizedBody of(String realizeReply) {
        String text = String.valueOf(realizeReply);
        Reply reply = Reply.of("artest space realize", text);
        if (!reply.has("ok")) {
            throw new AssertionError("this is not an `artest space realize` answer: it carries no"
                    + " `ok`, so nothing in it says whether a world was realized: " + text);
        }
        // absence is the answer twice over: the refusal shape carries no `ok`, and "the body was
        // not realized" is exactly what this branch reports; and the `reason` below is rendered
        // INTO that failure, where a refusal would replace the diagnosis with the reader's own.
        if (!reply.boolOr("ok", false)) {
            ArrangementFailure.arrangementFailed("`artest space realize` realized nothing ("
                    + reply.reported("reason") + "), so the properties this"
                    + " reading is about belong to no world: " + text);
        }
        return new RealizedBody(reply, text);
    }

    /** Realize the body of a cell named by its KEY, and read what it became. */
    public static RealizedBody at(Probe probe, String cellKey) throws Exception {
        return of(probe.exec("artest space realize " + cellKey));
    }

    /** Realize one NAMED member of a cell's family — the variant a caller means, never the default. */
    public static RealizedBody at(Probe probe, String cellKey, int variant) throws Exception {
        return of(probe.exec("artest space realize " + cellKey + " " + variant));
    }

    /**
     * Realize the body of the cell at a SECTOR-LOCAL address, which is the form a galaxy sweep
     * answers in ({@code sx}/{@code sy}/{@code sz}).
     */
    public static RealizedBody atSectorLocal(Probe probe, int sx, int sy, int sz) throws Exception {
        return of(probe.exec("artest space realize " + sx + " " + sy + " " + sz));
    }

    /**
     * Whether this reply is the verb's refusal, and what it said.
     *
     * <p>For a test whose SUBJECT is the refusal — a cell with nothing landable in it, a variant
     * that does not exist. Returns {@code null} when the reply is a realized world.</p>
     */
    public static String refusedBecause(String realizeReply) {
        String text = String.valueOf(realizeReply);
        Reply reply = Reply.of("artest space realize", text);
        if (!reply.has("ok")) {
            throw new AssertionError("this is not an `artest space realize` answer, so it is neither"
                    + " a refusal nor a world: " + text);
        }
        // absence is the answer, both halves: no `ok` means the refusal shape, and a refusal
        // without a stated reason is still a refusal.
        return reply.ok() ? null : reply.textOr("reason", "");
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "realized " + name + " dim=" + dim + " moon=" + moon + " parent=" + parent
                + " terrainSource=" + terrainSource;
    }
}
