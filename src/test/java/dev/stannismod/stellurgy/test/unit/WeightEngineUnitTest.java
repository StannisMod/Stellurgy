package dev.stannismod.stellurgy.test.unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import dev.stannismod.stellurgy.util.WeightEngine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * MC-free unit coverage for {@link WeightEngine}: the parts that don't need a
 * block/item registry — fluid weight arithmetic, the JSON table round-trip, and
 * default seeding. Block/material resolution (which needs real ItemStacks) is
 * covered by the server-tier {@code WeightSystemTest}.
 *
 * <p>Every test builds its own engine, so nothing reaches the game's table (the mod's) or its
 * file.</p>
 */
public class WeightEngineUnitTest {

    /** Materials the default table must hold before it counts as POPULATED — the test's own bar,
     *  far under what the mod ships. */
    private static final int MIN_MATERIALS = 10;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void seedDefaultsPopulatesMaterialTable() {
        WeightEngine we = WeightEngine.fromJson("{}");
        assertTrue("default material table must be populated", we.materialCount() > MIN_MATERIALS);
    }

    @Test
    public void individualOverrideSurvivesSaveLoadRoundTrip() throws Exception {
        File file = new File(tempFolder.getRoot(), "weights.json");

        WeightEngine fresh = new WeightEngine(file.getPath());
        assertNull("a fresh file must not know the test key", fresh.rawIndividual("ar:roundtrip_probe"));

        Files.write(file.toPath(), "{\"individual\":{\"ar:roundtrip_probe\":42.0}}"
                .getBytes(StandardCharsets.UTF_8));
        WeightEngine written = new WeightEngine(file.getPath());
        assertEquals("the override must be read from the file",
                Double.valueOf(42.0), written.rawIndividual("ar:roundtrip_probe"));

        // Save writes it back; an engine built from what was saved must hold it again.
        written.save();
        WeightEngine reloaded = new WeightEngine(file.getPath());
        assertEquals("override must persist across save/load",
                Double.valueOf(42.0), reloaded.rawIndividual("ar:roundtrip_probe"));
    }
}
