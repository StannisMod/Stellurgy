package dev.stannismod.stellurgy.test.server;

import java.util.List;

import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One switch, both weapon families, and a world that survives being switched.
 *
 * <h3>Why both families are pinned and not one</h3>
 * <p>The key this replaced gated the shot registry alone. A held beam has no record and never passes
 * through that registry, so a beam turret kept burning hulls on a server that had switched combat
 * off — and every instrument said the war was off. A switch that covers one family is worse than no
 * switch, because it reads as a promise, so the thrower half of this test is the control that would
 * have passed against the broken build and the beam half is the one that would not.</p>
 *
 * <h3>Why the silence is read off the guns' own decisions</h3>
 * <p>"Nothing fired for four seconds" said nothing on its own: a beam gun has to slew onto its wall
 * before it wants to burn at all, and a gun that never got there is silent for that reason alone. So
 * the off-leg waits for each gun to TAKE its decision with the war off — the thrower's fire question
 * answered on target, the beam's burn asked for and refused — and reads the verdict out of that
 * decision. The on-leg is the positive control for both halves: the same guns, not rebuilt, fire and
 * burn again, and the beam's wall takes a stage it did not take while the war was off.</p>
 *
 * <h3>Why ON again is the point</h3>
 * <p>The switch exists to be thrown on a world that has already been fought over and thrown back
 * later, so what OFF must NOT do is as load-bearing as what it does: damage already recorded stays,
 * and guns fire again afterwards without being rebuilt. So the war is switched off a second time over
 * a block carrying a stage of damage: the stage must still be there, and the welder — repair being
 * the obvious reason to switch the war off — must still take it off.</p>
 */
public class TheWarSwitchesOffAndOnAgainE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84, Z = 9900;
    private static final int THROWER_X = 9700, BEAM_X = 9760;
    /** Controller + three barrel-or-emitter sections + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 5;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /**
     * red-witnessed: with {@code !enableWeapons} taken out of {@code TileTurret.burnOneTick}'s gate
     * ({@code TileTurret#burnOneTick} at {@code if (!wantsToFire || perTick <= 0 || !StellurgyConfiguration.getCurrentConfig().enableWeapons)}), this fails at "the BEAM gun is lit with the war switched off" on a
     * {@code turret_beam} edge reading {@code lit:true, wanted:true, weapons:false} (2026-09-29).
     *
     * <p>red-witnessed: with the {@code enableWeapons} conjunct taken out of
     * {@code TileTurret.canFireNow} ({@code TileTurret#canFireNow} at {@code return StellurgyConfiguration.getCurrentConfig().enableWeapons}), this fails at "a thrower was
     * PERMITTED to fire with the war switched off" on a decision reading
     * {@code permitted:true, weapons:false} (2026-09-29).</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with {@code TileTurret#isDisabledByConfig}
     * at {@code return !StellurgyConfiguration.getCurrentConfig().enableWeapons;} answering false, this fails at "a gun reports itself merely idle
     * with combat switched off ... {...weaponsDisabled:false...}"; answering true, at "a gun still
     * reports itself disabled after the war was switched back on ... {...weaponsDisabled:true...}". That
     * method's only reader today is the probe's {@code turret read}, so these two verdicts pin what a
     * gun SAYS about the switch, not anything a player is shown.</p>
     *
     * <p>The second-off leg, one inversion per verdict, 2026-10-03:</p>
     *
     * <p>red-witnessed: with {@code ShotSubstrate#tick} at {@code endWhatWasStillInTheAir(world);} also
     * clearing the world's damage records, this fails at "switching the war off erased damage already on
     * the world: 9772,84,9903 carried stage 1 and with the war off it reads {...stage:0...}".</p>
     *
     * <p>red-witnessed: with {@code ItemRepairWelder#weld} at {@code if (stage <= 0)} followed by a
     * refusal while {@code enableWeapons} is off, this fails at "the welder refused with the war switched
     * off ... {...outcome:NO_CHARGE...}".</p>
     *
     * <p>red-witnessed: with {@code ItemRepairWelder#weld} at
     * {@code DamageState.setStage(world, pos, stage - 1);} writing {@code stage} unchanged, this fails at
     * "the weld with the war off did not take exactly one stage off ... {...outcome:REPAIRED,
     * stageBefore:1, stageAfter:1...}".</p>
     */
    @Test
    public void withTheWarOffNeitherFamilyDamagesAnythingAndBothWorkAgainAfterwards() throws Exception {
        long built = events.markInstrumented();
        buildSite(THROWER_X);
        buildGun(THROWER_X, false);
        buildSite(BEAM_X);
        buildGun(BEAM_X, true);
        Weapons.awaitAssembled(events, built, THROWER_X, Y, Z, PARTS, "the thrower never assembled");
        Weapons.awaitAssembled(events, built, BEAM_X, Y, Z, PARTS, "the beam gun never assembled");

        int throwerWall = THROWER_X + 12, beamWall = BEAM_X + 12;
        wall(throwerWall);
        wall(beamWall);

        long off = events.mark();
        long on;
        try {
            ask("stellurgytest config set enableWeapons false").requireOk("switch the war off");

            aimAndFeed(THROWER_X, throwerWall);
            aimAndFeed(BEAM_X, beamWall);

            // The thrower asks its fire question only once it is on target and charged; the record
            // of that question is where the switch answers.
            String refused = events.awaitRecordWithFields(off, "turret_fire_decided",
                    "the thrower never reached its wall with the war off, so its silence would be about"
                            + " the aim", Weapons.ARRANGEMENT_TICKS,
                    "pos", Weapons.at(THROWER_X, Y, Z), "caller", "auto", "weapons", "false");
            assertEquals("a thrower was PERMITTED to fire with the war switched off: " + refused,
                    "false", Events.text(refused, "permitted"));

            // The beam wants to burn once it is on target and not holding fire; the edge that says so
            // is the burn decision itself, taken with the war off.
            String asked = Weapons.awaitBeam(events, off, BEAM_X, Y, Z,
                    "the beam gun never wanted to burn with the war off, so it was never on its wall"
                            + " and its darkness would be about the aim",
                    "wanted", "true", "weapons", "false");
            assertEquals("the BEAM gun is lit with the war switched off — the half of the mechanic"
                    + " the old key never covered: " + asked, "false", Events.text(asked, "lit"));

            Reply thrower = read(THROWER_X);
            assertTrue("a gun reports itself merely idle with combat switched off: a disabled gun and"
                    + " a broken one then look identical, which is what the old switch did: " + thrower,
                    thrower.bool("weaponsDisabled"));

            String fired = events.since(off, "turret_fired");
            Events.assertInstrumentRan(fired, "turret_fire_events", "no round left with the war off");
            assertTrue("a thrower fired with the war switched off: " + fired,
                    Events.recordsWhere(fired, "pos", Weapons.at(THROWER_X, Y, Z)).isEmpty());
            String burned = events.since(off, "turret_beam");
            assertTrue("the beam gun lit at some tick with the war switched off: " + burned,
                    Events.recordsWhereAll(burned, "pos", Weapons.at(BEAM_X, Y, Z), "lit", "true")
                            .isEmpty());

            // Marked BEFORE the switch goes back: both guns are charged and on their walls, so they
            // act on the very tick the war returns, and an edge written then must be inside the window.
            on = events.mark();
            ask("stellurgytest config set enableWeapons true").requireOk("switch the war back on");
        } finally {
            ask("stellurgytest config set enableWeapons true").requireOk("restore the war switch");
        }

        // And on again, on the same world, with no rebuilding: the switch is meant to be thrown
        // twice. This leg is also what makes the silence above evidence — the same two guns, aimed at
        // the same two walls, do fire and burn once the switch is back.
        aimAndFeed(THROWER_X, throwerWall);
        aimAndFeed(BEAM_X, beamWall);
        Weapons.awaitFired(events, on, THROWER_X, Y, Z,
                "with the war switched back on the thrower never fired again: the switch is one-way,"
                        + " which is not what it was built for");
        Weapons.awaitBeam(events, on, BEAM_X, Y, Z,
                "with the war switched back on the beam gun never lit again", "lit", "true");
        events.awaitRecordWithFields(on, "block_stage_set",
                "the lit beam never staged its wall with the war back on, so its untouched wall above"
                        + " would not have been the switch's doing", Weapons.SUBJECT_TICKS,
                "dim", String.valueOf(DIM), "pos", Weapons.at(beamWall, Y, Z));
        Reply firing = read(THROWER_X);
        assertTrue("a gun still reports itself disabled after the war was switched back on: "
                + firing, !firing.bool("weaponsDisabled"));

        // Only now is the off-window's silence about the walls evidence: the stage write the on-leg
        // just produced is what proves the stage recorder was live on this server at all.
        assertTrue("the thrower's wall was staged with the war off: " + stage(throwerWall),
                Weapons.stagesSetAt(events, off, on, DIM, throwerWall, Y, Z).isEmpty());
        assertTrue("the beam's wall was staged with the war off, so the beam is still declaring"
                + " impacts: " + stage(beamWall),
                Weapons.stagesSetAt(events, off, on, DIM, beamWall, Y, Z).isEmpty());

        // And off AGAIN, now on a world that carries damage: what OFF must keep, and what it must
        // still allow. The damaged block is one stage into an iron block beside the beam's line,
        // declared through the damage engine with the war on — the beam itself takes an iron block
        // from undamaged to destroyed in one write (measured 2026-10-03: from 0 to 4 of max 4), so it
        // leaves nothing damaged-and-standing to keep. The record a declared stage writes is the one
        // weapon fire writes.
        int markedX = beamWall, markedZ = Z + 3;
        int markedStage = stageOneBlock(markedX, markedZ);
        long offAgain = events.mark();
        try {
            ask("stellurgytest config set enableWeapons false").requireOk("switch the war off again");
            // The gun's own decision taken with the war off is the record that the switch has acted
            // on this world: from here on nothing weapon-side writes a stage.
            Weapons.awaitBeam(events, offAgain, BEAM_X, Y, Z,
                    "the beam gun never took a tick with the war switched off again, so the reads below"
                            + " would be of a world the switch had not reached",
                    "wanted", "true", "weapons", "false");

            Reply kept = stageAt(markedX, markedZ);
            assertEquals("switching the war off erased damage already on the world: "
                            + Weapons.at(markedX, Y, markedZ) + " carried stage " + markedStage
                            + " and with the war off it reads " + kept,
                    markedStage, kept.integer("stage"));

            // Repair is not a weapon: with the war off the welder still takes a stage off. The tool is
            // charged past its capacity (the item clamps to it) and carries a full stack of the block's
            // material, so neither price — a fraction of the block's own recipe, and the per-stage
            // charge — can be what refuses it. Measured 2026-10-03: one stage of an iron block cost 9
            // ingots (64 -> 55 in the weld reply) and 2000 FE (100000 -> 98000).
            Reply weld = ask("stellurgytest damage weld " + DIM + " " + markedX + " " + Y + " " + markedZ + " "
                    + Integer.MAX_VALUE + " minecraft:iron_ingot 64").requireOk("weld with the war off");
            System.out.println("FIXTURE war-switch: with the war off, stage read " + kept + "; weld " + weld);
            assertEquals("the welder refused with the war switched off — repair is the reason to switch"
                    + " the war off at all: " + weld, "REPAIRED", weld.text("outcome"));
            assertEquals("the weld with the war off did not take exactly one stage off: " + weld,
                    markedStage - 1, weld.integer("stageAfter"));
        } finally {
            ask("stellurgytest config set enableWeapons true").requireOk("restore the war switch");
        }
    }

    /**
     * Put an iron block at {@code (x, Y, z)} and declare one impact into it carrying exactly the price
     * of one stage there ({@code stageCost}, read off the block itself); answers the stage it was left
     * at, which must be a damaged block still standing.
     */
    private int stageOneBlock(int x, int z) throws Exception {
        place("minecraft:iron_block", x, Y, z);
        int stageCost = stageAt(x, z).integer("stageCost");
        long declared = events.mark();
        Reply impact = ask("stellurgytest damage impact " + DIM + " " + (x - 0.5D) + " " + (Y + 0.5D) + " "
                + (z + 0.5D) + " 1 0 0 " + stageCost + " KINETIC");
        requireArranged("the damage engine refused the impact: " + impact, impact.ok());
        List<String> writes = Weapons.stagesSetAt(events, declared, DIM, x, Y, z);
        System.out.println("FIXTURE war-switch: damaged block " + Weapons.at(x, Y, z) + " stageCost=" + stageCost
                + " impact=" + impact + " stage write=" + writes);
        requireArranged("one stage's worth of impact did not leave the block damaged and standing: "
                + writes + " after " + impact, writes.size() == 1
                && Events.number(writes.get(0), "to") > 0
                && Events.number(writes.get(0), "to") < Events.number(writes.get(0), "max"));
        return (int) Events.number(writes.get(0), "to");
    }

    // ---- driving

    private void buildGun(int bx, boolean beam) throws Exception {
        place("stellurgy:turret", bx, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place(beam ? "stellurgy:gunBeamEmitter" : "stellurgy:gunBarrel", bx, Y + i, Z);
        }
        place("stellurgy:gunCooling", bx, Y, Z + 1);
        place("stellurgy:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 48) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill " + DIM + " " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " " + (bx + 40)
                + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        for (int cx = ((bx - 16) >> 4); cx <= ((bx + 40) >> 4); cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4))
                    .requireOk("hold the site's chunks");
        }
    }

    private void wall(int x) throws Exception {
        ask("stellurgytest fill " + DIM + " " + x + " " + Y + " " + Z + " " + (x + 3) + " " + Y + " " + Z
                + " minecraft:iron_block").requireOk("build the wall");
    }

    private void aimAndFeed(int bx, int wallX) throws Exception {
        ask("stellurgytest turret charge " + DIM + " " + bx + " " + Y + " " + Z).requireOk("charge the gun");
        ask("stellurgytest turret target " + DIM + " " + bx + " " + Y + " " + Z + " " + (wallX + 0.5D)
                + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("aim the gun at its wall");
    }

    // ---- reading

    private Reply read(int bx) throws Exception {
        return ask("stellurgytest turret read " + DIM + " " + bx + " " + Y + " " + Z).requireOk("read the gun");
    }

    private Reply stage(int x) throws Exception {
        return stageAt(x, Z);
    }

    private Reply stageAt(int x, int z) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + z).requireOk("read the stage");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
