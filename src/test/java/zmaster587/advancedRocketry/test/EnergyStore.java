package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest energy stored <dim> <x> <y> <z>} — what the Forge energy capability
 * at a block reports, if it has one.
 *
 * <p>Measured 2026-09-17: thirteen classes read this reply. Ten of them ask the same question,
 * {@code contains("\"hasEnergy\":true")}, and seven then read {@code energyStored} or
 * {@code energyMax} through a private helper of their own.</p>
 *
 * <h2>The two error shapes read as an EMPTY battery, which is the usual assertion</h2>
 *
 * <p>{@code {"error":"no tile entity","pos":[…]}} is what a block that is not there answers, and
 * {@code world not loaded} is the other. Both carry no {@code energyStored} — so a helper that
 * reads it as {@code 0}, or a {@code contains("\"hasEnergy\":false")} that is simply not satisfied,
 * turns "there is no machine at that position" into "the machine holds nothing". Half the call
 * sites here are asserting exactly that a battery is empty or that a machine has no capability, so
 * {@link #of} refuses both errors rather than letting a mis-placed fixture prove the claim.</p>
 *
 * <h2>{@code hasEnergy:false} carries no numbers at all</h2>
 *
 * <p>A tile with no energy capability answers the position and its class and nothing else. That is
 * a legitimate reading — several tests assert it, because a manual assembler is supposed to have no
 * capability — so it is {@link #hasEnergy} and not a refusal; but every number below it refuses,
 * because an absent {@code energyStored} is not a store holding zero.</p>
 *
 * <h2>{@code energyFace} is which face answered, and it is reported because it VARIES</h2>
 *
 * <p>The probe walks all six faces and then the null face, taking the first that exposes the
 * capability. A machine that exposes power on one side only is a different machine from one that
 * exposes it everywhere, and a test that cannot see which face answered cannot tell them apart.</p>
 */
public final class EnergyStore {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Which dimension and block this reading is about, as the reply echoed them back. */
    public final int dim;
    public final int posX;
    public final int posY;
    public final int posZ;
    /** Whether the tile exposes Forge's energy capability on any face. See the class note. */
    public final boolean hasEnergy;

    private final Reply reply;
    private final String raw;

    private EnergyStore(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.dim = reply.integer("dim");
        this.posX = reply.integer("posX");
        this.posY = reply.integer("posY");
        this.posZ = reply.integer("posZ");
        this.hasEnergy = reply.bool("hasEnergy");
    }

    /**
     * Read one {@code energy stored} reply, or refuse.
     *
     * <p>Refuses both error shapes — see the class note: each of them reads as an empty machine,
     * which is what most of these call sites assert.</p>
     */
    public static EnergyStore of(String storedReply) {
        String text = String.valueOf(storedReply);
        Reply reply = Reply.of("artest energy stored", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest energy stored` found nothing to ask ("
                    + reply.text("error") + ") — read as a reading, this reply is a machine with no"
                    + " energy capability and an empty store: " + text);
        }
        if (!reply.has("hasEnergy") || !reply.has("tileClass")) {
            throw new AssertionError("this is not an `artest energy stored` answer: it carries no"
                    + " `hasEnergy` beside the `tileClass` that was asked: " + text);
        }
        return new EnergyStore(reply, text);
    }

    /** Ask what the block at this position holds. */
    public static EnergyStore at(Probe probe, int dim, int x, int y, int z) throws Exception {
        return of(probe.exec("artest energy stored " + dim + " " + x + " " + y + " " + z));
    }

    /** This reading, refusing when the tile exposes no energy capability at all. */
    public EnergyStore requireEnergy(String what) {
        if (!hasEnergy) {
            ArrangementFailure.arrangementFailed(what + " — the tile at " + posX + "," + posY + ","
                    + posZ + " (" + tileClass() + ") exposes no energy capability on any face, so"
                    + " it has no store to read: " + raw);
        }
        return this;
    }

    /** The tile's class, as the server names it. Always present. */
    public String tileClass() {
        return reply.text("tileClass");
    }

    /** How much energy the store holds. Refuses a tile with no store. */
    public long stored() {
        return quantity("energyStored");
    }

    /** How much it holds at most. Refuses a tile with no store. */
    public long capacity() {
        return quantity("energyMax");
    }

    /** Which face answered. See the class note on why it is reported. */
    public String energyFace() {
        requireStore("energyFace");
        return reply.text("energyFace");
    }

    /** Whether the store lets energy be taken out of it, and whether it takes any in. */
    public boolean canExtract() {
        requireStore("canExtract");
        return reply.bool("canExtract");
    }

    public boolean canReceive() {
        requireStore("canReceive");
        return reply.bool("canReceive");
    }

    private long quantity(String field) {
        requireStore(field);
        return reply.longInteger(field);
    }

    private void requireStore(String field) {
        if (!hasEnergy) {
            throw new AssertionError("the tile at " + posX + "," + posY + "," + posZ + " ("
                    + tileClass() + ") exposes no energy capability, so `" + field + "` is not"
                    + " something it has — and a zero here reads as an empty machine: " + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return hasEnergy
                ? tileClass() + " at " + posX + "," + posY + "," + posZ + " holds "
                        + reply.reported("energyStored") + "/" + reply.reported("energyMax")
                        + " on face " + reply.reported("energyFace")
                : tileClass() + " at " + posX + "," + posY + "," + posZ + " has no energy store";
    }
}
