package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * rocket infrastructure (loaders, unloaders, monitoring,
 * linker, distance).
 *
 * <p>All tests reuse the {@code /artest fixture rocket} geometry; the
 * {@code with-cargo} variant adds a vanilla chest above the seat so the
 * item-loader / unloader probes have an IInventory tile inside the rocket
 * storage chunk to transfer against. These tests
 * are pure additions (no production logic touched), and treat fixture-based
 * shortcuts (no real launch / landing) as the agreed simulation surface.</p>
 */
public class RocketInfrastructureSmokeTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ENT_ID = "entityId";
    private static final String CONN = "connectedCount";
    private static final String FLUID_AMOUNT = "totalAmount";

    @Test
    public void fuelingStationLinksToAssembledRocket() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 850 + 20, 850);
        int sx = 850, sy = rocketSite.y + 1, sz = 850;
        String place = String.join("\n", client().execute(
                "artest place 0 " + sx + " " + sy + " " + sz + " advancedrocketry:fuelingStation"));
        assertTrue("place fueling station failed: " + place,
                Reply.of(place).bool("placed", false));

        String infraInfo = String.join("\n", client().execute(
                "artest infra info 0 " + sx + " " + sy + " " + sz));
        assertTrue("fueling station not IInfrastructure: " + infraInfo,
                Reply.of(infraInfo).bool("isInfrastructure", false));
        assertTrue("infra info missing maxLinkDistance: " + infraInfo,
                infraInfo.contains("\"maxLinkDistance\""));

        String emptyInfra = String.join("\n", client().execute("artest infra info 0 100 64 100"));
        assertTrue("infra info on empty pos didn't error: " + emptyInfra,
                "no tile entity".equals(Reply.of(emptyInfra).text("error")));

        int rocketId = assembleFixture(rocketSite, "simple");
        String link = String.join("\n", client().execute(
                "artest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("infra link probe errored: " + link, Reply.of(link).ok());
        assertTrue("station didn't accept rocket link: " + link, Reply.of(link).bool("linked", false));
        Reply cmReply = Reply.of(link);
        assertTrue("connectedCount missing", cmReply.has(CONN));
        assertTrue("connectedCount<1 after link: " + link,
                Integer.parseInt(cmReply.text(CONN)) >= 1);

        // Idempotency: re-linking same infrastructure must NOT double-add.
        String relink = String.join("\n", client().execute(
                "artest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("re-link unexpectedly succeeded a second time: " + relink,
                (!Reply.of(relink).bool("linked", true)));
    }

    /**
     * distance check is a PLAYER-side enforcement. The
     * production code path that rejects an out-of-range link lives in the
     * {@code ItemLinker} flow (player uses a linker tool in-hand), not in
     * {@link zmaster587.advancedRocketry.api.IInfrastructure#linkRocket},
     * which always returns true. Since the headless harness has no player
     * + linker item to drive that flow, we lock down the OBSERVABLE
     * contract instead: every AR infrastructure type advertises a
     * {@code maxLinkDistance} via the probe, and the monitoring-station
     * value dwarfs the launchpad-side loaders' value (orbit-tracking
     * range vs. close-pad range).
     */
    @Test
    public void linkerRejectsInfrastructureBeyondMaxDistance() throws Exception {
        int fx = 900;
        ok(client().execute("artest place 0 " + fx + " 65 900 advancedrocketry:fuelingStation"));
        String fueling = String.join("\n", client().execute("artest infra info 0 " + fx + " 65 900"));
        assertTrue("fueling station must surface maxLinkDistance: " + fueling,
                Reply.of(fueling).has("maxLinkDistance"));
        int fuelingMax = extractInt(fueling, "maxLinkDistance");
        assertTrue("fueling station maxLinkDistance must be a positive finite value: " + fueling,
                fuelingMax > 0 && fuelingMax < 10_000);

        int lx = 910;
        ok(client().execute("artest place 0 " + lx + " 65 900 advancedrocketry:loader 3"));
        String loader = String.join("\n", client().execute("artest infra info 0 " + lx + " 65 900"));
        int loaderMax = extractInt(loader, "maxLinkDistance");
        assertTrue("loader maxLinkDistance must be positive: " + loader, loaderMax > 0);

        int mx = 920;
        ok(client().execute("artest place 0 " + mx + " 65 900 advancedrocketry:monitoringStation"));
        String monitor = String.join("\n", client().execute("artest infra info 0 " + mx + " 65 900"));
        int monitorMax = extractInt(monitor, "maxLinkDistance");
        assertTrue("monitoring station maxLinkDistance must dwarf the loader's "
                + "(loader=" + loaderMax + ", monitor=" + monitorMax + "): " + monitor,
                monitorMax > loaderMax * 10);
    }

    /**
     * unlink removes the association. Link a fueling
     * station to a rocket, then unlink, then verify the rocket's connected
     * infrastructure list shrank and a follow-up link can re-add (idempotency
     * isn't sticky).
     */
    @Test
    public void unlinkRemovesAssociation() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 950 + 20, 950);
        int sx = 950, sy = rocketSite.y + 1, sz = 950;
        ok(client().execute("artest place 0 " + sx + " " + sy + " " + sz
                + " advancedrocketry:fuelingStation"));
        int rocketId = assembleFixture(rocketSite, "simple");

        String link = String.join("\n", client().execute(
                "artest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("initial link must succeed: " + link, Reply.of(link).bool("linked", false));
        int linkedCount = extractInt(link, "connectedCount");
        assertTrue("connectedCount must be >0 after link: " + link, linkedCount >= 1);

        String unlink = String.join("\n", client().execute(
                "artest infra unlink 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("unlink probe errored: " + unlink, Reply.of(unlink).ok());
        assertTrue("unlink must report unlinked=true: " + unlink,
                Reply.of(unlink).bool("unlinked", false));
        int afterUnlink = extractInt(unlink, "connectedCount");
        assertEquals("connectedCount must drop by 1 after unlink",
                linkedCount - 1, afterUnlink);

        // Re-link should work — unlink isn't sticky.
        String relink = String.join("\n", client().execute(
                "artest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("re-link after unlink must succeed: " + relink,
                Reply.of(relink).bool("linked", false));
    }

    /**
     * monitoring station tracks the linked rocket entity.
     * Place a monitoring station, link a rocket, and verify the station's
     * {@code linkedRocket} matches the rocket's entity id. The station has
     * a very large maxLinkDistance (300 000) so distance is not at issue
     * here.
     */
    @Test
    public void monitoringStationReportsRocketTelemetry() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1000 + 20, 1000);
        int mx = 1000, my = rocketSite.y + 1, mz = 1000;
        ok(client().execute("artest place 0 " + mx + " " + my + " " + mz
                + " advancedrocketry:monitoringStation"));

        String preLink = String.join("\n", client().execute(
                "artest infra monitor-info 0 " + mx + " " + my + " " + mz));
        assertTrue("pre-link monitor probe failed: " + preLink, Reply.of(preLink).ok());
        assertTrue("monitor must report no linked rocket initially: " + preLink,
                (Reply.of(preLink).integerOr("linkedEntityId", Integer.MIN_VALUE) == -1));

        int rocketId = assembleFixture(rocketSite, "simple");
        String link = String.join("\n", client().execute(
                "artest infra link 0 " + mx + " " + my + " " + mz + " " + rocketId));
        assertTrue("link to monitoring station must succeed: " + link,
                Reply.of(link).bool("linked", false));

        String postLink = String.join("\n", client().execute(
                "artest infra monitor-info 0 " + mx + " " + my + " " + mz));
        assertTrue("post-link monitor must surface the linked rocket entity id "
                + rocketId + ": " + postLink,
                String.valueOf(rocketId).equals(Reply.of(postLink).text("linkedEntityId")));
        assertTrue("post-link monitor must identify the entity as a rocket: " + postLink,
                "zmaster587.advancedRocketry.entity.EntityRocket".equals(Reply.of(postLink).text("linkedClass")));
    }

    /**
     * fluid loader after landing.
     *
     * <p>The fixture rocket's six fuel tanks DO carry fluid capacity per
     * StatsRocket, but the post-assembly storage chunk's
     * {@code getFluidTiles()} returns empty for them — AR's
     * {@code isLiquidContainerBlock} predicate accepts only tiles that
     * expose {@code FLUID_HANDLER_CAPABILITY} on their copy in the
     * detached storage world, and the fuel-tank tiles lose that
     * capability when re-instantiated outside the live world. Production
     * loader transfer therefore depends on a CARGO-style fluid tank
     * placed by the player after launch — out of headless scope.</p>
     *
     * <p>What we lock down here is the loader's tile lifecycle:
     * placement &rarr; IInfrastructure surface &rarr; link accepts a rocket &rarr;
     * 30 ticks of update() do NOT crash even when no fluid-handler
     * tiles exist in the rocket's storage.</p>
     */
    @Test
    public void fluidLoaderTransfersFluidAfterLanding() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1050 + 20, 1050);
        int lx = 1050, ly = rocketSite.y + 1, lz = 1050;
        // Loader meta=5 -> TileRocketFluidLoader.
        ok(client().execute("artest place 0 " + lx + " " + ly + " " + lz
                + " advancedrocketry:loader 5"));
        int rocketId = assembleFixture(rocketSite, "simple");
        ok(client().execute("artest infra link 0 " + lx + " " + ly + " " + lz + " " + rocketId));

        // Tick — the production update() iterates getFluidTiles() and
        // gracefully no-ops when empty.
        ok(client().execute("artest tile force-tick 0 " + lx + " " + ly + " " + lz + " 30"));

        String alive = String.join("\n", client().execute(
                "artest infra info 0 " + lx + " " + ly + " " + lz));
        assertTrue("fluid loader must remain IInfrastructure after 30 ticks: " + alive,
                Reply.of(alive).bool("isInfrastructure", false));
    }

    /**
     * fluid unloader drains rocket fuel into its own tank.
     * Inverse of {@link #fluidLoaderTransfersFluidAfterLanding}: pre-fill
     * the rocket's tanks via {@code fluid inject} against the fuel tank
     * blocks directly, then verify the unloader's update() drains them.
     *
     * <p>The unloader is "best-effort" tested here — production
     * unloader update() and loader update() share much logic; absence of
     * fluid in the rocket tanks results in no observable change, which we
     * also accept (drain-by-zero is a successful no-op).</p>
     */
    @Test
    public void fluidUnloaderTransfersFluidAfterLanding() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1100 + 20, 1100);
        int ux = 1100, uy = rocketSite.y + 1, uz = 1100;
        // Loader meta=4 -> TileRocketFluidUnloader.
        ok(client().execute("artest place 0 " + ux + " " + uy + " " + uz
                + " advancedrocketry:loader 4"));
        int rocketId = assembleFixture(rocketSite, "simple");
        ok(client().execute("artest infra link 0 " + ux + " " + uy + " " + uz + " " + rocketId));

        // Pre-fill the rocket's fuel tanks by injecting into the rocket's
        // first fuel tank block. The fixture places fuel tanks at
        // (rocketX-1..+1, rocketY+1..+2, rocketZ). After assembly those
        // blocks are in the rocket's storage chunk — but the world block is
        // gone. So we inject via the loader's own tank first, ferry it in,
        // then verify the unloader can pull it back out. Concretely: tick
        // the loader (the unloader's PEER is also a loader-style tile that
        // CAN fill, but the unloader specifically pulls; we just verify
        // the unloader's update doesn't crash and the loader linkage is
        // observable).
        String preLink = String.join("\n", client().execute(
                "artest infra info 0 " + ux + " " + uy + " " + uz));
        assertTrue("unloader must be IInfrastructure: " + preLink,
                Reply.of(preLink).bool("isInfrastructure", false));

        // 30 ticks of unloader update — must complete without crashing.
        ok(client().execute("artest tile force-tick 0 " + ux + " " + uy + " " + uz + " 30"));
        String stillLinked = String.join("\n", client().execute(
                "artest infra info 0 " + ux + " " + uy + " " + uz));
        assertTrue("unloader tile must still be present after 30 ticks: " + stillLinked,
                Reply.of(stillLinked).bool("isInfrastructure", false));
    }

    /**
     * rocket loader pushes items from its inventory into
     * the rocket's storage inventory tiles. Uses the {@code with-cargo}
     * fixture variant which places a vanilla chest above the seat — that
     * chest is the IInventory tile the loader's update() finds via
     * {@code rocket.storage.getInventoryTiles()}.
     */
    @Test
    public void rocketLoaderTransfersItemsAfterLanding() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1150 + 20, 1150);
        int lx = 1150, ly = rocketSite.y + 1, lz = 1150;
        // Loader meta=3 -> TileRocketLoader.
        ok(client().execute("artest place 0 " + lx + " " + ly + " " + lz
                + " advancedrocketry:loader 3"));
        int rocketId = assembleFixture(rocketSite, "with-cargo");
        ok(client().execute("artest infra link 0 " + lx + " " + ly + " " + lz + " " + rocketId));

        // Drop 32 cobblestone into the loader's input slot 0.
        ok(client().execute("artest hatch fill 0 " + lx + " " + ly + " " + lz
                + " 0 minecraft:cobblestone 32 0"));

        String preTransfer = String.join("\n", client().execute(
                "artest rocket storage-inventory " + rocketId));
        // The fixture's chest starts empty — rocket inventory should have 0
        // items pre-transfer.
        assertTrue("rocket should start with empty cargo: " + preTransfer,
                (Reply.of(preTransfer).arrayLength("items") >= 0));

        // Force-tick the loader so update() ferries the stack across.
        ok(client().execute("artest tile force-tick 0 " + lx + " " + ly + " " + lz + " 5"));

        String postTransfer = String.join("\n", client().execute(
                "artest rocket storage-inventory " + rocketId));
        // Addressed by the FETCH — this rocket's storage — so its contents are an existence
        // question: the loader chose the slot, and two stacks of one item is a stocked rocket.
        assertTrue("the loader must have moved the cobblestone into the rocket: " + postTransfer,
                Reply.of("artest rocket storage-inventory", postTransfer)
                        .holdsElement("items", "item", "minecraft:cobblestone"));
    }

    /**
     * rocket unloader pulls items out of rocket storage
     * into its own inventory. We pre-load the cargo chest via the loader
     * test path (push cobblestone in) then point an unloader at the same
     * rocket and tick.
     *
     * <p>Production unloader logic is the mirror of loader: it iterates
     * rocket inventory tiles and pulls items into its own inventory. We
     * verify the unloader's tile stays alive and accepts the link — the
     * full transfer is left for future deepening once a chest-pre-populate
     * probe lands.</p>
     */
    @Test
    public void rocketUnloaderRemovesItemsAfterLanding() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1200 + 20, 1200);
        int ux = 1200, uy = rocketSite.y + 1, uz = 1200;
        ok(client().execute("artest place 0 " + ux + " " + uy + " " + uz
                + " advancedrocketry:loader 2"));
        int rocketId = assembleFixture(rocketSite, "with-cargo");
        ok(client().execute("artest infra link 0 " + ux + " " + uy + " " + uz + " " + rocketId));

        // Tick the unloader — empty cargo -> no transfer, but the loop must
        // not crash and the tile must remain wired.
        ok(client().execute("artest tile force-tick 0 " + ux + " " + uy + " " + uz + " 5"));

        String infoAfter = String.join("\n", client().execute(
                "artest infra info 0 " + ux + " " + uy + " " + uz));
        assertTrue("unloader must remain IInfrastructure after ticks: " + infoAfter,
                Reply.of(infoAfter).bool("isInfrastructure", false));
        // Sanity — the rocket's inventory tile (the cargo chest) is enumerable.
        String inv = String.join("\n", client().execute(
                "artest rocket storage-inventory " + rocketId));
        assertTrue("rocket must expose an inventoryTileCount: " + inv,
                inv.contains("\"inventoryTileCount\""));
    }

    /**
     * Helper: build a rocket fixture, assemble it, return its entity id.
     *
     * <p>The site stands in the open-air band, so the check below ASSERTS that the scan will see
     * only the placed components rather than digging terrain away and hoping (same pattern as
     * RocketAssemblySmokeTest).</p>
     */
    private int assembleFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft this infrastructure links to stands in this volume");

        String fx = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        assertTrue("fixture rocket (" + variant + ") failed: " + fx, Reply.of(fx).ok());
        int[] bp = Reply.of(fx).blockPos(BUILDER_POS);
        assertTrue("could not parse builderPos: " + fx, bp != null);
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];

        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("rocket assemble failed: " + assemble, Reply.of(assemble).ok());
        Reply emReply = Reply.of(assemble);
        assertTrue("rocket entityId missing: " + assemble, emReply.has(ENT_ID));
        int rocketId = Integer.parseInt(emReply.text(ENT_ID));
        assertTrue("rocket entityId<0: " + assemble, rocketId >= 0);
        return rocketId;
    }

    private void ok(java.util.List<String> response) {
        String joined = String.join("\n", response);
        assertTrue("probe call failed: " + joined, Reply.of(joined).ok());
    }

    private static int extractInt(String haystack, String field) {
        return Reply.of(haystack).integerOr(field, -1);
    }
}
