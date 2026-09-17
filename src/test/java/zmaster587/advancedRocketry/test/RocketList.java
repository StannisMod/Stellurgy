package zmaster587.advancedRocketry.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * One reading of {@code artest rocket list <dim>}, parsed as the JSON it is.
 *
 * <p>The reply is a GLOBAL query — on a shared harness the world holds every other scenario's craft
 * too — so every caller has to narrow it, and two classes had grown the same REGEX to do that. That
 * regex was anchored on the reply's field ORDER, which is not something JSON promises: adding
 * {@code age} between {@code dim} and {@code pos} made both copies match nothing, and a caller
 * counting matches then reported <i>"exactly one rocket must stand in this plot, found 0"</i> — a
 * sentence about the WORLD, produced entirely by a broken reader.</p>
 *
 * <p>So this reads fields BY NAME and <b>refuses</b> a reply it cannot parse. An empty list is a
 * claim about the world, and it is returned only when the world really holds no rocket.</p>
 *
 * <p><b>{@code age} is why this class exists now.</b> When the list answers with more than one craft,
 * a caller has to say which of them it built, and a POSITION cannot do that: a craft that has moved
 * and a craft built somewhere else are the same coordinate. Ticks-existed separates them — tens for
 * one just assembled, thousands for one an earlier scenario left behind.</p>
 */
public final class RocketList {

    private RocketList() {
    }

    /** One rocket as the probe reported it. */
    public static final class Entry {
        public final int id;
        /**
         * The craft's persistent identity — the one thing a dimension change preserves, where the
         * entity id does not. {@code null} when the probe did not report one, which is a reading:
         * a caller asserting the surface exposes it must be able to see that it did not.
         */
        public final String uuid;
        public final int dim;
        /** Ticks this entity has existed — the age that separates a fresh build from a leftover. */
        public final int age;
        public final double x;
        public final double y;
        public final double z;

        Entry(JsonObject rocket) {
            this.id = rocket.get("id").getAsInt();
            this.uuid = rocket.has("uuid") && !rocket.get("uuid").isJsonNull()
                    ? rocket.get("uuid").getAsString() : null;
            this.dim = rocket.get("dim").getAsInt();
            this.age = rocket.get("age").getAsInt();
            JsonArray pos = rocket.getAsJsonArray("pos");
            this.x = pos.get(0).getAsDouble();
            this.y = pos.get(1).getAsDouble();
            this.z = pos.get(2).getAsDouble();
        }

        /** For a failure message: everything that separates one craft from another, in one token. */
        @Override
        public String toString() {
            return "id=" + id + "@" + x + "," + y + "," + z + " age=" + age + "t";
        }
    }

    /**
     * Every rocket in the reply, in the order the probe listed them.
     *
     * <p>Fails loudly on anything that is not this verb's reply — an error object, a truncated line,
     * a verb that does not exist. Those would otherwise read as an empty world, which is the one
     * thing an instrument must never be able to fake.</p>
     */
    public static List<Entry> of(String listReply) {
        JsonObject reply = object(listReply, "`artest rocket list`");
        if (!reply.has("rockets") || !reply.get("rockets").isJsonArray()) {
            throw new AssertionError("`artest rocket list` must answer an object carrying a"
                    + " `rockets` array, so that 'no rockets' is a statement about the WORLD and"
                    + " never about this reader: " + listReply);
        }
        List<Entry> found = new ArrayList<>();
        for (JsonElement rocket : reply.getAsJsonArray("rockets")) {
            found.add(new Entry(rocket.getAsJsonObject()));
        }
        return found;
    }

    /** Those of them standing inside {@code plot}. */
    public static List<Entry> inPlot(String listReply, Plot plot) {
        List<Entry> found = new ArrayList<>();
        for (Entry e : of(listReply)) {
            if (plot.contains(e.x, e.z)) {
                found.add(e);
            }
        }
        return found;
    }

    /**
     * Remove every rocket from {@code dim}, and answer how many went.
     *
     * <p><b>Why a scenario owes this, and why a PLOT does not cover it.</b> The allocator's promise
     * is spatial — one patch of world per scenario, never recycled, so nothing else ever looks here.
     * That holds for what STAYS PUT. A rocket does not: measured 2026-09-16, a craft 93 ticks old had
     * climbed 120 blocks and drifted 80 downrange into the NEXT scenario's plot, where that
     * scenario's own "how many rockets stand here" gate then found two and refused, three loaded runs
     * out of three. The craft left its owner's patch of world; no amount of allocation prevents that,
     * and only disposal does.</p>
     *
     * <p>The same reason {@code ShipReadiness.clearCraftFrom} was written for VS hulls the same week,
     * and the maintainer's ruling that produced it applies here word for word: <i>"в @After всем
     * тестам с кораблями надо добавить убийство их кораблей. Негоже мусор после себя оставлять."</i>
     * (in the @After of every test with craft, add killing its craft — leaving rubbish behind is not
     * on). An {@code EntityRocket} is that rubbish too, and it is the kind that moves.</p>
     *
     * <p>Clears EVERY rocket in the world rather than this scenario's alone, because by the time a
     * scenario ends its craft may be anywhere — which is the whole finding. That is sound because a
     * client class boots its own world. <b>A class that means to keep a rocket ACROSS scenarios must
     * not call this</b>; not calling it is the opt-out, and it is structural.</p>
     *
     * <p>Answers the count so a caller can print it. Nothing asserts on it: a scenario that built no
     * rocket legitimately clears none.</p>
     */
    public static int clearFrom(Events.Probe probe, int dim) throws Exception {
        String reply = probe.exec("artest rocket clear " + dim);
        JsonObject cleanup = object(reply, "the rocket cleanup verb");
        if (!cleanup.has("cleared")) {
            throw new AssertionError("the rocket cleanup verb must answer how many craft it removed,"
                    + " or a scenario cannot say whether it left anything behind: " + reply);
        }
        return cleanup.get("cleared").getAsInt();
    }

    /** Every entry rendered for a failure message — the ones outside the plot included, because an
     *  intruder's own coordinates and age are what say where it came from. */
    public static String describe(String listReply) {
        StringBuilder out = new StringBuilder();
        for (Entry e : of(listReply)) {
            out.append(' ').append(e);
        }
        return out.length() == 0 ? " (none)" : out.toString();
    }

    /** One reply as the object it is, or a refusal naming {@code what} answered and with what. */
    private static JsonObject object(String reply, String what) {
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(String.valueOf(reply));
        } catch (RuntimeException notJson) {
            throw new AssertionError(what + " must answer one JSON object; this could not be parsed ("
                    + notJson + "): " + reply);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new AssertionError(what + " must answer one JSON object, not " + parsed + ": "
                    + reply);
        }
        return parsed.getAsJsonObject();
    }
}
