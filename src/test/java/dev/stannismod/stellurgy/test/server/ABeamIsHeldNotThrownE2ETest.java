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
 * A beam is HELD, and what that means is that dwell is the weapon.
 *
 * <p>A gun that throws rounds spends its energy in lumps at intervals, and holding the trigger longer
 * buys more lumps. A beam has no lump: it is a line with a power, re-resolved every tick, and its depth
 * grows for as long as it is lit. These pin that difference where it is visible — in the hole — and the
 * one behaviour that makes a starved beam readable instead of a stutter.</p>
 *
 * <p>Numbers are deliberately not asserted. Every one of them is balance and will move; what is claimed
 * is an ORDERING (longer dwell digs deeper) and a STATE MACHINE (lit, then dark and saving), read off
 * the gun's own burn record ({@code turret_beam}).</p>
 */
public class ABeamIsHeldNotThrownE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84, Z = 9500;
    private static final int DWELL_X = 9600, STARVED_X = 9660;
    /** Deep enough that a short dwell cannot reach the far side; see the dwell scenario. */
    private static final int WALL_DEPTH = 30;
    /** Controller + three emitters + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 5;
    /**
     * One feed-and-burn cycle, in server ticks: the dose unit of the dwell experiment. It is the
     * 1.4 s the scenario was first written with, at the 20 ticks a second a healthy server runs, and
     * it is a DOSE rather than a wait — the gun is fed at the start of each, so what varies between
     * the two legs is how many of these the beam was held on the wall.
     */
    private static final int CYCLE_TICKS = 28;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * The claim the whole family exists for: keeping it on the same spot digs DEEPER, with no second
     * trigger pull and no round in flight anywhere.
     *
     * <p>The gun is kept fed while it burns, and that is not a convenience — it is the subject. A
     * beam with a finite buffer and no supply stops at what its capacitor held, and then "held twice
     * as long" would measure the capacitor rather than the dwell. Fed, what is left to vary is the
     * time on target, which is the thing being claimed. The dose starts only once the gun has LIT on
     * the wall, so the slew onto it is not billed to either leg.</p>
     *
     * <p>red-witnessed: with {@code HeldBeam#emit} at {@code TravellingBody body = new TravellingBody(ShotRegistry.get(world).nextImpactId(),} giving every tick of the beam the same impact
     * identity (so the dedup memory refuses every tick after the first), this fails with "keeping the
     * beam on the same spot four times as long got no further into the wall (short=1 long=1)".
     * 2026-09-30.</p>
     */
    @Test
    public void holdingItLongerDigsDeeper() throws Exception {
        buildSite(DWELL_X);
        long built = events.markInstrumented();
        buildBeamGun(DWELL_X);
        Weapons.awaitAssembled(events, built, DWELL_X, Y, Z, PARTS, "the beam gun never assembled");

        // Deep enough that a SHORT dwell cannot cross it, and made of the toughest ordinary thing
        // there is. The first version was six blocks of stone: the short burn went through all six,
        // both measurements read "6", and the test was reporting the depth of its own wall.
        int wallX = DWELL_X + 12;
        fill(wallX, wallX + WALL_DEPTH - 1, "minecraft:iron_block");
        long aimed = events.mark();
        charge(DWELL_X);
        aimAt(DWELL_X, wallX + WALL_DEPTH + 8);
        Weapons.awaitBeam(events, aimed, DWELL_X, Y, Z, "the beam gun never lit on the wall", "lit", "true");

        int afterShort = burnFor(DWELL_X, 1);
        assertTrue("a beam held on an iron wall cut nothing at all into it: then it is not "
                + "depositing its power into what it is pointed at, and dwell buys nothing. gun="
                + read(DWELL_X), afterShort > 0);
        assertTrue("the short burn already crossed the whole wall (" + afterShort + " of "
                + WALL_DEPTH + "): the measurement is saturated and cannot show a longer one going"
                + " further, whatever production does", afterShort < WALL_DEPTH);

        int afterLong = burnFor(DWELL_X, 4);
        assertTrue("keeping the beam on the same spot four times as long got no further into the "
                + "wall (short=" + afterShort + " long=" + afterLong + "): then depth does not grow "
                + "with dwell and a beam is just a gun with an odd fire rate", afterLong > afterShort);
    }

    /**
     * A starved beam goes DARK and saves up, rather than flickering at whatever rate its feed happens
     * to deliver. The distinction a fire control cannot be built without: "not shooting" and "cannot
     * shoot yet" are different answers, and only one of them means the gun is broken.
     *
     * <p>red-witnessed: with {@code beamRecharging = true} removed from {@code TileTurret.burnOneTick}'s
     * starved branch ({@code TileTurret#burnOneTick} at {@code beamRecharging = true;}), this fails at "a beam with no feed never went dark
     * to save up" (2026-09-29).</p>
     *
     * <p>red-witnessed: with that starved branch ({@code TileTurret#burnOneTick} at {@code return false;}) answering lit on the
     * tick it goes dark, this fails at "the gun reports itself lit while it is recharging ...
     * [{...lit:true,wanted:true,weapons:true,recharging:true,energy:0}]" (2026-09-30). The first form of
     * this verdict read only the record the wait returned — the newest, dark one — and stayed GREEN
     * under the same inversion; it now reads every recharging edge in the window.</p>
     */
    @Test
    public void aStarvedBeamGoesDarkAndSavesUpInsteadOfStuttering() throws Exception {
        buildSite(STARVED_X);
        long built = events.markInstrumented();
        buildBeamGun(STARVED_X);
        Weapons.awaitAssembled(events, built, STARVED_X, Y, Z, PARTS, "the beam gun never assembled");
        Reply gun = read(STARVED_X);
        assertTrue("this gun does not think it is a beam at all, so the run would test a thrower: "
                + gun, gun.integer("beamPower") > 0);

        int wallX = STARVED_X + 12;
        fill(wallX, wallX + 5, "minecraft:iron_block");
        long fed = events.mark();
        charge(STARVED_X);
        aimAt(STARVED_X, wallX + 20);

        // Nothing feeds this gun after this one charge, so its buffer is all it will ever have: burn
        // it down and the duty cycle is what happens next.
        String dark = Weapons.awaitBeam(events, fed, STARVED_X, Y, Z,
                "a beam with no feed never went dark to save up: then it either fired on an empty"
                        + " buffer or it stuttered on whatever arrived, and neither is a state anything"
                        + " can act on", "recharging", "true");
        // Every edge the gun wrote while saving up, not the one the wait returned: the record is
        // edge-only on (lit, wanted, weapons, recharging), so a single tick reported lit while
        // recharging is followed by a dark one on the next, and the wait's own record is the newest.
        String burns = events.since(fed, "turret_beam");
        Events.assertInstrumentRan(burns, "turret_beam_events", "the gun burned before it went dark");
        List<String> litWhileSaving = Events.recordsWhereAll(burns, "pos", Weapons.at(STARVED_X, Y, Z),
                "recharging", "true", "lit", "true");
        assertTrue("the gun reports itself lit while it is recharging: then the two states are one"
                + " and fire control cannot tell not-shooting from cannot-shoot-yet: " + litWhileSaving
                + " | the wait's record: " + dark, litWhileSaving.isEmpty());
        // CONTROL, and it is not decoration: an earlier version of this scenario went green against a
        // gun whose buffer was too small to ever hold its own quantum, so it was dark from the first
        // tick and never fired at all. "Went dark" only means anything if it burned FIRST.
        long darkSeq = (long) Events.number(dark, "seq");
        List<String> litFirst = Events.recordsWhereAll(burns, "pos", Weapons.at(STARVED_X, Y, Z), "lit", "true");
        litFirst.removeIf(edge -> Events.number(edge, "seq") >= darkSeq);
        assertTrue("the gun went dark without ever having burned: then this measured a weapon that"
                + " cannot fire, not one that ran its capacitor down: " + burns, !litFirst.isEmpty());
    }

    // ---- driving

    /** The reference beam gun: a controller with emitters on it and cooling around it. */
    private void buildBeamGun(int bx) throws Exception {
        place("stellurgy:turret", bx, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("stellurgy:gunBeamEmitter", bx, Y + i, Z);
        }
        place("stellurgy:gunCooling", bx, Y, Z + 1);
        place("stellurgy:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill " + DIM + " " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " " + (bx + 60)
                + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        for (int cx = (bx >> 4); cx <= ((bx + 40) >> 4); cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
    }

    private void fill(int fromX, int toX, String block) throws Exception {
        ask("stellurgytest fill " + DIM + " " + fromX + " " + Y + " " + Z + " " + toX + " " + Y + " " + Z
                + " " + block).requireOk("build the wall");
    }

    private void charge(int bx) throws Exception {
        ask("stellurgytest turret charge " + DIM + " " + bx + " " + Y + " " + Z).requireOk("feed the gun");
    }

    private void aimAt(int bx, int targetX) throws Exception {
        ask("stellurgytest turret target " + DIM + " " + bx + " " + Y + " " + Z + " " + (targetX + 0.5D)
                + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("aim the gun");
    }

    // ---- reading

    /**
     * Keep the gun fed and on target for {@code cycles} feed-and-burn cycles, then report how deep
     * the hole is. The feed stands in for the ship supply this scenario deliberately does not build:
     * without it the measurement would be of the capacitor, not of the dwell.
     */
    private int burnFor(int bx, int cycles) throws Exception {
        // EXPERIMENT: the number of cycles IS the dose — the thing the two legs differ in. Nothing
        // is awaited here; the depth read after it is the measurement of what the dose bought.
        for (int i = 0; i < cycles; i++) {
            charge(bx);
            GameTicks.advance(client(), GameTicks.server(), CYCLE_TICKS);
        }
        return depthOf(bx + 12);
    }

    /** How many blocks of the wall are gone or marked. */
    private int depthOf(int wallX) throws Exception {
        int depth = 0;
        for (int i = 0; i < WALL_DEPTH; i++) {
            Reply state = ask("stellurgytest damage stage " + DIM + " " + (wallX + i) + " " + Y + " " + Z)
                    .requireOk("read a stage of the wall");
            // the producer always writes `wasDestroyed`, `block` and `stage` on an ok `damage stage`
            // reply, so a reply without them is a broken probe and refusing here is the right failure.
            if (state.bool("wasDestroyed") || "minecraft:air".equals(state.text("block"))
                    || state.integer("stage") > 0) {
                depth = i + 1;
            }
        }
        return depth;
    }

    private Reply read(int bx) throws Exception {
        return ask("stellurgytest turret read " + DIM + " " + bx + " " + Y + " " + Z).requireOk("read the gun");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }
}
