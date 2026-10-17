package dev.stannismod.stellurgy.api.propulsion;

/**
 * Turns the kilograms an engine ejected into whole millibuckets per tank (C10 STAT-33): each tank
 * owes its share of the mass divided by its mass per millibucket, plus what it still owed from
 * before; it gives the whole millibuckets of that and keeps the fraction for next time. Nothing is
 * ever rounded up, so the millibuckets taken over a burn trail the mass ejected by less than one
 * millibucket per tank.
 */
public final class PropellantDrain {

    private PropellantDrain() {
    }

    /** What one draw takes from each tank, and what each still owes. */
    public static final class Draw {
        private final int[] millibuckets;
        private final double[] owed;

        Draw(int[] millibuckets, double[] owed) {
            this.millibuckets = millibuckets;
            this.owed = owed;
        }

        /** Whole millibuckets to take from tank {@code i}. */
        public int millibuckets(int i) {
            return millibuckets[i];
        }

        /** The fraction of a millibucket tank {@code i} still owes, in [0, 1). */
        public double owed(int i) {
            return owed[i];
        }
    }

    /**
     * @param ejectedKg        the mass the engine threw out this draw, kg
     * @param massShares       each tank's share of that mass; they sum to one
     * @param kgPerMillibucket each tank's fluid mass per millibucket
     * @param owedBefore       each tank's fraction of a millibucket carried from the last draw
     */
    public static Draw draw(double ejectedKg, double[] massShares, double[] kgPerMillibucket, double[] owedBefore) {
        int n = massShares.length;
        if (kgPerMillibucket.length != n || owedBefore.length != n) {
            throw new IllegalArgumentException("one share, one mass per millibucket and one carry per tank");
        }
        int[] whole = new int[n];
        double[] owed = new double[n];
        for (int i = 0; i < n; i++) {
            if (!(kgPerMillibucket[i] > 0)) {
                throw new IllegalArgumentException("tank " + i + " has no mass per millibucket: " + kgPerMillibucket[i]);
            }
            double total = owedBefore[i] + Math.max(0, ejectedKg) * massShares[i] / kgPerMillibucket[i];
            double floor = Math.floor(total);
            whole[i] = (int) Math.min(Integer.MAX_VALUE, floor);
            owed[i] = total - floor;
        }
        return new Draw(whole, owed);
    }

    /** The mass shares of a pair burnt at {@code mixtureRatio} oxidizer over fuel, by mass: {fuel, oxidizer}. */
    public static double[] pairShares(double mixtureRatio) {
        return new double[]{1 / (1 + mixtureRatio), mixtureRatio / (1 + mixtureRatio)};
    }
}
