package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.After;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

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

    /** The angular rate that says the departed hull IS rotating, in rad/s — a sensitivity control,
     *  and float noise is the only thing under it. */
    private static final double HULL_IS_ROTATING = 1e-3;


    /** A loaded overworld region of this class's own, well clear of every other server e2e. */
    private static final int SRC_X = 9400, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 9400;

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

    private static final int TOUCH_TICKS = 100;

    @Test
    public void aBodyLeftBehindByAJumpingShipIsNotThrownByIt() throws Exception {

        // No player stands anywhere near this craft, and an unattended ship unloads: without this the
        // ship is REGISTERED and not LOADED, which has no transform to read and no physics to tick.

        String coords = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-deck");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));
        assertTrue("the ship never loaded", loadedShips(0) >= 1);

        // THIS ship, by the name the assembler minted for it — then the physics id it maps to. The
        // build site is in a world this class shares with its siblings, so a lookup there answers
        // with a neighbour's craft in exactly the same shape as with this one.
        String durableId = ShipIdentity.nameFromAssembly(asm);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableId);

        // The ship is loaded before its world transform has propagated, so "is a ship here yet?" is a
        // POLL, not a question with an answer the moment the count goes up.
        // READ, not a poll. What `managed:true` reports is that the substrate resolved a STATE for
        // this id — the probe answers `{"managed":false}` when it cannot — and that is true by the
        // time this line runs: the spawn is drained on the world's tick, and every probe command is
        // drained on the server thread behind the task queue's own monitor, so two consecutive
        // commands are separated by a complete pass.
        //
        // The pump this replaces (`vs load-ships 0` on every unsatisfied check) never ran, because
        // the check was never unsatisfied. And the comment above it claimed the poll was waiting for
        // "the world transform to propagate"; the condition never tested that — it tested whether
        // the ship could be resolved at all, which is what the message now says.
        String info = exec("artest vs ship-info 0 id " + shipId);
        assertTrue("VS could not resolve a state for this ship at its own build site: " + info
                + " countAll=" + exec("artest vs ship-count-all 0")
                + " loaded=" + exec("artest vs ship-count 0"),
                ShipInfo.isLoaded(info));
        ShipInfo atBuildSite = ShipInfo.of(info);
        double sx = atBuildSite.x, sy = atBuildSite.y, sz = atBuildSite.z;

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
                + " before being measured: " + column, Reply.of(column).ok());

        // The jump: the production rigid relocation, aimed sideways so the lever arm is horizontal.
        String tp = exec("artest vs teleport-ship-by-id 0 " + shipId + " "
                + ((int) sx + JUMP_DX) + " " + (int) sy + " " + (int) sz);
        assertTrue("the jump failed: " + tp, Reply.of(tp).ok());
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
                Reply.of(rot).bool("afcResolved"));

        GameTicks.advance(client(), GameTicks.server(), WINDOW_TICKS);

        // One reading at the end is enough for the verdict: a fling is a DISPLACEMENT, and a body
        // that has been thrown does not come back.
        String after = exec("artest vs player-ship-data 0 " + subjectId);
        ShipInfo lastShip = ShipInfo.byId(this::exec, 0, shipId);
        double drift = Math.hypot(extractDouble(after, "playerX") - beforeX,
                extractDouble(after, "playerZ") - beforeZ);
        double omega = lastShip.omega;
        double leverArm = Math.abs(lastShip.x - beforeX);
        String evidence = " sinceAtHazard=" + sinceAtHazard + " leverArm=" + leverArm + " omega="
                + omega + " before=(" + beforeX + "," + beforeZ + ") after=" + after
                + " ship=" + lastShip.raw();

        // CONTROL 2 — a lever arm exists: the hull really is far from where the subject stands.
        assertTrue("sensitivity control — the ship did not leave, so no distance could amplify"
                + " anything:" + evidence, leverArm > JUMP_DX / 2.0);

        // CONTROL 3 — the hull really is rotating. A hull that only translates offers a delta that
        // distance does not amplify, and this test would pass on a ship standing perfectly still.
        assertTrue("sensitivity control — the departed hull is not rotating, so nothing was offered"
                + " to the subject at all:" + evidence, omega > HULL_IS_ROTATING);

        // THE VERDICT: a ship's jump is not a velocity, so a body it left behind stays where it was.
        assertTrue("a ship that jumped away must not throw the body it was carrying: it drifted "
                + drift + " blocks horizontally in " + WINDOW_TICKS + " ticks (allowed "
                + ALLOWED_HORIZONTAL_DRIFT + ");" + evidence,
                drift <= ALLOWED_HORIZONTAL_DRIFT);
    }

    @After
    public void cleanup() throws Exception {
    }

    // --- helpers (mirror VSShipDescentE2ETest) ------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }


    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, 0.0);
    }

    private static String extractString(String json, String key) {
        // absence is the answer: the callers WAIT on this, and the fields they wait for —
        // `lastTouchedShip` above all — are written as JSON null until the thing happens.
        return Reply.of(json).textOr(key, null);
    }
}
