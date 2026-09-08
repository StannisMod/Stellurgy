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
 *
 * <p><b>The reply is read by regex, so the envelope's keys are reserved.</b> {@code count},
 * {@code seq}, {@code type}, {@code tick}, {@code side}, {@code recording}, {@code mixins} are taken
 * as the FIRST occurrence in the reply; both probes emit every envelope key before the records, so a
 * record's payload can never be mistaken for the envelope — provided no payload reuses one of those
 * names. Measured 2026-09-05: a {@code crew_captured} payload carrying {@code "count":0} for an empty
 * crew, in a reply whose envelope count then trailed the records, made {@link #await} report a link
 * as "never recorded" while it sat in the very list the message printed. A recorder names its fields
 * for what they are ({@code crew}), never for the envelope's vocabulary.</p>
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

    /** The records of one {@code type} at or after {@code mark}, in order, as the raw reply. */
    public String since(long mark, String type) throws Exception {
        return probe.exec("artest events since " + mark + " " + type);
    }

    /** How many records in a {@code since} reply carry {@code needle} — a payload fragment such as
     *  {@code "accepted":false}. Records are split on the envelope's own {@code {"seq":} prefix. */
    public static int countRecords(String sinceReply, String needle) {
        int n = 0;
        for (String record : String.valueOf(sinceReply).split("\\{\"seq\":")) {
            if (record.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Every record's string {@code field} in a {@code since} reply, oldest first, one per line.
     *
     * <p>For a record type that carries a whole preformatted line — a per-tick trace, say — this
     * hands the reader the same text a production-side buffer used to publish as one field, while
     * the records themselves stay attributable to the body each describes and windowable by a mark.
     * An empty reply gives an empty string, not null: "nothing happened" is a reading.</p>
     */
    public static String fieldLines(String sinceReply, String field) {
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]*)\"")
                .matcher(String.valueOf(sinceReply));
        while (m.find()) {
            out.append(m.group(1)).append(System.lineSeparator());
        }
        return out.toString();
    }

    /** The string {@code field} of the FIRST record in a {@code since} reply, or {@code null} when no
     *  record carries it — the first thing that happened after the mark, which for a gate that goes
     *  on answering every tick (a refusal, then COOLDOWN, COOLDOWN, …) is the decision itself. */
    public static String firstField(String sinceReply, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(String.valueOf(sinceReply));
        return m.find() ? m.group(1) : null;
    }

    /** The string {@code field} of the LAST record in a {@code since} reply, or {@code null} when no
     *  record carries it. Records are in order, so the last one is the most recent. */
    public static String lastField(String sinceReply, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(String.valueOf(sinceReply));
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
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
                + TRIAGE + " | raw: " + since(mark));
    }

    /**
     * How to read the silence printed beside it — the sentence fifteen classes carried a copy of.
     *
     * <p>An empty result has four causes and only one of them is an answer, so a failure that does
     * not separate them sends its reader to the wrong subsystem. Both probes emit the same four
     * envelope keys ({@code TestProbeCommand}'s reply and the client bridge's {@code event_since}),
     * so this is true of either log.</p>
     */
    private static final String TRIAGE = ". An empty result has four causes and the reply below tells"
            + " them apart: `recording` false = the log was never armed, the instrument absent from"
            + " `instruments` = the observation point never ran, a non-zero entry in `droppedByType`"
            + " for this type = the ring turned over before it was read, and none of those = it ran"
            + " and the thing never happened";

    /**
     * Wait for one event of {@code type} whose payload CARRIES {@code needle}, or fail naming the
     * whole chain that did happen.
     *
     * <p>{@link #await} matches on the type alone, which on a shared world is a wait any subject can
     * satisfy: every neighbouring scenario's ship crosses the same seams and its records land in the
     * same log. Where more than one body can produce the type, the wait has to name WHICH one, and
     * this is that form — the needle is a fragment of the record's own payload, e.g.
     * {@code "\"ship\":\"" + shipId + "\""}.</p>
     *
     * <p>Measured 2026-09-06: a readiness wait built on {@link #await} was described in three
     * javadocs as "asked by id" while matching on type only, so a neighbour's ship satisfied it. The
     * id was in the failure message and nowhere else.</p>
     *
     * @param needle a substring of the payload that identifies the subject
     */
    public String awaitCarrying(long mark, String type, String needle, String what, int tickBudget)
            throws Exception {
        return awaitCarrying(mark, type, needle, what, tickBudget, null);
    }

    /** As above, driving {@code stimulus} between reads — see {@link Stimulus}. */
    public String awaitCarrying(long mark, String type, String needle, String what, int tickBudget,
                                Stimulus stimulus) throws Exception {
        return awaitMatching(mark, type, reply -> countRecords(reply, needle) > 0,
                "carrying " + needle, what, tickBudget, stimulus);
    }

    /**
     * Wait until the records of {@code type} since {@code mark} satisfy {@code holds}, or fail
     * naming the whole chain that did happen.
     *
     * <p>The general form the two waits above are written in terms of, and the one to reach for when
     * "did it happen" is not a substring: a frame that must DIFFER from the one before it, a count
     * that must reach a floor, a chat line matched without its capitalisation — prose case belongs to
     * the translation, not to the contract, and a needle folded by the caller is the only honest way
     * to say so.</p>
     *
     * <p>Three classes had grown a private copy of this loop with three different predicates baked
     * in, and each copy's failure message had drifted a different distance from the chain it was
     * supposed to print. The predicate is the part that differs between scenarios; the waiting, the
     * budget arithmetic and the narrative are not.</p>
     *
     * @param holds    tested against the whole {@code since} reply for {@code type}, re-read each step
     * @param matching how to say what {@code holds} wanted, for the failure — e.g. {@code "carrying
     *                 \"ship\":\"a1\""} or {@code "differing from the previous frame"}
     */
    public String awaitMatching(long mark, String type, java.util.function.Predicate<String> holds,
                                String matching, String what, int tickBudget) throws Exception {
        return awaitMatching(mark, type, holds, matching, what, tickBudget, null);
    }

    /**
     * Work a headless test has to keep doing WHILE it waits — arrangement a player would provide.
     *
     * <p>Its own parameter rather than folded into the wait, for the reason {@code GameTicks.until}
     * gives for the same split: a condition is ASKED and must change nothing, a stimulus is
     * PERFORMED and answers nothing. Folding the two together is how "this only passes when the
     * assertion runs" gets written.</p>
     */
    public interface Stimulus {
        void run() throws Exception;
    }

    /**
     * As {@link #awaitMatching(long, String, java.util.function.Predicate, String, String, int)},
     * driving {@code stimulus} between reads.
     *
     * <p>For the case a headless server creates: a mechanic that advances on a tick nobody is
     * running. A hyperspace transit is the example — its flight is stepped by a probe the test must
     * keep calling, and without that the wait is watching a jump that will never move. The stimulus
     * is NOT the observation: what decides is still the record, so the verdict remains "the thing
     * production announced happened" rather than "a sample eventually read the way I wanted".</p>
     */
    public String awaitMatching(long mark, String type, java.util.function.Predicate<String> holds,
                                String matching, String what, int tickBudget, Stimulus stimulus)
            throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = since(mark, type);
            if (holds.test(reply)) {
                return reply;
            }
            if (stimulus != null) {
                stimulus.run();
            }
            step.ticks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` " + matching + " was recorded"
                + " within " + tickBudget + " ticks. What DID happen since the mark: "
                + describe(since(mark)) + TRIAGE + " | `" + type + "` records: " + reply
                + " | everything since the mark: " + since(mark));
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

    /**
     * "dismount, pos_jump ×14, crew_captured, …" — the recorded types in order, consecutive repeats
     * collapsed into one entry with a count; or an explicit nothing, never an empty string.
     *
     * <p>Collapsed because a chain failure's first line is where a reader decides what happened, and
     * a chatty instrument (a position writer records every tick a rider is re-placed by his mount)
     * buries the six links that matter under two hundred identical names. The ORDER survives, the
     * count survives, and the raw reply beside it still carries every record.</p>
     */
    private static String describe(String sinceReply) {
        List<String> types = typesOf(sinceReply);
        if (types.isEmpty()) {
            return "(nothing was recorded at all)";
        }
        StringBuilder sb = new StringBuilder();
        int run = 0;
        for (int i = 0; i < types.size(); i++) {
            run++;
            boolean last = i + 1 == types.size() || !types.get(i + 1).equals(types.get(i));
            if (!last) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(types.get(i));
            if (run > 1) {
                sb.append(" ×").append(run);
            }
            run = 0;
        }
        return sb.toString();
    }
}
