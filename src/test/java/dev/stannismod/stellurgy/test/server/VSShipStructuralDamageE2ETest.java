package dev.stannismod.stellurgy.test.server;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.WarShip;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * Damaging a block that belongs to a SHIP — the case the whole damage engine exists for, and the one
 * its world-block tests cannot reach.
 *
 * <p>A ship's blocks do not live where the ship appears to be. They sit at fixed addresses in a distant
 * shipyard subspace while the hull flies around, so an impact arriving in world coordinates has to be
 * mapped into that frame before anything can be looked up, and the report's points mapped back out. In
 * an ordinary world the two frames are the same thing, which means every assertion fired at ordinary
 * blocks would pass just as happily with the mapping deleted — that is precisely why this test exists
 * as a separate class rather than another case beside them.</p>
 *
 * <h3>The arrangement has to MOVE the ship</h3>
 * <p>A freshly assembled ship sits at its build site with an identity transform, where world and
 * subspace still coincide. Testing there would be the same empty test with more steps. So the ship is
 * rigid-teleported far away first, and the test asserts <b>as a control</b> that the two frames have
 * genuinely diverged before it draws any conclusion from what follows.</p>
 *
 * <p>The craft is built and addressed by identity ({@link WarShip}); it was addressed by position
 * before, and the class carried an {@code Assume} on a {@code vs available} verb that no longer exists,
 * so it had been SKIPPED on every run since that verb was removed.</p>
 */
public class VSShipStructuralDamageE2ETest extends AbstractSharedServerTest {

    /** Build site, well clear of the other ship scenarios on this shared server. */
    private static final int SRC_X = 7200, SRC_Z = 7200;
    /** Where the ship is moved to. Far enough that no world-frame accident could reach the hull. */
    private static final int FAR_X = 7200, FAR_Y = 240, FAR_Z = 9600;
    /** Below this the two frames have not diverged enough for the control to mean anything. */
    private static final double MIN_FRAME_DIVERGENCE = 100.0D;
    /** The fixture spans about twenty blocks; a mapped seat further than this from the hull is not on it. */
    private static final double ON_THE_HULL = 64.0D;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /** A craft left behind goes on ticking in the world the next scenario runs in. */
    @Before
    public void disposeOfEarlierCraft() throws Exception {
        System.out.println("[reset] craft cleared: " + ShipReadiness.clearCraftFrom(this::exec, 0));
    }

    /**
     * red-witnessed: with {@code ShipDamageService#apply} at {@code String shipId = shipAt(world, point, request.getDirection());} resolving no ship (the world frame walked
     * instead), this fails with "an impact at the ship's world position struck nothing — the world
     * point was not mapped into the frame the ship's blocks live in: {...outcome:NOTHING_STRUCK...}";
     * with {@code ShipDamageService#toWorld} at {@code if (shipId == null)} returning the report's points unmapped, it fails with
     * "the entry point came back at (1.9200001E7,130.0,51200.0), 1.9192845083520055E7 blocks off the
     * vertical line it was fired down through". The "subspace block damaged" verdict was not separately
     * witnessed. 2026-09-30.
     */
    @Test
    public void aBlockOfAMovedShipIsDamagedThroughItsWorldPosition() throws Exception {
        Reply.of(exec("stellurgytest damage clear-impacts")).requireOk("forget earlier impacts");

        // Build a ship and move it, so world and subspace no longer coincide.
        WarShip ship = WarShip.build(events, this::exec, FixtureSite.openAir(0, SRC_X, SRC_Z), null,
                "the craft whose block is shot");
        ship.parkAt(FAR_X, FAR_Y, FAR_Z, ON_THE_HULL);

        // A block of this ship whose subspace address we know: its pilot seat.
        int[] sub = ship.seat();
        double[] world = ship.toWorld(sub[0], sub[1], sub[2]);

        // ARRANGEMENT CONTROL. The mapped point must actually be on the ship as the world sees it;
        // if it is not, everything below measures a broken fixture rather than the damage engine.
        Reply moved = ship.info();
        double offHull = Math.sqrt(sq(world[0] - moved.number("posX")) + sq(world[1] - moved.number("posY"))
                + sq(world[2] - moved.number("posZ")));
        requireArranged("the seat's mapped world point is " + offHull + " blocks from the ship's own"
                + " world position: the fixture, not the engine, is what this run would be measuring. "
                + moved, offHull < ON_THE_HULL);

        // THE CONTROL. Everything below is only evidence if the two frames actually differ.
        double divergence = Math.sqrt(sq(world[0] - sub[0]) + sq(world[1] - sub[1]) + sq(world[2] - sub[2]));
        requireArranged("world and subspace frames are only " + divergence + " blocks apart: this"
                + " arrangement cannot tell a correct conversion from no conversion",
                divergence > MIN_FRAME_DIVERGENCE);

        // The subject block, read at its SUBSPACE address — where a ship's blocks actually are.
        Reply before = stage(sub[0], sub[1], sub[2]);
        requireArranged("the seat's subspace address holds no block, so nothing below is about the ship: "
                + before, !"minecraft:air".equals(before.text("block")));
        requireArranged("the subject block is damaged before the impact: " + before,
                before.integer("stage") == 0);

        // Fire straight down through the seat's WORLD position with a budget that will not be spent
        // in one block, and give it an identity of its own.
        Reply result = Reply.of(exec("stellurgytest damage impact 0 " + world[0] + " " + (world[1] + 3.0D) + " "
                + world[2] + " 0 -1 0 200000 KINETIC 77001")).requireOk("declare the impact");
        assertTrue("the impact point resolved to no ship at all (candidates offered: "
                + result.integer("candidateShips") + "), so the engine walked the world frame where"
                + " this ship has no blocks:\n" + result, result.bool("onShip"));
        assertTrue("an impact at the ship's world position struck nothing — the world point was not"
                + " mapped into the frame the ship's blocks live in:\n" + result,
                !"NOTHING_STRUCK".equals(result.text("outcome")));
        assertTrue("the impact spent nothing on the ship:\n" + result, result.integer("spent") > 0);

        // The damage landed on the SHIP's own block, at its subspace address.
        Reply after = stage(sub[0], sub[1], sub[2]);
        assertTrue("the impact reported damage but the ship's own block is untouched at its subspace"
                + " address (before=" + before + " after=" + after + "):\n" + result,
                after.integer("stage") > 0 || after.bool("wasDestroyed") || "minecraft:air".equals(after.text("block")));

        // And the report comes back in WORLD coordinates: a shot that resumes on a subspace point
        // would carry on inside a shipyard nobody can see. The impact was fired straight DOWN through
        // the seat's world point, so its entry lies on that vertical line — horizontally within a
        // block of it by construction — and no higher than the three blocks above the seat it was
        // fired from. A subspace entry would sit at the yard's address, the divergence away.
        assertTrue("the report names no entry point:\n" + result, result.bool("hasEntry"));
        double entryX = result.number("entryX"), entryY = result.number("entryY"), entryZ = result.number("entryZ");
        double sideways = Math.sqrt(sq(entryX - world[0]) + sq(entryZ - world[2]));
        assertTrue("the entry point came back at (" + entryX + "," + entryY + "," + entryZ + "), "
                + sideways + " blocks off the vertical line it was fired down through (" + world[0] + ","
                + world[2] + "): the report was not mapped back out of the ship frame:\n" + result,
                sideways < 1.0D);
        assertTrue("the entry point came back at y=" + entryY + ", outside the three blocks above the"
                + " seat it was fired from (" + world[1] + "): the report was not mapped back out of the"
                + " ship frame:\n" + result, entryY <= world[1] + 3.0D + 1.0E-6D && entryY >= world[1] - 1.0D);
    }

    private Reply stage(int x, int y, int z) throws Exception {
        return Reply.of(exec("stellurgytest damage stage 0 " + x + " " + y + " " + z)).requireOk("read a stage");
    }

    private static double sq(double v) {
        return v * v;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
