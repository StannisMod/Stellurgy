package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Ignore;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

/**
 * SPIKE e2e: is a tier-2 ship CONTROLLABLE — and does the real client keep tracking it — at extreme
 * world Y, just under the TOP of the cells' realized pose band, so the whole advertised vertical
 * range is evidenced and not only the middle? The altitude is DERIVED from the band's own
 * production constants (see {@link #EXTREME_Y}) rather than written down, so it follows the band
 * when the band moves. The honest-Y realization
 * question: entities are NOT capped by the 256
 * build height (blocks are; vanilla's only hard line for entities is the void-kill below −64), so a
 * ship's world-frame pose can realize a galactic local-Y directly. A green run = GO for amending
 * the planar realization rule to an honest Y mapping.
 *
 * <p>The leg re-runs the SAME full-path pilot contract as the in-run control (real seated bot, real
 * vertical-up key, ship climbs; client rider tracks the server ship) — so a FAIL localises to the
 * coordinate regime, not to the pilot path. The arrange step is the rigid ship teleport
 * ({@code vs teleport-ship}: pose moves, subspace blocks stay, VS Y-limits widen, riders carried).
 *</p>
 *
 * <p>Findings recorded while building this spike:
 * (1) VS's load controller UNLOADS the teleported ship's physics object even with the pilot aboard
 * — {@code permanentlyLoaded} is the workaround here, production honest-Y must own loadedness;
 * (2) a VS collision mixin ({@code preGetCollisionBoxes}) prints a console line EVERY TICK for an
 * entity at extreme Y — log flood, and it races probe replies;
 * (3) after a SECOND relocation the ship's physics goes inert (neither pilot key nor push-ship
 * moves it) and the pilot-key path dies after a dismount&rarr;re-seat across the map — both are
 * relocation-SEQUENCE findings needing their own ordinary-coordinates control; the extreme-|X|
 * precision leg stays an open follow-up until they are resolved.</p>
 */
public class VSShipExtremeCoordinatesE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SHIP_ID = Pattern.compile("\"id\":\"([^\"]*)\"");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 3400, BY = 64, BZ = 3400;

    /**
     * How far below the TOP of the realized pose band this scenario flies, in blocks. The margin is
     * the quantity — it says "near the ceiling, with room to climb" — and the ceiling itself is read
     * from production rather than copied into a literal.
     */
    private static final double BELOW_BAND_TOP = 1_000d;

    /**
     * The extreme altitude under test: just under the top of the band a cell's poses are realized in.
     *
     * <p><b>DERIVED, never a literal.</b> {@code CellWorldMapper} maps a cell's local Y to
     * {@code local + HALF_CELL + POSE_BAND_Y}, so the band occupies world
     * {@code [POSE_BAND_Y, CELL + POSE_BAND_Y)} — and both of those are production constants that
     * MOVE. This test carried {@code 3_999_000} as a literal, chosen when a cell was 4,000,000
     * blocks; the cell became 32,000,000 on 2026-08-20 and the literal silently stopped meaning
     * "near the top of the range" — it became a point in the lower eighth of it, so the scenario
     * stopped evidencing the thing its own javadoc says it evidences. A test that hard-codes a
     * coordinate the product derives is pinned to an implementation detail, and it goes on passing
     * or failing for reasons that have nothing to do with its subject.</p>
     */
    private static final double EXTREME_Y =
            (double) (zmaster587.advancedRocketry.space.GalacticCoord.CELL
                    + zmaster587.advancedRocketry.space.CellWorldMapper.POSE_BAND_Y)
                    - BELOW_BAND_TOP;

    /**
     * How far the client-rendered rider may be from the server ship it is glued to, in blocks — the
     * same tolerance {@link #climbLeg} uses for the tracking it measures during a climb.
     */
    private static final double RIDER_TRACKING_TOLERANCE = 3.0;

    /**
     * How far from its base a {@code ship-info} answer may be and still be attributed to this
     * scenario's freshly assembled ship, in blocks — spent ONCE, on the capture below.
     */
    private static final int SHIP_QUERY_RADIUS = SHIP_CAPTURE_RADIUS_BLOCKS;

    /**
     * This scenario's ship, by IDENTITY. Captured once at the base, where the ship is the only
     * thing that can be there, and used for every question afterwards.
     *
     * <p>The positional form of {@code ship-info} is a NEAREST-ship lookup, and this scenario spends
     * its whole length making that lookup meaningless on purpose: the ship is rigid-teleported to
     * {@link #EXTREME_Y} and then flown further. A query point that trails the ship answers
     * about a neighbour or about nothing, and both replies have the shape of a correct one — so a
     * red here would describe a craft the test never built, which is a worse outcome than the red
     * it is trying to explain.</p>
     */
    private String shipId;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Ignore("The arrangement can no longer produce the state under test. This scenario reaches"
            + " extreme Y by rigid-teleporting a ship in an ORDINARY world, and an ordinary world now"
            + " has an orbit line: measured 2026-08-21, the teleport is immediately followed by an"
            + " entry crossing that takes the craft into a space cell under a new identity, before"
            + " the first assertion runs. No altitude is both extreme and still in an ordinary world."
            + " The pose band is realized inside CELL worlds, so that is where this scenario belongs"
            + " and re-homing it there is the fix. Note also that the three 'findings' in the class"
            + " javadoc below were all recorded under this arrangement and therefore describe a"
            + " crossing rather than extreme coordinates; they must be re-taken before being cited.")
    @Test
    public void aSeatedPilotKeepsControlAtExtremeY() throws Exception {

        // ── Arrange: assemble a piloted ship at the base site (same recipe as the pilot-keys e2e). ──
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship (all=" + all + ")", all >= 1);
        bot().waitTicks(40);
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);
        double y0 = Double.NaN;
        for (int i = 0; i < 40 && Double.isNaN(y0); i++) {
            bot().waitTicks(5);
            if (count("ship-count") >= 1) {
                // The one defensible positional query in this test: the ship is freshly assembled,
                // has not moved, and the bound cannot admit anything else. Its ANSWER is the id.
                String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ
                        + " " + SHIP_QUERY_RADIUS);
                if (info.contains("\"managed\":true")) {
                    shipId = readShipId(info);
                    y0 = readDouble(info, POS_Y);
                }
            }
        }
        assertTrue("the ship must LOAD with the client present", !Double.isNaN(y0));
        assertTrue("the loaded ship must report an identity to key the scenario on", shipId != null);

        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy",
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
        bot().waitTicks(10);

        // SPIKE FINDING (recorded): after a rigid teleport to extreme Y, VS's load controller UNLOADS
        // the physics object even with the pilot aboard ("managed":false) — the ship exists but stops
        // ticking. permanentlyLoaded is the documented headless-observation lever; production honest-Y
        // realization must own ship loadedness explicitly at extreme poses.
        assertTrue(exec("artest vs permaload true").contains("\"ok\":true"));

        // ── CONTROL leg: the pilot path works at ordinary coordinates (proves the instrument fires). ──
        climbLeg("control @ base");

        // ── Leg 1: the top of the pose band. Both commands name the SHIP, not a place: the source
        // pose is the probe's business (it reads the registry) and the destination is somewhere the
        // ship has never been, so neither end is a point this test can address from. ──
        String tpY = exec("artest vs teleport-ship-by-id 0 " + shipId
                + " " + BX + " " + EXTREME_Y + " " + BZ);
        assertTrue("teleport-ship to extreme Y must succeed: " + tpY, tpY.contains("\"ok\":true"));
        bot().waitTicks(30); // transform adoption + rider sync settle
        String unparked = exec("artest vs unpark-by-id 0 " + shipId);
        assertTrue("the teleport leaves the ship PARKED by VS's own recipe, and a parked ship cannot"
                + " be flown — the unpark must take: " + unparked, unparked.contains("\"ok\":true"));
        bot().waitTicks(10);
        String serverInfoAfterTp = shipInfoById();
        assertTrue("ARRANGEMENT: the teleported ship must still be loaded, or there is no server "
                        + "pose for the rider to be compared against: " + serverInfoAfterTp,
                serverInfoAfterTp.contains("\"managed\":true"));

        // THE CONTRACT, and it names no coordinate: a rider is glued to his ship, so wherever the
        // ship ends up the client must render him THERE. Asserting he reached a particular altitude
        // instead would pin the arrangement's own request — and did: the old form compared him to a
        // hard-coded destination, so it could fail either because the rider came adrift or because
        // the ship never went where it was sent, and the message could not tell the two apart.
        double shipYAfterTp = readDouble(serverInfoAfterTp, POS_Y);
        double riderY = bot().reportRidingEntity().get("posY").getAsDouble();
        assertTrue("the CLIENT-rendered rider must arrive WITH his ship: rider=" + riderY
                        + " ship=" + shipYAfterTp + " (apart by "
                        + Math.abs(riderY - shipYAfterTp) + " blocks); commanded=" + EXTREME_Y
                        + "; server ship after teleport: " + serverInfoAfterTp,
                Math.abs(riderY - shipYAfterTp) < RIDER_TRACKING_TOLERANCE);

        // Separately, and only after the tracking question is settled: the rigid teleport must have
        // put the ship where it was TOLD to go. Two facts, two assertions — a single one comparing
        // the rider to the request conflates them.
        assertTrue("teleport-ship must leave the ship at the altitude it was given: commanded="
                        + EXTREME_Y + " ship=" + shipYAfterTp,
                Math.abs(shipYAfterTp - EXTREME_Y) < 200);
        climbLeg("extreme Y");

        // The extreme-|X| leg is NOT automated yet — see the class javadoc: after a SECOND
        // relocation the ship's physics goes inert (neither the pilot key nor the push-ship
        // velocity setpoint moves it) and the pilot-key path dies after a dismount->re-seat across
        // the map. Both are relocation-sequence findings, not coordinate-regime ones; the XZ
        // precision leg stays an open follow-up until they are resolved.

        exec("artest player dismount");
        exec("artest vs permaload false");
    }

    /**
     * One controllability measurement at the ship's current location: hold the REAL vertical-up key,
     * the server ship must climb, and the CLIENT-rendered rider must climb WITH it (tracking within
     * the same tolerance the ordinary-coordinates pilot e2e uses — a precision breakdown at extreme
     * coordinates shows up here as divergence).
     */
    private void climbLeg(String label) throws Exception {
        double yBefore = shipY();
        double riderYBefore = bot().reportRidingEntity().get("posY").getAsDouble();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 100-iteration budget
            // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
            lift = ClientPoll.until(bot()::waitTicks,
                    this::shipY,
                    y -> y - yBefore > 1.5, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double yAfter = lift.value;
        assertTrue("[" + label + "] the vertical-up key must lift the ship (yBefore=" + yBefore
                + " yAfter=" + yAfter + ")", yAfter - yBefore > 1.0);
        bot().waitTicks(6);
        double serverDelta = shipY() - yBefore;
        double riderDelta = bot().reportRidingEntity().get("posY").getAsDouble() - riderYBefore;
        // Third witness on divergence: the SERVER-side player position separates "the seat glue died
        // server-side" (server player static too) from "the client stopped tracking" (server player
        // climbed, client did not).
        String serverPlayer = exec("artest player health");
        assertTrue("[" + label + "] the CLIENT rider must track the server ship's climb (client="
                + riderDelta + " server=" + serverDelta + "); server player: " + serverPlayer,
                Math.abs(riderDelta - serverDelta) < 3.0);
    }

    /** The report for THIS scenario's ship, wherever it now is — no distance term to be wrong about. */
    private String shipInfoById() throws Exception {
        return exec("artest vs ship-info 0 id " + shipId);
    }

    /**
     * The server ship's posY, tolerant of unrelated console lines interleaving with the probe's JSON
     * reply (at extreme coordinates a VS collision mixin spams STDERR lines, which can arrive inside
     * the captured console window) — retry until a parseable reply comes back.
     *
     * <p>A ship that has UNLOADED answers {@code managed:false} and carries no {@code posY}, so it
     * exhausts the retries and fails naming the reply. That is the intended report: "this ship is
     * not loaded" is a different fact from "the ship near this point moved", and the positional form
     * this replaced could not tell them apart.</p>
     */
    private double shipY() throws Exception {
        String last = "";
        for (int i = 0; i < 10; i++) {
            last = shipInfoById();
            Matcher m = POS_Y.matcher(last);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
            bot().waitTicks(2);
        }
        throw new AssertionError("ship-info never returned a parseable posY for ship " + shipId
                + "; last reply: " + last);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The {@code "id"} field of a {@code ship-info} reply, or null when it carries none. */
    private static String readShipId(String shipInfoJson) {
        Matcher m = SHIP_ID.matcher(shipInfoJson);
        return m.find() && !m.group(1).isEmpty() ? m.group(1) : null;
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
