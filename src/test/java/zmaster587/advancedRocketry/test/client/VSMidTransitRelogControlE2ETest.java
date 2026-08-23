package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.space.GalacticCoord;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

/**
 * A pilot who RELOGS in the middle of a hyperspace transit regains control ON ARRIVAL: after the
 * jump completes he is seated on his ship in the target cell and his held key flies it again — no
 * re-board, no re-click. (During the transit park itself the ship ignores input by design; what
 * this pins is that a mid-transit relog does not sever the control chain the arrival hands back.)
 *
 * <p><b>Why this must be a client test.</b> The relog is the one seam every lower tier fakes: the
 * crew record captured at departure references the pre-relog player entity, and a fresh login
 * replaces that entity wholesale. Whether the arrival's re-seating finds the RETURNED player — and
 * whether his client's input chain then reaches the arrived ship's computer — is observable only
 * with a real client logging out and back in around a real (probe-driven) transit.</p>
 *
 * <p><b>Shape.</b> The probe transit stack of the crewed-transit scenarios, but over a ship
 * that can actually FLY: {@code space transit-setup-empty} installs the stack with an EMPTY origin
 * cell, and the real {@code with-pilot-seat} fixture is built there with the real assembler — the
 * piloted setup's bare 3x3 deck has no propulsion, so a held key can move nothing and a control
 * pin on it is vacuous (measured: it slowly SINKS instead). Plus: a landing platform under the
 * ship's berth (the departure cuts the deck out from under the standing-by crew, and a pilot
 * mid-relog must not be falling into the void while the test drives the jump), a real
 * {@code reconnect} between departure and arrival, and the planet-side relog pin's held-key climb
 * as the load-bearing acceptance. The transit only advances when the probe ticks it, so the park
 * deterministically outlasts the relog.</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSMidTransitRelogControlE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-mid-transit-relog-control";
    }

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    private static final Pattern SHIP_ID = Pattern.compile("\"id\":\"([^\"]*)\"");

    /** A demonstrable held-key climb: well above settle jitter, cheap to reach. */
    private static final double MIN_CLIMB = 1.0;

    @Test
    public void aPilotWhoRelogsMidTransitRegainsControlOnArrival() throws Exception {

        // Headless: pin ships loaded so the assembled ship survives between probe calls.
        exec("artest vs permaload true");

        // ---- ARRANGE: the transit stack over an EMPTY origin cell, then a real FLYABLE piloted
        // ship built there with the real assembler. --------------------------------------------
        String setup = exec("artest space transit-setup-empty");
        assertTrue("empty transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");

        int bx = 40, by = 64, bz = 40;
        scenario().requireArranged("chunk warmup failed",
                exec("artest chunk warmup " + originDim + " " + ((bx - 2) >> 4) + " " + ((bz - 2) >> 4)
                        + " " + ((bx + 7) >> 4) + " " + ((bz + 7) >> 4)).contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket " + originDim + " " + bx + " " + by + " " + bz
                + " with-pilot-seat");
        scenario().requireArranged("fixture (with-pilot-seat) failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]").matcher(fixture);
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp.find());
        String assembled = exec("artest rocket assemble " + originDim
                + " " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assembled,
                assembled.contains("\"rocketCount\":0"));
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim "
                + originDim + ")", waitForLoadedShip(originDim) >= 1);

        // The scenario's ship, by IDENTITY, captured at the ONE moment a positional lookup is
        // defensible: freshly assembled, still at its own build site, inside a bound no other
        // craft could satisfy. Every question afterwards is keyed on this — the ship is about to
        // be flown, departed and re-materialised in another cell, and a query point left behind at
        // the build site would answer about a neighbour (a transit cell is a POOL slot and routinely
        // holds an earlier scenario's leavings) or about nothing, in the shape of a correct reply.
        String shipId = captureShipIdNear(originDim, bx + 3, by + 3, bz + 3);

        String seat = exec("artest vs find-seat " + originDim + " id " + shipId);
        assertTrue("the pilot seat must be found in the assembled ship (else the test is vacuous): "
                + seat, readBool(seat, "seatFound"));
        int seatX = readInt(seat, "seatX"), seatY = readInt(seat, "seatY"), seatZ = readInt(seat, "seatZ");
        int sx = (int) Math.round(readDouble(seat, "shipWorldX"));
        int sy = (int) Math.round(readDouble(seat, "shipWorldY"));
        int sz = (int) Math.round(readDouble(seat, "shipWorldZ"));

        // A landing platform under the berth: the departure cuts the ship out from under its
        // standing-by crew, and a pilot who relogs mid-transit resumes FALLING at login — over a
        // void cell he would be dead before the arrival could re-seat him. Geometry measured off
        // the ship's own world pose, not assumed.
        scenario().requireArranged("the landing platform must build: ",
                exec("artest fill " + originDim + " " + (sx - 12) + " " + (sy - 8) + " " + (sz - 12)
                        + " " + (sx + 12) + " " + (sy - 8) + " " + (sz + 12) + " minecraft:stone")
                        .contains("\"ok\":true"));

        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        String enter = exec("artest space enter " + botName + " " + originDim
                + " " + sx + " " + sy + " " + sz);
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        bot().waitTicks(20);
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        String mountAt = "", mount = "";
        boolean mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            mountAt = exec("artest vs seat-mount-at " + originDim
                    + " " + seatX + " " + seatY + " " + seatZ);
            assertTrue("seat-mount-at must spawn the seat dummy: " + mountAt, readBool(mountAt, "ok"));
            mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        assertTrue("the bot must mount the pilot-seat dummy (5 spawn+mount attempts): " + mount,
                mounted);
        bot().waitTicks(10);
        assertTrue("the bot must be seated BEFORE the jump (control): " + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        // CONTROL LEG (pre-transit): the seated pilot's REAL key must fly the ship in the origin
        // cell BEFORE anything happens to him — a dead key after the arrival could otherwise be a
        // chain that never worked here at all. Retried on a bounded budget: right after the async
        // assembly the ship can still be settling (measured: the first climb window sometimes
        // catches it sinking), and the contract is a bounded window, not the first ten seconds.
        scenario().requireArranged("control leg: the pilot must be able to fly BEFORE the transit."
                + " delivery=" + exec("artest vs seat-delivery")
                + " ship=" + shipInfoById(originDim, shipId),
                climbedWithinAttempts(3));
        bot().waitTicks(30); // let the station-hold settle before the departure snapshot

        // The climb moved the ship: the departure anchor is its CURRENT pose, never the build pose.
        String shipNow = shipInfoById(originDim, shipId);
        assertTrue("the ship must still be managed at its berth: " + shipNow,
                shipNow.contains("\"managed\":true"));
        int ax = (int) Math.round(readDouble(shipNow, "posX"));
        int ay = (int) Math.round(readDouble(shipNow, "posY"));
        int az = (int) Math.round(readDouble(shipNow, "posZ"));

        // ---- ACT 1: depart into hyperspace. The reduced speed sizes the park at ~40 probe-driven
        // ticks (the cells sit one 4M-block sector apart), so the relog lands INSIDE the transit
        // instead of racing a single-tick jump. ---------------------------------------------------
        String begin = exec("artest space transit-begin " + originDim
                + " " + ax + " " + ay + " " + az + " " + HYPERSPACE_JUMP_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));
        String firstTick = exec("artest space transit-tick 10");
        assertTrue("the ship must actually be IN TRANSIT when the pilot relogs — otherwise this "
                + "pins an ordinary relog, not the mid-transit one: " + firstTick,
                readInt(firstTick, "inTransit") >= 1);

        // ---- ACT 2: the real mid-transit relog. The transit is probe-driven, so the park waits
        // out the relog deterministically — no race between the login and the arrival. -----------
        bot().reconnect();
        bot().waitForWorld();

        // ---- ACT 3: drive the jump to arrival. --------------------------------------------------
        int targetDim = -1;
        String lastTick = "";
        // No fork multiplier: each iteration advances the transit ten ticks BY HAND, so the budget
        // is a count of pumps and a slow box does not need extra ones to cover the same flight.
        int arriveBudget = 80;
        for (int i = 0; i < arriveBudget && targetDim < 0; i++) {
            lastTick = exec("artest space transit-tick 10");
            if (readInt(lastTick, "inTransit") == 0) {
                targetDim = readInt(lastTick, "targetDim");
                break;
            }
            bot().waitTicks(2);
        }
        assertTrue("the jump never completed (still in transit); last tick=" + lastTick,
                targetDim >= 0);

        // The arrival re-seating is retry-based and completes a few ticks after inTransit hits 0 —
        // keep ticking to drive the retries while observing the CLIENT.
        boolean seatedOnArrival = false;
        // A pump count too - the retries this drives are driven by these same hand-advanced ticks.
        int reseatBudget = 60;
        String lastReseatTick = "";
        for (int i = 0; i < reseatBudget && !seatedOnArrival; i++) {
            lastReseatTick = exec("artest space transit-tick 10");
            bot().waitTicks(2);
            seatedOnArrival = bot().reportRidingEntity().get("riding").getAsBoolean()
                    && bot().reportWeather().get("dim").getAsInt() == targetDim;
        }

        // ---- ASSERT 1: the relogged pilot is SEATED on his ship in the target cell. -------------
        // The arrival re-seat is the one leg of this scenario that fails MUTELY — it drops its
        // pending entry without logging, unlike the departure boarding leg — so the failure
        // message carries the server's own account of it: whether the retry loop was still
        // running when we stopped ticking (`reseating`), where the seat match stopped
        // (`reseatBlock`), and who wrote the rider's position last (the arrival trace). Without
        // them a red here says only "not riding", which names no step.
        JsonObject riding = bot().reportRidingEntity();
        assertTrue("a pilot who relogged mid-transit must be re-seated on his ship ON ARRIVAL: "
                + riding + " (targetDim=" + targetDim
                + ", clientDim=" + bot().reportWeather().get("dim").getAsInt() + ")"
                + " lastTick=" + lastReseatTick
                + " arrival=" + exec("artest vs arrival-trace"),
                riding.get("riding").getAsBoolean());
        assertTrue("the re-mounted entity must be the ship's seat dummy: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertEquals("the relogged pilot must have followed his ship into the target cell",
                targetDim, bot().reportWeather().get("dim").getAsInt());

        // ---- ASSERT 1b: he is OUT IN THE CELL, not in the paste lane. A cell realizes its
        // coordinates in the POSE band (world Y = local Y + half a cell + the band offset), while an
        // arrival pastes its blocks into the ordinary block band near Y=200. If the ship is left
        // where it was pasted, the pilot rides ~2M blocks below everything the destination system
        // holds — every body, every other ship — and his own flight computer then reports an address
        // in the cell BELOW. The client's rendered altitude is the honest witness: a block-band
        // arrival can never reach half a cell.
        double arrivedY = clientPlayerY();
        assertTrue("the arrived pilot must be in the destination cell's pose band, not the paste"
                        + " lane: client-rendered Y=" + arrivedY + " (pose band starts at "
                        + GalacticCoord.HALF_CELL + ", the paste lane sits near 200)",
                arrivedY >= GalacticCoord.HALF_CELL);

        // ---- ASSERT 2 (load-bearing): control RESUMES on arrival — the held key flies the -------
        // arrived ship. A restored seat with a dead key is a broken chain, and it is exactly what
        // a stale pre-relog crew reference would produce. Same bounded retry as the pre-leg: the
        // just-crossed ship settles asynchronously in its target cell.
        assertTrue("after a mid-transit relog, held input must MOVE THE ARRIVED SHIP - control "
                + "resumes on arrival. delivery=" + exec("artest vs seat-delivery"),
                climbedWithinAttempts(3));
    }

    @After
    public void cleanup() {
        try {
            exec("artest player dismount");
            exec("artest vs permaload false");
        } catch (Exception ignored) {
        }
    }

    // --- helpers (mirror the tier-2 client e2e classes) -----------------------------------------

    /** Hold {@code key} until the client-rendered rider altitude climbs {@link #MIN_CLIMB} over
     *  {@code from} (bounded, early-exit); returns the last observed altitude. */
    private double climbWith(int key, double from) throws Exception {
        // THE MULTIPLIER STAYS, and this is what it waits on: a held key is sampled and re-sent per
        // CLIENT TICK - on change, plus a re-assert every PilotInputCadence.REPEAT_TICKS - so a loaded
        // box stretches the climb through the client's TICK rate. Wall-clock-bound work, which is the
        // one shape a fork scale measures. (NOT "once per rendered frame": that was the standing
        // explanation until 2026-08-21 and it is false.)
        int budget = (int) (40 * TestTimeouts.factor());
        double last = from;
        bot().holdKey(key);
        try {
            for (int i = 0; i < budget && (last - from) < MIN_CLIMB; i++) {
                bot().waitTicks(5);
                last = clientPlayerY();
            }
        } finally {
            bot().releaseKey(key);
        }
        return last;
    }

    /** Up to {@code attempts} bounded held-key climb windows with a settle between them; true as
     *  soon as one window sees the client-rendered altitude gain {@link #MIN_CLIMB}. */
    private boolean climbedWithinAttempts(int attempts) throws Exception {
        for (int i = 0; i < attempts; i++) {
            double from = clientPlayerY();
            double to = climbWith(Keyboard.KEY_R, from);
            if ((to - from) >= MIN_CLIMB) {
                return true;
            }
            bot().waitTicks(40);
        }
        return false;
    }

    /** The client's own rendered player altitude, or NaN while it has no world/player. */
    private double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces the load). */
    /**
     * The IDENTITY of the ship freshly assembled near {@code (x,y,z)} — the value every later
     * question about it is keyed on.
     *
     * <p>The bound is spent HERE and nowhere else: the positional form of {@code ship-info} reports
     * whichever loaded ship is nearest a point, and 48 blocks around a build site that has just
     * produced one ship is the only place in this scenario where that cannot mean somebody else.</p>
     */
    private String captureShipIdNear(int dim, int x, int y, int z) throws Exception {
        String info = "";
        for (int attempt = 0; attempt < 40; attempt++) {
            info = exec("artest vs ship-info " + dim + " " + x + " " + y + " " + z
                    + " " + SHIP_CAPTURE_RADIUS_BLOCKS);
            if (info.contains("\"managed\":true")) {
                Matcher m = SHIP_ID.matcher(info);
                if (m.find() && !m.group(1).isEmpty()) {
                    return m.group(1);
                }
            }
            bot().waitTicks(5);
        }
        throw new AssertionError("ARRANGEMENT: the assembled ship never reported an identity at its"
                + " own build site (" + x + "," + y + "," + z + ") in dim " + dim
                + "; last reply: " + info);
    }

    /**
     * The report for the NAMED ship, wherever it now is. {@code managed:false} here means that ship
     * is not loaded — never "it is somewhere else", which is the point of asking this way.
     */
    private String shipInfoById(int dim, String shipId) throws Exception {
        return exec("artest vs ship-info " + dim + " id " + shipId);
    }

    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = readIntOr(exec("artest vs ship-count " + dim), "count", -1);
                if (loaded >= 1) {
                    return loaded;
                }
            }
            bot().waitTicks(5);
        }
        return 0;
    }

    private static int readInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("expected int \"" + key + "\" in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static int readIntOr(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.E\\-]+)").matcher(json);
        assertTrue("expected number \"" + key + "\" in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static boolean readBool(String json, String key) {
        return Pattern.compile("\"" + key + "\":true").matcher(json).find();
    }
}
