package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import zmaster587.advancedRocketry.test.PlayerShipData;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.ShipFrameCheck;
import zmaster587.advancedRocketry.test.ShipInfo;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The full-path tier-2 flight e2e: a bot flies a real Valkyrien Skies ship with real keys, a real
 * mouse and a real camera, and every assertion reads something the CLIENT itself produced.
 *
 * <p>Each test pins one behaviour a hands-on playtest found broken, so each would have failed before
 * the fix that carries it:</p>
 * <ul>
 *   <li><b>The flight panel shows the ship's real speed.</b> The HUD is rendered from the client's own
 *       snapshot of the craft; a tier-1 rocket has always drawn a three-axis velocity + Flight-Assist
 *       setpoint panel, and a tier-2 ship drew nothing because neither number reached the client.</li>
 *   <li><b>Centring the flight cursor stops the ship turning.</b> A ship is a rigid body carrying
 *       angular momentum; unlike a rocket it does not stop just because the pilot stopped asking it to
 *       turn. Its controller must actively brake the residual spin.</li>
 *   <li><b>The camera turns over with the ship, and the eye stays out of the deck.</b> Vanilla adds the
 *       eye height along the WORLD up, so on an inverted ship the pilot's eye ends up inside the deck
 *       hanging above his seat and he sees nothing at all.</li>
 *   <li><b>A crew member stays on a steeply rolled deck.</b> Vanilla's vertical drag (0.98) and its
 *       horizontal friction (0.91) are not the same number, so a deck-down pull with world X/Z
 *       components is bent steeply toward world +Y: the crew member is flung up a wall.</li>
 * </ul>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipFlightTelemetryE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-flight-telemetry";
    }

    private static final String BUILDER_POS = "builderPos";

    /**
     * Client ticks the brake is given to act before anything is judged.
     *
     * <p>300 is the budget this scenario has always given it — the replaced poll's ceiling was 150
     * iterations two ticks apart. It is kept unchanged on purpose: the instrument is
     * what is being fixed here, and moving the allowance in the same change would confound "the
     * question is now askable" with "the question got easier".</p>
     *
     * <p>A TICK COUNT: it says how much WORLD the brake gets, not how long we are willing to wait
     * for an answer.</p>
     */
    private static final int BRAKE_SETTLE_TICKS = 300;

    /**
     * The hold that decides: how many readings, and how far apart in client ticks.
     *
     * <p>Sized from the oscillation it has to outlast rather than picked. Measured 2026-09-14, the
     * rate swung from 0.123 to 0.393 within 25 poll iterations of two ticks each — one visible swing
     * is on the order of fifty ticks — so a hold of {@code (10 - 1) * 10 = 90} ticks spans well over
     * a full swing and cannot be straddled by a single trough.</p>
     */
    private static final int HOLD_SAMPLES = 10;
    /** @see #HOLD_SAMPLES */
    private static final int HOLD_TICKS_BETWEEN = 10;
    private static final String DUMMY_ID = "dummyId";
    private static final String CRUISE_FWD = "cruiseForward";
    private static final String CRUISE_RIGHT = "cruiseRight";
    private static final String CRUISE_UP = "cruiseUp";
    private static final String LOCAL_X = "localX";
    private static final String LOCAL_Y = "localY";
    private static final String LOCAL_Z = "localZ";
    private static final String ENTITY_ID = "entityId";

    /** The deck-capture recorder's instrument name — what proves an EMPTY release log was listening. */
    private static final String DECK_INSTRUMENT = "deck_capture_events";

    /** THIS scenario's ship, by identity — the address every question below is keyed on. */
    private String scenarioShipId;

    private static final String VARIANT = "with-pilot-seat";
    private static final String KEY_BINDINGS = "zmaster587.advancedRocketry.client.KeyBindings";
    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.test.trace.DeckCameraState";
    /** The TEST-side holder of the client's own last camera setup — production keeps no such field. */
    private static final String DECK_CAMERA_STATE =
            "zmaster587.advancedRocketry.test.trace.DeckCameraState";
    /**
     * The Free Flight HUD as the client last DREW it, or {@code ""} when it has drawn none.
     *
     * <p>The recorder writes only when the line CHANGES, so the latest record is the current text.
     * Replaces a reflective read of a field that is now private — a read the compiler cannot check,
     * which is why it had to be found by scanning the channel rather than by building.</p>
     */
    private String freeFlightHud() throws Exception {
        String rec = Events.lastRecord(clientEvents().since(0, "ff_hud"));
        return rec == null ? "" : Events.text(rec, "text");
    }
    /** The client's own flight-cursor dead-zone: inside it the ship is commanded no rotation at all. */
    private static final double CURSOR_DEADZONE = 0.05;

    // ---- Test 1: the flight panel + the spin brake -------------------------------------------

    @Test
    public void seatedPilotSeesLiveVelocityAndACentredCursorStopsTheShipTurning() throws Exception {
        // IN THE AIR. This site was lifted before its neighbours were, as an EXPERIMENT with one
        // variable. Measured 2026-09-14 at four client forks: with the flight cursor provably inside
        // its dead-zone (worst deflection 0.016 against 0.05), this craft went on turning at
        // 0.2-0.5 rad/s, oscillating, with nothing commanding it — and it was the only scenario in
        // the suite that spins a hull while the hull still stands on its pad at the bottom of the
        // shared pre-clear's ten-block shaft, where a contact impulse on an asymmetric hull is
        // off-axis. Same pad, same craft, same load, no shaft: if the turning stopped, the rock was
        // turning it.
        //
        // THE CONTRAST THIS PARAGRAPH DESCRIBES NO LONGER EXISTS IN THE TREE: every site in this
        // class stands in the band now, and so does every other ship fixture in the suite. The note
        // is kept because it says why this one moved first and what its lift was an answer to — not
        // because a reader can still see the other half of the comparison here.
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildAndBoardShip(site);

        // --- The HUD panel. Climb, then read the text the CLIENT actually rendered. Before the ship's
        // velocity reached the client the panel had no speed line at all, and no bars.
        String hudBefore = freeFlightHud();
        assertTrue("a seated tier-2 pilot must get a Free Flight HUD at all: '" + hudBefore + "'",
                !hudBefore.isEmpty());

        Events events = events();
        long throttleMark = events.markInstrumented();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // The key REACHING the computer is a link, and it is waited for before the climb is
            // measured: a red on the climb alone cannot tell a throttle that never arrived (a broken
            // key binding, seat packet or dummy) from a ship that got it and did not rise.
            awaitRecord(events, throttleMark, "pilot_input_set",
                    "the real held throttle must reach a ship's flight computer at all", 100,
                    "\"input\":\"set\"");
            // Event-gated hover-lift: hold vertical-up until the ship has climbed, with a bounded
            // ceiling + early exit.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> shipInfo().y,
                    y -> y - ship[1] > 2.0, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double climbed = lift.value;
        assertTrue("holding vertical-up must lift the ship: " + ship[1] + " -> " + climbed,
                climbed - ship[1] > 1.0);

        // The client's own velocity readout must be non-zero while the ship is moving. Read it from the
        // rendered HUD text: that is the string the pilot is looking at, not an internal field.
        // Event-gated: poll the rendered HUD until it shows a non-zero speed (bounded ceiling +
        // early exit).
        ClientPoll.Result<String> hud = ClientPoll.until(bot()::waitTicks,
                this::freeFlightHud,
                VSShipFlightTelemetryE2ETest::hasNonZeroSpeedReadout, 2, 30);
        String hudMoving = hud.value;
        assertTrue("the tier-2 flight HUD must show the ship's real speed while it is moving; "
                + "the client rendered: '" + hudMoving + "'", hud.satisfied);

        // --- The spin brake. Deflect the flight cursor sideways through the client's OWN raw-mouse
        // entry point, so the ship rolls, then centre the cursor and watch the spin die.
        for (int i = 0; i < 12; i++) {
            mouseDelta(60, 0);
            bot().waitTicks(2);
        }
        double cursorDeflected = flightCursorX("after twelve raw mouse deltas");
        assertTrue("a raw mouse delta must deflect the client's flight cursor (got "
                + cursorDeflected + ")", Math.abs(cursorDeflected) > 0.2);

        // Poll omega until the deflected cursor has actually spun the ship up. This one STAYS a poll,
        // and the reason is the shape of its question rather than habit: "has it started turning" is
        // LATCHING — the first observation that sees the rate above the line records a fact that
        // cannot un-happen, so exiting there is exiting on the answer. Contrast the brake below,
        // where "has it fallen below the line" can be true for one sample of a rate that is rising,
        // and an early exit is therefore a way to miss the subject rather than a way to save ticks.
        ClientPoll.Result<Double> spin = ClientPoll.until(bot()::waitTicks,
                () -> shipInfo().omega,
                o -> o >= 0.05, 2, 60);
        double spinning = spin.value;
        assertTrue("a deflected flight cursor must actually spin the ship (omega=" + spinning + ")",
                spinning > 0.05);

        // Marked BEFORE the centring, because the packet that says "stop" is a CHANGE: the client
        // sends an idle input on the tick the cursor enters its dead-zone and never repeats it (a
        // held NON-idle input is re-asserted on a keep-alive; an idle one is exempt). The records
        // this mark collects are therefore the whole of what the computer was ever told to stop for.
        long centreMark = events.markInstrumented();
        // The CLIENT mark beside it, for the one question a red here cannot otherwise answer: did
        // the cursor STAY centred? `flight_cursor` is written on every tick the pilot path runs, so
        // the records since this mark are the whole history of what the client was commanding —
        // and a ship still turning with a cursor that never left its dead-zone is a different fault
        // from one whose pilot kept steering. Without it the two are one red.
        long cursorMarkAfterCentring = clientEvents().mark();
        double cursorCentred = centreFlightCursor();
        assertTrue("the client's flight cursor must return to centre (got " + cursorCentred + ")",
                Math.abs(cursorCentred) < CURSOR_DEADZONE);

        // With the cursor centred the controller must brake the ship to rest — and STAY at rest. That
        // second half is the contract, and it is why this is a WINDOW and not a poll.
        //
        // It used to be `ClientPoll.until(..., o -> o <= 0.05, ...)`, and that predicate can be
        // satisfied by a TROUGH. An early exit is right for a latching question (has it started
        // turning, has it climbed two blocks) because such a fact cannot un-happen; "the rate is
        // below X" of an oscillating quantity is the opposite, and the loop stops at the first dip.
        // Measured 2026-09-14: the poll reported satisfied at iteration 103 with omega=0.0377 while
        // its own trajectory over that same window read 0.219, 0.288, 0.372, 0.123, 0.393 — rising,
        // sampled at a dip. The scenario went GREEN on a ship that had plainly not stopped, and the
        // run that reddened differed from it only in whether a sample landed in a trough.
        //
        // So: give the brake the ticks it has always had, then WATCH. The window cannot end early,
        // the assertion is on the WORST reading in it, and the whole trajectory goes into the
        // message — a rate still FALLING steeply is a brake that wanted longer; one that PLATEAUS or
        // climbs is a ship still EXECUTING a rotation command, because the computer holds the last
        // input it was handed and a "stop" that never landed leaves it turning at whatever
        // deflection did; one creeping down with no floor is no braking torque at all, only the
        // substrate's own damping.
        // THE CONFOUND, removed before the question is asked. The climb leg above held the vertical
        // throttle, and a held throttle RAMPS the Flight-Assist cruise setpoint while releasing it
        // KEEPS that setpoint — the documented contract, with an e2e of its own. So a craft that has
        // merely stopped being steered is still commanded to fly, and asking "did it stop turning"
        // of it is a compound question.
        //
        // Measured 2026-09-14, which is why this is here: the physics recorder showed cmdSpeed=12.0
        // for the whole brake window, netMove [-42,-84,+4], the craft descending eighty-four blocks
        // onto the ground with its linear controller saturated. Every residual-rate reading this
        // scenario has ever taken came from a craft in that state. The pit hid it — the pad held the
        // craft where it was put, and contact friction killed the spin the controller was supposed
        // to kill.
        //
        // Cut it the way a player does, with the cut key, not with a probe: the cruise is the
        // pilot's own channel and this scenario is about the pilot's own controls.
        bot().holdKey(Keyboard.KEY_X);
        bot().waitTicks(20);
        bot().releaseKey(Keyboard.KEY_X);
        bot().waitTicks(10);
        String cruiseAfterCut = exec("artest vs ff-cruise-read-by-id 0 " + scenarioShipId);
        scenario().record("cruiseAfterCut", cruiseAfterCut);
        assertTrue("ARRANGEMENT: the cruise must be ZERO before the brake is judged, or this leg"
                        + " measures a craft that is still commanded to fly and merely not steered."
                        + " The cut key is what a pilot uses and it zeroes the setpoint; if this"
                        + " reply carries a non-zero cruise the cut did not take: " + cruiseAfterCut,
                cruiseAfterCut.contains("\"afcResolved\":true")
                        && Math.abs(readDouble(cruiseAfterCut, CRUISE_FWD)) < 1e-6
                        && Math.abs(readDouble(cruiseAfterCut, CRUISE_RIGHT)) < 1e-6
                        && Math.abs(readDouble(cruiseAfterCut, CRUISE_UP)) < 1e-6);

        bot().waitTicks(BRAKE_SETTLE_TICKS);
        java.util.List<Double> hold = ClientPoll.observe(bot()::waitTicks,
                () -> shipInfo().omega, HOLD_SAMPLES, HOLD_TICKS_BETWEEN);
        StringBuilder omegaTrace = new StringBuilder();
        double settled = 0.0;
        for (int i = 0; i < hold.size(); i++) {
            double omegaNow = hold.get(i);
            settled = Math.max(settled, omegaNow);
            omegaTrace.append(i == 0 ? "" : " ").append(i * HOLD_TICKS_BETWEEN).append("t:")
                    .append(Math.round(omegaNow * 1000.0) / 1000.0);
        }
        // Every control packet this computer ACCEPTED since before the centring began. The recorder
        // sits on setPilotInput, which the seat calls only after its own pilot guard, so a record
        // here is a packet the server took — and a stream that stops while the cursor was still
        // deflected means the computer was never told to stop, whatever the client's cursor reads.
        // What it cannot say is what an input CONTAINED (the payload is `set` / `null`), so it
        // counts deliveries and claims nothing more.
        String pilotInputs = events.since(centreMark, "pilot_input_set");
        int accepted = matchingRecords(pilotInputs, "\"input\":\"set\"");
        // THE discriminator, and it is the whole reason this window is read at all: the computer
        // LATCHES, so only an IDLE input stops a rotation. "The stop arrived and was ignored" and
        // "the stop never arrived" send a reader to opposite subsystems and are indistinguishable
        // from a count of accepted packets.
        int idleHandedOver = matchingRecords(pilotInputs, "\"input\":\"idle\"");
        // NOT Events.lastField: that reads STRING fields ("k":"v") and a record's tick is a bare
        // number, so it would answer null for a stream that is plainly there.
        String lastAcceptedTick = lastNumericField(pilotInputs, "tick");
        // What the CLIENT was commanding through the whole settle and hold. The worst deflection the
        // cursor reached since it was centred is the discriminator: inside the dead-zone means the
        // client asked for nothing and the craft turned anyway; outside it means the craft was being
        // steered and the subject of this leg was never set up.
        String cursorAfter = clientEvents().since(cursorMarkAfterCentring, "flight_cursor");
        double worstCursor = Math.max(maxAbsField(cursorAfter, "x"), maxAbsField(cursorAfter, "y"));
        // HOW MANY COMPUTERS drove this ship while it was supposed to be stopping. The physics-thread
        // recorder stamps every step with who drove it precisely because "two controllers on one ship
        // is a stale tile instance that outlived its replacement in the ship's controller set" — and a
        // dead instance still holding the pilot's last DEFLECTED input would command a turn forever
        // while the live one is handed the idle. That is the difference between "the brake is wrong"
        // and "something else is still pressing", which the rate alone cannot show.
        PilotSeat seatNow = PilotSeat.byId(this::exec, 0, scenarioShipId);
        String drivers = seatNow.hasAfc
                ? exec("artest vs motion-trace 0 " + seatNow.afcX + " "
                        + seatNow.afcY + " " + seatNow.afcZ + " 20000")
                : "(no afc address in: " + seatNow.raw() + ")";
        System.out.println("[tier2] omega spinning=" + spinning + " worstInHold=" + settled
                + " worstCursorAfterCentring=" + worstCursor
                + " settle=" + BRAKE_SETTLE_TICKS + "t hold=" + HOLD_SAMPLES + "x"
                + HOLD_TICKS_BETWEEN + "t trace=[" + omegaTrace + "]"
                + " pilotInputsAcceptedSinceCentring=" + accepted
                + " lastAcceptedTick=" + lastAcceptedTick);
        // The controller read-back that used to be printed here is GONE, and its absence is the
        // point. It came from `artest vs afc-debug`, which read a global last-writer static on
        // TileAdvancedFlightComputer — written by whichever flight computer ran last, on a shared
        // client that is frequently a different craft. It answered a different question from the
        // one this scenario asks, it was never asserted on, and production allocated an array on
        // every physics step to keep it fed. `ship-info` below is asked about THIS ship.
        assertTrue("with the flight cursor centred the ship must STOP turning AND STAY stopped, not"
                + " coast: it was spinning at " + spinning + " rad/s, and after "
                + BRAKE_SETTLE_TICKS + " ticks to brake its WORST rate across the following "
                + ((HOLD_SAMPLES - 1) * HOLD_TICKS_BETWEEN) + "-tick hold was " + settled
                + ". The whole hold, by tick offset: [" + omegaTrace + "]"
                + " — a rate that PLATEAUS or climbs is a ship still executing a rotation command,"
                + " not one failing to coast to a stop. This is the WORST of the hold and not the"
                + " first reading under the line, deliberately: the earlier form exited on its first"
                + " dip and passed this scenario on a ship whose rate was rising."
                + "\n  WHICH FAULT THIS IS, from the client's own record: the worst cursor"
                + " deflection since the centring was " + worstCursor + " against a dead-zone of "
                + CURSOR_DEADZONE + ". Inside it, the client asked for NOTHING and the craft turned"
                + " anyway — the command latched on the computer, which stops only on an idle input"
                + " that is sent ONCE, on the tick the cursor enters the dead-zone, and is the one"
                + " input deliberately exempt from the keep-alive that re-asserts a held one."
                + " Outside it, the craft was still being STEERED and this leg never had its"
                + " subject. A " + (-1.0) + " means the cursor recorder said nothing at all, which"
                + " is a third thing and not a centred cursor."
                + "\n  AND WHICH HALF OF THAT: the computer was handed " + idleHandedOver
                + " IDLE input(s) since before the cursor was centred, beside " + accepted
                + " deflected one(s) (the last at tick " + lastAcceptedTick + "). ZERO idle means"
                + " the stop never reached the computer at all — look at the send and its delivery,"
                + " where a packet whose tile is not loaded is dropped in silence. One or more means"
                + " the stop WAS handed over and the craft turned anyway — look at what the"
                + " controller does with an idle input, not at the wire. Every record in the window: "
                + pilotInputs
                + "\n  WHO WAS DRIVING, from the physics recorder — `writers` above 1 means a stale"
                + " flight computer is still commanding this ship beside the live one, which is a"
                + " different fault from a brake that does not brake: " + drivers,
                settled <= 0.05);

        exec("artest player dismount");
        reportClientHealth("seatedPilotSeesLiveVelocityAndACentredCursorStopsTheShipTurning");
    }

    // ---- Test 2: the camera turns with the ship, and the eye stays out of the deck ------------

    @Test
    public void anInvertedShipTurnsThePilotsCameraOverAndKeepsHisEyeOutOfTheDeck() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        buildAndBoardShip(site);
        bot().waitTicks(20);

        double rollUpright = deckCamera("roll");
        assertTrue("an upright ship must leave the camera level (roll=" + rollUpright + ")",
                Math.abs(rollUpright) < 15.0);

        // Roll the ship all the way over. The pilot's own attitude reference owns the angular channel
        // while he is seated, so steer it the way he does: hold the cursor hard over until it is there.
        rollShipUpsideDownWithTheMouse(bx, by, bz);

        // Where exactly a rigid body coasts to is not the contract; that it went over, and that the
        // camera went with it, is. Read the pair adjacently so they describe the same instant.
        double shipUpY = deckCamera("shipUpY");
        double rollInverted = deckCamera("roll");
        assertTrue("the ship must actually have rolled past vertical (its up points " + shipUpY + ")",
                shipUpY < -0.3);

        // 1. The camera turns over with the deck. Vanilla has no roll for a player camera at all, so it
        //    is zero unless AR supplies it - and it must be the SHIP's roll, not merely some roll: for a
        //    craft rolled about its nose, the cosine of the camera roll IS the world Y of the ship's up.
        assertTrue("an inverted ship must turn the pilot's camera over with it (roll=" + rollInverted
                + " deg)", Math.abs(rollInverted) > 100.0);
        double impliedUpY = Math.cos(Math.toRadians(rollInverted));
        assertTrue("the camera roll must BE the ship's roll: a camera rolled " + rollInverted
                        + " deg implies a ship up of " + impliedUpY + ", but the ship's is " + shipUpY,
                Math.abs(impliedUpY - shipUpY) < 0.15);

        // 2. The eye follows the SHIP's up, not the world's. This is the "camera sinks into the floor"
        //    bug: with the eye pinned to world +Y, an inverted pilot's eye is a metre and a half INSIDE
        //    the deck above his seat. The contract: the eye is displaced along the ship's up.
        double eyeY = deckCamera("eyeY");
        double playerY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[tier2] shipUpY=" + shipUpY + " playerY=" + playerY + " eyeY=" + eyeY);
        assertTrue("the eye must be offset along the SHIP's up, not the world's: shipUpY=" + shipUpY
                        + " but the eye sits " + (eyeY - playerY) + " above the body",
                (eyeY - playerY) * shipUpY > 0.0);
        assertTrue("the eye offset must be about an eye height (" + Math.abs(eyeY - playerY) + ")",
                Math.abs(eyeY - playerY) > 0.8 && Math.abs(eyeY - playerY) < 2.5);

        // 3. And the client is actually DRAWING something: capture the frame. An eye buried in a solid
        //    block renders a single flat colour; a cockpit does not. The capture needs the framebuffer,
        //    which the harness leaves off for driver safety; turn it on for these few frames, then back.
        boolean framebufferWasOn = bot().setFramebuffer(true).get("previous").getAsBoolean();
        JsonObject shot;
        try {
            bot().waitTicks(10); // let frames render into the freshly bound framebuffer
            shot = bot().screenshot("tier2-inverted");
        } finally {
            bot().setFramebuffer(framebufferWasOn);
        }
        assertTrue("the client must write the screenshot: " + shot, shot.get("exists").getAsBoolean());
        assertTrue("the capture must come from the framebuffer, or its pixels prove nothing: " + shot,
                shot.get("framebuffer").getAsBoolean());
        File png = new File(shot.get("path").getAsString());
        BufferedImage frame = ImageIO.read(png);
        assertTrue("the screenshot must decode as an image", frame != null);
        System.out.println("[tier2] captured " + png + " (" + frame.getWidth() + "x" + frame.getHeight()
                + ", distinct colours=" + distinctColours(frame) + ")");
        assertTrue("a rendered frame from inside a solid block is one flat colour; the pilot must see "
                + "the world (distinct colours=" + distinctColours(frame) + ")", distinctColours(frame) > 8);

        exec("artest player dismount");
        reportClientHealth("anInvertedShipTurnsThePilotsCameraOverAndKeepsHisEyeOutOfTheDeck");
    }

    // ---- Test 3: a crew member stays on a steeply rolled deck ---------------------------------

    @Test
    public void crewStaysOnASteeplyRolledDeckInsteadOfBeingFlungIntoACorner() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);

        // Stand a living body on the level deck and let it settle. An armour stand is a living entity
        // with a player's movement rules, and unlike a player it has no client sending positions - so
        // what happens to it is purely what the server's movement frame does. The capture is awaited
        // as an event carrying THIS body's id: a hook that never applied and one that applied and
        // declined are then different answers, and neither can be given by another body.
        int crewId = dropStandAndAwaitItsCapture(ship);

        double[] restingOnDeck = localOf(crewId);

        // Roll the deck steeply. Past 45 degrees the world-frame drag anisotropy dominates: the pull
        // toward the deck acquires a world X/Z component damped four times harder than its world Y one.
        double half = Math.toRadians(75.0) / 2.0;
        String point = exec("artest vs point-by-id 0 " + scenarioShipId
                + " " + Math.cos(half) + " 0.0 0.0 " + Math.sin(half));
        assertTrue("attitude hold must accept the roll: " + point, point.contains("\"commanded\":true"));
        bot().waitTicks(200);

        // An attitude SLEW is a value converging, so it stays a wait — but the value it converges to
        // is recorded here, because "he barely moved across the deck" is vacuous on a deck that never
        // rolled and nothing in this scenario said which of the two happened.
        scenario().record("upYAfterRoll", shipInfo().upY());

        double[] afterRoll = localOf(crewId);

        // Measured on a real client run - the frame ShipFrameTravel MOVES in (VS
        // ShipTransform.rotate) and the frame the camera LEVELS to (the attitude quaternion) are ONE
        // rotation, so "keys/mouse feel inverted" is NOT a frame-source split - it is the world-frame aim
        // under a deck-levelled camera. Pin it: at 75 degrees the disagreement is ~0.
        // Asked of THIS crew member, and computed on demand. The two numbers used to be read out of
        // a pair of production statics that were written by whichever body the resolver had last
        // handled and left at a -1.0 sentinel until it first ran — and -1.0 satisfies the "< 1e-6"
        // assertion below, so the pin could go green on an instrument that had never measured
        // anything. `ship-frame-check` takes the subject and answers about it or says it cannot.
        // The stronger arrangement gate the on-demand form allows: not "the number is not the
        // sentinel" but "the measurement ran, for this body" — which is the reader's own refusal,
        // and it says which of the TWO states an `available:false` could be.
        ShipFrameCheck rolledStats = ShipFrameCheck.byId(this::exec, 0, crewId)
                .requireMeasured("the ship-frame check must MEASURE the two frames for this crew"
                        + " member before their agreement can mean anything");
        double tcUp = rolledStats.upDisagreement();
        double tcFwd = rolledStats.fwdDisagreement();
        System.out.println("[tier2][TC] rolled-deck frame disagreement up=" + tcUp + " fwd=" + tcFwd);
        assertTrue("the movement frame and the camera frame must be ONE rotation on a 75-degree deck, so "
                + "the keys/mouse inversion is the aim-frame (Path B), not a frame-source split "
                + "(up=" + tcUp + " fwd=" + tcFwd + ")", tcUp < 1e-6 && tcFwd < 1e-6);

        double drift = distance(restingOnDeck, afterRoll);
        System.out.println("[tier2] crew on rolled deck: start=" + java.util.Arrays.toString(restingOnDeck)
                + " end=" + java.util.Arrays.toString(afterRoll) + " drift=" + drift);

        // The measurement is in the SHIP's coordinates: a body that genuinely rides the deck barely
        // moves there, whatever the ship does in the world. Before the movement frame followed the
        // deck, this body slid off and lodged in a corner metres away.
        assertTrue("a crew member must stay where he stands on a deck rolled 75 degrees; he moved "
                + drift + " blocks across it", drift < 2.0);

        // And he must still be standing on it, not falling.
        PlayerShipData data = PlayerShipData.byId(this::exec, 0, crewId);
        assertTrue("the crew member must still be resting on the deck: " + data.raw(),
                data.onGround);
        reportClientHealth("crewStaysOnASteeplyRolledDeckInsteadOfBeingFlungIntoACorner");
    }

    // ---- Test 3b: a crew member rides a ROTATING deck without the capture thrashing ------------
    // The maintainer's fall-through: a body loses an inverted/spinning ship's deck. The real driver is
    // ship ANGULAR VELOCITY, not the inversion ANGLE - a STATICALLY inverted deck rides fine (the 75deg
    // test), but the deck ROTATING under the body carries it faster than a tight external-move guard
    // tolerates, so the guard mistakes the deck's own rotation for a teleport and drops the capture every
    // tick until the body loses the deck. Reproduced by a free spin; the omega-aware guard fixes it.

    @Test
    public void aCrewMemberRidesARotatingDeckWithoutTheCaptureThrashing() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildShip(site);
        int crewId = dropStandAndAwaitItsCapture(ship);

        // Everything from here is counted off THIS body's own release records, since a mark taken
        // before the spin: every drop is a `deck_released` carrying the entity it dropped and
        // production's own reason. The JVM-global counter it replaces summed every body's churn —
        // the bot's included — and could not say whose deck was thrashing.
        //
        // Counted over ALL reasons since 2026-09-16. It used to filter on `externalMove`, the guard
        // that compared a committed deck point against the live one; that guard is gone, so the
        // filter would now return zero for a deck that thrashed its crew off through any other gate.
        // The contract has not changed — a rotating deck must not drop the body it carries — and
        // narrowing it to one mechanism was always describing the suspect rather than the crime.
        Events events = events();
        long spinMark = events.markInstrumented();

        // Spin the ship about a horizontal axis via free VS physics - the deck ROTATES under the standing
        // body. A body that rides the rotation stays captured; a tight external-move guard mistakes the
        // deck's OWN rotation for a teleport and drops the capture every tick, so the body loses the deck.
        // This isolates the fall-through's real driver: ship ANGULAR VELOCITY, not the inversion angle (a
        // STATICALLY inverted deck rides fine - the 75deg test). Reproduces the maintainer's ~174deg case,
        // which was a ship oscillating/hunting near the unstable inverted attitude (nonzero omega).
        exec("artest vs spin-ship-by-id 0 " + scenarioShipId + " 2.0 0.0 0.0");
        bot().waitTicks(30);
        exec("artest vs spin-ship-by-id 0 " + scenarioShipId + " 0.0 0.0 0.0");

        String released = events.since(spinMark, "deck_released");
        // A LOW count and a dead recorder produce the same number, so the recorder is asked first.
        Events.assertInstrumentRan(released, DECK_INSTRUMENT,
                "few drops means the deck's own rotation was tolerated");
        int drops = matchingRecords(released, "\"e\":" + crewId + ",", "\"reason\":");
        System.out.println("[tier2][SPIN] capture drops for entity " + crewId
                + " during a 2 rad/s roll spin: " + drops);
        System.out.println("[tier2][SPIN] releases since the spin began: " + released);

        // A rotating deck must NOT thrash the capture. Without the omega-aware guard this ratchets ~1 drop
        // per tick (tens over the window) and the body loses the deck; with it, the deck's own carry is
        // tolerated and the body rides the spin.
        assertTrue("a rotating deck must not thrash the aboard-body capture (external-move drops for"
                + " entity " + crewId + "=" + drops + " during a 2 rad/s spin). Every release"
                + " recorded for any body since the spin began, with production's own reason: "
                + released, drops < 8);
        // This scenario leaves the bot standing on a hull that was just spun at 2 rad/s and is left
        // steeply tilted, and the scenario that follows it opens on the shared base's full-health
        // gate. Read out what the client renders here, so a leftover is attributed to the window
        // that produced it instead of to the reset that could not clear it.
        reportClientHealth("aCrewMemberRidesARotatingDeckWithoutTheCaptureThrashing");
    }

    // ---- Test 4: a body on a GROUNDED ship's deck stays on the deck, not through it -----------

    @Test
    public void aBodyOnADeckWithWorldGroundBelowStaysOnTheDeck() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Playtest report: standing on the deck of a DOCKED tier-2 ship (one resting on the ground), the
        // player fell through the deck. A ship's world bounding box overlaps the terrain it sits on, and
        // the movement takeover was declined for any body with world ground near its feet - so a body on
        // the deck of a grounded ship was handed to vanilla, which cannot see the subspace deck and let
        // it fall. The fix keys the takeover on standing on a SHIP block, not on the absence of world
        // ground: a body on the deck is resolved in the ship frame whatever the terrain below does.
        double[] ship = buildShip(site);

        // A body settled on the actual deck - the exact thing the pilot stands on.
        int standId = dropStandAndAwaitItsCapture(ship);

        PlayerShipData onDeck = PlayerShipData.byId(this::exec, 0, standId);
        assertTrue("the body must have settled on the deck: " + onDeck.raw(),
                onDeck.onGround && onDeck.shipLoaded);
        // On THIS scenario's deck. `deckY` below is taken out of this same reply and every later
        // assertion is a comparison against it, so a neighbour's hull answering here does not merely
        // mislabel the settle — it moves the baseline the grounded-deck claim is measured from.
        onDeck.requireAboard(scenarioShipId,
                "the body must have settled on the deck of the ship this scenario built");
        double deckY = onDeck.playerY;
        assertTrue("a body on the deck must be resolved in the ship frame: "
                + exec("artest vs would-take-over 0 " + standId),
                exec("artest vs would-take-over 0 " + standId).contains("\"handles\":true"));

        // Now make the ship "grounded": lay a world stone floor right under the deck, so the deck has
        // real terrain close beneath it - the overlap that broke the playtest. A body on the deck must
        // stay ON the deck, not fall to (or through) this floor.
        int fy = (int) Math.floor(deckY) - 1;
        int sx = (int) Math.floor(ship[0]);
        int sz = (int) Math.floor(ship[2]);
        // The regression is a RELEASE — the gate hands the body to vanilla the moment it sees world
        // ground under its feet, and vanilla cannot see a subspace deck. So mark before the floor
        // goes in and read the silence afterwards: production names its own reason at the release,
        // and `steppedOntoTerrain` is that gate's word for exactly this.
        Events events = events();
        long floorMark = events.markInstrumented();
        assertTrue("must lay the world floor under the deck",
                exec("artest fill 0 " + (sx - 3) + " " + fy + " " + (sz - 3) + " "
                        + (sx + 3) + " " + fy + " " + (sz + 3) + " minecraft:stone").contains("\"ok\":true"));
        bot().waitTicks(60);

        String releases = events.since(floorMark, "deck_released");
        // "Nothing was released" and "nobody was recording releases" are the same empty reply until
        // the recorder is asked; and the body having been captured at all is asserted above, so this
        // silence is about a gate that declined to fire rather than a body that was never held.
        Events.assertInstrumentRan(releases, DECK_INSTRUMENT,
                "no terrain release means the body kept its deck when the ground appeared");
        assertTrue("laying world ground under the deck must not hand this body (entity " + standId
                        + ") to vanilla: the ship-frame gate released it naming the terrain it now"
                        + " stands over. Every release recorded since the floor went in: " + releases,
                matchingRecords(releases, "\"e\":" + standId + ",", "steppedOntoTerrain") == 0);

        PlayerShipData afterFloor = PlayerShipData.byId(this::exec, 0, standId);
        double yAfter = afterFloor.playerY;
        String handles = exec("artest vs would-take-over 0 " + standId);
        System.out.println("[tier2] grounded-deck: deckY=" + deckY + " afterFloor y=" + yAfter
                + " floorTop=" + (fy + 1) + " would-take-over=" + handles);
        assertTrue("a body on the deck of a grounded ship must stay resolved in the ship frame, not be "
                + "handed to vanilla because there is now ground below: " + handles,
                handles.contains("\"handles\":true"));
        assertTrue("it must stay ON the deck (y=" + deckY + "), not drop toward the world floor (top "
                + (fy + 1) + "): it is at y=" + yAfter, Math.abs(yAfter - deckY) < 1.0);
        assertTrue("and still on the ground (the deck), not falling: " + afterFloor.raw(),
                afterFloor.onGround);
        reportClientHealth("aBodyOnADeckWithWorldGroundBelowStaysOnTheDeck");
    }

    // ---- Test 5: a station-keeping ship holds altitude, it does not sink ----------------------
    // Playtest report: a flown tier-2 ship left to hold station could not be brought
    // to a full stop - the HUD showed a persistent ~-0.01/tick vertical velocity and the ship slowly
    // sank. The force controller was a bare velocity deadbeat with no gravity feed-forward, so against
    // the constant gravity the physics solver adds each tick it settled at vCmd + gravity*dt instead of
    // vCmd (a steady -g*dt ~ -0.16 blk/s). With the feed-forward, a ship commanded to hover - here flown
    // and then left UNMANNED, which makes the flight computer command a zero world velocity while holding
    // attitude - holds its altitude. Read the CLIENT-loaded ship's own world velocity + position.

    @Test
    public void aStationKeepingShipHoldsAltitudeInsteadOfSinking() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        double[] ship = buildAndBoardShip(site);

        // Fly it a couple of blocks up so it is genuinely airborne (and mark it "flown", which arms the
        // unmanned station-keeping hold), then release the throttle.
        Events events = events();
        long throttleMark = events.markInstrumented();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // As in test 1: the throttle's arrival at the computer is a link and is awaited as one,
            // so a climb that never happens is not reported as a control failure when the control
            // never got there.
            awaitRecord(events, throttleMark, "pilot_input_set",
                    "the real held throttle must reach a ship's flight computer at all", 100,
                    "\"input\":\"set\"");
            // Event-gated hover-lift (bounded ceiling + early exit): the loop returns the moment the
            // ship has climbed, so the ceiling is patience and not how far it flies.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> shipInfo().y,
                    y -> y - ship[1] > 2.0, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double climbed = lift.value;
        assertTrue("holding vertical-up must lift the ship: " + ship[1] + " -> " + climbed,
                climbed - ship[1] > 1.0);

        // Park before standing up: cut (X) zeroes the Flight-Assist cruise setpoint. A dismount
        // with a NON-zero setpoint deliberately leaves the ship CRUISING (the autopilot contract,
        // pinned by VSShipUnmannedCruiseE2ETest); the station-hold this test pins is the parked
        // pilot's case - zero setpoint - whose regression mode is the -g*dt sink measured below.
        bot().holdKey(Keyboard.KEY_X);
        bot().waitTicks(10);
        bot().releaseKey(Keyboard.KEY_X);
        bot().waitTicks(5);

        // Stand up. A parked ship that has been flown holds station while unmanned: the flight computer
        // commands a ZERO world velocity and holds the attitude - the exact path this bug lives on.
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        // Two links the 60-tick wait used to hide, and both are premises for the measurement below:
        // the computer is told the pilot has gone, and it then DECIDES to hold station. "It did not
        // sink" says nothing about a station hold that was never armed — an inert computer does not
        // sink either, it is simply not flying.
        awaitRecord(events, dismountMark, "pilot_input_set",
                "the flight computer must be told the pilot stood up", 120, "\"input\":\"null\"");
        String hold;
        try {
            hold = events.await(dismountMark, "unmanned_hold_decided",
                    "with nobody flying, the computer must reach its unmanned decision", 200);
        } catch (AssertionError never) {
            // Typed as arrangement: this seam is the one anchor in the flight computer's recorder
            // that can go stale in silence (an INVOKE descriptor), and its own instrument name is
            // what tells a dead seam from a ship that never went unmanned.
            scenario().arrangementFailed(never.getMessage());
            return;
        }
        scenario().requireArranged("the unmanned ship must decide to HOLD STATION before 'it did not"
                + " sink' is a statement about the hold: " + hold,
                matchingRecords(hold, "\"held\":true") > 0);
        bot().waitTicks(60); // let the controller brake the climb out and settle onto the hold

        double yStart = shipInfo().y;
        double worstVelY = 0.0;
        for (int i = 0; i < 40; i++) {
            bot().waitTicks(3);
            double velY = shipInfo().velY;
            if (Math.abs(velY) > Math.abs(worstVelY)) {
                worstVelY = velY;
            }
        }
        double yEnd = shipInfo().y;
        System.out.println("[tier2][STATIONKEEP] yStart=" + yStart + " yEnd=" + yEnd
                + " drift=" + (yEnd - yStart) + " worstVelY=" + worstVelY);

        // The bug held a steady -0.16 blk/s sink; the fix holds ~0. A threshold well under the bug and
        // well over solver noise separates them cleanly.
        assertTrue("a station-keeping ship must not sink: its vertical velocity peaked at " + worstVelY
                + " blk/s (the bug held ~-0.16)", Math.abs(worstVelY) < 0.05);
        assertTrue("a station-keeping ship must hold its altitude: it drifted " + (yEnd - yStart)
                + " blocks over ~6 s (the bug sank ~1 block)", Math.abs(yEnd - yStart) < 0.3);

        exec("artest player dismount");
        reportClientHealth("aStationKeepingShipHoldsAltitudeInsteadOfSinking");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Build a ship at this test's own base and wait for it to load with the client present; returns its
     * world position.
     *
     * <p>The harness server is shared by every test method, so "the ship near my base" is a question
     * a neighbour can answer. The ship's IDENTITY comes off the registry's own {@code ship_spawned}
     * record, taken since a mark set before this assembly was queued, and {@link #shipInfo()}
     * carries it for the rest of the scenario.</p>
     */
    private double[] buildShip(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The mark is taken BEFORE the assembly is queued, so the record it waits for is this
        // scenario's own ship by construction — where the count increment it replaces asked a
        // question every neighbour that ever assembled a ship also answers, and then had to recover
        // the identity from a nearest-ship lookup at the build site.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(site);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        scenarioShipId = awaitShipSpawned(events, spawnMark,
                "a with-pilot-seat assembly must create a VS ship in the queryable registry");
        bot().waitTicks(40);

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so what is asked of the"
                        + " ship below is only answerable because a client got here");

        // READINESS, as production's own event. This was a bounded poll of `ship-info` for
        // `managed:true`, under a comment calling that the one gate no event records — which stopped
        // being true: `ShipEvent.ShipLoadedEvent` is published on the tick a craft becomes ready to
        // be flown, recorded as `ship_usable`, and the base waits on it.
        //
        // And `managed` answers a WEAKER question than this scenario needs. It is true once a
        // PhysicsObject for the id exists in this world; readiness is that object being physics-ready
        // with its surrounding chunks cached, which is what makes it step. A craft that is loaded and
        // not yet ready answers `managed:true` and does not move — and this scenario immediately
        // flies, spins and drops it. The mark is `spawnMark`, taken before the assembly: readiness is
        // an EDGE that fires once, so a mark taken here could miss it entirely.
        awaitShipUsable(events, spawnMark, scenarioShipId);
        ShipInfo info = shipInfo();
        double[] where = new double[]{info.x, info.y, info.z};
        System.out.println("[tier2] ship at base (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
    }

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        double[] ship = buildShip(site);
        // The seat is located INSIDE this scenario's own ship: `vs seat-mount <dim>` takes the first
        // pilot seat in the world's loaded-tile list with no position filter, which is unambiguous
        // only while the world holds one ship, and mounts a neighbour's once scenarios share one.
        // By identity: the positional form resolves the yard of the ship NEAREST the base, over the
        // whole registry and with no distance bound, so a neighbour's craft answers it in the same
        // shape. This scenario was told which ship it built.
        PilotSeat seat = PilotSeat.byId(this::exec, 0, scenarioShipId)
                .requireFound("find-seat must locate the pilot seat inside THIS scenario's ship ("
                        + scenarioShipId + ", built at " + bx + "," + by + "," + bz + ")");
        String mountInfo = exec("artest vs seat-mount-at 0 " + seat.seatX + " "
                + seat.seatY + " " + seat.seatZ);
        int dummyId = Reply.of("artest vs seat-mount-at", mountInfo).integer(DUMMY_ID);
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                exec("artest player mount-entity " + dummyId).contains("\"mounted\":true"));
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat
        return ship;
    }

    /**
     * Steer the ship all the way over with the pilot's own controls: hold the flight cursor hard to one
     * side until the ship's up points down, then centre it so the controller stops the roll there.
     */
    private void rollShipUpsideDownWithTheMouse(int bx, int by, int bz) throws Exception {
        // The window each pass reads is the two ticks the pass itself waits, so this loop keeps its
        // own pacing: no extra wait is added to take a reading, and the reading is never older than
        // the pass before it.
        long cursorMark = clientEvents().mark();
        bot().waitTicks(2);
        for (int i = 0; i < 240; i++) {
            // Stop asking for roll BEFORE the ship is over: it is a rigid body turning at more than a
            // radian a second, and it coasts on into the brake. Aiming early lands it near inverted.
            if (deckCamera("shipUpY") < -0.45) {
                break;
            }
            if (Math.abs(cursorXSince(cursorMark, "while rolling the ship over, pass " + i)) < 0.9) {
                mouseDelta(60, 0);
            }
            cursorMark = clientEvents().mark();
            bot().waitTicks(2);
        }
        centreFlightCursor();
        bot().waitTicks(40); // let the spin brake settle the ship where the pilot left it
    }

    /**
     * Bring the client's flight cursor back inside its centre dead-zone, the way a pilot does: shove the
     * mouse the other way, coarsely at first and then in small nudges, watching the client's own value.
     * Inside the dead-zone the ship is commanded zero rotation, which is what "centred" means to it.
     */
    private double centreFlightCursor() throws Exception {
        double cursor = flightCursorX("before centring");
        for (int i = 0; i < 200 && Math.abs(cursor) >= CURSOR_DEADZONE * 0.5; i++) {
            int step = Math.abs(cursor) > 0.2 ? 30 : 2;
            mouseDelta(cursor > 0 ? -step : step, 0);
            bot().waitTicks(1);
            cursor = flightCursorX("while centring, nudge " + i);
        }
        return cursor;
    }

    /**
     * The flight cursor as the client last RECORDED it, or a failure that says the input path did not
     * run in that window.
     *
     * <p>Replaces a reflective read of a private production static. The cursor keeps its last value
     * when the input path stops running, so a poll cannot tell "it is where I left it" from "nothing
     * has updated it since" — and the loop above, against a dead input path, would nudge two hundred
     * times and return a stale number that looks like a measurement. A record exists only if
     * production ran this tick; a fresh mark each call keeps the window to that.</p>
     */
    private double flightCursorX(String what) throws Exception {
        long mark = clientEvents().mark();
        bot().waitTicks(1);
        return cursorXSince(mark, what);
    }

    /** The same reading, for a window the caller already owns — used by loops whose own pacing
     *  supplies the ticks, so that taking a reading never adds one. */
    private double cursorXSince(long mark, String what) throws Exception {
        String rec = Events.lastRecord(clientEvents().since(mark, "flight_cursor"));
        assertNotNull("no flight_cursor record " + what + " — the client's flight-input path did not "
                + "run in that window, so there is no cursor reading to act on", rec);
        return Events.number(rec, "x");
    }

    /** Feed a raw mouse delta to the client's own ship-pilot handler, as the window's mouse would. */
    private void mouseDelta(int dx, int dy) throws Exception {
        bot().invokeStaticInt(KEY_BINDINGS, "acceptShipPilotMouseDelta", dx, dy);
    }

    /** Whether the rendered HUD carries a speed readout with a non-zero value. */
    private static boolean hasNonZeroSpeedReadout(String hud) {
        Matcher m = Pattern.compile("([0-9]+\\.[0-9]+)").matcher(hud);
        while (m.find()) {
            if (Double.parseDouble(m.group(1)) > 0.05) {
                return true;
            }
        }
        return false;
    }

    /** How many distinct colours a captured frame contains - one means nothing was drawn. */
    private static int distinctColours(BufferedImage image) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int stepX = Math.max(1, image.getWidth() / 64);
        int stepY = Math.max(1, image.getHeight() / 64);
        for (int x = 0; x < image.getWidth(); x += stepX) {
            for (int y = 0; y < image.getHeight(); y += stepY) {
                seen.add(image.getRGB(x, y));
                if (seen.size() > 64) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }

    /**
     * Print what the CLIENT renders as the harness player's health, tagged with the scenario that
     * has just finished.
     *
     * <p>The shared base opens every scenario by asserting full health as the client renders it, and
     * that gate lives in its {@code @Before} — so a scenario that leaves the player short is not the
     * one that goes red: the NEXT one is, before its own body has run and before anything it could
     * record. Nothing then names the culprit. This goes to stdout and not to the scenario journal on
     * purpose: the journal is printed only for a scenario that FAILS, and the scenario that leaves
     * the leftover behind is by construction one that passed.</p>
     *
     * <p>It asserts nothing. The base's contract is "full health at the START, after a heal", which
     * is weaker than "full health at the end of every scenario" — pinning the stronger one here
     * would red a scenario the shared reset was always going to fix.</p>
     */
    private void reportClientHealth(String afterScenario) throws Exception {
        JsonObject state = bot().reportState();
        System.out.println("[tier2][HEALTH] after " + afterScenario + " the client renders health="
                + (state != null && state.has("health") ? state.get("health").getAsString() : "?"));
    }

    private String clientString(String className, String field) throws Exception {
        return bot().readStaticField(className, field).get("value").getAsString();
    }

    private double clientDouble(String className, String field) throws Exception {
        return Double.parseDouble(clientString(className, field));
    }

    /** This scenario's ship, asked by identity — captured once by {@link #buildShip}. */
    private ShipInfo shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return ShipInfo.byId(this::exec, 0, scenarioShipId);
    }

    private double[] localOf(int entityId) throws Exception {
        PlayerShipData json = PlayerShipData.byId(this::exec, 0, entityId);
        // TWO different failures wear the same message unless they are split, and the reader now
        // splits them: a subject that is NOT THERE is an arrangement failure it raises by type, and
        // a body whose ship frame did not resolve is the AssertionError `localX()` throws. What was
        // two hand-written guards here is the same distinction, made once for every caller.
        return new double[]{json.localX(), json.localY(), json.localZ()};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Wait for a record of {@code type} whose payload carries every one of {@code needles} — an
     * {@link Events#await} that can say WHICH body it means.
     *
     * <p>Local to this class because {@code Events.await} matches on the TYPE alone, and every deck
     * link this class waits for is about ONE body: the harness world holds the bot, this scenario's
     * armour stand and every earlier scenario's, and all of them are captured and released by the
     * same resolver. A wait that could be answered by any of them would be measuring the crowd.</p>
     */
    private String awaitRecord(Events events, long mark, String type, String what, int tickBudget,
                               String... needles) throws Exception {
        String reply = "";
        // This budget is a DEADLINE for a discrete commit with an early exit — how patient the test
        // is, never how far the world moves: the loop returns the moment the record appears, and
        // reaching the end of it is a failure either way.
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, type);
            if (matchingRecords(reply, needles) > 0) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying "
                + java.util.Arrays.toString(needles) + " was recorded within " + tickBudget
                + " ticks. Records of that type since the mark: " + reply
                + " | everything recorded since the mark, in order: "
                + Events.typesOf(events.since(mark)));
    }

    /**
     * The NUMERIC {@code field} of the last record in a {@code since} reply, or {@code "none"} when
     * no record carries one — the counterpart of {@code Events.lastField}, which reads only the
     * string-valued fields and answers {@code null} for a record's own tick.
     */
    private static String lastNumericField(String sinceReply, String field) {
        String last = null;
        for (String record : Events.records(String.valueOf(sinceReply))) {
            double value = Events.number(record, field);
            if (!Double.isNaN(value)) {
                last = Events.text(record, field);
            }
        }
        return last == null ? "none" : last;
    }

    /**
     * The largest absolute value of a numeric {@code field} across every record in a {@code since}
     * reply, or {@code -1} when no record carries one.
     *
     * <p>The WORST and not the last, deliberately: this is asked of a stream that says what the
     * client was commanding over a whole window, and "it ended up centred" is not the same claim as
     * "it never left centre" — the second is the one a still-turning craft has to be judged against.
     * A {@code -1} is distinguishable from a real reading, so "the recorder said nothing" cannot be
     * mistaken for "the cursor was at zero".</p>
     */
    private static double maxAbsField(String sinceReply, String field) {
        double worst = -1.0;
        for (String record : Events.records(String.valueOf(sinceReply))) {
            double value = Events.number(record, field);
            if (!Double.isNaN(value)) {
                // A field that is not a number is not this reading; the -1 above still says so.
                worst = Math.max(worst, Math.abs(value));
            }
        }
        return worst;
    }

    /** How many records of a {@code since} reply carry EVERY one of {@code needles}. */
    private static int matchingRecords(String sinceReply, String... needles) {
        int n = 0;
        // Events.records is the one definition of "a record": it reads the parsed `events` array, so
        // the envelope cannot be counted as one and no local guard is needed.
        for (String record : Events.records(sinceReply)) {
            boolean all = true;
            for (String needle : needles) {
                if (!record.contains(needle)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                n++;
            }
        }
        return n;
    }

    /**
     * Drop an armour stand over the deck and wait for the DECK to take it — the capture as a link,
     * named for this body.
     *
     * <p>What it replaces read three JVM-global statics ({@code resolvedTicks},
     * {@code lastObstacleCount}, {@code lastOnDeck}) after a blind 70-tick wait. Those are written
     * for whichever entity resolved LAST, which on this shared world is as likely to be the bot —
     * teleported onto the ship's footprint by {@code buildShip} — or a previous scenario's stand. A
     * green there could be about a body this scenario never dropped.</p>
     *
     * @return the stand's entity id
     */
    private int dropStandAndAwaitItsCapture(double[] ship) throws Exception {
        Events events = events();
        long dropMark = events.markInstrumented();
        int crewId = readInt(exec("artest vs drop-stand 0 " + ship[0] + " " + (ship[1] + 3)
                + " " + ship[2]), ENTITY_ID);
        // Keyed on the body AND on the ship: the entity needle alone says a deck took him, never
        // which deck, and every reading below is expressed in the taking ship's own frame.
        awaitRecord(events, dropMark, "deck_entered",
                "THIS ship's deck must TAKE the dropped body (entity " + crewId + "): the ship-frame"
                        + " resolver never captured it for this craft, so nothing below is about how"
                        + " a captured body rides THIS deck", 200,
                "\"e\":" + crewId + ",", "\"ship\":\"" + scenarioShipId + "\"");
        // The capture is the link; coming to REST on the deck is the body's own fall settling, which
        // is a value and stays a wait.
        bot().waitTicks(40);
        PlayerShipData resting = PlayerShipData.byId(this::exec, 0, crewId);
        scenario().requireArranged("the dropped body must come to REST on the deck before its drift"
                + " across that deck can mean anything: " + resting.raw(),
                resting.onGround);
        return crewId;
    }

    private double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        assertTrue("expected a number `" + field + "` in: " + json, !Double.isNaN(value));
        return value;
    }

    private int readInt(String json, String field) {
        return Reply.of(json).integer(field);
    }

    private String assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`. Open air, so
        // this ASSERTS rather than digging the shaft it replaces.
        //
        // The grounded-deck scenario in this class is not an exception to that and never was: the
        // "world ground below" it is about is a stone floor IT LAYS under the deck at run time. At a
        // fixed y=64 the seed's own surface sat near the deck as well, so that experiment had two
        // floors and controlled one of them. In the band the only world ground is the one the
        // scenario puts there, which is what makes it an experiment.
        //
        // HEIGHT 24 is the ENVELOPE: ~10 of hull, the deck on top, a body standing and jumping on
        // it, and the room a craft rolled upside down by the mouse sweeps.
        site.requireClear(this::exec, 2, 24,
                "the hull, the deck a body rides, and the air the craft rolls and climbs through");
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        return exec("artest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
    }
}
