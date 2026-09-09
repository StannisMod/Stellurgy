package zmaster587.advancedRocketry.test;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * <p><b>The reply is PARSED, not scraped.</b> Both probes answer one well-formed JSON object — an
 * envelope of {@code recording} / {@code mixins} / {@code instruments} / {@code droppedByType} /
 * {@code count} around an {@code events} array — and every accessor here reads that structure. This
 * file scraped it with regexes until 2026-09-09, and three defects it cost were one root:</p>
 *
 * <ul>
 *   <li>a record's payload could be mistaken for the ENVELOPE. *Measured 2026-09-05: a
 *       {@code crew_captured} payload carrying {@code "count":0} for an empty crew made
 *       {@link #await} report a link as "never recorded" while it sat in the very list the failure
 *       message printed.*</li>
 *   <li>{@link #countRecords} counted the envelope prefix, and that prefix carries the
 *       {@code instruments} array — so a needle naming an observation point counted a record that did
 *       not exist.</li>
 *   <li>a field written as a NUMBER read back as {@code null} through a quoted-string matcher, which
 *       is indistinguishable from ABSENT. *Measured 2026-09-08: that emptied half of an identity key,
 *       so two records naming different things compared EQUAL, and two green runs hid it.*</li>
 * </ul>
 *
 * <p>None of the three is representable now. The one rule that survives the parse, because it is
 * about the RECORDER and not the reader: a payload names its fields for what they are ({@code crew}),
 * never for the envelope's vocabulary — a record carrying its own {@code count} is legal here and
 * still confusing to read.</p>
 */
public final class Events {

    /**
     * One probe reply as the object it is.
     *
     * <p>Fails loudly on anything that is not a JSON object, naming the reply: a probe that answered
     * an error string, a truncated line, or a verb that does not exist would otherwise read as an
     * EMPTY LOG, which is the one thing an instrument must never be able to fake.</p>
     */
    private static JsonObject envelope(String reply) {
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(String.valueOf(reply));
        } catch (RuntimeException notJson) {
            throw new AssertionError("an events reply must be one JSON object; this could not be"
                    + " parsed (" + notJson + "): " + reply);
        }
        assertTrue("an events reply must be one JSON object, not " + parsed + ": " + reply,
                parsed != null && parsed.isJsonObject());
        return parsed.getAsJsonObject();
    }

    /**
     * The {@code events} array of a {@code since} reply.
     *
     * <p>Both probes always emit the key, so an object WITHOUT it is not a reply at all — it is one
     * RECORD, handed to a reply-level accessor by mistake. That is said out loud rather than
     * answered with an empty array: the mistake is invisible otherwise (a record and a reply are
     * both {@code String}, so the compiler cannot see it) and it returns {@code null} from a field
     * read, which is indistinguishable from "the field is absent". *Measured 2026-09-09:
     * {@code firstField(oneRecord, "pos")} silently answered null and the assertion below it failed
     * saying the record did not name a position — while printing the record, which did.*</p>
     */
    private static JsonArray eventsOf(String sinceReply) {
        JsonObject env = envelope(sinceReply);
        if (!env.has("events")) {
            throw new AssertionError("this is one RECORD, not an `events since` reply, and a"
                    + " reply-level accessor cannot read it — use Events.text / Events.number for a"
                    + " record's own field: " + sinceReply);
        }
        return env.get("events").isJsonArray() ? env.getAsJsonArray("events") : new JsonArray();
    }

    /** One record as the object it is. Records are handed to callers as their own JSON TEXT — so that
     *  a failure message can print one — and a reader holding one re-enters here to read a field. */
    private static JsonObject recordOf(String record) {
        return envelope(record);
    }

    /** A primitive field as text, whatever type it was written as: a caller asking for a field cannot
     *  be wrong about its SHAPE, which is the recorder's business. Null when absent or null. */
    private static String primitive(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        JsonElement value = obj.get(field);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

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
        JsonObject env = envelope(reply);
        assertTrue("the event recorder is not subscribed, so an empty log below would mean nothing:"
                + " " + reply, env.has("recording") && env.get("recording").getAsBoolean());
        assertTrue("events mark must report a sequence: " + reply, env.has("seq"));
        return env.get("seq").getAsLong();
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
        JsonObject env = envelope(reply);
        assertTrue("the test-only mixins were never installed, so an absent position write below"
                + " would mean nothing (is -Dfml.coreMods.load set on this JVM?): " + reply,
                env.has("mixins") && env.get("mixins").getAsBoolean());
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

    /**
     * How many RECORDS in a {@code since} reply carry {@code needle} — a payload fragment such as
     * {@code "accepted":false}.
     *
     * <p>Matched against each record's own JSON and nothing else. It used to be matched against the
     * reply split on the {@code seq} prefix, which included the ENVELOPE — and the envelope carries
     * the {@code instruments} array, so a needle naming an observation point counted a record that
     * did not exist.</p>
     */
    public static int countRecords(String sinceReply, String needle) {
        int n = 0;
        for (JsonElement record : eventsOf(sinceReply)) {
            if (record.toString().contains(needle)) {
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
        for (JsonElement record : eventsOf(sinceReply)) {
            String value = primitive(record.getAsJsonObject(), field);
            if (value != null) {
                out.append(value).append(System.lineSeparator());
            }
        }
        return out.toString();
    }

    /**
     * The records of a {@code since} reply, oldest first, one raw record per entry.
     *
     * <p>For a scenario measuring a QUANTITY over a window rather than waiting for a link: it reads
     * every sample production produced between the mark and now, instead of polling a static every
     * few ticks and taking the best of whatever the polls landed on — a spike between two polls is
     * invisible, a spike in the log is not.</p>
     *
     * <p>Per RECORD and not per field on purpose. A reply's {@code "skew"} values and its
     * {@code "mode"} values are two lists that only line up while every record carries both, so a
     * caller filtering one measurement by another's value must keep them together; handing out
     * parallel arrays would make the day they diverge look like a shift of one.</p>
     *
     * <p>An empty reply gives an empty list — "nothing happened" is a reading, and
     * {@link #assertInstrumentRan} is what separates it from "nobody was looking".</p>
     */
    public static List<String> records(String sinceReply) {
        List<String> out = new ArrayList<>();
        for (JsonElement record : eventsOf(sinceReply)) {
            out.add(record.toString());
        }
        return out;
    }

    /**
     * The records carrying EVERY one of {@code needles}, oldest first.
     *
     * <p>The verb seven classes had grown a private copy of, each splitting the reply on the
     * envelope's own prefix. Two of the copies carried a hand-rolled guard against counting the
     * ENVELOPE as a record — someone had hit that defect and patched around it locally — and one
     * explained itself with "written locally: Events is not this class's to edit". It is: a reader
     * of the log belongs in the reader of the log, and six of the seven copies had no guard.</p>
     *
     * <p>Several needles rather than one because that is what the copies wanted: a record must carry
     * this ship AND this verdict, and a whole-reply {@code contains} is satisfied by two different
     * records, or by one record's two different moments.</p>
     */
    public static List<String> recordsWithAll(String sinceReply, String... needles) {
        List<String> out = new ArrayList<>();
        for (String record : records(sinceReply)) {
            boolean all = true;
            for (String needle : needles) {
                all &= record.contains(needle);
            }
            if (all) {
                out.add(record);
            }
        }
        return out;
    }

    /** As above, matched without case — for prose. A chat line's capitalisation belongs to the
     *  translation, never to the contract, so a test that pinned it would fail on a language file
     *  edit that broke nothing. */
    public static List<String> recordsWithAllIgnoringCase(String sinceReply, String... needles) {
        List<String> out = new ArrayList<>();
        for (String record : records(sinceReply)) {
            String lower = record.toLowerCase(java.util.Locale.ROOT);
            boolean all = true;
            for (String needle : needles) {
                all &= lower.contains(needle.toLowerCase(java.util.Locale.ROOT));
            }
            if (all) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * A mark, or the reason there is none — for a reader that must NOT fail its scenario when the
     * HARNESS is the thing that is broken.
     *
     * <p>{@link #mark} and {@link #markInstrumented} assert, which is right where an empty log would
     * otherwise be read as a finding. But a shared base taking a mark for a DIAGNOSTIC has the
     * opposite duty: a recorder that is not subscribed is a harness gap, and a harness gap must not
     * present as this scenario's contract breaking. Two base classes hand-rolled exactly this, each
     * with its own regexes over the reply and its own sentinel.</p>
     */
    public static final class MarkOrWhyNot {
        /** The sequence, or {@code -1} when the log is not usable. */
        public final long seq;
        /** Empty when the mark is usable; otherwise what was wrong, ready to print. */
        public final String refusal;

        private MarkOrWhyNot(long seq, String refusal) {
            this.seq = seq;
            this.refusal = refusal;
        }

        public boolean usable() {
            return seq >= 0;
        }
    }

    /** A mark that REFUSES rather than throws — see {@link MarkOrWhyNot}. Both honesty flags are
     *  read, because they fail independently: the bus recorder may be unsubscribed, or the
     *  launch-time coremod may never have queued the test-only mixins, and the two silences are
     *  identical from a test. */
    public MarkOrWhyNot markIfInstrumented() throws Exception {
        String reply = probe.exec("artest events mark");
        JsonObject env = envelope(reply);
        boolean live = env.has("recording") && env.get("recording").getAsBoolean();
        boolean woven = env.has("mixins") && env.get("mixins").getAsBoolean();
        if (!live || !woven || !env.has("seq")) {
            return new MarkOrWhyNot(-1L, (live ? "" : "the event recorder is not subscribed; ")
                    + (woven ? "" : "the test-only mixins were never installed; ")
                    + "reply: " + reply);
        }
        return new MarkOrWhyNot(env.get("seq").getAsLong(), "");
    }

    /** The most recent record in a {@code since} reply, or {@code null} when it holds none — the
     *  "what is the latest" question a reader used to answer by reading a {@code last*} static, now
     *  answered by a record that names its body and its tick. */
    public static String lastRecord(String sinceReply) {
        List<String> all = records(sinceReply);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** One record's numeric {@code field}, or {@code NaN} when this record does not carry it — an
     *  absent measurement, which a caller must be able to tell from a measured zero. Matched as a
     *  JSON number, so a value recorded as a quoted string is deliberately not found. */
    public static double number(String record, String field) {
        String value = primitive(recordOf(record), field);
        if (value == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            return Double.NaN; // present, but not a measurement; the caller's NaN check covers it
        }
    }

    /** One record's string {@code field}, or {@code null} when this record does not carry it. */
    public static String text(String record, String field) {
        // Any primitive, as text. A caller asking for a field cannot be wrong about its SHAPE — that
        // is the recorder's business — and a matcher that took quoted values only made a NUMBER read
        // back as null, indistinguishable from absent. On 2026-09-08 that emptied half of an identity
        // key, so two records naming different things compared EQUAL and two green runs hid it. With
        // the reply parsed the distinction is structural: a JsonPrimitive knows its own type.
        return primitive(recordOf(record), field);
    }

    /** The string {@code field} of the FIRST record in a {@code since} reply, or {@code null} when no
     *  record carries it — the first thing that happened after the mark, which for a gate that goes
     *  on answering every tick (a refusal, then COOLDOWN, COOLDOWN, …) is the decision itself. */
    public static String firstField(String sinceReply, String field) {
        for (JsonElement record : eventsOf(sinceReply)) {
            String value = primitive(record.getAsJsonObject(), field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** The string {@code field} of the LAST record in a {@code since} reply, or {@code null} when no
     *  record carries it. Records are in order, so the last one is the most recent. */
    public static String lastField(String sinceReply, String field) {
        String last = null;
        for (JsonElement record : eventsOf(sinceReply)) {
            String value = primitive(record.getAsJsonObject(), field);
            if (value != null) {
                last = value;
            }
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
        // The envelope's `instruments` ARRAY, not the text of the reply. A substring search also
        // matched the name inside a record's PAYLOAD, so a recorder that merely mentioned an
        // observation point satisfied the assertion that the point had run.
        boolean ran = false;
        JsonObject env = envelope(reply);
        if (env.has("instruments") && env.get("instruments").isJsonArray()) {
            for (JsonElement entered : env.getAsJsonArray("instruments")) {
                if (entered.isJsonPrimitive() && name.equals(entered.getAsString())) {
                    ran = true;
                    break;
                }
            }
        }
        assertTrue("the observation point \"" + name + "\" never executed, so the log below cannot"
                + " support \"" + whatFor + "\" — an empty log here means nobody was looking, not that"
                + " nothing happened. Check the mixin wove (-PmixinDebug=true, then the preserved"
                + " client log) and that its method is on a path this scenario reaches. Reply: "
                + reply, ran);
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
            if (eventsOf(reply).size() > 0) {
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
        for (JsonElement record : eventsOf(sinceReply)) {
            String type = primitive(record.getAsJsonObject(), "type");
            if (type != null) {
                out.add(type);
            }
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
