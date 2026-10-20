package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ScenarioRockets;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * rocket item unloader active transfer contract.
 *
 * <p>The pre-existing {@link RocketInfrastructureSmokeTest#rocketUnloaderRemovesItemsAfterLanding}
 * pins only tile lifecycle (placement &rarr; link &rarr; 5 ticks survive); it
 * documents why the transfer was deferred ("once a chest-pre-populate
 * probe lands"). The
 * {@code rocket storage-item-fill} probe (mirror of
 * {@code storage-fluid-fill}) unblocks the active-transfer pin.</p>
 *
 * <p>Contract pinned: {@link
 * dev.stannismod.stellurgy.tile.infrastructure.TileRocketUnloader#update}
 * — items pre-injected into the rocket's storage chunk inventory tiles
 * land in the unloader's own inventory after force-tick.</p>
 *
 * <p>Reuses the {@code with-cargo} fixture variant (vanilla chest above
 * the seat in storage chunk; documented at TestProbeCommand fixture
 * dispatcher).</p>
 *
 * <p>Loose-bound: "at least 1 item moved" — the contract is the
 * direction, not exact items/tick. Production iterates the storage
 * chunk's inventory tiles each {@code update()} and moves at most one
 * stack per tick, so a 5-tick budget pinned the loader side; 10 here for
 * safety margin.</p>
 */
public class RocketItemUnloaderActiveTransferTest extends AbstractSharedServerTest {

    private static final String TOTAL_PLACED = "totalPlaced";
    private static final String TILES_WITH_CAP = "tilesWithCapability";

    /**
     * unloader pre-linked to a rocket actively drains the
     * rocket's storage inventory tiles into its own inventory across 10
     * ticks. Inverse direction of
     * {@link RocketInfrastructureSmokeTest#rocketLoaderTransfersItemsAfterLanding}.
     *
     * <p>Pre-fill the rocket's cargo chest via the
     * {@code rocket storage-item-fill} probe (which iterates
     * {@code storage.getInventoryTiles()} and inserts items via the
     * ITEM_HANDLER capability or IInventory — same surface the loader writes
     * against, but driven directly from the test).</p>
     * Pins INV-INFRA-14 (the item unloader pulls items out of a rocket).
     */
    @Pins("INV-INFRA-14")
    @RedWitness(member = "TileRocketUnloader#update", at = "ItemHandlerHelper.insertItem(ownHandler, actuallyExtracted, false)",
            replacedBy = "actuallyExtracted",
            fails = "the unloader's own inventory must hold what it drained", on = "2026-10-19")
    @Test
    public void unloaderPullsItemsFromRocketStorage() throws Exception {
        // THE PAIR MOVES TOGETHER: the unloader sat one block above the rocket's base, a RELATIVE
        // geometry written as two absolute numbers, so moving either alone would separate them
        // while both lines still read plausibly.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1450 + 20, 1450);
        int ux = 1450, uy = rocketSite.y + 1, uz = 1450;
        // Loader meta=2 -> TileRocketUnloader (item unloader).
        ok("stellurgytest place 0 " + ux + " " + uy + " " + uz
                + " stellurgy:loader 2");

        int rocketId = assembleFixture(rocketSite, "with-cargo");

        // Pre-fill rocket's storage inventory tiles (the with-cargo
        // chest) with cobblestone via the dedicated probe.
        String fillResp = arrange("stellurgytest rocket storage-item-fill " + rocketId
                + " minecraft:cobblestone 32").raw();
        assertTrue("storage-item-fill must succeed: " + fillResp,
                Reply.of(fillResp).ok());
        int tilesWithCap = extract(fillResp, TILES_WITH_CAP);
        int totalPlaced = extract(fillResp, TOTAL_PLACED);
        assertTrue("with-cargo fixture must produce at least one IInventory "
                        + "tile inside storage: " + fillResp,
                tilesWithCap >= 1);
        assertTrue("pre-fill must succeed with > 0 items placed: " + fillResp,
                totalPlaced > 0);

        // Sanity: storage-inventory probe agrees with fill result.
        String preStorage = read("stellurgytest rocket storage-inventory " + rocketId).raw();
        // The CONTAINER is what this claim is about, and it is already addressed: the reply was
        // fetched for THIS rocket. Its contents are then an existence question — the fixture chose
        // no slot, and two stacks of one item is a stocked rocket, not an ambiguity.
        assertTrue("the rocket's storage must hold the cobblestone the fixture put in: " + preStorage,
                Reply.of("stellurgytest rocket storage-inventory", preStorage)
                        .holdsElement("items", "item", "minecraft:cobblestone"));

        // Link rocket to unloader.
        String link = arrange("stellurgytest infra link 0 " + ux + " " + uy + " " + uz
                + " " + rocketId).raw();
        assertTrue("infra link must succeed: " + link,
                Reply.of(link).bool("linked"));

        // Run the unloader's production update() for 60 ticks. Storage
        // chunk may contain multiple inventory tiles (engine TEs etc.)
        // that the unloader iterates first; 60 ticks comfortably cover
        // the first-empty-slot scan even on the longest TE list.
        Reply ticked = act("stellurgytest tile force-tick 0 " + ux + " " + uy + " " + uz + " 60");
        assertTrue("probe must succeed: resp=" + ticked, ticked.ok());

        // Unloader's own inventory must contain at least one cobblestone
        // — that's the player-visible "drain returning rocket" contract.
        String postUnloader = read("stellurgytest hatch read 0 " + ux + " " + uy + " " + uz).raw();
        String postStorage = read("stellurgytest rocket storage-inventory " + rocketId).raw();
        assertTrue("the unloader's own inventory must hold what it drained: " + postUnloader,
                Reply.of("stellurgytest hatch read", postUnloader)
                        .holdsElement("slots", "item", "minecraft:cobblestone"));
    }

    private void ok(String cmd) throws Exception {
        String resp = arrange(cmd).raw();
        assertTrue("probe must succeed: cmd='" + cmd + "' resp=" + resp,
                Reply.of(resp).ok());
    }

    private int assembleFixture(FixtureSite site, String variant)
            throws Exception {
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`. Open air, so
        // this ASSERTS rather than digs. The rocket is the one the press's own `rocket_born` record
        // names.
        return rockets.assembleAt(site, c -> act(c).raw(), variant, 2, 10,
                "the craft the unloader empties stands in this volume");
    }

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }

    private static int extract(String src, String field) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.integer(field);
    }
}
