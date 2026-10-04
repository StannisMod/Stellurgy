package dev.stannismod.stellurgy.test.unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import dev.stannismod.stellurgy.util.WeightEngine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * MC-free unit coverage for {@link WeightEngine}: the parts that don't need a
 * block/item registry — the JSON table round-trip, the schema check, and default
 * seeding. Block/material resolution (which needs real ItemStacks) is covered by the
 * server-tier {@code WeightSystemTest}.
 *
 * <p>Every test builds its own engine, so nothing reaches the game's table (the mod's) or its
 * file.</p>
 */
public class WeightEngineUnitTest {

    /** Materials the default table must hold before it counts as POPULATED — the test's own bar,
     *  far under what the mod ships. */
    private static final int MIN_MATERIALS = 10;

    /** The opening of a table in the schema this engine reads. */
    private static final String CURRENT = "{\"formatVersion\":" + WeightEngine.FORMAT_VERSION;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void seedDefaultsPopulatesMaterialTable() {
        WeightEngine we = WeightEngine.fromJson(CURRENT + "}");
        assertTrue("default material table must be populated", we.materialCount() > MIN_MATERIALS);
    }

    @Test
    public void individualOverrideSurvivesSaveLoadRoundTrip() throws Exception {
        File file = new File(tempFolder.getRoot(), "weights.json");

        WeightEngine fresh = new WeightEngine(file.getPath());
        assertNull("a fresh file must not know the test key", fresh.rawIndividual("ar:roundtrip_probe"));

        Files.write(file.toPath(), (CURRENT + ",\"individual\":{\"ar:roundtrip_probe\":42.0}}")
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

    /**
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code WeightEngine#read} at {@code if (root == null || !root.has("formatVersion")} checking no schema
     * version fails "must NOT be read" (0.1); {@code WeightEngine#retireIncompatibleFile} at {@code if (!current.renameTo(retired))} deleting the file instead of setting it aside
     * fails "must be kept beside the new one"; {@code WeightEngine#retireIncompatibleFile} at {@code save();}'s save skipped fails "must write a fresh table";
     * {@code WeightEngine#save} at {@code json.addProperty("formatVersion", FORMAT_VERSION);} not stamping the version fails "retired again".</p>
     */
    @Test
    public void aTableFromAnotherSchemaIsSetAsideRatherThanRead() throws Exception {
        // The numbers in weights.json changed meaning when the tables were denominated in
        // kilograms: a value that used to mean "an ordinary block" now means a two-hundredth of
        // one. Reading such a file would silently make every hull far too light and every rocket
        // able to launch, so a file whose schema version does not match must be set aside and
        // replaced with defaults — never reinterpreted, and never deleted either, because only its
        // author can tell a material default from a deliberate absolute.
        File table = new File(tempFolder.getRoot(), "weights.json");
        File retired = new File(table.getPath() + ".v" + (WeightEngine.FORMAT_VERSION - 1) + ".bak");

        // Shaped like the pre-kilogram schema: no formatVersion, an override that would be read back
        // verbatim, and a one-entry material table — so a table that WAS read shows as one material,
        // and only a reseed can show as a populated one.
        Files.write(table.toPath(), ("{\"individual\":{\"ar:legacy_probe\":0.1},\"byRegex\":{},"
                + "\"fluids\":{},\"materials\":{\"ROCK\":0.4},"
                + "\"fallback\":0.1,\"fluidFallback\":0.001}").getBytes(StandardCharsets.UTF_8));

        WeightEngine we = new WeightEngine(table.getPath());

        assertNull("a table from another schema must NOT be read into the live tables",
                we.rawIndividual("ar:legacy_probe"));
        assertTrue("the incompatible file must be kept beside the new one, not dropped",
                retired.exists());
        assertTrue("the tables must be the defaults, not the one-material table that was set aside"
                        + " (materials " + we.materialCount() + ")",
                we.materialCount() > MIN_MATERIALS);

        // The file just written must survive its own version check, i.e. save() stamps it. A file
        // that fails the check is RETIRED again — which re-creates the backup — and then reseeded,
        // which leaves the tables looking healthy; so the backup is the discriminating reading and
        // the material count is not.
        requireArranged("could not clear the first backup before the second load", retired.delete());
        // The reseed must have WRITTEN a table: with no file, the next load takes the no-file branch,
        // retires nothing, and an absent backup would read as a passed check on a file that was
        // never there.
        assertTrue("the reseed must write a fresh table for the next load to check", table.exists());
        new WeightEngine(table.getPath());
        assertFalse("the reseeded file must pass the version check on the next load, but it was"
                + " retired again: save() did not stamp the version it checks", retired.exists());
    }
}
