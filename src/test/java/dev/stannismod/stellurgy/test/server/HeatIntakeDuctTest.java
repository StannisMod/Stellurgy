package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * The air-intake duct: the block that finally connects the part of a ship people live in to the part
 * that throws heat overboard.
 *
 * <p>A chiller alone talks only to coolant. Bolt a duct to its cold face and it breathes a room
 * instead — taking heat out of that room's air, paying electricity, and delivering both into the hot
 * loop where the radiators are. So the two assertions are a pair: the room must actually get colder,
 * and the loop must receive exactly what left the room PLUS the work that moved it. Either one alone
 * would pass on a machine that invented energy or on one that quietly destroyed it.</p>
 *
 * <p>An unpowered chiller is the control, and it is the honest one: it is the same rig in the same
 * place, differing only in the thing under test.</p>
 */
public class HeatIntakeDuctTest extends AbstractSharedServerTest {

    /** The Y and Z every helper here builds on, from this scenario's own site (see {@link #stand}). */
    private int cy;
    private int cz;

    /**
     * Ask for this scenario's site, prove its volume empty, and answer the X the room is centred on.
     * The chiller and its pipe run reach seven blocks past that, out through the room's east wall.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(4, 6, what);
        cy = site.y + 2;
        cz = site.z + 2;
        return site.x + 2;
    }

    /** Hot enough that a tick of chilling is unmistakable against integer rounding. */
    private static final int HOT_MILLI_K = 400_000;

    /**
     * A powered chiller breathing a room cools it, and its hot loop gains what the room lost plus the
     * work that moved it.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. COLDER — {@code HeatNetwork:535}
     * crediting the heat without taking it out of the air: "the room must actually get colder …
     * (before=400000 after=400000)". REPORTED — {@code HeatNetwork:541} not recording the delivery
     * as pumped in: "what the loop received must be reported as arriving from a pump: …
     * expected:&lt;6240&gt; but was:&lt;0&gt;". PLUS THE WORK — {@code HeatNetwork:536} delivering
     * the air's heat without the work: "the hot loop must receive what left the room PLUS the work
     * (air=6000 work=240 gained=6000)". The two premises at its head and the three before REPORTED
     * are arrangements and are not witnessed.</p>
     */
    @Test
    public void aChillerBreathingARoomCoolsItAndHeatsItsLoop() throws Exception {
        int cx = stand("a hot room with a powered chiller breathing it through a duct");
        buildRoomWithVent(cx);
        setAir(cx, 790_000, 210_000, 0, HOT_MILLI_K);
        buildChillerWithDuctAndLoop(cx);

        Reply roomBefore = ventInfo(cx);
        long tempBefore = roomBefore.longInteger("airTempMilliK");
        long capacity = roomBefore.longInteger("airHeatCapacity");
        assertEquals("premise: the room must start hot: " + roomBefore, HOT_MILLI_K, tempBefore);
        assertTrue("premise: and must hold heat to give up: " + roomBefore, capacity > 0);

        injectEnergyAt(cx + 3, cy + 1, 1_000_000);
        Reply cycled = cycleHotLoop(cx, 0L);

        Reply roomAfter = ventInfo(cx);
        long tempAfter = roomAfter.longInteger("airTempMilliK");
        long gained = cycled.longInteger("heatStored");
        long work = cycled.longInteger("work");
        // Heat taken out of the room's air on the SAME tick the delivery and the work were measured.
        long takenFromAir = cycled.longInteger("airTaken");

        assertTrue("the room must actually get colder — that is the whole point of the duct (before="
                + tempBefore + " after=" + tempAfter + "): " + roomAfter, tempAfter < tempBefore);
        assertTrue("premise: and the hot loop must have received something: " + cycled, gained > 0);
        assertTrue("premise: the chiller must have paid for it, or there is no work term to check: "
                + cycled, work > 0);
        assertTrue("premise: and must have actually taken heat out of the air: " + cycled,
                takenFromAir > 0);
        assertEquals("what the loop received must be reported as arriving from a pump: " + cycled,
                gained, cycled.longInteger("pumpedIn"));

        // Conservation across the air/coolant boundary. All three figures come from ONE tick of the
        // hot loop, which is what makes the equality checkable at all: read across probe calls, the
        // room's temperature would also carry whatever the natural ticks in the gap did to it, and
        // this loop's energy would not — measured 2026-08-17, the room read 18000 lost against 6240
        // delivered, and nothing was wrong with the machine.
        assertEquals("the hot loop must receive what left the room PLUS the work (air=" + takenFromAir
                        + " work=" + work + " gained=" + gained + "). A gap means the machine invented "
                        + "energy or quietly destroyed it: " + cycled,
                takenFromAir + work, gained);
    }

    /**
     * The control. The same rig with no electricity in the chiller must leave the room exactly where
     * it was — a duct is a mouth, not a hole, and heat does not walk out of a room on its own.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. MOVES NOTHING — {@code
     * TileHeatChiller:92} and {@code :104} both answering as if powered: "an unpowered chiller must
     * move nothing at all: … expected:&lt;352630&gt; but was:&lt;328945&gt;". WITH POWER — {@code
     * HeatNetwork:535} crediting the heat without taking it out of the air: "the same rig with power
     * must cool the room, or nothing above was measured: … \"airTempMilliK\":400000".</p>
     *
     * <p>What the loop receives is not asserted here: it is paid only what the room gave up plus paid
     * work, so any fault that feeds it moves the room first, and the conservation between the two is
     * the powered scenario's clause.</p>
     */
    @Test
    public void anUnpoweredChillerLeavesTheRoomAlone() throws Exception {
        int cx = stand("a hot room with an unpowered chiller breathing it through a duct");
        buildRoomWithVent(cx);
        setAir(cx, 790_000, 210_000, 0, HOT_MILLI_K);
        buildChillerWithDuctAndLoop(cx);

        long tempBefore = ventInfo(cx).longInteger("airTempMilliK");
        Reply cycled = cycleHotLoop(cx, 0L);
        long tempAfter = ventInfo(cx).longInteger("airTempMilliK");

        assertEquals("an unpowered chiller must move nothing at all: " + cycled,
                tempBefore, tempAfter);

        // And the same rig, powered, must then work — without this the assertions above would also
        // pass on a rig that was never able to cool anything in the first place.
        injectEnergyAt(cx + 3, cy + 1, 1_000_000);
        cycleHotLoop(cx, 0L);
        Reply powered = ventInfo(cx);
        assertTrue("the same rig with power must cool the room, or nothing above was measured: "
                        + powered, powered.longInteger("airTempMilliK") < tempBefore);
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * The chiller sits just outside the sealed room, its cold face carrying a duct that reaches
     * INTO the room's air, and its hot face against a short run of coolant pipe.
     */
    private void buildChillerWithDuctAndLoop(int cx) throws Exception {
        // The duct replaces a wall block, so one of its faces is a cell of the room's air.
        place(cx + 2, cy + 1, "stellurgy:heatIntakeDuct", null);
        // The chiller stands beyond it, facing away from the room: cold face on the duct, hot face
        // out into the pipe run.
        // meta 5 = EAST on libVulpes' horizontal FACING, so the hot face points along +X at the
        // pipe run and the COLD face lands on the duct. Placed without it the block defaults to
        // NORTH and the machine would be breathing a wall.
        place(cx + 3, cy + 1, "stellurgy:heatChiller", "5");
        for (int i = 4; i < 8; i++) {
            place(cx + i, cy + 1, "stellurgy:heatPipe", null);
        }
        Reply solved = arrange("stellurgytest subnet solve heat 0 1");
        assertEquals("solve failed: " + solved, 1, solved.integer("ticksSolved"));
    }

    private Reply cycleHotLoop(int cx, long charge) throws Exception {
        Reply cycled = arrange("stellurgytest heat cycle 0 " + (cx + 4) + " " + (cy + 1) + " " + cz
                + " " + charge + " 1");
        requireArranged("heat cycle found no loop: " + cycled, cycled.bool("inLoop"));
        assertEquals("premise: the loop must have been charged with exactly what was asked: " + cycled,
                charge, cycled.longInteger("charged"));
        return cycled;
    }

    private void buildRoomWithVent(int cx) throws Exception {
        int by = cy, bz = cz;
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by - 1) + " " + (bz - 2)
                + " " + (cx + 2) + " " + by + " " + (bz + 2) + " minecraft:stone");
        for (int yy = by + 1; yy <= by + 2; yy++) {
            arrange("stellurgytest fill 0 " + (cx - 2) + " " + yy + " " + (bz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (bz + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (cx - 1) + " " + yy + " " + (bz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (bz + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by + 3) + " " + (bz - 2)
                + " " + (cx + 2) + " " + (by + 3) + " " + (bz + 2) + " minecraft:stone");

        place(cx, cy, "stellurgy:oxygenVent", null);
        injectEnergyAt(cx, cy, 1_000_000);
        arrange("stellurgytest fluid inject 0 " + cx + " " + cy + " " + cz + " oxygen 16000");
        arrange("stellurgytest tile force-tick 0 " + cx + " " + cy + " " + cz + " 1");
        arrange("stellurgytest vent reseal 0 " + cx + " " + cy + " " + cz);
        arrange("stellurgytest tile force-tick 0 " + cx + " " + cy + " " + cz + " 5");
    }

    private void place(int x, int y, String block, String meta) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + y + " " + cz + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + "," + y + ": " + resp, resp.bool("placed"));
    }

    /** Gases in parts per million of an atmosphere, which is how a room's mix is quoted. */
    private void setAir(int cx, int n2, int o2, int co2, int milliK) throws Exception {
        arrange("stellurgytest vent setair 0 " + cx + " " + cy + " " + cz
                + " " + ppm(n2) + " " + ppm(o2) + " " + ppm(co2) + " " + milliK);
    }

    private void injectEnergyAt(int x, int y, int amount) throws Exception {
        arrange("stellurgytest energy inject 0 " + x + " " + y + " " + cz + " " + amount);
    }

    private Reply ventInfo(int cx) throws Exception {
        return ask("stellurgytest vent info 0 " + cx + " " + cy + " " + cz);
    }
}
