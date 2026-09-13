package zmaster587.advancedRocketry.test.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SPIKE: is there a height above a hovering deck where the release gate's WORLD half says "supported"
 * while its SHIP half says "nothing under him"?
 *
 * <h2>The one binary question</h2>
 *
 * <p>A body standing on a deck is released with the reason {@code steppedOntoTerrain} when
 * {@code isSupportedByWorldTerrain(entity) && !isSupportedByShipAt(entity, ship, gate)} — the world
 * says something is under his feet and the ship's own frame says nothing is. That pair has been
 * recorded firing at the APEX OF A JUMP on a craft hovering clear of the ground, which is the one
 * place it should be impossible. This asks whether the pair is reachable from GEOMETRY ALONE, by
 * lifting a body through the band a jump passes through and reading both halves at each height.</p>
 *
 * <h2>It can come back "no", and a "no" is the useful answer</h2>
 *
 * <p>The prediction, made from the source before the run: <b>no such height exists.</b> The world
 * half probes a 0.30-deep slab under the body's WORLD box, and the physics mod injects a ship's
 * blocks into a world collision query only for a player who is SNEAKING — so above a hovering deck
 * that slab is empty air and the world half should read false at every height above the deck's own
 * surface. If that holds, the gate cannot fire from geometry, and what fires it is the OTHER thing a
 * world collision query returns: the collision boxes of nearby ENTITIES, which the ship-frame half
 * can never see because it asks in subspace coordinates where no entity stands.</p>
 *
 * <p>So a green narrows the hunt rather than closing it, and a RED is the band itself, printed with
 * its numbers and the probe's own reply at each offending height.</p>
 *
 * <h2>The controls, because an all-false sweep is otherwise unfalsifiable</h2>
 *
 * <ul>
 *   <li><b>The instrument is answering about the right hull.</b> The capture is asserted anchored on
 *       THIS scenario's ship before the sweep starts; this world holds its siblings' craft too.</li>
 *   <li><b>The witness is sensitive DOWNWARD.</b> Standing on the deck, the ship-frame probe must
 *       report at least one obstacle. Without that reading, "the ship half was false everywhere" has
 *       two causes — no deck under him, or a probe that finds nothing anywhere — and an all-false
 *       sweep cannot tell them apart.</li>
 *   <li><b>The craft is genuinely off the ground.</b> Asserted from its own altitude gain: a deck
 *       resting on terrain makes the world half true for an honest reason, and the sweep would then
 *       be measuring the ground.</li>
 * </ul>
 *
 * <h2>Why each height is read with no ticks in between</h2>
 *
 * <p>The teleport moves the SERVER's copy of the body inside the command, and the probe asks the
 * server; both halves of the gate are recomputed from the body's current box every time they are
 * asked. Letting ticks pass between the two would only let gravity pull him back down, so the
 * height the probe answered about would not be the height that was set — the sweep would smear.</p>
 *
 * <h2>What it read, 2026-09-13 — the prediction held</h2>
 *
 * <pre>
 * deck y=118.476
 * [-1.0 world=false ship=1] [-0.9 world=false ship=0] [-0.8 world=false ship=0]
 * [-0.7 .. +2.0 world=false ship=-1]
 * </pre>
 *
 * <p><b>The world half is false at every height</b>, from a metre below the deck to two metres above
 * it. So over a craft hovering clear of the ground the release gate's world half never says
 * "supported", and the pair that fires {@code steppedOntoTerrain} is <b>not reachable from geometry
 * at all</b> there. What remains able to reach it is the other thing a world collision query
 * returns — the collision boxes of nearby entities, which the ship-frame half can never see — or a
 * disagreement between the two sides' copies of the body.</p>
 *
 * <p><b>The blind spot, stated rather than discovered later.</b> {@code ship=-1} from −0.7 upward is
 * not "no obstacle": it is the ship-frame probe's answer for a body it is not TRACKING. The first
 * teleport down through the deck released the capture, so only the first three samples are about a
 * captured body; everything above measures an untracked one. That does not weaken the reading above,
 * because the world half is computed from the body's own box whether or not anything holds it — but
 * it does mean this sweep says nothing about how the SHIP half behaves through a jump, and a future
 * version that wants that has to re-establish the capture between steps.</p>
 *
 * <p>A spike, and named one: it exercises no player-visible contract. It is kept only while the
 * question it answers is open; when that closes it is deleted and its reading lives in the ledger.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSDeckSupportBandSpikeTest extends AbstractSharedVsClientE2ETest {

    /** The tier-2 fixture: a craft with a pilot seat and a walkable deck, which assembles into a
     *  physics ship. A plain rocket variant assembles into an EntityRocket and never spawns one —
     *  measured 2026-09-13, and the arrangement failure said so in one line. */
    private static final String VARIANT = "with-pilot-deck";
    private static final int BX = 4120, BY = 64, BZ = 4120;

    /** How far above the ground the craft is lifted before the sweep, in blocks. */
    private static final double HOVER_GAIN_BLOCKS = 6.0;

    /** The sweep, relative to where the body rests: down THROUGH the deck, then up through the
     *  height a jump reaches. The lower half is the control — see the sweep loop. */
    private static final double SWEEP_FROM = -1.0;
    private static final double SWEEP_TO = 2.0;
    private static final double SWEEP_STEP = 0.1;

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SEAT_X = Pattern.compile("\"seatX\":(-?\\d+)");
    private static final Pattern SEAT_Y = Pattern.compile("\"seatY\":(-?\\d+)");
    private static final Pattern SEAT_Z = Pattern.compile("\"seatZ\":(-?\\d+)");
    private static final Pattern OBSTACLES = Pattern.compile("\"shipSupportObstacles\":(-?\\d+)");
    private static final Pattern WORLD_SUPPORT =
            Pattern.compile("\"supportedByWorldTerrain\":(true|false)");

    private String shipId;

    @Override
    protected String subsystem() {
        return "vs-deck-support-band";
    }

    @Test
    public void aBodyRaisedOverAHoveringDeckNeverSeesTheWorldHalfAloneSayYes() throws Exception {
        scenario().arranging("assemble a deck craft, board it and lift it clear of the ground");
        buildAndBoard();

        double groundY = readDouble(shipInfoById(shipId), POS_Y);
        hoverOnPilotThrust(shipId, HOVER_GAIN_BLOCKS);
        bot().waitTicks(10);
        String hovering = shipInfoById(shipId);
        double hoverY = readDouble(hovering, POS_Y);
        scenario().record("shipY", groundY + " -> " + hoverY);
        scenario().requireArranged("the craft must be clear of the ground before the sweep means"
                        + " anything: it rose " + (hoverY - groundY) + " blocks (needed > "
                        + (HOVER_GAIN_BLOCKS / 2.0) + "); " + hovering,
                hoverY - groundY > HOVER_GAIN_BLOCKS / 2.0);

        // Off the seat and onto the deck: the gate under test is the ABOARD one, and a seated pilot
        // is in an excluded state that never reaches it.
        long offSeat = clientEvents().mark();
        exec("artest player dismount");
        awaitClientDismount(offSeat, "the pilot must be off his seat and standing on the deck before"
                + " the standing gate can be asked about him", 200);
        bot().waitTicks(20);

        scenario().measuring("both halves of the release gate at every height a jump passes through");
        String standing = deckCaptureOfThisShip(shipId, "the body must be captured on THIS"
                + " scenario's deck before either half of the gate means anything");
        scenario().record("standingCapture", standing);
        JsonObject onDeck = bot().reportState();
        double footX = onDeck.get("playerX").getAsDouble();
        double footY = onDeck.get("playerY").getAsDouble();
        double footZ = onDeck.get("playerZ").getAsDouble();
        scenario().record("footOnDeck", footX + "," + footY + "," + footZ);

        // The sweep STARTS BELOW the body's resting height, and that lower half is the control.
        //
        // Asking for "the ship half says yes where he stands" as a separate precondition turned out
        // to ask for something a hovering craft need not provide: measured here, a body the capture
        // holds sits 0.4 above the point the capture committed (bodyShipFrameY 126.4 against
        // shipFrameY 126.0) and the hull is CARRYING him rather than being under him, so the 0.30
        // probe finds nothing and the reading is correct. Sweeping down through the deck instead
        // makes the witness prove its own sensitivity inside the same experiment: somewhere below
        // him the ship half must say yes, or every false above says nothing about the gate and
        // everything about the probe.
        List<String> band = new ArrayList<>();
        StringBuilder trace = new StringBuilder();
        boolean shipEverSaidYes = false;
        for (int step = (int) Math.round(SWEEP_FROM / SWEEP_STEP);
                step * SWEEP_STEP <= SWEEP_TO + 1e-9; step++) {
            double lift = step * SWEEP_STEP;
            exec(String.format(Locale.ROOT, "tp @a %.4f %.4f %.4f", footX, footY + lift, footZ));
            String reply = exec("artest vs deck-capture");
            boolean world = Boolean.parseBoolean(text(reply, WORLD_SUPPORT));
            int ship = readIntOr(reply, OBSTACLES, -1);
            shipEverSaidYes |= ship > 0;
            trace.append(String.format(Locale.ROOT, "[%+.1f world=%b ship=%d] ", lift, world, ship));
            if (world && ship == 0) {
                band.add(String.format(Locale.ROOT, "lift=%+.1f reply=%s", lift, reply));
            }
        }
        scenario().record("sweep", trace.toString());
        System.out.println("[supportband] deck y=" + footY + " :: " + trace);

        assertTrue("CONTROL: somewhere in the sweep the SHIP half must find an obstacle under the"
                        + " feet — it never did, so this run says nothing about the gate and"
                        + " everything about the probe. The body may not be over the deck at all,"
                        + " or the ship-frame query may be answering about the wrong place. sweep: "
                        + trace + " || standing: " + standing,
                shipEverSaidYes);

        scenario().asserting("the two halves of the gate never disagree from geometry alone");
        assertFalse("THE BAND EXISTS: at the heights below, the WORLD half of the release gate says"
                        + " the body is supported while the SHIP half finds nothing under him —"
                        + " which is exactly the pair that fires `steppedOntoTerrain` over a deck the"
                        + " craft is hovering clear of the ground. Each entry carries the probe's own"
                        + " reply. " + String.join(" | ", band)
                        + " || full sweep: " + trace,
                !band.isEmpty());
    }

    // ---- arrangement ------------------------------------------------------------------------

    private void buildAndBoard() throws Exception {
        int cx1 = (BX - 2) >> 4, cz1 = (BZ - 2) >> 4;
        int cx2 = (BX + 7) >> 4, cz2 = (BZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (BX - 2) + " " + (BY + 1) + " " + (BZ - 2)
                        + " " + (BX + 7) + " " + (BY + 10) + " " + (BZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + BX + " " + BY + " " + BZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());

        // Stand well clear while it assembles, then come BACK to it: nothing here force-loads the
        // craft, so what keeps the physics object alive is a real player's proximity — the substrate's
        // own mechanism. Assembled with the bot beside it, the spawn and the load race each other;
        // assembled with the bot away and never returned to, the ship spawns, loads and UNLOADS again
        // before `ship_usable` can be awaited. Measured here on the first run: `ship_spawned,
        // ship_loaded, ship_unloaded` in that order, and the wait then spent its whole budget.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        Events events = events();
        long assemblyMark = events.markInstrumented();
        String assemble = exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " "
                + bp.group(3));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));
        assertTrue("a with-pilot-seat build must route to a SHIP, not a rocket: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        shipId = awaitShipSpawned(events, assemblyMark,
                "the assembled craft must become a ship before anything can be asked of its deck");
        bot().waitTicks(40);

        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);
        // From the SAME mark: `ship_usable` fires once per load and is not a state to poll, so a
        // mark taken after the teleport could miss the load the teleport caused.
        awaitShipUsable(events, assemblyMark, shipId);

        String seat = exec("artest vs find-seat 0 id " + shipId);
        assertTrue("find-seat must locate the pilot seat: " + seat,
                seat.contains("\"seatFound\":true"));
        String mount = exec("artest vs seat-mount-at 0 " + readIntOr(seat, SEAT_X, 0)
                + " " + readIntOr(seat, SEAT_Y, 0) + " " + readIntOr(seat, SEAT_Z, 0));
        Matcher dm = DUMMY_ID.matcher(mount);
        assertTrue("seat-mount-at must report a dummy id: " + mount, dm.find());
        assertTrue("the bot must mount the seat dummy: " + mount,
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
        bot().waitTicks(20);
    }

    // ---- reading ----------------------------------------------------------------------------

    private static double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static int readIntOr(String json, Pattern p, int fallback) {
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String text(String json, Pattern p) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
