package dev.stannismod.stellurgy.test.unit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterial;
import dev.stannismod.stellurgy.subsystem.heat.ThermalMaterials;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
 * <p>The subject is the one table the game reads, at the one path it reads it from, so this class
 * works on that file and puts back whatever it found there, byte for byte, before it lets go.</p>
 */
public class ThermalTableFileTest {

    /** Where {@link ThermalMaterials#INSTANCE} reads and writes its table, relative to the run. */
    private static final Path TABLE = Paths.get("config", "advRocketry", "thermalMaterials.json");

    private byte[] found;

    @Before
    public void keepWhatIsThere() throws Exception {
        found = Files.exists(TABLE) ? Files.readAllBytes(TABLE) : null;
    }

    @After
    public void putItBack() throws Exception {
        if (found != null) {
            Files.write(TABLE, found);
        } else {
            Files.deleteIfExists(TABLE);
        }
        ThermalMaterials.INSTANCE.load();
    }

    /**
     * <p>The shipped row is read the way the game gets it on a fresh install - a load with no file -
     * so the expected values come from production's own table and none of them is typed here.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. RESOLVES — {@code ThermalMaterials:378}
     * no longer putting a missing shipped row into the table: "a material the file predates must
     * still resolve". FILE WINS — the shipped row put over the file's own: "a row the file holds
     * keeps the file's density expected:&lt;7875&gt; but was:&lt;7874&gt;". WRITTEN — the
     * {@code save()} after the merge removed: "the added material must be written into the file
     * … {"materials":{"iron":…}}". The two premises at its head are arrangements and are not
     * witnessed.</p>
     */
    @Test
    public void aTableWrittenBeforeAMaterialShippedStillKnowsIt() throws Exception {
        Files.deleteIfExists(TABLE);
        ThermalMaterials.INSTANCE.load();
        ThermalMaterial shippedWood = ThermalMaterials.INSTANCE.byName("wood");
        ThermalMaterial shippedIron = ThermalMaterials.INSTANCE.byName("iron");
        assertNotNull("premise: a fresh install must ship wood", shippedWood);
        assertNotNull("premise: and iron", shippedIron);

        // An older file: iron only, with values nobody ships, so "the file won" is distinguishable
        // from "the shipped row won".
        int editedDensity = shippedIron.densityKgPerCubicMetre() + 1;
        int editedHeat = shippedIron.specificHeatJoulesPerKgKelvin() + 1;
        int editedCeiling = shippedIron.ceilingKelvin() + 1;
        Files.write(TABLE, ("{\"materials\":{\"iron\":{\"density\":" + editedDensity
                + ",\"specificHeat\":" + editedHeat + ",\"ceilingKelvin\":" + editedCeiling + "}}}")
                .getBytes(StandardCharsets.UTF_8));
        ThermalMaterials.INSTANCE.load();

        ThermalMaterial wood = ThermalMaterials.INSTANCE.byName("wood");
        assertNotNull("a material the file predates must still resolve", wood);
        assertEquals("and with its shipped density", shippedWood.densityKgPerCubicMetre(),
                wood.densityKgPerCubicMetre());
        assertEquals("shipped specific heat", shippedWood.specificHeatJoulesPerKgKelvin(),
                wood.specificHeatJoulesPerKgKelvin());
        assertEquals("shipped ceiling", shippedWood.ceilingKelvin(), wood.ceilingKelvin());

        ThermalMaterial iron = ThermalMaterials.INSTANCE.byName("iron");
        assertEquals("a row the file holds keeps the file's density", editedDensity,
                iron.densityKgPerCubicMetre());
        assertEquals("the file's specific heat", editedHeat, iron.specificHeatJoulesPerKgKelvin());
        assertEquals("the file's ceiling", editedCeiling, iron.ceilingKelvin());

        String onDisk = new String(Files.readAllBytes(TABLE), StandardCharsets.UTF_8);
        JsonObject rows = new JsonParser().parse(onDisk).getAsJsonObject().getAsJsonObject("materials");
        assertTrue("the added material must be written into the file for the player to edit: "
                + onDisk, rows.has("wood"));
    }
}
