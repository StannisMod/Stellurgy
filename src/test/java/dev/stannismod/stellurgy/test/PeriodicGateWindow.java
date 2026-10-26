package dev.stannismod.stellurgy.test;

import java.util.ArrayList;
import java.util.List;

/**
 * The openings of ONE tile's periodic gate in a window of the server's event log — the {@code periodic_gate_opened}
 * records of that tile kind at that place, in order, each with the world's total time and time of day at the
 * opening.
 *
 * <p>Read off a reply of {@code Events#since(mark, "periodic_gate_opened")}; the reply's {@code instruments} say
 * whether the gate's tile was looked at at all ({@code periodic_gate_events}, noted on every update whether the
 * gate opened or not), so an empty window can be told from a blind one by {@link Events#assertInstrumentRan}.</p>
 */
public final class PeriodicGateWindow {

    /** Each opening as {@code {totalTime, timeOfDay}}, in the order the log holds them. */
    public final List<long[]> openings;

    private PeriodicGateWindow(List<long[]> openings) {
        this.openings = openings;
    }

    /** The openings in {@code reply} of the gate of tile {@code kind} at {@code (x, y, z)} in {@code dim}. */
    public static PeriodicGateWindow of(String reply, String kind, int dim, int x, int y, int z) {
        List<long[]> found = new ArrayList<>();
        for (String record : Events.records(reply)) {
            if (kind.equals(Events.text(record, "tile")) && Events.number(record, "dim") == dim
                    && Events.number(record, "x") == x && Events.number(record, "y") == y
                    && Events.number(record, "z") == z) {
                found.add(new long[]{(long) Events.number(record, "totalTime"), (long) Events.number(record, "timeOfDay")});
            }
        }
        return new PeriodicGateWindow(found);
    }

    /** Whether every opening saw the same time of day — the world's day stood still across the window. */
    public boolean timeOfDayStoodStill() {
        for (long[] opening : openings) {
            if (opening[1] != openings.get(0)[1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The total time between consecutive openings when it is one value for every pair, or {@code -1} when the
     * openings are fewer than two or unevenly spaced.
     */
    public long evenSpacing() {
        if (openings.size() < 2) {
            return -1L;
        }
        long spacing = openings.get(1)[0] - openings.get(0)[0];
        for (int i = 2; i < openings.size(); i++) {
            if (openings.get(i)[0] - openings.get(i - 1)[0] != spacing) {
                return -1L;
            }
        }
        return spacing;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("[");
        for (long[] opening : openings) {
            out.append(" total ").append(opening[0]).append("/day ").append(opening[1]);
        }
        return out.append(" ]").toString();
    }
}
