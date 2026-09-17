package zmaster587.advancedRocketry.test;

/**
 * The tanks of an {@code artest fluid stored} reply, read by tank.
 *
 * <p><b>Why this exists rather than a field read on {@link Reply}.</b> The reply's fluid fields do
 * not live on the reply: it answers
 * {@code {"tileClass":…,"hasFluid":true,"tanks":[{"capacity":64000,"fluid":"oxygen","amount":7500}]}},
 * so {@code capacity}, {@code fluid} and {@code amount} are a TANK's fields and a tile may hold
 * several. A regex over the whole rendering could not see that distinction and did not need to —
 * it matched the first occurrence anywhere — which is precisely why four readers asked the reply
 * for a field only a tank has, and each answered ABSENCE on a tile that was plainly full.</p>
 *
 * <p>An EMPTY tank reports {@code "fluid":null} and carries no {@code amount}; that is a reading
 * and not an error, so {@link #fluid} answers {@code null} and {@link #amount} answers 0 there —
 * while a tank INDEX that does not exist is refused, because nothing can be said about it.</p>
 */
public final class FluidStored {

    private static final String TANKS = "tanks";
    private static final String FLUID = "fluid";
    private static final String AMOUNT = "amount";
    private static final String CAPACITY = "capacity";

    private final String raw;
    private final String[] tanks;

    private FluidStored(String raw, String[] tanks) {
        this.raw = raw;
        this.tanks = tanks;
    }

    /** Parse a {@code fluid stored} reply, or refuse naming it. */
    public static FluidStored of(String raw) {
        return new FluidStored(String.valueOf(raw),
                Reply.of("artest fluid stored", raw).objectArray(TANKS));
    }

    /** How many tanks the tile reported. Zero is a reading: the tile holds no fluid handler. */
    public int count() {
        return tanks.length;
    }

    /** The fluid in tank {@code index}, or {@code null} when that tank is empty. */
    public String fluid(int index) {
        return tank(index).textOr(FLUID, null);
    }

    /** How much is in tank {@code index}; 0 when it is empty. */
    public int amount(int index) {
        return tank(index).integerOr(AMOUNT, 0);
    }

    /** Tank {@code index}'s capacity. */
    public int capacity(int index) {
        Reply one = tank(index);
        if (!one.has(CAPACITY)) {
            throw new AssertionError("tank " + index + " reports no capacity: " + raw);
        }
        return one.integer(CAPACITY);
    }

    /**
     * How much of {@code fluid} the tile holds, summed across its tanks — 0 when it holds none.
     *
     * <p>Summed rather than "the first tank carrying it": a multi-tank tile splits one fluid across
     * hatches, and answering with one tank's share reads as a partial drain.</p>
     */
    public int amountOf(String fluid) {
        int total = 0;
        for (int i = 0; i < tanks.length; i++) {
            if (fluid.equals(fluid(i))) {
                total += amount(i);
            }
        }
        return total;
    }

    private Reply tank(int index) {
        if (index < 0 || index >= tanks.length) {
            throw new AssertionError("artest fluid stored reported " + tanks.length
                    + " tank(s), so there is no tank " + index + ": " + raw);
        }
        return Reply.of("one tank of artest fluid stored", tanks[index]);
    }

    @Override
    public String toString() {
        return raw;
    }
}
