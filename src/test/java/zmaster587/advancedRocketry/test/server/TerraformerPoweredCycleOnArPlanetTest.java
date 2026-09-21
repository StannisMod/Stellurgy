package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.DimInfo;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * terraformer powered cycle on an AR-native planet.
 *
 * <p>Pins the <b>native-dim branch</b> of {@code TileAtmosphereTerraformer
 * .processComplete()}'s gate:</p>
 *
 * <pre>{@code
 *     (WorldProviderPlanet && isNativeDimension) || allowTerraformNonAR
 * }</pre>
 *
 * <p>Generates a fresh AR planet via {@code /ar planet generate}, builds
 * the 17×17 multiblock there, drives the libVulpes machine cycle with
 * fuel + power, and asserts the dim's {@code currentAtmosphere} moves.
 * The {@code allowTerraformNonAR} branch is pinned separately by
 * {@code TerraformerPoweredCycleOnOverworldTest} (Phase 1b).</p>
 *
 * <p>Counter-tests pin the no-fuel and no-power branches: each must
 * leave atmosphere density unchanged so the contract reads "all three
 * preconditions necessary, not just one or two".</p>
 *
 * <p>The fresh planet is generated per-method (not class-scope) because
 * the powered-cycle mutates dim-global atmosphere state — sharing it
 * across methods would leak the increase-mode mutation into the no-fuel
 * baseline read.</p>
 */
public class TerraformerPoweredCycleOnArPlanetTest extends AbstractSharedServerTest {

    /** The terraformer's own ceiling, in atmosphere-density units: the planet must start below it
     *  or the cycle has nothing to do. */
    private static final int TERRAFORMER_CEILING = 1600;

    private static final String CURRENT_ATMOS = "currentAtmosphere";
    private static final String POWER_POS = "powerPos";
    private static final String LIQUID_INPUT_POS = "liquidInputPos";
    /** All four 'L' hatches of the terraformer structure, as {@code [[x,y,z], …]}. */
    private static final String LIQUID_INPUT_POSITIONS = "liquidInputPositions";

    /** Each method picks distinct controller coords so per-method planets
     *  don't collide if a future refactor moves to class-scope. */
    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CX_POSITIVE = 200;
    private static final int CX_NO_FUEL  = 400;
    private static final int CX_NO_POWER = 600;
    private static final int CZ = 200;

    /** Per-method dim id, allocated in @Before and torn down in @After. */
    private int newDim = -1;

    @Before
    public void generatePlanet() throws Exception {
        Set<Integer> before = arDims();
        // The world is DERIVED, not rolled: /ar planet generate lost its three randomness arguments
        // with the legacy generator behind them, so the same command on the same seed now mints the
        // same planet.
        exec("ar planet generate 0 Phase1aTerraformer");
        Set<Integer> diff = arDims();
        diff.removeAll(before);
        assertEquals("planet generate must add exactly one dim — diff=" + diff,
                1, diff.size());
        newDim = diff.iterator().next();

        // Force-load the new dim so subsequent block/fluid/energy probes
        // can find a live WorldServer for it.
        String load = exec("artest dim load " + newDim);
        assertTrue("dim load did not report loaded:true — " + load,
                Reply.of(load).bool("loaded"));
    }

    @After
    public void cleanupPlanet() throws Exception {
        if (newDim != -1) {
            try {
                exec("ar planet delete " + newDim);
            } catch (Exception ignored) {
                // Best-effort cleanup; harness teardown will reclaim anyway.
            }
            newDim = -1;
        }
    }

    /** Powered + fueled + enabled multiblock on a native planet must
     *  mutate atmosphere density at least once over a long force-tick
     *  burst. Direction (increase vs decrease) is the default the GUI
     *  ships with — {@code buttonIncrease} defaults to true per
     *  {@code TileAtmosphereTerraformer.<init>}.
     *
     *  <p>A single density step requires {@code completionTime = 18000 ×
     *  terraformSpeed} (default 18000) onRunningPoweredTick() calls, each
     *  consuming {@code terraformliquidRate = 40} mB of both N2 and O2.
     *  The test runs in a fill&rarr;tick refill loop because no fluid hatch
     *  can hold the full 18000×40 = 720000 mB single-step requirement.</p> */
    @Test
    public void nativePlanetTerraformerWithFuelAndPowerStepsDensity() throws Exception {
        assertDimIsNativeArPlanet();

        String fixture = buildAndCompleteFixture(CX_POSITIVE);
        // 18000 ticks × 1000 powerPerTick = 18 M energy. Inject 30 M for
        // headroom; libVulpes power hatches accept large bursts.
        injectPower(fixture, 30_000_000);
        enableMachine(CX_POSITIVE);

        // DIAGNOSTIC — dump the controller's internal aggregator state so
        // a failure points directly at integration (P/L hatches not added)
        // vs cycle (currentTime not incrementing).
        String preState = exec("artest machine controller-state "
                + newDim + " " + CX_POSITIVE + " " + CY + " " + CZ);
        assertTrue("controller-state probe missing batteries readout — " + preState,
                Reply.of(preState).bool("batteriesPresent"));

        // ARRANGE the starting density instead of taking whatever the world hands over. The
        // terraformer only steps UP while density is below its ceiling of 1600, and a planet's
        // pressure is now DERIVED from its own physics — so a fixture that inherits it can be handed
        // a world already AT the ceiling and then measures nothing. (It was: this leg failed with
        // before=1600 after=1600 while its two siblings passed, which is what a fixture at the
        // ceiling looks like, not a terraformer that does not work.) The subject is whether a
        // powered, fuelled terraformer MOVES the density; where it starts is arrangement.
        exec("ar planet set " + newDim + " atmosphereDensity 100");
        int densityBefore = readDensity();
        assertTrue("arrangement: the planet must start below the terraformer's ceiling, got "
                + densityBefore, densityBefore < TERRAFORMER_CEILING);
        // Refill loop: terraformer needs BOTH N2 and O2 each tick.
        // TileFluidHatch holds one fluid per tank — so split: hatch 0+1
        // are N2 sources, hatch 2+3 are O2 sources. The controller's
        // drain loop iterates fluidInPorts; it picks up N2 from the
        // first two and O2 from the last two.
        // Budget: 60 iterations × 400 ticks = 24000 ticks -> at least
        // one density step (every 18000 ticks).
        for (int i = 0; i < 60; i++) {
            injectFluidAt(fixture, 0, "nitrogen", 16000);
            injectFluidAt(fixture, 1, "nitrogen", 16000);
            injectFluidAt(fixture, 2, "oxygen", 16000);
            injectFluidAt(fixture, 3, "oxygen", 16000);
            forceTick(CX_POSITIVE, 400);
        }
        int densityAfter = readDensity();

        String postState = exec("artest machine controller-state "
                + newDim + " " + CX_POSITIVE + " " + CY + " " + CZ);
        assertNotEquals("powered + fueled terraformer did not move density"
                        + " (before=" + densityBefore + " after=" + densityAfter + ")"
                        + "; preState=" + preState
                        + "; postState=" + postState,
                densityBefore, densityAfter);
    }

    /** Counter-test: fuel hatch empty &rarr; setOOF(true) &rarr; no power consumed,
     *  no progress, no density mutation. Pins the fuel-required branch. */
    @Test
    public void nativePlanetTerraformerWithoutFuelDoesNotStep() throws Exception {
        assertDimIsNativeArPlanet();
        String fixture = buildAndCompleteFixture(CX_NO_FUEL);
        injectPower(fixture, 30_000_000);
        // Deliberately skip fluid injection.
        enableMachine(CX_NO_FUEL);

        int densityBefore = readDensity();
        // Same tick budget as the positive test — proves OOF gate holds
        // for the full window during which the positive test mutates.
        forceTick(CX_NO_FUEL, 24000);
        int densityAfter = readDensity();

        assertEquals("fuel-less terraformer moved density anyway"
                        + " (before=" + densityBefore + " after=" + densityAfter + ")",
                densityBefore, densityAfter);
    }

    /** Counter-test: controller's battery aggregator cleared
     *  ({@code MultiBattery.clear()}) so {@code hasEnergy(powerPerTick)}
     *  reads 0 &rarr;  libVulpes' update() skips onRunningPoweredTick &rarr;
     *  currentTime never increments &rarr; processComplete never fires &rarr;
     *  density unchanged. Pins the power-required branch.
     *
     *  <p><b>Why clear-batteries instead of skip-inject</b>: the default
     *  'P'-mapping fixture places creative input plugs whose
     *  {@code TileCreativePowerInput.getUniversalEnergyStored()} returns
     *  {@code Integer.MAX_VALUE >> 4} unconditionally. Skipping
     *  {@code energy inject} still leaves the controller with effectively
     *  infinite aggregated power, so this counter-test wouldn't actually
     *  exercise the no-power branch without the explicit clear.</p> */
    @Test
    public void nativePlanetTerraformerWithoutPowerDoesNotStep() throws Exception {
        assertDimIsNativeArPlanet();
        String fixture = buildAndCompleteFixture(CX_NO_POWER);
        enableMachine(CX_NO_POWER);
        // Wipe the aggregator AFTER integrateTile populated it, so the
        // controller observes an empty battery list each tick.
        String drain = exec("artest machine clear-batteries " + newDim
                + " " + CX_NO_POWER + " " + CY + " " + CZ);
        assertTrue("clear-batteries probe failed: " + drain,
                Reply.of(drain).bool("cleared"));

        // Top up fluid each iteration so OOF can't be the cause of any
        // non-progression observed below — power-absence must be the
        // sole reason.
        int densityBefore = readDensity();
        for (int i = 0; i < 60; i++) {
            injectFluidAt(fixture, 0, "nitrogen", 16000);
            injectFluidAt(fixture, 1, "nitrogen", 16000);
            injectFluidAt(fixture, 2, "oxygen", 16000);
            injectFluidAt(fixture, 3, "oxygen", 16000);
            forceTick(CX_NO_POWER, 400);
        }
        int densityAfter = readDensity();

        assertEquals("battery-drained terraformer moved density anyway"
                        + " (before=" + densityBefore + " after=" + densityAfter + ")",
                densityBefore, densityAfter);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private String buildAndCompleteFixture(int cx) throws Exception {
        String fixture = exec("artest fixture multiblock terraformer "
                + newDim + " " + cx + " " + CY + " " + CZ);
        assertTrue("terraformer fixture build failed: " + fixture,
                Reply.of(fixture).ok() && (Reply.of(fixture).integer("unresolved") == 0));
        String tryComplete = exec("artest machine try-complete "
                + newDim + " " + cx + " " + CY + " " + CZ);
        assertTrue("terraformer structure failed to complete: " + tryComplete,
                Reply.of(tryComplete).bool("isComplete"));
        return fixture;
    }

    private void injectPower(String fixture, int amount) throws Exception {
        int[] m = Reply.of(fixture).blockPos(POWER_POS);
        assertTrue("no powerPos in fixture response: " + fixture, m != null);
        int px = m[0];
        int py = m[1];
        int pz = m[2];
        String resp = exec("artest energy inject "
                + newDim + " " + px + " " + py + " " + pz + " " + amount);
        assertTrue("energy inject failed: " + resp, Reply.of(resp).ok());
    }

    /** Precondition guard: a freshly-generated AR planet must report as
     *  a native AR dim (controller's {@code processComplete()} gate
     *  requires this). If this assert fires, the planet-generate or
     *  dim-load handshake has regressed and the powered-cycle assertions
     *  below would fail for an irrelevant reason. */
    private void assertDimIsNativeArPlanet() throws Exception {
        DimInfo info = DimInfo.forDim(WorldCommandFixtures::exec, newDim);
        assertTrue("dim info missing isARPlanet:true — " + info.raw(), info.arPlanet);
        // The terraformer gate also needs WorldProviderPlanet, asked of the field that names the
        // provider: the `contains` this replaces would have been answered by the save folder or by
        // the chunk generator's own class name.
        assertTrue("dim provider is not WorldProviderPlanet — " + info.raw(),
                info.providerClass().endsWith("WorldProviderPlanet"));
    }

    /** Injects {@code amount} mB of {@code fluidName} into the
     *  {@code hatchIndex}-th 'L' hatch returned by the fixture probe.
     *  {@code TileFluidHatch} holds one fluid type at a time, so the
     *  terraformer's onRunningPoweredTick (which demands BOTH N2 and O2)
     *  needs N2 in some hatches and O2 in others — see
     *  {@link #nativePlanetTerraformerWithFuelAndPowerStepsDensity}'s
     *  per-hatch loop. */
    private void injectFluidAt(String fixture, int hatchIndex, String fluidName,
                               int amount) throws Exception {
        int[] pos = nthLiquidInputPos(fixture, hatchIndex);
        String resp = exec("artest fluid inject "
                + newDim + " " + pos[0] + " " + pos[1] + " " + pos[2]
                + " " + fluidName + " " + amount);
        assertTrue(fluidName + " inject failed at hatch " + hatchIndex + ": " + resp,
                Reply.of(resp).ok());
    }

    /** Scans the fixture response's {@code liquidInputPositions} array
     *  for the n-th triple. */
    private static int[] nthLiquidInputPos(String fixture, int n) {
        // Asked for by NAME, which is also what keeps it clear of the back-compat single
        // `liquidInputPos` and of every other position list in the same reply — the slice-then-scan
        // this replaces had to know where the section started to get that right.
        int[][] hatches = Reply.of("artest fixture machine", fixture)
                .blockPosArray(LIQUID_INPUT_POSITIONS);
        assertTrue("liquidInputPositions has fewer than " + (n + 1) + " hatches: " + fixture,
                n < hatches.length);
        return hatches[n];
    }

    private void enableMachine(int cx) throws Exception {
        String resp = exec("artest machine set-enabled "
                + newDim + " " + cx + " " + CY + " " + CZ + " true");
        assertTrue("machine set-enabled failed: " + resp, Reply.of(resp).bool("enabled"));
    }

    private void forceTick(int cx, int ticks) throws Exception {
        String resp = exec("artest tile force-tick "
                + newDim + " " + cx + " " + CY + " " + CZ + " " + ticks);
        assertTrue("force-tick errored: " + resp, Reply.of(resp).ok());
    }

    private int readDensity() throws Exception {
        String info = exec("artest terraforming info " + newDim);
        Reply mReply = Reply.of(info);
        assertTrue("no currentAtmosphere in terraforming info: " + info, mReply.has(CURRENT_ATMOS));
        return Integer.parseInt(mReply.text(CURRENT_ATMOS));
    }

    /**
     * The AR dimensions registered right now, asked of the probe rather than scraped out of
     * {@code ar planet list}. Nothing here claims anything about that command's output; both read
     * {@code DimensionManager.getInstance().getRegisteredDimensions()}.
     */
    private static Set<Integer> arDims() throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (int dim : Reply.of("artest dim list", exec("artest dim list")).intArray("arDimensions")) {
            ids.add(dim);
        }
        return ids;
    }
}
