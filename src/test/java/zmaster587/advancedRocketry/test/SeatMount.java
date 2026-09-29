package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest vs seat-mount <dim> id <shipId>} — the dummy bound to a craft's
 * pilot seat, and where that seat is.
 *
 * <p>Measured 2026-09-17: eight classes read this reply, spelling seven of its field names, and the
 * same {@code contains("\"seatFound\":true")} appears in twelve of them.</p>
 *
 * <h2>{@code seatFound:false} here is not {@code find-seat}'s</h2>
 *
 * <p>This verb answers about the LOADED seats of one world: a false means no loaded pilot seat
 * belongs to the craft that was named — which the reply says out loud by carrying
 * {@link #seatsLoaded}, the number of seats it looked through. Zero seats loaded and five seats
 * belonging to other hulls are the same word and a different arrangement, so the count travels in
 * every failure this reader raises. {@link PilotSeat} answers the sibling question — where a craft's
 * seat is in its own subspace — and is not interchangeable with it.</p>
 *
 * <h2>The verb MOUNTS, so its reply carries whether it had to</h2>
 *
 * <p>{@link #reused} says the seat already had a bound dummy and none was spawned. A caller
 * asserting one seat keeps one occupant across a relog reads that field; a caller that merely needs
 * a body in the seat does not care, and the two must not be told apart by the id alone — an entity
 * id is reassigned when its chunk reloads.</p>
 */
public final class SeatMount {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether a pilot seat belonging to the named craft was found among the world's loaded seats. */
    public final boolean seatFound;
    /** How many pilot seats the world held when it looked — the discriminator in the class note. */
    public final int seatsLoaded;
    /** Whether the seat already carried a bound dummy, so none was spawned. */
    public final boolean reused;

    private final Reply reply;
    private final String raw;

    private SeatMount(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.seatFound = reply.bool("seatFound");
        this.seatsLoaded = reply.integer("seatsLoaded");
        this.reused = reply.bool("reused");
    }

    /**
     * Read one {@code seat-mount} reply, or refuse.
     *
     * <p>Refuses the verb's two error shapes — a world that is not loaded, and a spawn the world
     * declined — because both carry no {@code seatFound} at all and would otherwise read as "this
     * craft has no seat", which is a claim about the CRAFT.</p>
     */
    public static SeatMount of(String seatMountReply) {
        String text = String.valueOf(seatMountReply);
        Reply reply = Reply.of("artest vs seat-mount", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest vs seat-mount` could not even look for a"
                    + " seat (" + reply.text("error") + "), so nothing here says whether the craft"
                    + " has one: " + text);
        }
        if (!reply.has("seatFound")) {
            throw new AssertionError("this is not an `artest vs seat-mount` answer: it carries no"
                    + " `seatFound`, so a false read off it would be about the reply and not about"
                    + " the craft: " + text);
        }
        return new SeatMount(reply, text);
    }

    /**
     * Bind a dummy to the seat of the craft NAMED by {@code shipId}.
     *
     * <p>The id-keyed form, which is the only one that names a craft: the bare form takes the first
     * loaded seat in the world, and on a world holding several hulls that is a coin toss reported as
     * a fact — it once mounted a pilot onto a ship 16 000 000 blocks from the one under test.</p>
     */
    public static SeatMount onShip(Probe probe, int dim, String shipId) throws Exception {
        return of(probe.exec("artest vs seat-mount " + dim + " id " + shipId));
    }

    /**
     * Bind a dummy to whatever pilot seat this world loaded FIRST.
     *
     * <p>Named for what it does. Legitimate only where the world provably holds one seat, and
     * {@link #seatsLoaded} is what says so — which is why it is in every failure message here.</p>
     */
    public static SeatMount firstLoadedSeat(Probe probe, int dim) throws Exception {
        return of(probe.exec("artest vs seat-mount " + dim));
    }

    /** This reading, refusing when no seat was found — with the seat count in the message. */
    public SeatMount requireSeatFound(String what) {
        if (!seatFound) {
            ArrangementFailure.arrangementFailed(what + " — no pilot seat of that craft is loaded."
                    + " The world held " + seatsLoaded + " loaded seat(s) when it looked, so a zero"
                    + " there is a world that has not built the craft yet and a positive number is"
                    + " seats belonging to other hulls: " + raw);
        }
        return this;
    }

    /**
     * The dummy bound to the seat, refusing when no seat was found.
     *
     * <p>A {@code require} because this id TRAVELS — into {@code player mount-entity}, into a
     * deck-capture query — and an absent one would go out as the four characters {@code null}.</p>
     */
    public int requireDummyId() {
        requireSeatFound("the dummy this test is about must be bound to a seat");
        return reply.integer("dummyId");
    }

    /** The seat's SUBSPACE block position, refusing when no seat was found. */
    public int seatX() {
        return seat("seatX");
    }

    public int seatY() {
        return seat("seatY");
    }

    public int seatZ() {
        return seat("seatZ");
    }

    private int seat(String field) {
        requireSeatFound("the seat whose position this asks for must have been found");
        return reply.integer(field);
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "seat-mount found=" + seatFound + " seatsLoaded=" + seatsLoaded
                + " reused=" + reused;
    }
}
