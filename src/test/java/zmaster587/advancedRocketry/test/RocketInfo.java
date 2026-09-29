package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest rocket info <entityId>} — everything the server knows about one
 * craft, read by field name.
 *
 * <p>Measured 2026-09-17, after seven producers had already been converted: this is the widest
 * producer left in the tier. Sixteen classes ask the verb and THIRTEEN of them spell its field names
 * as string literals of their own, across twenty distinct names — {@code isInFlight} alone appears
 * in ten of them, each time as {@code contains("\"isInFlight\":true")}. Every copy is a separate
 * place for the producer's vocabulary to be re-learned, and none of them can say what the reply
 * meant when a name was missing.</p>
 *
 * <h2>The reply has TWO shapes, and the second one carries no craft at all</h2>
 *
 * <p>A known entity id answers a report. An id the server cannot resolve answers
 * {@code {"error":"rocket not found","entityId":N}} — no pose, no flight state, nothing. A caller
 * that reaches straight for {@code isInFlight} on that second shape does not fail: the field is
 * absent, a {@code contains} answers {@code false}, and the test then reports that the craft is not
 * flying. That is a sentence about the world, manufactured out of a craft that was never found.</p>
 *
 * <p>So the discrimination lives here, once. {@link #of} refuses the not-found shape as an
 * {@link ArrangementFailure} — the craft this reading is about was never built, which is a different
 * finding from the product having flown it wrongly — and {@link #notFound} is how a test whose
 * SUBJECT is the refusal asks for it, so that "the probe does not know this id" stays an answer
 * rather than becoming an exception everywhere.</p>
 *
 * <h2>{@code isInFlight} is not {@code isInOrbit}, and neither is {@code flightMode}</h2>
 *
 * <p>Production keeps three separate things: whether the craft is off the pad, whether it has
 * reached orbit, and which of the two flight systems is driving it. A launch sets the first; the
 * orbit transition sets the second; {@link #flightMode} is {@code CLASSIC_LAUNCH} or
 * {@code FREE_FLIGHT} and says nothing about either. They are carried here as three fields for the
 * same reason the producer emits three.</p>
 *
 * <h2>The free-flight input block is ABSENT, not zero, when nothing is flying the craft</h2>
 *
 * <p>{@code ffInput*} is emitted only while {@code getCurrentFreeFlightInput()} answers an object.
 * A reader that defaulted the missing block to zeros would make "nobody is holding the stick"
 * indistinguishable from "the stick is centred" — the exact question a free-flight test asks. So the
 * block is a value of its own: {@link #hasFreeFlightInput()} asks whether it is there, and
 * {@link #freeFlightInput()} refuses when it is not.</p>
 */
public final class RocketInfo {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The two names {@code flightMode} takes, as production spells them. */
    public static final String CLASSIC_LAUNCH = "CLASSIC_LAUNCH";
    public static final String FREE_FLIGHT = "FREE_FLIGHT";

    /**
     * The eight live stick inputs, present only while something is flying the craft.
     *
     * <p>See the class note: this is a value of its own precisely so that its ABSENCE cannot be read
     * as a centred stick.</p>
     */
    public static final class FreeFlightInput {
        public final double forward;
        public final double vertical;
        public final double strafe;
        public final double yaw;
        public final double pitch;
        public final double roll;
        public final double brake;
        public final boolean cut;

        private FreeFlightInput(Reply reply) {
            this.forward = reply.number("ffInputFwd");
            this.vertical = reply.number("ffInputVert");
            this.strafe = reply.number("ffInputStrafe");
            this.yaw = reply.number("ffInputYaw");
            this.pitch = reply.number("ffInputPitch");
            this.roll = reply.number("ffInputRoll");
            this.brake = reply.number("ffInputBrake");
            this.cut = reply.bool("ffInputCut");
        }

        @Override
        public String toString() {
            return "ffInput fwd=" + forward + " vert=" + vertical + " strafe=" + strafe
                    + " yaw=" + yaw + " pitch=" + pitch + " roll=" + roll
                    + " brake=" + brake + " cut=" + cut;
        }
    }

    /** The craft's entity id, as the reply echoed it back. */
    public final int entityId;
    /** The FQN of the entity class, which is what tells a station-deployed craft from a rocket. */
    public final String entityClass;
    /** Which world the craft is in, and where in it. */
    public final int dim;
    public final double posX;
    public final double posY;
    public final double posZ;
    /** Off the pad. Says nothing about orbit, and nothing about which system is flying it. */
    public final boolean inFlight;
    /** Orbit reached. See the class note: this is a separate edge from {@link #inFlight}. */
    public final boolean inOrbit;
    /** {@link #CLASSIC_LAUNCH} or {@link #FREE_FLIGHT}. */
    public final String flightMode;
    /** Ticks since the entity was spawned — how a craft built now is told from one left behind. */
    public final int ticksExisted;
    /** The countdown {@code prepareLaunch()} sets, or production's own {@code -1} when it did not. */
    public final int launchCounter;
    /** Where a launch is aimed. */
    public final int destinationDim;
    /**
     * What {@code setError(...)} last said, or the empty string when the craft reported nothing.
     *
     * <p>Empty is the producer's own "no error", and it is a DIFFERENT thing from the field being
     * absent — which happens only in a reply that is not a craft report at all, and which
     * {@link #of} refuses before a caller can see it.</p>
     */
    public final String errorMessage;
    /** Whether the flight-assist loop is holding a setpoint. */
    public final boolean flightAssistOn;
    /** Whether the craft carries a storage chunk at all; everything below reads {@code -1}/false without one. */
    public final boolean hasStorage;
    /** Whether a tier-2 Advanced Flight Computer rode along in the built hull. */
    public final boolean advancedFlightComputerPresent;
    /** Whether a guidance computer is in the hull, and whether its chip slot is filled. */
    public final boolean guidanceComputerPresent;
    public final boolean guidanceComputerSlotOccupied;
    /** Aggregates {@code StatsRocket} computed during the assembly scan. */
    public final int thrust;
    public final double weightNoFuel;
    public final double breakingProb;
    public final int seatCount;
    public final int engineCount;
    public final int fuelTankCount;
    public final double drillingPower;
    /** Where the craft is going, world frame, blocks per tick. */
    public final double motionX;
    public final double motionY;
    public final double motionZ;
    /** Where it is pointing: the entity's yaw, and the two free-flight attitude angles. */
    public final double rotationYaw;
    public final double freeFlightPitch;
    public final double freeFlightRoll;
    /** Thrust magnitude in {@code [0,1]} — what the client engine sound is driven from. */
    public final double enginePower;

    private final String uuid;
    private final FreeFlightInput freeFlightInput;
    private final Reply reply;
    private final String raw;

    private RocketInfo(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.entityId = reply.integer("entityId");
        this.uuid = reply.text("uuid");
        this.entityClass = reply.text("entityClass");
        this.dim = reply.integer("dim");
        this.posX = reply.number("posX");
        this.posY = reply.number("posY");
        this.posZ = reply.number("posZ");
        this.inFlight = reply.bool("isInFlight");
        this.inOrbit = reply.bool("isInOrbit");
        this.flightMode = reply.text("flightMode");
        this.ticksExisted = reply.integer("ticksExisted");
        this.launchCounter = reply.integer("launchCounter");
        this.destinationDim = reply.integer("destinationDim");
        this.errorMessage = reply.text("errorMessage");
        this.flightAssistOn = reply.bool("flightAssistOn");
        this.hasStorage = reply.bool("hasStorage");
        this.advancedFlightComputerPresent = reply.bool("advancedFlightComputerPresent");
        this.guidanceComputerPresent = reply.bool("guidanceComputerPresent");
        this.guidanceComputerSlotOccupied = reply.bool("guidanceComputerSlotOccupied");
        this.thrust = reply.integer("thrust");
        this.weightNoFuel = reply.number("weight_no_fuel");
        this.breakingProb = reply.number("breakingProb");
        this.seatCount = reply.integer("seatCount");
        this.engineCount = reply.integer("engineCount");
        this.fuelTankCount = reply.integer("fuelTankCount");
        this.drillingPower = reply.number("drillingPower");
        this.motionX = reply.number("motionX");
        this.motionY = reply.number("motionY");
        this.motionZ = reply.number("motionZ");
        this.rotationYaw = reply.number("rotationYaw");
        this.freeFlightPitch = reply.number("freeFlightPitch");
        this.freeFlightRoll = reply.number("freeFlightRoll");
        this.enginePower = reply.number("enginePower");
        this.freeFlightInput = reply.has("ffInputFwd") ? new FreeFlightInput(reply) : null;
    }

    /**
     * Read one {@code rocket info} reply, or refuse.
     *
     * <p>Refuses two things, each of which would otherwise be read as a measurement about the craft:
     * a reply that is not this verb's at all, and the not-found shape — an id the server could not
     * resolve, which carries no flight state to read. A test whose subject IS that refusal asks
     * {@link #notFound} instead.</p>
     */
    public static RocketInfo of(String infoReply) {
        String text = String.valueOf(infoReply);
        Reply reply = Reply.of("artest rocket info", text);
        if (isNotFound(reply)) {
            // absence is the answer here: this is the not-found shape, and the id is being
            // named in a message that must reach the reader whatever the reply carried. A
            // refusal in a failure message replaces the arrangement's own diagnosis with the
            // reader's.
            ArrangementFailure.arrangementFailed("`artest rocket info` does not know entity "
                    + reply.reported("entityId") + ", so nothing here is a"
                    + " reading of a craft — the craft was never built, or something else removed"
                    + " it: " + text);
        }
        if (!reply.has("entityId") || !reply.has("isInFlight")) {
            throw new AssertionError("this is not an `artest rocket info` report: a craft report"
                    + " carries `entityId` and `isInFlight`, so reading flight state out of this"
                    + " would be a claim about the world built from the wrong reply: " + text);
        }
        return new RocketInfo(reply, text);
    }

    /** Ask the server about one craft, and read what it answers. */
    public static RocketInfo byId(Probe probe, int entityId) throws Exception {
        return of(probe.exec("artest rocket info " + entityId));
    }

    /**
     * Whether this reply is the probe's "no such craft" answer.
     *
     * <p>For the tests whose subject is exactly that — a craft removed, an id never minted. Refuses
     * a reply that is neither shape, so a {@code false} here means "the server answered about a
     * craft" and never "the reply was unreadable".</p>
     */
    public static boolean notFound(String infoReply) {
        String text = String.valueOf(infoReply);
        Reply reply = Reply.of("artest rocket info", text);
        if (isNotFound(reply)) {
            return true;
        }
        if (!reply.has("entityId")) {
            throw new AssertionError("this is neither an `artest rocket info` report nor its"
                    + " not-found answer, so it says nothing about whether the craft exists: "
                    + text);
        }
        return false;
    }

    private static boolean isNotFound(Reply reply) {
        return reply.has("error") && !reply.has("isInFlight");
    }

    /**
     * The craft's persistent UUID, refusing when the reply carried an empty one.
     *
     * <p>A {@code require} and not a field because this value TRAVELS: it is pasted into
     * {@code artest rocket find-by-uuid <uuid>}, and an empty one would go out as nothing at all
     * and come back as "no craft has that id". An ABSENT uuid is refused a level lower, by the
     * reader that built this object.</p>
     */
    public String requireUuid() {
        if (uuid.isEmpty()) {
            throw new AssertionError("`artest rocket info` answered no uuid for entity " + entityId
                    + ", and this id is about to be asked of another verb: " + raw);
        }
        return uuid;
    }

    /** Whether something is flying the craft right now. See the class note. */
    public boolean hasFreeFlightInput() {
        return freeFlightInput != null;
    }

    /** The live stick inputs, refusing when nothing is flying the craft. */
    public FreeFlightInput freeFlightInput() {
        if (freeFlightInput == null) {
            throw new AssertionError("entity " + entityId + " reports no free-flight input block,"
                    + " so its stick positions are not zero — there are none: " + raw);
        }
        return freeFlightInput;
    }

    /** Whether the flight-assist velocity setpoint is reported at all. */
    public boolean hasFaSetpoint() {
        return reply.has("faSetpointFwd");
    }

    /** The flight-assist velocity setpoint, body frame, blocks per tick. */
    public double faSetpointForward() {
        return reply.number("faSetpointFwd");
    }

    public double faSetpointRight() {
        return reply.number("faSetpointRight");
    }

    public double faSetpointUp() {
        return reply.number("faSetpointUp");
    }

    /**
     * The storage chunk's dimensions in blocks, refusing when the craft carries no storage.
     *
     * <p>Emitted only inside the producer's {@code storage != null} branch, so a caller reading them
     * off a storage-less craft would be reading absence as a size of zero.</p>
     */
    public int storageSizeX() {
        return storageSize("storageSizeX");
    }

    public int storageSizeY() {
        return storageSize("storageSizeY");
    }

    public int storageSizeZ() {
        return storageSize("storageSizeZ");
    }

    /**
     * The storage chunk's volume in blocks, refusing when the craft carries no storage.
     *
     * <p>Production answers {@code -1} for a storage-less craft, which is a refusal written as a
     * number; it is refused here rather than handed on as a size.</p>
     */
    public int storageChunkSize() {
        return storageSize("storageChunkSize");
    }

    private int storageSize(String field) {
        if (!hasStorage) {
            throw new AssertionError("entity " + entityId + " carries no storage chunk, so `" + field
                    + "` is not a size — the craft has no hull to measure: " + raw);
        }
        return reply.integer(field);
    }

    /** Whether production reported an error on this craft's last launch attempt. */
    public boolean hasError() {
        return !errorMessage.isEmpty();
    }

    /**
     * Total fuel capacity across every registered fuel type.
     *
     * <p>Asked as "every entry" and not by type name: the types are the registry's, so a test that
     * named one would be pinning the registry's contents while meaning to ask about the hull.</p>
     */
    public long fuelCapacityTotal() {
        long total = 0;
        for (String perType : reply.objectValues("fuel")) {
            total += (long) Reply.of("artest rocket info fuel entry", perType)
                    .number("capacity");
        }
        return total;
    }

    /** Total fuel currently aboard, across every registered fuel type. */
    public long fuelAmountTotal() {
        long total = 0;
        for (String perType : reply.objectValues("fuel")) {
            total += (long) Reply.of("artest rocket info fuel entry", perType)
                    .number("amount");
        }
        return total;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "rocket " + entityId + " dim=" + dim + " inFlight=" + inFlight + " inOrbit=" + inOrbit
                + " mode=" + flightMode + " age=" + ticksExisted
                + (hasError() ? " error=" + errorMessage : "");
    }
}
