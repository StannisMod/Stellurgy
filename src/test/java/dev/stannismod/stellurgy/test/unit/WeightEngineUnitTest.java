package dev.stannismod.stellurgy.test.unit;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.util.WeightEngine;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * MC-free unit coverage for {@link WeightEngine}: the parts that don't need a
 * block/item registry — fluid weight arithmetic, the JSON table round-trip, and
 * default seeding. Block/material resolution (which needs real ItemStacks) is
 * covered by the server-tier {@code WeightSystemTest}.
 */
public class WeightEngineUnitTest {

    /** Materials the default table must hold before it counts as POPULATED — the test's own bar,
     *  far under what the mod ships. */
    private static final int MIN_MATERIALS = 10;

    private static Fluid testFluid() {
        ResourceLocation tex = new ResourceLocation("stellurgy", "blocks/unit_fluid");
        return new Fluid("stellurgy_unit_fluid", tex, tex);
    }

    @Test
    public void fluidWeightIsPositiveAndLinearInAmount() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
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
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
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
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        assertTrue("default material table must be populated", we.materialCount() > MIN_MATERIALS);
    }

    @Test
    public void individualOverrideSurvivesSaveLoadRoundTrip() {
        WeightEngine we = WeightEngine.INSTANCE;
        try {
            we.resetTables();
            assertNull("clean slate must not know the test key", we.rawIndividual("ar:roundtrip_probe"));

            we.setIndividual("ar:roundtrip_probe", 42.0);
            we.save();

            // Wipe in-memory state, then reload from the file just written.
            we.resetTables();
            assertNull("resetTables must drop the in-memory override", we.rawIndividual("ar:roundtrip_probe"));

            we.load();
            assertEquals("override must persist across save/load",
                    Double.valueOf(42.0), we.rawIndividual("ar:roundtrip_probe"));
        } finally {
            // Leave no residue in the on-disk config for other tests.
            we.resetTables();
            we.save();
        }
    }

    /**
     * <p>red-witnessed: with {@code WeightEngine:370} no longer stamping formatVersion in save(), fails "retired again", 2026-09-29.</p>
     */
    @Test
    public void aTableFromAnotherSchemaIsSetAsideRatherThanRead() throws Exception {
        // The numbers in weights.json changed meaning when the tables were denominated in
        // kilograms: a value that used to mean "an ordinary block" now means a two-hundredth of
        // one. Reading such a file would silently make every hull far too light and every rocket
        // able to launch, so a file whose schema version does not match must be set aside and
        // replaced with defaults — never reinterpreted, and never deleted either, because only its
        // author can tell a material default from a deliberate absolute.
        WeightEngine we = WeightEngine.INSTANCE;
        File table = new File("config/advRocketry/weights.json");
        File retired = new File(table.getPath() + ".v1.bak");
        try {
            if (retired.exists()) {
                assertTrue("could not clear a stale backup from an earlier run", retired.delete());
            }
            File parent = table.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            // Shaped like the pre-kilogram schema: no formatVersion, and an override that would be
            // read back verbatim if the version check were absent.
            try (Writer w = new FileWriter(table)) {
                w.write("{\"individual\":{\"ar:legacy_probe\":0.1},\"byRegex\":{},"
                        + "\"fluids\":{},\"materials\":{\"ROCK\":0.4},"
                        + "\"fallback\":0.1,\"fluidFallback\":0.001}");
            }

            we.load();

            assertNull("a table from another schema must NOT be read into the live tables",
                    we.rawIndividual("ar:legacy_probe"));
            assertTrue("the incompatible file must be kept beside the new one, not dropped",
                    retired.exists());
            assertTrue("defaults must be reseeded so the engine stays usable", we.materialCount() > 10);

            // The file just written must survive its own version check, i.e. save() stamps it. A file
            // that fails the check is RETIRED again — which re-creates the backup — and then reseeded,
            // which leaves the tables looking healthy; so the backup is the discriminating reading and
            // the material count is not.
            assertTrue("could not clear the first backup before the second load", retired.delete());
            // The reseed must have WRITTEN a table: with no file, the next load takes the no-file branch,
            // retires nothing, and an absent backup would read as a passed check on a file that was
            // never there.
            assertTrue("the reseed must write a fresh table for the next load to check", table.exists());
            we.load();
            assertFalse("the reseeded file must pass the version check on the next load, but it was"
                    + " retired again: save() did not stamp the version it checks", retired.exists());
        } finally {
            if (retired.exists()) {
                retired.delete();
            }
            we.resetTables();
            we.save();
        }
    }
}
