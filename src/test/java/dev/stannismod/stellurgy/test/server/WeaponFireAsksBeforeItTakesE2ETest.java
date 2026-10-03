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
 */
public class WeaponFireAsksBeforeItTakesE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int X = 9800, Y = 82, Z = 9800;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /**
     * red-witnessed: with the {@code mayRemove} refusal in {@code StructureDamageEngine.spendInto}
     * ({@code StructureDamageEngine#spendInto} at {@code if (!mayRemove(world, pos, state))}) disabled, this fails at "a guarded block was destroyed
     * by weapon fire anyway" (2026-09-29).
     */
    @Test
    public void aGuardedBlockSurvivesTheHitThatTakesTheUnguardedOneBesideIt() throws Exception {
        prepare();

        // Two identical blocks, one of them spoken for.
        place(X, "minecraft:stone");
        place(X + 4, "minecraft:stone");
        Reply guarded = ask("stellurgytest damage guard " + DIM + " " + X + " " + Y + " " + Z + " true");
        assertTrue("the veto listener refused to take the position: " + guarded, guarded.ok());

        try {
            // The same energy into each, straight down the middle of the block.
            long fired = events.markInstrumented();
            long subject = shoot(X);
            long control = shoot(X + 4);
            Weapons.awaitShotEnded(events, fired, subject, "the round at the guarded block never ended");
            Weapons.awaitShotEnded(events, fired, control, "the round at the unguarded block never ended");

            Reply controlStage = stage(X + 4);
            assertTrue("the UNGUARDED block survived the shot, so this run says nothing about the"
                    + " guarded one: " + controlStage, gone(controlStage));

            // The round did reach the guarded block and was answered there: the guard keeps the hit
            // and stops it one stage short of gone, which is the stage write this reads.
            List<String> atSubject = Weapons.stagesSetAt(events, fired, DIM, X, Y, Z);
            assertTrue("the round never reached the guarded block, so its standing proves nothing: "
                    + atSubject, !atSubject.isEmpty());
            Reply subjectStage = stage(X);
            assertTrue("a guarded block was destroyed by weapon fire anyway: every claim, region and"
                    + " spawn protection on the server is bypassed by building a turret: " + subjectStage,
                    !gone(subjectStage));
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

    private void place(int x, String block) throws Exception {
        ask("stellurgytest fill " + DIM + " " + x + " " + Y + " " + Z + " " + x + " " + Y + " " + Z
                + " " + block).requireOk("place " + block);
    }

    /** A round with enough energy to take a stone block out in one arrival, fired from close range. */
    private long shoot(int targetX) throws Exception {
        Reply fired = ask("stellurgytest shot fire " + DIM + " " + (targetX - 6) + " " + Y + " " + Z
                + " 4 0 0 2000000 200").requireOk("fire at " + targetX);
        long id = fired.longInteger("id");
        assertTrue("the launch was refused: " + fired, id >= 0L);
        return id;
    }

    private Reply stage(int x) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + Z).requireOk("read the stage");
    }

    /** Has this position been emptied - destroyed outright, or recorded as destroyed? */
    private static boolean gone(Reply stage) {
        return stage.bool("wasDestroyed") || "minecraft:air".equals(stage.text("block"));
    }

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
