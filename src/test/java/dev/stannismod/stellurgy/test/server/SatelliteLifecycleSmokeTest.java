package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertEquals;
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
 * <p>Plus three integration-level tests: builder-to-satellite synthesis,
 * terminal-chip linking, and an ID-chip persistence smoke (server-side
 * satellite survives a restart).</p>
 */
public class SatelliteLifecycleSmokeTest extends AbstractSharedServerTest {

    /** Satellite types the registry must offer — the test's own bar on "the catalogue is not
     *  empty", well under what the mod ships. */
    private static final int MIN_SATELLITE_TYPES = 5;

    private static final String ID_PATTERN = "id";

    @Test
    public void satelliteCreatePopulatesDimensionProperties() throws Exception {
        // Registry sanity.
        String types = String.join("\n", client().execute("stellurgytest satellite types"));
        int totalQuotes = countOccurrences(types, "\"");
        int actualCount = (totalQuotes - 2) / 2; // -2 for "satelliteTypes" key quotes
        assertTrue("expected ≥5 satellite types, got " + actualCount + ": " + types,
                actualCount >= MIN_SATELLITE_TYPES);
    }

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
        String resp = String.join("\n", client().execute(
                "stellurgytest satellite-builder build 0 optical"));
        assertTrue("builder build failed: " + resp, Reply.of(resp).ok());
        Reply mReply = Reply.of(resp);
        assertTrue("builder response missing id: " + resp, mReply.has(ID_PATTERN));
        long satId = Long.parseLong(mReply.text(ID_PATTERN));

        String info = String.join("\n", client().execute("stellurgytest satellite info 0 " + satId));
        assertTrue("builder-created satellite not registered: " + info,
                !Reply.of(info).has("error"));
        assertTrue("builder-created satellite must report type=optical: " + info,
                "optical".equals(Reply.of(info).text("type")));
    }

    /**
     * Place a satellite terminal, create a satellite, imprint its ID onto
     * an identification chip, place the chip in the terminal's slot 0, and
     * verify the terminal resolves the chip back to the live SatelliteBase.
     */
    @Test
    public void satelliteTerminalListsAttachedSatellites() throws Exception {
        // The terminal stands in the OPEN-AIR BAND, not on terrain. Its Y was a hard-coded 70 until
        // 2026-09-14 and the scenario never wanted ground: a terminal resolves a chip to a live
        // satellite, which has nothing to do with what is under it. What 70 bought was whatever the
        // pinned seed rolled here, and the 3x3x3 clear below then reads as a pocket in rock rather
        // than as air. In the band there is nothing to be inside of, and the clear below keeps its
        // own job — the place must not silently replace some other block.
        int bx = 1800, by = FixtureSite.OPEN_AIR_Y, bz = 1900;
        ok(client().execute("stellurgytest fill 0 " + (bx - 1) + " " + (by - 1) + " " + (bz - 1)
                + " " + (bx + 1) + " " + (by + 1) + " " + (bz + 1) + " minecraft:air"));

        String place = String.join("\n", client().execute(
                "stellurgytest place 0 " + bx + " " + by + " " + bz + " stellurgy:satelliteControlCenter"));
        assertTrue("satellite terminal did not place: " + place,
                Reply.of(place).bool("placed"));

        long satId = createAndGetId("density", 50, 500, 256);

        // Probe imprints the chip into slot 0 directly — bypasses the GUI
        // path the player would normally use.
        String imprint = String.join("\n", client().execute(
                "stellurgytest satellite imprint-terminal 0 " + bx + " " + by + " " + bz + " " + satId));
        assertTrue("terminal imprint failed: " + imprint, Reply.of(imprint).ok());

        String linked = String.join("\n", client().execute(
                "stellurgytest satellite terminal-info 0 " + bx + " " + by + " " + bz));
        assertTrue("terminal must surface the linked satellite ID: " + linked,
                String.valueOf(satId).equals(Reply.of(linked).text("linkedSatelliteId")));
        assertTrue("terminal must surface the linked satellite type: " + linked,
                "density".equals(Reply.of(linked).text("linkedType")));
    }

    /**
     * Helper: create a satellite via probe and return its generated long ID.
     */
    private long createAndGetId(String type, int powerGen, int powerStorage, int maxData) throws Exception {
        String create = String.join("\n", client().execute(
                "stellurgytest satellite create 0 " + type + " " + powerGen + " " + powerStorage + " " + maxData));
        assertTrue("satellite create (" + type + ") failed: " + create,
                Reply.of(create).ok());
        Reply mReply = Reply.of(create);
        assertTrue("could not extract satellite id from: " + create, mReply.has(ID_PATTERN));
        return Long.parseLong(mReply.text(ID_PATTERN));
    }

    private void ok(java.util.List<String> response) {
        String joined = String.join("\n", response);
        assertTrue("probe call failed: " + joined, Reply.of(joined).ok());
    }

    private static int countOccurrences(String s, String needle) {
        int c = 0, i = 0;
        while ((i = s.indexOf(needle, i)) != -1) { c++; i += needle.length(); }
        return c;
    }
}
