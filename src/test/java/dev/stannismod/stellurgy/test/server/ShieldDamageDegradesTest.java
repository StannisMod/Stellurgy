package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What being shot does to a shield, other than draining it.
 *
 * <p>Three claims, and the first two are a pair that only mean something together. A damaged emitter
 * <b>covers less ground</b> — the shell draws in and a stretch of hull it used to hold stops being
 * held — and it is <b>still billed for the field it was told to project</b>, because a shield that
 * got cheaper the more it was shot would make taking fire a way to save energy. Then the third: a
 * <b>neighbour that still reaches closes the hole</b>, and one that does not leaves it open, which is
 * nothing anybody implemented — it is what a smooth union of spheres already does, and the test
 * exists so it stays true.</p>
 *
 * <p>Everything here is driven through production's own doors: the damage arrives as a declared
 * impact through the damage engine, never as a probe writing a radius, and the coverage is read from
 * the emitter's own predicate rather than recomputed by the test.</p>
 */
public class ShieldDamageDegradesTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;
    private static final int Z = 830;

    /** How many stages one impact is allowed to buy, sized from the block's OWN stage cost. */
    private static final double STAGES_PER_IMPACT = 1.2D;

    private static final String RADIUS = "radius";
    private static final String DECLARED = "declaredRadius";
    private static final String CYCLE_COST = "cycleCost";
    private static final String CONVERSION = "conversionPerTick";
    private static final String THROUGHPUT = "throughput";
    private static final String CAPACITY = "shieldMaxEffective";
    private static final String STAGE = "stage";
    private static final String STAGE_COST = "stageCost";

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * red-witnessed: with {@code TileEntityFieldGenerator#refreshEffectiveRadius} at {@code int derived = ShieldCondition.effectiveRadius(world, pos, radius, MIN_RADIUS);} deriving the effective radius as
     * the declared one, this fails with "a damaged emitter must project a SMALLER field than it did
     * pristine (4 vs 4)"; with {@code TileEntityFieldGenerator#protects} at {@code return distanceSqToCenter(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= getFieldRadiusSq();}'s {@code protects} measured
     * against the declared radius, it fails with "the shell drew in and the hull it uncovered is still
     * reported as covered: {...covered:true...}"; with {@code TileEntityFieldGenerator#getShieldCycleCost} at {@code return estimateShieldCost(radius);}
     * pricing the cycle off the effective radius, it fails with "a shrunken emitter was billed less
     * than the field it declared (12060 -> 6780)". 2026-09-30.
     */
    @Test
    public void aDamagedEmitterCoversLessAndIsStillBilledForWhatItDeclared() throws Exception {
        int gx = 1200, ex = 1201;
        clearSite(gx - 6, gx + 12);
        place("affs:shield_generator", gx);
        place("affs:field_generator", ex);
        powerUp(gx, ex);

        String pristine = readShield(ex);
        int declared = (int) readInt(DECLARED, pristine);
        int radiusBefore = (int) readInt(RADIUS, pristine);
        long costBefore = readInt(CYCLE_COST, pristine);
        assertEquals("precondition: an undamaged emitter must project the field it declared:\n"
                + pristine, declared, radiusBefore);

        // A block on the shell's edge: covered while the emitter is whole, and the first thing it
        // drops when the field draws in. Derived from the radius the emitter reports, not guessed.
        int edge = ex + radiusBefore;
        assertTrue("precondition: the edge of a pristine field must be covered, or the loss below is"
                + " about nothing:\n" + readZone(edge), covered(edge));

        int radiusAfter = shootUntilFieldShrinks(ex, radiusBefore);
        String damaged = readShield(ex);
        assertTrue("a damaged emitter must project a SMALLER field than it did pristine (" + radiusAfter
                + " vs " + radiusBefore + "): the consequence a player is supposed to SEE coming did"
                + " not happen:\n" + damaged, radiusAfter < radiusBefore);

        // It must still be lit, or "no longer covered" would be about the power, not the radius.
        assertTrue("the shield went dark, so the coverage assertions below would pass for the wrong"
                + " reason:\n" + damaged, Reply.of(damaged).bool("powered"));
        assertTrue("the shell drew in and the hull it uncovered is still reported as covered:\n"
                + readZone(edge), !covered(edge));

        assertEquals("damage moved the DECLARED radius: the setting is the player's, and a repair has"
                + " nothing to restore to if a shell can edit it:\n" + damaged,
                declared, (int) readInt(DECLARED, damaged));
        assertEquals("a shrunken emitter was billed less than the field it declared (" + costBefore
                + " -> " + readInt(CYCLE_COST, damaged) + "): being shot at now SAVES energy, which"
                + " is a reward wearing a consequence's clothes:\n" + damaged,
                costBefore, readInt(CYCLE_COST, damaged));

        // The other half of "re-read, never accumulated" — that mending the block gives the field
        // back — is pinned one tier down, in ShieldConditionTest. It cannot be driven here: no shield
        // block has a crafting recipe, and the welder prices a repair out of one, so it answers
        // NO_RECIPE for every block in this subsystem.
    }

    /**
     * Both verdicts are read off a real explosion's Detonate handler ({@code ForceFieldExplosionHandler},
     * through {@code MixinForceFieldExplosionHandlerEvents}), which is where production combines the
     * emitters — each live one takes out of the blast what it protects — and the emitters' lit-ness and
     * radius are read off the same handler call that decided, so neither can drift between the two.
     *
     * <p>red-witnessed, one inversion per line, 2026-09-30: with {@code ForceFieldExplosionHandler.java:41}
     * (each emitter's {@code filterAffectedBlocks}) removed, this fails at "a hole left by a damaged
     * emitter must be closed by the neighbour that still reaches it ... the blast took the block there:
     * {...destroyed:[...,"1234,64,830",...]} | as heard: {...emitters:[{pos:1238,64,830,powered:true,
     * radius:4},{pos:1230,64,830,powered:true,radius:3}]}"; with {@code TileEntityFieldGenerator.java:327}'s
     * {@code protects} measured against the declared radius, it fails at "the far side, which only the
     * damaged emitter ever reached, is still protected from a blast while that emitter is lit and
     * shrunk ... {...destroyed:["1225,64,830"]}" — the emitter at 1230 read powered:true, radius:3 in
     * the same handler call.</p>
     */
    @Test
    public void aNeighbourThatStillReachesClosesTheHoleAndOneThatDoesNotLeavesIt() throws Exception {
        // Two independent single-emitter shields eight blocks apart, so their fields just meet.
        int aGx = 1229, aEx = 1230, bEx = 1238, bGx = 1239;
        clearSite(aGx - 8, bGx + 8);
        place("affs:shield_generator", aGx);
        place("affs:field_generator", aEx);
        place("affs:field_generator", bEx);
        place("affs:shield_generator", bGx);
        powerUp(aGx, aEx);
        powerUp(bGx, bEx);

        int radiusBefore = (int) readInt(RADIUS, readShield(aEx));
        int between = aEx + radiusBefore;          // on A's edge, and inside B's reach
        int outboard = aEx - radiusBefore;         // on A's edge, and nowhere near B
        assertTrue("precondition: the point between the two emitters must start covered:\n"
                + readZone(between), covered(between));
        assertTrue("precondition: the point on A's far side must start covered:\n" + readZone(outboard),
                covered(outboard));

        int radiusAfter = shootUntilFieldShrinks(aEx, radiusBefore);
        assertTrue("precondition: emitter A's field never shrank (" + radiusAfter + " vs "
                + radiusBefore + "), so neither point below was ever uncovered by anything:\n"
                + readShield(aEx), radiusAfter < radiusBefore);
        // BOTH emitters must be lit when the two points are read: a dark A covers its far side no more
        // than a shrunken one does, and a dark B closes no hole. Measured 2026-09-30: after the shots
        // A read powered:false (shieldStored 4000 against a cycleCost of 12060), so the far-side
        // verdict below had been going green on a shield that had merely lost power; and feeding A
        // back up through its generator took long enough for B to go dark in turn. So both coils are
        // filled at once and each emitter's tick is driven, which is what re-reads "powered" — the
        // radius is set by the block's damage, which neither touches.
        requireLit(aEx);
        requireLit(bEx);

        // What PRODUCTION decides, not what a probe adds up: a real explosion at each point, whose
        // Detonate handler asks every live emitter in turn and lets each take out of the blast what
        // it protects. A block of dirt stands at the point, so the blast has something to take. The
        // emitters' state is read off the handler's own record at the moment it decided — lit, and
        // A shrunk — so the verdict and the state it rests on are one reading, not two.
        String[] hole = explodeAt(between);
        requireArranged("the blast at the point between the emitters did not even reach the dirt"
                + " there, so the handler had nothing to decide:\n" + hole[0],
                listed(hole[0], "candidates", between));
        requireEmitterAtTheBlast(hole[0], aEx, radiusBefore, true);
        requireEmitterAtTheBlast(hole[0], bEx, radiusBefore, false);
        assertTrue("a hole left by a damaged emitter must be closed by the neighbour that still"
                + " reaches it — the field is one blended surface, not a set of private bubbles; the"
                + " blast took the block there:\n" + hole[1] + "\nas heard: " + hole[0],
                !listed(hole[1], "destroyed", between));

        String[] far = explodeAt(outboard);
        requireArranged("the blast on A's far side did not even reach the dirt there, so the handler"
                + " had nothing to decide:\n" + far[0], listed(far[0], "candidates", outboard));
        requireEmitterAtTheBlast(far[0], aEx, radiusBefore, true);
        assertTrue("the far side, which only the damaged emitter ever reached, is still protected from"
                + " a blast while that emitter is lit and shrunk: then nothing was actually lost and the"
                + " shrink costs a player nothing:\n" + far[1] + "\nas heard: " + far[0],
                listed(far[1], "destroyed", outboard));
    }

    /**
     * A small real explosion centred in a block of dirt at {@code x}, and the shield's Detonate
     * handler's own account of it, as {@code {heard, decided}}: {@code shield_explosion_heard} is the
     * handler's entry — the blast's candidate blocks and every emitter's state as the handler found
     * it — and {@code shield_explosion_decided} its exit, the blocks the blast goes on to destroy once
     * every emitter has taken what it protects. The explosion runs inside the probe command on the
     * server thread, so both records are in the log when the reply is.
     */
    private String[] explodeAt(int x) throws Exception {
        place("minecraft:dirt", x);
        long mark = events.markInstrumented();
        // Strength 1: enough to take the block it is centred in (dirt resists far less than one
        // ray's weakest start), and too weak to reach an emitter four blocks away.
        Reply.of(exec("stellurgytest shield explode " + DIM + " " + (x + 0.5D) + " " + (Y + 0.5D) + " "
                + (Z + 0.5D) + " 1.0")).requireOk("set off the blast");
        String at = (x + 0.5D) + "," + (Y + 0.5D) + "," + (Z + 0.5D);
        String heard = events.awaitRecordWithFields(mark, "shield_explosion_heard",
                "the shield's explosion handler never heard the blast at " + x, 20, "at", at);
        String decided = events.awaitRecordWithFields(mark, "shield_explosion_decided",
                "the shield's explosion handler never finished deciding the blast at " + x, 20, "at", at);
        return new String[]{heard, decided};
    }

    /** Whether the {@code field} list of an explosion record names the block at {@code x}. */
    private static boolean listed(String decided, String field, int x) {
        com.google.gson.JsonElement list = new com.google.gson.JsonParser().parse(decided)
                .getAsJsonObject().get(field);
        requireArranged("the explosion record carries no `" + field + "` list: " + decided,
                list != null && list.isJsonArray());
        String wanted = x + "," + Y + "," + Z;
        for (com.google.gson.JsonElement pos : list.getAsJsonArray()) {
            if (wanted.equals(pos.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The emitter at {@code ex}, as the handler found it when it decided: lit, and — when
     * {@code shrunk} — projecting less than {@code radiusBefore}. A dark emitter protects nothing and a
     * whole one still reaches its old edge, and either would decide the verdict for the wrong reason.
     */
    private static void requireEmitterAtTheBlast(String decided, int ex, int radiusBefore, boolean shrunk) {
        String wanted = ex + "," + Y + "," + Z;
        for (com.google.gson.JsonElement one : new com.google.gson.JsonParser().parse(decided)
                .getAsJsonObject().getAsJsonArray("emitters")) {
            com.google.gson.JsonObject emitter = one.getAsJsonObject();
            if (!wanted.equals(emitter.get("pos").getAsString())) {
                continue;
            }
            requireArranged("the emitter at " + ex + " was dark when the blast was decided, so the"
                    + " verdict is about its power, not its radius:\n" + decided,
                    emitter.get("powered").getAsBoolean());
            if (shrunk) {
                requireArranged("the emitter at " + ex + " was not shrunk when the blast was decided:\n"
                        + decided, emitter.get("radius").getAsInt() < radiusBefore);
            }
            return;
        }
        requireArranged("the handler did not know the emitter at " + ex + " when it decided:\n" + decided,
                false);
    }

    /**
     * red-witnessed, one inversion per block: {@code TileEntityShieldGenerator.java:159} returning the
     * rated conversion fails this with "a damaged shield generator must convert less than an intact one
     * (4000 vs 4000)"; {@code TileEntityShieldCable.java:109} returning the rated throughput fails it
     * with "a damaged cable must carry less than an intact one (20000 vs 20000)";
     * {@code TileEntityShieldAccumulator.java:141} returning the full capacity fails it with "a damaged
     * accumulator must hold less than an intact one (500000 vs 500000)". 2026-09-30.
     */
    @Test
    public void aDamagedGeneratorCableAndAccumulatorEachDeliverLess() throws Exception {
        int genX = 1260, cableX = 1268, accX = 1276;
        clearSite(genX - 6, accX + 6);
        place("affs:shield_generator", genX);
        place("affs:shield_cable", cableX);
        place("affs:shield_accumulator", accX);

        long conversionBefore = readInt(CONVERSION, readShield(genX));
        long throughputBefore = readInt(THROUGHPUT, readShield(cableX));
        long capacityBefore = readInt(CAPACITY, readShield(accX));

        shootUntilStaged(genX);
        shootUntilStaged(cableX);
        shootUntilStaged(accX);

        long conversionAfter = readInt(CONVERSION, readShield(genX));
        long throughputAfter = readInt(THROUGHPUT, readShield(cableX));
        long capacityAfter = readInt(CAPACITY, readShield(accX));

        assertTrue("a damaged shield generator must convert less than an intact one ("
                + conversionAfter + " vs " + conversionBefore + "):\n" + readShield(genX),
                conversionAfter < conversionBefore);
        assertTrue("a damaged cable must carry less than an intact one (" + throughputAfter + " vs "
                + throughputBefore + "):\n" + readShield(cableX), throughputAfter < throughputBefore);
        assertTrue("a damaged accumulator must hold less than an intact one (" + capacityAfter
                + " vs " + capacityBefore + "):\n" + readShield(accX), capacityAfter < capacityBefore);
    }

    // ---- driving the world

    /**
     * Shoot the emitter's own block until the field it projects draws in, and answer with the radius
     * it settled at. Bounded: an emitter that never shrinks fails on the caller's assertion with the
     * numbers in hand rather than hanging here.
     */
    private int shootUntilFieldShrinks(int ex, int radiusBefore) throws Exception {
        // STIMULUS: every iteration IS an impact plus the feed-and-tick that lets the emitter re-read
        // its condition — all driven by the probe on the server thread, so the read that opens the
        // next iteration sees what the last one did. Bounded by shot count, never by wall clock.
        for (int shot = 0; shot < 12; shot++) {
            int radius = (int) readInt(RADIUS, readShield(ex));
            if (radius < radiusBefore) {
                return radius;
            }
            // Stop one rung short of destruction and let the CALLER's claim fail with the numbers in
            // hand. Shooting on until the block is gone would replace "the field never shrank" with
            // "there is no emitter", which is a different sentence and a worse one to read.
            String damage = readStage(ex);
            // the producer always writes `stage` and `maxStage` on a `damage stage` reply for a
            // standing block, so refusing on a missing one is the right failure.
            if (readInt(STAGE, damage) >= readInt("maxStage", damage) - 1) {
                break;
            }
            hit(ex);
            // The emitter re-reads its own condition on its tick, and a lit field costs energy to
            // hold, so keep feeding it: a dark shield covers nothing for reasons unrelated to damage.
            // The FEED is the point — an emitter left to drain goes dark on a slow loop, and this
            // test then fails about power while claiming to be about radius (seen under parallel load
            // 2026-08-17, green serially).
            exec("stellurgytest energy inject " + DIM + " " + (ex - 1) + " " + Y + " " + Z + " 8000");
            exec("stellurgytest tile force-tick " + DIM + " " + (ex - 1) + " " + Y + " " + Z + " 1");
            exec("stellurgytest tile force-tick " + DIM + " " + ex + " " + Y + " " + Z + " 2");
            exec("stellurgytest shield tick " + DIM);
        }
        return (int) readInt(RADIUS, readShield(ex));
    }

    /** Shoot a block until the world records a stage against it. */
    private void shootUntilStaged(int x) throws Exception {
        // STIMULUS: each iteration is one synchronous impact; the stage it wrote is read at once.
        for (int shot = 0; shot < 12; shot++) {
            // the producer always writes `stage` on a `damage stage` reply, so refusing on a missing
            // one is the right failure.
            if (readInt(STAGE, readStage(x)) > 0) {
                return;
            }
            hit(x);
        }
        assertTrue("the block at " + x + " never took a stage, so nothing below is about damage:\n"
                + readStage(x), readInt(STAGE, readStage(x)) > 0);
    }

    /** This method's next impact identity; 0 until its first impact. */
    private long impactIdCursor;

    /**
     * An impact identity no other method on this shared server has spent. The service refuses a
     * repeat and answers DUPLICATE_IMPACT, which silently ends a scenario, and its memory is the
     * server's — so a per-method counter starting at a fixed number would shoot the ids the previous
     * method already spent. Each method's ids start at the server tick of its first impact, times a
     * thousand: methods run one after another, so their ranges are disjoint while none fires a
     * thousand impacts.
     */
    private long nextImpactId() throws Exception {
        if (impactIdCursor == 0) {
            impactIdCursor = serverTick() * 1000L;
        }
        return impactIdCursor++;
    }

    /**
     * One declared impact against the block at {@code x}, from the -Z side at its own height: that
     * block is the first solid thing the ray meets, and what is behind it is cleared air, so no
     * neighbour is quietly damaged by the leftover budget.
     */
    private void hit(int x) throws Exception {
        int budget = (int) Math.ceil(readInt(STAGE_COST, readStage(x)) * STAGES_PER_IMPACT);
        String resp = exec("stellurgytest damage impact " + DIM + " " + (x + 0.5D) + " " + (Y + 0.5D) + " "
                + (Z - 2.5D) + " 0 0 1 " + budget + " KINETIC " + nextImpactId());
        Reply.of(resp).requireOk("declare the impact");
        assertTrue("the impact spent nothing — it is not reaching the block, and every assertion"
                + " after this would be about an undamaged one: " + resp,
                readInt("spent", resp) > 0);
        assertTrue("the block was destroyed rather than damaged, so there is nothing left to degrade: "
                + readStage(x), !Reply.of(readStage(x)).bool("wasDestroyed"));
    }

    /** Feed the generator until its emitter lights up. */
    private void powerUp(int gx, int ex) throws Exception {
        // STIMULUS: each iteration is a feed plus one probe-driven solve pass.
        for (int i = 0; i < 16 && !Reply.of(readShield(ex)).bool("powered"); i++) {
            exec("stellurgytest energy inject " + DIM + " " + gx + " " + Y + " " + Z + " 4000");
            exec("stellurgytest tile force-tick " + DIM + " " + gx + " " + Y + " " + Z + " 1");
            exec("stellurgytest shield tick " + DIM);
        }
        assertTrue("precondition: the shield at " + ex + " never powered up:\n" + readShield(ex),
                Reply.of(readShield(ex)).bool("powered"));
    }

    /**
     * Fill the emitter at {@code ex}'s coil to its maximum and drive its tick, then require it lit. A
     * full coil holds a tier-0 field for tens of ticks, which covers the reads that follow.
     */
    private void requireLit(int ex) throws Exception {
        long max = readInt("shieldMax", readShield(ex));
        Reply.of(exec("stellurgytest shield charge " + DIM + " " + ex + " " + Y + " " + Z + " " + max))
                .requireOk("fill the emitter's coil");
        Reply.of(exec("stellurgytest tile force-tick " + DIM + " " + ex + " " + Y + " " + Z + " 1"))
                .requireOk("tick the emitter so it re-reads whether it is lit");
        String now = readShield(ex);
        requireArranged("the emitter at " + ex + " is dark with a full coil, so no coverage reading"
                + " below would be about its radius:\n" + now, Reply.of(now).bool("powered"));
    }

    private void clearSite(int minX, int maxX) throws Exception {
        Reply.of(exec("stellurgytest chunk warmup " + DIM + " " + (minX >> 4) + " " + ((Z - 8) >> 4) + " "
                + (maxX >> 4) + " " + ((Z + 8) >> 4))).requireOk("warm the site's chunks");
        // A lid first, then the clearing under it: the site lies under sand, and a cleared column with
        // sand above it fills again before the first shot (measured 2026-10-03: a sand block at
        // 1268,64,828, two blocks in front of the cable, stopped every round aimed at it). Until
        // kinetic rounds too poor for a stage were stopped by what they met, they passed through
        // that sand unseen.
        Reply.of(exec("stellurgytest fill " + DIM + " " + minX + " " + (Y + 7) + " " + (Z - 6) + " " + maxX
                + " " + (Y + 7) + " " + (Z + 6) + " minecraft:stone")).requireOk("roof the site");
        Reply.of(exec("stellurgytest fill " + DIM + " " + minX + " " + (Y - 2) + " " + (Z - 6) + " " + maxX
                + " " + (Y + 6) + " " + (Z + 6) + " minecraft:air")).requireOk("clear the site");
    }

    // ---- reading the world

    /** Whether ANY live emitter still holds the block at {@code x} — the emitter's own predicate. */
    private boolean covered(int x) throws Exception {
        return Reply.of(readZone(x)).bool("covered");
    }

    private String readZone(int x) throws Exception {
        return exec("stellurgytest shield zone " + DIM + " " + x + " " + Y + " " + Z);
    }

    private String readShield(int x) throws Exception {
        return exec("stellurgytest shield read " + DIM + " " + x + " " + Y + " " + Z);
    }

    private String readStage(int x) throws Exception {
        return exec("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private void place(String block, int x) throws Exception {
        String resp = exec("stellurgytest place " + DIM + " " + x + " " + Y + " " + Z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + Z + ": " + resp,
                Reply.of(resp).bool("placed"));
    }

    private static long readInt(String field, String json) {
        return Reply.of(json).longInteger(field);
    }


    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
