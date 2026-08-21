package zmaster587.advancedRocketry.command.test;

/**
 * Why the pilot's input did or did not reach the ship, gate by gate.
 *
 * <h2>What this answers</h2>
 *
 * <p>The pilot-input chain fails SILENTLY on both of its gates: a piloting client simply does not
 * send when it cannot resolve a linked seat for the mount it rides, and the server drops an arrived
 * packet without a reply when its own guard or the flight-computer resolve fails. "The ship ignores
 * the pilot" is therefore one symptom with several causes, and only these readings name which gate
 * ate the input.</p>
 *
 * <h2>Three facts, not one string</h2>
 *
 * <p>Production used to compose a verdict LINE — {@code seat=… pilotGuard=… afcResolved=…} — inside
 * the packet handler and publish it to a static. The verdict is now three independent facts, each
 * read from production's OWN call at the moment it happens: the guard from what {@code isPilotOf}
 * returned, the resolve from what {@code getFlightComputer} returned while a packet was in scope,
 * and delivery from whether the input actually reached the computer. That is strictly better than
 * the string: a composed line can only be believed as a whole, while three facts can disagree, and
 * a disagreement is itself a finding.</p>
 *
 * <p>Written by test-only mixins; a released jar carries none of it.</p>
 */
public final class SeatDiag {

    /** Pilot-input packets that reached a seat in this JVM (any seat). */
    public static volatile int pilotInputPacketsReceived;
    /** Pilot-input packets that passed both server gates and were handed to the flight computer. */
    public static volatile int pilotInputPacketsDelivered;
    /** The last received pilot-input packet's gate outcome, composed from the three facts below. */
    public static volatile String lastPilotInputVerdict = "";
    /** Pilot COMMAND packets (Flight-Assist, auto-takeoff, jump) that reached a seat in this JVM.
     *  The mirror image of the counters above: they attribute a command that was EATEN, this one
     *  attributes a command that ARRIVED — which for a craft that is not a ship it never should. */
    public static volatile int pilotCommandPacketsReceived;

    /** How many times the shared rider→seat resolver ran here (proof it is exercised at all). */
    public static volatile int riderResolveCount;
    /** What the last resolver call saw: the mount's bound seat, the position looked up, what tile
     *  was there, whether that seat was linked, and which side asked. */
    public static volatile String lastRiderResolve = "";

    // ---- The CLIENT half of the same chain: whether this client even tried to send. ----------
    //
    // The client gate refuses SILENTLY — when the ridden mount resolves no linked pilot seat the
    // client simply never sends, which from outside is indistinguishable from "sent but lost". The
    // counters above attribute a packet that ARRIVED; these attribute one that was never posted.

    /** Client ticks on which the tier-2 gate refused: riding, but no linked seat resolved. */
    public static volatile int shipGateClosedTicks;
    /** Client ticks on which the gate held a linked pilot seat (the pilot branch ran). */
    public static volatile int shipGateOpenTicks;
    /** Pilot-input packets this client actually dispatched to a seat. */
    public static volatile int shipInputSendCount;

    /** The client gate's decision for one tick. */
    public static void clientGate(boolean open) {
        if (open) {
            shipGateOpenTicks++;
        } else {
            shipGateClosedTicks++;
        }
    }

    /** One pilot-input packet left this client. */
    public static void clientSent() {
        shipInputSendCount++;
    }

    /** In-flight state for the packet being handled right now. */
    private static boolean inPacket;
    private static String packetSeat = "";
    private static boolean packetGuard;
    private static boolean packetAfcResolved;

    private SeatDiag() { }

    public static void reset() {
        pilotInputPacketsReceived = 0;
        pilotInputPacketsDelivered = 0;
        lastPilotInputVerdict = "";
        pilotCommandPacketsReceived = 0;
        riderResolveCount = 0;
        lastRiderResolve = "";
        shipGateClosedTicks = 0;
        shipGateOpenTicks = 0;
        shipInputSendCount = 0;
        inPacket = false;
    }

    /** A pilot-input packet arrived at {@code seat}. Opens the scope the two gates report into. */
    public static void pilotInputArrived(String seat) {
        pilotInputPacketsReceived++;
        inPacket = true;
        packetSeat = seat;
        packetGuard = false;
        packetAfcResolved = false;
    }

    /** An edge-triggered command packet arrived — counted once for all three kinds. */
    public static void commandArrived() {
        pilotCommandPacketsReceived++;
    }

    /** What production's own pilot guard answered. Ignored outside a packet, where the same
     *  resolver is asked by the HUD and the key context and would only add noise. */
    public static void pilotGuard(boolean pilot) {
        if (inPacket) {
            packetGuard = pilot;
        }
    }

    /** Whether production's own flight-computer resolve produced one, in the same scope. */
    public static void afcResolved(boolean resolved) {
        if (inPacket) {
            packetAfcResolved = resolved;
        }
    }

    /** The input reached the flight computer. */
    public static void pilotInputDelivered() {
        pilotInputPacketsDelivered++;
    }

    /** The packet is done: publish the verdict its gates produced and close the scope. */
    public static void pilotInputHandled() {
        if (inPacket) {
            lastPilotInputVerdict = "seat=" + packetSeat + " pilotGuard=" + packetGuard
                    + " afcResolved=" + packetAfcResolved;
            inPacket = false;
        }
    }

    /** One resolver call and what it saw. */
    public static void riderResolved(String description) {
        riderResolveCount++;
        lastRiderResolve = description;
    }
}
