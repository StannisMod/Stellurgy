package dev.stannismod.stellurgy.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;

import static org.junit.Assert.assertTrue;

/**
 * A player who dies keeps the record of which ship he belongs to.
 *
 * <p>The aboard record is durable per-player state, and the rule for it is the player's point of
 * view: a death does not make him forget his ship. Vanilla builds a NEW player entity on respawn, so
 * anything that lives on the old one is gone unless something carries it across — which is what the
 * bindings capability's clone handler exists to do. Before it existed a logout kept the record and a
 * death did not.</p>
 *
 * <p>A CLIENT test because the respawn is the client's to ask for: the server only rebuilds the
 * player when the death screen's respawn button sends the request, and that is the path a player
 * takes. The record is stamped directly — how he came to be aboard is the boarding tests' subject, and
 * this one is about what survives the death.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PlayerDeathKeepsHisAboardRecordE2ETest extends AbstractSharedClientE2ETest {

    /** A deadline for a discrete record — the death screen opening, the respawn arriving. */
    private static final int DEATH_LINK_BUDGET_TICKS = 200;

    /**
     * Client ticks after the death screen opens before its respawn button accepts a click.
     *
     * <p>{@code GuiGameOver.updateScreen} enables the buttons on its twentieth call
     * ({@code GuiGameOver:190-192}), and it is called once per client tick while the screen is open —
     * so this counts the screen's own clock, not a guess at a server. One more than twenty covers the
     * tick the screen opened on.</p>
     */
    private static final int DEATH_SCREEN_BUTTONS_ENABLE_TICKS = 21;

    /** The respawn button's index on the death screen of a normal (not hardcore) world. */
    private static final int RESPAWN_BUTTON_INDEX = 0;

    private static final String SHIP = "5e3a0f14-7c21-4b8e-9d55-0a1b2c3d4e5f";
    private static final String ABOARD_RECORD = "aboard record";

    @Override
    protected String subsystem() {
        return "player-bindings";
    }

    /**
     * The record survives a death and a respawn through the death screen.
     *
     * <p>red-witnessed: with {@code CapabilityPlayerBindings.carryAcrossDeath} not copying the aboard
     * record onto the respawned player: "a player who dies must still know which ship he belongs to …
     * {\"bound\":[]}", 2026-09-28.</p>
     */
    @Test
    public void aPlayerWhoDiesStillKnowsWhichShipHeBelongsTo() throws Exception {
        scenario().arranging("stamp the aboard record on the real player");
        String bind = exec("stellurgytest player bind-aboard " + SHIP);
        scenario().requireArranged("the arrangement must actually stamp the record: " + bind,
                Reply.of(bind).bool("tagged"));
        // THE CONTROL: the record is REPORTED before the death, by the same witness asked after it.
        // Without this, "still reported afterwards" could be a witness that always says so.
        String before = exec("stellurgytest player bindings");
        assertTrue("before the death the player must be reported bound to his ship: " + before,
                Reply.of(before).holdsText("bound", ABOARD_RECORD));

        scenario().asserting("the player dies, respawns through the death screen, and keeps the record");
        Events client = clientEvents();
        long deathMark = client.mark();
        exec("kill @a");
        client.awaitMatching(deathMark, "client_gui_opened",
                seen -> !Events.recordsWhere(seen, "gui", "GuiGameOver").isEmpty(),
                "opening the death screen",
                "the kill must bring the player's client to the death screen", DEATH_LINK_BUDGET_TICKS);
        // WINDOW: the death screen's own enabling timer, counted on the clock that runs it.
        bot().waitTicks(DEATH_SCREEN_BUTTONS_ENABLE_TICKS);
        long respawnMark = client.mark();
        bot().clickButton(RESPAWN_BUTTON_INDEX);
        // The server rebuilds the player and tells the client with a respawn packet — the moment the
        // new entity exists, and the one after which the question below is about it.
        client.awaitMatching(respawnMark, "client_dimension_changed",
                seen -> !Events.recordsWhere(seen, "via", "respawn").isEmpty(),
                "carrying via = respawn",
                "pressing respawn must make the server rebuild the player", DEATH_LINK_BUDGET_TICKS);

        String after = exec("stellurgytest player bindings");
        assertTrue("a player who dies must still know which ship he belongs to — the respawned player"
                + " must be reported bound to it: " + after,
                Reply.of(after).holdsText("bound", ABOARD_RECORD));
    }
}
