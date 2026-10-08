package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

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
     * <p>red-witnessed: with {@code TileEntityShieldConsole#applyNetworkState} at {@code networkConnected = shieldState.isConnected();} latching {@code networkConnected}
     * once it has been true: "a console whose network lost its last source must stop reporting it as
     * live: … \"networkConnected\":true", 2026-09-30, re-run under the battery arrangement.
     * STATUS — {@code TileEntityShieldConsole#applyNetworkState} at {@code networkStatus = shieldState.getStatus();} keeping its previous
     * status once the network is no longer connected: "and must report the disconnected status
     * rather than the previous one (2): … \"networkStatus\":2 … expected:&lt;1&gt; but
     * was:&lt;2&gt;", 2026-09-30. The two premises at its head are arrangements and are not
     * witnessed.</p>
     * Pins INV-NET-04 (a component with no source reports DISCONNECTED to controllers).
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
        // A generator FED EVERY TICK, by a creative battery beside it (off the row, touching only the
        // generator). A one-off energy inject is not a working network for long: the generator takes
        // at most one tick's conversion per call, and the sink drains that in the same tick, so every
        // solve after it offers nothing and reports the DISCONNECTED status — the very status asserted
        // at the end, which is how a console that kept its previous status used to pass here.
        // Measured: `sourceAvailable:0, networkStatus:1` two ticks after such an inject.
        place("libvulpes:creativePowerBattery", source, z + 1);

        // EXPERIMENT: two server ticks are the dose, not a wait: one for the battery to feed the
        // generator and the generator to convert, one more for a solve to see it on offer. The network
        // solves at the END of a server tick, after every tile, so these are real ticks, not forced
        // solves.
        GameTicks.advance(client(), GameTicks.server(), 2);
        Reply working = consoleInfo(console, z);
        assertTrue("premise: with a source and a sink the console must report a live network: "
                + working, working.bool("networkConnected"));
        int previousStatus = working.integer("networkStatus");
        requireArranged("premise: the working network must report a status OTHER than disconnected,"
                + " or a console that kept it would pass the last verdict: " + working,
                previousStatus != DISCONNECTED);

        // Take the source away. The network can no longer move anything, and the console must say so.
        arrange("stellurgytest fill " + DIM + " " + source + " " + y + " " + z + " "
                + source + " " + y + " " + z + " minecraft:air");
        arrange("stellurgytest shield tick " + DIM);

        Reply collapsed = consoleInfo(console, z);
        assertFalse("a console whose network lost its last source must stop reporting it as live: "
                + collapsed, collapsed.bool("networkConnected"));
        assertEquals("and must report the disconnected status rather than the previous one ("
                + previousStatus + "): " + collapsed, DISCONNECTED, collapsed.integer("networkStatus"));
    }

    private void place(String block, int x, int z) throws Exception {
        Reply resp = arrange("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue(block + " place failed: " + resp, resp.bool("placed"));
    }

    private Reply consoleInfo(int x, int z) throws Exception {
        return ask("stellurgytest shield console-info " + DIM + " " + x + " " + y + " " + z);
    }
}
