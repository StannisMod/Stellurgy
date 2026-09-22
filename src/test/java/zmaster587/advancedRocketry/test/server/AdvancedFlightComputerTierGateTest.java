package zmaster587.advancedRocketry.test.server;

import org.junit.Test;


import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

/**
 * Tier-2 gate — an Advanced Flight Computer in an assembled structure decides whether the launch
 * pad builds an ordinary rocket or a movable physics ship.
 *
 * <p><b>There is ONE half of that gate now, not two.</b> This class used to describe a fork on
 * whether the physics substrate was present: without it the computer was inert and the build fell
 * back to a rocket, with it the build routed to ship assembly. The substrate is compiled into this
 * jar, so the first branch was unreachable — its two scenarios were silently skipped on every run
 * for months, and the production fork behind them was deleted on 2026-09-22. What is left is the
 * gate that can actually go both ways: an AFC-bearing build assembles a ship and no rocket, and an
 * AFC alone satisfies guidance.</p>
 *
 * <p>What this deliberately does NOT assert: that the ship then fully materialises, is pilotable,
 * or simulates physics. Assembly is asynchronous and largely not headless-verifiable; the
 * observable contract here is the routing decision (rocket vs no-rocket), which is deterministic.</p>
 */
public class AdvancedFlightComputerTierGateTest extends AbstractSharedServerTest {


    private static final String VARIANT = "with-advanced-flight-computer";

    // THE TWO "WITHOUT VS" SCENARIOS ARE DELETED, 2026-09-22, and their own @Ignore said when to do
    // it. Retired in place on 2026-08-21 with this reason: "Retired rather than deleted because the
    // production fallback it describes may still exist, and would then be dead code with no test to
    // say so; that is its own finding, not this file's." That finding was made and acted on today —
    // `TileRocketAssemblingMachine`'s no-substrate fork is gone, along with the other 85 branches on
    // an availability probe for a class compiled into this jar. There is no fallback left for these
    // to describe, so keeping them would pin a behaviour the code no longer has.

    @Test
    public void flightComputerWithVsAssemblesShipNotRocket() throws Exception {
        // Needs Valkyrien Skies on the server classpath (suite run with );
        // skips cleanly otherwise.

        String assemble = assembleFixture(FixtureSite.openAir(0, 1600, 1600), VARIANT);
        // The defining contract of the fork WITH VS: the AFC diverts the build to a
        // ship, so no EntityRocket is spawned on the pad.
        assertTrue("with VS, an AFC-bearing build must not spawn a rocket: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
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
                (Reply.of(assemble).integer("rocketCount") == 0));
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
        int[] bp = RocketFixture.placeAt(site, cmd -> String.join("\n", client().execute(cmd)),
                variant, 2, 10,
                "the craft is built and scanned in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String haystack, String field) {
        return Reply.of(haystack).integer(field);
    }
}
