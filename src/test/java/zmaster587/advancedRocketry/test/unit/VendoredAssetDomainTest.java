package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A vendored mod's blocks register under THIS mod's domain, while its assets stay under its own.
 *
 * <p><b>This pins a bug that is still open, and it is written so that fixing the bug turns it
 * red.</b> The vendored physics mod's blocks reach the registry through a base class that calls
 * {@code setRegistryName(name)} with a bare string, which Forge resolves against the ACTIVE mod
 * container. Vendored means there is no container of its own, so the active one is this mod — and
 * the client then looks for {@code advancedrocketry:blockstates/captains_chair.json} while the file
 * that exists is {@code valkyrienskies/…/assets/valkyrienskies/blockstates/captains_chair.json}.
 * Every one of those blocks draws as the purple-and-black missing model, the captain's chair
 * included, which is the block the whole helm mechanic is entered through.</p>
 *
 * <p><b>Why it asserts the WRONG state.</b> The fix is a decision that has not been taken — an
 * explicit domain at registration, or a moved asset root — so a test asserting the right state
 * would be a red in every gate from now until somebody makes it. Instead this records what is true
 * today. <b>When the bug is fixed this test FAILS, and that failure is the signal to delete it</b>,
 * not to loosen it.</p>
 *
 * <p><b>The list is not written down here.</b> It is read off the vendored asset tree at run time,
 * so a vendored block added tomorrow is covered without anybody remembering to add a row — a
 * hand-kept list of this kind only ever becomes an understatement, and it would still look
 * exhaustive while doing it.</p>
 *
 * <p>Pure file IO: no Minecraft, no registry, no harness.</p>
 */
public class VendoredAssetDomainTest {

    /** Where the vendored mod keeps the assets it was written with. */
    private static final String VENDORED_ASSETS =
            "valkyrienskies/src/main/resources/assets/valkyrienskies";
    /** Where the client will actually look, because that is the domain the blocks register into. */
    private static final String HOST_ASSETS = "src/main/resources/assets/advancedrocketry";

    private static final FilenameFilter JSON = (dir, name) -> name.endsWith(".json");

    /**
     * The repository root, found by walking up from the working directory until the vendored tree
     * is underfoot. Gradle runs tests from the project directory, but a runner that does not is
     * what turns a path assumption into a green test measuring nothing.
     */
    private static File repoRoot() {
        File here = new File(".").getAbsoluteFile();
        while (here != null) {
            if (new File(here, VENDORED_ASSETS).isDirectory()) {
                return here;
            }
            here = here.getParentFile();
        }
        return null;
    }

    private static List<String> namesIn(File dir) {
        // The null case is a refusal rather than an empty answer: an unreadable directory must not
        // read as "this mod ships no blockstates", which would make every comparison below vacuous
        // and green.
        File[] files = dir.listFiles(JSON);
        if (files == null) {
            fail("could not list " + dir + " — an unreadable asset directory is not an empty one");
        }
        List<String> names = new ArrayList<>();
        for (File f : files) {
            names.add(f.getName());
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Every vendored blockstate is absent from the domain its block registers into.
     *
     * <p>Both halves are asserted. That the vendored file EXISTS is what makes the second half mean
     * something: "no file at the host domain" is equally true of a block nobody ever drew.</p>
     */
    @Test
    public void everyVendoredBlockstateIsMissingFromTheDomainItsBlockRegistersInto() {
        File root = repoRoot();
        assertTrue("the vendored asset tree was not found above " + new File(".").getAbsolutePath()
                + " — this test measures files and cannot run without them", root != null);

        File vendored = new File(root, VENDORED_ASSETS + "/blockstates");
        List<String> vendoredNames = namesIn(vendored);
        assertTrue("the vendored mod must ship at least one blockstate, or the comparison below is"
                + " vacuous; looked in " + vendored, !vendoredNames.isEmpty());

        List<String> alsoAtHost = new ArrayList<>();
        for (String name : vendoredNames) {
            if (new File(root, HOST_ASSETS + "/blockstates/" + name).isFile()) {
                alsoAtHost.add(name);
            }
        }

        assertTrue("THE MISSING-MODEL BUG IS FIXED for " + alsoAtHost + " — the vendored"
                + " blockstate(s) now also exist under the advancedrocketry domain, which is what"
                + " the client looks for. Delete this test rather than narrowing it: it exists to"
                + " record a bug that was open when it was written, and its job ends when the bug"
                + " does. Vendored blockstates seen: " + vendoredNames, alsoAtHost.isEmpty());
    }

    /** The same for the vendored ITEM model, by the same registration route. */
    @Test
    public void theVendoredShipTrackerModelIsMissingFromTheDomainItsItemRegistersInto() {
        File root = repoRoot();
        assertTrue("the vendored asset tree was not found above " + new File(".").getAbsolutePath(),
                root != null);

        File vendoredModel = new File(root, VENDORED_ASSETS + "/models/item/vs_ship_tracker.json");
        assertTrue("the vendored ship-tracker model must exist, or this test is about nothing: "
                + vendoredModel, vendoredModel.isFile());

        File hostModel = new File(root, HOST_ASSETS + "/models/item/vs_ship_tracker.json");
        assertTrue("THE MISSING-MODEL BUG IS FIXED for vs_ship_tracker — delete this test rather"
                + " than narrowing it", !hostModel.isFile());
    }
}
