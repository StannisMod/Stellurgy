package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.MachineInfo;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Atmosphere Terraformer group: multiblock validation, the lone controller's tick, and the
 * terraforming helper an atmosphere change starts.
 *
 * <p>{@link dev.stannismod.stellurgy.tile.multiblock.TileAtmosphereTerraformer}
 * — the largest Stellurgy multiblock by footprint: a 17×17 sphere-like shape over
 * ~10 layers, mixing {@code blockAdvStructureBlock}, {@code blockOxygenVent},
 * {@code blockConcrete}, {@code blockFuelTank}, {@code Blocks.CLAY} and the
 * {@code 'P'} / {@code 'L'} hatches at the base.</p>
 *
 * <p>Built through the new reflection-backed generic fixture probe
 * {@code /stellurgytest fixture multiblock terraformer} — reads the production
 * {@code structure} array directly, so the test stays in sync with the
 * production layout automatically.</p>
 *
 * <p>Position-isolated at x=8000 (well clear of x=7500 SolarArray and
 * predecessors). The 17×17 footprint is much larger than other multiblocks,
 * so successive test methods step by 60 blocks to avoid overlap.</p>
 */
public class TerraformerMultiblockTest extends AbstractSharedServerTest {

    private static final int CX = 8000;
    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 8000;

    @Test
    public void terraformerMultiblockValidatesWhenFixtureIsBuilt() throws Exception {
        String fixture = join(client().execute(
                "stellurgytest fixture multiblock terraformer 0 " + CX + " " + CY + " " + CZ));
        assertTrue("fixture multiblock terraformer failed: " + fixture,
                Reply.of(fixture).ok());
        assertTrue("fixture didn't place any blocks: " + fixture,
                Reply.of(fixture).has("placed") && !(Reply.of(fixture).integer("placed") == 0));

        String info = join(client().execute(
                "stellurgytest machine info 0 " + CX + " " + CY + " " + CZ));
        assertEquals("expected TileAtmosphereTerraformer tile at controller pos: " + info,
                "TileAtmosphereTerraformer", MachineInfo.of(info).tileSimpleName());

        String tryComplete = join(client().execute(
                "stellurgytest machine try-complete 0 " + CX + " " + CY + " " + CZ));
        assertTrue("try-complete probe errored: " + tryComplete,
                Reply.of(tryComplete).ok());
        assertTrue("terraformer multiblock didn't validate (isComplete=false): " + tryComplete,
                Reply.of(tryComplete).bool("isComplete"));
    }

    @Test
    public void terraformerMultiblockInvalidatesWhenAdjacentAdvStructureRemoved() throws Exception {
        int cx = CX + 60, cy = CY, cz = CZ;
        String fixture = join(client().execute(
                "stellurgytest fixture multiblock terraformer 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());

        // The controller sits in the equator ring. An advStructureBlock cell
        // directly adjacent at globalX = cx + 1 (one block east of the
        // controller, same row) is part of the structure — replacing it with
        // stone fails validation. Break BEFORE first try-complete (no-baseline
        // pattern — once hidden, oxygenVent / hatch TE breakBlocks can NPE).
        String breakAdj = join(client().execute(
                "stellurgytest place 0 " + (cx + 1) + " " + cy + " " + cz + " minecraft:stone"));
        assertTrue("could not replace neighbour: " + breakAdj,
                Reply.of(breakAdj).ok());

        String broken = join(client().execute(
                "stellurgytest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("terraformer validated despite missing neighbour: " + broken,
                (!Reply.of(broken).bool("isComplete")));
    }

    /**
     * A lone controller, with no structure around it, survives a tick burst: the production tick checks
     * {@code isComplete} before it does any work.
     */
    @Test
    public void terraformerControllerSurvivesTickWithoutStructure() throws Exception {
        FixtureSite s = site();
        String at = " 0 " + s.x + " " + s.y + " " + s.z;

        String place = join(client().execute("stellurgytest place" + at + " stellurgy:terraformer"));
        assertTrue("terraformer place failed: " + place, Reply.of(place).bool("placed"));

        String info = join(client().execute("stellurgytest machine info" + at));
        assertEquals("expected terraformer tile: " + info,
                "TileAtmosphereTerraformer", MachineInfo.of(info).tileSimpleName());

        String tryComplete = join(client().execute("stellurgytest machine try-complete" + at));
        assertTrue("incomplete terraformer should report isComplete=false: " + tryComplete,
                (!Reply.of(tryComplete).bool("isComplete")));

        String tick = join(client().execute("stellurgytest tile force-tick" + at + " 60"));
        assertTrue("force-tick errored: " + tick, Reply.of(tick).ok());
        assertEquals("must tick all 60 iterations", 60, Reply.of(tick).integer("ticked"));

        String postInfo = join(client().execute("stellurgytest machine info" + at));
        assertEquals("tile must survive tick burst: " + postInfo,
                "TileAtmosphereTerraformer", MachineInfo.of(postInfo).tileSimpleName());
    }

    /**
     * A change of air is what sets the ground to change: after
     * {@link dev.stannismod.stellurgy.dimension.DimensionProperties#setAtmosphereDensity(int)} the
     * planet's world has a terraforming helper working on it. The overworld's density is restored
     * afterwards, so no sibling here reads a mutated atmosphere.
     */
    @Test
    public void atmosphereChangeStartsTerraformingHelper() throws Exception {
        String before = join(client().execute("stellurgytest terraforming info 0"));
        assertTrue("baseline terraforming info errored: " + before, !Reply.of(before).has("error"));
        Reply baseline = Reply.of("stellurgytest terraforming info", before);
        assertTrue("could not extract currentAtmosphere from: " + before, baseline.has("currentAtmosphere"));
        int currentBefore = baseline.integer("currentAtmosphere");

        int target = currentBefore == 25 ? 75 : 25;
        try {
            client().execute("stellurgytest terraforming set-density 0 " + target);

            String after = join(client().execute("stellurgytest terraforming info 0"));
            assertTrue("no terraforming helper on the planet's world after the atmosphere changed: " + after,
                    Reply.of(after).bool("helperPresent"));
        } finally {
            client().execute("stellurgytest terraforming set-density 0 " + currentBefore);
        }
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
