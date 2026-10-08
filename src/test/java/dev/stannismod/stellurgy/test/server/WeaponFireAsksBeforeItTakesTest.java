package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertTrue;

/**
 * A promise a server owner has to be able to rely on, which the mechanic did not make on its own.
 *
 * <h3>A weapon asks before it takes a block</h3>
 * <p>Every protection system on this version - claims, regions, an admin's own listener - works by
 * cancelling a block-break event. A weapon that removed blocks directly was invisible to all of
 * them, so a turret was a way around the claim system rather than a weapon in it. What is pinned
 * here is the refusal: a guarded block that is fired on keeps standing, and the same block
 * unguarded does not.</p>
 *
 * <h3>A claimed structure absorbs the fire it refuses</h3>
 * <p>A round rich enough to pay for the guarded block, refused it, does not go on with what it had
 * left: the block it could not have stops it, so what stands behind a claim is not reached
 * through it.</p>
 */
public class WeaponFireAsksBeforeItTakesTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int X = 9800, Y = 82, Z = 9800;
    /**
     * The control's line of fire: two blocks off the subject's, inside the cleared site, and wider
     * apart than the reference round (radius 0.25) sweeps.
     */
    private static final int CONTROL_Z = Z + 2;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * red-witnessed: with the {@code mayRemove} refusal in {@code StructureDamageEngine.spendInto}
     * ({@code StructureDamageEngine#spendInto} at {@code if (!mayRemove(world, pos, state))}) disabled, this fails at "a guarded block was destroyed
     * by weapon fire anyway" (2026-09-29).
     *
     * <p>red-witnessed: with the refused-centre stop in {@code StructureDamageEngine.Walk#visit}
     * ({@code StructureDamageEngine#visit} at {@code if (axisRemovalRefused)}) disabled, this fails at
     * "a claimed block let the round through: the block behind it was reached" on a
     * {@code block_stage_set} at 9804,82,9800 from 0 to 4 and that block gone to air (2026-10-04).</p>
     */
    @Test
    public void aGuardedBlockSurvivesTheHitThatTakesTheUnguardedOneBesideIt() throws Exception {
        prepare();

        // Two identical blocks side by side, one of them spoken for, each on its own line of fire —
        // a control round that crossed the guarded block would be a second shot at the subject.
        // Behind the guarded one, on the subject's line, a third block it shields.
        place(X, Z, "minecraft:stone");
        place(X, CONTROL_Z, "minecraft:stone");
        place(X + 4, Z, "minecraft:stone");
        Reply guarded = ask("stellurgytest damage guard " + DIM + " " + X + " " + Y + " " + Z + " true");
        assertTrue("the veto listener refused to take the position: " + guarded, guarded.ok());

        try {
            // The same energy into each, straight down the middle of the block.
            long fired = events.markInstrumented();
            long subject = shoot(X, Z);
            long control = shoot(X, CONTROL_Z);
            Weapons.awaitShotEnded(events, fired, subject, "the round at the guarded block never ended");
            Weapons.awaitShotEnded(events, fired, control, "the round at the unguarded block never ended");

            Reply controlStage = stage(X, CONTROL_Z);
            assertTrue("the UNGUARDED block survived the shot, so this run says nothing about the"
                    + " guarded one: " + controlStage, gone(controlStage));

            // The round did reach the guarded block and was answered there: the guard keeps the hit
            // and stops it one stage short of gone, which is the stage write this reads.
            List<String> atSubject = Weapons.stagesSetAt(events, fired, DIM, X, Y, Z);
            assertTrue("the round never reached the guarded block, so its standing proves nothing: "
                    + atSubject, !atSubject.isEmpty());
            Reply subjectStage = stage(X, Z);
            assertTrue("a guarded block was destroyed by weapon fire anyway: every claim, region and"
                    + " spawn protection on the server is bypassed by building a turret: " + subjectStage,
                    !gone(subjectStage));

            // The round that paid for the guarded block and was refused it stopped there: nothing was
            // ever written at the block behind it, and that block still stands.
            List<String> behind = Weapons.stagesSetAt(events, fired, DIM, X + 4, Y, Z);
            Reply behindStage = stage(X + 4, Z);
            assertTrue("a claimed block let the round through: the block behind it was reached, so a"
                    + " claim protects its first block and nothing it shields: " + behind + " "
                    + behindStage, behind.isEmpty() && !gone(behindStage));
        } finally {
            ask("stellurgytest damage unguard-all").requireOk("drop the guard");
        }
    }

    // ---- driving

    private void prepare() throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((X + 32) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill " + DIM + " " + (X - 2) + " " + (Y - 2) + " " + (Z - 2) + " " + (X + 30)
                + " " + (Y + 6) + " " + (Z + 2) + " minecraft:air").requireOk("clear the site");
        for (int cx = ((X - 16) >> 4); cx <= ((X + 32) >> 4); cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
    }

    private void place(int x, int z, String block) throws Exception {
        ask("stellurgytest fill " + DIM + " " + x + " " + Y + " " + z + " " + x + " " + Y + " " + z
                + " " + block).requireOk("place " + block);
    }

    /** A round with enough energy to take a stone block out in one arrival, fired from close range. */
    private long shoot(int targetX, int z) throws Exception {
        Reply fired = ask("stellurgytest shot fire " + DIM + " " + (targetX - 6) + " " + Y + " " + z
                + " 4 0 0 2000000 200").requireOk("fire at " + targetX + "," + z);
        long id = fired.longInteger("id");
        assertTrue("the launch was refused: " + fired, id >= 0L);
        return id;
    }

    private Reply stage(int x, int z) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + z).requireOk("read the stage");
    }

    /** Has this position been emptied - destroyed outright, or recorded as destroyed? */
    private static boolean gone(Reply stage) {
        return stage.bool("wasDestroyed") || "minecraft:air".equals(stage.text("block"));
    }
}
