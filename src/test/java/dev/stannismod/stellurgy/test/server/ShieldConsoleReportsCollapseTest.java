package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkStatus;
import dev.stannismod.stellurgy.test.Reply;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.exec;

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
 * <p>The first version of this test pinned the wrong property, and that was caught only by reverting
 * the "fix" it was written for and watching the test pass anyway — a test that cannot fail says
 * nothing about the thing it names. Every step here is driven by the probe on the server thread
 * ({@code shield tick} runs one solve pass), so each read follows the step it is about.</p>
 */
public class ShieldConsoleReportsCollapseTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;

    /**
     * red-witnessed: with {@code TileEntityShieldConsole#applyNetworkState} at {@code networkConnected = shieldState.isConnected();} made sticky
     * ({@code networkConnected || isConnected()}), this fails with "a console whose network lost its
     * last source must stop reporting it as live: {...networkConnected:true,networkStatus:1...}"; and
     * with {@code TileEntityShieldConsole#applyNetworkState} at {@code networkStatus = shieldState.getStatus();} keeping the previous status whenever the new one
     * is DISCONNECTED, the status verdict fails with "expected:&lt;1&gt; but was:&lt;2&gt;". 2026-09-30.
     */
    @Test
    public void aConsoleStopsReportingANetworkThatLostItsLastSource() throws Exception {
        int z = 900;
        int source = 1200;
        int sink = source + 1;
        int console = source + 2;

        place("affs:shield_generator", source, z);
        place("affs:field_generator", sink, z);
        place("affs:shield_console", console, z);
        Reply.of(exec("stellurgytest energy inject " + DIM + " " + source + " " + Y + " " + z + " 1000000"))
                .requireOk("feed the generator");

        Reply.of(exec("stellurgytest shield tick " + DIM)).requireOk("solve the network");
        Reply working = consoleInfo(console, z);
        assertTrue("premise: with a source and a sink the console must report a live network: "
                + working, working.bool("networkConnected"));

        // Take the source away. The network can no longer move anything, and the console must say so.
        Reply.of(exec("stellurgytest fill " + DIM + " " + source + " " + Y + " " + z + " "
                + source + " " + Y + " " + z + " minecraft:air")).requireOk("remove the source");
        Reply.of(exec("stellurgytest shield tick " + DIM)).requireOk("solve the network");

        Reply collapsed = consoleInfo(console, z);
        assertTrue("a console whose network lost its last source must stop reporting it as live: "
                + collapsed, !collapsed.bool("networkConnected"));
        assertEquals("and must report the disconnected status rather than the previous one: "
                + collapsed, SubsystemNetworkStatus.DISCONNECTED, collapsed.integer("networkStatus"));
    }

    private void place(String block, int x, int z) throws Exception {
        Reply placed = Reply.of(exec("stellurgytest place " + DIM + " " + x + " " + Y + " " + z + " " + block));
        assertTrue(block + " place failed: " + placed, placed.bool("placed"));
    }

    /**
     * What the console displays, after one tick of the console driven on the server thread.
     *
     * <p>The console PULLS the network state on its own tick; a solve pass does not push to it. A
     * read straight after {@code shield tick} would therefore be of whatever console tick happened to
     * fall between two probe round trips — the write of the solve, not its adoption. Driving the
     * console's tick here makes the adoption part of the step the read follows.</p>
     */
    private Reply consoleInfo(int x, int z) throws Exception {
        Reply.of(exec("stellurgytest tile force-tick " + DIM + " " + x + " " + Y + " " + z + " 1"))
                .requireOk("tick the console so it pulls the network state");
        return Reply.of(exec("stellurgytest shield console-info " + DIM + " " + x + " " + Y + " " + z));
    }
}
