package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Locale;

/**
 * The CLIENT half of the pilot-input chain: whether this client even tried to send — over a window a
 * scenario opens and reads as the {@code seat_gate_window} record.
 *
 * <p>The client gate refuses silently: when the ridden mount resolves no linked pilot seat the client
 * simply never sends, which from the server's side is indistinguishable from "sent but lost". So the
 * window counts client ticks on which the gate held a linked seat ({@code open}), ticks on which it
 * refused while riding a seat mount ({@code closed} — a walking tick is not a refusal), and packets
 * actually dispatched ({@code sends}). The record's envelope carries the client tick it was taken on.
 * {@link SeatDeliveryWindow} is the server half.</p>
 *
 * <p>Client thread only. Test source set: absent from a released jar.</p>
 */
public final class SeatGateWindow implements TraceWindow {

    private long openTicks;
    private long closedTicks;
    private long sends;

    private SeatGateWindow() {}

    /** Start a window; answers its handle. */
    public static int open() {
        return SideTrace.client().open(new SeatGateWindow());
    }

    /** Write the counts as they stand, without ending the window. */
    public static int peek(int handle) {
        return SideTrace.client().window(handle, SeatGateWindow.class).record();
    }

    /** Write the counts and end the window. */
    public static int close(int handle) {
        return SideTrace.client().close(handle, SeatGateWindow.class).record();
    }

    private int record() {
        TestTrace.recordHere("seat_gate_window", String.format(Locale.ROOT,
                "\"open\":%d,\"closed\":%d,\"sends\":%d", openTicks, closedTicks, sends));
        return (int) sends;
    }

    /** The client gate's decision for one tick. */
    public static void gate(boolean open) {
        List<SeatGateWindow> windows = SideTrace.client().windows(SeatGateWindow.class);
        for (int i = 0; i < windows.size(); i++) {
            if (open) {
                windows.get(i).openTicks++;
            } else {
                windows.get(i).closedTicks++;
            }
        }
    }

    /** One pilot-input packet left this client. */
    public static void sent() {
        List<SeatGateWindow> windows = SideTrace.client().windows(SeatGateWindow.class);
        for (int i = 0; i < windows.size(); i++) {
            windows.get(i).sends++;
        }
    }
}
