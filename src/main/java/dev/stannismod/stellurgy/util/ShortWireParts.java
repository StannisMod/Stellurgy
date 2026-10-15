package dev.stannismod.stellurgy.util;

/**
 * A 64-bit value carried over a channel that holds 16 bits: vanilla's window property, which
 * {@code SPacketWindowProperty} writes with {@code writeShort}. The value travels as {@link #PARTS}
 * parts, least significant first; the receiver folds each part it gets into what it holds.
 */
public final class ShortWireParts {

    /** Parts per value: four 16-bit parts make a long. */
    public static final int PARTS = 4;

    private ShortWireParts() {}

    /** Part {@code part} (0 = least significant) of {@code value}, as the sender hands it to the wire. */
    public static int part(long value, int part) {
        return (int) ((value >>> (16 * part)) & 0xFFFFL);
    }

    /**
     * {@code held} with part {@code part} replaced by what arrived. The wire hands back a short, so
     * {@code received} may be sign-extended; only its low 16 bits are the part.
     */
    public static long fold(long held, int part, int received) {
        int shift = 16 * part;
        return (held & ~(0xFFFFL << shift)) | (((long) received & 0xFFFFL) << shift);
    }
}
