package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space nebulae <sx> <sy> <sz>} — the clouds one cell's sky shows, and
 * how many the generator seated around it.
 *
 * <p>Measured 2026-09-17: two classes read this reply and spell five field names between them.</p>
 *
 * <h2>{@code seated} and {@code drawn} are two numbers on purpose</h2>
 *
 * <p>{@code seated} is how many clouds the generator put within reach; {@code drawn} is how many
 * of them the sky renders after the level-of-detail filter. Reported separately so a reader can
 * tell a filter doing its job from a generator that stopped seating — and the invariant a caller
 * asserts is that {@link #drawn} never exceeds {@link #seated}, which needs both to be REAL
 * readings. An absent field answering zero satisfies that inequality trivially, which is why this
 * reader refuses rather than defaulting.</p>
 *
 * <h2>The clouds are a LIST, and a substring cannot address one</h2>
 *
 * <p>Each rendered cloud carries a direction, an angular radius, an appearance and an opacity. One
 * call site asserted {@code feed.contains("\"angularRadius\":")} to mean "a drawn cloud covers
 * something of the sky", which is satisfied by the FIELD being present and says nothing about its
 * value; read as a list, the same claim is every cloud's radius being above zero.</p>
 */
public final class SkyNebulae {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** One cloud as the sky renders it. */
    public static final class Cloud {

        /** Which way it lies, as a unit direction. */
        public final double dirX;
        public final double dirY;
        public final double dirZ;
        /** How much of the sky it covers. */
        public final double angularRadius;
        /** Which appearance the renderer picked, as the enum's ordinal. */
        public final int appearance;
        /** How solid it is drawn. */
        public final double opacity;

        private final String raw;

        private Cloud(Reply reply, String raw) {
            this.raw = raw;
            this.dirX = reply.number("dirX");
            this.dirY = reply.number("dirY");
            this.dirZ = reply.number("dirZ");
            this.angularRadius = reply.number("angularRadius");
            this.appearance = reply.integer("appearance");
            this.opacity = reply.number("opacity");
        }

        /** This cloud exactly as the producer wrote it. */
        public String raw() {
            return raw;
        }

        @Override
        public String toString() {
            return "cloud at " + dirX + "," + dirY + "," + dirZ + " radius=" + angularRadius;
        }
    }

    /** The world seed the clouds were generated from — a fact about the save, not the cell. */
    public final long seed;
    /** How many clouds the generator seated within reach, and how many the sky draws. */
    public final int seated;
    public final int drawn;

    private final Reply reply;
    private final String raw;

    private SkyNebulae(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.seed = reply.longInteger("seed");
        this.seated = reply.integer("seated");
        this.drawn = reply.integer("drawn");
    }

    /** Read one {@code space nebulae} reply, or refuse. */
    public static SkyNebulae of(String feedReply) {
        String text = String.valueOf(feedReply);
        Reply reply = Reply.of("artest space nebulae", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest space nebulae` could not run ("
                    + reply.text("error") + "), so this is not a reading of any sky — and read as"
                    + " one it is a sky with nothing in it, which is what the negative leg of this"
                    + " tier asserts: " + text);
        }
        if (!reply.has("seated") || !reply.has("drawn")) {
            throw new AssertionError("this is not an `artest space nebulae` answer: it carries no"
                    + " `seated` beside its `drawn`: " + text);
        }
        return new SkyNebulae(reply, text);
    }

    /** Ask what one cell's sky holds. */
    public static SkyNebulae at(Probe probe, long sectorX, long sectorY, long sectorZ)
            throws Exception {
        return of(probe.exec("artest space nebulae " + sectorX + " " + sectorY + " " + sectorZ));
    }

    /** The cell this reading is about, as the producer's own key. */
    public String cellKey() {
        return reply.text("cell");
    }

    /** Every cloud the sky draws, in the producer's own order. */
    public Cloud[] clouds() {
        String[] listed = reply.objectArray("nebulae");
        Cloud[] out = new Cloud[listed.length];
        for (int i = 0; i < listed.length; i++) {
            out[i] = new Cloud(Reply.of("one rendered nebula", listed[i]), listed[i]);
        }
        return out;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "cell " + reply.text("cell") + " draws " + drawn + " of " + seated + " seated";
    }
}
