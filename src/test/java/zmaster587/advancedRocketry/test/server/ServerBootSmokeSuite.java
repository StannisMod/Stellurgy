package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * server-boot smoke suite.
 *
 * <p>Consolidates 2 single-method smoke classes that previously each spawned
 * their own dedicated-server JVM into a single {@link AbstractSharedServerTest}
 * subclass (one boot for both methods).</p>
 *
 * <h2>Consolidated from</h2>
 * <ul>
 *   <li>{@code ServerStartupSmokeTest} &rarr; {@link #serverBootsAndCommandsRoundTrip()}</li>
 *   <li>{@code RegistrySmokeTest}      &rarr; {@link #arRegistriesArePopulated()}</li>
 * </ul>
 *
 * <h2>Not folded in</h2>
 * <ul>
 *   <li>{@code CommandsSmokeTest} — 4 methods, already extends
 *       {@link AbstractSharedServerTest}.</li>
 *   <li>{@code HarnessDiagnosticTest} — 2 methods, standalone harness
 *       lifecycle. Diagnostic-purposed; keep separate.</li>
 *   <li>{@code NonARDimensionIsolationTest} — 2 methods that explicitly need
 *       a pristine JVM to count fresh registry entries (see
 *       {@link AbstractSharedServerTest} "When NOT to use this base").</li>
 * </ul>
 *
 * <h2>State-leak audit</h2>
 *
 * <p>Neither method mutates world state, atmosphere, time, or weather.
 * Both are pure server-state read probes.</p>
 */
public class ServerBootSmokeSuite extends AbstractSharedServerTest {

    // ─────────────────────────────────────────────────────────────────────
    // From ServerStartupSmokeTest
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void serverBootsAndCommandsRoundTrip() throws Exception {
        List<String> listOutput = client().execute("list");
        assertTrue("/list returned no output", !listOutput.isEmpty());

        List<String> registry = client().execute("artest registry summary");
        boolean hasRegistryOutput = registry.stream().anyMatch(line -> line.contains("\"blocks\""));
        assertTrue("/artest registry summary missing 'blocks' key: " + registry,
                hasRegistryOutput);
    }

    // ─────────────────────────────────────────────────────────────────────
    // From RegistrySmokeTest
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void arRegistriesArePopulated() throws Exception {
        List<String> output = client().execute("artest registry summary");
        String joined = String.join("\n", output);

        assertTrue("registry summary missing 'blocks' key: " + joined,
                Reply.of(joined).has("blocks"));
        assertTrue("registry summary missing 'items' key: " + joined,
                Reply.of(joined).has("items"));
        assertTrue("registry summary missing 'entities' key: " + joined,
                Reply.of(joined).has("entities"));
        assertTrue("registry summary missing 'biomes' key: " + joined,
                Reply.of(joined).has("biomes"));

        int entitiesCount = Reply.of("artest registry summary", joined).integer("entities");
        assertTrue("entity registry suspiciously small (" + entitiesCount
                        + ") — AR may not have loaded",
                entitiesCount > 1);
    }

}
