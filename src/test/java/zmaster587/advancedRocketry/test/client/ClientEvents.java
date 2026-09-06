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
