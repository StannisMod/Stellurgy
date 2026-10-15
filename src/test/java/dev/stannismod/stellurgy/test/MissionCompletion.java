package dev.stannismod.stellurgy.test;

import java.util.List;

/**
 * One completion of a resource mission, driven by {@code stellurgytest mission complete-now <missionId>} —
 * what the completion did, which rockets it returned, and what the returned rocket carries.
 *
 * <h2>{@code completed} is the EDGE, and the two flags either side of it are not it</h2>
 *
 * <p>The producer reports {@link #wasDeadBefore} and {@link #isDeadAfter} separately and derives
 * {@link #completed} as {@code !wasDeadBefore && isDeadAfter} — the mission ENDED on this call. A
 * mission already finished before the call answers {@code isDeadAfter:true} and completed nothing,
 * so a caller reading the end state as the event sees a success for a call that did nothing.</p>
 *
 * <h2>The returned rocket is named by the completion's own record</h2>
 *
 * <p>A completion that returns a craft writes a {@code rocket_born} record carrying the mission's id;
 * those records, written inside the call, are the rockets it returned, and their number is how many.
 * Nothing looks for a rocket near the launch site or of a class. The cargo is then read off the ONE
 * returned rocket by its entity id ({@code mission rocket-cargo}). Every returned rocket is handed to
 * the scenario's {@link ScenarioRockets} for disposal.</p>
 */
public final class MissionCompletion {

    /** The mission this reading is about, as the reply echoed it back. */
    public final long missionId;
    /** Whether the mission had already ended before this call, and whether it has ended now. */
    public final boolean wasDeadBefore;
    public final boolean isDeadAfter;
    /** Whether the mission ended ON THIS CALL — the edge, not the state. See the class note. */
    public final boolean completed;
    /** Where the mission was launched from. */
    public final int launchDim;

    private final Reply reply;
    private final String raw;
    /** The {@code rocket_born} records this completion wrote. */
    private final List<String> returned;
    /** {@code mission rocket-cargo} of the one returned rocket; null unless exactly one was returned. */
    private final Reply cargo;

    private MissionCompletion(Reply reply, String raw, List<String> returned, Reply cargo) {
        this.reply = reply;
        this.raw = raw;
        this.returned = returned;
        this.cargo = cargo;
        this.missionId = (long) reply.number("missionId");
        this.wasDeadBefore = reply.bool("wasDeadBefore");
        this.isDeadAfter = reply.bool("isDeadAfter");
        this.completed = reply.bool("completed");
        this.launchDim = reply.integer("launchDim");
    }

    /**
     * Finish one mission now, and read what that did. {@code complete-now} decides the outcome, so it is
     * sent as an arrangement; the cargo read is a read.
     *
     * <p>Refuses the verb's two error shapes — a mission id nothing answers to, and a mission dimension
     * that is not loaded. Neither carries a completion.</p>
     */
    public static MissionCompletion now(ServerCommands commands, ScenarioRockets rockets, long missionId)
            throws Exception {
        String command = "stellurgytest mission complete-now " + missionId;
        long mark = rockets.mark();
        String text = String.valueOf(commands.arrange(command).raw());
        List<String> returned = rockets.returnedByMission(mark, missionId);
        Reply reply = Reply.of(command, text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`stellurgytest mission complete-now` completed nothing ("
                    + reply.text("error") + "), so what this reading is about belongs to no"
                    + " mission: " + text);
        }
        if (!reply.has("completed")) {
            throw new AssertionError("this is not an `stellurgytest mission complete-now` answer: it"
                    + " carries no `completed`, so nothing in it says a mission ended: " + text);
        }
        Reply cargo = null;
        if (returned.size() == 1) {
            String read = "stellurgytest mission rocket-cargo " + (int) Events.number(returned.get(0), "e");
            cargo = commands.read(read);
        }
        return new MissionCompletion(reply, text, returned, cargo);
    }

    /** How many rockets this completion returned: its {@code rocket_born} records. */
    public int returnedRocketCount() {
        return returned.size();
    }

    /**
     * This reading, refusing unless the completion returned exactly one rocket that the server holds and
     * whose cargo could be read — so a cargo assertion below is about that rocket and nothing else.
     */
    public MissionCompletion requireCargo(String what) {
        if (cargo == null) {
            throw new AssertionError(what + " — mission " + missionId + "'s completion returned "
                    + returned.size() + " rocket(s), not one, so there is no single craft whose cargo"
                    + " this is: " + returned + "; completion " + raw);
        }
        if (!cargo.ok() || !cargo.bool("found")) {
            throw new AssertionError(what + " — the server does not hold the rocket the completion"
                    + " returned (" + returned + "), so its cargo cannot be read: " + cargo);
        }
        String infraError = cargo.textOr("infraError", null);
        if (infraError != null) {
            ArrangementFailure.arrangementFailed(what + " — the returned rocket's linked infrastructure"
                    + " could not be read (" + infraError + "): " + cargo);
        }
        return this;
    }

    /** The returned rocket's class, as the server names it. */
    public String returnedClass() {
        requireCargo("the returned rocket's class");
        return cargo.text("cls");
    }

    /** How many non-empty tanks the returned rocket carries. */
    public int fluidEntries() {
        requireCargo("the returned rocket's tanks");
        return cargo.integer("fluidEntries");
    }

    /** How many non-empty inventory slots the returned rocket carries. */
    public int itemEntries() {
        requireCargo("the returned rocket's inventory");
        return cargo.integer("itemEntries");
    }

    /** The infrastructure tiles the returned rocket considers linked, as block positions. */
    public int[][] infrastructure() {
        requireCargo("the returned rocket's linked infrastructure");
        return cargo.blockPosArrayOrEmpty("infrastructure");
    }

    /**
     * The registry names of every item the returned rocket carries, in the order the read found them.
     *
     * <p>Asked of the {@code items} array rather than of the reply: a {@code contains} for an item
     * id over the whole answer is satisfied by any string in it — an id in the {@code fluids} list,
     * or one the probe happened to mention in an error — and reads as cargo either way.</p>
     */
    public String[] itemIds() {
        requireCargo("the returned rocket's items");
        String[] items = cargo.objectArray("items");
        String[] ids = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            ids[i] = Reply.of("stellurgytest mission rocket-cargo item", items[i]).text("id");
        }
        return ids;
    }

    /**
     * How much of fluid {@code type} the returned rocket carries, summed across its tanks, or
     * {@code 0} when it carries none of it.
     *
     * <p>Asked of the {@code fluids} array, in which each entry is a {@code type} with its own
     * {@code amount}: a pair of substrings could be satisfied by two DIFFERENT entries — one naming the
     * fluid, another carrying the number — which reads as a full tank of something never collected.</p>
     */
    public int fluidAmount(String type) {
        requireCargo("the returned rocket's " + type);
        int total = 0;
        for (String entry : cargo.objectArray("fluids")) {
            Reply fluid = Reply.of("stellurgytest mission rocket-cargo fluid", entry);
            // the producer always writes `type` beside the amount: an entry is built as one pair
            // and a tank with no fluid is skipped before it is appended at all.
            if (type.equals(fluid.text("type"))) {
                total += fluid.integer("amount");
            }
        }
        return total;
    }

    /** Whether the returned rocket carries at least one stack of {@code registryName}. */
    public boolean carriesItem(String registryName) {
        for (String id : itemIds()) {
            if (id.equals(registryName)) {
                return true;
            }
        }
        return false;
    }

    /** The completion reply exactly as the probe sent it, with the cargo read beside it. */
    public String raw() {
        return raw + (cargo == null ? "" : " cargo " + cargo.raw()) + " returned " + returned;
    }

    /**
     * Whether the verb THREW, and with what — the shape an unhandled exception reaches a caller
     * in: the command's top-level catch renders it as {@code {"error":"NullPointerException: …"}}.
     */
    public boolean threw(String exceptionSimpleName) {
        return reply.refused() && reply.error().startsWith(exceptionSimpleName);
    }

    @Override
    public String toString() {
        return "mission " + missionId + " completed=" + completed + " returned=" + returned.size()
                + (cargo == null ? "" : " cargo=" + cargo.raw());
    }
}
