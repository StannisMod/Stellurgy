package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Layer-1 gate: does Valkyrien Skies light up on a dynamically-created space POOL world?
 *
 * <p>A VS ship can only live in a slot if VS attaches its per-world ship manager to that world.
 * This confirms the capability/manager attaches to a {@code WorldProviderSpaceSlot} world, not just
 * the vanilla/AR dimensions — the cheap gate before the full ship round-trip (which needs a client
 * to load a ship, so it lives at the client tier). Run with; skipped otherwise.</p>
 */
public class SpaceSlotVsSupportTest extends AbstractSharedServerTest {

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void vsShipSupportAttachesToAPoolWorld() throws Exception {
        String r = exec("artest space vs-cap deep");
        assertTrue("vs-cap must complete: " + r, r.contains("\"ok\":true"));
        assertTrue("VS ship support (per-world ship manager) must attach to a pool world: " + r,
                r.contains("\"vsShipSupport\":true"));
    }
}
