package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest machine info <dim> <x> <y> <z>}: what tile stands at a block, and
 * what its multiblock API answers about it.
 *
 * <p><b>Why a class, for a reply that carries a class name.</b> The question every caller asks is
 * "is the tile at this block a {@code TileBeacon}", and it was asked by searching the rendering:
 * {@code info.contains("TileBeacon")}. That is satisfied by {@code TileBeaconAdvanced}, by a
 * {@code tileClass} belonging to some other field, and by the string appearing in an error
 * message about a different block — and in the other direction it fails silently if the producer
 * ever reports the SIMPLE name instead of the fully-qualified one. The class name lives in a
 * field called {@code tileClass}; asked by name and compared as a name, none of those is
 * possible.</p>
 *
 * <p><b>Absence is a first-class answer here and that is the point.</b> A block with no tile is
 * how this verb answers most often — {@code {"error":"no tile entity"}} — and a smoke test walking
 * a hundred block ids needs to tell that apart from "the probe fell over", which a substring over
 * the whole reply cannot. {@link #hasTile()} is the question; {@link #tileClass()} refuses when
 * the answer is no, so a reading can never be taken off a refusal.</p>
 */
public final class MachineInfo {

    /** The probe this reads, so a caller needs no knowledge of the verb's spelling. */
    public interface Probe {
        String exec(String command) throws Exception;
    }

    private static final String TILE_CLASS = "tileClass";
    private static final String NO_TILE = "no tile entity";

    private final Reply reply;
    private final String raw;

    private MachineInfo(String raw) {
        this.reply = Reply.of("artest machine info", raw);
        this.raw = raw;
    }

    /** Parse a reply already in hand. */
    public static MachineInfo of(String infoReply) {
        return new MachineInfo(infoReply);
    }

    /** Ask the server about one block. */
    public static MachineInfo at(Probe probe, int dim, int x, int y, int z) throws Exception {
        return of(probe.exec("artest machine info " + dim + " " + x + " " + y + " " + z));
    }

    /** Whether a tile entity stands at that block at all. */
    public boolean hasTile() {
        return reply.has(TILE_CLASS);
    }

    /**
     * Whether the verb refused because the block carries no tile — as opposed to refusing for
     * any other reason, which is a different finding and usually an arrangement fault.
     */
    public boolean reportsNoTile() {
        return reply.refused() && NO_TILE.equals(reply.error());
    }

    /** The tile's fully-qualified class name, refusing when no tile stands there. */
    public String tileClass() {
        if (!hasTile()) {
            throw new AssertionError("no tile stands at that block, so it has no class: " + raw);
        }
        return reply.text(TILE_CLASS);
    }

    /**
     * The tile's SIMPLE class name — what a test means when it names a tile.
     *
     * <p>Taken off the qualified name rather than matched inside it, so {@code TileBeacon} and
     * {@code TileBeaconAdvanced} are different answers instead of one being a substring of the
     * other.</p>
     */
    public String tileSimpleName() {
        if (!hasTile()) {
            throw new AssertionError("no tile stands at that block, so it has no class: " + raw);
        }
        return reply.simpleClassName(TILE_CLASS);
    }

    /** Whether the tile standing there is exactly {@code simpleName}. */
    public boolean isTile(String simpleName) {
        return hasTile() && tileSimpleName().equals(simpleName);
    }

    /**
     * The libVulpes multiblock completeness flag.
     *
     * <p>absence is the answer, and it is this verb's own contract: the probe writes
     * {@code "isComplete":"n/a"} — a STRING — for a tile that has no such method, and writes
     * nothing at all when the reflective call throws. A tile that cannot be asked is not a
     * complete multiblock, which is what a caller of this means.</p>
     */
    public boolean isComplete() {
        return reply.boolOr("isComplete", false);
    }

    /** The underlying reply, for a caller reading a field this class does not name. */
    public Reply reply() {
        return reply;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }
}
