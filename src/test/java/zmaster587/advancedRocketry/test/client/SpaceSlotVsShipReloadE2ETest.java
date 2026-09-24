package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.Events;


import static org.junit.Assert.assertTrue;

/**
 * The hardening check the data-survival server test could not reach: with a real client present, a
 * VS ship in a space pool slot must physically RE-LOAD as a live physics object after the slot is
 * unloaded and reloaded (a rebind). This exercises the riskiest path — a synchronous world removal
 * while VS has actually loaded the ship and is ticking its physics — which headless cannot, since VS
 * only loads a ship when an observer is near it.
 *
 * <p>Flow: assemble a ship in a pool slot; a bot enters the pool dimension so VS loads the ship;
 * the bot leaves (a world with a player cannot be unloaded); the slot is rebound (unload saves the
 * ship, reload restores its cell); the bot returns and the ship must load again.
</p>
 */
public class SpaceSlotVsShipReloadE2ETest extends AbstractClientE2ETest {

    private static final String SLOT = "slot";
    private static final String COUNT = "count";

    /** How long the client is given to FOLLOW a dimension transfer the server has performed — one
     *  round trip plus a world teardown and rebuild. */
    private static final int DIM_LINK_BUDGET_TICKS = 200;

    /** Enter the pool dim, then move the bot right on top of the ship (assembled around 0..2,64..66)
     *  so VS proximity-loads it. */
    private void enterPoolNearShip(int slot) throws Exception {
        exec("artest tp " + slot);
        exec("tp @a 1 68 1");
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /**
     * Enter the pool dim on the ship, then wait for production's own record that a ship in it became
     * USABLE (`ship_usable`, carrying the dimension) — from a mark taken before the client moved, so
     * it is this entry's load and not an earlier one. The slot is fresh and holds one ship, so the
     * dimension names it. Then ONE read of the loaded count, for the assertion's message.
     */
    private int enterAndAwaitLoad(Events serverLog, int slot, String what) throws Exception {
        long loadMark = serverLog.markInstrumented();
        enterPoolNearShip(slot);
        serverLog.awaitField(loadMark, "ship_usable", "dim", slot, what, 300);
        Reply mReply = Reply.of(exec("artest vs ship-count " + slot));
        return mReply.has(COUNT) ? Integer.parseInt(mReply.text(COUNT)) : -1;
    }

    @Test
    public void vsShipReloadsLiveAfterASlotRebind() throws Exception {

        // Assemble a ship in a fresh pool slot (cell "deep"). The substrate spawns it from a queue
        // it drains on a later tick, and `ship_spawned` is that drain's own record.
        Events serverLog = new Events(this::exec, bot()::waitTicks);
        long spawnMark = serverLog.markInstrumented();
        String asm = exec("artest space vs-assemble deep");
        Reply mReply = Reply.of(asm);
        assertTrue("vs-assemble must report a slot dim: " + asm, mReply.has(SLOT));
        int slot = Integer.parseInt(mReply.text(SLOT));
        serverLog.await(spawnMark, "ship_spawned", "the assembled ship must be spawned by the"
                + " substrate's queue before its registry can be asked about it", 200);

        // Sanity: the ship must exist in VS's queryable registry (assembly succeeded).
        Reply cmReply = Reply.of(exec("artest vs ship-count-all " + slot));
        int queryable = cmReply.has(COUNT) ? Integer.parseInt(cmReply.text(COUNT)) : -1;
        assertTrue("a ship must be created in the pool world's registry: count-all=" + queryable,
                queryable >= 1);

        // Bot enters the pool dim ON the ship so VS proximity-loads it (physics active).
        int loaded = enterAndAwaitLoad(serverLog, slot,
                "the ship must LOAD (physics) with a client on it in the pool dim");
        assertTrue("the ship must LOAD (physics) with a client on it in the pool dim: loaded=" + loaded,
                loaded >= 1);

        // Bot leaves (a world with a player cannot unload), then rebind the slot. The LEAVING is
        // what matters and it is a dimension change, so it is waited for as the client's own record
        // of one: a world the client has not left yet still holds a player, and the reload asserted
        // below would then be asked of a world that cannot unload.
        Events clientLog = ClientEvents.of(bot());
        long leaveMark = clientLog.mark();
        exec("artest tp 0");
        ClientEvents.awaitDim(clientLog, leaveMark, 0,
                "the bot must actually LEAVE the pool dimension — a world with a player in it"
                        + " cannot unload, and the reload below is about an unloaded one",
                DIM_LINK_BUDGET_TICKS);
        assertTrue("slot must reload after the ship's world is unloaded: ",
                Reply.of(exec("artest space reload " + slot + " deep")).bool("present"));

        // Bot returns onto the ship; it must RE-LOAD live after the rebind.
        int loadedAfter = enterAndAwaitLoad(serverLog, slot,
                "the ship must RE-LOAD live after the slot rebind");
        assertTrue("the ship must RE-LOAD live after the slot rebind: loadedAfter=" + loadedAfter,
                loadedAfter >= 1);

        exec("artest tp 0");
    }
}
