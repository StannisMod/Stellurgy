package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

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
 * <p><b>The arrangement, and why each half of it is there.</b> An empty minecart is the subject: its
 * movement is driven only by the server tick, so nothing but the drag path can move it. It is a
 * minecart and not a dropped item because the hazard needs a body the SUBSTRATE holds: since
 * 2026-09-29 a deck takes an item into its own frame and carries it with a jump, so the substrate
 * never associates an item with the ship and there is no body left behind to fling. It is landed
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
public class VSJumpingShipDoesNotFlingBystandersTest extends AbstractSharedServerTest {

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
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), WINDOW_TICKS);",
            why = "the subject's position was read aboard (`before`) and is read again after this stretch of the"
                    + " hull rotating; the verdict is the horizontal distance between the two, and its message"
                    + " names both. The bar is an UPPER bound, so overshoot gives the hull longer to throw the"
                    + " body and can only turn a green red.")
    public void aBodyLeftBehindByAJumpingShipIsNotThrownByIt() throws Exception {

        // No player stands anywhere near this craft, and an unattended ship unloads: without this the
        // ship is REGISTERED and not LOADED, which has no transform to read and no physics to tick.

        int[] bp = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-deck");
        long buildMark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, bp, asm, USABLE_TICKS,
                "the craft that jumps away from the body");
        builtShip = birth.physicsId;
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());

        // THIS ship, by the name the assembler minted for it — then the physics id it maps to, once
        // its own announcement says it is usable.
        String shipId = ArrangementFailure.arranged(
                () -> birth.awaitUsable(c -> read(c).raw(), events, USABLE_TICKS));

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
        String info = read("stellurgytest vs ship-info 0 id " + shipId).raw();
        assertTrue("VS could not resolve a state for this ship at its own build site: " + info,
                ShipInfo.isLoaded(info));
        ShipInfo atBuildSite = ShipInfo.of(info);
        double sx = atBuildSite.x, sy = atBuildSite.y, sz = atBuildSite.z;

        // The subject: an empty minecart, dropped over the hull so it falls onto the deck (see the
        // class comment for why not an item). Only the server tick moves it.
        // Marked before the drop: the association is formed on one of the ticks the body spends
        // falling, and a mark taken after it would be waiting for the body to touch a second ship.
        long touchMark = events.mark();
        String dropped = arrange("stellurgytest entity spawn 0 " + sx + " " + (sy + 6) + " " + sz
                + " minecraft:minecart").raw();
        int subjectId = extractInt(dropped, "entityId");
        assertTrue("the subject minecart was not spawned: " + dropped,
                subjectId != Integer.MIN_VALUE && Reply.of(dropped).bool("spawned"));

        // CONTROL 1 — the subject must actually register the ship. A body that never touched it is
        // never dragged by it, and everything below would be a measurement of nothing.
        //
        // Linked on the tick that FORMS the association: `entity_touched_ship` is written from
        // `Entity.move`'s return, where the substrate assigns `lastTouchedShip`, and is narrowed to
        // this body. The poll it replaces cost a probe call per read out of a twenty-tick
        // association window — the same window the experiment below needs — so a run that spent
        // several of them looking could reach its hazard after the substrate had let the body go.
        events.awaitField(touchMark, "entity_touched_ship", "entity", subjectId,
                "the subject never came to rest on the ship, so nothing could fling it", TOUCH_TICKS);

        // The reference position is still READ, not taken from the record: it is where the body
        // stands once it is aboard, which is what the drift below is measured against, and it is
        // the same reading the assertion at the hazard makes.
        String touch = read("stellurgytest vs player-ship-data 0 " + subjectId).raw();
        double beforeX = extractDouble(touch, "playerX");
        double beforeZ = extractDouble(touch, "playerZ");

        // Keep the ground out of the way: a subject that lands clears its own ship association and
        // would leave the window before the measurement is taken.
        // Sized against the probe's own 32 768-block fill cap, and centred on the SUBJECT rather than
        // on the build site: what has to stay empty is the shaft the subject falls down, not the pad.
        String column = arrange("stellurgytest fill 0 " + ((int) beforeX - 4) + " " + (SRC_Y - 40) + " "
                + ((int) beforeZ - 4) + " " + ((int) beforeX + 4) + " " + (SRC_Y + 12) + " "
                + ((int) beforeZ + 4) + " minecraft:air").raw();
        assertTrue("the subject's fall shaft was not cleared, so it would land and release the ship"
                + " before being measured: " + column, Reply.of(column).ok());

        // The jump: the production rigid relocation, aimed sideways so the lever arm is horizontal.
        String tp = arrange("stellurgytest vs teleport-ship-by-id 0 " + shipId + " "
                + ((int) sx + JUMP_DX) + " " + (int) sy + " " + (int) sz).raw();
        assertTrue("the jump failed: " + tp, Reply.of(tp).ok());
        // CONTROL 4, taken BEFORE the hazard rather than after it: the subject must still be
        // associated with the ship at the moment the rotation starts. Every probe call costs the
        // server several ticks, and the association only lives 20 of them — a run that spent them on
        // its own setup would introduce the hazard to a body the substrate had already let go, and
        // report a green that means "too slow", not "correct".
        String atHazard = read("stellurgytest vs player-ship-data 0 " + subjectId).raw();
        int sinceAtHazard = extractInt(atHazard, "ticksSinceTouchedShip");
        assertTrue("sensitivity control — the subject was released by the ship before the rotation"
                + " even began, so the defect had no path to it: sinceTouched=" + sinceAtHazard
                + " reading=" + atHazard, sinceAtHazard >= 0 && sinceAtHazard < STICK_TICKS);

        // Rotate the departed hull through the ship's OWN controller, which also steps its physics.
        // A directly-written angular velocity is not usable here: the flight computer drives omega
        // back to zero on every unmanned tick, so a one-shot spin is gone before it can be measured
        // (observed: omega read 0.000 on all ten ticks of a re-applied 0.5 rad/s spin). A commanded
        // rotation is held by the same controller instead of fought by it.
        String rot = arrange("stellurgytest vs force-rot-by-id 0 " + shipId + " 0 " + YAW_RAD_PER_S + " 0").raw();
        assertTrue("the departed hull could not be commanded to rotate: " + rot,
                Reply.of(rot).bool("afcResolved"));

        GameTicks.advance(client(), GameTicks.server(), WINDOW_TICKS);

        // One reading at the end is enough for the verdict: a fling is a DISPLACEMENT, and a body
        // that has been thrown does not come back.
        String after = read("stellurgytest vs player-ship-data 0 " + subjectId).raw();
        ShipInfo lastShip = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
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

    /** The physics id of the craft this scenario built; the jump is a rigid move that keeps it. */
    private String builtShip;

    @After
    public void disposeOfTheCraft() throws Exception {
        if (builtShip != null) {
            Disposal.ship(c -> arrange(c).raw(), events, 0, builtShip);
            builtShip = null;
        }
    }

    /** Server ticks for the craft to be named and become usable: one or two ticks away. */
    private static final int USABLE_TICKS = 200;

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());


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
    private int[] placeFixture(FixtureSite site, String variant) throws Exception {
        return RocketFixture.placeAt(site, c -> arrange(c).raw(), variant, 4, 12,
                "the craft this scenario builds stands in this volume");
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

    // The `extractString` reader that stood here is gone with the poll that needed it: nothing in
    // this class asks a probe reply whether `lastTouchedShip` has become non-null any more, because
    // the substrate's own association is a record now.
}
