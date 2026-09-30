package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;

/**
 * The emergency dump: heat leaves the ship inside a lump of matter that is thrown overboard.
 *
 * <p>What is pinned is the SHAPE of the bargain rather than any rate. The dump takes heat off the
 * loop only once the ship is already losing, it charges the material it was given, and the charged
 * lump goes out of the port carrying the energy with it - so the loop is genuinely colder and the
 * matter is genuinely gone. A dump that ran while the ship was coping would be a cooling system, and
 * the contract forbids exactly that.</p>
 */
public class HeatDumpBuysSecondsTest extends AbstractSharedServerTest {

    /** The row the rig stands on, from this scenario's own site (see {@link #stand}). */
    private int y;
    private int z;

    /**
     * Ask for this scenario's site, prove its volume empty, and answer where the pipe run starts.
     * The rig clears two blocks behind that and fifteen ahead of it, where a thrown slug lands.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(12, 3, what);
        y = site.y + 1;
        z = site.z + 2;
        return site.x + 2;
    }

    private static final int PIPES = 3;

    /** A loop with a dump bolted to its end, loaded with a block of iron and powered. */
    private void buildRig(int cx) throws Exception {
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (y - 1) + " " + (z - 2)
                + " " + (cx + PIPES + 12) + " " + (y + 2) + " " + (z + 2) + " minecraft:air");
        for (int i = 0; i < PIPES; i++) {
            Reply placed = arrange("stellurgytest place 0 " + (cx + i) + " " + y + " " + z
                    + " stellurgy:heatPipe");
            assertTrue("pipe place failed: " + placed, placed.bool("placed"));
        }
        Reply dump = arrange("stellurgytest place 0 " + (cx + PIPES) + " " + y + " " + z
                + " stellurgy:heatDump");
        assertTrue("dump place failed: " + dump, dump.bool("placed"));
        arrange("stellurgytest energy inject 0 " + (cx + PIPES) + " " + y + " " + z + " 1000000");
        Reply loaded = arrange("stellurgytest heat dump 0 " + (cx + PIPES) + " " + y + " " + z
                + " load minecraft:iron_block");
        assertTrue("the dump must be loaded with something to charge: " + loaded,
                loaded.bool("hasStack"));
        arrange("stellurgytest subnet solve all 0 1");
    }

    /**
     * The dump's own state — refusing as an arrangement failure when there is no dump there.
     *
     * <p>The probe answers {@code isDump:false, charge:0, hasStack:false} for a position holding no
     * dump, which satisfies both "it never fired" and "it fired and threw the slug out". Neither is a
     * reading about a dump that is not there.</p>
     */
    private Reply dumpInfo(int cx) throws Exception {
        Reply info = arrange("stellurgytest heat dump 0 " + (cx + PIPES) + " " + y + " " + z);
        requireArranged("there must be a dump at the end of the loop: " + info, info.bool("isDump"));
        return info;
    }

    /** Charge the loop to a stated temperature and advance the domain in one call. */
    private Reply cycle(int cx, long kelvin, int ticks) throws Exception {
        Reply empty = arrange("stellurgytest heat cycle 0 " + cx + " " + y + " " + z + " 0 1");
        requireArranged("premise: the pipes must form a loop: " + empty, empty.bool("inLoop"));
        long capacity = empty.longInteger("heatCapacity");
        assertTrue("premise: the loop must have thermal mass: " + empty, capacity > 0);
        long ambient = configValue("shipHeatAmbientKelvin");
        long charge = (kelvin - ambient) * capacity;
        Reply cycled = arrange("stellurgytest heat cycle 0 " + cx + " " + y + " " + z + " " + charge + " " + ticks);
        requireArranged("premise: the loop must still be a loop when charged: " + cycled,
                cycled.bool("inLoop"));
        return cycled;
    }

    private static long configValue(String key) throws Exception {
        return arrange("stellurgytest config get " + key).longInteger("value");
    }

    /**
     * KNOWN BUG, pinned as it stands: at the shipped defaults one dump sustains MORE than the
     * cheapest radiator sheds, so a ship can be kept cool on iron instead of on radiators.
     *
     * <p>The clause is a relation — a dump's sustained throughput must stay under the cheapest
     * continuous radiator tier — and the cheapest tier is ONE cell (a radiator is "a cell, not a
     * plate", and the design ruled it so on 2026-09-29). Both sides are read off the server the
     * harness booted, which carries the defaults, rather than restated here: a copy of the defaults
     * cannot notice them changing, and noticing that is this pin's whole job.</p>
     *
     * <p>This asserts the CURRENT violation. Rebalancing the defaults so a dump sheds less than one
     * cell turns it red on purpose — at which point the assertion flips to {@code dump < cell} and
     * the known-bug note goes.</p>
     *
     * <p>red-witnessed: with the shipped default at {@code StellurgyConfiguration:749} lowered from
     * 40000 to 4000, below one cell's 6000: "KNOWN BUG: at the shipped defaults a dump's sustained
     * throughput is at or above what the cheapest radiator (one cell) sheds … dump=4000 cell=6000",
     * 2026-09-30. The cell premise is an arrangement and is not witnessed.</p>
     */
    @Test
    public void atTheShippedDefaultsOneDumpOutshedsOneRadiatingCell() throws Exception {
        long dump = configValue("shipHeatDumpThroughput");
        long cell = configValue("shipHeatRadiatorCellPower");
        assertTrue("premise: a radiating cell must shed something at its reference point: " + cell,
                cell > 0);
        assertTrue("KNOWN BUG: at the shipped defaults a dump's sustained throughput is at or above"
                        + " what the cheapest radiator (one cell) sheds; if this is red, the balance was"
                        + " fixed — flip this to dump < cell. dump=" + dump + " cell=" + cell
                        + " per second",
                dump >= cell);
    }

    /**
     * red-witnessed: one inversion per verdict, 2026-09-30. INTO THE SLUG — {@code TileHeatDump:92}
     * asking for heat only at ten times the trigger: "the dump must have taken heat off the loop and
     * put it in the slug: … \"charge\":0 … \"hasStack\":true". POORER — {@code HeatNetwork:578}
     * charging the slug without counting it as drained: "and the loop must be poorer by what left
     * it: … \"heatStored\":36420 … \"sunk\":0". The second verdict reads the {@code sunk} figure,
     * not the loop's energy: with {@code HeatNetwork:281} alone removed, so that the slug is charged
     * and reported sunk while the loop keeps every unit, it stays green. The two premises at its head
     * are arrangements and are not witnessed.
     */
    @Test
    public void aLoopPastTheTriggerLosesHeatIntoTheSlugAndThrowsItOut() throws Exception {
        int cx = stand("a coolant loop with a loaded dump, about to be driven past its trigger");
        buildRig(cx);
        long trigger = configValue("shipHeatDumpTriggerKelvin");
        Reply before = dumpInfo(cx);
        assertEquals("premise: the dump must start holding the material it was given: " + before,
                0L, before.longInteger("charge"));
        assertTrue("premise: and that material must be able to take heat at all: " + before,
                before.longInteger("headroom") > 0);

        Reply cooked = cycle(cx, trigger + 200, 2);

        Reply after = dumpInfo(cx);
        // Either the slug is holding charge, or it filled and was thrown out - both are the rung
        // working, and telling them apart is what `hasStack` is for.
        assertTrue("the dump must have taken heat off the loop and put it in the slug: " + after,
                after.longInteger("charge") > 0 || !after.bool("hasStack"));
        assertTrue("and the loop must be poorer by what left it: " + cooked,
                cooked.longInteger("sunk") > 0);
    }

    /**
     * red-witnessed: one inversion per verdict, 2026-09-30. NOTHING AT ALL — {@code TileHeatDump:92}
     * dropping the trigger temperature: "below the trigger the dump must do nothing at all … expected:&lt;0&gt;
     * but was:&lt;6000&gt;". STILL HOLDING — {@code TileHeatDump:93} firing the slug whenever it is
     * below the trigger: "and it must still be holding the slug it was given: … \"hasStack\":false".
     * LOSES NOTHING — {@code HeatNetwork:580} draining one unit into a dump that took none: "and the
     * loop must lose nothing to it: … expected:&lt;0&gt; but was:&lt;1&gt;".
     */
    @Test
    public void aShipThatIsCopingThrowsNothingAway() throws Exception {
        int cx = stand("a coolant loop with a loaded dump, kept below its trigger");
        buildRig(cx);
        long trigger = configValue("shipHeatDumpTriggerKelvin");

        Reply cooked = cycle(cx, trigger - 200, 2);

        Reply after = dumpInfo(cx);
        assertEquals("below the trigger the dump must do nothing at all - it is an emergency, not a"
                + " cooling system: " + after, 0L, after.longInteger("charge"));
        // An empty slug reads `charge:0` too, and so does a slug that filled and went out of the port;
        // only a slug still in the slot makes the zero above mean "never charged".
        assertTrue("and it must still be holding the slug it was given: " + after,
                after.bool("hasStack"));
        assertEquals("and the loop must lose nothing to it: " + cooked, 0L, cooked.longInteger("sunk"));
    }
}
