package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Two promises a server owner has to be able to rely on, neither of which the mechanic made on its
 * own.
 *
 * <h3>A weapon asks before it takes a block</h3>
 * <p>Every protection system on this version - claims, regions, an admin's own listener - works by
 * cancelling a block-break event. A weapon that removed blocks directly was invisible to all of
 * them, so a turret was a way around the claim system rather than a weapon in it. What is pinned
 * here is the refusal: a guarded block that is fired on keeps standing, and the same block
 * unguarded does not.</p>
 *
 * <h3>An off switch ends what is in the air</h3>
 * <p>The shot registry is world-saved data. A switch that stopped stepping rounds without ending
 * them left them in the save, to resume whenever it was switched back on - a pause wearing the name
 * of an off switch.</p>
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

    /**
     * red-witnessed: with {@code endWhatWasStillInTheAir} no longer called from
     * {@code ShotSubstrate.tick}'s switched-off branch ({@code ShotSubstrate#tick} at {@code endWhatWasStillInTheAir(world);}), this fails at
     * "a round left in the air when the substrate was switched off is still in the registry"
     * (2026-09-29).
     *
     * <p>red-witnessed: with {@code ShotSubstrate#endWhatWasStillInTheAir} at {@code registry.end(shot.getId(), ShotEndReason.SUBSTRATE_DISABLED, endedAt);} ending those rounds as EXPIRED, this fails
     * at "the round ended, but for the wrong reason ... {...reason:EXPIRED} expected:&lt;[SUBSTRATE_DISABL]ED&gt;"
     * (2026-09-30).</p>
     */
    @Test
    public void switchingTheSubstrateOffEndsTheRoundsAlreadyInTheAir() throws Exception {
        prepare();
        try {
            // Straight up, with a long life: it will still be flying when the switch is thrown.
            long fired = events.markInstrumented();
            Reply launched = ask("stellurgytest shot fire " + DIM + " " + (X + 20) + " " + Y + " " + Z
                    + " 0 4 0 2000 400").requireOk("fire a round");
            long id = launched.longInteger("id");
            assertTrue("the launch was refused, so there is nothing in the air to end: " + launched,
                    id >= 0L);
            Reply inAir = ask("stellurgytest shot read " + DIM + " " + id).requireOk("read the round");
            assertTrue("the round was not in the air right after it was fired: " + inAir,
                    inAir.bool("present"));

            ask("stellurgytest config set enableWeapons false").requireOk("switch the substrate off");
            String ended = Weapons.awaitShotEnded(events, fired, id, "a round left in the air when the"
                    + " substrate was switched off is still in the registry: the switch suspends the"
                    + " mechanic instead of ending it, and the round is written back into the save on"
                    + " every tick that follows");
            assertEquals("the round ended, but for the wrong reason - it should say the substrate was"
                    + " switched off under it: " + ended, "SUBSTRATE_DISABLED", Events.text(ended, "reason"));
            Reply after = ask("stellurgytest shot read " + DIM + " " + id).requireOk("read the round");
            assertTrue("the registry still holds a round it announced as ended: " + after,
                    !after.bool("present"));
        } finally {
            ask("stellurgytest config set enableWeapons true").requireOk("switch the substrate back on");
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
