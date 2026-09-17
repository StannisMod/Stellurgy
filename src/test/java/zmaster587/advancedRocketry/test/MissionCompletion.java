package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest mission complete-now <missionId>} — what a mission's completion did,
 * and what the returning rocket carries.
 *
 * <p>Measured 2026-09-17: five classes read this reply and spell nine of its field names between
 * them, including the substring pair {@code contains("\"rocketCount\":") && !contains(
 * "\"rocketCount\":0")}, which is "a positive count" written as two string tests.</p>
 *
 * <h2>{@code completed} is the EDGE, and the two flags either side of it are not it</h2>
 *
 * <p>The producer reports {@link #wasDeadBefore} and {@link #isDeadAfter} separately and derives
 * {@link #completed} as {@code !wasDeadBefore && isDeadAfter} — the mission ENDED on this call. A
 * mission already finished before the call answers {@code isDeadAfter:true} and completed nothing,
 * so a caller reading the end state as the event sees a success for a call that did nothing.</p>
 *
 * <h2>The cargo is counted at the LAUNCH pad, and it can fail on its own</h2>
 *
 * <p>{@link #rocketCount} and the entry counts come from a box scan around the mission's launch
 * coordinates, taken inside the same call so the registry's prune cannot race it. That scan has its
 * own failure — a launch dimension that is not loaded answers every count as zero and says so in
 * {@code cargoError} — which is why {@link #requireCargoScanned} exists: three zeros from an
 * unloaded world read exactly like a rocket that came back empty.</p>
 */
public final class MissionCompletion {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The mission this reading is about, as the reply echoed it back. */
    public final long missionId;
    /** Whether the mission had already ended before this call, and whether it has ended now. */
    public final boolean wasDeadBefore;
    public final boolean isDeadAfter;
    /** Whether the mission ended ON THIS CALL — the edge, not the state. See the class note. */
    public final boolean completed;
    /** Where the mission was launched from. */
    public final int launchDim;
    /** How many craft the launch-pad scan found, and how much cargo they carry. */
    public final int rocketCount;
    public final int fluidEntries;
    public final int itemEntries;
    /** How many infrastructure tiles the returning craft still considers linked. */
    public final int infraEntries;

    private final Reply reply;
    private final String raw;

    private MissionCompletion(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.missionId = (long) reply.number("missionId");
        this.wasDeadBefore = reply.bool("wasDeadBefore", false);
        this.isDeadAfter = reply.bool("isDeadAfter", false);
        this.completed = reply.bool("completed", false);
        this.launchDim = reply.integer("launchDim");
        this.rocketCount = reply.integer("rocketCount");
        this.fluidEntries = reply.integer("fluidEntries");
        this.itemEntries = reply.integer("itemEntries");
        this.infraEntries = reply.integer("infraEntries");
    }

    /**
     * Read one {@code complete-now} reply, or refuse.
     *
     * <p>Refuses the verb's two error shapes — a mission id nothing answers to, and a mission
     * dimension that is not loaded. Neither carries a completion or a cargo count, so every number
     * below would read as a zero about a mission that was never ticked.</p>
     */
    public static MissionCompletion of(String completionReply) {
        String text = String.valueOf(completionReply);
        Reply reply = Reply.of("artest mission complete-now", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest mission complete-now` completed nothing ("
                    + reply.text("error") + "), so the counts this reading is about belong to no"
                    + " mission: " + text);
        }
        if (!reply.has("completed")) {
            throw new AssertionError("this is not an `artest mission complete-now` answer: it"
                    + " carries no `completed`, so nothing in it says a mission ended: " + text);
        }
        return new MissionCompletion(reply, text);
    }

    /** Finish one mission now, and read what that did. */
    public static MissionCompletion now(Probe probe, long missionId) throws Exception {
        return of(probe.exec("artest mission complete-now " + missionId));
    }

    /**
     * This reading, refusing when the launch-pad scan could not run.
     *
     * <p>A caller about to assert on cargo asks for this first: an unloaded launch dimension answers
     * every count as zero, which is what an empty rocket looks like.</p>
     */
    public MissionCompletion requireCargoScanned(String what) {
        String cargoError = reply.text("cargoError");
        if (cargoError != null) {
            ArrangementFailure.arrangementFailed(what + " — the launch-pad cargo scan did not run ("
                    + cargoError + "), so its zeros are about the scan and not about the craft: "
                    + raw);
        }
        return this;
    }

    /**
     * The registry names of every item the launch-pad craft carry, in the order the scan found them.
     *
     * <p>Asked of the {@code items} array rather than of the reply: a {@code contains} for an item
     * id over the whole answer is satisfied by any string in it — an id in the {@code fluids} list,
     * or one the probe happened to mention in an error — and reads as cargo either way.</p>
     */
    public String[] itemIds() {
        String[] items = reply.objectArray("items");
        String[] ids = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            ids[i] = Reply.of("artest mission complete-now item", items[i]).textOr("id", "");
        }
        return ids;
    }

    /**
     * How much of fluid {@code type} the returning craft carry, summed across their tanks, or
     * {@code 0} when they carry none of it.
     *
     * <p>Asked of the {@code fluids} array, in which each entry is a {@code type} with its own
     * {@code amount}: the pair of substrings this replaces could be satisfied by two DIFFERENT
     * entries — one naming the fluid, another carrying the number — which reads as a full tank of
     * something the craft never collected.</p>
     */
    public int fluidAmount(String type) {
        int total = 0;
        for (String entry : reply.objectArray("fluids")) {
            Reply fluid = Reply.of("artest mission complete-now fluid", entry);
            if (type.equals(fluid.text("type"))) {
                total += fluid.integerOr("amount", 0);
            }
        }
        return total;
    }

    /** Whether the returning craft carry at least one stack of {@code registryName}. */
    public boolean carriesItem(String registryName) {
        for (String id : itemIds()) {
            if (id.equals(registryName)) {
                return true;
            }
        }
        return false;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "mission " + missionId + " completed=" + completed + " rockets=" + rocketCount
                + " items=" + itemEntries + " fluids=" + fluidEntries;
    }
}
