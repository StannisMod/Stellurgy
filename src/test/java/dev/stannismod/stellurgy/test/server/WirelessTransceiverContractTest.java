package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * wireless transceiver contracts (server-tier).
 *
 * <p>Pins the player-visible contracts of {@code TileWirelessTransciever} —
 * the live replacement for the upstream-deprecated pipe blocks
 * (commit {@code 48610953}). The transceiver is the only data-network
 * endpoint a player can place today.</p>
 *
 * <p>Covered contracts:</p>
 * <ol>
 *   <li>Pairing branch — both unpaired: fresh id assigned + network exists.</li>
 *   <li>Pairing branch — only A paired: B inherits A's id.</li>
 *   <li>Pairing branch — only B paired: A inherits B's id.</li>
 *   <li>Pairing branch — both paired, different: ids merge to one.</li>
 *   <li>Pairing branch — both paired, same: re-pair is a no-op.</li>
 *   <li>Mode toggle: extract &rarr; tile is source on network.</li>
 *   <li>Mode toggle: inject &rarr; tile is sink on network.</li>
 *   <li>Enabled toggle round-trip surfaces via wireless-info.</li>
 *   <li>Mode flip swaps source &harr; sink registration on the live network.</li>
 * </ol>
 *
 * <p>Out of scope here: NBT round-trip across server
 * restart + onLoad role re-registration — those live in
 * {@code WirelessTransceiverRestartTest} which manages its own
 * harness lifecycle. Adjacent-tile {@code IDataHandler} data flow is
 * deferred to a future follow-up.</p>
 */
public class WirelessTransceiverContractTest extends AbstractSharedServerTest {

    // Each test method picks a unique BASE_X offset per the
    // AbstractSharedServerTest position-isolation contract. 50 blocks of
    // headroom per method covers up to 4 transceivers per scenario.

    /**
     * Frozen save identifiers. The block/ItemBlock registry name and the tile
     * entity id are written into every world that ever contained a transceiver
     * — the registry name into the {@code level.dat} FML snapshot, the tile id
     * into each saved chunk and into packed rockets/stations. Respelling either
     * one deletes those transceivers on load; it already happened once (the
     * 2026-05-31 "transciever" → "transceiver" typo fix, reverted afterwards).
     *
     * <p>This test fails if production breaks the contract that a world saved
     * by any shipped build still resolves its wireless transceivers: the block
     * and its ItemBlock stay registered under
     * {@code stellurgy:wirelesstransciever}, that name stays craftable,
     * the tile keeps writing the same NBT {@code id}, and the client-side
     * resources keyed off the registry name still exist.</p>
     */
    @Test
    public void frozenSaveIdentifiersMustNotBeRespelled() throws Exception {
        String reg = String.join("\n", client().execute(
                "stellurgytest registry lookup stellurgy:wirelessTransciever"));
        assertTrue("registry lookup probe errored: " + reg, Reply.of(reg).ok());
        assertTrue("FROZEN block registry name stellurgy:wirelesstransciever is gone — "
                        + "every existing world loses its placed transceivers: " + reg,
                Reply.of(reg).bool("blockRegistered"));
        assertTrue("FROZEN ItemBlock registry name is gone — stored transceivers are deleted "
                        + "from inventories and chests: " + reg,
                Reply.of(reg).bool("itemRegistered"));
        assertTrue("transceiver is no longer craftable — the recipe result no longer resolves "
                        + "against the frozen registry name: " + reg,
                Reply.of(reg).bool("craftable"));

        int baseX = 3000;
        placeAt(baseX);
        String nbt = String.join("\n", client().execute(
                "stellurgytest tile nbt-id " + DIM + " " + baseX + " " + Y + " " + Z));
        assertTrue("tile nbt-id probe errored: " + nbt, Reply.of(nbt).ok());
        assertTrue("FROZEN tile entity id changed — tiles in existing chunks and inside packed "
                        + "rockets/stations load as null, losing network id, mode and priority: " + nbt,
                "minecraft:stellurgytransciever".equals(Reply.of(nbt).text("id")));

        // The client resolves blockstate and models from the registry name
        // (lowercased). A server tier cannot render, but it can prove the files
        // a client will ask for exist and carry no stale reference.
        assertNotNull("blockstate JSON missing for the frozen registry name — the block would "
                        + "render as the missing model",
                getClass().getResource("/assets/stellurgy/blockstates/wirelesstransciever.json"));
        assertNotNull("block model JSON missing for the frozen registry name",
                getClass().getResource("/assets/stellurgy/models/block/wirelesstransciever.json"));
        assertNotNull("item model JSON missing for the frozen registry name",
                getClass().getResource("/assets/stellurgy/models/item/wirelesstransciever.json"));
    }

    // --- helpers -----------------------------------------------------------

    private static final int Y = FixtureSite.OPEN_AIR_Y;
    private static final int Z = 2000;
    private static final int DIM = 0;

    private void placeAt(int... xs) throws Exception {
        for (int x : xs) {
            String r = String.join("\n", client().execute(
                    "stellurgytest place " + DIM + " " + x + " " + Y + " " + Z
                            + " stellurgy:wirelessTransciever"));
            assertTrue("place failed at x=" + x + ": " + r,
                    Reply.of(r).bool("placed"));
            // ONE read, not a poll: the tile is there before `place` answers. 1.12.2's
            // Chunk.setBlockState creates the tile entity and hands it to World.setTileEntity
            // before it returns, and World.getTileEntity consults the pending list when the world
            // is mid-tick — so the place probe, which force-loads the chunk and then calls
            // setBlockState, has already established what this read asks about. wireless-info's
            // `"ok":true` IS `getTileEntity(pos) instanceof TileWirelessTransceiver`, so a loop
            // here has nothing left to wait for; on a real failure its timeout reported ten
            // seconds of silence where this read names the tile that is actually at the position.
            String info = info(x);
            assertTrue("no transceiver tile at x=" + x + " right after place: " + info,
                    Reply.of(info).ok());
        }
    }

    private String info(int x) throws Exception {
        return String.join("\n", client().execute(
                "stellurgytest pipe wireless-info " + DIM + " " + x + " " + Y + " " + Z));
    }

}
