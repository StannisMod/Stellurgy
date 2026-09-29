package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest vs player-ship-data} — what the physics substrate holds about one
 * body: which hull it is inside, what velocity it inherited, and where it actually is.
 *
 * <p>Measured 2026-09-17: four classes read this reply across twenty-four call sites, spelling six
 * of its field names. {@link ShipIdentity} already owned one of them — {@code shipId}, as
 * {@code aboardShipOf} — and this class takes the whole reply instead, so the branch rules live
 * with the fields they govern.</p>
 *
 * <h2>{@code available:false} is a THIRD shape, and it is not "no ship"</h2>
 *
 * <p>The verb answers three ways: an error when there is no subject at all, {@code available:false}
 * when the substrate holds no movement data for the subject, and the report. The middle one is a
 * statement about the INSTRUMENT — the physics mod was asked and had nothing — while every field a
 * caller then reads is absent, which looks exactly like a body standing on ordinary ground. So
 * {@link #of} refuses the first two, and a caller whose subject is the absence asks
 * {@link #dataAvailable}.</p>
 *
 * <h2>Two velocities, cleared by different things</h2>
 *
 * <p>{@link #addedVelX} and its siblings are what the SHIP lent the body; {@code motion*} is the
 * body's own. An inherited velocity dies when the body lands, its own motion does not — so a
 * drifting body attributed to the wrong one of these is attributed to the wrong cause, which is why
 * the producer reports both and this reader keeps them apart by name.</p>
 */
public final class PlayerShipData {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether the substrate holds a physics object for the ship the body is inside. */
    public final boolean shipLoaded;
    /** Whether the body is mounted on something the substrate knows. */
    public final boolean mounted;
    /** Where the body is, and whether it is standing on anything. */
    public final double playerX;
    public final double playerY;
    public final double playerZ;
    public final boolean onGround;
    /** The velocity the SHIP lent it this tick. See the class note. */
    public final double addedVelX;
    public final double addedVelY;
    public final double addedVelZ;
    public final double addedYawVelocity;
    /** The body's OWN motion, which is a different thing and cleared by different events. */
    public final double motionX;
    public final double motionY;
    public final double motionZ;
    /** How long since it touched a hull, and how long it has counted as part of one's ground. */
    public final int ticksSinceTouchedShip;
    public final int ticksPartOfGround;

    private final Reply reply;
    private final String raw;

    private PlayerShipData(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.shipLoaded = reply.bool("shipLoaded");
        this.mounted = reply.bool("mounted");
        this.playerX = reply.number("playerX");
        this.playerY = reply.number("playerY");
        this.playerZ = reply.number("playerZ");
        this.onGround = reply.bool("playerOnGround");
        this.addedVelX = reply.number("addedVelX");
        this.addedVelY = reply.number("addedVelY");
        this.addedVelZ = reply.number("addedVelZ");
        this.addedYawVelocity = reply.number("addedYawVelocity");
        this.motionX = reply.number("motionX");
        this.motionY = reply.number("motionY");
        this.motionZ = reply.number("motionZ");
        this.ticksSinceTouchedShip = reply.integer("ticksSinceTouchedShip");
        this.ticksPartOfGround = reply.integer("ticksPartOfGround");
    }

    /**
     * Read one {@code player-ship-data} reply, or refuse.
     *
     * <p>Refuses the error shape and the {@code available:false} shape — see the class note: both
     * leave every field below absent, and absence here reads as an ordinary body on ordinary
     * ground.</p>
     */
    public static PlayerShipData of(String dataReply) {
        String text = String.valueOf(dataReply);
        Reply reply = Reply.of("artest vs player-ship-data", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest vs player-ship-data` had no subject to"
                    + " report on (" + reply.text("error") + "): " + text);
        }
        // absence is the answer: the REPORT shape carries no `available` at all — the field
        // exists only on the third shape, `{"available":false}`, so missing means a report.
        if (!reply.boolOr("available", true)) {
            ArrangementFailure.arrangementFailed("the physics substrate holds no movement data for"
                    + " this body, so every field below would be absent rather than zero — that is"
                    + " a statement about the instrument, not about the body: " + text);
        }
        if (!reply.has("shipLoaded")) {
            throw new AssertionError("this is not an `artest vs player-ship-data` answer: it carries"
                    + " no `shipLoaded`: " + text);
        }
        return new PlayerShipData(reply, text);
    }

    /** Whether the substrate answered at all — for a caller whose subject IS the absence. */
    public static boolean dataAvailable(String dataReply) {
        Reply reply = Reply.of("artest vs player-ship-data", String.valueOf(dataReply));
        // absence is the answer, for the same reason as in `of` above.
        return !reply.has("error") && reply.boolOr("available", true) && reply.has("shipLoaded");
    }

    /** Ask about the FIRST player — the probe's own default subject. */
    public static PlayerShipData read(Probe probe) throws Exception {
        return of(probe.exec("artest vs player-ship-data"));
    }

    /** Ask about one NAMED entity, which is what a scenario with more than one body must do. */
    public static PlayerShipData byId(Probe probe, int dim, int entityId) throws Exception {
        return of(probe.exec("artest vs player-ship-data " + dim + " " + entityId));
    }

    /**
     * The ship the body is INSIDE, refusing when the substrate holds none loaded.
     *
     * <p>A {@code require} because this id is what tells a scenario's own craft from the hulls its
     * siblings left in the same world — the whole reason the field is read at all.</p>
     */
    public String requireShipId() {
        // absence is the answer: the producer writes `shipId` as JSON null when no loaded hull
        // holds the body, and the refusal below is this verb's own diagnosis of that.
        String shipId = reply.textOr("shipId", null);
        if (!shipLoaded || shipId == null || shipId.isEmpty()) {
            throw new AssertionError("no loaded hull holds this body, so there is no ship to name —"
                    + " an empty id would travel into the next command as the four characters"
                    + " `null`: " + raw);
        }
        return shipId;
    }

    /** Whether the body is inside {@code shipId} — the question a shared world makes necessary. */
    public boolean aboard(String shipId) {
        return shipId != null && shipLoaded && shipId.equals(reply.text("shipId"));
    }

    /** Fail unless the body is inside {@code expectedShipId}. */
    public void requireAboard(String expectedShipId, String what) {
        if (expectedShipId == null) {
            throw new AssertionError("this assertion cannot mean anything without the scenario's own"
                    + " ship id — it was null: " + raw);
        }
        String actual = requireShipId();
        if (!expectedShipId.equals(actual)) {
            throw new AssertionError(what + " — the body is inside a DIFFERENT craft than this"
                    + " scenario's, and every flag in the reply reads the same either way: " + raw);
        }
    }

    /**
     * The body's position in the ship's SUBSPACE frame, refusing when no hull is loaded for it.
     *
     * <p>Only present while a physics object exists: the transform that produces it is that
     * object's. A zero read off its absence is a coordinate at the shipyard origin, which is a real
     * place — so this refuses instead.</p>
     */
    public double localX() {
        return local("localX");
    }

    public double localY() {
        return local("localY");
    }

    public double localZ() {
        return local("localZ");
    }

    private double local(String field) {
        if (!shipLoaded || !reply.has(field)) {
            throw new AssertionError("no loaded hull holds this body, so `" + field + "` is not a"
                    + " ship-frame coordinate — there is no frame: " + raw);
        }
        return reply.number(field);
    }

    /**
     * The last hull this body TOUCHED, or {@code null} when it has touched none.
     *
     * <p>Not the same question as {@link #requireShipId()}: a body that has walked off a deck still
     * names the hull it left, which is what {@link #ticksSinceTouchedShip} counts from.</p>
     */
    public String lastTouchedShip() {
        // absence is the answer: a body that has touched no hull gets JSON null here, and
        // "none" is exactly what this verb reports.
        return reply.textOr("lastTouchedShip", null);
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "player-ship-data shipLoaded=" + shipLoaded + " mounted=" + mounted
                + " onGround=" + onGround + " y=" + playerY;
    }
}
