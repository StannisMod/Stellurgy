package dev.stannismod.stellurgy.test.unit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterial;
import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterials;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The material table a player can edit, read back after the mod has grown.
 *
 * <p>The contract: a row the file holds keeps the file's values, and a material the file has never
 * heard of still resolves, with its shipped values, and is written into the file so the player can
 * see and edit it. A file written by an older version must not hide what was added since.</p>
 *
 * <p>The file is one this test owns, in a folder of its own: {@link ThermalMaterials#load} reads the
 * path it is given, so the merge is the same decision wherever the file lies, and the install's own
 * table is never touched.</p>
 */
public class ThermalTableFileTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * <p>The shipped row is read the way the game gets it on a fresh install - a load with no file -
     * so the expected values come from production's own table and none of them is typed here.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, each in {@code ThermalMaterials#read}.
     * RESOLVES — at {@code parsed.put(shipped.getKey(), shipped.getValue());} removed, so a missing
     * shipped row never enters the table: "a material the file predates must still resolve". FILE
     * WINS — at {@code if (!parsed.containsKey(shipped.getKey()))} made always to hold, so the shipped
     * row goes over the file's own: "a row the file holds keeps the file's density
     * expected:&lt;7875&gt; but was:&lt;7874&gt;". WRITTEN — at {@code save(file, parsed);} removed: "the added material must be written into the file
     * … {"materials":{"iron":…}}". The two premises at its head are arrangements and are not
     * witnessed.</p>
     */
    @Test
    public void aTableWrittenBeforeAMaterialShippedStillKnowsIt() throws Exception {
        Path table = folder.getRoot().toPath().resolve("thermalMaterials.json");
        ThermalMaterials fresh = ThermalMaterials.load(table.toString());
        ThermalMaterial shippedWood = fresh.byName("wood");
        ThermalMaterial shippedIron = fresh.byName("iron");
        assertNotNull("premise: a fresh install must ship wood", shippedWood);
        assertNotNull("premise: and iron", shippedIron);

        // An older file: iron only, with values nobody ships, so "the file won" is distinguishable
        // from "the shipped row won".
        int editedDensity = shippedIron.densityKgPerCubicMetre() + 1;
        int editedHeat = shippedIron.specificHeatJoulesPerKgKelvin() + 1;
        int editedCeiling = shippedIron.ceilingKelvin() + 1;
        Files.write(table, ("{\"materials\":{\"iron\":{\"density\":" + editedDensity
                + ",\"specificHeat\":" + editedHeat + ",\"ceilingKelvin\":" + editedCeiling + "}}}")
                .getBytes(StandardCharsets.UTF_8));
        ThermalMaterials older = ThermalMaterials.load(table.toString());

        ThermalMaterial wood = older.byName("wood");
        assertNotNull("a material the file predates must still resolve", wood);
        assertEquals("and with its shipped density", shippedWood.densityKgPerCubicMetre(),
                wood.densityKgPerCubicMetre());
        assertEquals("shipped specific heat", shippedWood.specificHeatJoulesPerKgKelvin(),
                wood.specificHeatJoulesPerKgKelvin());
        assertEquals("shipped ceiling", shippedWood.ceilingKelvin(), wood.ceilingKelvin());

        ThermalMaterial iron = older.byName("iron");
        assertEquals("a row the file holds keeps the file's density", editedDensity,
                iron.densityKgPerCubicMetre());
        assertEquals("the file's specific heat", editedHeat, iron.specificHeatJoulesPerKgKelvin());
        assertEquals("the file's ceiling", editedCeiling, iron.ceilingKelvin());

        String onDisk = new String(Files.readAllBytes(table), StandardCharsets.UTF_8);
        JsonObject rows = new JsonParser().parse(onDisk).getAsJsonObject().getAsJsonObject("materials");
        assertTrue("the added material must be written into the file for the player to edit: "
                + onDisk, rows.has("wood"));
    }

    /**
     * A file that cannot be read as a table is refused, and the refusal names the file. The shipped
     * table is not quietly used in its place: a pack author whose edit has a stray comma would
     * otherwise run on every shipped value while believing his own were in force.
     *
     * <p>red-witnessed: with {@code ThermalMaterials#read} at {@code throw refusal(file,
     * String.valueOf(unreadable.getMessage()));} replaced by {@code return defaults();} — the shape
     * this file had before — "the table must be refused, but it loaded with 24 materials",
     * 2026-10-05. NAMES THE FILE — {@code ThermalMaterials#refusal} at {@code return new
     * IllegalStateException(file + " is not a thermal materials table: " + why} building its message
     * without the file: "the refusal must name the file the pack has to fix: the file is not a
     * thermal materials table: …", 2026-10-05.</p>
     */
    @Test
    public void aFileThatIsNotATableIsRefusedByName() throws Exception {
        Path table = folder.getRoot().toPath().resolve("thermalMaterials.json");
        Files.write(table, "{\"materials\":{\"iron\":{\"density\":7874,}".getBytes(StandardCharsets.UTF_8));

        IllegalStateException refusal = loadExpectingRefusal(table);
        assertTrue("the refusal must name the file the pack has to fix: " + refusal.getMessage(),
                refusal.getMessage().contains(table.toString()));
    }

    /**
     * A row missing one of its three numbers is refused, naming the row and the number. Read as zero
     * it would be a substance that holds no heat or melts at no temperature, which is a stand-in
     * for the value the author meant, not his value.
     *
     * <p>red-witnessed: with {@code ThermalMaterials#wholeNumber} at {@code throw refusal(file,
     * "material \"" + material + "\" has no number \"" + field + "\"");} replaced by {@code return
     * 0;} — the default the row had before — "the table must be refused, but it loaded with 24
     * materials", 2026-10-05. NAMES THE ROW — the same line refusing with "a material has no number":
     * "the refusal must name the row: …", 2026-10-05. NAMES THE FIELD — the same line refusing with
     * the row but without the field: "and the number it lacks: …", 2026-10-05.</p>
     */
    @Test
    public void aRowMissingANumberIsRefusedByRowAndField() throws Exception {
        Path table = folder.getRoot().toPath().resolve("thermalMaterials.json");
        Files.write(table, ("{\"materials\":{\"iron\":{\"density\":7874,\"specificHeat\":449,"
                + "\"ceilingKevlin\":1811}}}").getBytes(StandardCharsets.UTF_8));

        IllegalStateException refusal = loadExpectingRefusal(table);
        assertTrue("the refusal must name the row: " + refusal.getMessage(),
                refusal.getMessage().contains("iron"));
        assertTrue("and the number it lacks: " + refusal.getMessage(),
                refusal.getMessage().contains("ceilingKelvin"));
    }

    private static IllegalStateException loadExpectingRefusal(Path table) {
        ThermalMaterials loaded;
        try {
            loaded = ThermalMaterials.load(table.toString());
        } catch (IllegalStateException refusal) {
            return refusal;
        }
        throw new AssertionError("the table must be refused, but it loaded with "
                + loaded.all().size() + " materials");
    }
}
