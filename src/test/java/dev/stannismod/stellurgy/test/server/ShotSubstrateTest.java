package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

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

    @RedWitness(verdict = "loads no world", member = "StructureCrossing#worldFrameHit",
            at = "if (maxY < 0.0D || minY > world.getHeight())",
            replacedBy = "int n = (int) Math.ceil(length / 8.0D) + 1; for (int i = 0; i <= n; i++) {"
                    + " double t = (double) i / n; world.getChunkFromBlockCoords(new net.minecraft.util.math.BlockPos("
                    + "from.x + (to.x - from.x) * t, 64.0D, from.z + (to.z - from.z) * t)); }"
                    + " if (maxY < 0.0D || minY > world.getHeight())",
            fails = "the flight loaded the world under it at chunk", on = "2026-10-18")
    @RedWitness(verdict = "moves by its velocity", member = "ShotSubstrate#step",
            at = "position = segmentEnd;",
            replacedBy = "position = segmentEnd.addVector(1.0D, 0.0D, 0.0D);",
            fails = "a shot must move by its velocity once per tick of its own age", on = "2026-10-18")
    @RedWitness(verdict = "not deflected, Y", member = "ShotSubstrate#step",
            at = "position = segmentEnd;",
            replacedBy = "position = segmentEnd.addVector(0.0D, 0.001D, 0.0D);",
            fails = "nothing acts on it out there, so it must not have been deflected", on = "2026-10-18")
    @RedWitness(verdict = "not deflected, Z", member = "ShotSubstrate#step",
            at = "position = segmentEnd;",
            replacedBy = "position = segmentEnd.addVector(0.0D, 0.0D, 0.001D);",
            fails = "expected:<40000.5> but was:<40000.52", on = "2026-10-18")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int tick = 0; tick < 10; tick++) {",
            why = "ten steps of the substrate, each one the dose; nothing is awaited.")
    public void aShotCrossesEmptySpaceWithoutLoadingAnyWorld() throws Exception {
        arrange("stellurgytest shot clear 0").requireOk("clear the air");
        double originX = 40_000.5D;
        double originZ = 40_000.5D;
        double perTick = 50.0D;

        long id = fire(originX + " " + POSE_BAND_Y + " " + originZ + " " + perTick + " 0 0 4000 400");

        for (int tick = 0; tick < 10; tick++) {
            act("stellurgytest shield tick 0").requireOk("step the substrate");
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
                + read("stellurgytest chunk loaded 0 0 0"),
                read("stellurgytest chunk loaded 0 0 0").bool("loaded"));
        int chunkZ = (int) Math.floor(originZ) >> 4;
        for (int blocksAlong = 0; blocksAlong <= 480; blocksAlong += 160) {
            int chunkX = (int) Math.floor(originX + blocksAlong) >> 4;
            Reply loaded = read("stellurgytest chunk loaded 0 " + chunkX + " " + chunkZ);
            assertTrue("the flight loaded the world under it at chunk " + chunkX + "," + chunkZ
                    + ": " + loaded + ". A shot that pulls a corridor of chunks along with it is an"
                    + " attack on the host, which is the whole reason it is not an entity",
                    !loaded.bool("loaded"));
        }
    }

    @RedWitness(verdict = "says so", member = "ShotSubstrate#step",
            at = "return ShotEndReason.EXPIRED;",
            replacedBy = "return ShotEndReason.STRUCTURE_IMPACT;",
            fails = "a shot that timed out must say so", on = "2026-10-18")
    @RedWitness(verdict = "ends at its lifetime", member = "ShotSubstrate#step",
            at = "if (shot.getAge() > shot.getLifetimeTicks())",
            replacedBy = "if (shot.getAge() > shot.getLifetimeTicks() + 10)",
            fails = "a shot with a three-tick lifetime was still up after five", on = "2026-10-18")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int tick = 0; tick < 5; tick++) {",
            why = "five steps against a three-tick lifetime.")
    public void aShotEndsForAStatedReasonRatherThanJustDisappearing() throws Exception {
        arrange("stellurgytest shot clear 0").requireOk("clear the air");
        long id = fire(41_000.5D + " " + POSE_BAND_Y + " " + 41_000.5D + " 10 0 0 4000 3");

        for (int tick = 0; tick < 5; tick++) {
            act("stellurgytest shield tick 0").requireOk("step the substrate");
        }

        Reply gone = read(id);
        assertTrue("a shot with a three-tick lifetime was still up after five: " + gone,
                !gone.bool("present"));
        assertEquals("a shot that timed out must say so — a weapon that cannot tell a miss from a hit"
                + " cannot report either: " + gone, "EXPIRED", gone.text("ended"));
    }

    @RedWitness(verdict = "stopped by the wall", member = "ShotSubstrate#step",
            at = "boolean structureFirst = first.isStructure();",
            replacedBy = "boolean structureFirst = false;",
            fails = "the shot flew straight through a solid wall", on = "2026-10-18")
    @Test
    public void aFastShotCannotPassThroughAOneBlockWall() throws Exception {
        arrange("stellurgytest shot clear 0").requireOk("clear the air");
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        int wallX = X + 40;
        buildWall(wallX);

        int stageCost = read("stellurgytest damage stage 0 " + wallX + " " + Y + " " + Z)
                .requireOk("price the wall").integer("stageCost");
        requireArranged("no stage cost for the wall, so nothing here could show damage", stageCost > 0);

        // 60 blocks a tick against a wall one block thick: a per-tick position test looks before the
        // wall and then well past it, and sees stone at neither.
        long id = fire((X + 0.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D) + " 60 0 0 " + stageCost + " 40");
        act("stellurgytest shield tick 0").requireOk("step the substrate");

        Reply after = read(id);
        assertTrue("the shot flew straight through a solid wall — the step is longer than the wall is"
                + " thick, which is exactly the case a swept segment exists for: " + after,
                !after.bool("present"));
        assertEquals("it stopped, but not by hitting anything: " + after, "STRUCTURE_IMPACT",
                after.text("ended"));

        Reply wall = read("stellurgytest damage stage 0 " + wallX + " " + Y + " " + Z).requireOk("read the wall");
        assertTrue("the shot stopped at the wall but the wall took nothing: " + wall,
                wall.integer("stage") > 0 || wall.bool("wasDestroyed"));
    }

    @RedWitness(verdict = "not in another world", member = "ShotRegistry#get",
            at = "MapStorage storage = world.getPerWorldStorage();",
            replacedBy = "MapStorage storage = net.minecraftforge.common.DimensionManager.getWorld(0).getPerWorldStorage();",
            fails = "a shot fired in one world turned up in another", on = "2026-10-18")
    @RedWitness(verdict = "not stepped by another world", member = "ShotSubstrate#tick",
            at = "ShotRegistry registry = ShotRegistry.get(world);",
            replacedBy = "ShotRegistry registry = ShotRegistry.get(world); if (world.provider.getDimension() != 0) {"
                    + " ShotRegistry home = ShotRegistry.get(net.minecraftforge.common.DimensionManager.getWorld(0));"
                    + " for (Shot other : home.snapshot()) { home.end(other.getId(), ShotEndReason.EXPIRED,"
                    + " other.getPosition()); } }",
            fails = "ticking the other world stepped this world's shot", on = "2026-10-18")
    @Test
    public void aShotIsOnlyEverInTheWorldItWasFiredIn() throws Exception {
        // The isolation is structural — one registry per world, with no reference between them — so
        // what is worth pinning is that firing into one world leaves the other's count alone.
        arrange("stellurgytest chunk forceload -1 0 0").requireOk("bring the second dimension up");
        arrange("stellurgytest shot clear 0").requireOk("clear the overworld's air");
        arrange("stellurgytest shot clear -1").requireOk("clear the nether's air");

        long id = fire(42_000.5D + " " + POSE_BAND_Y + " " + 42_000.5D + " 20 0 0 4000 200");

        Reply here = read("stellurgytest shot list 0").requireOk("list the overworld's rounds");
        assertEquals("the shot was fired into the overworld and is not there: " + here, 1,
                here.integer("count"));
        Reply there = read("stellurgytest shot list -1").requireOk("list the nether's rounds");
        assertEquals("a shot fired in one world turned up in another: " + there, 0, there.integer("count"));

        act("stellurgytest shield tick -1").requireOk("step the other world");
        Reply stillUp = read(id);
        assertTrue("ticking the other world stepped this world's shot: " + stillUp, stillUp.bool("present"));

        arrange("stellurgytest shot clear 0").requireOk("clear the air");
        arrange("stellurgytest chunk release -1 0 0").requireOk("release the nether");
    }

    @RedWitness(verdict = "mirrored", member = "ShotSubstrate#step",
            at = "if (result.isReflected())",
            replacedBy = "if (result.isReflected() && shot == null)",
            fails = "a shell that could afford the round consumed it instead of mirroring it", on = "2026-10-18")
    @Test
    public void aShotThatMeetsAChargedShellBouncesOffItAndStaysUp() throws Exception {
        // The one place the substrate calls the shield and reads a velocity back. The shell owns the
        // reflection law — this pins that the answer is USED: the round turns around, resumes from
        // the crossing and is still in the air, rather than stopping at the shield or ploughing on.
        arrange("stellurgytest shot clear 0").requireOk("clear the air");
        int gx = 1040, gz = 880, gy = 96;
        int ex = gx + 1;
        chargedShield(gx, gy, gz);

        // Fired from outside the +Z shell straight inward, at a speed that reaches it this tick.
        double cz = gz + 0.5D;
        double startZ = cz + SHELL_RADIUS + 3.0D;
        long id = fire((ex + 0.5D) + " " + (gy + 0.5D) + " " + startZ + " 0 0 -4 2000 300");
        act("stellurgytest shield tick 0").requireOk("step the substrate");

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

        arrange("stellurgytest shot clear 0").requireOk("clear the air");
    }

    @LegacyWitness(prose = "the property is held by TWO lines. {@code ShotSubstrate#strikeFor} at {@code carriesMass(kind) ? velocity : null} declaring a body"
            + " for every kind left this green on its own, because {@code ShieldStrikeService#absorb} at {@code if (strike.getKind() == ShieldStrikeKind.KINETIC && strike.hasBody())} still"
            + " refused to reflect a non-kinetic strike; with that kind check removed as well it"
            + " fails with \"a beam came back off the shell ... {...present:true,vz:4.0,kind:BEAM...}\"."
            + " 2026-09-30.")
    @Test
    public void aBeamIsAbsorbedByAChargedShellRatherThanMirroredOffIt() throws Exception {
        // The same shell, the same energy, the same approach as the bounce above — only the KIND
        // differs. What the shell does with a strike is decided by what it was told the strike is.
        arrange("stellurgytest shot clear 0").requireOk("clear the air");
        int gx = 1040, gz = 912, gy = 96;
        int ex = gx + 1;
        chargedShield(gx, gy, gz);

        double cz = gz + 0.5D;
        double startZ = cz + SHELL_RADIUS + 3.0D;
        long id = fire((ex + 0.5D) + " " + (gy + 0.5D) + " " + startZ + " 0 0 -4 2000 300 BEAM");
        act("stellurgytest shield tick 0").requireOk("step the substrate");

        Reply after = read(id);
        assertTrue("a beam came back off the shell: the substrate declared it as a travelling body,"
                + " and a shell mirrors a body it can afford: " + after, !after.bool("present"));
        assertEquals("the beam ended, but not by being drunk by the shell — a shot that stops for the"
                + " wrong stated reason is a weapon that cannot report what happened: " + after,
                "FIELD_ABSORBED", after.text("ended"));

        arrange("stellurgytest shot clear 0").requireOk("clear the air");
    }

    // ---- arrangement

    /** A shield generator and emitter charged until the emitter reports itself powered. */
    @Wait(shape = Wait.Shape.STIMULUS, at = "for (int i = 0; i < 15; i++) {",
            why = "fifteen feed-and-solve passes, the dose the shell is charged with; the verdict on whether it"
                    + " took is the emitter's own reading below.")
    private void chargedShield(int gx, int gy, int gz) throws Exception {
        arrange("stellurgytest chunk warmup 0 " + ((gx - 16) >> 4) + " " + ((gz - 16) >> 4) + " "
                + ((gx + 16) >> 4) + " " + ((gz + 16) >> 4)).requireOk("warm the site's chunks");
        arrange("stellurgytest fill 0 " + (gx - 12) + " " + (gy - 4) + " " + (gz - 12) + " " + (gx + 12) + " "
                + (gy + 8) + " " + (gz + 12) + " minecraft:air").requireOk("clear the site");
        place("affs:shield_generator", gx, gy, gz);
        place("affs:field_generator", gx + 1, gy, gz);
        for (int i = 0; i < 15; i++) {
            arrange("stellurgytest energy inject 0 " + gx + " " + gy + " " + gz + " 4000").requireOk("feed the generator");
            act("stellurgytest tile force-tick 0 " + gx + " " + gy + " " + gz + " 1").requireOk("tick the generator");
            act("stellurgytest shield tick 0").requireOk("solve the network");
        }
        Reply emitter = read("stellurgytest shield read 0 " + (gx + 1) + " " + gy + " " + gz);
        requireArranged("the emitter never powered, so there is no shell here: " + emitter,
                emitter.bool("powered"));
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = arrange("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + placed,
                placed.bool("placed"));
    }

    /** A wall one block thick, three by three, with clear air on both sides of it. */
    private void buildWall(int wallX) throws Exception {
        arrange("stellurgytest chunk warmup 0 " + ((X - 4) >> 4) + " " + ((Z - 4) >> 4) + " "
                + ((wallX + 8) >> 4) + " " + ((Z + 4) >> 4)).requireOk("warm the range's chunks");
        arrange("stellurgytest fill 0 " + (X - 2) + " " + (Y - 2) + " " + (Z - 2) + " " + (wallX + 8) + " "
                + (Y + 3) + " " + (Z + 2) + " minecraft:air").requireOk("clear the range");
        arrange("stellurgytest fill 0 " + wallX + " " + (Y - 1) + " " + (Z - 1) + " " + wallX + " " + (Y + 1)
                + " " + (Z + 1) + " minecraft:stone").requireOk("build the wall");
    }

    /** Launch one round; its id, or a failed arrangement. */
    private long fire(String spec) throws Exception {
        Reply fired = act("stellurgytest shot fire 0 " + spec).requireOk("fire a round");
        long id = fired.longInteger("id");
        requireArranged("the launch was refused, so nothing else here means anything: " + fired, id > 0);
        return id;
    }

    /** The round as the substrate holds it — {@code present:false} with {@code ended} once it is over. */
    private Reply read(long id) throws Exception {
        return read("stellurgytest shot read 0 " + id).requireOk("read the round");
    }

    /** The round, which must still be in the air. */
    private Reply shot(long id) throws Exception {
        Reply read = read(id);
        assertTrue("the shot ended in mid-flight across empty space: " + read, read.bool("present"));
        return Reply.of("the round", read.object("shot"));
    }
}
