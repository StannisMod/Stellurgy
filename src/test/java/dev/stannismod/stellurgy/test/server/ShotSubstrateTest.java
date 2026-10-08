package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a shot IS between muzzle and impact: a record that crosses distance the world is not loaded
 * for, and that cannot be outrun by a wall.
 *
 * <p>Both properties are about the substrate, not about weapons or damage. The first says a flight
 * costs the host nothing but three vectors — a shot that quietly force-loaded a corridor of chunks
 * would pass every "did it arrive" test ever written and still be the thing this design exists to
 * avoid. The second says the integration is swept: at a step longer than a wall is thick, a
 * position-by-position simulation reports a clean miss through solid stone, and the faster the round
 * the more reliably it lies.</p>
 *
 * <p>Every scenario here STEPS the substrate itself with {@code stellurgytest shield tick}, which posts
 * the real end-of-tick event, and reads the round after the step: the step is the stimulus and its
 * reply is on the server thread, so the read that follows sees what the step did.</p>
 */
public class ShotSubstrateTest extends AbstractSharedServerTest {

    /**
     * The band ships fly in — millions of blocks up, where a cell's contents live and the world has no
     * blocks of its own. Firing here is what makes "nothing was loaded" a statement about the
     * substrate rather than about an empty patch of overworld somebody might later build in.
     */
    private static final double POSE_BAND_Y = 2_000_064.5D;

    /** A site of this class's own, clear of the other server scenarios. */
    private static final int X = 9200, Y = 80, Z = 9200;

    /** The emitter's mid-shell radius, as the shield tests use it — the face a round bounces off. */
    private static final double SHELL_RADIUS = 4.0D;

    /**
     * red-witnessed: with a loop inserted at {@code StructureCrossing#worldFrameHit} at {@code if (maxY < 0.0D || minY > world.getHeight())} (before the build-height
     * skip) loading every chunk along each step's segment, this fails with "the flight loaded the world
     * under it at chunk 2500,2500: {...loaded:true,count:756}". A first attempt that loaded only the
     * chunk at each step's END stayed green — it never touched the four chunks this test samples.
     * The motion verdicts were not separately witnessed. 2026-09-30.
     *
     * <p>red-witnessed, one inversion per verdict, each at {@code ShotSubstrate#step} at {@code position = segmentEnd;} (the open-air
     * step's {@code position = segmentEnd}), 2026-09-30: with a block added along X per step, this fails
     * at "a shot must move by its velocity once per tick of its own age ... expected:&lt;41050.5&gt; but
     * was:&lt;41071.5&gt;"; with 0.001 added along Y, at "nothing acts on it out there, so it must not
     * have been deflected ... but was:&lt;2000064.5209999986&gt;"; with 0.001 along Z, at "expected:&lt;
     * 40000.5&gt; but was:&lt;40000.52099999993&gt;".</p>
     */
    @Test
    public void aShotCrossesEmptySpaceWithoutLoadingAnyWorld() throws Exception {
        ask("stellurgytest shot clear 0").requireOk("clear the air");
        double originX = 40_000.5D;
        double originZ = 40_000.5D;
        double perTick = 50.0D;

        long id = fire(originX + " " + POSE_BAND_Y + " " + originZ + " " + perTick + " 0 0 4000 400");

        // STIMULUS: ten steps of the substrate, each one the dose; nothing is awaited.
        for (int tick = 0; tick < 10; tick++) {
            ask("stellurgytest shield tick 0").requireOk("step the substrate");
        }

        Reply flying = shot(id);
        // Against its OWN age, not against a tick count: this server is really running, so the
        // number of steps a shot has taken is not something a test gets to decide. What is pinned is
        // the rate — one velocity per tick of age, whoever did the ticking.
        int age = flying.integer("age");
        assertTrue("the shot never advanced at all: " + flying, age >= 10);
        assertEquals("a shot must move by its velocity once per tick of its own age: " + flying,
                originX + perTick * age, flying.number("x"), 1.0E-6D);
        assertEquals("nothing acts on it out there, so it must not have been deflected: " + flying,
                POSE_BAND_Y, flying.number("y"), 1.0E-6D);
        assertEquals(originZ, flying.number("z"), 1.0E-6D);

        // The precise claim: it crossed those chunks and none of them came into memory. A count of
        // all loaded chunks would move for reasons that have nothing to do with this shot. The reader
        // is shown able to say "loaded" first, on the spawn chunk the overworld always keeps, so the
        // four "not loaded" answers below are readings and not a verb that only ever says no.
        requireArranged("the chunk reader does not report the overworld's spawn chunk as loaded, so its"
                + " answers below cannot tell a loaded chunk from an unloaded one: "
                + ask("stellurgytest chunk loaded 0 0 0"),
                ask("stellurgytest chunk loaded 0 0 0").bool("loaded"));
        int chunkZ = (int) Math.floor(originZ) >> 4;
        for (int blocksAlong = 0; blocksAlong <= 480; blocksAlong += 160) {
            int chunkX = (int) Math.floor(originX + blocksAlong) >> 4;
            Reply loaded = ask("stellurgytest chunk loaded 0 " + chunkX + " " + chunkZ);
            assertTrue("the flight loaded the world under it at chunk " + chunkX + "," + chunkZ
                    + ": " + loaded + ". A shot that pulls a corridor of chunks along with it is an"
                    + " attack on the host, which is the whole reason it is not an entity",
                    !loaded.bool("loaded"));
        }
    }

    /**
     * red-witnessed: with {@code ShotSubstrate#step} at {@code return ShotEndReason.EXPIRED;}'s expiry reported as STRUCTURE_IMPACT, this
     * fails with "a shot that timed out must say so ... expected:&lt;[EXPIRED]&gt; but
     * was:&lt;[STRUCTURE_IMPACT]&gt;". 2026-09-30.
     *
     * <p>red-witnessed: with {@code ShotSubstrate#step} at {@code if (shot.getAge() > shot.getLifetimeTicks())} letting a round outlive its lifetime by ten
     * steps, this fails at "a shot with a three-tick lifetime was still up after five: {...present:true
     * ...age:11,lifetime:3...}" (2026-09-30).</p>
     */
    @Test
    public void aShotEndsForAStatedReasonRatherThanJustDisappearing() throws Exception {
        ask("stellurgytest shot clear 0").requireOk("clear the air");
        long id = fire(41_000.5D + " " + POSE_BAND_Y + " " + 41_000.5D + " 10 0 0 4000 3");

        // STIMULUS: five steps against a three-tick lifetime.
        for (int tick = 0; tick < 5; tick++) {
            ask("stellurgytest shield tick 0").requireOk("step the substrate");
        }

        Reply gone = read(id);
        assertTrue("a shot with a three-tick lifetime was still up after five: " + gone,
                !gone.bool("present"));
        assertEquals("a shot that timed out must say so — a weapon that cannot tell a miss from a hit"
                + " cannot report either: " + gone, "EXPIRED", gone.text("ended"));
    }

    /**
     * red-witnessed: with {@code ShotSubstrate#step} at {@code boolean structureFirst = first.isStructure();}'s {@code structureFirst} forced false, this
     * fails with "the shot flew straight through a solid wall ... {...present:true,x:9380.5...}".
     * 2026-09-30.
     */
    @Test
    public void aFastShotCannotPassThroughAOneBlockWall() throws Exception {
        ask("stellurgytest shot clear 0").requireOk("clear the air");
        ask("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        int wallX = X + 40;
        buildWall(wallX);

        int stageCost = ask("stellurgytest damage stage 0 " + wallX + " " + Y + " " + Z)
                .requireOk("price the wall").integer("stageCost");
        requireArranged("no stage cost for the wall, so nothing here could show damage", stageCost > 0);

        // 60 blocks a tick against a wall one block thick: a per-tick position test looks before the
        // wall and then well past it, and sees stone at neither.
        long id = fire((X + 0.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D) + " 60 0 0 " + stageCost + " 40");
        ask("stellurgytest shield tick 0").requireOk("step the substrate");

        Reply after = read(id);
        assertTrue("the shot flew straight through a solid wall — the step is longer than the wall is"
                + " thick, which is exactly the case a swept segment exists for: " + after,
                !after.bool("present"));
        assertEquals("it stopped, but not by hitting anything: " + after, "STRUCTURE_IMPACT",
                after.text("ended"));

        Reply wall = ask("stellurgytest damage stage 0 " + wallX + " " + Y + " " + Z).requireOk("read the wall");
        assertTrue("the shot stopped at the wall but the wall took nothing: " + wall,
                wall.integer("stage") > 0 || wall.bool("wasDestroyed"));
    }

    /**
     * red-witnessed: with {@code ShotRegistry#get} at {@code MapStorage storage = world.getPerWorldStorage();} reading the server-wide map storage instead of
     * the world's own, this fails with "a shot fired in one world turned up in another:
     * {...count:1...} expected:&lt;0&gt; but was:&lt;1&gt;". 2026-09-30.
     *
     * <p>red-witnessed: with {@code ShotSubstrate.tick} ({@code ShotSubstrate#tick} at {@code ShotRegistry registry = ShotRegistry.get(world);}) ending the
     * overworld's rounds when the nether is stepped by the probe, this fails at "ticking the other world
     * stepped this world's shot: {...present:false,ended:SUBSTRATE_DISABLED...}" (2026-09-30). That is
     * the only cross-world effect this verdict can see: it reads PRESENCE, so a step that merely moved
     * this world's round from the other world's tick would leave it green.</p>
     */
    @Test
    public void aShotIsOnlyEverInTheWorldItWasFiredIn() throws Exception {
        // The isolation is structural — one registry per world, with no reference between them — so
        // what is worth pinning is that firing into one world leaves the other's count alone.
        ask("stellurgytest chunk forceload -1 0 0").requireOk("bring the second dimension up");
        ask("stellurgytest shot clear 0").requireOk("clear the overworld's air");
        ask("stellurgytest shot clear -1").requireOk("clear the nether's air");

        long id = fire(42_000.5D + " " + POSE_BAND_Y + " " + 42_000.5D + " 20 0 0 4000 200");

        Reply here = ask("stellurgytest shot list 0").requireOk("list the overworld's rounds");
        assertEquals("the shot was fired into the overworld and is not there: " + here, 1,
                here.integer("count"));
        Reply there = ask("stellurgytest shot list -1").requireOk("list the nether's rounds");
        assertEquals("a shot fired in one world turned up in another: " + there, 0, there.integer("count"));

        ask("stellurgytest shield tick -1").requireOk("step the other world");
        Reply stillUp = read(id);
        assertTrue("ticking the other world stepped this world's shot: " + stillUp, stillUp.bool("present"));

        ask("stellurgytest shot clear 0").requireOk("clear the air");
        ask("stellurgytest chunk release -1 0 0").requireOk("release the nether");
    }

    /**
     * red-witnessed: with {@code ShotSubstrate#step} at {@code if (result.isReflected())}'s reflected branch disabled (the shell's
     * answer ignored), this fails with "a shell that could afford the round consumed it instead of
     * mirroring it ... {...present:false,ended:FIELD_ABSORBED...}". The direction and speed verdicts
     * were not separately witnessed. 2026-09-30.
     */
    @Test
    public void aShotThatMeetsAChargedShellBouncesOffItAndStaysUp() throws Exception {
        // The one place the substrate calls the shield and reads a velocity back. The shell owns the
        // reflection law — this pins that the answer is USED: the round turns around, resumes from
        // the crossing and is still in the air, rather than stopping at the shield or ploughing on.
        ask("stellurgytest shot clear 0").requireOk("clear the air");
        int gx = 1040, gz = 880, gy = 96;
        int ex = gx + 1;
        chargedShield(gx, gy, gz);

        // Fired from outside the +Z shell straight inward, at a speed that reaches it this tick.
        double cz = gz + 0.5D;
        double startZ = cz + SHELL_RADIUS + 3.0D;
        long id = fire((ex + 0.5D) + " " + (gy + 0.5D) + " " + startZ + " 0 0 -4 2000 300");
        ask("stellurgytest shield tick 0").requireOk("step the substrate");

        Reply read = read(id);
        assertTrue("a shell that could afford the round consumed it instead of mirroring it — a "
                + "kinetic body declared to the shield must come back out: " + read, read.bool("present"));
        Reply after = Reply.of("the round", read.object("shot"));
        double vz = after.number("vz");
        assertTrue("the round is still travelling inward (vz=" + vz + ", it arrived at -4): the shell's"
                + " answer was read but not applied: " + after, vz > 0.0D);
        double z = after.number("z");
        assertTrue("the round bounced but is still inside the shell (z=" + z + ", shell face at "
                + (cz + SHELL_RADIUS) + "): it resumed on the wrong side of the crossing: " + after,
                z >= cz + SHELL_RADIUS - 1.0D);
        assertTrue("the round left faster than it arrived (vz=" + vz + " vs 4): a mirror returns"
                + " energy, it does not create it: " + after, vz <= 4.0D + 1.0E-6D);

        ask("stellurgytest shot clear 0").requireOk("clear the air");
    }

    /**
     * red-witnessed: the property is held by TWO lines. {@code ShotSubstrate#strikeFor} at {@code carriesMass(kind) ? velocity : null} declaring a body
     * for every kind left this green on its own, because {@code ShieldStrikeService#absorb} at {@code if (strike.getKind() == ShieldStrikeKind.KINETIC && strike.hasBody())} still
     * refused to reflect a non-kinetic strike; with that kind check removed as well it
     * fails with "a beam came back off the shell ... {...present:true,vz:4.0,kind:BEAM...}".
     * 2026-09-30.
     */
    @Test
    public void aBeamIsAbsorbedByAChargedShellRatherThanMirroredOffIt() throws Exception {
        // The same shell, the same energy, the same approach as the bounce above — only the KIND
        // differs. What the shell does with a strike is decided by what it was told the strike is.
        ask("stellurgytest shot clear 0").requireOk("clear the air");
        int gx = 1040, gz = 912, gy = 96;
        int ex = gx + 1;
        chargedShield(gx, gy, gz);

        double cz = gz + 0.5D;
        double startZ = cz + SHELL_RADIUS + 3.0D;
        long id = fire((ex + 0.5D) + " " + (gy + 0.5D) + " " + startZ + " 0 0 -4 2000 300 BEAM");
        ask("stellurgytest shield tick 0").requireOk("step the substrate");

        Reply after = read(id);
        assertTrue("a beam came back off the shell: the substrate declared it as a travelling body,"
                + " and a shell mirrors a body it can afford: " + after, !after.bool("present"));
        assertEquals("the beam ended, but not by being drunk by the shell — a shot that stops for the"
                + " wrong stated reason is a weapon that cannot report what happened: " + after,
                "FIELD_ABSORBED", after.text("ended"));

        ask("stellurgytest shot clear 0").requireOk("clear the air");
    }

    // ---- arrangement

    /** A shield generator and emitter charged until the emitter reports itself powered. */
    private void chargedShield(int gx, int gy, int gz) throws Exception {
        ask("stellurgytest chunk warmup 0 " + ((gx - 16) >> 4) + " " + ((gz - 16) >> 4) + " "
                + ((gx + 16) >> 4) + " " + ((gz + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill 0 " + (gx - 12) + " " + (gy - 4) + " " + (gz - 12) + " " + (gx + 12) + " "
                + (gy + 8) + " " + (gz + 12) + " minecraft:air").requireOk("clear the site");
        place("affs:shield_generator", gx, gy, gz);
        place("affs:field_generator", gx + 1, gy, gz);
        // STIMULUS: fifteen feed-and-solve passes, the dose the shell is charged with; the verdict on
        // whether it took is the emitter's own reading below.
        for (int i = 0; i < 15; i++) {
            ask("stellurgytest energy inject 0 " + gx + " " + gy + " " + gz + " 4000").requireOk("feed the generator");
            ask("stellurgytest tile force-tick 0 " + gx + " " + gy + " " + gz + " 1").requireOk("tick the generator");
            ask("stellurgytest shield tick 0").requireOk("solve the network");
        }
        Reply emitter = ask("stellurgytest shield read 0 " + (gx + 1) + " " + gy + " " + gz);
        requireArranged("the emitter never powered, so there is no shell here: " + emitter,
                emitter.bool("powered"));
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + placed,
                placed.bool("placed"));
    }

    /** A wall one block thick, three by three, with clear air on both sides of it. */
    private void buildWall(int wallX) throws Exception {
        ask("stellurgytest chunk warmup 0 " + ((X - 4) >> 4) + " " + ((Z - 4) >> 4) + " "
                + ((wallX + 8) >> 4) + " " + ((Z + 4) >> 4)).requireOk("warm the range's chunks");
        ask("stellurgytest fill 0 " + (X - 2) + " " + (Y - 2) + " " + (Z - 2) + " " + (wallX + 8) + " "
                + (Y + 3) + " " + (Z + 2) + " minecraft:air").requireOk("clear the range");
        ask("stellurgytest fill 0 " + wallX + " " + (Y - 1) + " " + (Z - 1) + " " + wallX + " " + (Y + 1)
                + " " + (Z + 1) + " minecraft:stone").requireOk("build the wall");
    }

    /** Launch one round; its id, or a failed arrangement. */
    private long fire(String spec) throws Exception {
        Reply fired = ask("stellurgytest shot fire 0 " + spec).requireOk("fire a round");
        long id = fired.longInteger("id");
        requireArranged("the launch was refused, so nothing else here means anything: " + fired, id > 0);
        return id;
    }

    /** The round as the substrate holds it — {@code present:false} with {@code ended} once it is over. */
    private Reply read(long id) throws Exception {
        return ask("stellurgytest shot read 0 " + id).requireOk("read the round");
    }

    /** The round, which must still be in the air. */
    private Reply shot(long id) throws Exception {
        Reply read = read(id);
        assertTrue("the shot ended in mid-flight across empty space: " + read, read.bool("present"));
        return Reply.of("the round", read.object("shot"));
    }
}
