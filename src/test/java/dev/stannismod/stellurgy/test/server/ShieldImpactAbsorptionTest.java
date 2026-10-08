package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.EntityState;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.ShieldTile;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.FixtureSite;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P1: a charged shield emitter must absorb a single impact whose cost exceeds the coil's per-tick
 * intake. An energy projectile (AFFS {@code laser_bolt}) costs {@code energyProjectileImpactEnergy}
 * (default 10000) — far above a Tier 0 emitter's per-tick recharge throughput (default 4000).
 * Absorption is all-or-nothing, so
 * if the coil could only release a per-tick sliver it would refuse the bolt outright (and burn the
 * sliver). This pins that a well-charged coil actually stops the bolt at the shell and pays its full
 * cost — guarding the fix that unthrottles coil extraction.
 *
 * <p>The emitter absorbs in its {@code update()} (via {@code containUnauthorizedEntities}); the test
 * drives one deterministic emitter tick with {@code /stellurgytest tile force-tick} after spawning the bolt
 * inside the influence box.</p>
 */
public class ShieldImpactAbsorptionTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = FixtureSite.OPEN_AIR_Y;
    private static final int FE_PER_ITERATION = 4000;
    private static final int ENERGY_PROJECTILE_COST = 10_000; // ModConfig.energyProjectileImpactEnergy default
    private static final String STORED = "shieldStored";
    private static final String ENTITY_ID = "entityId";

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** Pins INV-SHD-05 (a charged coil absorbs a single impact costing more than its per-tick intake). */
    @Test
    public void chargedCoilAbsorbsEnergyProjectileCostingMoreThanIntake() throws Exception {
        int gx = 980, gz = 774;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);

        // Charge the coil well above one bolt's cost, so the coil charge is not the limiter — only the
        // (pre-fix) per-tick extract cap could stand between a full coil and absorbing the bolt.
        for (int i = 0; i < 15; i++) {
            exec("stellurgytest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
            exec("stellurgytest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
            exec("stellurgytest shield tick " + DIM);
        }
        ShieldTile before = read(ex, gz);
        assertTrue("emitter never powered — cannot test absorption:\n" + before.raw(),
                before.powered());
        long storedBefore = before.shieldStored();
        assertTrue("precondition: coil not charged above one bolt's cost (stored=" + storedBefore + ")",
                storedBefore > ENERGY_PROJECTILE_COST + 5_000L);

        // Spawn an energy projectile in the air just above the powered emitter (inside the influence
        // box, not embedded in a block so it cannot self-destruct on a collision).
        String spawn = exec("stellurgytest entity spawn " + DIM + " " + (ex + 0.5D) + " " + (Y + 1.5D)
                + " " + (gz + 0.5D) + " affs:laser_bolt");
        int boltId = readEntityId(spawn);

        // One deterministic emitter tick: containUnauthorizedEntities runs and absorbs the bolt.
        exec("stellurgytest tile force-tick " + DIM + " " + ex + " " + Y + " " + gz + " 1");

        EntityState boltInfo = entity(boltId);
        assertTrue("the powered coil did not absorb the energy projectile (it survived): a full coil "
                        + "cannot block a hit larger than its per-tick intake:\n" + boltInfo.raw(),
                boltInfo.goneOrDying());

        long storedAfter = read(ex, gz).shieldStored();
        long drop = storedBefore - storedAfter;
        // Corroborate the bolt died to the shield, not to some incidental collision: the coil must have
        // actually paid roughly the projectile's cost. (Guards against a false green where the bolt
        // self-destructs without any absorption.)
        assertTrue("coil energy did not drop by the projectile's cost (before=" + storedBefore
                        + " after=" + storedAfter + " drop=" + drop + "): the bolt was not absorbed by "
                        + "the shield.", drop >= ENERGY_PROJECTILE_COST - 1_000L);
    }

    /**
     * A powered shield keeps a block it covers through a blast that destroys the same block unshielded,
     * and pays for it out of its coil.
     *
     * <p>The payment is read off the shield's own decision — the coil as the blast is heard and as it
     * is decided, inside one handler call — and not off two probe reads of the coil a round trip
     * apart, which a refill from the network could make equal under load.</p>
     *
     * <p>red-witnessed: with {@code TileEntityFieldGenerator#tryAbsorbExplosionImpact} at
     * {@code return impactEnergy > 0 && consumeShieldEnergy(impactEnergy) >= impactEnergy;} reduced to
     * {@code return impactEnergy > 0;} — the glass still saved, nothing paid — this fails at "the
     * emitter took the glass out of the blast without paying for it (coil 37689 as the blast was
     * heard, 37689 once decided)" (2026-10-03).</p>
     */
    @Test
    public void chargedShieldProtectsBlocksFromExplosion() throws Exception {
        // Control first: an unshielded glass block is destroyed by the blast. Without this the shielded
        // case proves nothing — the block might survive because the explosion is too weak, not because
        // of the shield.
        int cx = 940, cz = 780;
        place("minecraft:glass", cx, cz);
        exec("stellurgytest shield explode " + DIM + " " + (cx + 0.5D) + " " + (Y + 1.5D) + " " + (cz + 0.5D) + " 4");
        String controlBlock = exec("stellurgytest block at " + DIM + " " + cx + " " + Y + " " + cz);
        assertTrue("control: the explosion did not destroy an unshielded glass block — the blast is "
                        + "not lethal here, so the shielded case would prove nothing:\n" + controlBlock,
                Reply.of(controlBlock).bool("isAir"));

        // Shielded: the same block, same blast, but inside a powered field — it must survive.
        int gx = 986, gz = 780;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).powered());

        int px = ex + 2, pz = gz; // inside the emitter's radius-4 field
        place("minecraft:glass", px, pz);
        long blast = events.markInstrumented();
        exec("stellurgytest shield explode " + DIM + " " + (px + 0.5D) + " " + (Y + 1.5D) + " " + (pz + 0.5D) + " 4");

        String shieldedBlock = exec("stellurgytest block at " + DIM + " " + px + " " + Y + " " + pz);
        // The id, compared. `contains("minecraft:glass")` is also satisfied by
        // `minecraft:glass_pane` and by `stained_glass`, so a block the explosion REPLACED with a
        // glass variant would have read as the shield protecting the original.
        // the producer always writes `block` for a loaded dimension, and this asks about one
        // the fixture has just built in.
        assertEquals("a glass block inside a powered shield was destroyed by an explosion — the field did "
                        + "not protect it:\n" + shieldedBlock,
                "minecraft:glass", Reply.of("stellurgytest block at", shieldedBlock).text("block"));

        // That the shield PAID for it, read off the decision itself rather than off two coil samples
        // taken a probe round-trip apart: the network refills an emitter every tick, so a debit sampled
        // that way can be paid back before the second read and look like no debit at all. Both records
        // below are written inside the one handler call that decided the blast, with no tick between.
        // Read, not awaited: the probe detonates inside its own call and the handler records inside the
        // detonation (ForceFieldExplosionHandler#onExplosionDetonate, HEAD and RETURN), so both records
        // exist by the time the command has answered.
        Reply decided = Reply.of("shield_explosion_decided",
                oneBlastRecord(events.since(blast, "shield_explosion_decided"), px, pz));
        Reply heard = Reply.of("shield_explosion_heard",
                oneBlastRecord(events.since(blast, "shield_explosion_heard"), px, pz));
        String glass = px + "," + Y + "," + pz;
        requireArranged("the blast never reached the glass, so its surviving says nothing about the"
                + " shield: " + heard, heard.holdsText("candidates", glass));
        String emitter = ex + "," + Y + "," + gz;
        long storedHeard = heard.element("emitters", "pos", emitter).longInteger("stored");
        long storedDecided = decided.element("emitters", "pos", emitter).longInteger("stored");
        System.out.println("FIXTURE shield-explosion: glass " + glass + " emitter " + emitter + " coil heard="
                + storedHeard + " decided=" + storedDecided + "; heard " + heard + "; decided " + decided);
        assertTrue("the emitter took the glass out of the blast without paying for it (coil "
                        + storedHeard + " as the blast was heard, " + storedDecided + " once decided)",
                storedDecided < storedHeard);
    }

    /**
     * The one record in {@code since} for the blast centred over {@code (x, Y+1, z)}; fails naming the
     * reply when the handler wrote none for it, or the recorder never ran.
     */
    private static String oneBlastRecord(String since, int x, int z) {
        Events.assertInstrumentRan(since, "shield_explosion_events", "the shield's decision on the blast");
        java.util.List<String> here = Events.recordsWhere(since, "at", blastCentre(x, z));
        requireArranged("the shield's handler did not record exactly one decision for the blast, so"
                + " there is no decision to read the payment from: " + since, here.size() == 1);
        return here.get(0);
    }

    /** The {@code at} the explosion recorder writes for a blast centred over block {@code (x, Y+1, z)}. */
    private static String blastCentre(int x, int z) {
        return (x + 0.5D) + "," + (Y + 1.5D) + "," + (z + 0.5D);
    }

    /** Pins INV-SHD-06 (a kinetic projectile is deflected, not consumed). */
    @Test
    public void chargedShieldDeflectsAnArrow() throws Exception {
        int gx = 992, gz = 786;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).powered());

        double centerX = ex + 0.5D, centerY = Y + 0.5D, centerZ = gz + 0.5D;
        double radius = 4.0D;
        // An arrow on the +Z shell, aimed straight inward at the emitter.
        double sx = centerX, sy = centerY, sz = centerZ + radius;
        String spawn = exec("stellurgytest entity spawn " + DIM + " " + sx + " " + sy + " " + sz + " minecraft:arrow");
        int arrowId = readEntityId(spawn);
        exec("stellurgytest entity set-motion " + DIM + " " + arrowId + " 0 0 -0.3");
        // Step the arrow inward once so it physically advances (pos != prevPos): the shield's deflection
        // reads the pos->prevPos sweep, so a stationary arrow with only a motion field is never repelled.
        exec("stellurgytest entity tick " + DIM + " " + arrowId + " 1");
        exec("stellurgytest tile force-tick " + DIM + " " + ex + " " + Y + " " + gz + " 1");

        EntityState info = entity(arrowId);
        assertTrue("the arrow is gone (absorbed/dead), not deflected — a kinetic projectile should be "
                        + "pushed back, not consumed:\n" + info.raw(),
                info.alive && !info.dead());
        double dist = Math.sqrt(sq(info.posX() - centerX) + sq(info.posY() - centerY)
                + sq(info.posZ() - centerZ));
        assertTrue("the arrow ended up inside the shell (dist=" + dist + " <= radius " + radius
                        + "): it was not deflected back outside the shield:\n" + info.raw(),
                dist > radius);
    }

    private ShieldTile read(int x, int z) throws Exception {
        return ShieldTile.at(cmd -> exec(cmd), DIM, x, Y, z);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("stellurgytest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                Reply.of(resp).bool("placed"));
    }

    private void chargeIteration(int gx, int gz) throws Exception {
        exec("stellurgytest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
        exec("stellurgytest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
        exec("stellurgytest shield tick " + DIM);
    }

    /** What the server says about one entity in this test's dimension. */
    private EntityState entity(int entityId) throws Exception {
        return EntityState.byId(this::exec, DIM, entityId);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static long readStored(String json) {
        Reply mReply = Reply.of(json);
        assertTrue("no shieldStored field in probe response: " + json, mReply.has(STORED));
        return Long.parseLong(mReply.text(STORED));
    }

    private static int readEntityId(String json) {
        Reply mReply = Reply.of(json);
        assertTrue("no entityId in spawn response: " + json, mReply.has(ENTITY_ID));
        return Integer.parseInt(mReply.text(ENTITY_ID));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
