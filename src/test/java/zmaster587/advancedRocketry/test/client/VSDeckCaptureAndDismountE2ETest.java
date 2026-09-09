package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The two open tier-2 playtest bugs, pinned against a REAL CLIENT PLAYER on a REAL assembled ship -
 * the subject that can actually exhibit them. Both were reported from a hands-on playtest and neither
 * is reproducible by the earlier suite, which read an armour stand's position through a SERVER probe:
 * a server-only body cannot fall through a deck on the client, and a probe cannot see where the client
 * renders the player. This class drives and observes the client, so it can.
 *
 * <ul>
 *   <li><b>A walking client player on a GROUNDED ship's deck stays on it, not through it.</b> The
 *       maintainer stood on a docked ship and fell through the deck. The subject here is the bot
 *       itself - a walking client whose own client resolves its movement - and the observation is the
 *       Y its client renders, cross-checked against where the server holds it (the honest oracle).</li>
 *   <li><b>Standing up from the pilot seat while hovering keeps the ship up and the pilot aboard.</b>
 *       The maintainer hovered, stood up with Shift, and both fell: the ship dropped and he was left
 *       in the world. The ship's hold is driven by live pilot input, so it dies at dismount, and the
 *       dismounted player is handed to a capture path at the exact moment the deck starts falling.</li>
 * </ul>
 *
 *
 * <h2>One client for all twelve scenarios</h2>
 *
 * <p>Measured 2026-08-07 over the whole ship client tier: these twelve scenarios cost <b>19.4
 * minutes across twelve client boots</b> — 97 s each, of which the body is a small minority. They
 * were the tier's wall-clock floor. Sharing one harness is what removes it, and the base coordinates
 * below are unchanged: the scenarios already stood 100 blocks apart, which is a plot allocator by
 * hand, and each one's ground is the ground its green runs were taken on.</p>
 *
 * <p>Two gates had to become scoped to survive a shared world, both marked at their call sites: the
 * pilot seat is now located INSIDE this scenario's own ship ({@code vs find-seat} at its base)
 * instead of taking the first pilot seat in the world, and "has the ship unloaded?" reads this
 * scenario's own base rather than a whole-dimension ship count that a neighbour's ship answers.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSDeckCaptureAndDismountE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-deck-capture";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");
    private static final Pattern PLAYER_Y = Pattern.compile("\"playerY\":(-?[0-9.E\\-]+)");
    private static final Pattern OBSTACLES = Pattern.compile("\"shipSupportObstacles\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SEAT_X = Pattern.compile("\"seatX\":(-?\\d+)");
    private static final Pattern SEAT_Y = Pattern.compile("\"seatY\":(-?\\d+)");
    private static final Pattern SEAT_Z = Pattern.compile("\"seatZ\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-deck";

    /**
     * How long one link of a deck-capture or ship-lifecycle chain may take. A DEADLINE for a
     * discrete event, not a guess at how long a value takes to settle: production either captures a
     * body, releases it, loads a ship or unloads one — 240 ticks is generous against an eight-fork
     * load and still fails a scenario that never gets there rather than waiting out a budget.
     */
    private static final int DECK_LINK_BUDGET_TICKS = 240;

    // The bugs this class exists for are all CLIENT facts — a player falls through a deck on his OWN
    // client while the server holds him on it, which is exactly why an armour stand read through a
    // server probe could never reproduce them. So every wait below reads the base's
    // {@link #clientEvents()}, not {@link #events()}.

    /**
     * Wait for a ship-lifecycle record naming THIS scenario's ship, or fail printing every such
     * record that DID arrive.
     *
     * <p>{@link Events#await} filters by type only, and on a shared world every neighbour's ship
     * loads and unloads through the same seam — so a bare await would be answered by somebody else's
     * craft. The physics uuid is the join: {@code ship_loaded} / {@code ship_unloaded} /
     * {@code ship_removed} carry {@code vsShip} = {@code ShipData.getUuid()}, and the id this
     * scenario holds is the {@code vsShip} of its own {@code ship_spawned} record — the same field,
     * written from the same uuid at the registry's add.</p>
     *
     * <p>A payload filter belongs on {@link Events} itself; it is written here because this wave does
     * not extend the shared base.</p>
     */
    private String awaitThisShip(Events events, long mark, String type, String what)
            throws Exception {
        String reply = "";
        for (int waited = 0; waited <= DECK_LINK_BUDGET_TICKS; waited += 5) {
            reply = events.since(mark, type);
            if (Events.countRecords(reply, "\"vsShip\":\"" + scenarioShipId + "\"") > 0) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` naming this scenario's ship ("
                + scenarioShipId + ") was recorded within " + DECK_LINK_BUDGET_TICKS + " ticks."
                + " Records of that type in the window (they belong to other ships): " + reply);
    }

    /**
     * THIS scenario's ship, by identity — read by {@link #buildShip} off the registry's own
     * {@code ship_spawned} record for the assembly it just queued, and the address every later
     * question uses. A scenario that builds its own ship is TOLD which ship that is; nothing here
     * re-derives that from a position.
     *
     * <p>Every question here used to be "the ship nearest my base, within a radius". That radius is a
     * mitigation and not an identity: these scenarios deliberately tumble, invert and hover their
     * ship, and on a shared client the neighbour built by another scenario is a candidate the whole
     * time. An id has no distance term to be wrong about.</p>
     */
    private String scenarioShipId;

    // ---- Bug: a walking client player on a grounded deck falls through it -----------------------

    @Test
    public void aRealClientPlayerOnAGroundedDeckStaysOnItInsteadOfFallingThrough() throws Exception {
        final int bx = 3620, by = 64, bz = 3620;

        // Grounded on purpose: a freshly assembled ship has physics disabled, so it rests where it was
        // built. Its world AABB spans from the deck down to the keel and overlaps the terrain beneath -
        // the exact overlap the playtest fell through - and the deck sits several blocks above the ground,
        // so a fall-through is an unmistakable multi-block drop, not a one-block ambiguity.
        double[] ship = buildShip(bx, by, bz);

        // The subject is the REAL client player. Drop the bot onto the deck and let its OWN client
        // resolve the landing (this is the thing that breaks; an armour stand read via a server probe
        // is not). Mirrors the crew test's drop-and-settle.
        // The landing is a LINK, not a duration: his own client's resolver either takes him onto the
        // deck or it does not, and 80 ticks was a guess at how long that takes. The mark goes before
        // the teleport, so nothing can happen between the stimulus and the read.
        //
        // `deck_captured` is a per-tick COMMIT, so a body the build step already left standing on
        // this deck satisfies the await at once. That is deliberate and it is still the right link:
        // what this scenario's bug looks like is NO capture on the client at all while the server
        // holds him — and a capture that existed and was then lost shows up in the release absence
        // over the sink window below, which is where "he kept sinking" would have to appear.
        Events clientEvents = clientEvents();
        long landingMark = clientEvents.mark();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        // Carrying this scenario's ship: the record names the hull that took the body, and this class
        // shares its world — a type-only wait returns on a sibling scenario's capture and calls the
        // fall-through "resolved".
        String landing = clientEvents.awaitCarrying(landingMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "the player's OWN client must resolve him on the deck of THIS grounded ship — a"
                        + " fall-through leaves the client with no capture at all, which is the fault"
                        + " this scenario exists for", DECK_LINK_BUDGET_TICKS);
        System.out.println("[deckcap] grounded client capture=" + landing);

        // Server oracle: does the server capture the standing player on the deck at all, and is the deck
        // solid under his feet in the ship frame? deck-capture prints the whole handles() decision.
        String server = exec("artest vs player-ship-data");
        String capture = deckCaptureOfThisShip(scenarioShipId,
                "the server must resolve him on THIS scenario's grounded ship");
        double serverY = readDouble(server, PLAYER_Y);
        System.out.println("[deckcap] grounded server=" + server);
        System.out.println("[deckcap] grounded capture=" + capture);
        assertTrue("server must recognise the client player as aboard the grounded ship: " + server,
                server.contains("\"shipLoaded\":true"));
        ShipIdentity.assertAboardShip(server, scenarioShipId,
                "the server must place him inside THIS scenario's grounded ship");
        assertTrue("server must resolve the player in the ship frame, not hand him to vanilla: " + capture,
                capture.contains("\"verdict\":true"));
        assertTrue("the deck must be solid under his feet in the ship frame (>0), else he falls "
                + "through: " + capture, readInt(capture, OBSTACLES) > 0);
        assertTrue("a client player standing on the deck must be on the ground: " + server,
                server.contains("\"playerOnGround\":true"));

        // Client observation: where does the player's OWN client render him? A client fall-through
        // leaves his client Y well below where the server is holding him on the deck.
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] grounded serverY=" + serverY + " clientY=" + clientY);
        assertTrue("the client must render the player ON the deck where the server holds him, not "
                + "fallen through it: serverY=" + serverY + " clientY=" + clientY,
                Math.abs(clientY - serverY) < 2.0);

        // And he must not keep sinking through it over time. The window stays a window — expiry is
        // not the failure — but the client's resolver records EVERY release with the gate that
        // performed it, so "he was not dropped during it" is now an absence in the log rather than
        // an inference from two Y samples. An absence only means something once the instrument has
        // announced itself, which is what assertInstrumentRan is for.
        long sinkMark = clientEvents.mark();
        bot().waitTicks(60);
        double clientYLater = bot().reportState().get("playerY").getAsDouble();
        String sinkReleases = clientEvents.since(sinkMark, "deck_released");
        Events.assertInstrumentRan(sinkReleases, "deck_capture_events",
                "the client held the player on the deck for the whole window");
        assertTrue("the client player must stay on the deck, not sink through it: " + clientY + " -> "
                + clientYLater, clientY - clientYLater < 1.5);
        assertTrue("the client must not let go of a player standing still on a grounded deck; a"
                + " release here names the gate that dropped him: " + sinkReleases,
                Events.countRecords(sinkReleases, "\"reason\"") == 0);
    }

    // ---- Bug: dismounting mid-hover drops the ship and the pilot --------------------------------

    @Test
    public void standingUpWhileHoveringKeepsTheShipUpAndThePilotOnTheDeck() throws Exception {
        final int bx = 3720, by = 64, bz = 3720;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20); // let the seated idle pilot's hold stabilise the ship

        // Lift into a real hover with the pilot's own vertical-up key.
        double startY = readDouble(shipInfo(), POS_Y);
        bot().holdKey(Keyboard.KEY_R);
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 200-iteration budget
            // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(shipInfo(), POS_Y),
                    y -> y - startY >= 3.0, 2, 200);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double liftedY = lift.value;
        assertTrue("the pilot must be able to lift the ship off the ground: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        bot().waitTicks(10);

        double shipYPre = readDouble(shipInfo(), POS_Y);

        // Dismount exactly as the maintainer did: the real sneak key. (While seated it also feeds the
        // flight brake, but a held sneak still triggers vanilla's dismount.) Confirm on the CLIENT that
        // the player left the seat; fall back to the server dismount only if the key path did not fire.
        // The un-seating is recorded where production performs it — at
        // {@code Entity.dismountRidingEntity}, with the CALLER that performed it — so the wait is on
        // that record rather than on the client's replicated riding flag, and the record's own
        // caller trail says which of the two routes below actually got him out. (The client flag is
        // the replication of a server write; polling it measured the round trip as much as the
        // dismount.)
        Events events = events();
        long dismountMark = events.markInstrumented();
        Events clientEvents = clientEvents();
        long clientDismountMark = clientEvents.mark();
        boolean dismounted = false;
        String dismountPath = "sneak-key";
        bot().holdKey(Keyboard.KEY_LSHIFT);
        for (int i = 0; i < 40 && !dismounted; i++) {
            bot().waitTicks(2);
            dismounted = Events.countRecords(events.since(dismountMark, "dismount"), "\"mount\"") > 0;
        }
        bot().releaseKey(Keyboard.KEY_LSHIFT);
        String serverDismount = "";
        if (!dismounted) {
            System.out.println("[deckcap] sneak key did not dismount; using server dismount");
            dismountPath = "sneak-key-then-server-dismount";
            serverDismount = exec("artest player dismount").replace('\n', ' ');
        }
        // Which PATH was taken is the diagnosis, and this message used to be a bare sentence. Both
        // paths failing is a different fault from the key path alone failing: the first says the
        // player cannot be un-seated at all, the second says only the real key route is dead, which
        // is the one a player actually uses and the one this scenario is about. The record's `by`
        // trail names the un-seater — vanilla's own updateRidden for the sneak key, the probe verb
        // for the fallback — so the two are now told apart by the log instead of by which branch the
        // test happened to take.
        String dismountRecord;
        try {
            dismountRecord = events.await(dismountMark, "dismount",
                    "the pilot must actually leave the seat, and NEITHER route got him out. tried="
                            + dismountPath
                            + (serverDismount.isEmpty() ? "" : " serverDismount=" + serverDismount),
                    DECK_LINK_BUDGET_TICKS);
        } catch (AssertionError neverDismounted) {
            throw new AssertionError(neverDismounted.getMessage()
                    + " | riding=" + bot().reportRidingEntity()
                    + " | capture=" + exec("artest vs deck-capture"), neverDismounted);
        }
        System.out.println("[deckcap] dismount route tried=" + dismountPath
                + " unSeatedBy=" + Events.lastField(dismountRecord, "by"));

        // Let the now-unmanned ship reveal whether it holds or falls — waited on the flight
        // computer's OWN decision rather than on 40 ticks. With no pilot input it either commands a
        // hover or returns inert, and until that record existed a 2-block drop could not say
        // "station-keeping was off" apart from "the hold engaged and under-thrust".
        String hold = events.await(dismountMark, "unmanned_hold_decided",
                "the flight computer must take an unmanned decision once the pilot stands up — with"
                        + " no record of one, a ship that then falls cannot be told from a ship whose"
                        + " computer never noticed it was unmanned", DECK_LINK_BUDGET_TICKS);
        bot().waitTicks(40); // and then let a ship that is NOT holding visibly fall
        String info = shipInfo();
        double shipYPost = readDouble(info, POS_Y);
        double velYPost = readDouble(info, VEL_Y);
        String server = exec("artest vs player-ship-data");
        String capture = deckCaptureOfThisShip(scenarioShipId,
                "the dismounted pilot must be resolved on the deck of the ship he was flying");
        double serverY = readDouble(server, PLAYER_Y);
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] dismount shipY " + shipYPre + "->" + shipYPost + " velYPost="
                + velYPost + " serverY=" + serverY + " clientY=" + clientY);
        System.out.println("[deckcap] dismount capture=" + capture);

        // The ship must keep hovering, not drop, when the pilot stands up. The computer's own
        // unmanned decision rides in the message: `held=false` says station-keeping was never on,
        // which is a different defect from a hold that engaged and under-thrust.
        assertTrue("a hovering ship must not fall when the pilot dismounts: it dropped from " + shipYPre
                + " to " + shipYPost + ". The computer's unmanned decision was " + hold,
                shipYPre - shipYPost < 2.0);
        assertTrue("a hovering ship must not start falling when the pilot dismounts (velY=" + velYPost
                + "). The computer's unmanned decision was " + hold, velYPost > -0.5);

        // The pilot must stay aboard: resolved on the deck in the ship frame, and rendered there by his
        // own client - not dropped into the world. The client's capture is a link and is awaited as
        // one; a seat dismount seeds it, so a client with no `deck_captured` since the un-seating is
        // the "left in the world" half of the report, named instead of inferred from two heights.
        clientEvents.awaitCarrying(clientDismountMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "the ex-pilot's OWN client must take him onto THIS ship's deck when he stands up"
                        + " mid-hover", DECK_LINK_BUDGET_TICKS);
        assertTrue("the dismounted pilot must be resolved on the deck, not handed to vanilla: " + capture,
                capture.contains("\"verdict\":true") && readInt(capture, OBSTACLES) > 0);
        assertTrue("the client must render the dismounted pilot on the deck where the server holds him: "
                + "serverY=" + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < 2.5);

        exec("artest player dismount"); // clean state for any following test
    }

    // ---- Bug: a ship reloaded from a save drops a walking client player through its deck ---------

    @Test
    public void aClientPlayerReturningToASavedShipStandsOnItsDeckInsteadOfFallingThrough() throws Exception {
        final int bx = 3820, by = 64, bz = 3820;

        // The maintainer's "old ships" are ones from a PRIOR SESSION - assembled, the world saved and
        // unloaded, then loaded again. A freshly assembled ship (the grounded test above) is already
        // loaded and holds him fine; a ship loaded from disk starts in the registry, UNLOADED, until a
        // player brings it back. This drives that path in-harness: build, walk away until the ship's
        // chunks unload (VS saves it to the registry), then return to its deck.
        double[] ship = buildShip(bx, by, bz);
        assertTrue("the ship must be loaded before we unload it", shipIsLoaded());

        // Walk away far enough that nothing tickets the ship's chunks; the harness warmup holds no
        // ticket, so idle chunks unload. Belt and braces: drop any tickets a prior step left.
        Events events = events();
        long unloadMark = events.markInstrumented();
        exec("artest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        // Scoped to THIS ship: a whole-dimension "no ship is loaded" gate would wait on every
        // neighbour scenario's ship as well, and would answer about theirs rather than ours.
        // ASK for the unload rather than waiting to see whether one happens. Walking away is what a
        // player does; on a headless server with no other observer it is a wait on chance, and this
        // scenario used to SKIP whenever the chance did not come — the "different mechanism" its old
        // skip message asked for is this verb.
        // Waiting for the unload is not waiting on chance, which is what the SKIP this replaced
        // assumed: with nothing holding the craft, the substrate's own loading controller queues an
        // unload every tick for any ship with no player inside its unload distance, and the shared
        // base resets the one affordance that would override that. So the state below is REACHED,
        // not hoped for, and failing to reach it is news.
        //
        // The unload is a COMMIT — the substrate drops the physics object at one seam — so it is
        // awaited as one, keyed on this scenario's own ship. The 80x10 poll it replaces read a
        // by-id state and could only ever say "not yet".
        String unloaded = awaitThisShip(events, unloadMark, "ship_unloaded",
                "arrangement: the ship must actually unload before the RELOAD path can be exercised");
        assertTrue("the by-id state must agree with the unload record " + unloaded, !shipIsLoaded());
        // The registry must not have LOST it: an unloaded ship is a SAVED ship, and the registry
        // removal is a different seam with its own record. This is an absence, so the instrument
        // says out loud that it was listening.
        String removals = events.since(unloadMark, "ship_removed");
        Events.assertInstrumentRan(removals, "ship_registry_events",
                "the unloaded ship survived in the registry rather than being removed from it");
        assertTrue("the unloaded ship must survive in the registry (a saved ship is unloaded, never"
                        + " removed): " + removals,
                Events.countRecords(removals, "\"vsShip\":\"" + scenarioShipId + "\"") == 0);

        // Return to the ship exactly as re-entering a docked ship from a saved world, and stand on
        // it. Two links, and each one names a different fault: the ship comes back
        // (`ship_loaded` — a new physics object for THIS ship), and his own client then takes him
        // onto its deck (`deck_captured`). 80 ticks used to cover both and could distinguish
        // neither.
        long reloadMark = events.markInstrumented();
        Events clientEvents = clientEvents();
        long landingMark = clientEvents.mark();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        String reloaded = awaitThisShip(events, reloadMark, "ship_loaded",
                "a saved ship must come back when the player returns to its deck");
        String landing = clientEvents.awaitCarrying(landingMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "the returning player's OWN client must resolve him on THIS RELOADED deck — the"
                        + " playtest's \"old ships drop me through\" is exactly this link missing",
                DECK_LINK_BUDGET_TICKS);
        System.out.println("[deckcap] reloaded ship=" + reloaded + " clientCapture=" + landing);

        String server = exec("artest vs player-ship-data");
        String capture = deckCaptureOfThisShip(scenarioShipId,
                "the returning player must be resolved on the ship this scenario built, which is the"
                        + " one that was saved and reloaded");
        double serverY = readDouble(server, PLAYER_Y);
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] reloaded server=" + server);
        System.out.println("[deckcap] reloaded capture=" + capture);
        System.out.println("[deckcap] reloaded serverY=" + serverY + " clientY=" + clientY
                + " loadedNow=" + shipIsLoaded());

        assertTrue("a reloaded ship must come back when the player returns to its deck: " + server,
                server.contains("\"shipLoaded\":true"));
        // "A ship came back" is not the claim — THIS ship coming back is. A sibling scenario's hull
        // standing in the same airspace satisfies `shipLoaded` byte-identically.
        ShipIdentity.assertAboardShip(server, scenarioShipId,
                "the ship that came back under him must be the one this scenario saved");
        assertTrue("the player must be resolved on the reloaded deck, not fall through it: " + capture,
                capture.contains("\"verdict\":true") && readInt(capture, OBSTACLES) > 0);
        assertTrue("the client must render him ON the reloaded deck, not fallen through: serverY="
                + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < 2.0);
    }

    // ---- Bug: flying into a ship's airspace hijacks a walking player's camera ------------------

    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.test.trace.DeckCameraState";
    /** The TEST-side holder of the client's own last camera setup — production keeps no such field. */
    private static final String DECK_CAMERA_STATE =
            "zmaster587.advancedRocketry.test.trace.DeckCameraState";

    @Test
    public void flyingIntoAShipsAirspaceWithoutStandingOnItDoesNotHijackTheCamera() throws Exception {
        final int bx = 3920, by = 64, bz = 3920;

        double[] ship = buildShip(bx, by, bz);

        // Roll the ship so its world AABB spans a large air volume with a tilted deck - the airspace you
        // cross flying up to a ship. Attitude hold does it with no pilot aboard.
        double h = Math.toRadians(45.0) / 2.0;
        assertTrue("attitude hold must accept the roll",
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).contains("\"commanded\":true"));
        bot().waitTicks(120);
        String info = shipInfo();
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);

        // NEGATIVE (the bug): a player who has NEVER stood on this deck flies into its airspace, off the
        // deck. He comes straight from far, so nothing has captured him (his ship-frame movement state is
        // empty). His view must stay his own - not snap to the tilted deck's horizon.
        exec("tp @a " + (sx + 200) + " 120 " + (sz + 200) + " 0 0");
        bot().waitTicks(10);
        Events clientEvents = clientEvents();
        long flyInMark = clientEvents.mark();
        exec("tp @a " + sx + " " + (sy + 3) + " " + sz + " 0 0");
        bot().waitTicks(1); // one render pass at the off-deck point before he can fall onto the deck
        String flyInCap = exec("artest vs deck-capture");
        boolean inAABB = flyInCap.contains("\"aboardByContainment\":true");
        boolean onShipBlock = flyInCap.contains("\"supportedByShip\":true");
        boolean tracked = flyInCap.contains("\"alreadyTracked\":true");
        boolean flyInCam = Boolean.parseBoolean(deckCameraText("active"));
        double flyInRoll = deckCamera("roll");
        System.out.println("[deckcap] cam fly-in active=" + flyInCam + " roll=" + flyInRoll + " inAABB="
                + inAABB + " onShipBlock=" + onShipBlock + " tracked=" + tracked + " cap=" + flyInCap);
        assertTrue("setup: the fly-in point must be inside the ship's AABB, off any deck block, with the "
                + "player not already resolved on it: " + flyInCap, inAABB && !onShipBlock && !tracked);
        // The negative, as an ABSENCE in the log as well as a static read: the renderer records the
        // camera's ENGAGE edge, so "it did not hijack his view" is "no `deck_camera_changed` with
        // active:true since the mark" — and the instrument says out loud it was listening, or the
        // silence would mean nothing.
        String flyInCamEdges = clientEvents.since(flyInMark, "deck_camera_changed");
        Events.assertInstrumentRan(flyInCamEdges, "deck_camera_events",
                "the deck camera stayed out of a fly-in player's view");
        assertTrue("a player flying through a ship's airspace, not standing on its deck, must keep his "
                + "own view; the deck camera must not hijack it (active=" + flyInCam + " roll="
                + flyInRoll + " edges=" + flyInCamEdges + ")",
                !flyInCam && Events.countRecords(flyInCamEdges, "\"active\":true") == 0);

        // POSITIVE control: level the ship and land him ON the deck. Now the deck camera SHOULD engage -
        // so the negative above is a real on-deck/off-deck discrimination, not the camera never firing.
        assertTrue("attitude hold must accept levelling",
                exec("artest vs point-by-id 0 " + scenarioShipId + " 1.0 0.0 0.0 0.0")
                        .contains("\"commanded\":true"));
        bot().waitTicks(120);
        String lvl = shipInfo();
        // The control is the camera's STATE, and it may NOT be its engage edge — a fact about the
        // recorder, not a preference. `deck_camera_changed` is written at
        // {@code ShipFrameCamera.recordCamera} only when `active` differs from what the last frame
        // left in the field, production's two release branches set `shipCamActive = false` directly
        // without passing that seam, and every production caller of `recordCamera` passes `true`. So
        // an engage edge exists only where the flag had actually DROPPED — and the negative leg
        // above has already left the bot standing on this deck with the camera engaged, so
        // levelling the ship and putting him down again produces no edge at all. Awaiting one reds a
        // client whose camera is engaged, which is the opposite of what this control asserts.
        //
        // The discrimination the negative leg needs survives intact and is still made of two
        // measurements of the same flag: OFF while he is in the airspace off the deck, ON while he
        // stands on it. The edges since the mark stay in the message — one recorded HERE would say
        // the camera had been released and came back, which is a different and interesting story
        // from one that never dropped.
        long onDeckMark = clientEvents.mark();
        exec("tp @a " + readDouble(lvl, POS_X) + " " + (readDouble(lvl, POS_Y) + 5) + " "
                + readDouble(lvl, POS_Z) + " 0 0");
        ClientPoll.Result<Boolean> camPoll = ClientPoll.<Boolean>until(bot()::waitTicks,
                () -> Boolean.parseBoolean(deckCameraText("active")),
                active -> active.booleanValue(), 5, 40);
        String engaged = clientEvents.since(onDeckMark, "deck_camera_changed");
        boolean onDeckCam = camPoll.value;
        String camCapture = exec("artest vs deck-capture");
        System.out.println("[deckcap] cam on-deck active=" + onDeckCam + " poll=" + camPoll
                + " edgesSincePutDown=" + engaged + " cap=" + camCapture);
        // The camera is DOWNSTREAM of the capture, so a bare "no deck camera" blames the renderer
        // for something that usually happened one link earlier. The capture verdict is already read
        // for the stdout line above; putting it in the message is free and it splits the two: a
        // capture that says verdict=false means the body was never resolved on the deck at all and
        // the camera is behaving correctly, while a true capture with no camera is a real render gap.
        assertTrue("a player actually standing on the deck must get the deck camera. If the capture"
                        + " below says the body is NOT on the deck then this is not a camera fault at"
                        + " all - the body never got there. capture=" + camCapture.replace('\n', ' ')
                        + " poll=" + camPoll + " cameraEdgesSincePutDown=" + engaged
                        + " playerY=" + bot().reportState().get("playerY").getAsDouble(),
                onDeckCam);
    }

    // ---- Bug: a hovering ship falls (and tumbles inverted) after a world reload -----------------

    @Test
    public void aHoveringShipKeepsHoveringAcrossAReloadInsteadOfFalling() throws Exception {
        final int bx = 4320, by = 64, bz = 4320;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Fly it into a hover, then stand up: it is now an unmanned, station-keeping, hovering ship -
        // exactly the state a saved hovering ship is in on disk.
        double startY = readDouble(shipInfo(), POS_Y);
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_R);
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 200-iteration budget
            // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(shipInfo(), POS_Y),
                    y -> y - startY >= 3.0, 2, 200);
        } finally {
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_R);
        }
        double liftedY = lift.value;
        assertTrue("the pilot must lift the ship into a hover: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        // The hold engaging is the computer's own decision, so the wait is on that record rather
        // than on 40 ticks: the state this whole scenario saves and restores is the one it names.
        Events events = events();
        long standUpMark = events.markInstrumented();
        exec("artest player dismount");
        String holdBefore = events.await(standUpMark, "unmanned_hold_decided",
                "the flight computer must take an unmanned decision when the pilot stands up — the"
                        + " hover this scenario then saves and reloads is that decision's result",
                DECK_LINK_BUDGET_TICKS);
        double hoverY = readDouble(shipInfo(), POS_Y);
        assertTrue("the unmanned ship must still be hovering off the ground: " + hoverY
                + " (the computer's unmanned decision was " + holdBefore + ")",
                hoverY - startY > 1.0);

        // Simulate a world reload: unload the ship (its flight-computer tile is written to NBT, its LIVE
        // attitudeReference lost) and load it again. The persisted station-keeping flag must bring the
        // hold back so the ship does NOT fall - the live playtest's "hovering ship survived a restart,
        // then fell and flipped".
        long unloadMark = events.markInstrumented();
        exec("artest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        // Scoped to THIS ship, like the saved-ship scenario above: on a shared world a whole-dimension
        // count answers about whichever neighbour's ship is loaded — and the substrate's own
        // unload/load commits are what is awaited, keyed on this ship's physics uuid.
        // Waiting for the unload is not waiting on chance, which is what the SKIP this replaced
        // assumed: with nothing holding the craft, the substrate's own loading controller queues an
        // unload every tick for any ship with no player inside its unload distance, and the shared
        // base resets the one affordance that would override that. So the state below is REACHED,
        // not hoped for, and failing to reach it is news.
        String unloaded = awaitThisShip(events, unloadMark, "ship_unloaded",
                "arrangement: the ship must actually unload before the RELOAD path can be exercised");
        assertTrue("the by-id state must agree with the unload record " + unloaded, !shipIsLoaded());

        long reloadMark = events.markInstrumented();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        // The reload is a COMMIT — a fresh physics object for this ship — and the poll it replaces
        // discarded its own `satisfied` flag, so a reload that never happened surfaced further down
        // as a regex failure reading like the contract breaking.
        String reloaded = awaitThisShip(events, reloadMark, "ship_loaded",
                "arrangement: the saved ship must come back before its hold can be judged");
        bot().waitTicks(80); // give a ship that lost its hold time to visibly fall

        // What the flight computer restored from NBT, and what it then decided unmanned. Read, not
        // awaited: the contract below is the ALTITUDE, and these two records are what let its failure
        // say "the persisted flag did not survive the save" apart from "it did and the hold
        // under-thrust" — the question a 3-block drop on its own can never answer.
        String restored = events.since(reloadMark, "station_keeping_restored");
        String heldAfter = events.since(reloadMark, "unmanned_hold_decided");
        double afterY = readDouble(shipInfo(), POS_Y);
        System.out.println("[deckcap] reload-hover startY=" + startY + " hoverY=" + hoverY
                + " afterReloadY=" + afterY + " loaded=" + reloaded + " restored=" + restored
                + " unmanned=" + heldAfter);
        assertTrue("a hovering ship must KEEP hovering across a reload, not fall out of the sky: it was "
                + "at " + hoverY + " and after reload is at " + afterY
                + ". What the computer restored from NBT: " + restored
                + " | what it then decided unmanned: " + heldAfter, hoverY - afterY < 3.0);
    }

    // ---- Bug: entering / leaving the seat on a truly INVERTED ship (the maintainer's live scenario) --

    private static final String KEY_BINDINGS = "zmaster587.advancedRocketry.client.KeyBindings";
    private static final Pattern OMEGA = Pattern.compile("\"omega\":(-?[0-9.E\\-]+)");
    private static final Pattern QX = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern QZ = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");

    private double shipUpYFromInfo(String info) {
        double qx = readDouble(info, QX), qz = readDouble(info, QZ);
        return 1.0 - 2.0 * (qx * qx + qz * qz); // world-Y of the ship's local +Y
    }

    private double[] readShipInfoXYZ(String info) {
        return new double[]{readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
    }

    @Test
    public void standingUpFromASeatOnASteeplyTiltedShipKeepsThePilotOnTheDeck() throws Exception {
        final int bx = 4620, by = 64, bz = 4620;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Put the craft at a steep but STANDABLE tilt, then stand up FROM the seat while it is
        // tilted — the maintainer's "after leaving, I fall through" is on a non-upright ship, which
        // the upright dismount test never exercised. 60 degrees of roll about X is
        // q = (cos30, sin30, 0, 0), i.e. up.y = 0.5, in the middle of the envelope this scenario
        // needs rather than at either edge of it.
        //
        // Commanded, not mouse-rolled. The roll this replaced was an open loop against a craft that
        // keeps turning after the cursor centres — its own comment says so — so it landed anywhere
        // between no tilt and a vertical wall and SKIPPED whenever it missed. The mouse is not the
        // subject here; standing up on a tilted deck is, and it is still the real client that stands.
        for (int i = 0; i < 40 && shipUpYFromInfo(shipInfo()) > 0.55; i++) {
            exec("artest vs point-by-id 0 " + scenarioShipId + " 0.8660254 0.5 0 0");
            bot().waitTicks(4);
        }
        exec("artest vs force-clear-by-id 0 " + scenarioShipId);
        centreFlightCursor();
        bot().waitTicks(30);
        double tilted = shipUpYFromInfo(shipInfo());
        // An ASSERT, not an Assume: the tilt is commanded to a value inside the envelope, so failing
        // to be there is news about how a craft holds a commanded attitude — not a dice roll to be
        // stepped over. The client-side capture packet snaps the fresh dismount onto the deck and
        // holds it there, like a crew member who rode in and holds at 90 degrees.
        assertTrue("arrangement: the craft must sit in the steep-but-standable envelope before the"
                + " subject is exercised (upY=" + tilted + ")", tilted >= 0.25 && tilted < 0.80);

        double[] seat = readShipInfoXYZ(shipInfo());
        // The seat dismount seeds the ex-pilot's capture on the CLIENT, and that is the link this
        // scenario's report is about ("after leaving, I fall through"). Marked before the stimulus,
        // awaited after it — the settle window below then measures a body that is provably captured
        // rather than one that may never have been.
        Events clientEvents = clientEvents();
        long dismountMark = clientEvents.mark();
        exec("artest player dismount");
        String seeded = clientEvents.awaitCarrying(dismountMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "standing up on THIS tilted deck must leave the ex-pilot captured ON THE CLIENT — the"
                        + " seed is what puts him there, and without it the heights below are"
                        + " measuring a body vanilla owns", DECK_LINK_BUDGET_TICKS);
        StringBuilder traj = new StringBuilder();
        double settledMin = Double.MAX_VALUE;
        for (int i = 0; i < 22; i++) {
            bot().waitTicks(2);
            double y = bot().reportState().get("playerY").getAsDouble();
            traj.append(String.format("%.1f ", y));
            if (i >= 14) { // last ~8 samples, once the dismount motion has settled
                settledMin = Math.min(settledMin, y);
            }
        }
        // Every release in the window, each with the gate that performed it — production's own words
        // for what the cumulative counters this replaced could only report as a number.
        String releases = clientEvents.since(dismountMark, "deck_released");
        String capture = exec("artest vs deck-capture");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = readDouble(exec("artest vs player-ship-data"), PLAYER_Y);
        System.out.println("[deckcap] tilted-dismount upY=" + tilted + " shipPosY=" + seat[1]
                + " settledMinY=" + settledMin + " Ytraj=" + traj);
        System.out.println("[deckcap] tilted-dismount seed=" + seeded + " releases=" + releases);
        System.out.println("[deckcap] tilted-dismount capture=" + capture + " clientY=" + clientY
                + " serverY=" + serverY);

        // The ship hovers well above the by=64 ground (its solid top at y=65). The contract is that the
        // pilot does NOT fall through/off to the ground: his SETTLED height must stay up on the ship, not
        // drop to ~65. A single-instant "aboard" read is unreliable (it can catch him mid-fall while still
        // nominally inside the AABB), so we assert the settled trajectory instead.
        assertTrue("standing up on a tilted ship must keep the pilot UP on it, not drop him to the ~65 "
                + "ground: settledMinY=" + settledMin + " shipPosY=" + seat[1] + " Ytraj=" + traj
                + ". The client's releases in this window (each with the gate that performed it): "
                + releases, settledMin > 66.0);
        assertTrue("the client and server must agree on the ex-pilot's height on the tilted ship: serverY="
                + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < 3.0);
    }

    @Test
    public void aFreshlyDismountedPilotStaysCapturedWhenTheShipThenRollsNinetyDegrees() throws Exception {
        double h = Math.toRadians(90.0) / 2.0; // deck on its side (upY ~ 0)
        assertDismountThenRollHolds(4720, 64, 4720, Math.cos(h), Math.sin(h), -0.35, 0.35, "90deg");
    }

    @Test
    public void aFreshlyDismountedPilotStaysCapturedWhenTheShipThenRollsPastVertical() throws Exception {
        // Command 160deg; the attitude hold settles well PAST vertical on this fixture (measured deck-up
        // ~ -0.93, i.e. ~160deg - nearly inverted, the ex-pilot hanging below the deck). An EXACT 180deg is
        // the axis-angle singularity the controller cannot converge to, and a free spin to it is VS-damped
        // in a headless run - so the last few degrees to full inversion are a manual-playtest item.
        assertDismountThenRollHolds(4820, 64, 4820, 0.17365, 0.98481, -1.01, -0.4, "past-vertical");
    }

    /**
     * The fresh-dismount capture must survive the ship SUBSEQUENTLY rolling to a steep/inverted attitude:
     * stand up from the seat on a LEVEL deck (the client-side {@code PacketDeckCapture} seeds the ex-pilot
     * on the deck), THEN command the ship to a fixed roll about its nose and hold it, and assert the
     * ex-pilot rode the deck over - still resolved on it, held at deck height, client and server agreeing -
     * instead of being dropped through the hull or left behind in the world.
     *
     * <p>Reliable because the roll is a commanded quaternion on an UNMANNED ship ({@code artest vs point}):
     * a seated pilot's own input overwrites the attitude target every tick, and a free spin is VS-damped,
     * so commanding the attitude after the dismount is the only way to put a walking ex-pilot on a
     * steep/inverted deck in the headless harness. Thresholds are deck-relative (derived from the measured
     * ship Y), never a magic absolute.</p>
     */
    private void assertDismountThenRollHolds(int bx, int by, int bz, double qw, double qz,
            double upYLo, double upYHi, String label) throws Exception {

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Stand up on the LEVEL deck: the dismount capture packet seeds the ex-pilot on the deck.
        //
        // Awaited on the CLIENT's own record of THIS dismount. What it replaces could not fail: the
        // gate read `ShipFrameTravel.resolvedTicks`, a counter that is cumulative for the life of
        // the side, and on a shared client an earlier scenario has already resolved somebody on a
        // deck — so "> 0" was true before this dismount ever happened. A mark taken immediately
        // before the stimulus is what turns the same question into one that can come back "no".
        Events clientEvents = clientEvents();
        long dismountMark = clientEvents.mark();
        exec("artest player dismount");
        String seeded = clientEvents.awaitCarrying(dismountMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "the fresh dismount must engage the ship-frame capture on THIS ship's level deck",
                DECK_LINK_BUDGET_TICKS);

        // Roll the now-UNMANNED ship (a mounted pilot would overwrite the target) to the commanded attitude.
        assertTrue("attitude hold must accept the " + label + " roll command",
                exec("artest vs point-by-id 0 " + scenarioShipId + " " + qw + " 0.0 0.0 " + qz)
                        .contains("\"commanded\":true"));
        bot().waitTicks(200); // slew to the roll and settle - stationary, not a transient
        double tilted = shipUpYFromInfo(shipInfo());
        // Reliable command -> a HARD assert that the regime was reached (fail loudly, not a silent skip).
        assertTrue("the ship must reach the " + label + " regime for the test to mean anything (upY="
                + tilted + " expected [" + upYLo + "," + upYHi + "])", tilted >= upYLo && tilted <= upYHi);

        double shipPosY = readShipInfoXYZ(shipInfo())[1];
        long rollMark = clientEvents.mark();
        StringBuilder traj = new StringBuilder();
        double settledMin = Double.MAX_VALUE, settledMax = -Double.MAX_VALUE;
        for (int i = 0; i < 22; i++) {
            bot().waitTicks(2);
            double y = bot().reportState().get("playerY").getAsDouble();
            traj.append(String.format("%.1f ", y));
            if (i >= 14) { // last ~8 samples, once the roll has settled
                settledMin = Math.min(settledMin, y);
                settledMax = Math.max(settledMax, y);
            }
        }
        double osc = settledMax - settledMin;
        // What the client's resolver did across the settle, in its own records: how many ticks it
        // committed a capture, and every release with the gate that performed it. This replaces a
        // read of the cumulative `resolvedTicks` static, which counted every body this side ever
        // resolved and so could not be scoped to this window at all.
        String rollCaptures = clientEvents.since(rollMark, "deck_captured");
        String rollReleases = clientEvents.since(rollMark, "deck_released");
        // Read once and proved to be about this scenario's craft: the whole claim below is "the roll
        // did not hand him away", and a capture re-anchored onto a neighbour's hull mid-roll is
        // exactly that failure while reading `verdict:true`.
        String capture = deckCaptureOfThisShip(scenarioShipId,
                "the ex-pilot must stay resolved on the ship he was rolled with");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = readDouble(exec("artest vs player-ship-data"), PLAYER_Y);
        System.out.println("[deckcap] dismount-then-roll " + label + " upY=" + tilted + " shipPosY="
                + shipPosY + " settledMin=" + settledMin + " osc=" + osc + " seed=" + seeded
                + " capturesOnRoll=" + Events.countRecords(rollCaptures, "\"ship\"")
                + " releasesOnRoll=" + rollReleases
                + " capture=" + capture + " clientY=" + clientY + " serverY=" + serverY + " Ytraj=" + traj);

        // Still captured while the deck is steep/inverted - the ship frame keeps resolving him, not vanilla.
        assertTrue("the ex-pilot must stay resolved on the " + label + " deck, not be handed to vanilla: "
                + capture + ". The client's releases across the roll: " + rollReleases,
                capture.contains("\"verdict\":true"));
        // Deck-relative hold: he must not slide down toward the ~" + (by + 1) + " ground - his settled
        // height stays within a body of the measured ship, not 2.5+ blocks below it.
        assertTrue("the ex-pilot must ride the " + label + " deck over, not drop to the ground: settledMin="
                + settledMin + " shipPosY=" + shipPosY + " Ytraj=" + traj
                + ". The client's releases across the roll: " + rollReleases,
                settledMin > shipPosY - 2.5);
        // Held, not sliding: a captured body is stationary on the stationary rolled ship (small tail swing);
        // a body sliding off shows a large monotonic settle.
        assertTrue("the captured ex-pilot must be HELD on the " + label + " deck, not sliding (settled Y "
                + "oscillation=" + osc + "): " + traj, osc < 1.5);
        assertTrue("the client and server must agree on the ex-pilot's height (serverY=" + serverY
                + " clientY=" + clientY + ")", Math.abs(clientY - serverY) < 3.0);
    }

    @Test
    public void enteringAndLeavingTheSeatOnAnInvertedShipWorks() throws Exception {
        final int bx = 4520, by = 64, bz = 4520;

        double[] ship = buildShip(bx, by, bz);

        // Put the FRESH (never-piloted) craft into a held inversion by writing the attitude: 180
        // degrees about X is q = (0, 1, 0, 0), so the deck's own +Y points at world −Y. It STAYS
        // there once written, because an attitude error this far past the reference reseed is
        // ADOPTED and then held rather than corrected — which is also the maintainer's ship, stuck
        // inverted after a tumble.
        //
        // Three arrangements were measured side by side (2026-08-22, server tier, same craft type),
        // and only this one works. Re-applying a raw 5 rad/s spin — what this scenario did until
        // today — never even reached inversion: best up.y = +0.490 over 60 attempts, because the
        // unmanned auto-level torques the deck back to horizontal as fast as the write arrives, and
        // that is why this scenario had been SKIPPING. Commanding the roll through the flight
        // computer does reach it (−0.902) and then loses it: released, the craft rights itself to
        // +0.353 within 60 ticks. Neither can arrange what this test is about.
        // `point-by-id` COMMANDS a target attitude held by torque — it is the attitude-hold
        // interface, not a pose write — so the craft has to slew there, and the command has to stand
        // while it does. Measured cost of getting this wrong: commanded once and read 20 ticks later,
        // the craft was at up.y = +0.038 with omega = 2.0 rad/s, i.e. still on its way round. Half a
        // turn at that rate needs about 31 ticks.
        double invertedUpY = 1.0;
        for (int i = 0; i < 40 && invertedUpY > -0.9; i++) {
            exec("artest vs point-by-id 0 " + scenarioShipId + " 0 1 0 0");
            bot().waitTicks(4);
            invertedUpY = shipUpYFromInfo(shipInfo());
        }
        // Then let go, and let it sit: REACHING an attitude and KEEPING it are different questions,
        // and everything below needs the second one.
        exec("artest vs force-clear-by-id 0 " + scenarioShipId);
        bot().waitTicks(40);
        String info0 = shipInfo();
        invertedUpY = shipUpYFromInfo(info0);
        System.out.println("[deckcap] force-invert upY=" + invertedUpY + " info=" + info0);
        // An ASSERT, not an Assume: the attitude write is deterministic, so a craft that is not
        // inverted here is a real change in how a craft holds an adopted attitude — which is a thing
        // this suite should go red for, not skip over. The skip it replaces hid this scenario for as
        // long as the spin arrangement was failing, and a scenario nobody sees fail is not a test.
        assertTrue("arrangement: the craft must be INVERTED before the subject is exercised (upY="
                + invertedUpY + "): " + info0, invertedUpY < -0.85);

        // ENTER the seat on the inverted ship — located inside THIS ship, not "the first seat in
        // the world" (see mountPilotSeatOfShipAt).
        mountPilotSeatOfShipAt(bx, by, bz);
        bot().waitTicks(20);

        // SYMPTOM "after entering, the ship does not react": a turn command must actually move it.
        for (int i = 0; i < 15; i++) {
            mouseDelta(60, 0);
            bot().waitTicks(2);
        }
        // Early exit + a load-scaled ceiling. The verdict below is "omega crossed 0.1 at some
        // point", so the first sample that crosses settles it and every further tick is burned; and
        // a FIXED 20-iteration budget can under-observe under concurrent-fork load. Equivalent to
        // the max it replaces: "some sample exceeded 0.1" is what both compute.
        double omegaAfter = ClientPoll.until(bot()::waitTicks,
                () -> readDouble(shipInfo(), OMEGA), o -> o > 0.1, 2, 20).value;
        System.out.println("[deckcap] force-invert control cursor="
                + flightCursorX("at the force-invert leg") + " omegaAfter=" + omegaAfter);

        // SYMPTOM "after leaving, I fall through": dismount, the pilot must stay on the inverted deck.
        // The client's own capture of THIS dismount is the link; a body that fell through has none.
        Events clientEvents = clientEvents();
        long dismountMark = clientEvents.mark();
        exec("artest player dismount");
        String seeded = clientEvents.awaitCarrying(dismountMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "leaving the seat on THIS INVERTED ship must leave the ex-pilot captured BY IT on his"
                        + " own client, which is where the reported fall-through happens",
                DECK_LINK_BUDGET_TICKS);
        String capture = deckCaptureOfThisShip(scenarioShipId,
                "after leaving the seat on an INVERTED ship the ex-pilot must stay resolved on THAT"
                        + " ship, not on whatever else is in the airspace");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = readDouble(exec("artest vs player-ship-data"), PLAYER_Y);
        System.out.println("[deckcap] force-invert dismount seed=" + seeded + " capture=" + capture
                + " clientY=" + clientY + " serverY=" + serverY);

        assertTrue("after ENTERING an inverted ship, a turn command must move it, not leave it dead "
                + "(omega=" + omegaAfter + ")", omegaAfter > 0.1);
        assertTrue("after LEAVING an inverted ship, the pilot must stay resolved on the deck, not fall "
                + "through: " + capture, capture.contains("\"verdict\":true"));
    }

    @Test
    public void aSeatedPilotCanStillTurnTheShipWhenItIsInverted() throws Exception {
        final int bx = 4420, by = 64, bz = 4420;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Command the craft over to inverted and let it hold there. The subject is what the pilot's
        // controls do ONCE INVERTED — the maintainer's report is that they stop working there — and
        // that subject is still driven below by the real mouse. Only the way IN changed: rolling
        // over by mouse also demonstrated that the controls work on the way, but it arrived at a
        // variable attitude and SKIPPED whenever it undershot, which bought that side observation at
        // the price of the scenario running at all.
        for (int i = 0; i < 40 && deckCamera("shipUpY") > -0.9; i++) {
            exec("artest vs point-by-id 0 " + scenarioShipId + " 0 1 0 0");
            bot().waitTicks(4);
        }
        exec("artest vs force-clear-by-id 0 " + scenarioShipId);
        centreFlightCursor();
        bot().waitTicks(40); // let it settle inverted, omega -> ~0
        double shipUpY = deckCamera("shipUpY");
        // An ASSERT: the attitude is commanded, so not being there is news, not a dice roll. And it
        // is read from the CLIENT's own camera state, which is what the pilot below is looking at.
        assertTrue("arrangement: the craft must be inverted ON THE CLIENT before its controls are"
                + " tested there (shipUpY=" + shipUpY + ")", shipUpY < -0.4);
        double omegaSettled = readDouble(shipInfo(), OMEGA);
        System.out.println("[deckcap] inverted-control shipUpY=" + shipUpY + " omegaSettled=" + omegaSettled);

        // Now, WHILE inverted, command a fresh turn. The ship must respond - its angular velocity must
        // rise - just as it does upright. If it stays at rest, the controls are dead at inversion.
        for (int i = 0; i < 20; i++) {
            mouseDelta(60, 0);
            bot().waitTicks(2);
        }
        double cursor = flightCursorX("after twenty raw mouse deltas while inverted");
        // Same wait as the force-invert leg above, and it carried a 1.5x budget for no stated
        // reason; both are now the same early-exit poll with the same load-scaled ceiling.
        double omegaTurning = ClientPoll.until(bot()::waitTicks,
                () -> readDouble(shipInfo(), OMEGA), o -> o > 0.1, 2, 30).value;
        System.out.println("[deckcap] inverted-control cursor=" + cursor + " omegaTurning=" + omegaTurning);

        assertTrue("a hard flight-cursor deflection must register on the client even when inverted "
                + "(cursor=" + cursor + ")", Math.abs(cursor) > 0.2);
        assertTrue("a seated pilot must still be able to TURN the ship when it is inverted - commanding a "
                + "turn must spin it up, not leave it dead (omega=" + omegaTurning + ")", omegaTurning > 0.1);
    }

    /** Feed a raw mouse delta to the client's own ship-pilot handler, as the window's mouse would. */
    private void mouseDelta(int dx, int dy) throws Exception {
        bot().invokeStaticInt(KEY_BINDINGS, "acceptShipPilotMouseDelta", dx, dy);
    }

    /** Bring the client's flight cursor back inside its centre dead-zone. */
    private void centreFlightCursor() throws Exception {
        double cursor = flightCursorX("before centring");
        for (int i = 0; i < 200 && Math.abs(cursor) >= 0.03; i++) {
            int step = Math.abs(cursor) > 0.2 ? 30 : 2;
            mouseDelta(cursor > 0 ? -step : step, 0);
            bot().waitTicks(1);
            cursor = flightCursorX("while centring, nudge " + i);
        }
    }

    /**
     * The flight cursor as the client last RECORDED it, or a failure naming the empty window.
     *
     * <p>Replaces a reflective read of a private production static. The field keeps its last value
     * when the input path stops running, so a poll cannot separate "the cursor is where I left it"
     * from "nothing has updated it since" — and the centring loop above, against a dead input path,
     * would nudge two hundred times and hand back a stale number that reads like a measurement.</p>
     */
    private double flightCursorX(String what) throws Exception {
        long mark = clientEvents().mark();
        bot().waitTicks(1);
        String rec = Events.lastRecord(clientEvents().since(mark, "flight_cursor"));
        assertNotNull("no flight_cursor record " + what + " — the client's flight-input path did not "
                + "run in that tick, so there is no cursor reading to act on", rec);
        return Events.number(rec, "x");
    }

    // ---- Bug: camera/capture instability on a steeply tilted, HELD deck ------------------------

    @Test
    public void aClientPlayerRidingASteeplyTiltedDeckHasStableCaptureAndCamera() throws Exception {
        final int bx = 4120, by = 64, bz = 4120;

        double[] ship = buildShip(bx, by, bz);

        // Stand the client player on the UPRIGHT deck first (capture works there), then tilt the ship
        // and hold it. The player rides the deck; the camera and capture must stay STABLE while the ship
        // is stationary at a steep angle - not jitter frame-to-frame (RC-2 Euler pole) nor flicker the
        // capture (which alternates gravity and drags the body "back and forth through the deck").
        Events clientEvents = clientEvents();
        long landingMark = clientEvents.mark();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        // The message says CLIENT, so the read is the client's: his own resolver's capture record,
        // awaited rather than given 80 ticks. (The server probe beside it re-evaluates handles() for
        // the SERVER player and reads the SERVER state map — a second opinion, not this one.)
        clientEvents.awaitCarrying(landingMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "the client must be captured on THIS upright deck before the ship is tilted under him",
                DECK_LINK_BUDGET_TICKS);
        // Read ONCE, and proved to be about THIS ship: the two execs this replaces
        // printed one sample and asserted a second, and neither said which craft
        // held the body.
        String deckCapture = deckCaptureOfThisShip(scenarioShipId,
                "the capture this assertion reads must be on this scenario's own ship");
        assertTrue("server must agree the body is on the upright deck first: "
                + deckCapture,
                deckCapture.contains("\"verdict\":true"));

        double h = Math.toRadians(90.0) / 2.0; // 90deg roll about the nose (+Z): deck on its side
        assertTrue("attitude hold must accept the tilt",
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).contains("\"commanded\":true"));
        bot().waitTicks(160); // slew to the tilt and settle - the ship is now HELD stationary

        // Sample across frames while the ship is stationary. Any variation is instability, not motion.
        // The mark opens BEFORE the sampling: "the capture did not flicker" is an absence, and five
        // 4-tick samples of a server verdict cannot see a release that was recovered between two of
        // them — the client's log records every one, with the gate that performed it.
        long stabilityMark = clientEvents.mark();
        int n = 5;
        double rollMin = Double.MAX_VALUE, rollMax = -Double.MAX_VALUE;
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        int captured = 0, camOn = 0;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < n; i++) {
            bot().waitTicks(4);
            boolean active = Boolean.parseBoolean(deckCameraText("active"));
            double roll = deckCamera("roll");
            // Counted as "captured" only while the capture is anchored on THIS scenario's ship: the
            // claim below is that one capture held for the whole window, and a body handed from this
            // hull to a neighbour's and back keeps `verdict:true` at every sample.
            String sample = exec("artest vs deck-capture");
            boolean verdict = sample.contains("\"verdict\":true")
                    && scenarioShipId.equals(ShipIdentity.anchorOf(sample));
            double y = bot().reportState().get("playerY").getAsDouble();
            if (active) { camOn++; rollMin = Math.min(rollMin, roll); rollMax = Math.max(rollMax, roll); }
            if (verdict) captured++;
            yMin = Math.min(yMin, y); yMax = Math.max(yMax, y);
            trace.append(String.format("[%d act=%b roll=%.1f verd=%b y=%.2f] ", i, active, roll, verdict, y));
        }
        double rollJitter = camOn > 0 ? rollMax - rollMin : 0.0;
        double yOsc = yMax - yMin;
        String flickers = clientEvents.since(stabilityMark, "deck_released");
        Events.assertInstrumentRan(flickers, "deck_capture_events",
                "the client's capture held for the whole stationary window");
        System.out.println("[deckcap] tilted-stability n=" + n + " captured=" + captured + " camOn=" + camOn
                + " rollJitter=" + rollJitter + " yOsc=" + yOsc + " releases=" + flickers
                + " :: " + trace);

        assertTrue("capture must stay STABLE on a held tilted deck, not flicker - the client released"
                + " it, and the record names the gate: " + flickers + " :: " + trace,
                Events.countRecords(flickers, "\"reason\"") == 0);
        assertTrue("capture must stay STABLE on a held tilted deck, not flicker (captured " + captured
                + "/" + n + "): " + trace, captured == n);
        // The camera's DISENGAGE is not recordable — production drops `shipCamActive` directly in the
        // two release branches without passing through the seam the engage edge is taken at, and the
        // mixin says so — so "it stayed engaged" is still read off the client's render flag.
        assertTrue("the deck camera must stay engaged on a held tilted deck (camOn " + camOn + "/" + n
                + "): " + trace, camOn == n);
        assertTrue("the levelled camera roll must be STABLE while the ship is stationary, not jitter at "
                + "the Euler pole (jitter=" + rollJitter + " deg): " + trace, rollJitter < 5.0);
        assertTrue("the client player must not be dragged through the deck (Y oscillation=" + yOsc
                + "): " + trace, yOsc < 1.0);
    }

    // ---- Bug: coordinate transforms break at extreme (inverted) attitudes ----------------------

    @Test
    public void anInvertedShipsMovementAndCameraFramesStayConsistent() throws Exception {
        final int bx = 4020, by = 64, bz = 4020;

        double[] ship = buildShip(bx, by, bz);

        // Flip the ship nearly upside-down: a 160-degree roll about its nose (+Z) - past inverted, but
        // shy of the exact 180 axis-angle singularity so the controller converges cleanly. Quaternion
        // (w,x,y,z) = (cos80, 0, 0, sin80). This is the regime the playtest saw break.
        assertTrue("attitude hold must accept the flip",
                exec("artest vs point-by-id 0 " + scenarioShipId + " 0.17365 0.0 0.0 0.98481")
                        .contains("\"commanded\":true"));
        bot().waitTicks(200); // slew all the way over and settle

        String info = shipInfo();
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);
        exec("tp @a " + sx + " " + (sy + 1) + " " + sz + " 0 0"); // inside the AABB so the probe resolves
        bot().waitTicks(2);

        String tc = exec("artest vs ship-frame-check");
        System.out.println("[deckcap] inverted transform-check=" + tc);
        // The attitude controller converges shy of a full 180 (axis-angle is singular there), settling
        // near 135deg - deck-up well past horizontal and pointing downward. That is a strongly non-trivial
        // attitude, which is all the consistency check needs.
        assertTrue("ship must be strongly inverted (deck-up points well below horizontal): " + tc,
                readDouble(tc, Pattern.compile("\"upQuatY\":(-?[0-9.E\\-]+)")) < -0.5);

        // THE decisive check: the MOVEMENT frame (VS vector rotate, used by ShipFrameTravel) and the
        // CAMERA/gravity frame (the attitude quaternion) must describe the SAME rotation. A disagreement
        // here is the root of "the inverted ship drags me through the deck while the camera never turns
        // over" - movement resolving in one frame, the camera reading another.
        double upDis = readDouble(tc, Pattern.compile("\"upDisagreement\":(-?[0-9.E\\-]+)"));
        double fwdDis = readDouble(tc, Pattern.compile("\"fwdDisagreement\":(-?[0-9.E\\-]+)"));
        double posRt = readDouble(tc, Pattern.compile("\"posRoundTripErr\":(-?[0-9.E\\-]+)"));
        double rotRt = readDouble(tc, Pattern.compile("\"rotRoundTripErr\":(-?[0-9.E\\-]+)"));
        System.out.println("[deckcap] inverted upDis=" + upDis + " fwdDis=" + fwdDis
                + " posRt=" + posRt + " rotRt=" + rotRt);
        assertTrue("movement rotate and camera quaternion must agree on ship-up (disagree=" + upDis
                + "): " + tc, upDis < 0.02);
        assertTrue("movement rotate and camera quaternion must agree on ship-forward (disagree=" + fwdDis
                + ")", fwdDis < 0.02);
        assertTrue("world<->subspace position round-trip must be exact (err=" + posRt + ")", posRt < 0.02);
        assertTrue("world<->subspace rotation round-trip must be exact (err=" + rotRt + ")", rotRt < 0.02);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    private String clientString(String className, String field) throws Exception {
        return bot().readStaticField(className, field).get("value").getAsString();
    }

    private double clientDouble(String className, String field) throws Exception {
        return Double.parseDouble(clientString(className, field));
    }

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(int bx, int by, int bz) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        // The registry's own addShip, awaited as a LINK since a mark taken BEFORE the assembly is
        // queued — so the record is THIS scenario's ship and names it, where a whole-dimension count
        // that merely went up is answered by any neighbour that assembled one in the same window.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        scenarioShipId = awaitShipSpawned(events, spawnMark,
                "assembly must create a NEW VS ship in the queryable registry (async spawn)");
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        // The IDENTITY is already known: this scenario ASSEMBLED the ship, and the registry's own
        // `ship_spawned` record above names it. What still has to be waited for is a different fact
        // — the physics object being USABLE, which a registry add does not prove. That fact is
        // production's own `ShipEvent.ShipLoadedEvent`, recorded off the bus as `ship_usable`: the
        // conjunction the physics loop selects a ship by, where the old `managed:true` poll read a
        // literal `true` in the probe's reply builder and so waited on nothing. The wait is still
        // keyed BY ID: these scenarios hover, tumble and invert their ship on purpose, and a bounded
        // nearest-ship lookup cannot follow it there — out of the radius it answers about nothing,
        // inside it about a neighbour, and both replies read like a correct one. An id has no
        // distance term to be wrong about. The mark is `spawnMark`, taken BEFORE the assembly above:
        // `ship_usable` fires ONCE per load and is not a state to poll, so a later mark could miss it.
        awaitShipUsable(events, spawnMark, scenarioShipId);
        String si = shipInfo();
        double[] where = {readDouble(si, POS_X), readDouble(si, POS_Y), readDouble(si, POS_Z)};
        System.out.println("[deckcap] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
    }

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(int bx, int by, int bz) throws Exception {
        double[] ship = buildShip(bx, by, bz);
        mountPilotSeatOfShipAt(bx, by, bz);
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat
        return ship;
    }

    /**
     * Sit the bot on the pilot seat of the ship this scenario BUILT — the one it holds the id of,
     * not the one nearest a coordinate.
     *
     * <p>{@code artest vs seat-mount <dim>} takes the first {@code TilePilotSeat} in the world's
     * loaded-tile list, with no position filter — unambiguous when the world holds exactly one ship,
     * and a scenario mounting a NEIGHBOUR's ship once several scenarios share a world. The positional
     * {@code find-seat} was narrower and no cure: it resolves through {@code
     * VSBridge.shipyardBoundsAt}, which answers for the ship NEAREST the anchor — no containment
     * test, no distance bound, over the registry, so an unloaded craft or a blockless crossing
     * remnant is a candidate. Twelve scenarios share dim 0 here.</p>
     *
     * <p>{@code find-seat <dim> id <shipUuid>} resolves the yard from the ship's own name instead, so
     * a wrong craft is unreachable rather than merely unlikely. The base coordinates stay in the
     * signature because the failure message needs them: a reader diagnosing a miss wants to know
     * where the scenario thought its ship was.</p>
     */
    private void mountPilotSeatOfShipAt(int bx, int by, int bz) throws Exception {
        String seat = exec("artest vs find-seat 0 id " + scenarioShipId);
        assertTrue("find-seat must locate the pilot seat inside THIS scenario's ship ("
                + scenarioShipId + ", built at " + bx + "," + by + "," + bz + "): " + seat,
                seat.contains("\"seatFound\":true"));
        String mountInfo = exec("artest vs seat-mount-at 0 " + readInt(seat, SEAT_X) + " "
                + readInt(seat, SEAT_Y) + " " + readInt(seat, SEAT_Z));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount-at must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    /** This scenario's ship, asked by identity. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return exec("artest vs ship-info 0 id " + scenarioShipId);
    }

    /**
     * Is THIS scenario's ship loaded? Asked BY IDENTITY: with one boot per test a whole-dimension
     * ship count had exactly one ship to report on, and on a shared client it answers with whichever
     * neighbour's ship happens to be loaded.
     *
     * <p>It took three coordinates until today and used none of them, so every failure message built
     * around it printed a position the check had never looked at.</p>
     */
    private boolean shipIsLoaded() throws Exception {
        return shipInfo().contains("\"managed\":true");
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private int readInt(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected an integer in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }
}
