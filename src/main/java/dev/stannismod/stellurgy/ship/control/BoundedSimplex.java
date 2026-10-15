package dev.stannismod.stellurgy.ship.control;

/**
 * A dense primal simplex over box-bounded variables: maximise {@code c . x} subject to
 * {@code A x = 0} and {@code 0 <= x_j <= upper_j}.
 *
 * <p>Two properties the allocation relies on, and the reason this is written out rather than
 * borrowed:</p>
 * <ul>
 *   <li><b>The origin is always feasible</b>, because every right-hand side is zero and every lower
 *       bound is zero. So there is no phase one: the start basis is one artificial column per row,
 *       each fixed to {@code [0, 0]}, which is feasible at once and can never carry weight.</li>
 *   <li><b>Deterministic</b>. Bland's rule — the lowest-index improving column enters, the
 *       lowest-index blocking row leaves — both prevents cycling on the degenerate vertices this
 *       problem is full of and makes the answer a function of the column ORDER alone. The caller
 *       sorts its columns by a stable identity, so the same hull gives the same recipe on every
 *       run, on either side of the connection.</li>
 * </ul>
 */
final class BoundedSimplex {

    private BoundedSimplex() {}

    /** The outcome: the optimum, or the reason there is none. */
    static final class Result {
        final double[] x;
        final boolean converged;

        private Result(double[] x, boolean converged) {
            this.x = x;
            this.converged = converged;
        }
    }

    /**
     * @param a      the equality rows, {@code m x n}, already scaled so entries are of order one
     * @param upper  the upper bound of each column; {@link Double#POSITIVE_INFINITY} for none
     * @param c      the objective
     * @param pivotTolerance below this magnitude an entry is treated as zero
     * @param maxIterations  a bound that Bland's rule makes unreachable; hitting it means the
     *                       arithmetic has broken down, and the result says so
     */
    static Result maximise(double[][] a, double[] upper, double[] c, double pivotTolerance,
                           int maxIterations) {
        int m = a.length;
        int n = c.length;
        int total = n + m; // structural columns, then one artificial per row
        double[][] t = new double[m][total];
        for (int i = 0; i < m; i++) {
            System.arraycopy(a[i], 0, t[i], 0, n);
            t[i][n + i] = 1.0D;
        }
        double[] ub = new double[total];
        System.arraycopy(upper, 0, ub, 0, n);
        // artificial columns stay at zero: upper bound zero
        double[] cost = new double[total];
        System.arraycopy(c, 0, cost, 0, n);

        int[] basis = new int[m];
        boolean[] basic = new boolean[total];
        for (int i = 0; i < m; i++) {
            basis[i] = n + i;
            basic[n + i] = true;
        }
        boolean[] atUpper = new boolean[total];
        double[] xB = new double[m]; // all zero: the origin

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            // reduced costs d_j = c_j - c_B . column_j
            int entering = -1;
            boolean increase = true;
            for (int j = 0; j < total; j++) {
                if (basic[j] || ub[j] <= 0.0D) {
                    continue; // basic, or fixed at zero
                }
                double d = cost[j];
                for (int i = 0; i < m; i++) {
                    d -= cost[basis[i]] * t[i][j];
                }
                if (!atUpper[j] && d > pivotTolerance) {
                    entering = j;
                    increase = true;
                    break;
                }
                if (atUpper[j] && d < -pivotTolerance) {
                    entering = j;
                    increase = false;
                    break;
                }
            }
            if (entering < 0) {
                return new Result(solution(n, basis, xB, atUpper, ub), true);
            }

            // ratio test: move the entering column by theta in the improving direction
            double sign = increase ? 1.0D : -1.0D;
            double theta = ub[entering]; // a bound flip, if nothing else blocks first
            int leavingRow = -1;
            boolean leavingToUpper = false;
            for (int i = 0; i < m; i++) {
                double rate = sign * t[i][entering]; // basic value falls by rate * theta
                double limit;
                boolean toUpper;
                if (rate > pivotTolerance) {
                    limit = xB[i] / rate;
                    toUpper = false;
                } else if (rate < -pivotTolerance) {
                    double room = ub[basis[i]] - xB[i];
                    if (Double.isInfinite(room)) {
                        continue;
                    }
                    limit = room / -rate;
                    toUpper = true;
                } else {
                    continue;
                }
                if (limit < 0.0D) {
                    limit = 0.0D; // rounding on a degenerate vertex
                }
                if (limit < theta - pivotTolerance
                        || (leavingRow >= 0 && Math.abs(limit - theta) <= pivotTolerance
                            && basis[i] < basis[leavingRow])) {
                    theta = limit;
                    leavingRow = i;
                    leavingToUpper = toUpper;
                }
            }
            if (Double.isInfinite(theta)) {
                // Unbounded. Every column here but the objective's own is boxed, so this means the
                // problem was built wrong; there is no answer to give.
                return new Result(new double[n], false);
            }

            for (int i = 0; i < m; i++) {
                xB[i] -= sign * t[i][entering] * theta;
            }
            if (leavingRow < 0) {
                atUpper[entering] = !atUpper[entering]; // the entering column met its own bound
                continue;
            }

            int leaving = basis[leavingRow];
            double enteringValue = (increase ? 0.0D : ub[entering]) + sign * theta;
            double pivot = t[leavingRow][entering];
            double[] pivotRow = t[leavingRow];
            for (int j = 0; j < total; j++) {
                pivotRow[j] /= pivot;
            }
            for (int i = 0; i < m; i++) {
                if (i == leavingRow) {
                    continue;
                }
                double factor = t[i][entering];
                if (factor != 0.0D) {
                    double[] row = t[i];
                    for (int j = 0; j < total; j++) {
                        row[j] -= factor * pivotRow[j];
                    }
                }
            }
            basic[leaving] = false;
            atUpper[leaving] = leavingToUpper;
            basic[entering] = true;
            atUpper[entering] = false;
            basis[leavingRow] = entering;
            xB[leavingRow] = enteringValue;
        }
        return new Result(new double[n], false);
    }

    private static double[] solution(int n, int[] basis, double[] xB, boolean[] atUpper,
                                     double[] ub) {
        double[] x = new double[n];
        for (int j = 0; j < n; j++) {
            if (atUpper[j]) {
                x[j] = ub[j];
            }
        }
        for (int i = 0; i < basis.length; i++) {
            if (basis[i] < n) {
                x[basis[i]] = xB[i];
            }
        }
        return x;
    }
}
