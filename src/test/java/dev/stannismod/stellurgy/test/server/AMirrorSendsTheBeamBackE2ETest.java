package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertTrue;

/**
 * A mirror does not swallow a beam — it sends it somewhere, and somewhere is a place with blocks in it.
 *
 * <p>Mirror plating computes an outgoing direction for the beam it reflects and hands it back as a
 * deflection. For a long time nothing on the beam path asked whether the answer WAS a deflection: the
 * beam ended at the plating and the reflected energy was reported to a caller that did not read it. The
 * hull behind the mirror was protected, so from the defender's chair the armour looked right, and
 * three green tests over the thrown-round path — where deflection has always worked — said nothing
 * about it. What was missing had no observer at all.</p>
 *
 * <p>This gives it one. A beam meeting a plate square-on is reflected back down its own line, and the
 * only thing standing on that line is the gun that fired it. That is a real consequence and not a test
 * fixture: shooting a mirror head-on is a way to shoot yourself, and a player is entitled to find that
 * out. The assertion is deliberately about WHERE the energy went rather than how much of it went
 * there — the reflectances are balance and will move.</p>
 */
public class AMirrorSendsTheBeamBackE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84, Z = 9560;
    private static final int GUN_X = 9700;
    /** Far enough that the reflected line has a clear run home, short enough to stay in loaded chunks. */
    private static final int MIRROR_X = GUN_X + 12;
    /** Controller + three emitters + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 5;
    /** How often the gun is fed while the wait runs, in ticks — arrangement, not subject. */
    private static final int FEED_EVERY_TICKS = 20;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * Hold a beam on a mirror and the gun is what the beam comes back to.
     *
     * <p>Two halves, and both are the point. The gun's own controller must take damage — the link
     * this waits on, the stage write at the gun's own position — or the reflected energy went nowhere
     * and the mirror is an absorber with extra steps. And the iron behind the mirror must be untouched
     * over the same window, or the plating is not reflecting but merely being slow to break. Either
     * half alone passes for the wrong reason.</p>
     *
     * <p>red-witnessed: with {@code HeldBeam.emit}'s deflection branch ({@code HeldBeam#emit} at {@code if (answer.isDeflected())})
     * made to end the beam at the plating — the answer thrown away, as it once was — this fails at
     * "the gun that fired into a mirror took nothing at all" (2026-09-29).</p>
     *
     * <p>red-witnessed: with {@code BlockMirrorPlating#onContact} at {@code return ContactResult.deflected(away, (int) Math.round(contact.getEnergy() * reflectance));} answering "passed through, carrying
     * everything" on nineteen ticks in twenty and deflecting on the twentieth, this fails at "the iron
     * BEHIND the mirror was damaged before the reflection reached the gun ... [{...pos:9713,84,9560,
     * from:0,to:1...}...]" (2026-09-30). One tick in four was not enough: the gun's controller was
     * staged by the reflected quarter before the iron by the rest, and the run stayed green — so this
     * verdict catches a mirror that leaks most of a beam, not one that leaks some of it.</p>
     */
    @Test
    public void aBeamHeldOnAMirrorComesBackToTheGunThatFiredIt() throws Exception {
        buildSite();
        long built = events.markInstrumented();
        buildBeamGun();
        Weapons.awaitAssembled(events, built, GUN_X, Y, Z, PARTS, "the beam gun never assembled");

        // One plate square across the line of fire, with plain iron directly behind it. The iron is
        // the control: whatever happens to the gun, this must not be dug.
        place("stellurgy:mirrorPlatingGold", MIRROR_X, Y, Z);
        place("minecraft:iron_block", MIRROR_X + 1, Y, Z);

        long held = events.mark();
        aimAt(MIRROR_X);
        Weapons.awaitBeam(events, held, GUN_X, Y, Z, "the beam gun never lit on the mirror",
                "lit", "true");
        String hitHome = events.awaitMatching(held, "block_stage_set",
                reply -> !Events.recordsWhereAll(reply, "dim", String.valueOf(DIM),
                        "pos", Weapons.at(GUN_X, Y, Z)).isEmpty(),
                "at the gun's own controller", "the gun that fired into a mirror took nothing at all."
                        + " The reflected energy went nowhere: the plating answered with a deflection"
                        + " and the beam path threw the answer away, which is what made a mirror look"
                        + " like armour and behave like a hole in the world's bookkeeping",
                Weapons.SUBJECT_TICKS, this::charge, FEED_EVERY_TICKS);
        long home = (long) Events.number(Events.recordsWhereAll(hitHome, "dim", String.valueOf(DIM),
                "pos", Weapons.at(GUN_X, Y, Z)).get(0), "seq");

        List<String> behind = Weapons.stagesSetAt(events, held, home + 1, DIM, MIRROR_X + 1, Y, Z);
        Reply behindNow = stageAt(MIRROR_X + 1);
        assertTrue("the iron BEHIND the mirror was damaged before the reflection reached the gun, so"
                + " the plating passed the beam through instead of turning it — this test is then"
                + " measuring a broken mirror and not a reflection: " + behind + " | " + behindNow,
                behind.isEmpty());
    }

    // ---- driving

    /** The same reference beam gun the dwell scenarios use: a controller, emitters, cooling. */
    private void buildBeamGun() throws Exception {
        place("stellurgy:turret", GUN_X, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("stellurgy:gunBeamEmitter", GUN_X, Y + i, Z);
        }
        place("stellurgy:gunCooling", GUN_X, Y, Z + 1);
        place("stellurgy:gunCooling", GUN_X, Y, Z - 1);
    }

    private void buildSite() throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((GUN_X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((GUN_X + 32) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill " + DIM + " " + (GUN_X - 8) + " " + (Y - 2) + " " + (Z - 4) + " "
                + (GUN_X + 28) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        for (int cx = ((GUN_X - 16) >> 4); cx <= ((GUN_X + 28) >> 4); cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
    }

    private void aimAt(int targetX) throws Exception {
        ask("stellurgytest turret target " + DIM + " " + GUN_X + " " + Y + " " + Z + " "
                + (targetX + 0.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("aim at the mirror");
        charge();
    }

    /**
     * Feed the gun, taking whatever the probe answers: the reflected beam wears the gun away, and a
     * gun gone by the end of the wait is this arrangement working rather than a failed feed.
     */
    private void charge() throws Exception {
        exec("stellurgytest turret charge " + DIM + " " + GUN_X + " " + Y + " " + Z);
    }

    // ---- reading

    private Reply stageAt(int x) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }


    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
