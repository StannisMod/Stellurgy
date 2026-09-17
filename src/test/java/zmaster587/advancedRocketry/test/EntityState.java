package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest entity info <dim> <entityId>} — where an entity is, what it is
 * doing, and whether it is there at all.
 *
 * <p>Measured 2026-09-17: nine classes ask this verb; six of them read fields out of the reply and
 * between them spell {@code isAlive}, {@code isDead}, {@code entityClass}, {@code posX/Y/Z},
 * {@code motionX/Y/Z}, {@code hasNoGravity} and {@code fallDistance}. Four asserted the aliveness by
 * {@code contains("\"isAlive\":true")}, and one asked for both that and {@code isDead:false} in one
 * expression because nothing said which of the two it should be reading.</p>
 *
 * <h2>The reply has TWO shapes and only one of them carries a position</h2>
 *
 * <p>An entity the world no longer holds answers {@code {"isAlive":false,"entityId":N}} — no class,
 * no position, no motion. A caller reaching for {@code posY} on that gets {@code NaN} from
 * {@link Reply} and prints a sentence about where the entity is; one asking {@code fallDistance}
 * gets an absence that reads as a reset. So every accessor below that belongs to a LIVE entity
 * refuses when there is none, naming the id it asked about.</p>
 *
 * <h2>{@code isAlive} and {@code isDead} are different questions</h2>
 *
 * <p>{@code isAlive} is whether {@code getEntityByID} still finds it — whether it is in the world's
 * list. {@code isDead} is the entity's own flag, and an entity can carry it for a tick or two while
 * still being findable. A test that wants "it was destroyed" may accept either and must say which
 * it means: {@link #alive} is the world's answer, {@link #dead()} the entity's own, and
 * {@link #goneOrDying()} is the disjunction, written once here instead of at each call site.</p>
 */
public final class EntityState {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The entity id this reading is about, as the reply echoed it back. */
    public final int entityId;
    /** Whether the world still holds an entity under that id. See the class note. */
    public final boolean alive;

    private final Reply reply;
    private final String raw;

    private EntityState(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.entityId = reply.integer("entityId");
        this.alive = reply.bool("isAlive", false);
    }

    /**
     * Read one {@code entity info} reply, or refuse.
     *
     * <p>Refuses {@code {"error":"world not loaded"}}: that reply says nothing about any entity, and
     * read as one it is an entity that is not alive — which is a real state, and the one most of
     * these call sites are asserting.</p>
     */
    public static EntityState of(String infoReply) {
        String text = String.valueOf(infoReply);
        Reply reply = Reply.of("artest entity info", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest entity info` could not look in that world"
                    + " (" + reply.text("error") + "), so nothing here is a reading of an entity —"
                    + " and read as one, this reply is an entity that is gone: " + text);
        }
        if (!reply.has("isAlive") || !reply.has("entityId")) {
            throw new AssertionError("this is not an `artest entity info` answer: it carries no"
                    + " `isAlive` beside the `entityId` it was asked about: " + text);
        }
        return new EntityState(reply, text);
    }

    /** Ask the server about one entity by id. */
    public static EntityState byId(Probe probe, int dim, int entityId) throws Exception {
        return of(probe.exec("artest entity info " + dim + " " + entityId));
    }

    /** This reading, refusing when the world no longer holds the entity. */
    public EntityState requireAlive(String what) {
        if (!alive) {
            ArrangementFailure.arrangementFailed(what + " — the world holds no entity "
                    + entityId + " any more, so there is nothing to measure on it: " + raw);
        }
        return this;
    }

    /** The entity's own dead FLAG, which is not the same as being gone. See the class note. */
    public boolean dead() {
        return live("isDead") && reply.bool("isDead", false);
    }

    /**
     * Whether the entity is gone from the world OR carrying its own dead flag.
     *
     * <p>The disjunction one call site wrote out as two {@code contains} tests. It is a legitimate
     * question — "the shield destroyed the bolt" is satisfied either way — and it is the only
     * question for which mixing the two is right.</p>
     */
    public boolean goneOrDying() {
        return !alive || reply.bool("isDead", false);
    }

    /** The entity's class, as the server names it. Refuses when the entity is gone. */
    public String entityClass() {
        requireLive("entityClass");
        return reply.text("entityClass");
    }

    /** Where it is. Refuses when the entity is gone — an absent coordinate is not the origin. */
    public double posX() {
        return coordinate("posX");
    }

    public double posY() {
        return coordinate("posY");
    }

    public double posZ() {
        return coordinate("posZ");
    }

    /** How it is moving. Refuses when the entity is gone. */
    public double motionX() {
        return coordinate("motionX");
    }

    public double motionY() {
        return coordinate("motionY");
    }

    public double motionZ() {
        return coordinate("motionZ");
    }

    /** Whether vanilla gravity is switched off on it — what a pinned fixture entity carries. */
    public boolean hasNoGravity() {
        requireLive("hasNoGravity");
        return reply.bool("hasNoGravity", false);
    }

    /**
     * How far it has fallen without landing.
     *
     * <p>Refuses for a gone entity rather than answering zero, because zero is exactly what the
     * mechanic under test at the one call site that reads it PRODUCES: a gravity controller resets
     * this, so "absent" and "reset" must not look the same.</p>
     */
    public double fallDistance() {
        return coordinate("fallDistance");
    }

    private double coordinate(String field) {
        requireLive(field);
        return reply.number(field);
    }

    private boolean live(String field) {
        return reply.has(field);
    }

    private void requireLive(String field) {
        if (!alive) {
            throw new AssertionError("the world holds no entity " + entityId + ", so `" + field
                    + "` is not something it has — an absence here is not a measurement: " + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return alive
                ? "entity " + entityId + " " + reply.text("entityClass") + " at "
                        + reply.number("posX") + "," + reply.number("posY") + ","
                        + reply.number("posZ")
                : "entity " + entityId + " is gone";
    }
}
