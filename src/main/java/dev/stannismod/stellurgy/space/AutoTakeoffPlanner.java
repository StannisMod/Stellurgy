package dev.stannismod.stellurgy.space;

import java.util.function.Predicate;

/**
 * The take-off CORRIDOR: whether the column a craft climbs to orbit through is clear. A climb to orbit is
 * flown straight up, so its corridor is the column of blocks above the craft. Kept free of any world type
 * so the test — every block of the column, none skipped — is checked without a server.
 *
 * <p>A blocked corridor is a NORMAL outcome, reported to the pilot as a refusal of the climb, never an
 * error.</p>
 */
public final class AutoTakeoffPlanner {

    private AutoTakeoffPlanner() { }

    /**
     * Whether no block of the column {@code (x, z)} from height {@code fromY} up to {@code toY} answers
     * solid to {@code solidAt}, which is asked about each block position {@code {x, y, z}} once. Every
     * block of the column is asked, so a single thin slab cannot be stepped over. A column whose top is
     * below its bottom is vacuously clear: there is nothing left to climb through.
     */
    public static boolean corridorClear(double x, double z, double fromY, double toY, Predicate<long[]> solidAt) {
        long bx = (long) Math.floor(x);
        long bz = (long) Math.floor(z);
        long top = (long) Math.floor(toY);
        for (long y = (long) Math.floor(fromY); y <= top; y++) {
            if (solidAt.test(new long[]{bx, y, bz})) {
                return false;
            }
        }
        return true;
    }
}
