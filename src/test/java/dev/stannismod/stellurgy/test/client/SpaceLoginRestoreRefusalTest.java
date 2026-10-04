package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.Test;

import dev.stannismod.stellurgy.test.LedgerEntry;
import dev.stannismod.stellurgy.test.SubsystemStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The leg where the restore must NOT put anybody on a ship across a server restart: the player who was
 * never aboard at all comes back where vanilla puts him.
 *
 * <p>SEPARATE-BOOT: a server restart that is the subject. The leg stops the server and boots a new one
 * over the same world root, so it cannot share a running server with anything. Its relog-only sibling —
 * the pilot whose ship the server no longer knows — needs no restart and runs in
 * {@code SpaceCellRelogGroupTest}.</p>
 *
 * <p>See {@link AbstractSpaceLoginRestoreClientTest} for the shared fixture.</p>
 */
public class SpaceLoginRestoreRefusalTest extends AbstractSpaceLoginRestoreClientTest {

    /**
     * THE FALSIFIABILITY WITNESS for every positive restart leg, and the reason a green "he is aboard"
     * means anything at all: the same world, the same entry, the same ship in the same cell, the same
     * two boots and the same oracles - but a player who never boarded.
     *
     * <p>He must come back where vanilla would put him, off the ship and carrying no record. Without a
     * leg that comes back negative through these very oracles, "he is aboard his ship" could equally be
     * produced by an oracle that answers "aboard" unconditionally, or by a restore that drags every
     * logging-in player to the nearest ship.</p>
     *
     * <p>red-witnessed: with the login hook ({@code SpaceEventHandler#onPlayerLoadFromFile} at
     * {@code ShipAboardTag.Aboard aboard = ShipAboardTag.of(player)}) giving a player who has no
     * aboard record a standing one on the first settled ship in the ledger — the restore that drags
     * everyone aboard: "a player who was never aboard must come back where vanilla puts him …
     * expected:&lt;0&gt; but was:&lt;3&gt;", 2026-09-28. The line before it is an arrangement.</p>
     */
    @Test
    public void aPlayerWhoWasNeverAboardIsNotRestoredOntoTheShip() throws Exception {
        flyOneShipIntoItsCell();

        // The client never went near the ship. The record must be absent BEFORE the restart too -
        // that is what makes the reading after it attributable to the restore rather than to a
        // record that was never written in the first place.
        String tagBefore = exec("stellurgytest space aboard-tag " + BOT);
        assertTrue("a player who never boarded must carry no aboard record: " + tagBefore,
                (!Reply.of(tagBefore).bool("tagged")));

        closeBoth();
        keepBootLog("boot1-never-aboard");

        serverHarness = RealDedicatedServerHarness.startWith(root, false);

        // The same two discriminators the other legs establish, so this leg differs from them in
        // exactly one thing: whether the player was ever aboard.
        SubsystemStatus statusAfter = SubsystemStatus.read(this::exec);
        assertTrue("the production subsystem must come up again on boot 2: " + statusAfter.raw(),
                statusAfter.registered);
        LedgerEntry ledger = LedgerEntry.forShip(this::exec, arrangedShipId);
        assertTrue("the ship must still be ledgered - a restore with nothing to restore ONTO would "
                + "leave him in the overworld for the wrong reason: " + ledger.raw(),
                ledger.found);

        startClient();
        bot().waitForWorld();
        // WINDOW: an ABSENCE watched for exactly as long as every positive leg waits for the presence
        // (RESTORE_LINK_BUDGET_TICKS, which outlives the re-seat's silent give-up). Shorter, and a
        // restore the positive legs would have accepted could land after the reads; its two ends are
        // the pre-restart record read above and the reads below.
        bot().waitTicks(RESTORE_LINK_BUDGET_TICKS);

        int dim = clientDim();
        JsonObject riding = bot().reportRidingEntity();
        String tag = exec("stellurgytest space aboard-tag " + BOT);
        String observed = "clientDim=" + dim + " riding=" + riding + " tag=" + tag;

        assertTrue("the client must have a world at all before anything can be read from it: "
                + observed, dim != NO_CLIENT_WORLD);
        assertEquals("a player who was never aboard must come back where vanilla puts him, not in "
                + "the ship's cell: " + observed, OVERWORLD_DIM, dim);
        assertFalse("and he must not be seated on a ship he never boarded: " + observed,
                riding.get("riding").getAsBoolean());
        assertTrue("and the record oracle must still answer NO for him - if it cannot, every "
                + "\"tagged\":true in this class is worthless: " + observed,
                (!Reply.of(tag).bool("tagged")));
    }
}
