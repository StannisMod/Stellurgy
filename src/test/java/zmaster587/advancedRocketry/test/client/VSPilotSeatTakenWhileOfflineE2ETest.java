package zmaster587.advancedRocketry.test.client;

import zmaster587.advancedRocketry.test.GameTicks;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A pilot seat TAKEN while its pilot was OFFLINE stays with the occupant. The returning pilot is
 * restored STANDING aboard his ship — never seated, never fighting the occupant for the chair —
 * and is told, by name, who took his seat. And through it all the seat keeps exactly ONE bound
 * mount dummy: vanilla persists a seated player's mount inside his own player data and re-spawns
 * it at login, so without a reconciliation the returning pilot brings a DUPLICATE dummy back with
 * him — two invisible mounts on one seat, the empty one clearing the ship's pilot input every tick
 * (a control tug-of-war), and two riders both believing they hold the chair.
 *
 * <p><b>Why this must be a client test.</b> The subject is a live player's LOGIN — the one seam
 * every lower tier fakes. The offline window is real: the client genuinely quits the server (his
 * player data, mount included, is written to disk), the seat is taken while the world runs without
 * him, and his return is a real fresh login that re-reads that data. The refusal message is read
 * off the returning client's own action bar, the not-seated outcome off its own riding report.</p>
 *
 * <p><b>Subject on the hard side:</b> a real ASSEMBLED ship (the seat block lives in ship
 * subspace, its dummy at the seat's live world position — the frame split that every seat-binding
 * bug in this repo has lived in), not a loose world seat. The boarding is the {@code vs seat-mount}
 * probe + mount-entity (the harness cannot right-click a post-assembly ship-subspace block); the
 * occupant is the {@code vs seat-occupy} armor stand (no AI, holds the seat indefinitely — a
 * second human client is not needed to make the seat contested).</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSPilotSeatTakenWhileOfflineE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-pilot-seat-offline";
    }

    /**
     * World the server is given to notice the logout, in SERVER ticks - the old 40 x 250 ms with a
     * fork multiplier on top. The client cannot supply a clock here: it is the thing that went away.
     */
    private static final int LOGOUT_TICKS = 200;

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern OCCUPANT_NAME = Pattern.compile("\"occupantName\":\"([^\"]+)\"");
    private static final Pattern OCCUPANT_ID = Pattern.compile("\"occupantId\":(-?\\d+)");
    private static final Pattern BOUND_COUNT = Pattern.compile("\"boundCount\":(-?\\d+)");
    private static final Pattern SEAT_AT = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern POS = Pattern.compile(
            "\"posX\":(-?[0-9.E\\-]+),\"posY\":(-?[0-9.E\\-]+),\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 7600, BY = 64, BZ = 7600;

    /** The account the client harness plays under — the server keys his data and probes by it. */
    private static final String BOT = "ForgeTestClient";

    /**
     * How far off his seat the restored-standing pilot may be and still count as "aboard at his
     * post". Covers the deck spot beside/under the seat plus settle drift; far too small to be
     * satisfied by a world-spawn fallback or a fall off the hull.
     */
    private static final double ABOARD_EPSILON = 6.0;

    @Test
    public void aSeatTakenWhileThePilotWasOfflineStaysWithTheOccupant() throws Exception {

        // ---- ARRANGE: build + assemble a piloted ship, seat the client player on it. ------------
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        int shipsBefore = count("ship-count-all");
        String assemble = assembleFixture(BX, BY, BZ);
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        // THE FORK MULTIPLIER SURVIVES HERE, and this is what it is waiting on: VS builds the ship on
        // its OWN thread, off the game loop entirely. That work finishes in wall-clock time, so a busy
        // box genuinely needs more game ticks to elapse before it is done — which is the one shape
        // where scaling a tick ceiling by the fork count is measuring the right thing. Contrast the
        // logout wait below, which is server-tick work and carries no multiplier at all.
        int assemblyBudget = (int) (40 * TestTimeouts.factor());
        int all = shipsBefore;
        for (int i = 0; i < assemblyBudget && all <= shipsBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        scenario().requireArranged("assembly must create a NEW VS ship (was " + shipsBefore
                + ", now " + all + ")", all > shipsBefore);
        // Keep the ship observable while nobody is online: the offline window below leaves the
        // server empty, and an unloaded ship would fail every probe the arrangement depends on.
        exec("artest vs permaload true");
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(40);

        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        scenario().requireArranged("seat-mount must report a dummy id: " + mountInfo, dm.find());
        Matcher sm = SEAT_AT.matcher(mountInfo);
        scenario().requireArranged("seat-mount must report the seat's block pos: " + mountInfo,
                sm.find());
        final int seatX = Integer.parseInt(sm.group(1));
        final int seatY = Integer.parseInt(sm.group(2));
        final int seatZ = Integer.parseInt(sm.group(3));
        String mount = exec("artest player mount-entity " + dm.group(1));
        scenario().requireArranged("bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);
        scenario().requireArranged("the CLIENT must confirm it is seated before it logs out: "
                + bot().reportRidingEntity(), isRiding(bot().reportRidingEntity()));

        // ---- ACT 1: a REAL logout that leaves the world running (disconnect half only). ---------
        bot().disconnect();
        // The client is away, so it has no world of its own to wait in - but the SERVER is still
        // ticking, and processing a disconnect is something it does on a tick. The budget is the
        // server's ticks, and the fork multiplier that used to size it is gone with the wall clock.
        final String[] offline = {""};
        boolean gone = GameTicks.until(serverClient(), GameTicks.server(), LOGOUT_TICKS, () -> {
            offline[0] = exec("artest player position-of " + BOT);
            // "no such player" = others online, he is not; "no players connected" = the server is
            // empty (this test's single-client case). Both mean he is gone.
            return offline[0].contains("\"error\":\"no such player\"")
                    || offline[0].contains("\"error\":\"no players connected\"");
        });
        scenario().requireArranged("the server must see the pilot GONE after the disconnect (his "
                + "player data, mount included, written to disk): " + offline[0], gone);

        // With no player near them the ship's chunks can drop out from under the probes below —
        // force them back in before acting on the seat.
        exec("artest chunk warmup 0 " + ((BX - 2) >> 4) + " " + ((BZ - 2) >> 4)
                + " " + ((BX + 7) >> 4) + " " + ((BZ + 7) >> 4));

        // Vanilla takes a seated player's mount WITH him into his player data — witnessed here
        // because the occupy below must therefore spawn the seat's fresh (single) dummy, and
        // because it is exactly what the returning login will try to re-spawn back into the world.
        String seatWhileGone = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        scenario().requireArranged("with its pilot offline the seat must have NO bound dummy left "
                + "(vanilla persists the mount inside the player's own data): " + seatWhileGone,
                seatWhileGone.contains("\"dummyFound\":false"));

        // ---- ACT 2: someone takes the seat while he is offline. ---------------------------------
        String occupy = exec("artest vs seat-occupy 0 " + seatX + " " + seatY + " " + seatZ);
        scenario().requireArranged("the seat-occupy probe must seat an NPC occupant: " + occupy,
                occupy.contains("\"ok\":true") && occupy.contains("\"mounted\":true"));
        Matcher nm = OCCUPANT_NAME.matcher(occupy);
        scenario().requireArranged("seat-occupy must report the occupant's name: " + occupy, nm.find());
        final String occupantName = nm.group(1);
        Matcher om = OCCUPANT_ID.matcher(occupy);
        scenario().requireArranged("seat-occupy must report the occupant's id: " + occupy, om.find());
        final String occupantId = om.group(1);
        String occupancy = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        scenario().requireArranged("the occupancy must HOLD before the pilot returns: " + occupancy,
                occupancy.contains("\"id\":" + occupantId));

        // ---- ACT 3: the pilot comes back — a real fresh login over his saved data. --------------
        bot().connect();
        bot().waitForWorld();

        // The refusal message rides the action bar (delayed past the join flood, gone ~4s later),
        // so it is watched FOR while the login settles rather than sampled after. The loop exits
        // early once seen; the end-state assertions below run either way.
        String lastOverlay = "";
        boolean sawMessage = false;
        // THE MULTIPLIER STAYS. This waits for state the SERVER restores on login to arrive at the
        // client and be applied - a round trip whose latency is the machine's, not the game's.
        int settleBudget = (int) (80 * TestTimeouts.factor());
        for (int i = 0; i < settleBudget && !sawMessage; i++) {
            bot().waitTicks(5);
            String ov = bot().reportChat(1).get("overlay").getAsString();
            if (!ov.isEmpty()) {
                lastOverlay = ov;
            }
            sawMessage = ov.contains(occupantName);
        }

        String seatAfter = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        JsonObject riding = bot().reportRidingEntity();
        String observed = "seatStatus=" + seatAfter + " riding=" + riding
                + " overlay=\"" + lastOverlay + "\"";

        // ---- ASSERT 1: the occupant KEEPS the seat. ---------------------------------------------
        assertTrue("the occupant who took the seat while its pilot was offline must still hold it "
                + "after the pilot returns: " + observed, seatAfter.contains("\"id\":" + occupantId));

        // ---- ASSERT 2: one seat — ONE dummy, even across a relog. -------------------------------
        // Vanilla re-spawns the returning pilot's persisted mount; unreconciled, that is a second
        // invisible dummy on the same seat, whose empty twin clears the ship's pilot input every
        // tick. The player-visible shape of that bug is a control tug-of-war nobody can attribute.
        Matcher bc = BOUND_COUNT.matcher(seatAfter);
        assertTrue("seat-status must report boundCount: " + seatAfter, bc.find());
        assertEquals("a seat must keep exactly ONE bound mount dummy across its pilot's relog - "
                + "a re-spawned duplicate fights the occupant for the ship's controls: " + observed,
                1, Integer.parseInt(bc.group(1)));

        // ---- ASSERT 3: the returner is NOT seated — twice, so a late re-mount cannot hide. ------
        assertFalse("a pilot whose seat was taken while he was offline must NOT come back seated: "
                + observed, isRiding(riding));
        bot().waitTicks(20);
        JsonObject ridingLater = bot().reportRidingEntity();
        assertFalse("...and must STAY unseated (no delayed re-mount stealing the seat back): "
                + ridingLater, isRiding(ridingLater));

        // ---- ASSERT 4: he is restored STANDING ABOARD, at his post — not dropped at spawn, -----
        // not fallen off the hull. Client-observed position against the seat's live world
        // position (server oracle, read fresh: the ship may have settled).
        double[] seatWorld = seatWorldPosition(seatX, seatY, seatZ);
        JsonObject state = bot().reportState();
        assertTrue("the client must report a player position: " + state,
                state.get("worldReady").getAsBoolean());
        double cx = state.get("playerX").getAsDouble();
        double cy = state.get("playerY").getAsDouble();
        double cz = state.get("playerZ").getAsDouble();
        String posObserved = "client=(" + cx + "," + cy + "," + cz + ") seatWorld=("
                + seatWorld[0] + "," + seatWorld[1] + "," + seatWorld[2] + ") " + observed;
        assertEquals("the displaced pilot must be restored ABOARD at his post on X: " + posObserved,
                seatWorld[0], cx, ABOARD_EPSILON);
        assertEquals("the displaced pilot must be restored ABOARD at his post on Y: " + posObserved,
                seatWorld[1], cy, ABOARD_EPSILON);
        assertEquals("the displaced pilot must be restored ABOARD at his post on Z: " + posObserved,
                seatWorld[2], cz, ABOARD_EPSILON);

        // ---- ASSERT 5: he is TOLD, by name, who took his seat. ----------------------------------
        assertTrue("the returning pilot must be told WHO took his seat (\"" + occupantName
                + "\") on his action bar - a silently-lost chair reads as a broken relog: "
                + observed, sawMessage);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The seat's live WORLD position, read off the seat's bound dummy: {@code EntityDummy}
     *  glues itself to the seat's world image every tick, so the occupant's dummy IS the seat's
     *  live world-frame oracle (the seat BLOCK's own coordinates are ship-subspace). */
    private double[] seatWorldPosition(int seatX, int seatY, int seatZ) throws Exception {
        String status = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        Matcher dm = DUMMY_ID.matcher(status);
        assertTrue("seat-status must expose the bound dummy for the position oracle: " + status,
                dm.find());
        String pos = exec("artest entity info 0 " + dm.group(1));
        Matcher pm = POS.matcher(pos);
        assertTrue("the entity-info probe must answer for the seat's dummy: " + pos, pm.find());
        return new double[]{Double.parseDouble(pm.group(1)),
                Double.parseDouble(pm.group(2)), Double.parseDouble(pm.group(3))};
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        scenario().requireArranged("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        scenario().requireArranged("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        scenario().requireArranged("fixture (" + VARIANT + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }
}
