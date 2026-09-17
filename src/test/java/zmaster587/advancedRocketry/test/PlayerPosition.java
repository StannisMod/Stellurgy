package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest player position-of <name>} — where the SERVER says a named player
 * is, without dispatching anything as him.
 *
 * <p>Measured 2026-09-17: four classes read this reply and spell five field names between them.</p>
 *
 * <h2>{@link #dim} and {@link #dimField} are maintained separately and CAN disagree</h2>
 *
 * <p>{@code playerDim} is the world the entity is actually ticking in; {@code playerDimField} is
 * the entity's own dimension field, and it is the FIELD that {@code Entity.writeToNBT} persists. A
 * placement that moved one and not the other is a player who is standing in one world and will be
 * saved into another — so the reader carries both, and neither accessor is a convenience for the
 * other.</p>
 *
 * <h2>An OFFLINE player answers an error, which reads as the origin of the overworld</h2>
 *
 * <p>{@code {"error":"no such player","name":"…"}} carries no dim and no coordinates, so a caller
 * reading {@code playerDim} off it gets an absence — and {@code 0} is a real dimension while
 * {@code (0, 0, 0)} is a real place. {@link #of} refuses it.</p>
 *
 * <p><b>Two call sites assert that error on purpose</b> — "the server must see him GONE after the
 * disconnect, or nothing below is a relog" — and those keep the raw reply, because a reading of it
 * cannot be the thing being asserted.</p>
 */
public final class PlayerPosition {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The player this reading is about, as the reply echoed the name back. */
    public final String player;
    /** The world he is ticking in. See the class note: not the same fact as {@link #dimField}. */
    public final int dim;
    /** His own dimension FIELD — what a save writes. */
    public final int dimField;
    /** Where he is standing. */
    public final double x;
    public final double y;
    public final double z;

    private final String raw;

    private PlayerPosition(Reply reply, String raw) {
        this.raw = raw;
        this.player = reply.text("player");
        this.dim = reply.integer("playerDim");
        this.dimField = reply.integer("playerDimField");
        this.x = reply.number("playerPosX");
        this.y = reply.number("playerPosY");
        this.z = reply.number("playerPosZ");
    }

    /**
     * Read one {@code player position-of} reply, or refuse.
     *
     * <p>Refuses the offline shapes — see the class note.</p>
     */
    public static PlayerPosition of(String positionReply) {
        String text = String.valueOf(positionReply);
        Reply reply = Reply.of("artest player position-of", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest player position-of` found no such player"
                    + " (" + reply.text("error") + "), so there is no position — and read as one,"
                    + " this reply puts him at the origin of the overworld: " + text);
        }
        if (!reply.has("playerDim") || !reply.has("playerDimField")) {
            throw new AssertionError("this is not an `artest player position-of` answer: a position"
                    + " reading carries the world he TICKS in beside the dimension FIELD that his"
                    + " save writes: " + text);
        }
        return new PlayerPosition(reply, text);
    }

    /** Ask where the server says one named player is. */
    public static PlayerPosition of(Probe probe, String playerName) throws Exception {
        return of(probe.exec("artest player position-of " + playerName));
    }

    /**
     * Whether the world he is in and the dimension his save would record are the same.
     *
     * <p>Offered as its own question because the two-assertion pair it replaces is the one thing
     * this reply exists to make visible, and a test that checked only one of them would pass on a
     * player who is about to be saved into a world he is not standing in.</p>
     */
    public boolean dimensionsAgree() {
        return dim == dimField;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return player + " in dim " + dim + (dim == dimField ? "" : " (field says " + dimField + ")")
                + " at " + x + "," + y + "," + z;
    }
}
