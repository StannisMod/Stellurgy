package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest player health} — who the server thinks the player is, where, and in
 * what condition.
 *
 * <p>Measured 2026-09-17: nine classes carried a {@code PLAYER_NAME = "player"} constant of their
 * own and six of them a private {@code botName()} around it. The reply is small; what made it the
 * fourth most widely re-spelt producer is that <b>every test needs the bot's NAME</b> — it is what
 * makes a wait on a shared event log about THIS crew member rather than any body's — so nearly every
 * class reaches for this verb once and then spells its own way to the field.</p>
 *
 * <h2>The name comes off the SERVER's own answer, never from a constant</h2>
 *
 * <p>The harness account's username is the server's to decide, and a test that hard-codes it is
 * asserting about the harness rather than reading it. {@link #name} is the field for that, and
 * {@link #requireName} refuses rather than handing back an empty string — a nameless player carried
 * into a command becomes the four characters {@code null}, and the verb then answers about nobody.</p>
 *
 * <h2>{@code health} is the player's, not a verdict on the reply</h2>
 *
 * <p>The verb's name is {@code health} and its reply carries an {@code ok}. They are different
 * things: {@code ok} says the probe found a player, {@link #health} says how much of one is left. A
 * caller testing survival wants the second; a caller testing that a player exists at all wants the
 * refusal this reader raises.</p>
 */
public final class PlayerState {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The username the server knows this player by. */
    public final String name;
    /** Hit points remaining, and the maximum for this player. */
    public final double health;
    public final double maxHealth;
    /** The dimension the SERVER has him in — which a client-side read can legitimately disagree with. */
    public final int dim;
    /** Where the server has him. */
    public final double x;
    public final double y;
    public final double z;

    private final String raw;

    private PlayerState(Reply reply, String raw) {
        this.raw = raw;
        this.name = reply.text("player");
        this.health = reply.number("health");
        this.maxHealth = reply.number("maxHealth");
        this.dim = reply.integer("dim");
        this.x = reply.number("posX");
        this.y = reply.number("posY");
        this.z = reply.number("posZ");
    }

    /**
     * Read one {@code player health} reply, or refuse.
     *
     * <p>Refuses the no-player replies — {@code no such player}, {@code no players connected} —
     * because a test that goes on to read a position off one of those gets {@code NaN}, and a
     * {@code NaN} compared against a coordinate is silently false rather than loudly absent.</p>
     */
    public static PlayerState of(String healthReply) {
        Reply reply = Reply.of("artest player health", String.valueOf(healthReply));
        if (!reply.ok()) {
            ArrangementFailure.arrangementFailed("the server has no player to report on, so nothing"
                    + " read below would be about anybody: " + healthReply);
        }
        return new PlayerState(reply, String.valueOf(healthReply));
    }

    /** Ask the server about the player it has. */
    public static PlayerState read(Probe probe) throws Exception {
        return of(probe.exec("artest player health"));
    }

    /**
     * The bot's own username, off the server's own answer — the one-line form nine classes wanted.
     *
     * <p>Every record a crew scenario waits on carries {@code who}, and on a log any body's mount can
     * write to, the name is what makes a wait about THIS crew member.</p>
     */
    public static String botName(Probe probe) throws Exception {
        return read(probe).requireName();
    }

    /** The username, refusing when the reply carries none. */
    public String requireName() {
        if (name == null || name.isEmpty()) {
            ArrangementFailure.arrangementFailed("`player health` must echo the player's name — it is"
                    + " what every later wait is filtered on, and an empty one filters on nothing: "
                    + raw);
        }
        return name;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return name + " hp=" + health + "/" + maxHealth + " dim=" + dim + " @" + x + "," + y + "," + z;
    }
}
