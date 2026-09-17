package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest weather get <dim>} — what one world's sky is doing, and which
 * {@code WorldInfo} is answering for it.
 *
 * <p>Measured 2026-09-17: TEN classes read this reply — the widest producer in the suite by class
 * count — and between them they spelled {@code "\"isRaining\":true"} twenty-two times,
 * {@code "\"isRaining\":false"} four times, and the strengths as rendered decimals.</p>
 *
 * <h2>The error reply carries {@code dim}, and a NEGATED substring passes on it</h2>
 *
 * <p>{@code weather get} answers {@code {"error":"world not loaded","dim":N}} for a world it could
 * not bring up. Every field below is absent there — so
 * {@code assertFalse(reply.contains("\"isRaining\":true"))}, which is how this tier states "the sky
 * over dim N must be clear", is SATISFIED by that error. Sixteen of the sites this reader replaces
 * were of exactly that shape: a claim about a world, provable by the world not existing. {@link #of}
 * refuses the error instead.</p>
 *
 * <h2>A strength is a FLOAT, and the substring tests on it were wrong in two ways</h2>
 *
 * <p>{@code "\"thunderStrength\":0.0"} is a PREFIX: it is satisfied by {@code 0.05}, so an assertion
 * that a fresh planet is born with no thunder passed for any strength below {@code 0.1}. And
 * {@code "\"rainStrength\":0.0,"} — the same test's neighbour — pins the TRAILING COMMA, which is to
 * say it pins that {@code rainStrength} is not the last field the producer writes. Both are read here
 * as numbers.</p>
 *
 * <h2>{@code isRaining} and {@code rainStrength} are different questions</h2>
 *
 * <p>Vanilla's {@code isRaining()} is a boolean the world keeps; the strength is a per-tick ramp
 * toward it, and {@code World.isRainingAt} crosses its own threshold at {@code 0.2}. A test that
 * sets rain and reads the strength immediately is reading the ramp's first frame, which is why the
 * two are separate fields and are kept separate here.</p>
 */
public final class DimWeather {

    /** How this reader reaches the probe. The callers sit under four different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Which dimension this reading is about, as the reply echoed it back. */
    public final int dim;
    /** Whether the world says it is raining — the boolean, not the ramp. See the class note. */
    public final boolean raining;
    /** Whether it says it is thundering. */
    public final boolean thundering;
    /** How long the current rain and thunder still have to run, in ticks. */
    public final int rainTime;
    public final int thunderTime;
    /** How long the world is FORCED clear for — while this is above zero nothing may rain. */
    public final int cleanWeatherTime;
    /** The rendered strengths: the per-tick ramp toward {@link #raining} and {@link #thundering}. */
    public final double rainStrength;
    public final double thunderStrength;

    private final Reply reply;
    private final String raw;

    private DimWeather(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.dim = reply.integer("dim");
        this.raining = reply.bool("isRaining", false);
        this.thundering = reply.bool("isThundering", false);
        this.rainTime = reply.integer("rainTime");
        this.thunderTime = reply.integer("thunderTime");
        this.cleanWeatherTime = reply.integer("cleanWeatherTime");
        this.rainStrength = reply.number("rainStrength");
        this.thunderStrength = reply.number("thunderStrength");
    }

    /**
     * Read one {@code weather get} reply, or refuse.
     *
     * <p>Refuses the {@code world not loaded} shape. See the class note: that reply echoes the
     * {@code dim} back and carries nothing else, so read as a weather reading it is a world that is
     * neither raining nor thundering, with no rain queued and no forced-clear window — a perfectly
     * ordinary clear sky.</p>
     */
    public static DimWeather of(String weatherReply) {
        String text = String.valueOf(weatherReply);
        Reply reply = Reply.of("artest weather get", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest weather get` could not bring the world up"
                    + " (" + reply.text("error") + "), so there is no sky to read — and read as one,"
                    + " this reply is a clear day: " + text);
        }
        if (!reply.has("isRaining") || !reply.has("worldInfoClass")) {
            throw new AssertionError("this is not an `artest weather get` answer: a weather reading"
                    + " carries `isRaining` beside the `worldInfoClass` that produced it: " + text);
        }
        return new DimWeather(reply, text);
    }

    /** Ask the server what one world's sky is doing. */
    public static DimWeather forDim(Probe probe, int dim) throws Exception {
        return of(probe.exec("artest weather get " + dim));
    }

    /** The FQN of the {@code WorldInfo} that answered — what says whose weather state this is. */
    public String worldInfoClass() {
        String value = reply.text("worldInfoClass");
        if (value == null) {
            throw new AssertionError("`artest weather get` named no world info class: " + raw);
        }
        return value;
    }

    /**
     * Whether AR's own per-dimension {@code WorldInfo} is answering for this world, rather than the
     * save's.
     *
     * <p>This is the question three classes asked of the class name, each spelling the suffix
     * itself. It is a SUFFIX and not an equality because the class is package-private to its own
     * tier and the name a test can state is the simple one.</p>
     */
    public boolean usesARWorldInfo() {
        return worldInfoClass().endsWith("ARDimensionWorldInfo");
    }

    /** This reading, refusing when it is not about {@code expected} — a guard against a stale read. */
    public DimWeather requireDim(int expected) {
        if (dim != expected) {
            throw new AssertionError("this weather reading is about dim " + dim + ", not dim "
                    + expected + ": " + raw);
        }
        return this;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "dim " + dim + " raining=" + raining + "(" + rainStrength + ")"
                + " thundering=" + thundering + "(" + thunderStrength + ")"
                + " clean=" + cleanWeatherTime;
    }
}
