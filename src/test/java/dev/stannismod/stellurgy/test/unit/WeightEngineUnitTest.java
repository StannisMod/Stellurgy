package dev.stannismod.stellurgy.test.unit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import dev.stannismod.stellurgy.util.WeightEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    // ---- The whole file, not one column of it -------------------------------

    /**
     * A column the engine READS is a column a pack may write, and {@code save()} rewrites the whole
     * file — so a column that is loaded and not saved is a column the pack silently loses the first
     * time anything saves. This walks every column through the file rather than picking one, because
     * the columns that go missing are by definition the ones nobody remembered to add to a list.
     *
     * <p>Two ablation columns were lost exactly this way: read by {@code load()}, absent from
     * {@code save()}, invisible until a pack's hand-written rows evaporated.</p>
     */
    @Test
    public void everyColumnAPackCanWriteSurvivesASave() throws Exception {
        File config = new File(tempFolder.getRoot(), "weights.json");
        writeConfig(config, "{\n"
                    + "  \"individual\": {\"ar:probe\": 1.5},\n"
                    + "  \"byRegex\": {\"ar:probe.*\": 2.5},\n"
                    + "  \"fluids\": {\"ar_probe_fluid\": 0.5},\n"
                    + "  \"materials\": {\"IRON\": 3.5},\n"
                    + "  \"fallback\": 4.5,\n"
                    + "  \"fluidFallback\": 5.5,\n"
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
        assertRow(saved, "fluids", "ar_probe_fluid", 0.5, lost);
        assertRow(saved, "materials", "IRON", 3.5, lost);
        assertScalar(saved, "fallback", 4.5, lost);
        assertScalar(saved, "fluidFallback", 5.5, lost);
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
     */
    @Test
    public void aRegexColumnKeepsThePackSOrderAcrossASave() throws Exception {
        File config = new File(tempFolder.getRoot(), "weights.json");
        // Deliberately not alphabetical and not hash order: three overlapping patterns whose
        // meaning is entirely decided by which one is tried first.
        writeConfig(config, "{\n"
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
     */
    @Test
    public void aConfigThatCannotBeReadLeavesNoColumnBehind() throws Exception {
        File config = new File(tempFolder.getRoot(), "weights.json");
        writeConfig(config, "{\n"
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
