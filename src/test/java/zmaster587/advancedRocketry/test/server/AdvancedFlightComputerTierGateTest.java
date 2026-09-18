package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Tier-2 gate — an Advanced Flight Computer in an assembled structure decides
 * whether the launch pad builds an ordinary rocket or a movable Valkyrien Skies
 * ship. The two @Test methods pin the two halves of that gate, each gated on the
 * server's real VS presence so exactly one runs per suite configuration:
 *
 * <ul>
 *   <li><b>no VS</b> (default suite) — the computer is inert; an AFC-bearing build
 *       still assembles a normal {@code EntityRocket}, with the computer captured
 *       inside it. This is the soft-dependency safety contract.</li>
 *   <li><b>with VS</b> (suite run with) — the fork routes to VS
 *       ship assembly, so NO rocket is spawned.</li>
 * </ul>
 *
 * <p>What the harness deliberately does NOT assert: that the VS ship then fully
 * materialises, is pilotable, or simulates physics. VS assembly is async on a
 * physics thread and largely not headless-verifiable; the observable contract here
 * is the routing decision (rocket vs no-rocket), which is deterministic.</p>
 */
public class AdvancedFlightComputerTierGateTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";

    private static final String VARIANT = "with-advanced-flight-computer";

    @Test
    @Ignore("UNREACHABLE PREMISE, retired in place 2026-08-21. It pins what an Advanced Flight "
            + "Computer does when Valkyrien Skies is ABSENT, and VS cannot be absent: it is vendored "
            + "into AR's own main source set and its classes ship inside the mod. The guard used to "
            + "be Assume.assumeFalse(serverHasVs()), which SKIPPED this on every run since VS was "
            + "vendored - dead coverage that read as coverage. Retired rather than deleted because "
            + "the production fallback it describes may still exist, and would then be dead code with "
            + "no test to say so; that is its own finding, not this file's.")
    public void flightComputerWithoutVsBuildsInertRocket() throws Exception {
        String assemble = assembleFixture(FixtureSite.openAir(0, 1200, 1200), VARIANT);
        // A rocket WAS built (fallback taken) ...
        assertTrue("expected exactly one rocket from the fallback path: " + assemble,
                (Reply.of(assemble).integerOr("rocketCount", Integer.MIN_VALUE) == 1));
        int entityId = extractInt(assemble, "entityId");
        assertTrue("assemble did not report a rocket entity id: " + assemble, entityId >= 0);

        RocketInfo rocket = RocketInfo.of(String.join("\n",
                client().execute("artest rocket info " + entityId)));
        // ... it has a storage chunk (real EntityRocket) ...
        assertTrue("expected a normal rocket with a storage chunk: " + rocket.raw(),
                rocket.hasStorage);
        // ... and the Advanced Flight Computer rode along inside it, proving the
        // block was present yet did NOT reroute the build away from the rocket path.
        assertTrue("advanced flight computer should be captured in the built rocket: "
                + rocket.raw(), rocket.advancedFlightComputerPresent);
    }

    @Test
    public void flightComputerWithVsAssemblesShipNotRocket() throws Exception {
        // Needs Valkyrien Skies on the server classpath (suite run with );
        // skips cleanly otherwise.

        String assemble = assembleFixture(FixtureSite.openAir(0, 1600, 1600), VARIANT);
        // The defining contract of the fork WITH VS: the AFC diverts the build to a
        // ship, so no EntityRocket is spawned on the pad.
        assertTrue("with VS, an AFC-bearing build must not spawn a rocket: " + assemble,
                (Reply.of(assemble).integerOr("rocketCount", Integer.MIN_VALUE) == 0));
    }

    @Test
    public void flightComputerAloneSatisfiesGuidanceAndAssemblesShipWithVs() throws Exception {
        // The build a player actually makes: an Advanced Flight Computer and NO guidance
        // computer. The AFC IS the tier-2 ship's flight computer, so it must satisfy the
        // "computer with instructions" requirement — the scan must not reject it as NOGUIDANCE,
        // and with VS the build routes to a ship (no rocket).

        String assemble = assembleFixture(FixtureSite.openAir(0, 2000, 2000), "advanced-flight-computer-only");
        assertTrue("an AFC alone must satisfy the guidance requirement and route to a ship "
                        + "(no rocket): " + assemble,
                (Reply.of(assemble).integerOr("rocketCount", Integer.MIN_VALUE) == 0));
    }

    @Test
    @Ignore("UNREACHABLE PREMISE, retired in place 2026-08-21 - see the sibling without-VS test. "
            + "Valkyrien Skies is vendored and mandatory, so this scenario has been silently skipped "
            + "on every run rather than failing. The mirror gate it describes: without VS the AFC is "
            + "inert, the build falls back to a rocket, and a rocket still needs a guidance computer.")
    public void flightComputerAloneWithoutVsStillRequiresGuidance() throws Exception {
        String coords = placeFixture(FixtureSite.openAir(0, 2000, 2000), "advanced-flight-computer-only");
        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("without VS, an AFC alone must not satisfy guidance — scan must be NOGUIDANCE: "
                        + assemble,
                "NOGUIDANCE".equals(Reply.of(assemble).text("status")));
    }

    /**
     * Place the fixture on a pad and run scan+assemble; returns the raw
     * {@code /artest rocket assemble} JSON (carries {@code status}, {@code entityId}
     * and {@code rocketCount}). See {@code RocketAssemblySmokeTest} for the
     * chunk-warmup / pre-clear rationale.
     */
    private String assembleFixture(FixtureSite site, String variant) throws Exception {
        String coords = placeFixture(site, variant);
        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("assemble (" + variant + ") failed: " + assemble, Reply.of(assemble).ok());
        return assemble;
    }

    /**
     * Place the fixture on a pad (warmup + pre-clear + spawn) WITHOUT assembling; returns the
     * builder position as a {@code "bx by bz"} string. Split from {@link #assembleFixture} so a
     * test that expects the scan to FAIL (e.g. NOGUIDANCE) can drive {@code rocket assemble}
     * directly, since that probe returns an error (not {@code ok:true}) on a non-SUCCESS scan.
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        String warmup = String.join("\n", client().execute(
                "artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + warmup, Reply.of(warmup).ok());

        // FIRST link: the volume this craft is built in is EMPTY. The site stands in open air, so
        // this ASSERTS rather than digs - anything standing here means the arrangement is wrong,
        // and it is said now instead of arriving many links later as a scan that found nothing.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft is built and scanned in this volume");

        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        assertTrue("fixture (" + variant + ") failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp != null);
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String haystack, String field) {
        return Reply.of(haystack).integerOr(field, -1);
    }
}
