package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code /ar star *}, {@code /ar dumpBiomes},
 * {@code /ar reloadRecipes}.
 *
 * <p>Stars are registered in Stellurgy's
 * {@code DimensionManager.getInstance()} alongside planets; the
 * default star is Sol with id=0, name="Sol", temperature=100 (set in
 * {@code DimensionManager} ctor lines 78-81). Tests assert against
 * that baseline.</p>
 */
public class WorldCommandStarMiscContractTest extends AbstractSharedServerTest {

    /**
     * A constant: a compiled {@code Pattern} is immutable and thread-safe; each use makes its own matcher.
     */
    private static final Pattern TEMP_LINE = Pattern.compile("Temp:\\s*(-?\\d+)");

    @Test
    public void starListIncludesSolAsId0() throws Exception {
        String list = exec("ar star list");
        assertTrue("star list must include Sol — got: " + list,
                list.contains("Star ID: 0") && list.contains("Sol"));
    }

    /**
     * Earth belongs to the Sol that is REGISTERED as star 0 — the same object the star list, the star
     * commands and every "same system" comparison use — not merely to a star that carries id 0.
     *
     * <p>A copy with the right id passes every id check and fails every identity one: a station at
     * Earth was quoted the interstellar price for a planet of its own star, and an edit to star 0 does
     * not reach the star Earth is lit by.</p>
     *
     * <p>red-witnessed: 2026-09-30, on the code as it stood before the fix — {@code DimensionManager#createAndLoadDimensions}
     * at {@code overworldProperties.setStar(sol)} written as
     * {@code sol.addPlanet(overworldProperties)}, which lists Earth without binding it:
     * "Earth's star must be the registered star 0 itself, not a copy with its id: {… "starIsRegistered":false …}".</p>
     */
    @Test
    public void earthBelongsToTheRegisteredSolNotToACopyOfIt() throws Exception {
        Reply earth = Reply.of(exec("stellurgytest planet info 0"));
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "Earth is bound to star 0: " + earth, earth.integer("starId") == 0);
        assertTrue("Earth's star must be the registered star 0 itself, not a copy with its id: " + earth,
                earth.bool("starIsRegistered"));
    }

    @Test
    public void starGetTempEchoesSolBaselineTemperature() throws Exception {
        String resp = exec("ar star get temp 0");
        Matcher m = TEMP_LINE.matcher(resp);
        assertTrue("must include a Temp: line — got: " + resp, m.find());
        assertEquals("Sol baseline temperature per DimensionManager ctor",
                100, Integer.parseInt(m.group(1)));
    }

    @Test
    public void starGenerateRegistersNewStarObservableInList() throws Exception {
        String beforeList = exec("ar star list");
        assertTrue("baseline list must NOT yet contain the test star name",
                !beforeList.contains("GenStarA"));
        exec("ar star generate GenStarA 8000 50 50");
        String afterList = exec("ar star list");
        // No `star delete` exists in production — the new star persists for
        // the rest of the shared harness. That's fine because (a) the name
        // is unique to this test and (b) subsequent tests don't enumerate
        // by count, only by-name presence.
        assertTrue("star list must include the generated star name — got: "
                        + afterList,
                afterList.contains("GenStarA"));
    }

    /** {@code /ar dumpBiomes} writes {@code ./BiomeDump.txt} relative to
     *  the server JVM's CWD, which is the harness workdir. The file's
     *  first column is the vanilla biome id; pin its presence + the
     *  known {@code minecraft:plains} biome name (id=1 in vanilla 1.12.2). */
    @Test
    public void dumpBiomesWritesBiomeDumpFileWithVanillaPlainsBiome() throws Exception {
        Path root = harness().root();
        Path dump = root.resolve("BiomeDump.txt");
        Files.deleteIfExists(dump);
        exec("ar dev dumpBiomes");
        assertTrue("BiomeDump.txt must exist after the command",
                Files.exists(dump));
        String body = new String(Files.readAllBytes(dump));
        assertTrue("dump must contain minecraft:plains — got: " + body,
                body.contains("minecraft:plains"));
    }

    /** The {@code createAutoGennedRecipes}
     *  call that hit Forge's frozen recipe registry was removed from
     *  the runtime reload path; init-time registration handles it
     *  once. XML hot-reload now succeeds. */
    @Test
    public void reloadRecipesEmitsSuccessConfirmationMessage() throws Exception {
        String resp = exec("ar reloadRecipes");
        assertTrue("reloadRecipes must emit success confirmation — got: " + resp,
                resp.contains("Recipes reloaded"));
        assertTrue("must NOT emit the catch-branch error envelope — got: " + resp,
                !resp.contains("Serious error has occurred"));
    }

    private static final String CUTTING_COUNT = "TileCuttingMachine";

    private int cuttingMachineRecipeCount() throws Exception {
        String summary = exec("stellurgytest machine recipes-summary");
        Reply mReply = Reply.of(summary);
        assertTrue("recipes-summary must include TileCuttingMachine count: "
                + summary, mReply.has(CUTTING_COUNT));
        return Integer.parseInt(mReply.text(CUTTING_COUNT));
    }

    /** Stronger pin: not just "chat envelope says success" but
     *  "the recipe registry actually has recipes afterwards". The reload
     *  pipeline is {@code clearAllMachineRecipes} &rarr;
     *  {@code registerAllMachineRecipes} (re-adds programmatic recipes)
     *  &rarr; {@code registerXMLRecipes} (re-loads XML from
     *  {@code config/<machine>.xml}). The {@code TileCuttingMachine} has
     *  several recipes registered at init via both paths; if reload
     *  silently drops them (e.g. clear without successful re-register),
     *  this assertion fires.
     *
     *  <p>Pin shape: post-reload count must be {@code >= pre-reload count}
     *  AND {@code > 0}. The "==" form would be stricter but is fragile
     *  against future additions that register recipes lazily before the
     *  reload but not after — the "no recipes lost" semantic is the
     *  actual contract.</p> */
    @Test
    public void reloadRecipesPreservesProgrammaticAndXmlRecipesForCuttingMachine()
            throws Exception {
        int before = cuttingMachineRecipeCount();
        assertTrue("pre-condition: TileCuttingMachine must have recipes "
                + "registered at init (got " + before + ")", before > 0);

        String resp = exec("ar reloadRecipes");
        assertTrue("reload must succeed: " + resp,
                resp.contains("Recipes reloaded"));

        int after = cuttingMachineRecipeCount();
        assertTrue("post-reload count " + after + " must be >= pre-reload "
                        + "count " + before + " — no recipes silently dropped",
                after >= before);
        assertTrue("post-reload count must remain > 0 (reload must actually "
                        + "re-register, not just clear)",
                after > 0);
    }
}
