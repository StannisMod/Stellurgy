package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest station pads <id>} — every landing pad a station holds.
 *
 * <p>Measured 2026-09-17: four classes read this reply across sixteen call sites, and every one of
 * them was a {@code contains} over the WHOLE list.</p>
 *
 * <h2>A list read by substring pools its members</h2>
 *
 * <p>{@code pads.contains("\"x\":10") && pads.contains("\"z\":20")} is satisfied by a station
 * holding a pad at {@code (10, 99)} and another at {@code (77, 20)} — the assertion is about a pad
 * that does not exist. And {@code pads.contains("\"occupied\":true")} is satisfied when ANY pad is
 * occupied, while the message beside it says "the pad": the dock tests assert exactly that the pad
 * they docked at is the one that changed, and the substring cannot say which pad it read.</p>
 *
 * <p>So this reader is a LIST of pads addressed by POSITION: {@link #at(int, int)} finds the one
 * the caller means and refuses when the station holds no pad there.</p>
 *
 * <h2>{@code name} is absent, not empty, for an unnamed pad</h2>
 *
 * <p>The producer omits the field when {@code getName()} is null. {@link Pad#name()} refuses that
 * rather than answering the empty string, because a pad's name is what a player reads off the
 * station's own list and an empty one is a different reading from an absent one.</p>
 */
public final class StationPads {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** One landing pad of the station. */
    public static final class Pad {

        /** Its position, in the station's own world coordinates. */
        public final int x;
        public final int z;
        /** Whether a craft is on it right now. */
        public final boolean occupied;
        /** Whether the station owner opted it into automatic landing. */
        public final boolean allowAutoLand;

        private final Reply reply;
        private final String raw;

        private Pad(Reply reply, String raw) {
            this.reply = reply;
            this.raw = raw;
            this.x = reply.integer("x");
            this.z = reply.integer("z");
            this.occupied = reply.bool("occupied");
            this.allowAutoLand = reply.bool("allowAutoLand");
        }

        /** The pad's name. Refuses an unnamed pad — see the class note. */
        public String name() {
            return reply.text("name");
        }

        /** Whether the pad carries a name at all. */
        public boolean hasName() {
            // absence is the answer: this verb's whole subject is whether a name is there.
            return reply.textOr("name", null) != null;
        }

        /** This pad exactly as the producer wrote it. */
        public String raw() {
            return raw;
        }

        @Override
        public String toString() {
            return "pad " + x + "," + z + " occupied=" + occupied + " autoLand=" + allowAutoLand;
        }
    }

    /** The station this reading is about, as the reply echoed it back. */
    public final int stationId;

    private final Reply reply;
    private final String raw;

    private StationPads(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.stationId = reply.integer("id");
    }

    /**
     * Read one {@code station pads} reply, or refuse.
     *
     * <p>Refuses {@code {"error":"station not found or wrong type","id":N}}. That reply carries the
     * id and no {@code pads} array, so read as a list it is a station with no pads at all — which
     * is a state several of these tests arrange and assert.</p>
     */
    public static StationPads of(String padsReply) {
        String text = String.valueOf(padsReply);
        Reply reply = Reply.of("artest station pads", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest station pads` found no station ("
                    + reply.text("error") + "), so this is not a list of its pads — and read as one"
                    + " it is a station holding none: " + text);
        }
        // `has` asks for a PRIMITIVE and answers false for an array, so it can never answer this
        // question — which is why a substring stood beside it. `arrayLength` is the verb that
        // can: -1 exactly when the reply carries no such array, and 0 for a station with no pads,
        // which is a reading and must not be refused here.
        if (reply.arrayLength("pads") < 0) {
            throw new AssertionError("this is not an `artest station pads` answer: it carries no"
                    + " `pads`: " + text);
        }
        return new StationPads(reply, text);
    }

    /** Ask the server for one station's pads. */
    public static StationPads byId(Probe probe, int stationId) throws Exception {
        return of(probe.exec("artest station pads " + stationId));
    }

    /** How many pads the station holds. */
    public int count() {
        return reply.objectArray("pads").length;
    }

    /** Every pad, in the producer's own order. */
    public Pad[] all() {
        String[] listed = reply.objectArray("pads");
        Pad[] out = new Pad[listed.length];
        for (int i = 0; i < listed.length; i++) {
            out[i] = new Pad(Reply.of("one station pad", listed[i]), listed[i]);
        }
        return out;
    }

    /**
     * The pad AT {@code (x, z)}, refusing when the station holds none there.
     *
     * <p>This is the whole point of the reader: every call site it replaces asked the list as a
     * whole and got an answer about whichever pad happened to match.</p>
     */
    public Pad at(int x, int z) {
        for (Pad pad : all()) {
            if (pad.x == x && pad.z == z) {
                return pad;
            }
        }
        throw new AssertionError("station " + stationId + " holds no pad at " + x + "," + z
                + " — it holds " + count() + ": " + raw);
    }

    /** Whether the station holds a pad at {@code (x, z)} at all. */
    public boolean has(int x, int z) {
        for (Pad pad : all()) {
            if (pad.x == x && pad.z == z) {
                return true;
            }
        }
        return false;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "station " + stationId + " holds " + count() + " pad(s)";
    }
}
