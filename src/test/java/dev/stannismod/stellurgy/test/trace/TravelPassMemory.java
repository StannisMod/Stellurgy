package dev.stannismod.stellurgy.test.trace;

/**
 * What the deck frame's instruments relay between two seams of ONE side's pass — one per side, kept
 * in that side's {@link SideTrace}.
 *
 * <p>Production computes the facts a record needs in different places a few frames apart, and some
 * of those places have no body at all (the pose pass's return). So an instrument writes a fact at the
 * first seam and reads it at the second. Both seams run on the side's own thread, and the client and
 * the server run the same code, so the relay must be per SIDE.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class TravelPassMemory {

    /** Held ticks THIS side has produced, counted where the deck frame writes its per-tick record —
     *  the {@code resolvedTick} of {@code ship_frame_tick}. */
    public long resolvedTicks;

    /** The largest re-seat step of the pose pass currently running on this side. Released by
     *  {@link #takeReseatPassMax}, which every pass calls exactly once. */
    private double reseatPassMax;

    /** The side's memory. */
    public static TravelPassMemory of(SideTrace side) {
        return side.memory(TravelPassMemory.class, TravelPassMemory::new);
    }

    /** Note one re-seat: {@code seat} is where the body is being put, {@code from*} where it was.
     *  Zero on a still ship, one tick of ship motion at the body's radius on a moving one. */
    public void noteReseat(double seatX, double seatY, double seatZ,
                           double fromX, double fromY, double fromZ) {
        double dx = seatX - fromX;
        double dy = seatY - fromY;
        double dz = seatZ - fromZ;
        double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (step > reseatPassMax) {
            reseatPassMax = step;
        }
    }

    /** The largest step since the last call, and reset — read once per pass, by its recorder. */
    public double takeReseatPassMax() {
        double max = reseatPassMax;
        reseatPassMax = 0.0;
        return max;
    }

    /** Bodies the deck frame's pose pass has re-imaged since {@link #takeDeckFrameReseats}. */
    private int deckFrameReseats;

    /** Count one body the deck frame's pass re-imaged. */
    public void countDeckFrameReseat() {
        deckFrameReseats++;
    }

    /** The deck frame's count since the last call, and reset — read once per pass. */
    public int takeDeckFrameReseats() {
        int n = deckFrameReseats;
        deckFrameReseats = 0;
        return n;
    }
}
