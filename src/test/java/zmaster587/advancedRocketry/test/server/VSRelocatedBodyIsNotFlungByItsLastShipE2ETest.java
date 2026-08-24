package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

/**
 * E2E: a body that is CARRIED AWAY from a craft is not thrown by the hull it left behind.
 *
 * <p>This is the mirror of {@link VSJumpingShipDoesNotFlingBystandersE2ETest} and it is a different
 * defect, not a second arrangement of the same one. There the SHIP is relocated and the body stays;
 * here the BODY is relocated and the ship stays. The substrate guards the first case — a hull that
 * has just been jumped is not allowed to drag anything until its teleport timer expires — and the
 * two halves of the pair are asymmetric: nothing consults how far away the BODY has been moved.
 *
 * <p><b>Why the distance is the whole mechanism.</b> The drag carries an entity by transforming its
 * world position by the ship's between-tick transform and using the difference as an added velocity.
 * That transform is a RIGID motion about the ship, so its displacement at a point grows with that
 * point's distance from the ship: a hull turning a degree per tick writes a block per tick at 60
 * blocks and eighty blocks per tick at 4 500. The association that authorises this is a TIMER — the
 * last ship touched, for {@code VSConfig.ticksToStickToShip} ticks — and a timer cannot tell that the
 * body it is holding has since been moved to the other side of the world.
 *
 * <p><b>Player-visible shape</b>: step off a craft and be teleported (a dimension change, a cell
 * crossing, a command, a rocket), and the hull you were standing on a moment ago keeps writing your
 * position from wherever it is, at a rate set by how far away you now are. The observed end of it is
 * a body descending past y=0 at tens of blocks per tick with its reported velocity flat — being
 * placed, not falling.
 *
 * <p><b>The arrangement.</b> A dropped item is the subject: only the server tick moves it, so any
 * displacement below has exactly one possible author. It is landed on the deck so the substrate
 * records the touch. It is then relocated far away HORIZONTALLY — so the lever arm is horizontal and
 * the verdict can ignore gravity entirely — through a position write rather than a move, which is
 * what a teleport is and is why the association survives it. The hull is left where it was and spun,
 * because a hull that only translates offers a delta that distance does not amplify.
 *
 * <p><b>What makes this able to fail.</b> Four sensitivity controls are asserted before the verdict:
 * the subject really did register the ship, the relocation really did take (a lever arm exists at
 * all), the hull really is rotating, and the subject is still associated at the moment of
 * measurement. Without all four this is a body nothing tried to move, and it would pass against any
 * code.
 */
public class VSRelocatedBodyIsNotFlungByItsLastShipE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** A loaded overworld region of this class's own, well clear of every other server e2e. */
    private static final int SRC_X = 10600, SRC_Y = 80, SRC_Z = 10600;

    /** How far the SUBJECT is carried. The lever arm IS the amplifier, and this is the distance the
     *  field report was taken at: a craft built on one plot, a body teleported to another ~4 500
     *  blocks away. */
    private static final int CARRY_DX = 4_500;

    /** Where the subject is put down: high enough that it cannot reach terrain by falling inside the
     *  measured window, so it never lands and clears its own association before the verdict. */
    private static final int DST_Y = 200;

    /** Yaw rate commanded on the hull left behind, in rad/s — about 0.05 rad per tick. With the lever
     *  arm above, hundreds of blocks of transform delta per tick offered to the subject. */
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
     *  air with no input, so anything past a block is something moving it. */
    private static final double ALLOWED_HORIZONTAL_DRIFT = 1.0;

    private static final int LOAD_TICKS = 200;
    private static final int TOUCH_TICKS = 100;

    @Test
    public void aBodyCarriedAwayFromAShipIsNotThrownByIt() throws Exception {

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

        // The destination is prepared BEFORE the subject exists, because everything after the touch
        // is spent against the 20-tick association window. Warming it keeps the relocated subject a
        // genuinely ticked entity rather than one frozen in an unloaded chunk, so what is measured
        // below is the production tick and not an artefact of the arrangement.
        int dstX = SRC_X + CARRY_DX, dstZ = SRC_Z;
        assertTrue("the destination chunks never loaded, so the relocated subject would not be"
                        + " ticked there",
                exec("artest chunk warmup 0 " + ((dstX - 16) >> 4) + " " + ((dstZ - 16) >> 4) + " "
                        + ((dstX + 16) >> 4) + " " + ((dstZ + 16) >> 4)).contains("\"ok\":true"));

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

        // The carry: the subject is put down far away through a position write, which is what a
        // teleport is. It does not route through move(), so the substrate never re-evaluates which
        // ship the body is standing on and the association crosses the gap intact — exactly as it
        // does for a player the game teleports.
        String moved = exec("artest entity set-pos 0 " + subjectId + " " + dstX + " " + DST_Y
                + " " + dstZ);
        assertTrue("the subject could not be relocated: " + moved, moved.contains("\"ok\":true"));

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
        double beforeX = extractDouble(atHazard, "playerX");
        double beforeZ = extractDouble(atHazard, "playerZ");

        // Rotate the hull that stayed behind, through the ship's OWN controller, which also steps its
        // physics. A directly-written angular velocity is not usable here: the flight computer drives
        // omega back to zero on every unmanned tick, so a one-shot spin is gone before it can be
        // measured. A commanded rotation is held by the same controller instead of fought by it.
        String rot = exec("artest vs force-rot-by-id 0 " + shipId + " 0 " + YAW_RAD_PER_S + " 0");
        assertTrue("the hull left behind could not be commanded to rotate: " + rot,
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

        // CONTROL 2 — a lever arm exists: the subject really was carried clear of the hull.
        assertTrue("sensitivity control — the subject did not move away, so no distance could amplify"
                + " anything:" + evidence, leverArm > CARRY_DX / 2.0);

        // CONTROL 3 — the hull really is rotating. A hull that only translates offers a delta that
        // distance does not amplify, and this test would pass on a ship standing perfectly still.
        assertTrue("sensitivity control — the hull left behind is not rotating, so nothing was"
                + " offered to the subject at all:" + evidence, omega > 1e-3);

        // THE VERDICT: a body that is no longer anywhere near a hull is not carried by it, however
        // recently it stood on it.
        assertTrue("a ship must not throw a body that has been carried away from it: it drifted "
                + drift + " blocks horizontally in " + WINDOW_TICKS + " ticks (allowed "
                + ALLOWED_HORIZONTAL_DRIFT + ");" + evidence,
                drift <= ALLOWED_HORIZONTAL_DRIFT);
    }

    @After
    public void cleanup() throws Exception {
        exec("artest vs permaload false");
    }

    // --- helpers (mirror VSJumpingShipDoesNotFlingBystandersE2ETest) -------------------------------

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
