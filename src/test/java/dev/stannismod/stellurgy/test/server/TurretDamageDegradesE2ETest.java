package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The first time losing a fight costs anything but holes.
 *
 * <p>A gun is shot at through the damage engine's own entry point — no probe writes a drive state —
 * and the mount walks down its ladder: it turns, it turns slowly, it seizes. The last rung is the
 * one worth having a test for: <b>a seized gun still fires</b> down the bearing it stopped at, which
 * is the whole reason the ladder ends in a named state and not in a rate of zero.</p>
 *
 * <p>The ladder is read off the mount's own record of its drive state ({@code turret_aim}, which the
 * gun re-derives from its condition every tick) as a CHAIN: the order of the rungs is the contract,
 * so it is the order of the records that is asserted.</p>
 */
public class TurretDamageDegradesE2ETest extends AbstractSharedServerTest {

    private static final int X = 9000, Y = 80, Z = 9000;
    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;

    /**
     * How many stages one impact is allowed to buy. Sized from the block's OWN stage cost, read off
     * the probe rather than guessed: the cost comes from the toughness table, which is balance and
     * will move, and a hard-coded budget silently stops damaging anything the day it does.
     */
    private static final double STAGES_PER_IMPACT = 1.5D;

    /**
     * Impact identities, never reused. The service refuses a repeated id and answers
     * {@code DUPLICATE_IMPACT} — correct behaviour, and it silently ends a scenario that walks the
     * ladder in two passes if both passes number their shots from the same place.
     */
    private int nextImpactId = 1000;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /**
     * red-witnessed: with {@code TurretDriveState.permitsFiring()} ({@code TurretDriveState#permitsFiring} at {@code return this != DEAD;})
     * made to refuse {@code JAMMED} as well as {@code DEAD}, this fails at "a seized gun stopped
     * firing — then JAMMED is just a slower way of saying DEAD" (2026-09-29).
     *
     * <p>red-witnessed: with {@code TurretDriveState#fromDamage} at {@code if (damageFraction >= Math.max(derateAt, jamAt))} jamming at the LOWER of the two
     * thresholds (no derated rung), the ladder verdict fails with "a damaged mount went straight from
     * turning to seizing without ever turning slowly ... [JAMMED]" (2026-09-30).</p>
     *
     * <p>red-witnessed: with {@code TileTurret.launch} ({@code TileTurret#launch} at {@code markDirty();}) setting the mount's
     * drive back to WORKING as a round leaves, this fails at "the round left, but not from a seized
     * mount: {...drive:WORKING...} expected:&lt;[JAMMED]&gt;" (2026-09-30).</p>
     */
    @Test
    public void aGunShotUpTurnsSlowlyThenSeizesAndStillFires() throws Exception {
        int base = X;
        buildSite(base);
        long built = events.markInstrumented();
        buildGun(base);
        Weapons.awaitAssembled(events, built, base, Y, Z, PARTS, "the gun never assembled");
        assertEquals("a pristine gun must report a working drive", "WORKING", read(base).text("drive"));

        // It works: pointed at something, it turns onto it and fires. Without this the degradation
        // below would be indistinguishable from a gun that never did anything.
        long aimed = events.mark();
        ask("stellurgytest turret charge 0 " + base + " " + Y + " " + Z).requireOk("charge the gun");
        ask("stellurgytest turret target 0 " + base + " " + Y + " " + Z + " " + (base + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("aim the gun");
        Weapons.awaitFired(events, aimed, base, Y, Z, "the pristine gun never fired, so nothing below is"
                + " about damage");

        // Now shoot the mount itself, through production's own path, until it seizes.
        long shotAt = events.mark();
        String seized = shootUntilDrive(base, "JAMMED");
        List<String> ladder = driveLadder(shotAt, base);
        assertTrue("a damaged mount went straight from turning to seizing without ever turning slowly"
                + " — it must turn slowly rather than either working perfectly or dying outright: "
                + ladder + " | " + seized, ladder.indexOf("DERATED") >= 0
                && ladder.indexOf("DERATED") < ladder.lastIndexOf("JAMMED"));

        // The rung that earns its own name: it stopped turning, it did not stop shooting.
        long jammed = events.mark();
        ask("stellurgytest turret charge 0 " + base + " " + Y + " " + Z).requireOk("recharge the gun");
        String fired = Weapons.awaitFired(events, jammed, base, Y, Z,
                "a seized gun stopped firing — then JAMMED is just a slower way of saying DEAD, and a"
                        + " whole class of desperate defence is gone: " + read(base));
        assertEquals("the round left, but not from a seized mount: " + fired, "JAMMED",
                Events.text(fired, "drive"));
    }

    // ---- driving the world

    /**
     * Hit the mount until its own record says its drive is {@code wanted}, and answer that record.
     * Every impact carries its own identity, because the service refuses a repeat.
     */
    private String shootUntilDrive(int bx, String wanted) throws Exception {
        List<String> seen = new ArrayList<>();
        // STIMULUS: each iteration IS the dose — one impact — and the loop stops on the mount's own
        // record of the rung, never on a sample. Deleting the loop stops the damage happening, not
        // merely being watched; it is bounded by the block's own stage count, past which the mount
        // is gone rather than seized.
        for (int shot = 0; shot < 40; shot++) {
            long hit = events.mark();
            Reply stage = ask("stellurgytest damage stage 0 " + bx + " " + Y + " " + Z)
                    .requireOk("price the mount");
            // the producer always writes `stageCost` on an ok `damage stage` reply, so a reply without
            // it is a broken probe and refusing here is the right failure.
            int budget = (int) Math.ceil(stage.integer("stageCost") * STAGES_PER_IMPACT);
            // From the SIDE, at the mount's own height, through cleared air: the mount is the first
            // solid thing the ray meets. From above it would go through the barrels first and take
            // the gun apart before the drive ever degraded — which is a different experiment, and
            // the one the first version of this test accidentally ran.
            Reply resp = ask("stellurgytest damage impact 0 " + (bx - 2.5D) + " " + (Y + 0.5D) + " "
                    + (Z + 0.5D) + " 1 0 0 " + budget + " KINETIC " + (nextImpactId++))
                    .requireOk("shoot the mount");
            assertTrue("the impact spent nothing — it is not reaching the mount, and every assertion"
                    + " after this would be about an undamaged gun: " + resp, resp.integer("spent") > 0);
            assertTrue("the gun's own block was destroyed before it could seize: " + resp,
                    resp.integer("destroyed") == 0);
            // The mount re-reads its condition on its NEXT tick: one world pass, then its own record.
            GameTicks.advance(client(), GameTicks.server(), 1);
            String aim = events.since(hit, "turret_aim");
            for (String edge : Events.recordsWhere(aim, "pos", Weapons.at(bx, Y, Z))) {
                seen.add(Events.text(edge, "drive"));
                if (wanted.equals(Events.text(edge, "drive"))) {
                    return edge;
                }
            }
        }
        throw new AssertionError("forty impacts never brought the mount to " + wanted + ": the drive"
                + " rungs it recorded were " + seen + " | " + read(bx));
    }

    /** The drive states the mount recorded since {@code mark}, in order, consecutive repeats kept. */
    private List<String> driveLadder(long mark, int bx) throws Exception {
        String aim = events.since(mark, "turret_aim");
        Events.assertInstrumentRan(aim, "turret_aim_events", "the drive ladder of the mount");
        List<String> ladder = new ArrayList<>();
        for (String edge : Events.recordsWhere(aim, "pos", Weapons.at(bx, Y, Z))) {
            ladder.add(Events.text(edge, "drive"));
        }
        return ladder;
    }

    private void buildGun(int bx) throws Exception {
        place("stellurgy:turret", bx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", bx, Y + i, Z);
        }
        place("stellurgy:gunCooling", bx, Y, Z + 1);
        place("stellurgy:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        ask("stellurgytest chunk warmup 0 " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill 0 " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " " + (bx + 60) + " "
                + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        ask("stellurgytest chunk forceload 0 " + (bx >> 4) + " " + (Z >> 4)).requireOk("hold the chunk");
    }

    // ---- reading the world

    private Reply read(int bx) throws Exception {
        return ask("stellurgytest turret read 0 " + bx + " " + Y + " " + Z).requireOk("read the gun");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
