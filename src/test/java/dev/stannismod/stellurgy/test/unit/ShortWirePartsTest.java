package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.util.ShortWireParts;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * The contract: a value a machine's screen syncs arrives WHOLE on the client, although vanilla's window
 * property carries 16 bits ({@code SPacketWindowProperty} writes it with {@code writeShort}, so the
 * receiver gets a sign-extended short back). Its consumer is the Rocket Assembler's screen, whose thrust
 * in newtons and mass in kilograms are far wider than a short — the screen must show what the server
 * computed.
 *
 * <p>Checked as a law over the regions that differ for a 16-bit wire: zero, values that fit, values
 * whose part has its sign bit set (sign extension on receipt), the full long range at both ends, and
 * random values — each delivered in every arrival order of its parts, over a held value that is not
 * empty (a previous slot value).</p>
 *
 * red-witnessed: with {@code ShortWireParts#fold} at {@code ((long) received & 0xFFFFL)} replaced by {@code (long) received}, fails: "a value must arrive whole through a 16-bit wire" (2026-10-08).
 */
public class ShortWirePartsTest {

    @Test
    public void aValueArrivesWholeThroughASixteenBitWireInAnyOrder() {
        Random random = new Random(762L);
        long[] regions = {0L, 1L, 32767L, 32768L, 65535L, 490500L, -1L, Long.MIN_VALUE, Long.MAX_VALUE,
                0x8000_8000_8000_8000L};
        for (long value : regions) {
            assertArrivesWhole(value, random.nextLong());
        }
        for (int i = 0; i < 2000; i++) {
            assertArrivesWhole(random.nextLong(), random.nextLong());
        }
    }

    private static void assertArrivesWhole(long value, long heldBefore) {
        int[][] orders = {{0, 1, 2, 3}, {3, 2, 1, 0}, {1, 3, 0, 2}, {2, 0, 3, 1}};
        for (int[] order : orders) {
            long held = heldBefore;
            for (int part : order) {
                short onTheWire = (short) ShortWireParts.part(value, part);
                held = ShortWireParts.fold(held, part, onTheWire);
            }
            assertEquals("a value must arrive whole through a 16-bit wire (sent " + value + ", parts in order "
                    + java.util.Arrays.toString(order) + ")", value, held);
        }
    }
}
