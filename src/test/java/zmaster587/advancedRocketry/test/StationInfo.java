package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest station info <id>} — where a space station is, what it is holding,
 * and what its controllers are steering it toward.
 *
 * <p>Measured 2026-09-17: twelve classes ask this verb and seven read fields out of the reply,
 * spelling seventeen field names between them. Almost every read was a {@code contains} on a
 * rendered pair ({@code "\"padCount\":1"}, {@code "\"fuelAmount\":40"}), which is a substring test
 * against a number and therefore a PREFIX test: {@code "\"padCount\":1"} is satisfied by a station
 * with 10 pads, and {@code "\"fuelAmount\":40"} by one holding 400.</p>
 *
 * <h2>The reply has THREE blocks and two of them are conditional</h2>
 *
 * <p>The head ({@code id}, {@code orbitingPlanetId}, {@code destOrbitingBody},
 * {@code orbitalDistance}, {@code isAnchored}, {@code transitionTime}) is always there. The SPAWN
 * block exists only for a station that has one. The STATION block — the fuel, the pads, the
 * controllers' targets and the rotation — exists only for a {@code SpaceStationObject}, and an
 * {@code ISpaceObject} that is not one answers the head and nothing else. A caller reaching for
 * {@code fuelAmount} on that is not told it asked the wrong question, so each block is entered
 * through an accessor that refuses when its block is absent.</p>
 *
 * <h2>{@code padCount} is written by two OTHER verbs as well</h2>
 *
 * <p>{@code station add-pad} and {@code station remove-pad} each echo it. So a substring read of
 * {@code padCount} answers off whichever reply is nearest to hand, and one class asserted a pad
 * count against an {@code add-pad} reply while its comment described the station. This reader is
 * for {@code station info}; the other two verbs are read as their own replies.</p>
 *
 * <h2>The error reply carries the {@code id} that was asked for</h2>
 *
 * <p>{@code {"error":"station not found","id":N}} — so a {@code contains} on the id is satisfied by
 * the station NOT existing, which is the one thing most of these tests are arranging against.
 * {@link #of} refuses it.</p>
 */
public final class StationInfo {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The station's own id, as the reply echoed it back. */
    public final int id;
    /** Which body it is orbiting now, and which one it is on its way to. */
    public final int orbitingPlanetId;
    public final int destOrbitingBody;
    /**
     * How far out it is orbiting, in AR's own units.
     *
     * <p>A {@code float} on the producer's side, and read as one here: the altitude controller walks
     * it {@code 0.02} per tick, so an integer reading of it reports a station that did not move for
     * the first fifty ticks of every climb.</p>
     */
    public final double orbitalDistance;
    /** Whether it is anchored — an anchored station does not drift toward its destination. */
    public final boolean anchored;
    /** How long its current transition still has to run, in ticks. A {@code long} at the source. */
    public final long transitionTime;

    private final Reply reply;
    private final String raw;

    private StationInfo(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.id = reply.integer("id");
        this.orbitingPlanetId = reply.integer("orbitingPlanetId");
        this.destOrbitingBody = reply.integer("destOrbitingBody");
        this.orbitalDistance = reply.number("orbitalDistance");
        this.anchored = reply.bool("isAnchored");
        this.transitionTime = reply.longInteger("transitionTime");
    }

    /**
     * Read one {@code station info} reply, or refuse.
     *
     * <p>Refuses {@code {"error":"station not found","id":N}}. See the class note: that reply
     * carries the id, so the substring form these call sites used could be answered by the absence
     * of the very station under test.</p>
     */
    public static StationInfo of(String infoReply) {
        String text = String.valueOf(infoReply);
        Reply reply = Reply.of("artest station info", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest station info` found no station ("
                    + reply.text("error") + "), so nothing below is a reading of one — and the reply"
                    + " still carries the id that was asked for: " + text);
        }
        if (!reply.has("id") || !reply.has("orbitingPlanetId")) {
            throw new AssertionError("this is not an `artest station info` answer: a station reading"
                    + " carries `orbitingPlanetId` beside its `id`, and `add-pad` / `remove-pad`"
                    + " carry an id without one: " + text);
        }
        return new StationInfo(reply, text);
    }

    /** Ask the server about one station. */
    public static StationInfo byId(Probe probe, int stationId) throws Exception {
        return of(probe.exec("artest station info " + stationId));
    }

    /** Whether the reply carries the spawn block at all. */
    public boolean hasSpawn() {
        return reply.has("spawnX");
    }

    /** The station's spawn point. Refuses when it has none — a zero here would be a coordinate. */
    public int spawnX() {
        return spawn("spawnX");
    }

    public int spawnY() {
        return spawn("spawnY");
    }

    public int spawnZ() {
        return spawn("spawnZ");
    }

    private int spawn(String field) {
        if (!hasSpawn()) {
            throw new AssertionError("station " + id + " reports no spawn location: " + raw);
        }
        return reply.integer(field);
    }

    /**
     * Whether this is a full space STATION rather than some other space object.
     *
     * <p>The station block below exists exactly then. An {@code ISpaceObject} that is not a
     * {@code SpaceStationObject} has no fuel, no pads and no controllers, and every field of them
     * is absent rather than zero.</p>
     */
    public boolean isStationObject() {
        return reply.has("fuelAmount");
    }

    /** What its warp tank holds, and what it holds at most. */
    public int fuelAmount() {
        return stationField("fuelAmount");
    }

    public int fuelMax() {
        return stationField("fuelMax");
    }

    /** How many landing pads it has. See the class note on the two other verbs writing this. */
    public int padCount() {
        return stationField("padCount");
    }

    /** Whether any of them is free for a rocket to land on. */
    public boolean hasFreePad() {
        requireStation("hasFreePad");
        return reply.bool("hasFreePad");
    }

    /** The orbital distance its altitude controller is steering toward. */
    public int targetOrbitalDistance() {
        return stationField("targetOrbitalDistance");
    }

    /**
     * The gravitational MULTIPLIER it has now — {@code 1.0} is Earth-like.
     *
     * <p>Not the same quantity as {@link #targetGravityPercent()}, and the difference is the point:
     * the controller's target is stored as an integer PERCENT (its own range is 10..100), while
     * what the station has is the float multiplier. Comparing the two directly is off by a hundred.
     * </p>
     */
    public double gravity() {
        requireStation("gravity");
        return reply.number("gravity");
    }

    /** What its gravity controller is steering toward, as a PERCENT of Earth. See {@link #gravity()}. */
    public int targetGravityPercent() {
        return stationField("targetGravity");
    }

    /** Its live spin about one axis, in the producer's own units. */
    public double rotationEast() {
        return rotation("rotationEast");
    }

    public double rotationUp() {
        return rotation("rotationUp");
    }

    public double rotationNorth() {
        return rotation("rotationNorth");
    }

    /**
     * The rotations-per-hour its rotation controller is steering toward, by AXIS INDEX.
     *
     * <p>{@code 0}, {@code 1}, {@code 2} are the producer's own {@code targetRotationsPerHour}
     * slots, in that array's order — which is the only thing the reply says about them, so the
     * reader does not invent axis names for them.</p>
     */
    public int targetRotationsPerHour(int axis) {
        return stationField("targetRPH" + axis);
    }

    private double rotation(String field) {
        requireStation(field);
        return reply.number(field);
    }

    private int stationField(String field) {
        requireStation(field);
        return reply.integer(field);
    }

    private void requireStation(String field) {
        if (!isStationObject()) {
            throw new AssertionError("space object " + id + " is not a space STATION, so `" + field
                    + "` is not something it has — it has no fuel, no pads and no controllers: "
                    + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "station " + id + " orbiting=" + orbitingPlanetId + " dest=" + destOrbitingBody
                + " distance=" + orbitalDistance + " anchored=" + anchored
                + (isStationObject() ? " fuel=" + reply.integer("fuelAmount")
                        + " pads=" + reply.integer("padCount") : " (not a station object)");
    }
}
