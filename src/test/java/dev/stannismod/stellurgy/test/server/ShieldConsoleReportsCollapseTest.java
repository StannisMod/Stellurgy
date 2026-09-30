package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * A console reports its network as dead once the network dies.
 *
 * <p>This started life as a regression test for a defect that turned out not to exist: the solver's
 * disconnected path does not notify controllers, but the console never depended on that push — its
 * own tick pulls the network state and falls back to a cleared readout when there is none. The test
 * was kept because the property it actually pins is worth pinning, and it was RE-AIMED at that: the
 * fallback path, not the push. It fails if the console stops clearing itself, which is the way this
 * display can really go stale.</p>
 *
 * <p>The wrong version was caught only by re-running this test with the "fix" reverted and watching
 * it pass anyway.</p>
 */
public class ShieldConsoleReportsCollapseTest extends AbstractSharedServerTest {

    /** `SubsystemNetworkStatus.DISCONNECTED` — no source, or no sink, so nothing can flow. */
    private static final int DISCONNECTED = 1;

    private static final int DIM = 0;
    /** The row the network stands on, one above this scenario's own site. */
    private int y;

    /**
     * <p>red-witnessed: with {@code TileEntityShieldConsole:143} latching {@code networkConnected}
     * once it has been true: "a console whose network lost its last source must stop reporting it as
     * live: … \"networkConnected\":true", 2026-09-30. The live-network reading at its head is an
     * arrangement and is not witnessed. The status verdict is not witnessed: a console that kept
     * its previous status stays green, because the working network already reports the
     * disconnected status.</p>
     */
    @Test
    public void aConsoleStopsReportingANetworkThatLostItsLastSource() throws Exception {
        FixtureSite site = clearedSite(2, 2, "a shield source, sink and console in a row");
        y = site.y + 1;
        int z = site.z;
        int source = site.x;
        int sink = source + 1;
        int console = source + 2;

        place("affs:shield_generator", source, z);
        place("affs:field_generator", sink, z);
        place("affs:shield_console", console, z);
        arrange("stellurgytest energy inject " + DIM + " " + source + " " + y + " " + z + " 1000000");

        arrange("stellurgytest shield tick " + DIM);
        Reply working = consoleInfo(console, z);
        assertTrue("premise: with a source and a sink the console must report a live network: "
                + working, working.bool("networkConnected"));

        // Take the source away. The network can no longer move anything, and the console must say so.
        arrange("stellurgytest fill " + DIM + " " + source + " " + y + " " + z + " "
                + source + " " + y + " " + z + " minecraft:air");
        arrange("stellurgytest shield tick " + DIM);

        Reply collapsed = consoleInfo(console, z);
        assertFalse("a console whose network lost its last source must stop reporting it as live: "
                + collapsed, collapsed.bool("networkConnected"));
        assertEquals("and must report the disconnected status rather than the previous one: "
                + collapsed, DISCONNECTED, collapsed.integer("networkStatus"));
    }

    private void place(String block, int x, int z) throws Exception {
        Reply resp = arrange("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue(block + " place failed: " + resp, resp.bool("placed"));
    }

    private Reply consoleInfo(int x, int z) throws Exception {
        return ask("stellurgytest shield console-info " + DIM + " " + x + " " + y + " " + z);
    }
}
