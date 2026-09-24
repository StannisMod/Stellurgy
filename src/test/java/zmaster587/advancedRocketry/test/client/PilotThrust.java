package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.server.TestClient;

import org.lwjgl.input.Keyboard;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;

/**
 * A seated pilot's vertical-up key, driven as a DOSE of thrust: held until the flight computer has
 * it, kept down for a counted stretch of the hull's own world, then let go until the computer has
 * the release too.
 *
 * <h2>Why a dose and not a wait for an altitude</h2>
 *
 * <p>The computer LATCHES what it is handed ({@code setPilotInput} keeps the last command until
 * another replaces it), and the client re-asserts a held non-idle input every
 * {@code PilotInputCadence.REPEAT_TICKS} besides, so a tile replaced mid-dose loses at most that
 * stretch. So from the {@code pilot_input_set} the hold waits for until the release's own record,
 * the craft is under thrust on every tick of its world. The COUNTED part is the same on any box; the
 * part after it is not — the key stays in force while the release travels to the server, and that
 * trip is the machine's. The error runs one way: a slow box gives MORE thrust, which only ever makes
 * a "did it climb at all" bar easier, never lets a craft with no thrust pass one. What the thrust
 * bought is the CALLER's reading, taken after the release has arrived, so it describes the craft the
 * scenario goes on to use.</p>
 *
 * <p>It replaced a poll that held the key until the craft had climbed, up to 400 client ticks. That
 * ceiling was not only patience: the key was held while it ran, so it also decided how far a craft
 * that did not stop could fly — and a craft under a held throttle for 200 ticks leaves the loaded
 * region (measured in {@code VSGroundFlightGroupE2ETest}, which then read {@code managed:false}).</p>
 *
 * <p>The two links are what make a red legible: a key that never arrived fails naming the missing
 * {@code pilot_input_set}, never as a craft that "did not climb".</p>
 *
 * <h2>For a pilot whose only intent is this key</h2>
 *
 * <p>The release is recognised by the computer being handed something that is not {@code set}: with
 * the flight cursor deflected the input stays {@code set} after the key comes up, and the release
 * wait fails saying so.</p>
 *
 * <p>Static methods over what the caller passes in, and no state of its own: two test bases with no
 * common ancestor below the harness drive the same key, and one account of it is the point.</p>
 */
final class PilotThrust {

    /**
     * Ticks of the hull's world clock a held vertical key is given, counted from the moment the key
     * REACHED the flight computer — a dose of thrust, not a budget for an altitude.
     *
     * <p>Measured on the 2026-09-23 gate: the four hover scenarios reached +3.0 blocks sixteen
     * CLIENT ticks after the key went DOWN (eight polls of two), and that stretch includes the
     * packet's trip to the server. Twenty ticks from the arrival is therefore more thrust than any
     * of them needed for +3, under the same ramp — the count is taken on the world the craft is ticked
     * in, so a busy client cannot shorten it (it can lengthen the tail after it; see the class note).
     * The altitude it buys is READ after the cut and asserted by the caller, so a dose that turns out short says so rather than
     * passing.</p>
     */
    static final int DOSE_TICKS = 20;

    /** Ticks a held or released key is given to REACH the flight computer — a link budget. */
    private static final int LINK_TICKS = 200;

    private PilotThrust() { }

    /**
     * Hold the key, wait for it to reach the flight computer, and give the hull {@code thrustTicks}
     * of its world clock under it. The key is left DOWN; on a failure it is let go before the
     * failure propagates.
     *
     * @param serverLog   the SERVER's event log — the computer records what it is handed there
     * @param server      the server harness connection, whose world clock the dose is counted on
     * @param dim         the world the craft is ticked in
     * @param thrustTicks ticks of thrust from the key's arrival
     * @param what        the scenario's own sentence for why the key must arrive, used on a failure
     */
    static void hold(ClientBot bot, Events serverLog, TestClient server, int dim, int thrustTicks,
                     String what) throws Exception {
        long pressMark = serverLog.markInstrumented();
        bot.holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            serverLog.awaitField(pressMark, "pilot_input_set", "input", "set", what, LINK_TICKS);
            // STIMULUS: thrustTicks of the hull's world clock under the held key, from its arrival.
            // The dose, not patience — see DOSE_TICKS; the caller reads what it did.
            GameTicks.advanceWorld(server, dim, thrustTicks);
        } catch (Exception | AssertionError failed) {
            bot.releaseKey(Keyboard.KEY_R);
            throw failed;
        }
    }

    /**
     * Let go of the key and wait for the release to REACH the flight computer — so that whatever
     * the caller reads next is the craft with its thrust cut.
     *
     * <p>Over the LATEST record, not the first: a held input is re-asserted every
     * {@code PilotInputCadence.REPEAT_TICKS}, so a {@code set} sent just before the key came up can
     * land after this mark and would satisfy "any record". Letting go of the only held key turns the
     * pilot's intent idle, and an idle input is sent once, on the change, and never repeated — so a
     * window whose latest record is not {@code set} is one the release has arrived in.</p>
     */
    static void release(ClientBot bot, Events serverLog, String what) throws Exception {
        long releaseMark = serverLog.mark();
        bot.releaseKey(Keyboard.KEY_R);
        serverLog.awaitMatching(releaseMark, "pilot_input_set",
                seen -> {
                    String last = Events.lastRecord(seen);
                    return last != null && !"set".equals(Events.text(last, "input"));
                },
                "whose latest input is no longer \"set\"",
                "the released climb key must reach the flight computer before anything is read as"
                        + " the craft with its thrust cut — " + what,
                LINK_TICKS);
    }

    /** {@link #hold} then {@link #release}: a dose of thrust, cut, and the cut on the record. */
    static void climb(ClientBot bot, Events serverLog, TestClient server, int dim, int thrustTicks,
                      String what) throws Exception {
        hold(bot, serverLog, server, dim, thrustTicks, what);
        release(bot, serverLog, what);
    }
}
