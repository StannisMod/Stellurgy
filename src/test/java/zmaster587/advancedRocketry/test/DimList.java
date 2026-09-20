package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest dim list}: which dimensions the mod's registry holds, and which
 * ones Forge holds.
 *
 * <p><b>Why a class for two arrays.</b> The question every caller actually asks is
 * "is dim N registered", and thirteen of them asked it by searching the rendering for the digits:
 * {@code dimList.contains(String.valueOf(9402))}. A digit sequence matches anywhere in the blob —
 * inside {@code 94020}, inside {@code 19402}, inside a tick time or a seed that happens to contain
 * it — so the positive claim is satisfied with the dimension ABSENT and the negative claim
 * ("the malformed planet must not be registered") fails for a number belonging to something else.
 * Both directions are wrong and neither is visible at the call site.</p>
 *
 * <p>Membership is a question about a SET of integers, so it is asked of one.</p>
 */
public final class DimList {

    /** The probe this reads, so a caller needs no knowledge of the verb's spelling. */
    public interface Probe {
        String exec(String command) throws Exception;
    }

    private static final String AR_DIMENSIONS = "arDimensions";
    private static final String FORGE_DIMENSIONS = "forgeDimensions";

    private final Reply reply;
    private final String raw;

    private DimList(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
    }

    /** Parse a reply already in hand. */
    public static DimList of(String listReply) {
        return new DimList(Reply.of("artest dim list", listReply), listReply);
    }

    /** Ask the server. */
    public static DimList from(Probe probe) throws Exception {
        return of(probe.exec("artest dim list"));
    }

    /**
     * Every dimension the MOD's registry holds, in the producer's own order.
     *
     * <p>Refuses a reply that carries no such array, rather than answering an empty one: "the
     * registry holds nothing" and "the probe stopped reporting it" are opposite findings, and a
     * caller asking about membership must not have them merged.</p>
     */
    public int[] registered() {
        assertPresent(AR_DIMENSIONS);
        return reply.intArray(AR_DIMENSIONS);
    }

    /** Every dimension FORGE holds — a different question, and the one a world-load test asks. */
    public int[] forgeDimensions() {
        assertPresent(FORGE_DIMENSIONS);
        return reply.intArray(FORGE_DIMENSIONS);
    }

    /** Whether the mod's registry holds {@code dim}. */
    public boolean holds(int dim) {
        for (int registered : registered()) {
            if (registered == dim) {
                return true;
            }
        }
        return false;
    }

    /** Whether FORGE holds {@code dim} — a dimension can be one without being the other. */
    public boolean forgeHolds(int dim) {
        for (int registered : forgeDimensions()) {
            if (registered == dim) {
                return true;
            }
        }
        return false;
    }

    /** How many dimensions the mod's registry holds. */
    public int count() {
        return registered().length;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    private void assertPresent(String field) {
        if (!reply.has(field)) {
            throw new AssertionError("artest dim list did not report `" + field + "`: " + raw);
        }
    }
}
