package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import com.google.gson.JsonObject;

import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.ShipInfo;

import static org.junit.Assert.assertNotNull;
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
 *       ship before anything is pressed. The seat's own SIT DECISION is asserted first — production
 *       answering, where production answers it, that no ship manages this craft — so the leg's
 *       premise is measured before any silence is read as evidence for it.</li>
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

    /**
     * How far from the seat the client may observably stand, in blocks SQUARED.
     *
     * <p>PRODUCTION'S reach restated: the server drops a block interaction beyond reach plus three.
     * The gate exists so a dropped click is reported as the arrangement it is.</p>
     */
    private static final double WITHIN_REACH_DIST_SQ = 25.0;

    @Override
    protected String subsystem() {
        return "vs-unassembled-craft-orders";
    }

    private static final String DUMMY_ID = "dummyId";

    /** The control ship's build site. */
    /** How long the CLIENT is given to PERFORM a seating the server has already done, in ticks. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    private static final int SHIP_X = 3600, SHIP_Y = FixtureSite.OPEN_AIR_Y, SHIP_Z = 3600;
    /** The subject craft, far enough that the control ship is unloaded while it is flown. */
    private static final int CRAFT_X = 4600, CRAFT_Y = FixtureSite.OPEN_AIR_Y, CRAFT_Z = 4600;

    // `KEY_NOT_ASSEMBLED` lived here — the seat's "this craft is not a ship" notice, by translation
    // key. Nothing asks for the notice now: what it announces is that the craft takes no orders, and
    // that is measured at the seams an order would travel through.

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
        long awayMark = clientEvents().mark();
        exec("tp @a " + (SHIP_X + 600) + " 120 " + (SHIP_Z + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, SHIP_X + 600, SHIP_Z + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The registry's own record of the ship being added, since a mark taken before the assembly
        // was queued: THIS scenario's ship by construction, where a whole-dimension count is
        // answered by every neighbour that ever assembled one.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleShip();
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        // Kept, not discarded: the mark precedes the assembly, so this record is this scenario's
        // own ship, and the mount below has to name it rather than take the first loaded seat.
        String controlShipId = awaitShipSpawned(events, spawnMark,
                "assembly must create a VS ship in the queryable registry (async spawn)");

        long approachMark = clientEvents().mark();
        exec("tp @a " + (SHIP_X + 0.5) + " " + (SHIP_Y + 6) + " " + (SHIP_Z + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, SHIP_X + 0.5, SHIP_Z + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so what is asked of the"
                        + " ship below is only answerable because a client got here");
        // The LOAD of THIS ship is a record (`ship_usable`, later than every unload of it) — the
        // dimension-wide `ship-count` this used to poll was answered by any neighbour's hull. It is
        // asserted here because an unloaded control ship used to red at the seat-mount below as
        // "seat-mount must find the ship's pilot seat", which names the wrong thing entirely.
        awaitShipUsable(events, spawnMark, controlShipId);
        String controlInfo = shipInfoById(controlShipId);
        scenario().requireArranged("the control ship must LOAD with the client standing on it before"
                + " anything is pressed — an unloaded ship has no seat to mount and no computer to"
                + " answer, and the control leg would indict the harness's keys instead: "
                + controlInfo, ShipInfo.isLoaded(controlInfo));

        SeatMount mountInfo = SeatMount.onShip(this::exec, 0, controlShipId);
        scenario().requireArranged("seat-mount must find the ship's pilot seat: " + mountInfo.raw(),
                mountInfo.seatFound);
        scenario().requireArranged("seat-mount must report a dummy id: " + mountInfo.raw(),
                true /* the reader refuses a reply with no dummy */);
        long seatMark = clientEvents().mark();
        String mount = exec("artest player mount-entity " + mountInfo.requireDummyId());
        scenario().requireArranged("the bot must mount the seat dummy: " + mount,
                Reply.of(mount).bool("mounted"));
        // "Let the mount replicate" is a record on the client's own log. The control below presses a
        // command key from that seat, and a client not yet riding routes it elsewhere.
        awaitClientMount(seatMark, "the client must be riding the seat before a command key is"
                + " pressed from it", SEAT_LINK_BUDGET_TICKS, " | server said: " + mount);

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
                Events.anyRecordHas(commanded, "kind", "jump"));

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
            // Asked of the RECORD by name — and the record is TAKEN first, because `await`
            // answers the `events since` ENVELOPE and a record-level accessor reads an
            // envelope's top level, where a record's fields are not. (The reader says exactly
            // that and refuses; the first version of this line learned it from a red.)
            //
            // The needle it replaces was `"seat":"` — a rendering of the first character of a
            // non-empty string value — so it failed for a seat written with a space after the
            // colon and passed for the text appearing in any other field of the envelope,
            // `instruments` included.
            assertNotNull("CONTROL: the client's send must name the seat it resolved: "
                    + sentByClient, Events.text(Events.lastRecord(sentByClient), "seat"));
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        // OFF, as the CLIENT renders him, and this is not tidying: leg 2's whole claim is that a
        // craft it seats him on stays deaf, and a bot still riding leg 1's REAL ship would send its
        // input to that seat and leave leg 2 green for the wrong reason. Ten ticks could only ever
        // be too few, and when they were, nothing said so.
        long offMark = clientEvents().mark();
        exec("artest player dismount");
        awaitClientDismount(offMark, "leg 2 must start with him off the REAL ship — its silence is"
                + " only evidence if there is no other seat his input could have reached",
                SEAT_LINK_BUDGET_TICKS);
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
        scenario().requireArranged("placing the pilot seat failed: " + seat, Reply.of(seat).ok());
        String afc = exec("artest fill 0 " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z
                + " " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z
                + " advancedrocketry:advancedFlightComputer");
        scenario().requireArranged("placing the flight computer failed: " + afc, Reply.of(afc).ok());

        // Link the two WITHOUT assembling: exactly what the assembler leaves behind when the
        // physics mod rejects the spawn. A real failed assembly cannot be arranged deterministically.
        String linked = exec("artest vs seat-link 0 " + CRAFT_X + " " + CRAFT_Y + " " + CRAFT_Z
                + " " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z);
        scenario().requireArranged("the seat must end up LINKED — an unlinked seat is refused for a "
                        + "reason that has nothing to do with this bug: " + linked,
                Reply.of(linked).bool("linked"));
        scenario().requireArranged("CONTROL: and NO ship may manage it — otherwise the craft is simply "
                        + "a ship and the refusal under test would be wrong: " + linked,
                (!Reply.of(linked).bool("managedByShip")));

        standBesideTheSeat();
        emptyTheHand();

        Events events = events();
        long sitMark = events.markInstrumented();
        // The CLIENT's mark beside it: he PERFORMS the mount when the server tells him who is
        // riding what, so the replication half is a link on the other log rather than a poll.
        long sitClientMark = clientEvents().mark();
        bot().interactBlock(CRAFT_X, CRAFT_Y, CRAFT_Z);
        // What production commits when he sits down, in its own source order: Forge fires the
        // right-click before the block sees it, the seat mounts him, and the seat then decides
        // whether a ship manages it — which is what decides whether anything will answer his keys.
        // Two further links stood on this chain — `action_bar_queued` and `status_message_sent` —
        // with a chat check after them, and all three were about the NOTICE the seat answers with.
        // A notice is a rendering. The DECISION it renders is the third link, recorded off
        // production's own `isManagedByShip` verdict at the moment production asks for it.
        events.assertChain(sitMark, "sitting on a craft that never assembled must still SEAT the"
                        + " player and the seat must then decide his controls are dead — the craft"
                        + " is deaf, not a wall", LINK_BUDGET_TICKS,
                "right_click_block", "mount", "pilot_seat_sit_decided");
        // ...and the decision must be the one this leg is built on. The record is present only when
        // the conjunction REACHED that call, i.e. the block has a linked pilot-seat tile — which is
        // the arrangement `seat-link` asserted above — so `managed:false` here is the refusal
        // itself, taken where production took it, rather than a re-derivation of production's test.
        String sat = events.since(sitMark, "pilot_seat_sit_decided");
        // Printed on a GREEN run, because this record is the leg's PREMISE and it is new: a premise
        // whose reading appears only inside a failure message is one nobody has ever looked at.
        System.out.println("[vs-unassembled] sit decisions: " + Events.records(sat));
        assertTrue("the seat must have decided that NO ship manages this craft — with a ship"
                        + " managing it the whole leg below would be measuring a real ship's"
                        + " refusals, and every silence it reads would mean something else: " + sat,
                Events.countRecords(sat, "managed", "false") > 0);

        // The client PERFORMS the mount — his own link, off the mark taken before the click. An
        // ARRANGEMENT claim: a player his client never seated cannot exercise the gate below.
        JsonObject riding = awaitClientMount(sitClientMark,
                "the client must MOUNT the player onto the seat the server already mounted him to"
                        + " (see the chain above) — his keys reach nothing while he is on his feet",
                LINK_BUDGET_TICKS, " serverMountRecord=" + events.since(sitMark, "mount"));
        scenario().requireArranged("...and he must still be on it when it is read: " + riding,
                isRiding(riding));

        // ---- The absences. One mark for all of them, taken before the first key. ---------------
        long deafMark = events.markInstrumented();
        long deafClientMark = clientEvents().mark();

        // Command it: Flight Assist, auto-takeoff, jump — the three edge-triggered keys. Checked
        // FIRST so that a build where BOTH gates leak still reports the command leak (an assertion
        // that never runs measures nothing, and the steering leg alone would abort ahead of it).
        tapKey(Keyboard.KEY_N);
        tapKey(Keyboard.KEY_K);
        tapKey(Keyboard.KEY_J);
        // EXPERIMENT: the claim is "no command arrived within SILENCE_WINDOW_TICKS of the taps" —
        // an absence has no record to link on, only a deadline. The control leg's jump arrived on
        // the same seam, so the window is long enough to have seen one; overshoot only lengthens
        // it, which is the strict direction for an absence.
        bot().waitTicks(SILENCE_WINDOW_TICKS);
        String commands = events.since(deafMark, "pilot_command_received");
        Events.assertInstrumentRan(commands, "pilot_seat_events",
                "a craft that never became a ship is never COMMANDED");
        assertTrue("a craft that never became a ship must not be COMMANDED: Flight Assist / "
                        + "auto-takeoff / jump reached a seat (which then answers the pilot from a "
                        + "flight state that does not exist). The same seam recorded the control "
                        + "leg's jump on a real ship, so this silence is a refusal and not a dead "
                        + "instrument: " + commands,
                Events.countRecords(commands, "who", BOT) == 0);

        // ...and steer it: hold the same flight keys that lift a real ship, for long enough that
        // the client's per-tick sampler has run many times over.
        bot().holdKey(Keyboard.KEY_R);
        bot().holdKey(Keyboard.KEY_W);
        try {
            // STIMULUS: 40 ticks of held flight keys — many samples of the client's per-tick gate,
            // and two of its once-a-second re-assertions of a held intent.
            bot().waitTicks(40);
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
            bot().releaseKey(Keyboard.KEY_R);
        }
        // EXPERIMENT: the tail of the server-side silence. The client-side reads below are complete
        // at the release, but an input sent on the hold's last tick is still in flight to the seat,
        // and "nothing reached a seat" is only a claim once it has had time to land. Overshoot only
        // lengthens the absence window — the strict direction.
        bot().waitTicks(10);

        // THE POSITIVE PRECONDITION, and the reason this leg is worth anything: the client's gate
        // must have been CONSULTED and answered CLOSED while he sat on this seat. Without it, the
        // server's silence below is equally satisfied by a client whose sampler never ran at all —
        // which is the same green on a completely broken build.
        String gate = clientEvents().since(deafClientMark, "ship_pilot_gate_decided");
        Events.assertInstrumentRan(gate, "ship_pilot_gate_events",
                "the client's pilot gate REFUSED this craft every tick");
        assertTrue("the client's own pilot gate must have been consulted while he sat on this craft"
                        + " and answered CLOSED — a silence from the server below means nothing if"
                        + " the client never sampled its keys at all: " + gate,
                // ONE gate record saying both: a closed gate beside a different record that
                // happens to carry ridingDummy is not this claim.
                Events.anyRecordHasAll(gate, "open", "false", "ridingDummy", "true"));

        String clientSends = clientEvents().since(deafClientMark, "pilot_input_sent");
        Events.assertInstrumentRan(clientSends, "pilot_input_sent_events",
                "the client sent NO pilot input for a craft that is not a ship");
        assertTrue("a craft that never became a ship must not be STEERED: the CLIENT put pilot input"
                        + " on the wire for it. The same send seam fired in the control leg on a"
                        + " real ship, so its silence here is the gate refusing: " + clientSends,
                Events.countRecordsWithField(clientSends, "seat") == 0);

        String received = events.since(deafMark, "pilot_input_received");
        Events.assertInstrumentRan(received, "pilot_seat_events",
                "no pilot input reached a seat from a craft that is not a ship");
        assertTrue("...and nothing may have reached a seat on the server either. The seat's link is"
                        + " a build-time intention that survives a rejected assembly; it is not a"
                        + " ship: " + received,
                Events.countRecords(received, "who", BOT) == 0);

        // Two more absences stood here — no `action_bar_queued` and no `status_message_sent` for him
        // after the keys — i.e. "the craft answers none of the keys pressed at it". They are gone
        // with the rest of the message layer: what the keys must not reach is the seat and the wire,
        // and the three absences immediately above say exactly that, off the seams an order travels
        // rather than off the reply it would have produced.

        assertTrue("the refused pilot must still be SEATED — the craft is deaf, not ejecting",
                isRiding(bot().reportRidingEntity()));

        exec("artest player dismount");
    }

    // ---- Instruments ----------------------------------------------------------------------------

    /**
     * One press-and-release of a key binding, as the keyboard would deliver it.
     *
     * <p>No key-up time follows the release: every caller taps a DIFFERENT binding next (or none),
     * and independent bindings do not need to see each other released. Tapping the SAME key twice
     * in a row would need a client tick between the release and the next press to be two edges.</p>
     */
    private void tapKey(int keyCode) throws Exception {
        bot().holdKey(keyCode);
        // STIMULUS: held across client ticks, so the edge-triggered handler samples it pressed.
        bot().waitTicks(2);
        bot().releaseKey(keyCode);
    }


    // ---- Arrangement helpers ---------------------------------------------------------------------

    /** Stood on a floor his client holds, then read ONCE. */
    private void standBesideTheSeat() throws Exception {
        standOnFloorTheClientHolds(CRAFT_X + 0.5, CRAFT_Y, CRAFT_Z + 1.5, 0f, 0f,
                "the player must be stood beside the seat");
        JsonObject state = bot().reportState();
        double dx = state.get("playerX").getAsDouble() - (CRAFT_X + 0.5);
        double dy = state.get("playerY").getAsDouble() - CRAFT_Y;
        double dz = state.get("playerZ").getAsDouble() - (CRAFT_Z + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;
        scenario().requireArranged("the client must observably stand within reach of the seat, or the "
                + "right-click is dropped before the block sees it. state=" + state, distSq < WITHIN_REACH_DIST_SQ);
    }

    /** Server-side clear + client-observed empty hand (a held stack can eat the right-click). */
    private void emptyTheHand() throws Exception {
        emptyTheHandOnClient("the bot's hand must be observably empty");
    }

    /**
     * FIRST link, and it ASSERTS where the warmup+fill pair DUG. HEIGHT 16: the hull, plus the air
     * above it this scenario watches for a craft that must NOT move.
     */
    private String assembleShip() throws Exception {
        return RocketFixture.assembleAt(FixtureSite.openAir(0, SHIP_X, SHIP_Z), this::exec,
                "with-pilot-seat", 2, 16,
                "the loose craft that must take no orders, and the air it must not climb into");
    }

    // ---- Observation helpers ---------------------------------------------------------------------

    // `awaitRiding(samples, want)` lived here, with a javadoc claiming "a CLIENT-rendered mount has
    // no record of its own — the position writers are server-side". That was wrong about this tree:
    // `MixinEntityPositionWriters` is in the COMMON mixin list, so the client's own `startRiding`
    // records `mount` in the client log. The base's `awaitClientMount` waits for that record.

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

}
