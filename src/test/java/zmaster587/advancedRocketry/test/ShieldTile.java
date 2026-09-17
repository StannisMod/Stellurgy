package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest shield read <dim> <x> <y> <z>} — the shield block standing at a
 * position, whichever of the four kinds it is.
 *
 * <p>Measured 2026-09-17: eight classes read this reply, and the {@code contains(
 * "\"powered\":true")} needle alone appears in six of them.</p>
 *
 * <h2>FOUR kinds answer this verb, and their fields do not overlap</h2>
 *
 * <p>An emitter reports a radius, a tier and a throughput; a generator reports what it can give; a
 * cable reports only its transport cap; an accumulator reports what it is holding and how much room
 * is left. A caller asking an emitter's {@code powered} of a CABLE gets absence, which reads as an
 * unpowered field — so {@link #kind} is the first thing this reader carries, and every accessor
 * below refuses when the block at that position is not the kind it belongs to.</p>
 *
 * <h2>Two THROUGHPUTS with one name</h2>
 *
 * <p>A cable's {@link #cableThroughput()} is a transport cap; an emitter's
 * {@link #rechargeThroughput()} is a per-zone regen cap. The producer spells both
 * {@code throughput} because they belong to different blocks, and the two limiters are exactly what
 * the balance tests compare — so reading one where the other was meant is the mistake this reader
 * exists to make impossible.</p>
 */
public final class ShieldTile {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The four kinds the verb answers, as the producer spells them. */
    public static final String EMITTER = "emitter";
    public static final String GENERATOR = "generator";
    public static final String CABLE = "cable";
    public static final String ACCUMULATOR = "accumulator";

    /** Which of the four kinds stands at the probed position. */
    public final String kind;
    /** Where this reading was taken. */
    public final int dim;
    public final int posX;
    public final int posY;
    public final int posZ;

    private final Reply reply;
    private final String raw;

    private ShieldTile(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.kind = reply.text("kind");
        this.dim = reply.integer("dim");
        this.posX = reply.integer("posX");
        this.posY = reply.integer("posY");
        this.posZ = reply.integer("posZ");
    }

    /**
     * Read one {@code shield read} reply, or refuse.
     *
     * <p>Refuses a world that is not loaded and a position holding something that is not a shield
     * block at all — both answer an {@code error} and no {@code kind}, and every field below would
     * then be absent, which reads as an unpowered, empty, zero-throughput block.</p>
     */
    public static ShieldTile of(String readReply) {
        String text = String.valueOf(readReply);
        Reply reply = Reply.of("artest shield read", text);
        if (!reply.has("kind")) {
            ArrangementFailure.arrangementFailed("`artest shield read` found no shield block at that"
                    + " position (" + reply.textOr("error", "no kind reported") + "), so every field"
                    + " below would be absent rather than zero: " + text);
        }
        return new ShieldTile(reply, text);
    }

    /** Read whatever shield block stands at this position. */
    public static ShieldTile at(Probe probe, int dim, int x, int y, int z) throws Exception {
        return of(probe.exec("artest shield read " + dim + " " + x + " " + y + " " + z));
    }

    /** Whether the block here is of {@code kind} — for a caller whose subject is which one it is. */
    public boolean is(String expectedKind) {
        return expectedKind.equals(kind);
    }

    /** This reading, refusing when the block is not the kind the caller meant. */
    public ShieldTile require(String expectedKind, String what) {
        if (!is(expectedKind)) {
            ArrangementFailure.arrangementFailed(what + " — the block at " + posX + "," + posY + ","
                    + posZ + " in dim " + dim + " is a " + kind + ", not a " + expectedKind
                    + ", and their fields do not overlap: " + raw);
        }
        return this;
    }

    // ---- emitter ----------------------------------------------------------------------------

    /** Whether the emitter's field is up. */
    public boolean powered() {
        return emitterField("powered").bool("powered", false);
    }

    /** The emitter's shell radius in blocks. */
    public int radius() {
        return emitterField("radius").integer("radius");
    }

    /** Its tier, and the per-zone recharge cap that tier buys. See the class note on throughput. */
    public int tier() {
        return emitterField("tier").integer("tier");
    }

    public long rechargeThroughput() {
        return (long) emitterField("throughput").number("throughput");
    }

    /** What it asked the network for this tick, and what actually arrived. */
    public long requested() {
        return (long) emitterField("requested").number("requested");
    }

    public long receivedThisTick() {
        return (long) emitterField("receivedThisTick").number("receivedThisTick");
    }

    /** What holding the field costs it per tick with nothing striking it. */
    public long maintenance() {
        return (long) emitterField("maintenance").number("maintenance");
    }

    /** Where the shell is centred in the WORLD — ship-transformed when the emitter rides a hull. */
    public double worldX() {
        return emitterField("worldX").number("worldX");
    }

    public double worldY() {
        return emitterField("worldY").number("worldY");
    }

    public double worldZ() {
        return emitterField("worldZ").number("worldZ");
    }

    /** Whether the shell is resolved in a ship's frame, and whether that frame answers right now. */
    public boolean shipFramed() {
        return emitterField("shipFramed").bool("shipFramed", false);
    }

    public boolean frameReady() {
        return emitterField("frameReady").bool("frameReady", false);
    }

    /** Where this emitter stands in the supply order when the network cannot feed everything. */
    public int priority() {
        return emitterField("priority").integer("priority");
    }

    /** The domain that owns it, the group that lists it, and the credential it carries. */
    public String domainId() {
        return emitterField("domainId").textOr("domainId", "");
    }

    public String group() {
        return emitterField("group").textOr("group", "");
    }

    public String accessCode() {
        return emitterField("accessCode").textOr("accessCode", "");
    }

    private Reply emitterField(String field) {
        require(EMITTER, "`" + field + "` is an emitter's field");
        return reply;
    }

    // ---- the energy-holding kinds -------------------------------------------------------------

    /**
     * How much shield energy this block holds.
     *
     * <p>Reported by the emitter, the generator and the accumulator — three different stores, which
     * is why the KIND is required beside it rather than assumed from the position.</p>
     */
    public long shieldStored() {
        requireStores("shieldStored");
        return (long) reply.number("shieldStored");
    }

    /** How much it can hold, for the two kinds that have a ceiling. */
    public long shieldMax() {
        requireStores("shieldMax");
        return (long) reply.number("shieldMax");
    }

    /** How much of what it holds it is willing to hand out this tick. */
    public long available() {
        requireStores("available");
        return (long) reply.number("available");
    }

    /** An accumulator's free capacity — how much it could still take. */
    public long free() {
        require(ACCUMULATOR, "`free` is an accumulator's field");
        return (long) reply.number("free");
    }

    /** A generator's raw FE buffer, which is not the same store as its shield energy. */
    public long feStored() {
        require(GENERATOR, "`feStored` is a generator's field");
        return (long) reply.number("feStored");
    }

    /** A cable's transport cap. See the class note: this is NOT the emitter's recharge cap. */
    public long cableThroughput() {
        require(CABLE, "`throughput` here is a cable's transport cap");
        return (long) reply.number("throughput");
    }

    private void requireStores(String field) {
        if (!reply.has(field)) {
            ArrangementFailure.arrangementFailed("a " + kind + " reports no `" + field + "`, so a"
                    + " zero read here would be about the reply rather than about the store: " + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return kind + " at " + posX + "," + posY + "," + posZ + " in dim " + dim;
    }
}
