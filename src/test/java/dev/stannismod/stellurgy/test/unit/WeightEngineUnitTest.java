package dev.stannismod.stellurgy.test.unit;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
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
 * <p>Every test builds its own engine, so nothing reaches the game's {@link WeightEngine#INSTANCE}
 * or its file.</p>
 */
public class WeightEngineUnitTest {

    /** Materials the default table must hold before it counts as POPULATED — the test's own bar,
     *  far under what the mod ships. */
    private static final int MIN_MATERIALS = 10;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static Fluid testFluid() {
        ResourceLocation tex = new ResourceLocation("stellurgy", "blocks/unit_fluid");
        return new Fluid("stellurgy_unit_fluid", tex, tex);
    }

    @Test
    public void fluidWeightIsPositiveAndLinearInAmount() {
        WeightEngine we = WeightEngine.fromJson("{}");
        double prevScale = StellurgyConfiguration.getCurrentConfig().fuelMassScale;
        try {
            StellurgyConfiguration.getCurrentConfig().fuelMassScale = 1.0;
            // An unknown fluid still weighs something (the fallback per-mB rate)
            // and the weight is linear in the amount. The exact kN/mB constant is
            // an implementation default.
            float base = we.getWeight(testFluid(), 1000f);
            assertTrue("fallback fluid weight must be positive: " + base, base > 0);
            assertEquals("fluid weight must be linear in the amount",
                    2 * base, we.getWeight(testFluid(), 2000f), 1e-4);
        } finally {
            StellurgyConfiguration.getCurrentConfig().fuelMassScale = prevScale;
        }
    }

    @Test
    public void fuelMassScaleMultipliesFluidWeight() {
        WeightEngine we = WeightEngine.fromJson("{}");
        double prevScale = StellurgyConfiguration.getCurrentConfig().fuelMassScale;
        try {
            StellurgyConfiguration.getCurrentConfig().fuelMassScale = 1.0;
            float base = we.getWeight(testFluid(), 1000f);

            StellurgyConfiguration.getCurrentConfig().fuelMassScale = 2.5;
            assertEquals("fluid weight must scale by fuelMassScale",
                    2.5f * base, we.getWeight(testFluid(), 1000f), 1e-4);
        } finally {
            StellurgyConfiguration.getCurrentConfig().fuelMassScale = prevScale;
        }
    }

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
