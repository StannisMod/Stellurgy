package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;

/**
 * The failure ladder's last rung: past its material's own limit a block stops being damaged and is
 * gone.
 *
 * <p>Every scenario runs the real thing - a real coolant loop, charged through the same verb the
 * physics tests use, sweeping on the domain's own tick. What is asserted is the RULE (the substance
 * decides, and only past its ceiling) rather than any temperature, and the ceiling is read off the
 * probe rather than named here.</p>
 *
 * <p>The control leg is what makes the subject a measurement: the identical rig with a cooler loop
 * must leave the same block standing. Without it, "the stone is gone" is also what a test that never
 * placed the stone looks like.</p>
 */
public class HullMeltsPastItsMaterialTest extends AbstractSharedServerTest {

    /** The row the pipes stand on, from this scenario's own site (see {@link #stand}). */
    private int y;
    private int z;

    /** Three pipes is enough loop to charge; the block under test stands against the middle one. */
    private static final int PIPES = 3;

    /**
     * Ask for this scenario's site, prove its volume empty, and answer where the pipe run starts.
     * The rig's own air fill reaches two blocks either side of the run and one below it.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(2, 3, what);
        y = site.y + 1;
        z = site.z + 2;
        return site.x + 2;
    }

    /** A run of pipe, with one block of {@code victim} standing against its middle, in clear air. */
    private void buildRig(int cx, String victim) throws Exception {
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (y - 1) + " " + (z - 2)
                + " " + (cx + PIPES + 2) + " " + (y + 2) + " " + (z + 2) + " minecraft:air");
        for (int i = 0; i < PIPES; i++) {
            Reply placed = arrange("stellurgytest place 0 " + (cx + i) + " " + y + " " + z
                    + " stellurgy:heatPipe");
            assertTrue("pipe place failed: " + placed, placed.bool("placed"));
        }
        Reply victimPlaced = arrange("stellurgytest place 0 " + (cx + 1) + " " + (y + 1) + " " + z
                + " " + victim);
        assertTrue("victim place failed: " + victimPlaced, victimPlaced.bool("placed"));
        Reply solved = arrange("stellurgytest subnet solve all 0 1");
        assertEquals("the loop never solved: " + solved, 1, solved.integer("ticksSolved"));
    }

    private Reply blockAbove(int cx) throws Exception {
        return materialAt(cx + 1, y + 1);
    }

    private Reply materialAt(int x, int y) throws Exception {
        return arrange("stellurgytest heat material 0 " + x + " " + y + " " + z);
    }

    /**
     * Charge the loop to a temperature and let the domain tick, with the sweep on every tick.
     *
     * <p>The sweep's pace is a server-wide setting and this server is shared by every scenario in
     * the class, so it is put back as soon as the two ticks that need it have run.</p>
     */
    private void cookAt(int cx, long kelvin) throws Exception {
        String pace = arrange("stellurgytest config get shipHeatMeltCheckTicks").text("value");
        arrange("stellurgytest config set shipHeatMeltCheckTicks 1");
        try {
            cookWithTheSweepOnEveryTick(cx, kelvin);
        } finally {
            arrange("stellurgytest config set shipHeatMeltCheckTicks " + pace);
        }
    }

    private void cookWithTheSweepOnEveryTick(int cx, long kelvin) throws Exception {
        Reply empty = arrange("stellurgytest heat cycle 0 " + cx + " " + y + " " + z + " 0 1");
        requireArranged("premise: the pipes must form a loop: " + empty, empty.bool("inLoop"));
        long capacity = empty.longInteger("heatCapacity");
        assertTrue("premise: the loop must have thermal mass: " + empty, capacity > 0);
        long ambient = arrange("stellurgytest config get shipHeatAmbientKelvin").longInteger("value");
        long charge = (kelvin - ambient) * capacity;
        Reply cooked = arrange("stellurgytest heat cycle 0 " + cx + " " + y + " " + z + " " + charge + " 2");
        // The readback can legitimately find no loop: at a temperature past the PIPES' own material
        // the first swept tick takes them, which is the self-consumption scenario. Only a loop that
        // still exists is required to read the temperature it was charged to. The branch reads a
        // real answer either way: the producer always writes `inLoop` - both of `heat cycle`'s
        // replies carry it, false when the position is in no loop.
        //
        // EXACTLY that temperature. Nothing here takes heat out of this loop: it has no radiating
        // cell (so `rejectHeat` returns 0), stands in open air with no cabin to conduct into, and
        // `HullMelting.sweep` destroys blocks without drawing on the loop. So the stored heat after
        // the ticks is the charge, and `HeatNetwork.temperature` is ambient + stored / capacity —
        // `kelvin` to the milli-kelvin, since the charge is a whole multiple of the capacity.
        if (cooked.bool("inLoop")) {
            assertEquals("premise: the loop must hold exactly the temperature it was charged to: "
                    + cooked, kelvin * 1000L, cooked.longInteger("temperatureMilliK"));
        }
    }

    /**
     * red-witnessed: with {@code HullMelting#meltIfPast} at {@code || actingKelvin < material.ceilingKelvin())} melting nothing until 1000 K past a material's
     * ceiling: "past its own limit the block is not damaged, it is gone - and rock leaves lava
     * behind: … expected:&lt;minecraft:[lava]&gt; but was:&lt;minecraft:[stone]&gt;", 2026-09-30. The
     * two premises at its head are arrangements and are not witnessed.
     */
    @Test
    public void aLoopPastTheMaterialsCeilingTakesTheBlockAndLeavesLava() throws Exception {
        int xMelt = stand("a coolant loop with a block of stone against it, cooked past stone's limit");
        buildRig(xMelt, "minecraft:stone");
        Reply before = blockAbove(xMelt);
        long ceiling = before.longInteger("ceilingKelvin");
        assertTrue("premise: the victim must be a substance with a limit: " + before, ceiling > 0);
        assertEquals("premise: and it must still be stone before anything is cooked: " + before,
                "minecraft:stone", before.text("block"));

        cookAt(xMelt, ceiling + 200);

        Reply after = blockAbove(xMelt);
        assertEquals("past its own limit the block is not damaged, it is gone - and rock leaves lava"
                + " behind: " + after, "minecraft:lava", after.text("block"));
    }

    /**
     * red-witnessed: with {@code HullMelting#meltIfPast} at {@code || actingKelvin < material.ceilingKelvin())} melting from 300 K below a material's ceiling:
     * "below the limit the rung must not fire at all - a block is lost at a temperature, not at a
     * mood: … expected:&lt;minecraft:[stone]&gt; but was:&lt;minecraft:[lava]&gt;", 2026-09-30.
     */
    @Test
    public void theSameRigBelowTheCeilingLeavesTheBlockStanding() throws Exception {
        int xCold = stand("a coolant loop with a block of stone against it, kept below stone's limit");
        buildRig(xCold, "minecraft:stone");
        long ceiling = blockAbove(xCold).longInteger("ceilingKelvin");

        cookAt(xCold, ceiling - 200);

        Reply after = blockAbove(xCold);
        assertEquals("below the limit the rung must not fire at all - a block is lost at a"
                + " temperature, not at a mood: " + after, "minecraft:stone", after.text("block"));
    }

    /**
     * Found by the bedrock scenario rather than planned: past the pipes' OWN material the loop is
     * what melts, and there is nothing left to cook anything else with. The design says an overheated
     * loop eats its own pipes first, and this is that, arrived at from the other direction.
     *
     * <p>Read off the pipe's own position as a BLOCK, because that is what a player finds there: the
     * block that was placed must be gone. The premise reads the same position before the cook, so the
     * block compared against is the one that actually stood there.</p>
     *
     * <p>red-witnessed: with {@code HullMelting#meltIfPast} at {@code || actingKelvin < material.ceilingKelvin())} melting nothing until 1000 K past a material's
     * ceiling: "a loop hotter than its own pipes has no pipes - the pipe that stood there must be gone:
     * … Actual: stellurgy:heatpipe", 2026-09-30. The premise at its head is an arrangement and is not
     * witnessed.</p>
     *
     * <p>Whether the position is still in a network is not asserted: a pipe that stood there and was
     * solved into a loop is in one exactly while it stands, so that reading is this one again.</p>
     */
    @Test
    public void aLoopPastItsOwnMaterialConsumesItself() throws Exception {
        int xSelf = stand("a coolant loop cooked past its own pipes' limit");
        buildRig(xSelf, "minecraft:stone");
        Reply pipe = materialAt(xSelf, y);
        long pipeCeiling = pipe.longInteger("ceilingKelvin");
        assertTrue("premise: the pipes must be made of something with a limit: " + pipe,
                pipeCeiling > 0);
        String pipeBlock = pipe.text("block");

        cookAt(xSelf, pipeCeiling + 500);

        Reply where = materialAt(xSelf, y);
        assertNotEquals("a loop hotter than its own pipes has no pipes - the pipe that stood there must"
                + " be gone: " + where, pipeBlock, where.text("block"));
    }

    /**
     * A block the world will not let a player break is not taken by heat either, whatever it is made
     * of. Bedrock is the case: its vanilla material is rock, so the table knows it as stone and it has
     * a ceiling well under this cook. The only thing standing between it and the melt is the rule that
     * an unbreakable block is never taken — which is what stops the mechanic eating the floor of a
     * dimension.
     *
     * <p>The premise reads that ceiling off the block itself, so the verdict cannot pass on a block the
     * table merely fails to name: that case would never cross a threshold at all, and would say
     * nothing about the rule.</p>
     *
     * <p>red-witnessed: with {@code HullMelting#meltIfPast} at {@code if (block.getBlockHardness(state, world, pos) < 0.0F)} no longer sparing a block the world will not
     * break: "a block the world refuses to let be broken is never taken by heat, even past its own
     * material's ceiling: … expected:&lt;minecraft:[bedrock]&gt; but was:&lt;minecraft:[lava]&gt;",
     * 2026-09-30. The two premises are arrangements and are not witnessed.</p>
     */
    @Test
    public void aBlockTheWorldWillNotLetBeBrokenIsNeverMelted() throws Exception {
        int xBedrock = stand("a coolant loop with a block of bedrock against it");
        buildRig(xBedrock, "minecraft:bedrock");
        Reply before = blockAbove(xBedrock);
        assertEquals("premise: the fixture must actually stand on bedrock: " + before,
                "minecraft:bedrock", before.text("block"));

        // Under what the loop's OWN pipes survive, so the loop is still there to run the sweep.
        long cook = 1600;
        long ceiling = before.longInteger("ceilingKelvin");
        requireArranged("premise: bedrock must have a ceiling under the cook, or nothing here asks the"
                + " unbreakable rule (cook " + cook + " K): " + before, ceiling > 0 && ceiling < cook);

        cookAt(xBedrock, cook);

        Reply after = blockAbove(xBedrock);
        assertEquals("a block the world refuses to let be broken is never taken by heat, even past its"
                + " own material's ceiling: " + after, "minecraft:bedrock", after.text("block"));
    }
}
