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

    /**
     * One record as the object it is. Records are handed to callers as their own JSON TEXT — so that
     * a failure message can print one — and a reader holding one re-enters here to read a field.
     *
     * <p>And it refuses a REPLY, which is the mirror of {@link #eventsOf}'s refusal above and was
     * missing until 2026-09-18. A reply's fields live one level down inside {@code events}, so a
     * record-level read of one resolves nothing and answers {@code null} — indistinguishable from
     * "this record does not carry the field". *Measured: two asserts in the M1 milestone read
     * {@code managed} off an `await` REPLY through {@code Boolean.parseBoolean}, so both had been
     * unable to pass since the reply became parsed rather than scraped — the regex that preceded it
     * searched the whole string and found the field nested. Neither was in the two-leg pre-diff
     * gate, so neither red was ever seen.* A record and a reply are both {@code String}, so this is
     * the only place that can tell them apart.</p>
     */
    private static JsonObject recordOf(String record) {
        JsonObject obj = envelope(record);
        if (obj.has("events")) {
            throw new AssertionError("this is an `events since` REPLY, not one record, and a"
                    + " record-level accessor reads its fields at the top level — where a record's"
                    + " fields are not. Take the record first (Events.lastRecord / records /"
                    + " recordsWhere), or ask the reply itself (Events.anyRecordHas): " + record);
        }
        return obj;
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
        return read("artest events since " + mark);
    }

    /** The records of one {@code type} at or after {@code mark}, in order, as the raw reply. */
    public String since(long mark, String type) throws Exception {
        return read("artest events since " + mark + " " + type);
    }

    /**
     * The highest eviction count REPORTED so far for each record type, so a growing one is announced
     * once per growth instead of on every read.
     *
     * <p><b>A static, and here is its owner and its lifetime</b>: the TEST JVM — the client or the
     * server process this suite runs in — for as long as that process lives. It holds nothing but
     * "what has already been printed", no assertion reads it, and a wrong value costs a duplicate
     * line or a missing one, never a verdict. Two logs (the server's and the client's) share it and
     * count separately, so the blind spot is named rather than engineered away: a growth on the
     * quieter side is masked while the busier side's total is higher. Announcing the busy side is
     * the point.</p>
     */
    private static final java.util.Map<String, Long> REPORTED_EVICTIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The log's per-type ring depth ({@code TestEventLog.CAPACITY_PER_TYPE}), as the STEP between
     * eviction announcements.
     *
     * <p>Copied rather than read: the constant lives in the production-side log and this is a test
     * reading its own output over a probe channel. It is a reporting cadence, not an assertion — a
     * drift between the two costs a line said early or late and nothing else.</p>
     */
    private static final long RING_DEPTH_PER_TYPE = 256L;

    /**
     * Read a reply and ANNOUNCE what the log threw away to produce it.
     *
     * <p>The ring is bounded per type; when a type turns it over, every window that straddles the
     * eviction reads an absence that is not one. That number has always travelled in the reply's
     * envelope and was printed only by a FAILING assertion — so a recorder whose ring turns over
     * hundreds of times a scenario looked healthy on every green run, right up to the day a window
     * happened to land on it and something else went red. <i>Maintainer ruling 2026-09-16, on a red
     * whose envelope showed 25 217 evictions and which could not be compared against the green run
     * before it because the green run printed nothing: "пусть печатаются всегда".</i></p>
     *
     * <p>Announced on GROWTH, not on every read: a line per new high, naming the type and the jump.
     * A run that evicts nothing says nothing, which is what makes a run that does stand out.</p>
     */
    private String read(String command) throws Exception {
        String reply = probe.exec(command);
        announceEvictions(reply);
        return reply;
    }

    /** @see #read(String) — split out so any other reader of a log reply can announce the same. */
    public static void announceEvictions(String reply) {
        JsonObject env;
        try {
            env = envelope(reply);
        } catch (RuntimeException | AssertionError notAnEnvelope) {
            return; // a reply with no envelope has no counters to announce; the caller's own
        }           // assertions are what say whether that is a problem
        if (env == null || !env.has("droppedByType") || !env.get("droppedByType").isJsonObject()) {
            return;
        }
        for (java.util.Map.Entry<String, JsonElement> byType
                : env.getAsJsonObject("droppedByType").entrySet()) {
            long now;
            try {
                now = byType.getValue().getAsLong();
            } catch (RuntimeException notANumber) {
                continue;
            }
            if (now <= 0L) {
                continue;
            }
            Long before = REPORTED_EVICTIONS.get(byType.getKey());
            // A RING'S WORTH since the last line, not every record. These counters climb on every
            // read of a chatty type, so "announce on growth" alone is a line per read — 821 of them
            // in one class, which is the same silence wearing a different coat. One ring's depth is
            // the step because that is the unit of harm: it is exactly how much has to be lost for a
            // window to have been emptied under a reader.
            if (before != null && now < before + RING_DEPTH_PER_TYPE) {
                continue;
            }
            REPORTED_EVICTIONS.merge(byType.getKey(), now, Math::max);
            System.out.println("[events] RING EVICTED `" + byType.getKey() + "` — " + now
                    + " records dropped so far (+" + (before == null ? now : now - before)
                    + " since this was last said). Any window of this type that straddles the"
                    + " eviction reads an absence that is not one.");
        }
    }

    /**
     * How many RECORDS in a {@code since} reply carry {@code field} with this value, compared as
     * text — the counting sibling of {@link #anyRecordHas}.
     *
     * <p><b>This is the form to reach for.</b> The field read is structural: it survives a producer
     * adding a field, reordering two, or changing how gson renders one. Its substring cousin below
     * survives none of those, and when it breaks it answers ZERO — a count, which reads as a finding
     * about the world rather than as a reader that stopped matching.</p>
     */
    public static int countRecords(String sinceReply, String field, String value) {
        int n = 0;
        for (JsonElement record : eventsOf(sinceReply)) {
            String seen = primitive(record.getAsJsonObject(), field);
            if (seen != null && seen.equals(value)) {
                n++;
            }
        }
        return n;
    }

    /**
     * How many RECORDS in a {@code since} reply carry {@code field} at all, whatever its value.
     *
     * <p>For the question a needle like {@code "\"reason\":"} was asking: not <i>which</i> reason,
     * but whether the producer wrote one. Ask it by NAME — the substring form also matched a
     * {@code reason} appearing inside some other field's text.</p>
     */
    public static int countRecordsWithField(String sinceReply, String field) {
        int n = 0;
        for (JsonElement record : eventsOf(sinceReply)) {
            if (primitive(record.getAsJsonObject(), field) != null) {
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
     * The records whose own JSON CONTAINS every one of {@code needles}, oldest first.
     *
     * <p><b>{@code Containing} is in the name because this matches a RENDERING</b>, and a rendering
     * is the writer's field order and formatting rather than the contract. Where a needle is really
     * one field's value, {@link #countRecords(String, String, String)} and {@link #anyRecordHas}
     * ask for it by name and survive the producer changing shape.</p>
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
    /**
     * The records carrying {@code field} with this value, oldest first — the filtering sibling of
     * {@link #anyRecordHas}, and the form to reach for when a needle was really one field's value.
     *
     * <p>Each comes back as its own JSON text, so a caller reads a field off it with {@link #number}
     * or {@link #text} and prints the whole record in a failure message.</p>
     */
    public static List<String> recordsWhere(String sinceReply, String field, String value) {
        List<String> out = new ArrayList<>();
        for (JsonElement record : eventsOf(sinceReply)) {
            String seen = primitive(record.getAsJsonObject(), field);
            if (seen != null && seen.equals(value)) {
                out.add(record.toString());
            }
        }
        return out;
    }

    public static List<String> recordsContainingAll(String sinceReply, String... needles) {
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
    public static List<String> recordsContainingAllIgnoringCase(String sinceReply, String... needles) {
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

    /**
     * How many records of {@code type} the ring EVICTED, from the reply's own {@code droppedByType}.
     *
     * <p>Every failure message in this class tells its reader to check this counter before reading a
     * gap as an absence — and until this verb existed there was no way to ask for it except by
     * eyeballing the raw reply, which is a thing nobody does on a run that PASSED. So a recorder
     * whose own ring turns over hundreds of times per scenario looks healthy right up to the day a
     * window happens to straddle the eviction, and then reds somewhere else entirely.</p>
     *
     * <p><b>Measured 2026-09-10, which is why this is here</b>: a newly added {@code entity_removed}
     * recorder evicted <b>2605</b> records in one scenario leg (and {@code entity_joined_world}
     * 2555 beside it) while every assertion on it passed. The number reached a human only because a
     * deliberately broken assertion printed the whole envelope.</p>
     *
     * @return the eviction count, or {@code 0} when the reply reports none for this type
     */
    public static long droppedOf(String reply, String type) {
        JsonObject env = envelope(reply);
        if (!env.has("droppedByType") || !env.get("droppedByType").isJsonObject()) {
            return 0L;
        }
        JsonObject byType = env.getAsJsonObject("droppedByType");
        return byType.has(type) ? byType.get(type).getAsLong() : 0L;
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
     * Wait until some record of {@code type} since {@code mark} carries {@code field} equal to
     * {@code value} — asked of the FIELD, not of a rendering of it.
     *
     * <p><b>This is the form to reach for.</b> The substring verbs this replaced are gone
     * (2026-09-17, at zero call sites); what they took was a needle like
     * {@code "\"dim\":9301,"} is a substring of gson's serialisation: it rides on the field order the
     * writer happened to use and on the value being followed by a comma, so a producer that adds a
     * field or reorders two breaks every caller at once and silently — the wait then expires and
     * reports that the thing never happened, which is a statement about the world manufactured by a
     * broken reader.</p>
     *
     * <p>Values are compared as TEXT, whatever the recorder wrote them as, for the reason
     * {@link #text} gives: a caller asking for a field cannot be wrong about its shape.</p>
     */
    public String awaitField(long mark, String type, String field, Object value, String what,
                             int tickBudget) throws Exception {
        return awaitField(mark, type, field, value, what, tickBudget, null);
    }

    /** As above, driving {@code stimulus} between reads — see {@link Stimulus}. */
    public String awaitField(long mark, String type, String field, Object value, String what,
                             int tickBudget, Stimulus stimulus) throws Exception {
        String wanted = String.valueOf(value);
        return awaitMatching(mark, type, reply -> anyRecordHas(reply, field, wanted),
                "carrying " + field + " = " + wanted, what, tickBudget, stimulus);
    }

    /**
     * As {@link #awaitField}, answering the RECORD that satisfied the wait rather than the REPLY it
     * arrived in — the form to take when the caller wants a FIELD of what happened.
     *
     * <p>Every wait in this class answers the whole {@code events since} envelope, whose own fields
     * are {@code ok}, {@code count}, {@code instruments} and {@code events}. Asking THAT for
     * {@code dim} is asking the postmark for the letter's address, and it truthfully answers "no
     * such field" — after which five transit scenarios reported <i>"the arrival was announced but
     * names no dimension"</i> while printing the arrival, with its dimension, in the same sentence
     * (measured 2026-09-17).</p>
     *
     * <p>The LAST matching record: a wait that
     * expires and one that matched on its first read see different numbers of them, and the newest
     * is the one the wait was about.</p>
     */
    public String awaitRecordWithField(long mark, String type, String field, Object value,
                                       String what, int tickBudget) throws Exception {
        String wanted = String.valueOf(value);
        String reply = awaitField(mark, type, field, wanted, what, tickBudget);
        List<String> matching = recordsWhere(reply, field, wanted);
        if (matching.isEmpty()) {
            throw new AssertionError(what + " — the wait for a `" + type + "` carrying " + field
                    + " = " + wanted + " returned, yet no record of the reply carries it. This is a"
                    + " reader fault, not a statement about the world: " + reply);
        }
        return matching.get(matching.size() - 1);
    }

    /** Whether any record in a {@code since} reply carries {@code field} with this value, compared as
     *  text. The field read is structural; nothing here matches a rendering of the record. */
    public static boolean anyRecordHas(String sinceReply, String field, String value) {
        for (JsonElement record : eventsOf(sinceReply)) {
            String seen = primitive(record.getAsJsonObject(), field);
            if (seen != null && seen.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether ONE record carries every one of {@code fieldsAndValues}, given as
     * {@code field, value, field, value, …}.
     *
     * <p>For a subject that takes two fields to name — a chunk is {@code cx} AND {@code cz}, a
     * lookup is its type AND its outcome. Two separate {@link #anyRecordHas} calls would be
     * satisfied by two DIFFERENT records, which is the same defect as reading two probe replies a
     * moment apart and calling the pair one instant.</p>
     *
     * <p>Refuses an odd argument count rather than dropping the last one: a mis-paired call would
     * otherwise narrow by one field fewer than the caller wrote and pass more often, which is the
     * direction a broken reader must never fail in.</p>
     */
    public static boolean anyRecordHasAll(String sinceReply, String... fieldsAndValues) {
        if (fieldsAndValues.length == 0 || fieldsAndValues.length % 2 != 0) {
            throw new AssertionError("anyRecordHasAll takes field, value pairs and was given "
                    + fieldsAndValues.length + " argument(s): "
                    + java.util.Arrays.toString(fieldsAndValues));
        }
        for (JsonElement record : eventsOf(sinceReply)) {
            JsonObject one = record.getAsJsonObject();
            boolean all = true;
            for (int i = 0; i < fieldsAndValues.length && all; i += 2) {
                String seen = primitive(one, fieldsAndValues[i]);
                all = seen != null && seen.equals(fieldsAndValues[i + 1]);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any record carries {@code value} in ANY of {@code fields}.
     *
     * <p>For a subject that HAS more than one identity and a caller who holds one of them without
     * knowing which. The live case is {@code ship_usable}, which carries a craft's durable AR name
     * as {@code ship} and the substrate's opaque key as {@code vsShip} — <b>two different values for
     * one ship</b>, minted in different places, and a scenario arrives holding whichever its own
     * chain produced.</p>
     *
     * <p>This is still a FIELD read: the value is compared against named fields and never against a
     * rendering of the record. What it will not do is tell the caller WHICH identity matched, so a
     * caller that needs to know must ask for the field it means.</p>
     */
    public static boolean anyRecordHasAnyOf(String sinceReply, String value, String... fields) {
        for (JsonElement record : eventsOf(sinceReply)) {
            JsonObject one = record.getAsJsonObject();
            for (String field : fields) {
                String seen = primitive(one, field);
                if (seen != null && seen.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any record's {@code field} CONTAINS {@code fragment}.
     *
     * <p>The one place a substring is the honest test, and it is a substring of a VALUE rather than
     * of the record: a chat line the player was shown is assembled by the game — a prefix, a name,
     * a translation resolved on the client — so a test that pins the whole string pins the
     * formatting. The field is still taken by name, so a producer that renames or reorders anything
     * breaks LOUDLY here instead of matching a fragment that happens to sit elsewhere in the JSON.</p>
     */
    public static boolean anyRecordFieldContains(String sinceReply, String field, String fragment) {
        for (JsonElement record : eventsOf(sinceReply)) {
            String seen = primitive(record.getAsJsonObject(), field);
            if (seen != null && seen.contains(fragment)) {
                return true;
            }
        }
        return false;
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
    public String awaitMatching(long mark, String type, Condition holds,
                                String matching, String what, int tickBudget) throws Exception {
        return awaitMatching(mark, type, holds, matching, what, tickBudget, null);
    }

    /**
     * What {@link #awaitMatching} tests each step — a {@code Predicate<String>} that is allowed to
     * THROW.
     *
     * <p>Not `java.util.function.Predicate`, and the difference is load-bearing: a condition
     * frequently has to consult something else to answer, and everything worth consulting here is a
     * probe call that throws. The interesting ones are exactly those: "a dismount with no LATER
     * mount" needs the other record type, and a lambda that cannot throw has to reach for it
     * outside the loop, where it goes stale — which is how a two-type condition silently becomes a
     * one-type one. Widened from `Predicate` on 2026-09-10 for that case; every lambda already
     * written satisfies it unchanged, because a lambda that throws nothing satisfies a throwing
     * interface.</p>
     */
    @FunctionalInterface
    public interface Condition {
        boolean holds(String sinceReply) throws Exception;
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
    public String awaitMatching(long mark, String type, Condition holds,
                                String matching, String what, int tickBudget, Stimulus stimulus)
            throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = since(mark, type);
            if (holds.holds(reply)) {
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
