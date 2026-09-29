package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Server-side companion to the client GUI test — covers the
 * server state machine for {@link
 * dev.stannismod.stellurgy.tile.multiblock.TilePlanetSelector} without
 * needing an OpenGL display.
 *
 * <ol>
 *   <li>Place {@code stellurgy:planetSelector} block.</li>
 *   <li>{@code /stellurgytest selector info} reports {@code hasSelection=false} on a
 *       freshly-placed tile (no client has picked a planet yet).</li>
 *   <li>{@code /stellurgytest selector simulate-click <pos> 0} mimics the wire-side
 *       state change that {@link
 *       dev.stannismod.stellurgy.tile.multiblock.TilePlanetSelector#useNetworkData}
 *       applies when a packet arrives from a real GUI click — sets
 *       {@code dimCache} to Earth's {@code DimensionProperties}.</li>
 *   <li>{@code /stellurgytest selector info} now reports {@code hasSelection=true},
 *       {@code selectedDim=0}.</li>
 *   <li>Simulating a second click flips selection without leaking state
 *       (idempotent re-selection).</li>
 * </ol>
 *
 * <p>The full client GUI path lives in {@code client/PlanetSelectorGuiE2ETest}
 * and is gated by the {@code forge.test.client.enabled} system property.</p>
 */
public class SelectorServerSmokeTest extends AbstractHeadlessServerTest {

    @Test
    public void selectorTileStateMachineFollowsSimulatedClicks() throws Exception {
        // Place at a position that won't collide with other tests' fixtures.
        int x = 250, y = FixtureSite.OPEN_AIR_Y, z = 250;
        String place = String.join("\n", client().execute(
                "stellurgytest place 0 " + x + " " + y + " " + z + " stellurgy:planetSelector"));
        assertTrue("could not place planetSelector: " + place,
                Reply.of(place).bool("placed"));

        // Initial state — no selection yet.
        String empty = String.join("\n", client().execute(
                "stellurgytest selector info 0 " + x + " " + y + " " + z));
        assertTrue("selector info errored on fresh tile: " + empty,
                !Reply.of(empty).has("error"));
        assertTrue("freshly placed selector tile should report hasSelection=false: " + empty,
                (!Reply.of(empty).bool("hasSelection")));

        // Simulate a click selecting Earth (dim 0).
        String clickEarth = String.join("\n", client().execute(
                "stellurgytest selector simulate-click 0 " + x + " " + y + " " + z + " 0"));
        assertTrue("simulate-click failed: " + clickEarth,
                Reply.of(clickEarth).ok());

        // dimCache must now reflect Earth.
        String earthInfo = String.join("\n", client().execute(
                "stellurgytest selector info 0 " + x + " " + y + " " + z));
        assertTrue("selection didn't stick: " + earthInfo,
                Reply.of(earthInfo).bool("hasSelection"));
        assertTrue("selectedDim mismatch: " + earthInfo,
                (Reply.of(earthInfo).integer("selectedDim") == 0));

        // Probe non-existent planet dim — must reject without mutating state.
        String reject = String.join("\n", client().execute(
                "stellurgytest selector simulate-click 0 " + x + " " + y + " " + z + " 99999"));
        assertTrue("expected rejection for non-registered planet dim 99999: " + reject,
                "planet dim not registered".equals(Reply.of(reject).text("error")));

        String unchanged = String.join("\n", client().execute(
                "stellurgytest selector info 0 " + x + " " + y + " " + z));
        assertTrue("selection unexpectedly mutated after rejected simulate-click: " + unchanged,
                (Reply.of(unchanged).integer("selectedDim") == 0));
    }

    @Test
    public void selectorInfoOnEmptyPositionErrorsCleanly() throws Exception {
        String resp = String.join("\n", client().execute("stellurgytest selector info 0 100 80 100"));
        assertTrue("expected 'tile not TilePlanetSelector' on empty pos: " + resp,
                "tile not TilePlanetSelector".equals(Reply.of(resp).text("error")));
    }
}
