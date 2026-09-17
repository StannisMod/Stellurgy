package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest vs ship-info <dim> id <shipId>} — a ship's POSE, read by field name.
 *
 * <p>This verb is the most widely parsed producer in the tier: measured 2026-09-17, fifteen classes
 * spelled its field names as string literals of their own, and the same five names
 * ({@code posX}, {@code posY}, {@code posZ}, {@code qw…qz}, {@code omega}) appeared in fourteen,
 * eight and two of them respectively. Each copy is a separate place for the producer's vocabulary to
 * be re-learned, and none of them could say what the reply meant when a name was missing.</p>
 *
 * <p><b>Why a reader rather than a field name held in one constant.</b> The reply has TWO shapes and
 * they are not distinguished by any field a caller reads. A ship the world knows answers with a
 * pose; an id naming nothing loaded in that world answers
 * {@code {"managed":false,"id":"…"}} — <i>no position in it at all</i>. A caller that reaches
 * straight for {@code posX} on that second shape does not fail: {@link Reply} answers absence, the
 * absence arrives as {@code NaN}, and what the test then prints is a sentence about where the ship
 * is. So the discrimination belongs in the reader, once, where it can refuse — and it names the id
 * it could not find, which is the one thing that makes such a failure diagnosable.</p>
 *
 * <p><b>{@code managed} is not {@code ready}, and this class keeps them apart</b> because production
 * takes trouble to. {@code managed} says a physics object for the id was found; {@code ready} says
 * the substrate's initial-ticks delay is over and its resolver holds the surrounding chunks — the
 * pair the physics loop itself selects on. A craft that exists and does not move yet satisfies the
 * first and not the second, and three tests once read the first as answering the second.</p>
 *
 * <p>Readiness is an EDGE, and this is a reading of which side of it the ship is on: a caller that
 * means to WAIT for it wants the {@code ship_usable} event, not this field.</p>
 *
 * <p>The not-loaded refusal is an {@link ArrangementFailure}, not a bare {@code AssertionError}: it
 * says the state was never built, which is a different finding from the product having put the ship
 * somewhere wrong, and the gate's XML records the type where it cannot record a prose prefix.</p>
 */
public final class ShipInfo {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The ship's own id, as the reply echoed it back. */
    public final String id;
    /** The pose, in WORLD coordinates — where the ship's subspace origin sits in its dimension. */
    public final double x;
    public final double y;
    public final double z;
    /** The attitude quaternion, {@code w} first, as the substrate holds it. */
    public final double qw;
    public final double qx;
    public final double qy;
    public final double qz;
    /** Linear velocity, world frame, blocks per tick as the substrate reports it. */
    public final double velX;
    public final double velY;
    public final double velZ;
    /** Angular velocity per axis, and its magnitude — the latter is what "is it spinning" reads. */
    public final double omegaX;
    public final double omegaY;
    public final double omegaZ;
    public final double omega;
    /**
     * Whether the substrate's two physics gates are both open. A pose is reported either way: a
     * {@code ready:false} ship has a position and does not move.
     */
    public final boolean ready;
    /**
     * How many blocks the registry says this hull owns, or {@code null} when the reply did not
     * carry the count at all. Production's own {@code -1} means something different and narrower —
     * "the registry has no row for this id" — so the two are kept apart here rather than merged
     * into one number a reader cannot interpret.
     */
    public final Integer blocks;

    private final String raw;

    private ShipInfo(Reply reply, String raw) {
        this.raw = raw;
        this.id = reply.text("id");
        this.x = reply.number("posX");
        this.y = reply.number("posY");
        this.z = reply.number("posZ");
        this.qw = reply.number("qw");
        this.qx = reply.number("qx");
        this.qy = reply.number("qy");
        this.qz = reply.number("qz");
        this.velX = reply.number("velX");
        this.velY = reply.number("velY");
        this.velZ = reply.number("velZ");
        this.omegaX = reply.number("omegaX");
        this.omegaY = reply.number("omegaY");
        this.omegaZ = reply.number("omegaZ");
        this.omega = reply.number("omega");
        this.ready = reply.bool("ready", false);
        this.blocks = reply.has("blocks") ? Integer.valueOf(reply.integer("blocks")) : null;
    }

    /**
     * The pose out of one {@code vs ship-info} reply.
     *
     * <p>Refuses three things, each of which would otherwise be read as a measurement: a reply that
     * is not this verb's at all, a reply carrying no {@code managed} field, and a ship this world
     * does not hold. The last is the one that bites — it is a legitimate answer to the QUESTION and
     * never an answer to "where is it".</p>
     */
    public static ShipInfo of(String shipInfoReply) {
        return parse(shipInfoReply, "the world this was asked of");
    }

    private static ShipInfo parse(String shipInfoReply, String askedOf) {
        Reply reply = Reply.of("artest vs ship-info", String.valueOf(shipInfoReply));
        if (!reply.has("managed")) {
            throw new AssertionError("`artest vs ship-info` must answer a `managed` field saying"
                    + " whether this world holds the ship at all, so that a missing pose cannot read"
                    + " as a position: " + shipInfoReply);
        }
        if (!reply.bool("managed", false)) {
            // An ARRANGEMENT failure by TYPE and not by prefix: the id names nothing in that world,
            // so the state this reading is about was never built. A reader — human or the gate's own
            // XML — must be able to tell that from "the product put the ship in the wrong place".
            ArrangementFailure.arrangementFailed("no ship with id " + reply.text("id")
                    + " is loaded in " + askedOf + ", so it has no pose to report: " + shipInfoReply);
        }
        return new ShipInfo(reply, String.valueOf(shipInfoReply));
    }

    /**
     * Whether the world that answered holds this ship at all — the {@code managed} field, as a
     * question rather than as a pose.
     *
     * <p>This exists because it is the OTHER thing every caller of this verb wants, and measured
     * 2026-09-17 there were 39 private copies of it across the tier, each spelled
     * {@code contains("\"managed\":true")}. That spelling is a substring over a rendering: it
     * matches whatever else in the reply happens to carry those characters, and it answers
     * <i>false</i> — indistinguishable from "the ship is gone" — for a reply that is an error object,
     * a truncated line, or a verb that does not exist. Here a reply that is not this verb's is
     * REFUSED, so a false is a statement about the world.</p>
     */
    public static boolean isLoaded(String shipInfoReply) {
        Reply reply = Reply.of("artest vs ship-info", String.valueOf(shipInfoReply));
        if (!reply.has("managed")) {
            throw new AssertionError("`artest vs ship-info` must answer a `managed` field, or"
                    + " 'the ship is not here' cannot be told from 'that was not the reply I think"
                    + " it was': " + shipInfoReply);
        }
        return reply.bool("managed", false);
    }

    /** The same question, asked of {@code dim} about {@code shipId}. */
    public static boolean loadedIn(Probe probe, int dim, String shipId) throws Exception {
        return isLoaded(probe.exec("artest vs ship-info " + dim + " id " + shipId));
    }

    /**
     * Ask the world named by {@code dim} about the ship named by {@code shipId}, and read the answer.
     *
     * <p>The id-keyed form is the only one there is: the positional {@code ship-info <dim> <x> <y>
     * <z>} was removed 2026-09-14 because it answered with the loaded ship NEAREST a point, on a
     * world every sibling scenario also builds in, and a neighbour's craft and your own produce
     * byte-identical replies.</p>
     */
    public static ShipInfo byId(Probe probe, int dim, String shipId) throws Exception {
        return parse(probe.exec("artest vs ship-info " + dim + " id " + shipId), "dim " + dim);
    }

    /**
     * The world-frame Y of the ship's OWN up axis, from its attitude quaternion. {@code 1.0} is
     * level, {@code 0} is on its side, negative is past vertical.
     *
     * <p>The formula lives here and nowhere else. Measured 2026-09-17 it had three copies: the
     * shared VS base's {@code upYOf}, and twice more inside one class — once as
     * {@code shipUpFromInfo} and once written out inline beside it — each re-spelling
     * {@code qx}/{@code qz} for itself.</p>
     */
    public double upY() {
        return 1.0 - 2.0 * (qx * qx + qz * qz);
    }

    /**
     * The same, or {@code NaN} when the reply carries no attitude at all — a ship this world does
     * not hold, or a report that answered nothing.
     *
     * <p>Separate from {@link #upY()} because of what a caller does with it: a precondition that
     * declines to measure on a TIPPED hull must not also decline on an UNREPORTED one, or a harness
     * problem is filed as a product defect. That distinction is this method's whole reason to exist,
     * and it is why an absent attitude here is not a refusal.</p>
     */
    public static double upYOrNaN(String shipInfoReply) {
        Reply reply = Reply.of("artest vs ship-info", String.valueOf(shipInfoReply));
        double ax = reply.number("qx");
        double az = reply.number("qz");
        if (Double.isNaN(ax) || Double.isNaN(az)) {
            return Double.NaN;
        }
        return 1.0 - 2.0 * (ax * ax + az * az);
    }

    /** Distance from this pose to a point, for the assertions that are about where a ship ended up. */
    public double distanceTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Everything that separates one reading from another, for a failure message. */
    @Override
    public String toString() {
        return "ship " + id + " @" + x + "," + y + "," + z + " omega=" + omega
                + (ready ? " ready" : " NOT-ready") + (blocks == null ? "" : " blocks=" + blocks);
    }

    /** The reply exactly as the probe sent it — for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }
}
