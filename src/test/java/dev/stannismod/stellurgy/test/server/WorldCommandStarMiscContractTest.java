package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.server.ServerRoot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;


import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
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
     * Earth belongs to the Sol that is REGISTERED as star 0 — the same object the star list, the star
     * commands and every "same system" comparison use — not merely to a star that carries id 0.
     *
     * <p>A copy with the right id passes every id check and fails every identity one: a station at
     * Earth was quoted the interstellar price for a planet of its own star, and an edit to star 0 does
     * not reach the star Earth is lit by.</p>
     */
    @LegacyWitness(prose = "2026-09-30, on the code as it stood before the fix — {@code DimensionManager#createAndLoadDimensions}"
            + " at {@code overworldProperties.setStar(sol)} written as"
            + " {@code sol.addPlanet(overworldProperties)}, which lists Earth without binding it:"
            + " \"Earth's star must be the registered star 0 itself, not a copy with its id: {… \"starIsRegistered\":false …}\".")
    @Test
    public void earthBelongsToTheRegisteredSolNotToACopyOfIt() throws Exception {
        Reply earth = Reply.of(read("stellurgytest planet info 0").raw());
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "Earth is bound to star 0: " + earth, earth.integer("starId") == 0);
        assertTrue("Earth's star must be the registered star 0 itself, not a copy with its id: " + earth,
                earth.bool("starIsRegistered"));
    }

    @Test
    public void starGenerateRegistersNewStarObservableInList() throws Exception {
        String beforeList = read("ar star list").raw();
        assertTrue("baseline list must NOT yet contain the test star name",
                !beforeList.contains("GenStarA"));
        arrange("ar star generate GenStarA 8000 50 50");
        String afterList = read("ar star list").raw();
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
     *  known {@code minecraft:plains} biome name (id=1 in vanilla 1.12.2).
     *  The control is a read of the same file BEFORE the command: absent,
     *  so a file found after it was written by it. */
    @RedWitness(member = "DumpBiomesCommand#execute", at = "String fileName = \"./BiomeDump.txt\";",
            replacedBy = "String fileName = \"./BiomeDumpElsewhere.txt\";",
            fails = "BiomeDump.txt must exist after the command", on = "2026-10-12")
    @RedWitness(member = "DumpBiomesCommand#execute", at = "for (Biome biome : Biome.REGISTRY) {",
            replacedBy = "for (Biome biome : java.util.Collections.<Biome>emptyList()) {",
            fails = "dump must contain minecraft:plains", on = "2026-10-12")
    @Test
    public void dumpBiomesWritesBiomeDumpFileWithVanillaPlainsBiome() throws Exception {
        ServerRoot root = harness().root();        requireArranged("BiomeDump.txt must not exist before the command, or its presence after proves"
                + " nothing", !root.exists("BiomeDump.txt"));
        arrange("ar dev dumpBiomes");
        assertTrue("BiomeDump.txt must exist after the command",
                root.exists("BiomeDump.txt"));
        String body = root.readText("BiomeDump.txt");
        assertTrue("dump must contain minecraft:plains — got: " + body,
                body.contains("minecraft:plains"));
    }

    /** The {@code createAutoGennedRecipes}
     *  call that hit Forge's frozen recipe registry was removed from
     *  the runtime reload path; init-time registration handles it
     *  once. XML hot-reload now succeeds. */
    @Test
    public void reloadRecipesEmitsSuccessConfirmationMessage() throws Exception {
        String resp = arrange("ar reloadRecipes").raw();
        assertTrue("reloadRecipes must emit success confirmation — got: " + resp,
                resp.contains("Recipes reloaded"));
        assertTrue("must NOT emit the catch-branch error envelope — got: " + resp,
                !resp.contains("Serious error has occurred"));
    }

    private static final String CUTTING_COUNT = "TileCuttingMachine";

    private int cuttingMachineRecipeCount() throws Exception {
        String summary = read("stellurgytest machine recipes-summary").raw();
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

        String resp = arrange("ar reloadRecipes").raw();
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
