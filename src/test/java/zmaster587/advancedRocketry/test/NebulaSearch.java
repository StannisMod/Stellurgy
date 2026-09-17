package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space nebula-find <steps> <stride>} — the first cell along +X whose
 * sky holds a cloud, and where the generator says that cloud IS.
 *
 * <p>An arrangement helper: a cloud's position is a fact about the SEED, so a test that wants to
 * look through one has to ask the world where one is rather than write a coordinate down.</p>
 *
 * <p>Measured 2026-09-17: two classes read this reply and spell six field names between them,
 * through a hand parser that took the digits after {@code "field":} — which truncates every decimal
 * in it ({@code largest}, {@code radiusLy}, {@code peakDensity}) to its integer part.</p>
 *
 * <h2>{@code found:false} carries the SEARCH, not a cell</h2>
 *
 * <p>A walk that reached its step limit answers {@code {"ok":true,"found":false,"searched":N,
 * "stride":S}} — no cell, no sector, no centre. Every caller here goes on to build a sight line out
 * of {@code centreX} and {@code radiusCells}, so an absence there becomes a line from the origin to
 * the origin: zero length, crossing nothing, and the extinction reading taken along it is a
 * legitimate-looking zero. {@link #requireFound} refuses it.</p>
 *
 * <h2>The CLOUD block is conditional on the generator having an object for it</h2>
 *
 * <p>{@code drawn} counts what the sky RENDERS, and a render record carries a direction and an
 * angle and deliberately no position. The centre and radius come from the generator's own biggest
 * nebula instead, and are absent when it has none — so the accessors for them refuse rather than
 * answering the origin.</p>
 */
public final class NebulaSearch {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether the walk found a cell with a cloud in its sky. */
    public final boolean found;

    private final Reply reply;
    private final String raw;

    private NebulaSearch(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.found = reply.bool("found", false);
    }

    /** Read one {@code nebula-find} reply, or refuse. */
    public static NebulaSearch of(String findReply) {
        String text = String.valueOf(findReply);
        Reply reply = Reply.of("artest space nebula-find", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest space nebula-find` could not run ("
                    + reply.text("error") + "), so nothing was searched — and read as a result, this"
                    + " reply is a galaxy with no clouds in it: " + text);
        }
        if (!reply.has("found")) {
            throw new AssertionError("this is not an `artest space nebula-find` answer: it carries"
                    + " no `found`: " + text);
        }
        return new NebulaSearch(reply, text);
    }

    /** Walk out along +X for {@code steps} of {@code stride} cells, looking for a cloud. */
    public static NebulaSearch walk(Probe probe, int steps, long stride) throws Exception {
        return of(probe.exec("artest space nebula-find " + steps + " " + stride));
    }

    /** This reading, refusing when the walk found nothing. */
    public NebulaSearch requireFound(String what) {
        if (!found) {
            ArrangementFailure.arrangementFailed(what + " — the walk searched "
                    + reply.textOr("searched", "?") + " steps of "
                    + reply.textOr("stride", "?") + " cells and found no cloud: " + raw);
        }
        return this;
    }

    /** The cell whose sky holds the cloud, as the producer's own key. */
    public String cellKey() {
        return foundField("cell");
    }

    /** That cell's X sector — what a caller hands to {@code space nebulae}. */
    public long sectorX() {
        return foundNumber("sectorX");
    }

    /** How many clouds that sky DRAWS. */
    public int drawn() {
        requireWalkFound("drawn");
        return reply.integer("drawn");
    }

    /** The angular radius of the largest of them, in the producer's own units. */
    public double largestAngularRadius() {
        return foundDecimal("largest");
    }

    /** How many steps the walk took to get there. */
    public int steps() {
        requireWalkFound("steps");
        return reply.integer("steps");
    }

    /** Whether the generator had a nebula OBJECT for the cloud — the centre block exists then. */
    public boolean hasCloudPosition() {
        return found && reply.has("centreX");
    }

    /** Where the biggest nebula around that cell is, in CELLS. Refuses when there is no object. */
    public long centreX() {
        return cloudNumber("centreX");
    }

    public long centreY() {
        return cloudNumber("centreY");
    }

    public long centreZ() {
        return cloudNumber("centreZ");
    }

    /** Its radius in CELLS — what a sight line through its core is measured in. */
    public long radiusCells() {
        return cloudNumber("radiusCells");
    }

    /** Its radius as a LENGTH, in light years. A decimal, and it was being truncated. */
    public double radiusLy() {
        requireCloud("radiusLy");
        return reply.number("radiusLy");
    }

    /** Its peak density, in the generator's own units. Also a decimal. */
    public double peakDensity() {
        requireCloud("peakDensity");
        return reply.number("peakDensity");
    }

    private String foundField(String field) {
        requireWalkFound(field);
        return reply.text(field);
    }

    private long foundNumber(String field) {
        requireWalkFound(field);
        return reply.longInteger(field);
    }

    private double foundDecimal(String field) {
        requireWalkFound(field);
        return reply.number(field);
    }

    private long cloudNumber(String field) {
        requireCloud(field);
        return reply.longInteger(field);
    }

    private void requireWalkFound(String field) {
        if (!found) {
            throw new AssertionError("the nebula walk found no cloud, so `" + field + "` is not"
                    + " something it has — and a zero coordinate here is the ORIGIN: " + raw);
        }
    }

    private void requireCloud(String field) {
        requireWalkFound(field);
        if (!reply.has("centreX")) {
            throw new AssertionError("the generator holds no nebula object around that cell, so `"
                    + field + "` is absent — the sky DRAWS a cloud there, but a render record"
                    + " carries a direction and an angle and no position: " + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return found
                ? "a cloud at cell " + reply.text("cell") + " (" + reply.text("drawn") + " drawn)"
                : "no cloud in " + reply.textOr("searched", "?") + " steps";
    }
}
