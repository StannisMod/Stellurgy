package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest vs find-seat <dim> id <shipId>} — where a craft's pilot seat is, in
 * its own subspace and in the world.
 *
 * <p>Measured 2026-09-17: seven classes spelled this reply's field names for themselves, across ten
 * distinct names, and twenty more sites read it through a locally-declared helper. It is the widest
 * producer left after {@code vs ship-info}.</p>
 *
 * <h2>{@code seatFound:false} is ONE word for THREE worlds, and this class will not let a caller
 * conflate them</h2>
 *
 * <p>The producer takes deliberate trouble to say which: it puts the SEARCHED BOX on every reply.
 * A false can mean the craft resolved no chunk claim at all ({@code yard} null), or that the claim
 * exists and its blocks have not been written into the subspace yet (a plausible box, nothing in
 * it), or that the craft genuinely carries no pilot seat. <b>The first two are arrangement faults
 * that look exactly like the third</b>, and a caller retrying the verb cannot tell which it is
 * waiting out. So {@link #requireFound} refuses with the yard in the message, and a caller that
 * wants to poll reads {@link #found} and decides for itself.</p>
 *
 * <h2>Two coordinate frames, and they are not interchangeable</h2>
 *
 * <p>{@link #seatX}/{@link #seatY}/{@link #seatZ} and the flight computer's {@code afc*} are
 * SUBSPACE block positions — the ship's own frame, which does not move when the ship does.
 * {@link #shipWorldX} and its siblings are the seat's live WORLD position, which is where a body has
 * to be teleported to reach it. A test that aims at a subspace coordinate in the world is aiming at
 * whatever happens to be at that address in the dimension, and the two are numerically plausible for
 * each other.</p>
 */
public final class PilotSeat {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether a pilot seat was found in the searched yard at all. */
    public final boolean found;
    /** The seat's SUBSPACE block position. {@link Integer#MIN_VALUE} when no seat was found. */
    public final int seatX;
    public final int seatY;
    public final int seatZ;
    /**
     * The linked flight computer's SUBSPACE position — seat position plus the stored offset, so a
     * test can target the computer block itself without geometry guesswork.
     *
     * <p>Absent when the seat carries no link, and absence is reported as {@link #hasAfc} rather
     * than as a coordinate: a seat that resolves no computer is a real and different finding from
     * one whose computer sits at the origin.</p>
     */
    public final int afcX;
    public final int afcY;
    public final int afcZ;
    /** Whether the seat resolved a linked flight computer. */
    public final boolean hasAfc;
    /** The seat's live WORLD position — where a body must be put to reach it. NaN when unreported. */
    public final double shipWorldX;
    public final double shipWorldY;
    public final double shipWorldZ;
    /**
     * The box that was searched, as the probe reported it, or {@code null} when the craft resolved
     * no chunk claim. This is the discriminator described above; it is here to be PRINTED.
     */
    public final int[] yard;

    private final String raw;

    private PilotSeat(Reply reply, String raw) {
        this.raw = raw;
        this.found = reply.bool("seatFound", false);
        this.seatX = reply.integerOr("seatX", Integer.MIN_VALUE);
        this.seatY = reply.integerOr("seatY", Integer.MIN_VALUE);
        this.seatZ = reply.integerOr("seatZ", Integer.MIN_VALUE);
        this.hasAfc = reply.has("afcX") && reply.has("afcY") && reply.has("afcZ");
        this.afcX = reply.integerOr("afcX", Integer.MIN_VALUE);
        this.afcY = reply.integerOr("afcY", Integer.MIN_VALUE);
        this.afcZ = reply.integerOr("afcZ", Integer.MIN_VALUE);
        this.shipWorldX = reply.number("shipWorldX");
        this.shipWorldY = reply.number("shipWorldY");
        this.shipWorldZ = reply.number("shipWorldZ");
        this.yard = reply.has("yard") ? reply.intArray("yard") : null;
    }

    /**
     * Read one {@code find-seat} reply, or refuse.
     *
     * <p>Refuses a reply that is not this verb's — an error object, a truncated line, a verb that
     * does not exist — because every one of those would otherwise read as {@code seatFound:false},
     * which is a statement about the CRAFT.</p>
     */
    public static PilotSeat of(String findSeatReply) {
        Reply reply = Reply.of("artest vs find-seat", String.valueOf(findSeatReply));
        if (!reply.has("seatFound")) {
            throw new AssertionError("`artest vs find-seat` must answer a `seatFound` field, or"
                    + " 'this craft has no pilot seat' cannot be told from 'that was not the reply I"
                    + " think it was': " + findSeatReply);
        }
        return new PilotSeat(reply, String.valueOf(findSeatReply));
    }

    /**
     * Ask {@code dim} about the craft named by {@code shipId}, and read the answer.
     *
     * <p>The id-keyed form, never the positional one: a scenario's craft shares its cell with
     * whatever earlier scenarios left there, and the yard lookup at a shared anchor answers about
     * the first craft ever assembled at it.</p>
     */
    public static PilotSeat byId(Probe probe, int dim, String shipId) throws Exception {
        return of(probe.exec("artest vs find-seat " + dim + " id " + shipId));
    }

    /**
     * This reading, refusing when no seat was found — with the searched box in the message, which is
     * what separates "no claim", "claim not yet written" and "no seat on this craft".
     */
    public PilotSeat requireFound(String what) {
        if (!found) {
            ArrangementFailure.arrangementFailed(what + " — no pilot seat was found. The searched"
                    + " yard was " + describeYard() + ", which is what says whether the craft"
                    + " resolved a chunk claim at all, whether its blocks have been written into the"
                    + " subspace yet, or whether it genuinely carries no seat: " + raw);
        }
        return this;
    }

    /** The searched box in words, for a failure that has to say which of the three worlds it is in. */
    public String describeYard() {
        if (yard == null) {
            return "ABSENT (the craft resolved no chunk claim)";
        }
        if (yard.length < 4) {
            return "malformed " + java.util.Arrays.toString(yard);
        }
        return "x " + yard[0] + ".." + yard[2] + ", z " + yard[1] + ".." + yard[3];
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        if (!found) {
            return "no pilot seat; yard " + describeYard();
        }
        return "seat sub=" + seatX + "," + seatY + "," + seatZ
                + (hasAfc ? " afc=" + afcX + "," + afcY + "," + afcZ : " (no linked computer)")
                + " world=" + shipWorldX + "," + shipWorldY + "," + shipWorldZ;
    }
}
