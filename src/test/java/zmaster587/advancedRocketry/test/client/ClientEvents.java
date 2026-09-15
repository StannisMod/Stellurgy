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
