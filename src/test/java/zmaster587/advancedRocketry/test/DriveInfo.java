package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest drive info <dim> <afcX> <afcY> <afcZ>} — what a ship's hyperdrive is
 * worth, what its bank holds, and what the jump gate says about going.
 *
 * <p>Measured 2026-09-17: three classes read this reply and spell fifteen of its twenty-one field
 * names between them. The widest of the three carried one {@code field(json, name)} of its own that
 * every read went through — a {@code Reply} delegate, so not a regex, but untyped: it answered an
 * {@code int} for six fields the producer writes as {@code long} (a bank's capacity, its charge, the
 * energy stored, the flight cost), and every caller assigned the result to a {@code long} as though
 * the width had been kept.</p>
 *
 * <h2>{@code hullOutsideWindow} is {@code 0} when the hull was never MEASURED</h2>
 *
 * <p>The producer writes {@code hullMeasured:false} beside it, and the zero is a placeholder, not a
 * count. Read as a count it says the whole hull sits inside the jump window — which is the reading
 * a pilot is allowed to act on, and the exact opposite of "nobody could work out where the hull
 * is". {@link #hullOutsideWindow()} refuses it; {@link #hullMeasured} is the question the zero is
 * really the answer to.</p>
 *
 * <h2>{@code message} is the four characters {@code null} when the gate had nothing to say</h2>
 *
 * <p>Not an absent field — the producer writes the word, the way {@code dim info} does. And the
 * message is a translation KEY, so a caller pinning that the pilot was warned about a particular
 * thing names the key; {@link #messageIs(String)} asks that question of the FIELD rather than
 * {@code contains}-ing the key over the whole reply, where it would also be satisfied by a key
 * appearing in some other field.</p>
 *
 * <h2>A reply about an empty block looks like a badly built ship</h2>
 *
 * <p>{@code drive info} answers for any position: with no flight computer there, {@code ShipDrive}
 * finds no generator and no capacitors and every number above is a legitimate-looking zero. The
 * probe now says which it is ({@code afc}), and {@link #of} refuses the reply that is about
 * nothing — the wrong coordinate in a fixture is otherwise reported as a drive that was built
 * wrong.</p>
 */
public final class DriveInfo {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** What the producer writes where the gate had no message. See the class note. */
    private static final String NO_MESSAGE = "null";

    /** What the generator is worth — the number every other quantity here is derived from. */
    public final long drivePower;
    /** What holding the window open costs per tick, and what opening it costs at once. */
    public final long inFlightDraw;
    public final long burstCost;
    /** How many capacitors belong to this ship, what they hold in all, and what is in them now. */
    public final int capacitors;
    public final long capacity;
    public final long charge;
    /** How long this bank still needs to reach the next burst. Zero is READY, not "no timer". */
    public final long cooldownTicks;
    /** How many window emitters and inertial dampeners belong to it. */
    public final int emitters;
    public final int dampeners;
    /** How many of those dampeners have power in their buffer — the ones that will protect anybody. */
    public final int poweredDampeners;
    /** Whether the hull's coverage could be worked out at all. See the class note. */
    public final boolean hullMeasured;
    /** Every unit of energy the ship's machines hold between them. */
    public final long storedEnergy;
    /** What the navigation computer plans: how fast, for how long, and at what cost. */
    public final long speedBlocksPerTick;
    public final long transitTicks;
    public final long flightEnergyCost;
    /** The jump gate's verdict: whether it may go, and whether the pilot must confirm first. */
    public final boolean allowed;
    public final boolean confirm;
    /** Whether the drive is winding up right now. */
    public final boolean spooling;

    private final Reply reply;
    private final String raw;

    private DriveInfo(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.drivePower = reply.longInteger("drivePower");
        this.inFlightDraw = reply.longInteger("inFlightDraw");
        this.burstCost = reply.longInteger("burstCost");
        this.capacitors = reply.integer("capacitors");
        this.capacity = reply.longInteger("capacity");
        this.charge = reply.longInteger("charge");
        this.cooldownTicks = reply.longInteger("cooldownTicks");
        this.emitters = reply.integer("emitters");
        this.dampeners = reply.integer("dampeners");
        this.poweredDampeners = reply.integer("poweredDampeners");
        this.hullMeasured = reply.bool("hullMeasured", false);
        this.storedEnergy = reply.longInteger("storedEnergy");
        this.speedBlocksPerTick = reply.longInteger("speedBlocksPerTick");
        this.transitTicks = reply.longInteger("transitTicks");
        this.flightEnergyCost = reply.longInteger("flightEnergyCost");
        this.allowed = reply.bool("allowed", false);
        this.confirm = reply.bool("confirm", false);
        this.spooling = reply.bool("spooling", false);
    }

    /**
     * Read one {@code drive info} reply, or refuse.
     *
     * <p>Refuses the verb's error shapes and the reply that is about an empty block — see the class
     * note on {@code afc}. The reader tolerates a reply with no {@code afc} field so it can be
     * pointed at a recording taken before the probe reported one, but it refuses an explicit
     * {@code false}.</p>
     */
    public static DriveInfo of(String infoReply) {
        String text = String.valueOf(infoReply);
        Reply reply = Reply.of("artest drive info", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest drive info` answered no drive ("
                    + reply.text("error") + "), so nothing below is a reading of one: " + text);
        }
        if (!reply.has("drivePower") || !reply.has("allowed")) {
            throw new AssertionError("this is not an `artest drive info` answer: a drive reading"
                    + " carries `drivePower` beside the gate's `allowed`, and a `build`, `charge` or"
                    + " `push` reply carries neither: " + text);
        }
        if (reply.has("afc") && !reply.bool("afc", true)) {
            ArrangementFailure.arrangementFailed("there is no flight computer at that position, so"
                    + " there is no ship whose drive this could be — every zero below is an empty"
                    + " block and not a badly built craft: " + text);
        }
        return new DriveInfo(reply, text);
    }

    /** Ask one ship's drive what it is. {@code where} is {@code <dim> <afcX> <afcY> <afcZ>}. */
    public static DriveInfo at(Probe probe, String where) throws Exception {
        return of(probe.exec("artest drive info " + where));
    }

    /**
     * How many blocks of the hull stand OUTSIDE the jump window, refusing when it was never
     * measured. See the class note: the producer's zero there is a placeholder.
     */
    public long hullOutsideWindow() {
        if (!hullMeasured) {
            ArrangementFailure.arrangementFailed("the hull's coverage was never measured on this"
                    + " ship, so there is no count of what sits outside the window — the probe"
                    + " answers 0 there, which reads as a hull that fits: " + raw);
        }
        return reply.longInteger("hullOutsideWindow");
    }

    /** Whether the gate had anything to say to the pilot at all. */
    public boolean hasMessage() {
        String value = reply.text("message");
        return value != null && !NO_MESSAGE.equals(value);
    }

    /** The gate's first message — a translation key. Refuses when it had none. */
    public String message() {
        if (!hasMessage()) {
            throw new AssertionError("the jump gate said nothing, so there is no message to read —"
                    + " the probe answers the four characters \"null\" there: " + raw);
        }
        return reply.text("message");
    }

    /**
     * Whether the gate's message is {@code key}.
     *
     * <p>Asked of the FIELD: the three call sites this replaces searched the whole reply for the
     * key, which is also satisfied by the key turning up anywhere else in it.</p>
     */
    public boolean messageIs(String key) {
        return hasMessage() && message().equals(key);
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "drive power=" + drivePower + " charge=" + charge + "/" + capacity
                + " cooldown=" + cooldownTicks + " allowed=" + allowed + " confirm=" + confirm
                + (hullMeasured ? " outsideWindow=" + reply.longInteger("hullOutsideWindow")
                        : " hull not measured");
    }
}
