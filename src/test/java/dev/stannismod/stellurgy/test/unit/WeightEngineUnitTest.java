package dev.stannismod.stellurgy.test.unit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.util.WeightEngine;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.*;

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

    /** Pins INV-WGT-04 (seedDefaults populates a non-empty material table). */
    @Pins("INV-WGT-04")
    @RedWitness(member = "WeightEngine#defaultMaterials", at = "return m;", replacedBy = "return new LinkedHashMap<>();",
            fails = "default material table must be populated", on = "2026-10-19")
    @Test
    public void seedDefaultsPopulatesMaterialTable() {
        WeightEngine we = WeightEngine.fromJson(CURRENT + "}");
        assertTrue("default material table must be populated", we.materialCount() > MIN_MATERIALS);
    }

    @LegacyWitness(prose = "one break per verdict, 2026-09-30 — {@code WeightEngine#read} at {@code if (root == null || !root.has(\"formatVersion\")} checking no schema"
            + " version fails \"must NOT be read\" (0.1); {@code WeightEngine#retireIncompatibleFile} at {@code if (!current.renameTo(retired))} deleting the file instead of setting it aside"
            + " fails \"must be kept beside the new one\"; {@code WeightEngine#retireIncompatibleFile} at {@code save();}'s save skipped fails \"must write a fresh table\";"
            + " {@code WeightEngine#save} at {@code json.addProperty(\"formatVersion\", FORMAT_VERSION);} not stamping the version fails \"retired again\".")
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

    // ---- The whole file, not one column of it -------------------------------

    /**
     * Regex columns are first-match-wins, so the order a pack writes its patterns in IS the
     * precedence between two patterns that both match. A column deserialised into an unordered map
     * answers a different question after a reload than the one the pack asked.
     * Pins INV-WGT-07 (Every column the file declares survives a save and load, and every regex column keeps its written order).
     */
    @Pins("INV-WGT-07")
    @RedWitness(member = "WeightEngine#read", at = "\"ablationByRegex\", linkedType", replacedBy = "\"ablationByRegex\", mapType",
            fails = "first-match-wins makes pattern order the precedence rule, and ablationByRegex came back reordered",
            on = "2026-10-19")
    @Test
    public void aRegexColumnKeepsThePackSOrderAcrossASave() throws Exception {
        File config = new File(tempFolder.getRoot(), "weights.json");
        // Deliberately not alphabetical and not hash order: three overlapping patterns whose
        // meaning is entirely decided by which one is tried first.
        writeConfig(config, "{\n"
                + "  \"formatVersion\": " + WeightEngine.FORMAT_VERSION + ",\n"
                + "  \"ablationByRegex\": {\"ar:zulu.*\": 1.0, \"ar:.*\": 2.0, \"ar:alpha.*\": 3.0},\n"
                + "  \"toughnessByRegex\": {\"ar:zulu.*\": 1.0, \"ar:.*\": 2.0, \"ar:alpha.*\": 3.0}\n"
                + "}\n");

        new WeightEngine(config.getPath()).save();

        JsonObject saved = readConfig(config);
        List<String> expected = Arrays.asList("ar:zulu.*", "ar:.*", "ar:alpha.*");
        for (String column : new String[]{"ablationByRegex", "toughnessByRegex"}) {
            assertEquals("first-match-wins makes pattern order the precedence rule, and "
                            + column + " came back reordered",
                    expected, keysInOrder(saved.getAsJsonObject(column)));
        }
    }

    /**
     * A config file that cannot be read falls back to the defaults, and the fallback is a clean slate.
     * The columns are read one after another, so a file that breaks part-way has already filled the
     * columns before the break — and a column the fallback forgets keeps those rows, so a broken
     * config silently inherits half of the file it failed to parse.
     *
     * <p>The file below breaks on its LAST field, {@code toughnessFallback}, which is read after every
     * column this asserts on.</p>
     * Pins INV-WGT-08 (A config file that cannot be read leaves no column carrying the previous load's rows).
     */
    @Pins("INV-WGT-08")
    @RedWitness(member = "WeightEngine#seedDefaults", at = "ablationIndividual = new HashMap<>();", replacedBy = "",
            fails = "the fallback must leave no column carrying the broken file's rows", on = "2026-10-19")
    @Test
    public void aConfigThatCannotBeReadLeavesNoColumnBehind() throws Exception {
        File config = new File(tempFolder.getRoot(), "weights.json");
        writeConfig(config, "{\n"
                + "  \"formatVersion\": " + WeightEngine.FORMAT_VERSION + ",\n"
                + "  \"individual\": {\"ar:probe\": 1.5},\n"
                + "  \"toughnessIndividual\": {\"ar:probe\": 6.5},\n"
                + "  \"ablationIndividual\": {\"ar:probe\": 10.5},\n"
                + "  \"ablationByRegex\": {\"ar:probe.*\": 11.5},\n"
                + "  \"toughnessFallback\": \"not a number\"\n"
                + "}\n");

        new WeightEngine(config.getPath()).save();

        JsonObject saved = readConfig(config);
        List<String> survivors = new ArrayList<String>();
        for (String column : new String[]{"individual", "toughnessIndividual",
                "ablationIndividual", "ablationByRegex"}) {
            // Present-and-empty, not merely absent: a column that save() drops altogether would
            // otherwise read as "the fallback cleared it".
            assertTrue("save() must write column " + column + ", or this test cannot see whether "
                    + "the fallback cleared it", saved.has(column));
            if (saved.getAsJsonObject(column).entrySet().size() != 0) {
                survivors.add(column + " -> " + saved.getAsJsonObject(column));
            }
        }
        assertTrue("the fallback must leave no column carrying the broken file's rows, and these "
                + "still do: " + survivors, survivors.isEmpty());
    }

    private static void writeConfig(File config, String json) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(config), StandardCharsets.UTF_8)) {
            w.write(json);
        }
    }

    private static JsonObject readConfig(File config) throws IOException {
        try (Reader r = new InputStreamReader(new FileInputStream(config), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(r, JsonObject.class);
        }
    }

    /** This Gson has no {@code keySet()}; {@code entrySet()} keeps insertion order all the same. */
    private static List<String> keysInOrder(JsonObject column) {
        List<String> keys = new ArrayList<String>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : column.entrySet()) {
            keys.add(e.getKey());
        }
        return keys;
    }
}
