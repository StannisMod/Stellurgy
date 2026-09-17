package zmaster587.advancedRocketry.test;

/**
 * One reading of the observatory's own answer — what the instrument is, where it is pointed, and
 * what the look it is doing has covered so far.
 *
 * <p>FOUR verbs share this reply body: {@code artest telescope info} reads it idle,
 * {@code scan} and {@code passive} answer it as the aim they just accepted, and {@code abort}
 * answers it as what is left after a stop. They are one producer — the server assembles all four
 * from one method — so they are read by one reader, and a caller that holds a reply no longer
 * remembers which of the four produced it.</p>
 *
 * <p>Measured 2026-09-17: four classes read this reply, spelling eighteen field names between them,
 * and three of the four carried a private hand-parser of their own — one scanning the text for
 * {@code "field":} and taking the digits that followed, one taking the digits AND a decimal point,
 * one slicing between quotes. The first of those silently truncates every length in the reply
 * ({@code reachLy}, {@code distanceLy}, {@code progress}, {@code halfAngleDeg}) to its integer part,
 * and none of the three can tell a field of THIS reply from a field of whatever else is in the
 * string — one call site read {@code artest nav status}'s {@code ship} with the telescope's
 * parser.</p>
 *
 * <h2>The scan block is CONDITIONAL, and its absence reads as "no progress"</h2>
 *
 * <p>{@code min}, {@code max}, {@code cells}, {@code cellsDone}, {@code stride}, {@code distanceLy},
 * {@code estimatedTicks} and the rest exist only while a survey is in flight. An idle instrument
 * answers the head and nothing else — so a caller asking an idle reply for {@code cellsDone} is not
 * told it asked the wrong question: the field is simply absent, and zero cells done is exactly what
 * a stalled survey looks like. Every accessor below that belongs to the block refuses when there is
 * no survey, naming that the instrument is idle.</p>
 *
 * <h2>{@code addresses} is a count with a NON-COUNT in it</h2>
 *
 * <p>The producer answers {@code -1} for "there is no crystal in the machine at all", which is an
 * arrangement fact and not a number of addresses. {@link #addressesOnCrystal()} refuses it;
 * {@link #holdsCrystal()} is the question that {@code -1} is the answer to.</p>
 *
 * <h2>{@code origin} is the four characters {@code null} for a world with no galactic address</h2>
 *
 * <p>Not an absent field — the producer writes the word. Callers read the origin to place a fixture
 * relative to the instrument, so a reader handing on {@code "null"} produces a fixture at a parsed
 * nonsense cell rather than a refusal. {@link #originCellKey()} refuses it, and
 * {@link #hasOrigin()} asks the question directly.</p>
 *
 * <p>A cell key may carry a ZONE prefix ({@code zone.sx_sy_sz}), which is why
 * {@link #originSector(int)} strips to the last {@code .} before splitting on {@code _} — the same
 * rule {@code GalacticCoord.fromCellKey} reads by. Splitting the whole key on {@code _} answers
 * {@code zone.sx} as the first sector coordinate.</p>
 */
public final class TelescopeScan {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** What the producer writes where a world has no galactic address. See the class note. */
    private static final String NO_ORIGIN = "null";

    /** What the producer writes in {@code addresses} where the machine holds no crystal. */
    private static final int NO_CRYSTAL = -1;

    /** Whether the verb that produced this reply did what it was asked. */
    public final boolean ok;
    /** Whether a survey is in flight right now — the head field, always present. */
    public final boolean scanning;
    /** Whether the instrument is in its local-radar mode rather than aimed at a region. */
    public final boolean passive;
    /** Whether this look is characterising whole systems rather than single bodies. */
    public final boolean wholeSystem;
    /** The world clock the deadlines below are measured against. */
    public final long now;
    /** How many addresses the crystal holds, or {@code -1} for no crystal. See the class note. */
    public final int addresses;
    /** How many bodies the last completed look resolved. */
    public final int lastDiscoveries;
    /** Which direction the OPERATOR has the instrument pointed — the tile's own pick. */
    public final int aim;
    /** How far out that aim reaches, in steps of one star's territory. */
    public final int aimDistance;
    /** The same aim as a length. */
    public final double aimLy;
    /** The configured horizon, as a length — what the instrument could ever see. */
    public final double reachLy;
    /** That same horizon in steps, at this world's star spacing. */
    public final int reachSteps;
    /** What ONE step of the aim is worth in cells — the instrument's stride while idle. */
    public final long stepCells;
    /** The aperture: the faintest magnitude this instrument resolves. */
    public final double limitMagnitude;
    /** The opening of one pointing, in degrees. */
    public final double halfAngleDeg;

    private final Reply reply;
    private final String raw;

    private TelescopeScan(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.ok = reply.bool("ok", false);
        this.scanning = reply.bool("scanning", false);
        this.passive = reply.bool("passive", false);
        this.wholeSystem = reply.bool("wholeSystem", false);
        this.now = reply.longInteger("now");
        this.addresses = reply.integer("addresses");
        this.lastDiscoveries = reply.integer("lastDiscoveries");
        this.aim = reply.integer("aim");
        this.aimDistance = reply.integer("aimDistance");
        this.aimLy = reply.number("aimLy");
        this.reachLy = reply.number("reachLy");
        this.reachSteps = reply.integer("reachSteps");
        this.stepCells = reply.longInteger("stepCells");
        this.limitMagnitude = reply.number("limitMagnitude");
        this.halfAngleDeg = reply.number("halfAngleDeg");
    }

    /**
     * Read one telescope reply — from {@code info}, {@code scan}, {@code passive} or {@code abort}.
     *
     * <p>Refuses a reply that carries no instrument. {@code telescope} answers
     * {@code {"error":"no observatory at that position"}} for an empty block and
     * {@code {"ok":false,"reason":"noOrigin",…}} for an aim it would not accept, and NEITHER carries
     * a single field below: read as this reply they say the instrument is idle, holds no crystal and
     * has resolved nothing, which is a description of a working machine that found nothing.</p>
     */
    public static TelescopeScan of(String telescopeReply) {
        String text = String.valueOf(telescopeReply);
        Reply reply = Reply.of("artest telescope info|scan|passive|abort", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("the telescope probe found no instrument to"
                    + " answer about, so nothing below is a reading of one: " + text);
        }
        if (!reply.has("scanning") || !reply.has("addresses")) {
            ArrangementFailure.arrangementFailed("this is not a telescope reply carrying the"
                    + " instrument's state: an aim the machine REFUSED answers `ok:false` with a"
                    + " reason and no state at all, and read as one it says the instrument is idle"
                    + " and empty: " + text);
        }
        return new TelescopeScan(reply, text);
    }

    /** Ask an observatory what it is doing. {@code where} is {@code <dim> <x> <y> <z>}. */
    public static TelescopeScan at(Probe probe, String where) throws Exception {
        return of(probe.exec("artest telescope info " + where));
    }

    /** This reading, refusing when the verb that produced it did not take. */
    public TelescopeScan requireOk(String what) {
        if (!ok) {
            ArrangementFailure.arrangementFailed(what + " — the instrument refused: " + raw);
        }
        return this;
    }

    /** Whether the machine holds a crystal at all — the question {@code addresses:-1} answers. */
    public boolean holdsCrystal() {
        return addresses != NO_CRYSTAL;
    }

    /**
     * How many addresses the crystal in the machine holds, refusing when there is no crystal.
     *
     * <p>{@code -1} is not a count and must never be compared with one: every "the crystal learned
     * something" assertion in the tier is {@code >= 1}, and an empty machine passes a {@code >= 0}
     * and fails a {@code == 0} for a reason that has nothing to do with what was surveyed.</p>
     */
    public int addressesOnCrystal() {
        if (!holdsCrystal()) {
            ArrangementFailure.arrangementFailed("there is no crystal in the observatory, so it"
                    + " holds no addresses and cannot learn any: " + raw);
        }
        return addresses;
    }

    /** WHICH worlds the crystal names, read without depositing anything. */
    public int[] crystalDims() {
        return reply.intArray("crystalDims");
    }

    /** Whether the world the instrument stands in has a galactic address. See the class note. */
    public boolean hasOrigin() {
        String value = reply.text("origin");
        return value != null && !NO_ORIGIN.equals(value);
    }

    /** The cell the instrument stands in, as the producer's own key. Refuses a world with none. */
    public String originCellKey() {
        if (!hasOrigin()) {
            ArrangementFailure.arrangementFailed("the observatory's world has no galactic address,"
                    + " so there is no cell to survey out from — the probe answers the four"
                    + " characters \"null\" there: " + raw);
        }
        return reply.text("origin");
    }

    /**
     * One sector coordinate of that cell, {@code 0}-based over {@code x}, {@code y}, {@code z}.
     *
     * <p>Every caller wanted this and each split the key itself. The zone prefix is stripped first;
     * see the class note for why splitting the whole key is wrong.</p>
     */
    public long originSector(int axis) {
        return sectorOf("origin", originCellKey(), axis);
    }

    /** The three sector coordinates of that cell, in order. */
    public long[] originSectors() {
        return new long[]{originSector(0), originSector(1), originSector(2)};
    }

    /**
     * One sector coordinate out of a cell key this reply carries, {@code 0}-based over x, y, z.
     *
     * <p>{@code which} names the field, so a malformed key says which of the three keys in this
     * reply it came out of.</p>
     */
    private long sectorOf(String which, String key, int axis) {
        String cell = key.substring(key.lastIndexOf('.') + 1);
        String[] parts = cell.split("_");
        if (parts.length != 3) {
            throw new AssertionError("the telescope's `" + which + "` is `" + key + "`, which is not"
                    + " a cell key (zone.sx_sy_sz), so no sector coordinate can be read out of it: "
                    + raw);
        }
        try {
            return Long.parseLong(parts[axis]);
        } catch (NumberFormatException notANumber) {
            throw new AssertionError("the telescope's `" + which + "` is `" + key + "`, which"
                    + " carries `" + parts[axis] + "` where sector axis " + axis + " belongs: "
                    + raw);
        }
    }

    // ── the survey in flight ──────────────────────────────────────────────────

    /** Whether the reply carries a survey block at all — it does exactly while one is in flight. */
    public boolean hasSurvey() {
        return reply.has("cells");
    }

    /** The low corner of the region the survey is covering, as a cell key. */
    public String regionMin() {
        return surveyText("min");
    }

    /** Its high corner. */
    public String regionMax() {
        return surveyText("max");
    }

    /** One sector coordinate of the low corner, {@code 0}-based over x, y, z. */
    public long regionMinSector(int axis) {
        return sectorOf("min", regionMin(), axis);
    }

    /** The same for the high corner. */
    public long regionMaxSector(int axis) {
        return sectorOf("max", regionMax(), axis);
    }

    /** How many cells the survey has to look at in all. */
    public int cells() {
        return survey("cells");
    }

    /** How many of them it has resolved. Never a zero for an idle instrument — see the class note. */
    public int cellsDone() {
        return survey("cellsDone");
    }

    /** How many it resolves per step. */
    public int cellsPerStep() {
        return survey("cellsPerStep");
    }

    /** How far out the survey reaches, in cells. */
    public long distanceCells() {
        requireSurvey("distance");
        return reply.longInteger("distance");
    }

    /** The same reach as a length — what says whether a look is interstellar at all. */
    public double distanceLy() {
        requireSurvey("distanceLy");
        return reply.number("distanceLy");
    }

    /** How far apart, in cells, the looks of this survey stand. */
    public long stride() {
        requireSurvey("stride");
        return reply.longInteger("stride");
    }

    /** The world tick the survey started on. */
    public long startTick() {
        requireSurvey("start");
        return reply.longInteger("start");
    }

    /** The tick the next step is due on, measured against {@link #now}. */
    public long stepDeadline() {
        requireSurvey("stepDeadline");
        return reply.longInteger("stepDeadline");
    }

    /** How many ticks one step costs. */
    public int ticksPerStep() {
        return survey("ticksPerStep");
    }

    /** How long the whole survey is expected to take, in ticks. */
    public long estimatedTicks() {
        requireSurvey("estimatedTicks");
        return reply.longInteger("estimatedTicks");
    }

    /** How far through its region it is, as a fraction. */
    public double progress() {
        requireSurvey("progress");
        return reply.number("progress");
    }

    /** Whether a step is due right now. */
    public boolean stepDue() {
        requireSurvey("stepDue");
        return reply.bool("stepDue", false);
    }

    /** Whether this survey is a POINTING (an apex and an opening) rather than a local radar. */
    public boolean pointing() {
        requireSurvey("pointing");
        return reply.bool("pointing", false);
    }

    /** How many shells a pointing walks through; {@code 0} for a radar. */
    public int shells() {
        return survey("shells");
    }

    /**
     * WHERE the survey is aimed, as the producer's own direction triple.
     *
     * <p>The aim and not the corners: a pointing's bounding box is its apex plus its reach on every
     * axis, so re-aiming the same instrument leaves {@link #regionMin()} and {@link #regionMax()}
     * exactly where they were.</p>
     */
    public String direction() {
        return surveyText("dir");
    }

    private int survey(String field) {
        requireSurvey(field);
        return reply.integer(field);
    }

    private String surveyText(String field) {
        requireSurvey(field);
        return reply.text(field);
    }

    private void requireSurvey(String field) {
        if (!hasSurvey()) {
            throw new AssertionError("the instrument is idle, so there is no survey to ask for `"
                    + field + "` — an absent count here is not a survey that has covered nothing: "
                    + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "telescope scanning=" + scanning + " passive=" + passive + " addresses=" + addresses
                + " aim=" + aim + "@" + aimDistance
                + (hasSurvey() ? " cells=" + reply.integer("cellsDone") + "/"
                        + reply.integer("cells") : " idle");
    }
}
