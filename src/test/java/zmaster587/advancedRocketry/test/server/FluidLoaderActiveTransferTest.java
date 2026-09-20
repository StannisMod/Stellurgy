package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.FluidStored;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * fluid loader / unloader active transfer contract.
 *
 * <p>The pre-existing {@link RocketInfrastructureSmokeTest#fluidLoaderTransfersFluidAfterLanding}
 * pins only tile lifecycle (placement &rarr; link &rarr; 30 ticks survive). It
 * documents why active transfer was deferred: fuel-tank tiles in the
 * fixture rocket's storage chunk lose {@code FLUID_HANDLER_CAPABILITY}
 * when re-instantiated in the detached storage world.</p>
 *
 * <p>The blocker is bypassed by the
 * {@code with-fluid-cargo} fixture variant which replaces 2 of the 6
 * fuel-tank slots with {@code advancedrocketry:liquidTank} (TileFluidTank)
 * blocks — those TEs DO survive the storage-chunk round-trip with their
 * Forge fluid capability intact (already exercised by
 * {@link MissionGasCompletionTest#gasCompletionFillsRocketFluidTilesWithConfiguredFluid}).</p>
 *
 * <p>Pins:</p>
 * <ul>
 *   <li><b>Loader &rarr; rocket transfer</b> ({@link
 *       zmaster587.advancedRocketry.tile.infrastructure.TileRocketFluidLoader#update}):
 *       fluid pre-loaded into the loader's own tank ends up in the
 *       linked rocket's storage liquidTanks after a tick budget.</li>
 *   <li><b>Unloader &rarr; rocket-drain</b> ({@link
 *       zmaster587.advancedRocketry.tile.infrastructure.TileRocketFluidUnloader#update}):
 *       fluid pre-filled into the linked rocket's storage liquidTanks
 *       gets pulled out into the unloader's tank.</li>
 * </ul>
 *
 * <p>Loose-bound assertions: "at least 1 mB moved" (the contract is the
 * direction, not exact mB/tick); both tests use a generous 60-tick
 * budget which is well above any plausible per-tick transfer cost.</p>
 */
public class FluidLoaderActiveTransferTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ENT_ID = "entityId";
    private static final String TOTAL_AMOUNT = "totalAmount";
    private static final String TILES_WITH_CAP = "tilesWithCapability";
    private static final String TOTAL_FILLED = "totalFilled";

    /**
     * loader pre-loaded with oxygen actively transfers it into
     * the linked rocket's storage liquidTanks across 60 production
     * ticks. Asserts both legs of the transfer:
     *
     * <ol>
     *   <li>Loader's own tank drained by &gt;0 mB.</li>
     *   <li>Rocket's storage gained &gt;0 mB total across its
     *       liquidTanks.</li>
     * </ol>
     *
     * <p>Doesn't pin exact mB-per-tick — production's transfer rate is
     * impl (depends on tank capacity, handler fill behaviour, etc.).</p>
     */
    @Test
    public void loaderTransfersOxygenIntoRocketStorageLiquidTanks() throws Exception {
        // THE PAIR MOVES TOGETHER. The loader sat at y=65 and the rocket's base at y=64, one below
        // it — a RELATIVE geometry written as two absolute numbers, which is why the mechanical
        // lift could not touch this class: moving either alone would have put the loader a hundred
        // blocks from the craft it loads while both lines still looked plausible.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1300 + 20, 1300);
        int lx = 1300, ly = rocketSite.y + 1, lz = 1300;
        ok("artest place 0 " + lx + " " + ly + " " + lz
                + " advancedrocketry:loader 5");

        int rocketId = assembleFixture(rocketSite, "with-fluid-cargo");

        // Pre-load loader's tank with oxygen. The loader IS a
        // TileFluidHatch, so `fluid inject` works against its world pos.
        String inj = exec("artest fluid inject 0 " + lx + " " + ly + " " + lz
                + " oxygen 32000");
        assertTrue("loader fluid inject must succeed: " + inj,
                Reply.of(inj).ok());
        int loaderFilled = extract(inj, "filled");
        assertTrue("loader pre-fill must accept > 0 mB: " + inj,
                loaderFilled > 0);

        // Link rocket to loader. From this moment forward the real
        // server tick loop will fire TileRocketFluidLoader.update()
        // every server tick (~50ms) — that means by the time the test
        // thread issues its next probe command, natural ticks have
        // already done the transfer. The contract pin is therefore on
        // the END STATE, not on the delta around a synthetic force-tick
        // window: after linking + ticking, the rocket's storage
        // liquidTanks hold oxygen and the loader's own tank has
        // drained.
        ok("artest infra link 0 " + lx + " " + ly + " " + lz + " " + rocketId);

        // Force at least 60 additional ticks of the loader's update()
        // to ensure the transfer completes even on slow harnesses.
        ok("artest tile force-tick 0 " + lx + " " + ly + " " + lz + " 60");

        // Loader's tank must be drained (production transferred
        // fluid out). After full drain the tank reads
        // {"fluid":null} (no amount field) — parse defensively.
        String loaderAfter = exec("artest fluid stored 0 " + lx + " " + ly + " " + lz);
        int loaderTankAfter = parseOxygenAmountOrZero(loaderAfter);
        assertTrue("loader's own tank must have drained from "
                        + loaderFilled + " mB toward 0 after ticks; "
                        + "after=" + loaderTankAfter
                        + " loaderJson=" + loaderAfter,
                loaderTankAfter < loaderFilled);

        // Rocket storage must hold oxygen. The exact amount depends on
        // tank capacities + how many ticks fired between commands; the
        // contract pin is "rocket gained the loader's fluid", not a
        // specific mB count.
        String postStorage = exec("artest rocket storage-fluid " + rocketId);
        // THE oxygen tank, and its amount read off that same tank. `totalAmount` sums every tank,
        // so "the storage grew" and "oxygen is in there" were two questions about one subject —
        // and a rocket holding oxygen beside anything else answers them from two different tanks.
        Reply oxygen = Reply.of("artest rocket storage-fluid", postStorage)
                .element("tanks", "fluid", "oxygen");
        assertTrue("rocket storage liquidTanks must contain oxygen after loader ticks (the"
                        + " player-visible 're-fuel automation' contract): " + postStorage,
                oxygen.integer("amount") > 0);
    }

    /**
     * unloader pre-linked to a rocket actively drains the
     * rocket's storage liquidTanks into its own tank across 60 ticks.
     * Inverse direction of the loader test.
     *
     * <p>Pre-fill the rocket's storage liquidTanks via the
     * {@code rocket storage-fluid-fill} probe (which iterates
     * {@code storage.getFluidTiles()} and fills each one via the
     * FLUID_HANDLER capability — same surface the loader writes
     * against, but driven directly from the test).</p>
     */
    @Test
    public void unloaderDrainsRocketStorageLiquidTanksIntoOwnTank() throws Exception {
        // The pair moves together; see the sibling scenario above for why this is one decision.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1400 + 20, 1400);
        int ux = 1400, uy = rocketSite.y + 1, uz = 1400;
        ok("artest place 0 " + ux + " " + uy + " " + uz
                + " advancedrocketry:loader 4");

        int rocketId = assembleFixture(rocketSite, "with-fluid-cargo");

        // Pre-fill rocket's storage liquidTanks with oxygen via the
        // dedicated probe.
        String fillResp = exec("artest rocket storage-fluid-fill " + rocketId
                + " oxygen 16000");
        assertTrue("storage-fluid-fill must succeed: " + fillResp,
                Reply.of(fillResp).ok());
        int tilesWithCap = extract(fillResp, TILES_WITH_CAP);
        int totalFilled = extract(fillResp, TOTAL_FILLED);
        assertTrue("with-fluid-cargo fixture must produce at least one "
                        + "TE with FLUID_HANDLER capability inside storage: "
                        + fillResp,
                tilesWithCap >= 1);
        assertTrue("pre-fill must succeed with > 0 mB total: " + fillResp,
                totalFilled > 0);

        // Sanity: storage-fluid probe agrees with fill result.
        String preStorage = exec("artest rocket storage-fluid " + rocketId);
        int storageBefore = extract(preStorage, TOTAL_AMOUNT);
        assertTrue("rocket storage must show the pre-filled amount "
                        + "(storage-fluid probe sanity gate): " + preStorage,
                storageBefore > 0);

        // Pre-condition: unloader's own tank starts empty (or with any
        // residual from previous test runs — only the delta matters).
        String preUnloader = exec("artest fluid stored 0 " + ux + " " + uy + " " + uz);
        int unloaderTankBefore = parseOxygenAmountOrZero(preUnloader);

        // Link rocket to unloader.
        String link = exec("artest infra link 0 " + ux + " " + uy + " " + uz
                + " " + rocketId);
        assertTrue("infra link must succeed: " + link,
                Reply.of(link).bool("linked"));

        // Run the unloader's production update() for 60 ticks.
        ok("artest tile force-tick 0 " + ux + " " + uy + " " + uz + " 60");

        // Unloader's tank must have gained fluid.
        String postUnloader = exec("artest fluid stored 0 " + ux + " " + uy + " " + uz);
        int unloaderTankAfter = parseOxygenAmountOrZero(postUnloader);
        assertTrue("unloader's own tank must have gained oxygen after "
                        + "60 ticks (the player-visible 'drain returning "
                        + "rocket' contract); before=" + unloaderTankBefore
                        + " after=" + unloaderTankAfter
                        + " postUnloader=" + postUnloader,
                unloaderTankAfter > unloaderTankBefore);

        // Rocket storage must have drained.
        String postStorage = exec("artest rocket storage-fluid " + rocketId);
        int storageAfter = extract(postStorage, TOTAL_AMOUNT);
        assertTrue("rocket storage liquidTanks must have drained after "
                        + "60 unloader ticks; before=" + storageBefore
                        + " after=" + storageAfter,
                storageAfter < storageBefore);
    }

    // -- helpers ----------------------------------------------------------

    private static String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private void ok(String cmd) throws Exception {
        String resp = exec(cmd);
        assertTrue("probe must succeed: cmd='" + cmd + "' resp=" + resp,
                Reply.of(resp).ok());
    }

    private int assembleFixture(FixtureSite site, String variant)
            throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed` — the number the
        // pre-clear it replaces was throwing away. Open air, so this ASSERTS rather than digs.
        site.requireClear(cmd -> exec(cmd), 2, 10,
                "the craft the loader fills stands in this volume");
        String fx = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + variant);
        assertTrue("fixture rocket (" + variant + ") failed: " + fx,
                Reply.of(fx).ok());
        int[] bp = Reply.of(fx).blockPos(BUILDER_POS);
        assertTrue("could not parse builderPos: " + fx, bp != null);
        String assemble = exec("artest rocket assemble 0 "
                + bp[0] + " " + bp[1] + " " + bp[2]);
        assertTrue("rocket assemble failed: " + assemble,
                Reply.of(assemble).ok());
        Reply emReply = Reply.of(assemble);
        assertTrue("rocket entityId missing: " + assemble, emReply.has(ENT_ID));
        return Integer.parseInt(emReply.text(ENT_ID));
    }

    private static int extract(String src, String field) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.integer(field);
    }

    /**
     * Parse the oxygen amount from a {@code fluid stored} probe response.
     * Returns 0 when the tank has no oxygen ({@code "fluid":null} or
     * missing) — that's a valid drained-tank state, not a parse error.
     */
    private static int parseOxygenAmountOrZero(String src) {
        // Read per TANK. The regex this replaces matched `fluid` and `amount` in one expression, so
        // a tank that wrote them in the other order read as no oxygen at all.
        return FluidStored.of(src).amountOf("oxygen");
    }
}
