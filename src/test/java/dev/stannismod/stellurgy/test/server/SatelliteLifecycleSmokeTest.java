package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * satellite lifecycle.
 *
 * <p>Registry sanity + per-type round-trip coverage for all 10 production
 * satellite types: optical, density, composition, mass, asteroidMiner,
 * gasMining, solarEnergy, oreScanner, biomeChanger, weatherController.
 * Each round-trip creates a satellite via {@code /stellurgytest satellite create},
 * confirms it appears in the dimension's list with the right type, and that
 * {@code info} echoes the requested powerGen / powerStorage / maxData fields
 * — pinning the contract that {@link
 * dev.stannismod.stellurgy.tile.satellite.TileSatelliteBuilder} ultimately
 * relies on.</p>
 *
 * <p>Plus an integration-level test of builder-to-satellite synthesis.</p>
 */
public class SatelliteLifecycleSmokeTest extends AbstractSharedServerTest {

    private static final String ID_PATTERN = "id";

    /**
     * Build a real satellite via {@link
     * dev.stannismod.stellurgy.tile.satellite.TileSatelliteBuilder}'s
     * assemble code path, exercised through a probe that fills the multiblock's
     * slots (chassis + primary function chip + power source + battery) and
     * invokes assembly. Asserts the output ItemStack carries a freshly-minted
     * satellite ID and that the satellite is registered in the dim.
     */
    @Test
    public void satelliteBuilderProducesValidSatelliteFromComponents() throws Exception {
        // optical = SatellitePrimaryFunction meta=0 (see Stellurgy.java:535).
        String resp = String.join("\n", client().arrange(
                "stellurgytest satellite-builder build 0 optical"));
        assertTrue("builder build failed: " + resp, Reply.of(resp).ok());
        Reply mReply = Reply.of(resp);
        assertTrue("builder response missing id: " + resp, mReply.has(ID_PATTERN));
        long satId = Long.parseLong(mReply.text(ID_PATTERN));

        String info = String.join("\n", client().read("stellurgytest satellite info 0 " + satId));
        assertTrue("builder-created satellite not registered: " + info,
                !Reply.of(info).has("error"));
        assertTrue("builder-created satellite must report type=optical: " + info,
                "optical".equals(Reply.of(info).text("type")));
    }
}
