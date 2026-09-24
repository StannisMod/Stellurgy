package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;

import zmaster587.advancedRocketry.test.Events;

/**
 * The CLIENT's ordered event log behind the same {@link Events} verbs the server log is read through.
 *
 * <p>{@link Events} speaks one probe language — {@code artest events mark} and
 * {@code artest events since <seq> [type]} — because that is how the SERVER answers, over a command
 * channel. The client bot answers the same two questions on its own socket, as JSON objects rather
 * than a command reply. This is the translation, and it exists so a client-side chain gets
 * {@code await} / {@code awaitCarrying} / {@code assertChain} and their failure narrative instead of
 * a hand-rolled poll and a regex per class.</p>
 *
 * <p><b>Why one adapter and not one per class.</b> Nine classes carried a private copy of these ten
 * lines, and five of them parsed the command by index: {@code parts[3]} on anything that was not
 * {@code artest events since N} raised a {@code NumberFormatException} from inside a wait, which
 * reads as the game misbehaving rather than as the test asking the log a question it does not
 * answer. The copy that refused explicitly is the one kept below.</p>
 *
 * <p><b>What a client mark can and cannot assert.</b> The client reply carries {@code recording}, so
 * {@link Events#mark} means something here. It carries no {@code mixins} flag, so
 * {@link Events#markInstrumented} must NEVER be called on a client log — it would find nothing and
 * fail for the wrong reason. A scenario about to conclude something from a client SILENCE asserts
 * {@link Events#assertInstrumentRan} on the reply instead, which is the stronger check anyway: it
 * names the observation point that had to run, rather than the weave that had to happen.</p>
 *
 * <p><b>Which log a fact lives in is the scenario's decision, not this class's.</b> A body released
 * and reclaimed inside a hull is a CLIENT fact — for an {@code EntityPlayerMP} the server rebases the
 * position instead of releasing at all, so a server probe answers "still tracked" straight through a
 * release the client really performed. A ship's assembly is a SERVER fact. Reading one from the other
 * log is not a slower answer, it is a different question.</p>
 */
public final class ClientEvents {

    private static final String MARK = "artest events mark";
    private static final String SINCE = "artest events since ";

    private ClientEvents() {
    }

    /**
     * Wait until the client has APPLIED a server placement within one block of {@code x, z}.
     *
     * <p>The far side of a teleport: the server writes the position, the client applies the packet,
     * and the harness records THAT with the absolute coordinates the client ends up holding. A test
     * that reads the client — its rendered position, its tracked chunks, a ray cast from where it
     * stands — is asking about the side this record belongs to, and a fixed settle in its place is a
     * guess at one round trip.</p>
     *
     * <p>Matched on WHERE rather than on a record of any kind, because the seam re-sends this packet
     * on every movement rejection and a bare type wait can close on a rubber-band. The tolerance is
     * a block: a placement above the surface may settle onto it.</p>
     *
     * <p>Static, and here rather than on a base class, because the tier has TWO hierarchies — the
     * shared AR bases and the harness's own {@code AbstractClientE2ETest} — and this is the third
     * time a wait that belongs to both has been solved by copying it into each.</p>
     *
     * @param clientLog the CLIENT's log ({@link #of})
     * @param mark      a mark on THAT log, taken BEFORE the teleport command
     */
    public static void awaitPlacedNear(Events clientLog, long mark, double x, double z,
                                       String what, int tickBudget) throws Exception {
        clientLog.awaitMatching(mark, "client_pos_look_applied",
                reply -> appliedNear(reply, x, z),
                "placing the client at " + x + ", " + z, what, tickBudget);
    }

    /** Whether any {@code client_pos_look_applied} in a {@code since} reply put the client within a
     *  block of {@code x, z}. A record carrying no finite coordinate answers NaN, and NaN is near
     *  nothing. */
    public static boolean appliedNear(String sinceReply, double x, double z) {
        for (String record : Events.records(sinceReply)) {
            if (Math.abs(Events.number(record, "x") - x) <= 1.0
                    && Math.abs(Events.number(record, "z") - z) <= 1.0) {
                return true;
            }
        }
        return false;
    }

    /** Vanilla's rain-strength game state: the client sets its rain strength to the packet's value. */
    public static final String RAIN_STRENGTH_STATE = "7";

    /** Vanilla's begin-raining game state: the client starts raining at strength 0. */
    public static final String BEGIN_RAINING_STATE = "1";

    /**
     * Whether any {@code client_game_state_changed} in a {@code since} reply told the client a rain
     * strength of at least {@code min}. A 1.12 client does not lerp its own weather, so this is the
     * only way its rain can rise.
     */
    public static boolean toldRainStrengthAtLeast(String sinceReply, double min) {
        for (String record : Events.recordsWhere(sinceReply, "state", RAIN_STRENGTH_STATE)) {
            if (Events.number(record, "value") >= min) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wait until the CLIENT has SEATED him since {@code mark} and nothing has taken him off after
     * it — the replication half of a mount the server has already performed.
     *
     * <p>The predicate is over the CHAIN, not a count: a window that can hold a dismount after the
     * mount (a retried boarding, a crossing's re-seat, a seat destroyed under him) makes "a mount
     * happened" a weaker claim than the one a caller is about to assert. <b>A mount must EXIST and
     * be later than every dismount</b> — an empty window means NOT YET, which is the difference
     * between this and a predicate written for a caller holding a prior read.</p>
     *
     * <p>Static, and here rather than on a base class, for the same reason as
     * {@link #awaitPlacedNear}: the tier has two class hierarchies and the login-restore family
     * belongs to neither of the shared bases.</p>
     *
     * @param clientLog the CLIENT's log ({@link #of})
     * @param mark      a mark on THAT log, taken BEFORE the command that mounts him
     */
    public static void awaitMounted(Events clientLog, long mark, String what, int tickBudget)
            throws Exception {
        try {
            clientLog.awaitMatching(mark, "mount", seen -> endsMounted(clientLog, mark),
                    "a chain that ENDS in a mount (a mount exists, after every dismount)",
                    what, tickBudget);
        } catch (AssertionError never) {
            // An absence is evidence only once somebody was listening.
            Events.assertInstrumentRan(clientLog.since(mark, "mount"), "entity_mount_writes",
                    "the client's own mounts must be observed at all before an absent one can be"
                            + " read as a seating the client never performed");
            throw new AssertionError(never.getMessage()
                    + " | the client's own chain: mounts=" + clientLog.since(mark, "mount")
                    + " ||| dismounts=" + clientLog.since(mark, "dismount"), never);
        }
    }

    /** Whether {@code log}'s mount chain since {@code mark} ends with him SEATED: a mount exists,
     *  and it is later than every dismount. Compared by {@code seq}, the only ordering two per-type
     *  rings share. */
    public static boolean endsMounted(Events log, long mark) throws Exception {
        String lastMount = Events.lastField(log.since(mark, "mount"), "seq");
        if (lastMount == null) {
            return false;
        }
        String lastDismount = Events.lastField(log.since(mark, "dismount"), "seq");
        return lastDismount == null
                || Long.parseLong(lastMount.trim()) > Long.parseLong(lastDismount.trim());
    }

    /**
     * Wait until the CLIENT has been respawned into {@code expectedDim} — the far side of a transfer
     * the server has already ordered.
     *
     * <p>A transfer tears the old world down and builds a new {@code WorldClient}; the harness
     * records that as {@code client_dimension_changed}, and the record's arrival is the first
     * instant "the client's own dimension is N" is true. A poll on the rendered dimension cannot
     * tell <i>already there</i> from <i>never went</i>, and a client torn down and rebuilt twice
     * between two samples shows one change or none — so its expiry could only ever report that N
     * samples had not caught the change yet, which is a sentence about the machine.</p>
     *
     * <p>No read-first branch, and the reason is worth the line: the server sends the respawn packet
     * unconditionally, so this link always has something to close on — where a record written only
     * on an EDGE would not, and would burn the whole budget on the healthy path.</p>
     *
     * <p>Static, and here rather than on a base class, for the same reason as
     * {@link #awaitPlacedNear} and {@link #awaitMounted}: <b>five classes across three hierarchies
     * had grown a private copy of this wait</b> — two of them written out as polling loops over the
     * same record, and three of the five unable to say which silence they met.</p>
     *
     * @param clientLog the CLIENT's log ({@link #of})
     * @param mark      a mark on THAT log, taken BEFORE the command that transfers him
     * @param what      what the caller needs the client to be in that dimension FOR
     */
    public static void awaitDim(Events clientLog, long mark, int expectedDim, String what,
                                int tickBudget) throws Exception {
        awaitDim(clientLog, mark, expectedDim, what, tickBudget, null);
    }

    /**
     * As above, appending {@code tail}'s reading to the failure.
     *
     * <p>For a caller holding a probe whose answer makes the silence readable — the client's last
     * weather report, its last spawn report. It is asked only when the wait has already failed.</p>
     */
    public static void awaitDim(Events clientLog, long mark, int expectedDim, String what,
                                int tickBudget, Diagnostic tail) throws Exception {
        try {
            // Asked of the FIELD. The needle form this replaces — `"dim":N,` as a substring of the
            // record's serialisation — pins the writer's field ORDER and the comma after the value,
            // so a record that gained a field would make this wait expire and report a transfer that
            // never happened.
            clientLog.awaitField(mark, "client_dimension_changed", "dim", expectedDim,
                    "the client must follow the transfer into dim " + expectedDim + " — " + what,
                    tickBudget);
        } catch (AssertionError never) {
            // An absence is evidence only once somebody was listening.
            Events.assertInstrumentRan(clientLog.since(mark, "client_dimension_changed"),
                    "client_dimension_changed", "the client's own dimension changes must be observed"
                            + " at all before an absent one can be read as a transfer that failed");
            throw new AssertionError(never.getMessage()
                    + (tail == null ? "" : " | " + tail.read()), never);
        }
    }

    /**
     * A reading taken only to make a failure legible — evaluated after the wait has lost, never on
     * the healthy path.
     *
     * <p>Its own interface rather than {@code Supplier<String>} because everything worth reading
     * here is a probe call, and a probe call throws.</p>
     */
    @FunctionalInterface
    public interface Diagnostic {
        String read() throws Exception;
    }

    /** The bot's own event log, read through {@link Events}, paced by that same bot's ticks. */
    public static Events of(ClientBot bot) {
        return new Events(probe(bot), bot::waitTicks);
    }

    /**
     * The same log, paced by something OTHER than this bot's ticks.
     *
     * <p>For a scenario whose subject is offline, or whose clock is a second client's: the log being
     * read and the thing being waited ON are then different, and a wait paced by a disconnected
     * bot's ticks never advances.</p>
     */
    public static Events of(ClientBot bot, Events.Step step) {
        return new Events(probe(bot), step);
    }

    private static Events.Probe probe(ClientBot bot) {
        return command -> {
            if (MARK.equals(command)) {
                return String.valueOf(bot.eventMark());
            }
            if (command.startsWith(SINCE)) {
                String[] parts = command.substring(SINCE.length()).trim().split(" ");
                return String.valueOf(bot.eventsSince(Long.parseLong(parts[0]),
                        parts.length > 1 ? parts[1] : null));
            }
            throw new IllegalArgumentException("the client event log answers `mark` and `since` only,"
                    + " not: " + command);
        };
    }
}
