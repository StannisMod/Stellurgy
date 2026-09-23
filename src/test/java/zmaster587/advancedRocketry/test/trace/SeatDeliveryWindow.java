package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Locale;

import net.minecraft.world.World;

/**
 * Why a pilot's input did or did not reach the ship, gate by gate, on the SERVER — over a window a
 * scenario opens (through the probe's {@code invoke-static}) and reads as the
 * {@code seat_delivery_window} record.
 *
 * <h2>What it answers</h2>
 *
 * <p>The pilot-input chain fails SILENTLY on the server: an arrived packet is dropped without a reply
 * when the seat's own guard or the flight-computer resolve fails. "The ship ignores the pilot" is
 * one symptom with several causes, and these counts name which gate ate the input: packets that
 * arrived, packets handed to the flight computer, the last packet's gate verdict — composed from the
 * facts production itself answered ({@code MixinTilePilotSeatDiag}) — command packets, and what the
 * shared rider-to-seat resolver last saw.</p>
 *
 * <h2>Whose it is</h2>
 *
 * <p>An instance per window, created by the scenario and registered with the SERVER's
 * {@link SideTrace}. This replaces {@code SeatDiag}: nine {@code public static volatile} fields in the
 * main jar's probe package, cumulative for the JVM's life, zeroed by a reset verb the shared base had
 * to remember to call, and read by twenty-one failure messages that could not say which scenario
 * their numbers belonged to.</p>
 *
 * <p>Server thread only. Test source set: absent from a released jar.</p>
 */
public final class SeatDeliveryWindow implements TraceWindow {

    private long received;
    private long delivered;
    private long commandsReceived;
    private String lastVerdict = "";
    private long riderResolveCount;
    private String lastRiderResolve = "";

    private SeatDeliveryWindow() {}

    /** Start a window on the server; answers its handle. */
    public static int open() {
        return SideTrace.here().open(new SeatDeliveryWindow());
    }

    /** Write the counts as they stand, without ending the window. */
    public static int peek(int handle) {
        return SideTrace.here().window(handle, SeatDeliveryWindow.class).record();
    }

    /** Write the counts and end the window. */
    public static int close(int handle) {
        return SideTrace.here().close(handle, SeatDeliveryWindow.class).record();
    }

    private int record() {
        TestTrace.recordServer("seat_delivery_window", String.format(Locale.ROOT,
                "\"received\":%d,\"delivered\":%d,\"commandsReceived\":%d,\"lastVerdict\":\"%s\""
                        + ",\"riderResolveCount\":%d,\"lastRiderResolve\":\"%s\"",
                received, delivered, commandsReceived, TestTrace.json(lastVerdict),
                riderResolveCount, TestTrace.json(lastRiderResolve)));
        return (int) received;
    }

    private static List<SeatDeliveryWindow> windows(World world) {
        return SideTrace.of(world).windows(SeatDeliveryWindow.class);
    }

    /** A pilot-input packet arrived at a seat in {@code world}. */
    public static void pilotInputArrived(World world) {
        List<SeatDeliveryWindow> open = windows(world);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).received++;
        }
    }

    /** An edge-triggered command packet (assist, auto-takeoff, jump) arrived. */
    public static void commandArrived(World world) {
        List<SeatDeliveryWindow> open = windows(world);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).commandsReceived++;
        }
    }

    /** The input reached the flight computer. */
    public static void pilotInputDelivered(World world) {
        List<SeatDeliveryWindow> open = windows(world);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).delivered++;
        }
    }

    /** The packet is done; {@code verdict} is what its gates answered. */
    public static void pilotInputHandled(World world, String verdict) {
        List<SeatDeliveryWindow> open = windows(world);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).lastVerdict = verdict;
        }
    }

    /** One resolver call on this side, and what it saw. */
    public static void riderResolved(World world, String description) {
        List<SeatDeliveryWindow> open = windows(world);
        for (int i = 0; i < open.size(); i++) {
            SeatDeliveryWindow w = open.get(i);
            w.riderResolveCount++;
            w.lastRiderResolve = description;
        }
    }
}
