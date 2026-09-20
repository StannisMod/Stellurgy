package zmaster587.advancedRocketry.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One probe reply, read by FIELD NAME.
 *
 * <p>Every {@code artest} verb answers one JSON object. This parses it once and reads fields off the
 * parsed structure, which is the whole point: a regex or a substring pins the writer's field ORDER
 * and its formatting, and neither is part of any contract. When such a reader stops matching it does
 * not fail — it answers ABSENCE, and absence reads as a finding about the world.</p>
 *
 * <p><b>Measured, and this is why the class exists rather than a rule about it.</b> Two classes had
 * grown the same anchored regex over {@code artest rocket list}; adding an {@code age} field between
 * {@code dim} and {@code pos} made both match nothing, and the caller counting matches then reported
 * <i>"exactly one rocket must stand in this plot, found 0"</i> — a sentence about the world produced
 * entirely by a broken reader. Twenty more classes carried a private {@code readDouble(json,
 * Pattern)} of their own, each with its own silent fallback.</p>
 *
 * <p><b>It refuses what it cannot parse</b>, naming the command and the reply. A reader that answers
 * a default instead cannot be told apart from one that read a real value, which is the defect above
 * wearing a tidier coat.</p>
 *
 * <p>A field that is ABSENT is reported as absent, never as zero. An absent measurement and a
 * measured zero are different readings, and a test that cannot tell them apart is not measuring.</p>
 *
 * <p><b>ONE RULE FOR EVERY READER HERE: the bare name REFUSES on absence, and the {@code …Or} name
 * defaults.</b> {@link #bool(String)}, {@link #number(String)}, {@link #text(String)},
 * {@link #integer(String)} and {@link #longInteger(String)} say that the producer always writes
 * this field, so not finding it is the reader being wrong rather than the world being some way.
 * {@link #boolOr}, {@link #numberOr}, {@link #textOr} and {@link #integerOr} say the opposite — the
 * producer writes it only sometimes — and the caller owes one line saying why absence is the answer
 * THERE. {@link #has(String)} asks the absence question outright, for a claim that is about it.</p>
 *
 * <p>The rule is greppable, which is the point: {@code Or(} is every place a default is still being
 * taken, and a default nobody can find is one nobody reviews.</p>
 */
public final class Reply {

    /** The field every probe verb writes when it refuses. Named here so `error()` and
     *  `refused()` cannot drift apart, and so the spelling is stated once. */
    private static final String ERROR = "error";

    private final String command;
    private final String raw;
    private final JsonObject json;

    private Reply(String command, String raw, JsonObject json) {
        this.command = command;
        this.raw = raw;
        this.json = json;
    }

    /** Parse a reply, or refuse naming it. {@code command} appears in every failure message. */
    public static Reply of(String command, String raw) {
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(String.valueOf(raw));
        } catch (RuntimeException notJson) {
            throw new AssertionError(command + " did not answer JSON (" + notJson + "): " + raw);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new AssertionError(command + " must answer one JSON object, not " + parsed
                    + ": " + raw);
        }
        return new Reply(command, String.valueOf(raw), parsed.getAsJsonObject());
    }

    /** The same for a reply whose command the caller no longer holds. */
    public static Reply of(String raw) {
        return of("this probe reply", raw);
    }

    /** Whether the reply carries {@code field} with a value at all. */
    public boolean has(String field) {
        return primitiveOf(field) != null;
    }

    /**
     * The primitive at {@code field}, refusing when the reply does not carry one.
     *
     * <p>Every refusing reader goes through here, so they all name the same two things: the verb
     * that answered and the reply it answered with. A caller reading the message sees what the
     * producer actually wrote, which is the one fact that decides whether the field was renamed,
     * whether the verb took a different branch, or whether the question was aimed at the wrong
     * reply entirely.</p>
     */
    private String requirePrimitive(String field) {
        String value = primitiveOf(field);
        if (value == null) {
            throw new AssertionError(command + " did not report `" + field + "`: " + raw);
        }
        return value;
    }

    /**
     * {@code field} as a number, refusing when the reply does not carry it.
     *
     * <p>Absence is not a measurement. A verb that did not write the field did not measure zero and
     * did not measure {@code NaN} — it took a branch the caller did not expect, or the producer
     * renamed the field, and both are the reader being wrong about the world rather than the world
     * being in some state. Where absence really is the answer — a negative claim, an optional
     * member — {@link #numberOr(String, double)} says so at the call site.</p>
     */
    public double number(String field) {
        String value = requirePrimitive(field);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            throw new AssertionError(command + "'s `" + field + "` is `" + value + "`, which is not"
                    + " a number: " + raw);
        }
    }

    /** {@code field} as a number, or {@code fallback} when absent — say out loud why a default is
     *  legitimate here, because it cannot be told from a measurement afterwards. */
    public double numberOr(String field, double fallback) {
        String value = primitiveOf(field);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** {@code field} as an int, refusing when the reply does not carry one — for a field the verb
     *  always writes, where absence is a broken probe rather than a reading. */
    public int integer(String field) {
        return (int) number(field);
    }

    /**
     * {@code field} as a long, refusing when the reply does not carry one.
     *
     * <p>Not {@code (long) number(field)}, and both halves of that matter. An ABSENT field answers
     * {@code NaN} there, and {@code (long) Double.NaN} is {@code 0} — so a field the producer
     * renamed reads as a bank holding nothing, a drive costing nothing, a clock at tick zero. And
     * an energy bank, a capacity or a world clock outgrows an {@code int}, so {@link #integer}
     * cannot carry one either: it truncates at {@code 2^31} without saying so.</p>
     *
     * <p>The digits are read as a long where the producer wrote a whole number, and only a decimal
     * rendering falls through to {@code double} — which is where a long would lose its last digits
     * anyway.</p>
     */
    public long longInteger(String field) {
        String value = requirePrimitive(field);
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notAWholeNumber) {
            return (long) number(field);
        }
    }

    /** {@code field} as an int, or {@code fallback} when absent. */
    public int integerOr(String field, int fallback) {
        double value = numberOr(field, Double.NaN);
        return Double.isNaN(value) ? fallback : (int) value;
    }

    /**
     * {@code field} as text, refusing when the reply does not carry it. Any primitive, as text: a
     * caller asking for a field cannot be wrong about its SHAPE, which is the verb's.
     *
     * <p>It refuses rather than answering {@code null} because a {@code null} is compared, not
     * checked: {@code "docked".equals(reply.text("state"))} is FALSE both when the state is
     * something else and when there is no state field at all, and only the first is a reading.
     * Where the absence itself is the claim, {@link #textOr(String, String)} or {@link #has(String)}
     * says which was meant.</p>
     */
    public String text(String field) {
        return requirePrimitive(field);
    }

    /**
     * {@code field} rendered FOR A MESSAGE, never as a reading.
     *
     * <p>The one place a default is not a decision: a failure message has to print whatever the
     * reply carried, and a reader that refused inside one would replace the failure under
     * investigation with a complaint about the message. So this never refuses — and it never
     * answers {@code "null"} either, which is the shape that made the old {@code textOr(f, null)}
     * in a message worth removing: {@code "null"} is also a value a producer writes, so a reader of
     * the failure could not tell "the field said null" from "there was no field".</p>
     *
     * <p>It is not a reading and must not be used as one: nothing compares its result, branches on
     * it, or passes it to another command. Those are {@link #text(String)} and
     * {@link #textOr(String, String)}.</p>
     */
    public String reported(String field) {
        String value = primitiveOf(field);
        return value == null ? "<no " + field + ">" : value;
    }

    /** {@code field} as text, or {@code fallback} when absent. */
    public String textOr(String field, String fallback) {
        String value = primitiveOf(field);
        return value == null ? fallback : value;
    }

    /**
     * {@code field} as a boolean, refusing when the reply does not carry it. Anything that is not
     * the literal {@code true} reads false, the way a JSON boolean does.
     *
     * <p>This is the verb that wave 3 exists for. {@code boolOr(f, false)} answers "no" to two
     * different worlds — the verb reported {@code false}, and the verb reported nothing — and a
     * test cannot act on the difference it cannot see. Most probe verbs write their flags on the
     * success path only, so the second world is an ARRANGEMENT that did not happen: a tile that
     * was not there, a lookup that missed, a verb that refused. Refusing says that, where a
     * {@code false} says the mechanic answered.</p>
     */
    public boolean bool(String field) {
        return "true".equalsIgnoreCase(requirePrimitive(field));
    }

    /** {@code field} as a boolean; {@code fallback} when absent — for a field the producer writes
     *  only sometimes, where the caller says in one line why absence is the answer. */
    public boolean boolOr(String field, boolean fallback) {
        String value = primitiveOf(field);
        return value == null ? fallback : "true".equalsIgnoreCase(value);
    }

    /**
     * Whether the verb reported success — the {@code ok} FIELD, read by name.
     *
     * <p><b>This exists because its absence was the single largest hole in the class.</b> Measured
     * 2026-09-18: {@code contains("\"ok\":true")} stood at <b>803 sites in 217 files</b>, forty-seven
     * per cent of every remaining substring test over a rendered reply — and the reason was that the
     * most frequent question a test asks a probe had no data form at all, while all 22 other verbs
     * here read a field by name.</p>
     *
     * <p><b>What the substring could not do, and this can.</b> A reply that is not JSON, or is a
     * truncated line, or is a different object entirely, answers {@code false} to the needle and the
     * caller reads it as "the verb said no". Here it never gets that far: {@link #of(String, String)}
     * refuses anything that is not one JSON object, naming it. What remains false is exactly one
     * thing — a reply that carries no {@code ok:true} — which is what the question means.</p>
     *
     * <p>A BRANCH may ask this ("it did not take, retry"). A claim that the verb REFUSED is a
     * different thing and belongs in {@link #requireOk(String)}: {@code !ok()} is also satisfied by
     * a reply that says nothing at all.</p>
     */
    public boolean ok() {
        return boolOr("ok", false);
    }

    /**
     * This reply, refusing as an ARRANGEMENT failure when the verb did not report success.
     *
     * <p>The type is deliberate: a probe verb that would not do what it was asked has not disproved
     * anything about the mechanic under test — it is the world not having been put in place, and the
     * gate's XML should record it as that. Use it where the call is a step of the arrangement; leave
     * an {@code assertTrue} where the verb's success IS the claim under test.</p>
     */
    public Reply requireOk(String what) {
        if (!ok()) {
            ArrangementFailure.arrangementFailed(what + " — the verb did not report ok: " + raw);
        }
        return this;
    }

    /**
     * Whether the verb REFUSED — the {@code error} FIELD, read by name.
     *
     * <p>The twin of {@link #ok()}, and the field this class had no reader for until 2026-09-20.
     * Its absence is why {@code contains("\"error\":\"no tile entity\"")} was the idiom: a refusal
     * is the one thing a probe verb says most often and the only shape a caller could not ask
     * for. {@code ok()} is not its negation — a reply can carry neither, and a verb that answers
     * a reading rather than a status carries no {@code ok} at all.</p>
     */
    public boolean refused() {
        return has(ERROR);
    }

    /**
     * The refusal in the producer's OWN WORDS, refusing when the verb did not refuse.
     *
     * <p>It refuses rather than answering {@code null} for the reason {@link #text} does: a
     * {@code null} is compared, and {@code "no tile entity".equals(reply.error())} would then be
     * false both for a different refusal and for a reply that succeeded — only the first is a
     * reading. Ask {@link #refused()} first where the question is whether it refused at all.</p>
     *
     * <p><b>Compare it, do not search it.</b> These strings are short and exact —
     * {@code "no tile entity"}, {@code "not an emitter"}, {@code "world not loaded"} — so
     * {@code assertEquals} says which refusal was expected and prints the one that came. A
     * {@code startsWith} is right only where the producer builds the message around a number, as
     * {@code "tile.update() threw after N ticks: …"} does, and then it is a reading OF THE FIELD
     * rather than of the rendering around it.</p>
     */
    public String error() {
        String value = primitiveOf(ERROR);
        if (value == null) {
            throw new AssertionError(command + " did not refuse — it carries no `" + ERROR
                    + "`: " + raw);
        }
        return value;
    }

    /**
     * Whether the verb refused with EXACTLY this message — a branch, where {@link #error()} is
     * the reading.
     *
     * <p>For the caller that asks "did it say THIS" and must act on the answer rather than fail
     * on it: a retry under a different spelling, a smoke test recording a verdict per block.
     * Answers false for a reply that succeeded and for one that refused differently, and those
     * are the two things the substring it replaces could not tell apart from each other.</p>
     */
    public boolean refusedWith(String message) {
        String value = primitiveOf(ERROR);
        return value != null && value.equals(message);
    }

    /**
     * The ONE element of the ARRAY {@code field} whose {@code member} is {@code value} — the object
     * the caller NAMED — refusing when the list holds no such element, or more than one.
     *
     * <p><b>A test checks the object its own fixture created, and this is what lets it say so.</b>
     * Maintainer ruling 2026-09-18, on the shape this replaces: a verb answering whether ANY
     * element carries a value is a weaker question than the test's own claim — it passes on a
     * neighbour's slot, a neighbouring station's row, another satellite — and, because it answers
     * a {@code boolean}, it THROWS AWAY WHICH ONE MATCHED. A caller that then wants another field
     * of that object has to ask again, and the second question may land on a different element:
     * "a slot holds sticks" and "a slot holds 16" are both true of a hatch holding one stick and
     * sixteen cobblestones. Here the element is fetched once, by the member that IDENTIFIES it,
     * and everything else is read off THAT object — so a failure names which field differed.</p>
     *
     * <p>{@code StationPads.at(x, z)} is the same verb for one producer, and it is where this
     * vocabulary already lived; this is the general form of it.</p>
     *
     * <p><b>Why more than one is also a refusal.</b> You address an element by something that
     * identifies it — a slot index, an id, a name. Two matches mean the member does not identify,
     * and every read afterwards is a coin toss wearing the shape of a clean answer.</p>
     *
     * <p>Measured 2026-09-18: the substrings these call sites came from searched the WHOLE
     * rendering, so they found the field wherever it lived. Converting them turned <b>33 server
     * tests red in one run</b>, each one {@code refuseIfOnlyNested} naming the member that owns the
     * field — {@code slots[].item}, {@code tanks[].fluid}, {@code items[].item},
     * {@code stations[].id}, {@code satellites[].id}, {@code rockets[].id}, {@code groups[].name},
     * {@code ships[].state}. The refusal was right in every case.</p>
     */
    public Reply element(String field, String member, String value) {
        // absence is the answer inside the scan below: an ARRAY is heterogeneous by nature, so
        // an element that does not carry `member` is one that does not match — refusing there
        // would turn a neighbour's shape into a failure of the caller's own question.
        String[] elements = objectArray(field);
        Reply found = null;
        for (String element : elements) {
            Reply one = Reply.of(command + " [" + field + "]", element);
            if (String.valueOf(value).equals(one.textOr(member, null))) {
                if (found != null) {
                    throw new AssertionError(command + "'s `" + field + "` holds MORE THAN ONE"
                            + " element whose `" + member + "` is " + value + ", so that member"
                            + " does not identify one of them: " + raw);
                }
                found = one;
            }
        }
        if (found == null) {
            throw new AssertionError(command + "'s `" + field + "` holds no element whose `"
                    + member + "` is " + value + " — it holds " + elements.length + ": " + raw);
        }
        return found;
    }

    /**
     * The ONE element of the ARRAY {@code field} carrying EVERY one of {@code memberValues},
     * given as {@code member, value, member, value, …} — for a subject whose ADDRESS takes more
     * than one field.
     *
     * <p>{@link #element(String, String, String)} names an object by a single member, which is the
     * right shape when one exists: an id, a uuid, a slot index. Where it does not — a block is
     * {@code posX} AND {@code posY} AND {@code posZ} — a single member is a DESCRIPTION, and the
     * same syntax carries both. Asking by every field of the address makes the answer structurally
     * about the object the caller built.</p>
     *
     * <p>Refuses on none and on more than one, for the same reason {@code element} does.</p>
     */
    public Reply elementWhereAll(String field, String... memberValues) {
        if (memberValues.length == 0 || memberValues.length % 2 != 0) {
            throw new AssertionError("elementWhereAll takes member, value pairs and was given "
                    + memberValues.length + " argument(s): "
                    + java.util.Arrays.toString(memberValues));
        }
        String[] elements = objectArray(field);
        Reply found = null;
        for (String element : elements) {
            Reply one = Reply.of(command + " [" + field + "]", element);
            boolean all = true;
            for (int i = 0; i < memberValues.length && all; i += 2) {
                // absence is the answer inside the scan, as in `element` above: an element that
                // does not carry the member is not the one being addressed.
                all = String.valueOf(memberValues[i + 1]).equals(one.textOr(memberValues[i], null));
            }
            if (all) {
                if (found != null) {
                    throw new AssertionError(command + "'s `" + field + "` holds MORE THAN ONE"
                            + " element carrying " + java.util.Arrays.toString(memberValues)
                            + ", so those members do not address one of them: " + raw);
                }
                found = one;
            }
        }
        if (found == null) {
            throw new AssertionError(command + "'s `" + field + "` holds no element carrying "
                    + java.util.Arrays.toString(memberValues) + " — it holds " + elements.length
                    + ": " + raw);
        }
        return found;
    }

    /**
     * Whether the ARRAY {@code field} holds an element whose {@code member} is {@code value}.
     *
     * <p><b>For a NEGATIVE claim, and only that</b> — "the hatch no longer holds sticks anywhere",
     * where absence IS the subject and {@link #element}'s refusal would be the pass. A positive
     * claim uses {@code element}: a test that built the thing can name it, and naming it is what
     * makes the reading about that thing.</p>
     */
    public boolean holdsElement(String field, String member, String value) {
        // absence is the answer, as in element above: an element without `member` does not match.
        for (String element : objectArray(field)) {
            if (String.valueOf(value).equals(
                    Reply.of(command + " [" + field + "]", element).textOr(member, null))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A fully-qualified class name this reply carries, as its SIMPLE name — refusing when the
     * reply does not carry the field.
     *
     * <p><b>What a test means when it names a tile or an entity is the simple name</b>, and the
     * probes report the qualified one. Every caller that bridged that gap did it with
     * {@code contains("TileBeacon")}, which is satisfied by {@code TileBeaconAdvanced}, by a
     * package component carrying those letters, and by the name appearing in a refusal about a
     * different block entirely. Taking the name apart and comparing it makes those different
     * answers.</p>
     *
     * <p>It lives here rather than in each reply's own reader because three of them needed it
     * within one sweep, and the fourth copy is the one that would have differed.</p>
     */
    public String simpleClassName(String field) {
        String qualified = text(field);
        int lastDot = qualified.lastIndexOf('.');
        return lastDot < 0 ? qualified : qualified.substring(lastDot + 1);
    }

    /**
     * Whether the TEXT array {@code field} holds exactly this value.
     *
     * <p>The twin of {@link #holdsElement} for an array of plain strings —
     * {@code "bound":["aboard record","rocket transfer grace"]} — and the data form of the idiom
     * it replaces. Callers asked {@code reply.contains("\"aboard record\"")}, which searches the
     * WHOLE rendering: it is satisfied by the value appearing in a different array, in a field
     * name, or inside a longer member ({@code "aboard record incomplete"} contains
     * {@code "aboard record"}), and it cannot tell an empty list from a missing one.</p>
     *
     * <p>Element equality is exact. Where the caller means "one of these", it says so with two
     * calls, because that is a different claim and deserves to read like one.</p>
     */
    public boolean holdsText(String field, String value) {
        for (String element : textArray(field)) {
            if (String.valueOf(value).equals(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the ARRAY {@code field} holds an element carrying EVERY one of
     * {@code memberValues}, given as {@code member, value, member, value, …}.
     *
     * <p>The fourth cell of a two-by-two this class carried three of: {@link #element} addresses
     * by one member and refuses, {@link #elementWhereAll} addresses by several and refuses,
     * {@link #holdsElement} asks by one and answers {@code false}. This asks by several.</p>
     *
     * <p><b>It exists because its absence turned a reading into a refusal.</b> A recipe test asks
     * "is this input slot still holding its initial stack" — and a slot the recipe CONSUMED is
     * absent from the list entirely, which is the answer. Written with {@code elementWhereAll}
     * the drained case threw instead of answering, so the one state the check exists to detect
     * was the one it could not report. Measured 2026-09-20: six recipe e2es, red on the first
     * run of the conversion that introduced it.</p>
     */
    public boolean holdsElementWithAll(String field, String... memberValues) {
        if (memberValues.length == 0 || memberValues.length % 2 != 0) {
            throw new AssertionError("holdsElementWithAll takes member, value pairs and was given "
                    + memberValues.length + " argument(s): "
                    + java.util.Arrays.toString(memberValues));
        }
        for (String element : objectArray(field)) {
            Reply one = Reply.of(command + " [" + field + "]", element);
            boolean all = true;
            for (int i = 0; i < memberValues.length && all; i += 2) {
                // absence is the answer inside the scan, as in `element` above: an element that
                // does not carry the member is not the one being addressed.
                all = String.valueOf(memberValues[i + 1]).equals(one.textOr(memberValues[i], null));
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many elements the ARRAY field holds, or {@code -1} when the reply carries no such array.
     *
     * <p>The {@code -1} is the whole point and it is not a fallback: {@link #intArray} answers an
     * EMPTY array for a field that is absent, which is the right shape for a caller iterating and
     * the wrong one for a caller ASKING — "the galaxy registered no dimensions" and "the probe
     * stopped reporting them" are opposite findings behind one zero. Measured 2026-09-18: seventeen
     * sites spelled this as {@code contains("\"arDimensions\":[]")}, and the three beside them that
     * meant "the key is there at all" spelled it {@code contains("\"arDimensions\":[")} — a needle
     * that is a PREFIX of the first, so one of the two pairs could never have distinguished them.</p>
     */
    public int arrayLength(String field) {
        return json.has(field) && json.get(field).isJsonArray()
                ? json.getAsJsonArray(field).size() : -1;
    }

    /** One element of a numeric ARRAY field ({@code "pos":[x,y,z]}), or {@code NaN} when the reply
     *  carries no such array or it is shorter than {@code index}. */
    public double arrayNumber(String field, int index) {
        if (!json.has(field) || !json.get(field).isJsonArray()) {
            refuseIfOnlyNested(field);
            return Double.NaN;
        }
        JsonArray array = json.getAsJsonArray(field);
        if (index < 0 || index >= array.size()) {
            return Double.NaN;
        }
        try {
            return array.get(index).getAsDouble();
        } catch (RuntimeException notANumber) {
            return Double.NaN;
        }
    }

    /**
     * Every integer of an array field ({@code "arDimensions":[0,9701,9702]}), in order, or an EMPTY
     * array when the reply carries none — an array that is present and empty and one that is absent
     * are told apart by {@link #has}.
     *
     * <p>Seventeen call sites matched the brackets with a regex and then split the captured text on
     * commas, so each carried its own opinion about spaces, signs and an empty list.</p>
     */
    public int[] intArray(String field) {
        if (!json.has(field) || !json.get(field).isJsonArray()) {
            refuseIfOnlyNested(field);
            return new int[0];
        }
        JsonArray array = json.getAsJsonArray(field);
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = array.get(i).getAsInt();
            } catch (RuntimeException notANumber) {
                throw new AssertionError(command + "'s `" + field + "`[" + i + "] is not a number: "
                        + raw);
            }
        }
        return out;
    }

    /**
     * A nested OBJECT field as its own JSON text ({@code "fuels":{…}}), or {@code null} when the
     * reply carries none — hand it back to {@link #of(String)} to read inside it.
     *
     * <p>This is what a reply whose shape is a MAP needs. The regex it replaces in the fuel readers
     * used a back-reference to look up the entry named by another field, which works exactly once:
     * the moment the producer writes the two in the other order, the expression matches nothing and
     * the caller reads "no fuel".</p>
     */
    public String object(String field) {
        if (!json.has(field) || !json.get(field).isJsonObject()) {
            refuseIfOnlyNested(field);
            return null;
        }
        return json.getAsJsonObject(field).toString();
    }

    /**
     * Every VALUE of a MAP-SHAPED object field ({@code "fuel":{"RP_FUEL":{…},"LIQUID_FUEL":{…}}}),
     * each as its own JSON text.
     *
     * <p>The keys there belong to the producer — a registry's enum names, a dimension's ids — so a
     * caller asking "every entry" has no name to ask by, and the only reader it could otherwise
     * write is a regex over the whole rendering.</p>
     */
    public String[] objectValues(String field) {
        if (!json.has(field) || !json.get(field).isJsonObject()) {
            refuseIfOnlyNested(field);
            return new String[0];
        }
        JsonObject members = json.getAsJsonObject(field);
        String[] out = new String[members.entrySet().size()];
        int i = 0;
        for (java.util.Map.Entry<String, JsonElement> member : members.entrySet()) {
            out[i++] = member.getValue().toString();
        }
        return out;
    }

    /**
     * Every element of an array of OBJECTS ({@code "ships":[{…},{…}]}) as its own JSON text, so a
     * caller reads one member's fields by handing it back to {@link #of(String)}.
     *
     * <p>This is what a list-shaped reply needs and a regex cannot give: matching three coordinate
     * fields in one expression only works while they stay adjacent and in that order, and it
     * silently pairs one member's x with another's z the moment they do not.</p>
     */
    public String[] objectArray(String field) {
        if (!json.has(field) || !json.get(field).isJsonArray()) {
            refuseIfOnlyNested(field);
            return new String[0];
        }
        JsonArray array = json.getAsJsonArray(field);
        String[] out = new String[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).toString();
        }
        return out;
    }

    /**
     * Every element of an array field as text ({@code "ships":["a","b"]}), in order, or an EMPTY
     * array when the reply carries none.
     */
    public String[] textArray(String field) {
        if (!json.has(field) || !json.get(field).isJsonArray()) {
            refuseIfOnlyNested(field);
            return new String[0];
        }
        JsonArray array = json.getAsJsonArray(field);
        String[] out = new String[array.size()];
        for (int i = 0; i < out.length; i++) {
            JsonElement element = array.get(i);
            out[i] = element.isJsonPrimitive() ? element.getAsString() : element.toString();
        }
        return out;
    }

    /**
     * The three integers of a block-position array field ({@code "builderPos":[x,y,z]}), or
     * {@code null} when the reply carries no such array.
     *
     * <p>The single most-copied read in the tier: 86 call sites matched
     * {@code "\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]"} and then parsed three capture groups,
     * so a producer that started writing the coordinates as doubles, or put a space after a comma,
     * would have told 86 tests at once that the fixture had reported no position.</p>
     */
    public int[] blockPos(String field) {
        double x = arrayNumber(field, 0);
        double y = arrayNumber(field, 1);
        double z = arrayNumber(field, 2);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return null;
        }
        return new int[]{(int) x, (int) y, (int) z};
    }

    /**
     * Every element of an array OF positions ({@code "outputPositions":[[x,y,z],[x,y,z]]}), in
     * order, or an empty array when the reply carries none.
     */
    public int[][] blockPosArray(String field) {
        if (!json.has(field) || !json.get(field).isJsonArray()) {
            refuseIfOnlyNested(field);
            return new int[0][];
        }
        JsonArray array = json.getAsJsonArray(field);
        int[][] out = new int[array.size()][];
        for (int i = 0; i < out.length; i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonArray() || element.getAsJsonArray().size() < 3) {
                throw new AssertionError(command + "'s `" + field + "`[" + i + "] is not an"
                        + " [x, y, z]: " + raw);
            }
            JsonArray triple = element.getAsJsonArray();
            out[i] = new int[]{triple.get(0).getAsInt(), triple.get(1).getAsInt(),
                    triple.get(2).getAsInt()};
        }
        return out;
    }

    /** The same, refusing rather than answering {@code null} — for a field the verb always writes. */
    public int[] requireBlockPos(String field) {
        int[] pos = blockPos(field);
        if (pos == null) {
            throw new AssertionError(command + " did not report `" + field + "` as [x, y, z]: " + raw);
        }
        return pos;
    }

    /** The whole reply, for a failure message — the reader never hides what it read. */
    @Override
    public String toString() {
        return raw;
    }

    private String primitiveOf(String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            refuseIfOnlyNested(field);
            return null;
        }
        JsonElement value = json.get(field);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /**
     * Refuse a field that is NOT on this reply but sits inside one of its members, naming where.
     *
     * <p><b>This exists because the alternative is silent.</b> A regex over the rendering found a
     * field wherever it sat; a reader that asks the reply by name finds only the reply's own, and
     * answers ABSENT for everything nested. Absence is a reading a caller acts on — a default, a
     * skip, an assertion that the world did not do the thing — so the conversion turns a working
     * test into one that reports on the world while describing its own reader.</p>
     *
     * <p><b>Measured 2026-09-17</b>, converting the tree's regex readers: 14 of 670 server tests
     * red, every one of them this. Four asked {@code artest fluid stored} for {@code amount} and
     * {@code capacity}, which are a TANK's fields ({@code tanks:[{…}]}); one asked
     * {@code artest rocket list} for {@code uuid}, which belongs to a ROCKET; six asked an
     * {@code events since} envelope for {@code dim}, which belongs to a RECORD. Each printed the
     * value it said was missing, in its own failure message.</p>
     *
     * <p>So the answer is neither the value nor "no": it is that the QUESTION is aimed one level
     * too high, and only this reader can see that. A caller that really wants "not on this reply,
     * whatever the members hold" asks the member it means.</p>
     */
    private void refuseIfOnlyNested(String field) {
        String path = nestedPathOf(json, field, "");
        if (path != null) {
            throw new AssertionError(command + " carries no `" + field + "` of its own, but one"
                    + " lives at `" + path + "` — that is a field of a MEMBER, and reading it off"
                    + " the reply answers a silent absence. Ask the member (objectArray /"
                    + " objectValues / object) for it: " + raw);
        }
    }

    /** Where {@code field} sits below {@code at}, as a dotted path, or {@code null}. */
    private static String nestedPathOf(JsonElement at, String field, String prefix) {
        if (at.isJsonObject()) {
            JsonObject object = at.getAsJsonObject();
            for (java.util.Map.Entry<String, JsonElement> member : object.entrySet()) {
                String here = prefix.isEmpty() ? member.getKey() : prefix + "." + member.getKey();
                if (!prefix.isEmpty() && member.getKey().equals(field)) {
                    return here;
                }
                String deeper = nestedPathOf(member.getValue(), field, here);
                if (deeper != null) {
                    return deeper;
                }
            }
        } else if (at.isJsonArray()) {
            JsonArray array = at.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                String deeper = nestedPathOf(array.get(i), field, prefix + "[" + i + "]");
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }
}
