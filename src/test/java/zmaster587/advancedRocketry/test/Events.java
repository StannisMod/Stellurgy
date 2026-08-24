package zmaster587.advancedRocketry.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Waiting for an EVENT instead of sampling a value.
 *
 * <p>Every other waiting helper here polls a value on a tick budget. That cannot see anything which
 * does not persist, races its own start, cannot express ORDER, and on failure carries one last
 * sample. This reads the server's ordered event log instead: {@link #mark} is taken BEFORE the
 * action, so nothing that happens afterwards can be missed between two reads, and a failure prints
 * the whole chain rather than a number.</p>
 *
 * <p><b>The transport is still polled and that is fine.</b> What matters is that the EVENTS are
 * buffered on the far side: a slow reader loses nothing. A blocking await inside the game would be a
 * deadlock by construction — the tick that must advance for the event to happen is the tick being
 * blocked.</p>
 */
public final class Events {

    private static final Pattern SEQ = Pattern.compile("\"seq\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern TYPE = Pattern.compile("\"type\":\"([^\"]*)\"");
    private static final Pattern RECORDING = Pattern.compile("\"recording\":(true|false)");
    private static final Pattern MIXINS = Pattern.compile("\"mixins\":(true|false)");

    /** How a caller runs one probe command and gets the raw reply. */
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** How a caller lets the game advance between reads. */
    public interface Step {
        void ticks(int ticks) throws Exception;
    }

    private final Probe probe;
    private final Step step;

    public Events(Probe probe, Step step) {
        this.probe = probe;
        this.step = step;
    }

    /**
     * The sequence to read from, taken BEFORE the action under test.
     *
     * <p>Asserts that a recorder is actually subscribed. Without that check an empty log later reads
     * as "it never happened" when the truth is "nobody was listening" — the one thing an instrument
     * must never be able to fake.</p>
     */
    public long mark() throws Exception {
        String reply = probe.exec("artest events mark");
        Matcher rec = RECORDING.matcher(reply);
        assertTrue("the event recorder is not subscribed, so an empty log below would mean nothing:"
                + " " + reply, rec.find() && "true".equals(rec.group(1)));
        Matcher m = SEQ.matcher(reply);
        assertTrue("events mark must report a sequence: " + reply, m.find());
        return Long.parseLong(m.group(1));
    }

    /**
     * The same, for a chain that includes events only a test-only MIXIN can record — a position
     * write, a mount refusal, anything the Forge bus does not fire.
     *
     * <p>Two things can be off independently and their silences look identical: the bus recorder may
     * be unsubscribed, or the launch-time coremod may never have queued the mixin configuration. A
     * test that awaits a mixin-sourced event must rule out BOTH before an empty log is allowed to
     * mean anything.</p>
     */
    public long markInstrumented() throws Exception {
        long seq = mark();
        String reply = probe.exec("artest events mark");
        Matcher m = MIXINS.matcher(reply);
        assertTrue("the test-only mixins were never installed, so an absent position write below"
                + " would mean nothing (is -Dfml.coreMods.load set on this JVM?): " + reply,
                m.find() && "true".equals(m.group(1)));
        return seq;
    }

    /** Everything recorded at or after {@code mark}, in order, as the raw reply. */
    public String since(long mark) throws Exception {
        return probe.exec("artest events since " + mark);
    }

    /**
     * Fail unless the named observation point actually EXECUTED — the assertion that makes an empty
     * log mean something.
     *
     * <p>Three different silences produce the same empty reply: a mixin that never wove, one that wove
     * but whose method never ran, and one that ran and saw nothing. Only the third is an answer, and
     * without this the other two are read as it. So an instrument announces itself on entry
     * ({@code TestTrace.instrument}) and the reply carries the names; a test that is about to conclude
     * something FROM a silence asserts here first, and gets a failure that names the cause instead of
     * a green that names nothing.</p>
     *
     * <p>Deliberately an assertion and not a boolean: a caller who has to remember to check would be
     * back where this started. Measured 2026-08-23 — three runs and one wrong ledger entry were spent
     * reading a silence produced by an instrument that was not there.</p>
     *
     * @param reply    an {@code events since} reply, from {@link #since} or {@link #await}
     * @param name     the instrument's name, as passed to {@code TestTrace.instrument}
     * @param whatFor  what this test was about to conclude from the log, for the failure message
     */
    public static void assertInstrumentRan(String reply, String name, String whatFor) {
        assertTrue("the observation point \"" + name + "\" never executed, so the log below cannot"
                + " support \"" + whatFor + "\" — an empty log here means nobody was looking, not that"
                + " nothing happened. Check the mixin wove (-PmixinDebug=true, then the preserved"
                + " client log) and that its method is on a path this scenario reaches. Reply: "
                + reply, reply != null && reply.contains("\"" + name + "\""));
    }

    /**
     * Wait for one event of {@code type} to appear after {@code mark}, or fail naming the whole
     * chain that DID happen.
     *
     * @param what a player-facing sentence for what this event means, used in the failure
     */
    public String await(long mark, String type, String what, int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = probe.exec("artest events since " + mark + " " + type);
            Matcher m = COUNT.matcher(reply);
            if (m.find() && Integer.parseInt(m.group(1)) > 0) {
                return reply;
            }
            step.ticks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` was recorded within " + tickBudget
                + " ticks. What DID happen since the mark: " + describe(since(mark))
                + " | raw: " + since(mark));
    }

    /**
     * Assert an ordered chain: each type must appear, and in this order.
     *
     * <p>This is the verb a test should reach for. A contract in this project is nearly always a
     * chain — a boarding, a crossing, a jump, a re-seat — and the ORDER is the part a poll can never
     * check.</p>
     */
    public void assertChain(long mark, String what, int tickBudget, String... types)
            throws Exception {
        for (String type : types) {
            await(mark, type, what, tickBudget);
        }
        List<String> seen = typesOf(since(mark));
        int at = -1;
        for (String type : types) {
            int found = -1;
            for (int i = at + 1; i < seen.size(); i++) {
                if (seen.get(i).equals(type)) {
                    found = i;
                    break;
                }
            }
            assertTrue(what + " — `" + type + "` is missing from the chain AFTER the events that"
                    + " must precede it. Recorded in order: " + seen, found > at);
            at = found;
        }
    }

    /** The recorded types, in order — the compact form a failure leads with. */
    public static List<String> typesOf(String sinceReply) {
        List<String> out = new ArrayList<>();
        Matcher m = TYPE.matcher(sinceReply);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** "right_click_block, sleep_in_bed" — or an explicit nothing, never an empty string. */
    private static String describe(String sinceReply) {
        List<String> types = typesOf(sinceReply);
        return types.isEmpty() ? "(nothing was recorded at all)" : String.join(", ", types);
    }
}
