package dev.stannismod.stellurgy.test;

/**
 * One reading of {@code stellurgytest planet orbit-line <dim> [<blocks>|unset]} — a world's
 * atmosphere&harr;orbit line as production reads it, and the takeoff ceiling derived from it.
 *
 * <p>The line is the body's own since the global config key was removed: Earth's is 100 000 world
 * blocks, Luna's about 27 300, and a world the planet file gives an {@code <orbitHeight>} carries that
 * instead. So a test that climbs a ship past it, or teleports one above it, ASKS for it here rather than
 * carrying a number — a constant in a test was the reason six classes all still said 1 200 the day the
 * line moved a hundredfold.</p>
 *
 * <p>Absence is a reading: a world with no radius and no stated line has none, and {@link #line()}
 * refuses there instead of answering a number nobody chose.</p>
 */
public final class OrbitLine {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The dimension this reading is about. */
    public final int dim;
    /** Whether the planet file states this world's line, rather than its body deriving it. */
    public final boolean stated;

    private final Reply reply;

    private OrbitLine(Reply reply) {
        this.reply = reply;
        this.dim = reply.integer("dim");
        this.stated = reply.bool("stated");
    }

    /** Read the line of {@code dim} as it stands. */
    public static OrbitLine of(Probe probe, int dim) throws Exception {
        return read(probe.exec("stellurgytest planet orbit-line " + dim));
    }

    /**
     * STATE the planet file's {@code <orbitHeight>} for {@code dim} — the arrangement for a low line —
     * and read the result. A scenario on a shared server that states one clears it with
     * {@link #unstate}.
     */
    public static OrbitLine state(Probe probe, int dim, int blocks) throws Exception {
        return read(probe.exec("stellurgytest planet state-orbit-line " + dim + " " + blocks));
    }

    /** Clear a stated line, so the body's own applies again. */
    public static OrbitLine unstate(Probe probe, int dim) throws Exception {
        return read(probe.exec("stellurgytest planet state-orbit-line " + dim + " unset"));
    }

    private static OrbitLine read(String raw) {
        Reply reply = Reply.of("stellurgytest planet orbit-line", raw);
        reply.requireOk("planet orbit-line");
        return new OrbitLine(reply);
    }

    /** The body's radius in Earth radii as the server holds it; 0 for a world with none. */
    public double radius() {
        return reply.number("radius");
    }

    /** Whether this world has a line at all. */
    public boolean exists() {
        return reply.has("line");
    }

    /** The line, in world Y. Refuses for a world that has none. */
    public int line() {
        if (!exists()) {
            throw new AssertionError("dim " + dim + " has no orbit line — no radius and no stated "
                    + "<orbitHeight> — so there is no height to be above: " + reply);
        }
        return reply.integer("line");
    }

    /**
     * The world Y a ship's takeoff fires above: the line capped below the physics clamp. Refuses for a
     * world with no line, or one that is not loaded (the clamp is the loaded world's).
     */
    public int entryCeiling() {
        if (!reply.has("entryCeiling")) {
            throw new AssertionError("dim " + dim + " reports no entry ceiling (line "
                    + reply.reported("line") + ", loaded " + reply.reported("loaded") + "): " + reply);
        }
        return reply.integer("entryCeiling");
    }

    /**
     * A world Y a ship placed at stands above the {@link #entryCeiling()} by the room production itself
     * states a ship needs to cross that line demonstrably
     * ({@code ShipEntryController.PHYSICS_CLAMP_ENTRY_MARGIN}) — where an arrangement puts a ship it
     * means to have already climbed past the line.
     */
    public int aboveEntryCeiling() {
        return entryCeiling() + dev.stannismod.stellurgy.space.ShipEntryController.PHYSICS_CLAMP_ENTRY_MARGIN;
    }

    @Override
    public String toString() {
        return reply.toString();
    }
}
