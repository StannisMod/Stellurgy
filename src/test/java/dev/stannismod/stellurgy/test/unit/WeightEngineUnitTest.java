package dev.stannismod.stellurgy.test.unit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
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
    @Test
    public void seedDefaultsPopulatesMaterialTable() {
        WeightEngine we = WeightEngine.fromJson(CURRENT + "}");
        assertTrue("default material table must be populated", we.materialCount() > MIN_MATERIALS);
    }

    /** Pins INV-WGT-02 (an individual override survives a JSON save and load). */
    @Pins("INV-WGT-02")
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
                + "\"fallback\":0.1,\"fluidFallbackDensity\":0.001}").getBytes(StandardCharsets.UTF_8));

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

    /**
     * INV-WGT-09: a millibucket is a litre of the condensed form, so {@code amount} millibuckets weigh
     * {@code density/1000 · amount} kg — the row's density first, Forge's only when positive (it makes
     * gases negative), else the table's fallback.
     *
     * <p>Does not see: the once-per-fluid announcement of the fallback (a log line), and the rocket's and
     * the hull's call sites, which reach this through {@code getWeight(Fluid, …)} and the tile reader.</p>
     */
    @Pins("INV-WGT-09")
    @Regions(of = "INV-WGT-09", value = {"row", "positive Forge density", "non-positive Forge density", "fallback"})
    @RedWitness(member = "WeightEngine#densityOf", at = "if (fluid.getDensity() > 0) {", replacedBy = "if (fluid.getDensity() != 0) {",
            fails = "INV-WGT-09 non-positive Forge density", on = "2026-10-17")
    @Test
    public void aMillibucketWeighsItsDensityOverAThousand() {
        WeightEngine we = WeightEngine.fromJson(CURRENT + ",\"fluids\":{\"w_row\":{\"density\":70.8}},"
                + "\"fluidFallbackDensity\":640.0}");
        net.minecraft.util.ResourceLocation still = new net.minecraft.util.ResourceLocation("stellurgy", "w_still");
        net.minecraftforge.fluids.Fluid row = new net.minecraftforge.fluids.Fluid("w_row", still, still).setDensity(5000);
        net.minecraftforge.fluids.Fluid forge = new net.minecraftforge.fluids.Fluid("w_forge", still, still).setDensity(1021);
        net.minecraftforge.fluids.Fluid gas = new net.minecraftforge.fluids.Fluid("w_gas", still, still).setDensity(-1000);
        net.minecraftforge.fluids.Fluid zero = new net.minecraftforge.fluids.Fluid("w_zero", still, still).setDensity(0);
        int amount = 1234;

        assertEquals("INV-WGT-09 row: the row's density, not Forge's 5000", 70.8 / 1000 * amount,
                we.getWeight(row, amount), 1e-3);
        assertEquals("INV-WGT-09 positive Forge density", 1021.0 / 1000 * amount, we.getWeight(forge, amount), 1e-3);
        assertEquals("INV-WGT-09 non-positive Forge density falls to the table's fallback, never a negative mass",
                640.0 / 1000 * amount, we.getWeight(gas, amount), 1e-3);
        assertEquals("INV-WGT-09 fallback for a zero Forge density", 640.0 / 1000 * amount, we.getWeight(zero, amount), 1e-3);
    }

    // ---- The whole file, not one column of it -------------------------------

    /**
     * A column the engine READS is a column a pack may write, and {@code save()} rewrites the whole
     * file — so a column that is loaded and not saved is a column the pack silently loses the first
     * time anything saves. This walks every column through the file rather than picking one, because
     * the columns that go missing are by definition the ones nobody remembered to add to a list.
     *
     * <p>Two ablation columns were lost exactly this way: read by {@code load()}, absent from
     * {@code save()}, invisible until a pack's hand-written rows evaporated.</p>
     * Pins INV-WGT-07 (Every column the file declares survives a save→load cycle carrying its value, and every regex column comes back in the order it).
     */
    @Pins("INV-WGT-07")
    @Test
    public void everyColumnAPackCanWriteSurvivesASave() throws Exception {
        File config = new File(tempFolder.getRoot(), "weights.json");
        writeConfig(config, "{\n"
                    + "  \"formatVersion\": " + WeightEngine.FORMAT_VERSION + ",\n"
                    + "  \"individual\": {\"ar:probe\": 1.5},\n"
                    + "  \"byRegex\": {\"ar:probe.*\": 2.5},\n"
                    + "  \"fluids\": {\"ar_probe_fluid\": {\"density\": 0.5, \"molarMass\": 12.5,"
                    + " \"monopropellantExhaustVelocity\": 13.5}},\n"
                    + "  \"propellantPairs\": [{\"fuel\": \"ar_f\", \"oxidizer\": \"ar_o\", \"exhaustVelocity\": 14.5,"
                    + " \"mixtureRatio\": 15.5}],\n"
                    + "  \"materials\": {\"IRON\": 3.5},\n"
                    + "  \"fallback\": 4.5,\n"
                    + "  \"fluidFallbackDensity\": 5.5,\n"
                    + "  \"toughnessIndividual\": {\"ar:probe\": 6.5},\n"
                    + "  \"toughnessByRegex\": {\"ar:probe.*\": 7.5},\n"
                    + "  \"toughnessMaterials\": {\"IRON\": 8.5},\n"
                    + "  \"toughnessFallback\": 9.5,\n"
                    + "  \"ablationIndividual\": {\"ar:probe\": 10.5},\n"
                    + "  \"ablationByRegex\": {\"ar:probe.*\": 11.5}\n"
                    + "}\n");

        new WeightEngine(config.getPath()).save();

        JsonObject saved = readConfig(config);
        List<String> lost = new ArrayList<String>();
        assertRow(saved, "individual", "ar:probe", 1.5, lost);
        assertRow(saved, "byRegex", "ar:probe.*", 2.5, lost);
        JsonObject fluids = saved.has("fluids") ? saved.getAsJsonObject("fluids") : new JsonObject();
        assertRow(fluids, "ar_probe_fluid", "density", 0.5, lost);
        assertRow(fluids, "ar_probe_fluid", "molarMass", 12.5, lost);
        assertRow(fluids, "ar_probe_fluid", "monopropellantExhaustVelocity", 13.5, lost);
        JsonObject pair = saved.has("propellantPairs") && saved.getAsJsonArray("propellantPairs").size() == 1
                ? saved.getAsJsonArray("propellantPairs").get(0).getAsJsonObject() : new JsonObject();
        assertScalar(pair, "exhaustVelocity", 14.5, lost);
        assertScalar(pair, "mixtureRatio", 15.5, lost);
        assertRow(saved, "materials", "IRON", 3.5, lost);
        assertScalar(saved, "fallback", 4.5, lost);
        assertScalar(saved, "fluidFallbackDensity", 5.5, lost);
        assertRow(saved, "toughnessIndividual", "ar:probe", 6.5, lost);
        assertRow(saved, "toughnessByRegex", "ar:probe.*", 7.5, lost);
        assertRow(saved, "toughnessMaterials", "IRON", 8.5, lost);
        assertScalar(saved, "toughnessFallback", 9.5, lost);
        assertRow(saved, "ablationIndividual", "ar:probe", 10.5, lost);
        assertRow(saved, "ablationByRegex", "ar:probe.*", 11.5, lost);

        assertTrue("these hand-written config values did not survive one save/load cycle, so a "
                + "pack that edits them loses them the first time the game writes the file: "
                + lost, lost.isEmpty());
    }

    /**
     * Regex columns are first-match-wins, so the order a pack writes its patterns in IS the
     * precedence between two patterns that both match. A column deserialised into an unordered map
     * answers a different question after a reload than the one the pack asked.
     * Pins INV-WGT-07 (Every column the file declares survives a save and load, and every regex column keeps its written order).
     */
    @Pins("INV-WGT-07")
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
     * column this asserts on. {@link #everyColumnAPackCanWriteSurvivesASave} is the control: the same
     * rows in a file that parses are kept.</p>
     * Pins INV-WGT-08 (A config file that cannot be read leaves no column carrying the previous load's rows).
     */
    @Pins("INV-WGT-08")
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

    private static void assertRow(JsonObject saved, String column, String key, double value,
                                  List<String> lost) {
        if (!saved.has(column) || !saved.getAsJsonObject(column).has(key)
                || Math.abs(saved.getAsJsonObject(column).get(key).getAsDouble() - value) > 1e-9) {
            lost.add(column + "[" + key + "]");
        }
    }

    private static void assertScalar(JsonObject saved, String key, double value, List<String> lost) {
        if (!saved.has(key) || Math.abs(saved.get(key).getAsDouble() - value) > 1e-9) {
            lost.add(key);
        }
    }
}
