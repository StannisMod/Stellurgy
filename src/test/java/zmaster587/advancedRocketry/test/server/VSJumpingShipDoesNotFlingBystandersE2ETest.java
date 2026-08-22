package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

/**
 * E2E: a craft that JUMPS does not throw the bodies it was carrying a moment earlier.
 *
 * <p>The physics substrate drags entities with the ship under them by transforming them by the
 * ship's per-tick transform DELTA and using the difference as an added velocity. That delta is a
 * velocity only while the ship MOVES. When a ship is relocated instead — a cell crossing, a transit
 * park, a descent — the body it was standing on is left behind, keeps its association with that ship
 * for a short window, and every later rotation of the now-distant hull is offered to it amplified by
 * the whole lever arm between them. The player-visible shape is being thrown thousands of blocks,
 * accelerating.
 *
 * <p><b>The arrangement, and why each half of it is there.</b> A dropped item is the subject: its
 * movement is driven only by the server tick, so nothing but the drag path can move it. It is landed
 * on the ship's deck so the substrate records the touch — a body standing on ORDINARY ground has that
 * association cleared at once, and only a body that was on the SHIP can be dragged by it. The ship is
 * then jumped far away HORIZONTALLY, so the lever arm is horizontal and the verdict can ignore
 * gravity entirely, and spun, because a pure translation of a distant hull is not amplified by
 * distance and could not throw anyone. A column of air below the subject keeps it from landing and
 * clearing its own association before the window it is measured in.
 *
 * <p><b>What makes this able to fail.</b> Four sensitivity controls are asserted before the verdict:
 * the subject really did register the ship, the ship really did leave (a lever arm exists at all),
 * the hull really is rotating, and the subject is still associated at the moment of measurement.
 * Without all four this is a body nothing tried to move, and it would pass against any code.
 */
public class VSJumpingShipDoesNotFlingBystandersE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** A loaded overworld region of this class's own, well clear of every other server e2e. */
    private static final int SRC_X = 9400, SRC_Y = 80, SRC_Z = 9400;

    /** How far the ship jumps. The lever arm IS the amplifier: a small rotation of a hull this far
     *  away moves a point near the old spot by hundreds of blocks per tick. */
    private static final int JUMP_DX = 50_000;

    /** Yaw rate commanded on the departed hull, in rad/s. About 0.05 rad per tick — with the lever
     *  arm above, thousands of blocks of transform delta per tick offered to the subject. */
    private static final String YAW_RAD_PER_S = "1.0";

    /** How long the substrate keeps a body associated with the last ship it touched
     *  ({@code VSConfig.ticksToStickToShip}). The hazard has to be introduced inside this, which is
     *  what makes probe round-trips expensive here: each one costs the server a couple of ticks. */
    private static final int STICK_TICKS = 20;

    /** The measuring window, in server ticks: long enough for the commanded rotation to be applied
     *  several times, and it need not fit inside the association window — a body that has been
     *  thrown stays thrown, so the displacement is still there to read afterwards. */
    private static final int WINDOW_TICKS = 20;

    /** What the subject may wander horizontally in that window: it is falling straight down in still
     *  air with no input, so anything past a block is something moving it. The defect moved it by
     *  ~58 000. */
    private static final double ALLOWED_HORIZONTAL_DRIFT = 1.0;

    private static final int LOAD_TICKS = 200;
    private static final int TOUCH_TICKS = 100;

    @Test
    public void aBodyLeftBehindByAJumpingShipIsNotThrownByIt() throws Exception {

        // No player stands anywhere near this craft, and an unattended ship unloads: without this the
        // ship is REGISTERED and not LOADED, which has no transform to read and no physics to tick.
        exec("artest vs permaload true");

        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-deck");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the ship never loaded", waitForLoadedShip(0) >= 1);

        // The ship is loaded before its world transform has propagated, so "is a ship here yet?" is a
        // POLL, not a question with an answer the moment the count goes up.
        final String[] look = {""};
        boolean managed = GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            look[0] = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z
                    + " " + SHIP_CAPTURE_RADIUS_BLOCKS);
            return look[0].contains("\"managed\":true");
        }, () -> exec("artest vs load-ships 0"));
        assertTrue("ship not managed by VS at its own build site: " + look[0] + " countAll="
                + exec("artest vs ship-count-all 0") + " loaded=" + exec("artest vs ship-count 0"),
                managed);
        String info = look[0];
        String shipId = extractString(info, "id");
        double sx = extractDouble(info, "posX"), sy = extractDouble(info, "posY"),
                sz = extractDouble(info, "posZ");

        // The subject: a plain item, dropped over the hull so it falls onto the deck. Only the server
        // tick moves it, so any displacement below has exactly one possible author.
        String dropped = exec("artest vs drop-item 0 " + sx + " " + (sy + 6) + " " + sz);
        int subjectId = extractInt(dropped, "entityId");
        assertTrue("the subject item was not spawned: " + dropped, subjectId != Integer.MIN_VALUE);

        // CONTROL 1 — the subject must actually register the ship. A body that never touched it is
        // never dragged by it, and everything below would be a measurement of nothing.
        final String[] touch = {""};
        boolean armed = GameTicks.until(client(), GameTicks.server(), TOUCH_TICKS, () -> {
            touch[0] = exec("artest vs player-ship-data 0 " + subjectId);
            return extractString(touch[0], "lastTouchedShip") != null;
        });
        assertTrue("precondition: the subject never came to rest on the ship, so nothing could fling"
                + " it; last reading=" + touch[0], armed);

        double beforeX = extractDouble(touch[0], "playerX");
        double beforeZ = extractDouble(touch[0], "playerZ");

        // Keep the ground out of the way: a subject that lands clears its own ship association and
        // would leave the window before the measurement is taken.
        // Sized against the probe's own 32 768-block fill cap, and centred on the SUBJECT rather than
        // on the build site: what has to stay empty is the shaft the subject falls down, not the pad.
        String column = exec("artest fill 0 " + ((int) beforeX - 4) + " " + (SRC_Y - 40) + " "
                + ((int) beforeZ - 4) + " " + ((int) beforeX + 4) + " " + (SRC_Y + 12) + " "
                + ((int) beforeZ + 4) + " minecraft:air");
        assertTrue("the subject's fall shaft was not cleared, so it would land and release the ship"
                + " before being measured: " + column, column.contains("\"ok\":true"));

        // The jump: the production rigid relocation, aimed sideways so the lever arm is horizontal.
        String tp = exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + ((int) sx + JUMP_DX) + " " + (int) sy + " " + (int) sz);
        assertTrue("the jump failed: " + tp, tp.contains("\"ok\":true"));
        // CONTROL 4, taken BEFORE the hazard rather than after it: the subject must still be
        // associated with the ship at the moment the rotation starts. Every probe call costs the
        // server several ticks, and the association only lives 20 of them — a run that spent them on
        // its own setup would introduce the hazard to a body the substrate had already let go, and
        // report a green that means "too slow", not "correct".
        String atHazard = exec("artest vs player-ship-data 0 " + subjectId);
        int sinceAtHazard = extractInt(atHazard, "ticksSinceTouchedShip");
        assertTrue("sensitivity control — the subject was released by the ship before the rotation"
                + " even began, so the defect had no path to it: sinceTouched=" + sinceAtHazard
                + " reading=" + atHazard, sinceAtHazard >= 0 && sinceAtHazard < STICK_TICKS);

        // Rotate the departed hull through the ship's OWN controller, which also steps its physics.
        // A directly-written angular velocity is not usable here: the flight computer drives omega
        // back to zero on every unmanned tick, so a one-shot spin is gone before it can be measured
        // (observed: omega read 0.000 on all ten ticks of a re-applied 0.5 rad/s spin). A commanded
        // rotation is held by the same controller instead of fought by it.
        String rot = exec("artest vs force-rot-by-id 0 " + shipId + " 0 " + YAW_RAD_PER_S + " 0");
        assertTrue("the departed hull could not be commanded to rotate: " + rot,
                rot.contains("\"afcResolved\":true"));

        GameTicks.advance(client(), GameTicks.server(), WINDOW_TICKS);

        // One reading at the end is enough for the verdict: a fling is a DISPLACEMENT, and a body
        // that has been thrown does not come back.
        String after = exec("artest vs player-ship-data 0 " + subjectId);
        String lastShip = exec("artest vs ship-info 0 id " + shipId);
        double drift = Math.hypot(extractDouble(after, "playerX") - beforeX,
                extractDouble(after, "playerZ") - beforeZ);
        double omega = extractDouble(lastShip, "omega");
        double leverArm = Math.abs(extractDouble(lastShip, "posX") - beforeX);
        String evidence = " sinceAtHazard=" + sinceAtHazard + " leverArm=" + leverArm + " omega="
                + omega + " before=(" + beforeX + "," + beforeZ + ") after=" + after
                + " ship=" + lastShip;

        // CONTROL 2 — a lever arm exists: the hull really is far from where the subject stands.
        assertTrue("sensitivity control — the ship did not leave, so no distance could amplify"
                + " anything:" + evidence, leverArm > JUMP_DX / 2.0);

        // CONTROL 3 — the hull really is rotating. A hull that only translates offers a delta that
        // distance does not amplify, and this test would pass on a ship standing perfectly still.
        assertTrue("sensitivity control — the departed hull is not rotating, so nothing was offered"
                + " to the subject at all:" + evidence, omega > 1e-3);

        // THE VERDICT: a ship's jump is not a velocity, so a body it left behind stays where it was.
        assertTrue("a ship that jumped away must not throw the body it was carrying: it drifted "
                + drift + " blocks horizontally in " + WINDOW_TICKS + " ticks (allowed "
                + ALLOWED_HORIZONTAL_DRIFT + ");" + evidence,
                drift <= ALLOWED_HORIZONTAL_DRIFT);
    }

    @After
    public void cleanup() throws Exception {
        exec("artest vs permaload false");
    }

    // --- helpers (mirror VSShipDescentE2ETest) ------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private int waitForLoadedShip(int dim) throws Exception {
        final int[] loaded = {0};
        GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") < 1) {
                return false;
            }
            exec("artest vs load-ships " + dim);
            loaded[0] = extractInt(exec("artest vs ship-count " + dim), "count");
            return loaded[0] >= 1;
        });
        return loaded[0];
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 8) >> 4, cz1 = (baseZ - 8) >> 4;
        int cx2 = (baseX + 24) >> 4, cz2 = (baseZ + 24) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
