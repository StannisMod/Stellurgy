package zmaster587.advancedRocketry.test.server;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Valkyrien Skies is PRESENT — asserted once, here, rather than assumed 110 times everywhere else.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Until 2026-08-21 every ship-touching test opened with
 * {@code Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", …)} — 110
 * of them across 49 files, each backed by its own copy of a {@code serverHasVs()} probe call, 39
 * copies in all. All of it was inherited from a time when VS was an optional dependency. It has not
 * been one for a long while: VS is vendored into AR's own main source set and its classes ship
 * inside the mod, {@code -PwithVS} is not wired into {@code build.gradle} at all, and AR's
 * production mixin against {@code WorldServerShipManager} is {@code required} and applies on every
 * launch — so the condition those guards tested could never have been false.</p>
 *
 * <h2>Why the guards were worse than useless</h2>
 *
 * <p>{@code Assume} skips SILENTLY. Had VS ever genuinely gone missing, 110 tests would not have
 * gone red — they would have quietly vanished from the run and the gate would have stayed green
 * over a mod with no ship physics at all. A guard that can only ever hide a catastrophe is not a
 * guard. This test is the inverse: if VS ever stops being available, exactly one test fails and it
 * says why, while the other 110 fail on their own terms instead of disappearing.</p>
 */
public class VsVendorSmokeTest extends AbstractSharedServerTest {

    @Test
    public void valkyrienSkiesIsOnTheServerClasspathAndReportsAvailable() throws Exception {
        String resp = String.join("\n", client().execute("artest vs available"));
        assertTrue("Valkyrien Skies must be available on the server: it is vendored into AR's own "
                + "main source set and ships inside the mod, so an unavailable answer means the "
                + "vendored tree stopped being compiled in or its integration stopped resolving — "
                + "and every ship test in the suite is meaningless until that is fixed. Got: " + resp,
                resp.contains("\"available\":true"));
    }

    /** A ship registry that answers at all — the integration is wired, not merely on the classpath. */
    @Test
    public void theShipRegistryAnswersForALoadedWorld() throws Exception {
        List<String> lines = client().execute("artest vs ship-count 0");
        String resp = String.join("\n", lines);
        assertTrue("with VS present the ship registry must answer for the overworld (a count of 0 is "
                + "a fine answer; no answer is not): " + resp, resp.contains("\"count\":"));
    }
}
