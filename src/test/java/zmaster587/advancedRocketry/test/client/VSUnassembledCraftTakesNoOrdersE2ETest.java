package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertTrue;

/**
 * A craft that never became a ship takes NO orders from the player sitting in it: not steering, not
 * a Flight-Assist / auto-takeoff / jump command, and not the cockpit camera. The CLIENT half of the
 * rule that an unassembled craft does not accept pilot input.
 *
 * <p>The pilot seat's flight-computer link is a BUILD-TIME INTENTION: the assembler records it
 * before the physics mod has confirmed the spawn, and a rejected spawn (over-size flood, bedrock
 * contact) leaves it set forever. Every client gate that asked the link ALONE therefore asserted a
 * flight state that did not exist — the steering keys were scoped into the cockpit, the per-tick
 * input was shipped to the server, and each edge-triggered command key (Flight Assist, auto-takeoff,
 * jump) reached the ship's computer and was answered by its gate instead of being refused as "not a
 * ship". A player watched a craft that does nothing, forever, tell him it was flying.</p>
 *
 * <p>The test is deliberately built around its own CONTROL, because both of its instruments can
 * fail silent:</p>
 * <ul>
 *   <li><b>Leg 1 (control, a REAL ship)</b> — the same key presses and the same event types on an
 *       assembled ship MUST fire. This proves the harness's injected key actually reaches an
 *       edge-triggered handler, that the client's gate answers TRUE on a real ship, and — the part
 *       an absence needs most — that every observation point leg 2 reads a SILENCE from has
 *       executed at least once in this JVM.</li>
 *   <li><b>Leg 2 (subject, a linked craft that never assembled)</b> — arranged by the
 *       {@code artest vs seat-link} probe, which links a seat WITHOUT assembling (the only
 *       deterministic way to reproduce the post-failure state), and verified to be linked-but-not-a-
 *       ship before anything is pressed. The seat's own "not assembled" notice is asserted first, so
 *       the messaging instruments are known to speak before silence is used as evidence.</li>
 * </ul>
 *
 * <h2>Why every negative here is asked of the LOG</h2>
 *
 * <p>"No packet arrived" is satisfied by a client that pressed nothing, by a sampler that never ran,
 * and by an instrument that was never installed — three different sentences that a counter which
 * did not move renders identically. So each absence below is a {@code since(mark, type)} with a
 * stated deadline, {@link Events#assertInstrumentRan} beside it naming the observation point whose
 * silence is being read, and — for the steering leg — the client's own {@code
 * ship_pilot_gate_decided} records proving the gate was EXERCISED and answered CLOSED, rather than
 * never consulted at all. The JVM-wide delivery counters this used to read could say none of that:
 * they counted every seat in the world and named neither the seat, nor the kind, nor the side.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSUnassembledCraftTakesNoOrdersE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-unassembled-craft-orders";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    /** The control ship's build site. */
    private static final int SHIP_X = 3600, SHIP_Y = 64, SHIP_Z = 3600;
    /** The subject craft, far enough that the control ship is unloaded while it is flown. */
    private static final int CRAFT_X = 4600, CRAFT_Y = 71, CRAFT_Z = 4600;

    /** The seat's own "this craft is not a ship" notice, by translation key — the message's
     *  identity, where the rendered English is one translation of it. */
    private static final String KEY_NOT_ASSEMBLED = "msg.pilotseat.notassembled";

    /** How long one link may take. A deadline for discrete events, not a settling time. */
    private static final int LINK_BUDGET_TICKS = 200;

    /**
     * How long an absence is given to become provable. The client re-samples its intent every tick
     * and re-asserts a held one about once a second, so a window this wide covers many chances for a
     * leak to appear — and unlike the counter reads it replaces, an absence over it is a stated
     * deadline rather than "we looked and the number was the same".
     */
    private static final int SILENCE_WINDOW_TICKS = 60;

    /** The account the client harness plays under; every record is keyed on it. */
    private static final String BOT = "ForgeTestClient";

    @Test
    public void aCraftThatNeverBecameAShipTakesNoOrdersFromItsPilot() throws Exception {

        controlARealShipDoesTakeOrders();
        theUnassembledCraftDoesNot();
    }

    // ---- Leg 1: the control ---------------------------------------------------------------------

    /**
     * A REAL assembled ship, driven by the same keys and read through the same event types as leg 2.
     * Everything here must FIRE; it is the proof that leg 2's silences measure the production gate,
     * and it is what puts every one of leg 2's observation points on record as having executed.
     */
    private void controlARealShipDoesTakeOrders() throws Exception {
        exec("tp @a " + (SHIP_X + 600) + " 120 " + (SHIP_Z + 600) + " 0 0");
        bot().waitTicks(10);

        // The registry's own record of the ship being added, since a mark taken before the assembly
        // was queued: THIS scenario's ship by construction, where a whole-dimension count is
        // answered by every neighbour that ever assembled one.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleShip();
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        // Kept, not discarded: the mark precedes the assembly, so this record is this scenario's
        // own ship, and the mount below has to name it rather than take the first loaded seat.
        String controlShipId = awaitShipSpawned(events, spawnMark,
                "assembly must create a VS ship in the queryable registry (async spawn)");
        bot().waitTicks(40);

        exec("tp @a " + (SHIP_X + 0.5) + " " + (SHIP_Y + 6) + " " + (SHIP_Z + 0.5) + " 0 0");
        bot().waitTicks(20);
        // The LOAD has no event of its own, so this stays a bounded probe read — but it is ASSERTED
        // now: an unloaded control ship used to red at the seat-mount below as "seat-mount must find
        // the ship's pilot seat", which names the wrong thing entirely.
        int loaded = count("ship-count");
        for (int i = 0; i < 40 && loaded < 1; i++) {
            bot().waitTicks(5);
            loaded = count("ship-count");
        }
        scenario().requireArranged("the control ship must LOAD with the client standing on it before"
                + " anything is pressed — an unloaded ship has no seat to mount and no computer to"
                + " answer, and the control leg would indict the harness's keys instead (loaded="
                + loaded + ")", loaded >= 1);

        String mountInfo = exec("artest vs seat-mount 0 id " + controlShipId);
        scenario().requireArranged("seat-mount must find the ship's pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        scenario().requireArranged("seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        scenario().requireArranged("the bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat

        // A command key: the jump key, chosen because a ship with no hyperdrive answers it with a
        // refusal and changes no flight state — so this control cannot perturb anything downstream.
        long commandMark = events.markInstrumented();
        tapKey(Keyboard.KEY_J);
        String commanded = events.await(commandMark, "pilot_command_received",
                "CONTROL: pressing a command key while piloting a REAL ship must reach the ship's"
                        + " own seat. If this fails, the harness's injected key never reaches the"
                        + " edge-triggered handler and leg 2 below proves nothing at all",
                LINK_BUDGET_TICKS);
        assertTrue("CONTROL: the command that arrived must be the JUMP the test pressed — a seat"
                        + " answering some other key is not the control this leg needs: " + commanded,
                commanded.contains("\"kind\":\"jump\""));

        // And the per-tick steering path, the other gate leg 2 measures. Both ends are asserted,
        // separately: the two logs are joined only by game tick, so their ORDER within one tick is
        // undefined and nothing here claims one.
        long inputMark = events.markInstrumented();
        long inputClientMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            events.await(inputMark, "pilot_input_received",
                    "CONTROL: holding a flight key while piloting a REAL ship must deliver pilot"
                            + " input to the ship's seat on the server", LINK_BUDGET_TICKS);
            String sentByClient = clientEvents().await(inputClientMark, "pilot_input_sent",
                    "CONTROL: and the CLIENT must be the thing that sent it —"
                            + " this is the send seam whose silence leg 2 reads as a refusal",
                    LINK_BUDGET_TICKS);
            assertTrue("CONTROL: the client's send must name the seat it resolved: " + sentByClient,
                    sentByClient.contains("\"seat\":\""));
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        exec("artest player dismount");
        bot().waitTicks(10);
    }

    // ---- Leg 2: the subject ---------------------------------------------------------------------

    /** The craft whose assembly never produced a ship: linked, seated in, and completely deaf. */
    private void theUnassembledCraftDoesNot() throws Exception {
        exec("artest chunk warmup 0 " + ((CRAFT_X - 8) >> 4) + " " + ((CRAFT_Z - 8) >> 4)
                + " " + ((CRAFT_X + 8) >> 4) + " " + ((CRAFT_Z + 8) >> 4));
        exec("artest fill 0 " + (CRAFT_X - 3) + " " + (CRAFT_Y - 1) + " " + (CRAFT_Z - 3)
                + " " + (CRAFT_X + 3) + " " + (CRAFT_Y - 1) + " " + (CRAFT_Z + 3) + " minecraft:obsidian");
        exec("artest fill 0 " + (CRAFT_X - 3) + " " + CRAFT_Y + " " + (CRAFT_Z - 3)
                + " " + (CRAFT_X + 3) + " " + (CRAFT_Y + 4) + " " + (CRAFT_Z + 3) + " minecraft:air");
        String seat = exec("artest fill 0 " + CRAFT_X + " " + CRAFT_Y + " " + CRAFT_Z
                + " " + CRAFT_X + " " + CRAFT_Y + " " + CRAFT_Z + " advancedrocketry:pilotSeat");
        scenario().requireArranged("placing the pilot seat failed: " + seat, seat.contains("\"ok\":true"));
        String afc = exec("artest fill 0 " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z
                + " " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z
                + " advancedrocketry:advancedFlightComputer");
        scenario().requireArranged("placing the flight computer failed: " + afc, afc.contains("\"ok\":true"));

        // Link the two WITHOUT assembling: exactly what the assembler leaves behind when the
        // physics mod rejects the spawn. A real failed assembly cannot be arranged deterministically.
        String linked = exec("artest vs seat-link 0 " + CRAFT_X + " " + CRAFT_Y + " " + CRAFT_Z
                + " " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z);
        scenario().requireArranged("the seat must end up LINKED — an unlinked seat is refused for a "
                        + "reason that has nothing to do with this bug: " + linked,
                linked.contains("\"linked\":true"));
        scenario().requireArranged("CONTROL: and NO ship may manage it — otherwise the craft is simply "
                        + "a ship and the refusal under test would be wrong: " + linked,
                linked.contains("\"managedByShip\":false"));

        standBesideTheSeat();
        emptyTheHand();

        Events events = events();
        long sitMark = events.markInstrumented();
        long sitClientMark = clientEvents().mark();
        bot().interactBlock(CRAFT_X, CRAFT_Y, CRAFT_Z);
        // The seat's own notice, as the chain production commits in its own source order: Forge
        // fires the right-click before the block sees it, the seat mounts him, it decides the craft
        // is not a ship and DEFERS the notice past the mount packet's tracker flush, and the drain
        // sends it. This is also the positive control for the messaging instruments — the silence
        // asserted at the end of this leg is read from the same two types.
        events.assertChain(sitMark, "sitting on a craft that never assembled must seat the player"
                        + " and answer him with the \"not assembled\" notice", LINK_BUDGET_TICKS,
                "right_click_block", "mount", "action_bar_queued", "status_message_sent");
        String queued = events.since(sitMark, "action_bar_queued");
        assertTrue("the notice the seat queues must be keyed on " + KEY_NOT_ASSEMBLED + ": " + queued,
                queued.contains("\"key\":\"" + KEY_NOT_ASSEMBLED + "\""));
        String shown = awaitClientChat(sitClientMark, "not assembled", LINK_BUDGET_TICKS,
                "the \"not assembled\" notice must reach the pilot's own HUD");
        assertTrue("the line the client was handed must say the ship is not assembled: " + shown,
                shown.toLowerCase(Locale.ROOT).contains("not assembled"));

        JsonObject riding = awaitRiding(30, true);
        scenario().requireArranged("the client must RENDER the player on the seat the server already"
                + " mounted him to (see the chain above): " + riding, isRiding(riding));

        // ---- The absences. One mark for all of them, taken before the first key. ---------------
        long deafMark = events.markInstrumented();
        long deafClientMark = clientEvents().mark();

        // Command it: Flight Assist, auto-takeoff, jump — the three edge-triggered keys. Checked
        // FIRST so that a build where BOTH gates leak still reports the command leak (an assertion
        // that never runs measures nothing, and the steering leg alone would abort ahead of it).
        tapKey(Keyboard.KEY_N);
        tapKey(Keyboard.KEY_K);
        tapKey(Keyboard.KEY_J);
        bot().waitTicks(SILENCE_WINDOW_TICKS);
        String commands = events.since(deafMark, "pilot_command_received");
        Events.assertInstrumentRan(commands, "pilot_seat_events",
                "a craft that never became a ship is never COMMANDED");
        assertTrue("a craft that never became a ship must not be COMMANDED: Flight Assist / "
                        + "auto-takeoff / jump reached a seat (which then answers the pilot from a "
                        + "flight state that does not exist). The same seam recorded the control "
                        + "leg's jump on a real ship, so this silence is a refusal and not a dead "
                        + "instrument: " + commands,
                Events.countRecords(commands, "\"who\":\"" + BOT + "\"") == 0);

        // ...and steer it: hold the same flight keys that lift a real ship, for long enough that
        // the client's per-tick sampler has run many times over.
        bot().holdKey(Keyboard.KEY_R);
        bot().holdKey(Keyboard.KEY_W);
        try {
            bot().waitTicks(40);
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
            bot().releaseKey(Keyboard.KEY_R);
        }
        bot().waitTicks(10);

        // THE POSITIVE PRECONDITION, and the reason this leg is worth anything: the client's gate
        // must have been CONSULTED and answered CLOSED while he sat on this seat. Without it, the
        // server's silence below is equally satisfied by a client whose sampler never ran at all —
        // which is the same green on a completely broken build.
        String gate = String.valueOf(bot().eventsSince(deafClientMark, "ship_pilot_gate_decided"));
        Events.assertInstrumentRan(gate, "ship_pilot_gate_events",
                "the client's pilot gate REFUSED this craft every tick");
        assertTrue("the client's own pilot gate must have been consulted while he sat on this craft"
                        + " and answered CLOSED — a silence from the server below means nothing if"
                        + " the client never sampled its keys at all: " + gate,
                Events.countRecords(gate, "\"open\":false") > 0
                        && gate.contains("\"ridingDummy\":true"));

        String clientSends = String.valueOf(bot().eventsSince(deafClientMark, "pilot_input_sent"));
        Events.assertInstrumentRan(clientSends, "pilot_input_sent_events",
                "the client sent NO pilot input for a craft that is not a ship");
        assertTrue("a craft that never became a ship must not be STEERED: the CLIENT put pilot input"
                        + " on the wire for it. The same send seam fired in the control leg on a"
                        + " real ship, so its silence here is the gate refusing: " + clientSends,
                Events.countRecords(clientSends, "\"seat\":\"") == 0);

        String received = events.since(deafMark, "pilot_input_received");
        Events.assertInstrumentRan(received, "pilot_seat_events",
                "no pilot input reached a seat from a craft that is not a ship");
        assertTrue("...and nothing may have reached a seat on the server either. The seat's link is"
                        + " a build-time intention that survives a rejected assembly; it is not a"
                        + " ship: " + received,
                Events.countRecords(received, "\"who\":\"" + BOT + "\"") == 0);

        // The player must get no answer from a gate that should never have been consulted — the
        // only message this craft owes him is the notice at sit-down, which is already asserted
        // above and is what puts both message instruments on record.
        String lateQueued = events.since(deafMark, "action_bar_queued");
        String lateSent = events.since(deafMark, "status_message_sent");
        Events.assertInstrumentRan(lateQueued, "action_bar_events",
                "the deaf craft answers none of the keys pressed at it");
        Events.assertInstrumentRan(lateSent, "status_message_events",
                "the deaf craft answers none of the keys pressed at it");
        assertTrue("the craft must answer NONE of the keys pressed at it — the only message it owes"
                        + " him is the \"not assembled\" notice at sit-down: " + lateQueued,
                Events.countRecords(lateQueued, "\"who\":\"" + BOT + "\"") == 0);
        assertTrue("...on either door to his action bar: " + lateSent,
                Events.countRecords(lateSent, "\"who\":\"" + BOT + "\"") == 0);

        assertTrue("the refused pilot must still be SEATED — the craft is deaf, not ejecting",
                isRiding(bot().reportRidingEntity()));

        exec("artest player dismount");
    }

    // ---- Instruments ----------------------------------------------------------------------------

    /** One press-and-release of a key binding, as the keyboard would deliver it. */
    private void tapKey(int keyCode) throws Exception {
        bot().holdKey(keyCode);
        bot().waitTicks(2);
        bot().releaseKey(keyCode);
        bot().waitTicks(4);
    }


    // ---- Arrangement helpers ---------------------------------------------------------------------

    private void standBesideTheSeat() throws Exception {
        double distSq = Double.POSITIVE_INFINITY;
        JsonObject state = null;
        for (int attempt = 0; attempt < 6 && distSq >= 25.0; attempt++) {
            exec("tp @a " + (CRAFT_X + 0.5) + " " + CRAFT_Y + " " + (CRAFT_Z + 1.5) + " 0 0");
            bot().waitTicks(20);
            state = bot().reportState();
            if (state.has("worldReady") && state.get("worldReady").getAsBoolean()) {
                double dx = state.get("playerX").getAsDouble() - (CRAFT_X + 0.5);
                double dy = state.get("playerY").getAsDouble() - CRAFT_Y;
                double dz = state.get("playerZ").getAsDouble() - (CRAFT_Z + 0.5);
                distSq = dx * dx + dy * dy + dz * dz;
            }
        }
        scenario().requireArranged("the client must observably stand within reach of the seat, or the "
                + "right-click is dropped before the block sees it. state=" + state, distSq < 25.0);
    }

    /** Server-side clear + client-observed empty hand (a held stack can eat the right-click). */
    private void emptyTheHand() throws Exception {
        exec("clear @a");
        bot().selectHotbar(0);
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonObject items = bot().reportPlayerItems();
            if (items.has("worldReady") && items.get("worldReady").getAsBoolean() && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
            bot().waitTicks(5);
        }
        scenario().requireArranged("the bot's hand must be observably empty; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }

    private String assembleShip() throws Exception {
        int cx1 = (SHIP_X - 2) >> 4, cz1 = (SHIP_Z - 2) >> 4;
        int cx2 = (SHIP_X + 7) >> 4, cz2 = (SHIP_Z + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (SHIP_X - 2) + " " + (SHIP_Y + 1) + " " + (SHIP_Z - 2)
                        + " " + (SHIP_X + 7) + " " + (SHIP_Y + 10) + " " + (SHIP_Z + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + SHIP_X + " " + SHIP_Y + " " + SHIP_Z
                + " with-pilot-seat");
        assertTrue("fixture (with-pilot-seat) failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    // ---- Observation helpers ---------------------------------------------------------------------

    /** Poll until the client reports riding == {@code want} (bounded); returns the last sample.
     *  A CLIENT-rendered mount has no record of its own — the position writers are server-side — so
     *  this stays a poll, and its call site states the server link it is following. */
    private JsonObject awaitRiding(int samples, boolean want) throws Exception {
        JsonObject riding = null;
        for (int i = 0; i < samples; i++) {
            riding = bot().reportRidingEntity();
            if (isRiding(riding) == want) {
                break;
            }
            bot().waitTicks(5);
        }
        return riding;
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

}
