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
 * <p>A field that is ABSENT is reported as absent — {@code NaN}, {@code null}, or the explicit
 * fallback a caller passes — and never as zero. An absent measurement and a measured zero are
 * different readings, and a test that cannot tell them apart is not measuring.</p>
 */
public final class Reply {

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

    /** {@code field} as a number, or {@code NaN} when the reply does not carry it. */
    public double number(String field) {
        String value = primitiveOf(field);
        if (value == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    /** {@code field} as a number, or {@code fallback} when absent — say out loud why a default is
     *  legitimate here, because it cannot be told from a measurement afterwards. */
    public double numberOr(String field, double fallback) {
        double value = number(field);
        return Double.isNaN(value) ? fallback : value;
    }

    /** {@code field} as an int, refusing when the reply does not carry one — for a field the verb
     *  always writes, where absence is a broken probe rather than a reading. */
    public int integer(String field) {
        double value = number(field);
        if (Double.isNaN(value)) {
            throw new AssertionError(command + " did not report `" + field + "`: " + raw);
        }
        return (int) value;
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
        String value = primitiveOf(field);
        if (value == null) {
            throw new AssertionError(command + " did not report `" + field + "`: " + raw);
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notAWholeNumber) {
            double asDouble = number(field);
            if (Double.isNaN(asDouble)) {
                throw new AssertionError(command + "'s `" + field + "` is `" + value + "`, which is"
                        + " not a number: " + raw);
            }
            return (long) asDouble;
        }
    }

    /** {@code field} as an int, or {@code fallback} when absent. */
    public int integerOr(String field, int fallback) {
        double value = number(field);
        return Double.isNaN(value) ? fallback : (int) value;
    }

    /** {@code field} as text, or {@code null} when the reply does not carry it. Any primitive, as
     *  text: a caller asking for a field cannot be wrong about its SHAPE, which is the verb's. */
    public String text(String field) {
        return primitiveOf(field);
    }

    /** {@code field} as text, or {@code fallback} when absent. */
    public String textOr(String field, String fallback) {
        String value = primitiveOf(field);
        return value == null ? fallback : value;
    }

    /** {@code field} as a boolean; {@code fallback} when absent. Anything that is not the literal
     *  {@code true} reads false, the way a JSON boolean does. */
    public boolean bool(String field, boolean fallback) {
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
        return bool("ok", false);
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
        String[] elements = objectArray(field);
        Reply found = null;
        for (String element : elements) {
            Reply one = Reply.of(command + " [" + field + "]", element);
            if (String.valueOf(value).equals(one.text(member))) {
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
     * Whether the ARRAY {@code field} holds an element whose {@code member} is {@code value}.
     *
     * <p><b>For a NEGATIVE claim, and only that</b> — "the hatch no longer holds sticks anywhere",
     * where absence IS the subject and {@link #element}'s refusal would be the pass. A positive
     * claim uses {@code element}: a test that built the thing can name it, and naming it is what
     * makes the reading about that thing.</p>
     */
    public boolean holdsElement(String field, String member, String value) {
        for (String element : objectArray(field)) {
            if (String.valueOf(value).equals(
                    Reply.of(command + " [" + field + "]", element).text(member))) {
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
