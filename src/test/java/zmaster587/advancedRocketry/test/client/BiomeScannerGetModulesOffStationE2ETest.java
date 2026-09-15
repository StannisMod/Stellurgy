package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Repro for finding C172 (MED) — the
 * player-visible client side.
 *
 * <p>{@code TileBiomeScanner.getModules} runs inside {@code if (world.isRemote)}
 * and dereferences
 * {@code SpaceObjectManager.getSpaceStationFromBlockCoords(pos).getOrbitingPlanetId()}
 * with no null guard (TileBiomeScanner.java:78-79). When the scanner is NOT on a
 * registered space station that lookup returns null, so building the scanner's
 * modular GUI NPEs on the client — a client crash for the player opening it. The
 * bug only fires when {@code suitable} is also true (the column below is all air).</p>
 *
 * <p><b>Why {@code getModules} is driven directly:</b> the GUI-open path
 * ({@code BlockMultiblockMachine.onBlockActivated}) only opens the GUI once the
 * multiblock {@code isComplete()}, and the scanner structure requires a
 * {@code blockAluminum}-oredict block that only an external mod provides — so the
 * scanner cannot be assembled in the bare test environment. The bug is in
 * {@code getModules}, a public method independent of assembly, so the test drives
 * that exact client-side production method on a real client world/tile via the
 * {@code tile_modules_throws} bridge command, off-station.</p>
 *
 * <p><b>Corrected contract, pinned here (C172 fix, Path B — null-guard)</b>:
 * building the scanner GUI off-station does not throw.</p>
 */
public class BiomeScannerGetModulesOffStationE2ETest extends AbstractClientE2ETest {

    private static final int X = 8, Y = FixtureSite.OPEN_AIR_Y, Z = 8;

    /** How long the client is given to APPLY the server's placement, in ticks — a ceiling on one
     *  round trip, not a guess at how long a teleport takes. */
    private static final int PLACEMENT_LINK_BUDGET_TICKS = 200;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void buildingScannerGuiOffStationDoesNotThrowOnClient() throws Exception {
        bot().waitForWorld();

        // Overworld (dim 0) has no space stations, so getSpaceStationFromBlockCoords
        // is null there — the off-station case.
        String place = exec("artest place 0 " + X + " " + Y + " " + Z + " advancedrocketry:biomeScanner");
        assertTrue("scanner must place: " + place, place.contains("\"placed\":true"));

        // Clear the column below the scanner so getModules' `suitable` gate is true;
        // that is the branch that reaches the null deref.
        exec("fill " + X + " 1 " + Z + " " + X + " " + (Y - 1) + " " + Z + " minecraft:air");
        // Stand the player on the scanner so its chunk is client-tracked. The tracking follows the
        // CLIENT's own position, so the placement is waited for as the packet that applies it —
        // thirty ticks were a bet on a round trip, and the read below is a client read.
        Events clientLog = ClientEvents.of(bot());
        long standMark = clientLog.mark();
        exec("tp @a " + (X + 0.5) + " " + (Y + 1) + " " + (Z + 0.5) + " 0 60");
        ClientEvents.awaitPlacedNear(clientLog, standMark, X + 0.5, Z + 0.5,
                "the scanner's chunk is sent because the CLIENT is standing on it",
                PLACEMENT_LINK_BUDGET_TICKS);
        // AND THE CHUNK ITSELF, which is a different fact and the one the read below needs. The
        // player arriving is what makes the server send it; `chunk_data_applied` is where the client
        // finishes applying it. The thirty ticks this replaces stood for BOTH facts at once, and
        // that is why one link was not enough: measured 2026-09-15, the placement link alone left
        // the read answering "no tile at pos" — the honest answer to a question asked of a client
        // that did not have the blocks yet.
        clientLog.awaitCarrying(standMark, "chunk_data_applied",
                "\"cx\":" + (X >> 4) + ",\"cz\":" + (Z >> 4),
                "the client must hold the scanner's own chunk before its tile is asked for a GUI",
                PLACEMENT_LINK_BUDGET_TICKS);

        JsonObject res = bot().tileModulesThrows(X, Y, Z);
        assertFalse("building the biome-scanner GUI off-station must not throw on the "
                        + "client (getModules must null-guard the absent space station): " + res,
                res.get("threw").getAsBoolean());
    }
}
