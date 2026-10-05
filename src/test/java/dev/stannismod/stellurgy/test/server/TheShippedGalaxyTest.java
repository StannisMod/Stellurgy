package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The galaxy a fresh install is given: no {@code planetDefs.xml}, so the catalogue the code declares,
 * under the shipped procedural configuration.
 *
 * <p>NEW-GROUP: the shipped galaxy of a fresh install — the code-declared catalogue and the generator it
 * runs under. No group holds it: {@code PlanetDefsAuthoringTest} and {@code PlanetXmlConfigIntegrationTest}
 * each boot an authored file, and a server with no file at all takes a different branch of
 * {@code DimensionManager#createAndLoadDimensions} from a file that merely states no {@code <galaxyGen>}.</p>
 *
 * <p>What this does not see: where the derived worlds stand or what they are, nor a player reaching one.</p>
 */
public class TheShippedGalaxyTest extends AbstractSharedServerTest {

    private static final int SUN = 0;

    /**
     * red-witnessed: with {@code DimensionManager#createAndLoadDimensions} at
     * {@code dev.stannismod.stellurgy.universe.GalaxyGenConfig.defaults()} (the no-file branch) replaced by
     * {@code GalaxyGenConfig.nonProcedural()}, this fails with "a server with no planetDefs.xml must run
     * the procedural galaxy: {"generator":"EmptyGalaxyGenerator","config":null}", 2026-10-05.
     */
    @Test
    public void aFreshInstallRunsTheShippedProceduralGalaxy() throws Exception {
        Reply inForce = ask("stellurgytest space gen-config");

        assertEquals("a server with no planetDefs.xml must run the procedural galaxy: " + inForce,
                "ClusteredGalaxyGenerator", inForce.text("generator"));
    }

    /**
     * red-witnessed: with {@code DimensionManager#createAndLoadDimensions} at
     * {@code dev.stannismod.stellurgy.universe.GalaxyGenConfig.defaults()} (the no-file branch) replaced by
     * {@code GalaxyGenConfig.nonProcedural()}, this fails with "the Sun asks for 10 worlds and must hold
     * derived ones beside Earth and the Moon, not the authored bodies alone: … "systemBodies":3", 2026-10-05.
     */
    @Test
    public void aFreshInstallsSunHoldsDerivedWorldsBesideItsAuthoredOnes() throws Exception {
        Reply sun = ask("stellurgytest star get " + SUN);
        int asked = sun.integer("maxRetinue");
        if (asked <= 0) {
            throw new ArrangementFailure("the shipped Sun must ask for a retinue, or there is nothing"
                    + " to derive: " + sun);
        }
        String cell = ask("stellurgytest space anchor " + SUN).text("cell");
        Reply system = ask("stellurgytest space cell-info " + cell);

        int derived = 0;
        for (String raw : system.objectArray("bodies")) {
            Reply body = Reply.of(raw);
            String kind = body.text("kind");
            if (body.integer("dim") == Constants.INVALID_PLANET
                    && ("PLANET".equals(kind) || "GAS_GIANT".equals(kind))) {
                derived++;
            }
        }
        assertTrue("the Sun asks for " + asked + " worlds and must hold derived ones beside Earth and the"
                + " Moon, not the authored bodies alone: " + system, derived > 0);
    }
}
