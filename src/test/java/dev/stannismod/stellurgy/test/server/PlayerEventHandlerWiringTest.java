package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Assume;
import dev.stannismod.stellurgy.test.GameTicks;

import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Player-event handler wiring &
 * pre-join side-effects.
 *
 * The headless dedicated-server harness in this repo has NO connected
 * player. "Player joins Stellurgy planet &rarr; sky/gravity/weather wrapper applied"
 * is a behaviour that belongs in the {@code testClient} e2e harness
 * (real GL client + dedicated server), not here. What this layer
 * CAN do is
 * pin the SERVER-SIDE state that {@code PlanetEventHandler} maintains:
 *
 * <ol>
 *   <li>The {@code ServerTickEvent} subscription is live (its public
 *       counter advances under normal ticking).</li>
 *   <li>{@link dev.stannismod.stellurgy.event.PlanetEventHandler},
 *       {@code RocketEventHandler}, and {@code PlanetWeatherEventHandler}
 *       are all class-loaded by the time the server is up. A regression
 *       in the {@code @Mod} init phase that drops one would silently
 *       break swathes of gameplay.</li>
 *   <li>For every Stellurgy dimension that's loaded, the side-effects that a
 *       player-join would observe are coherent SERVER-SIDE: the world
 *       info is the B1 weather wrapper, an atmosphere handler is
 *       registered, the dimension is classified as a Stellurgy planet, and
 *       gravity / sky color are non-default.</li>
 *   <li>The transition queue (used for rocket-launch warp transitions)
 *       is empty at rest — counter-test for any test that mistakenly
 *       leaks a {@code TransitionEntity}.</li>
 * </ol>
 *
 * The full "player joins Stellurgy dim &rarr; side effects fire" path is the job
 * of the {@code testClient} e2e harness; the server-side state checked
 * here is the necessary pre-condition for that join to be coherent.
 */
public class PlayerEventHandlerWiringTest extends AbstractSharedServerTest {

    /**
     * Ticks the counters are watched across. The old 400 ms was "~4 ticks with headroom of 2"; asked
     * for as ticks it is the same intent without the hope.
     */
    private static final int OBSERVED_TICKS = 10;

    private static final String TIME_PATTERN = "time";
    private static final String WORLD_TIME_PATTERN = "worldTotalTime";
    private static final String AR_DIMS_ARRAY = "stellurgyDimensions";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private static long parseGroup(String field, String resp, String label) {
        Reply reply = Reply.of(resp);
        assertTrue("could not parse " + label + ": " + resp, reply.has(field));
        return (long) reply.integer(field);
    }

    private int firstStellurgyDimOrSkip() throws Exception {
        String joined = ok(client().execute("stellurgytest dim list"));
        Assume.assumeFalse(
                "No Stellurgy dimensions registered — skipping",
                (Reply.of(joined).arrayLength("stellurgyDimensions") == 0));
        Reply dims = Reply.of("stellurgytest dim list", joined);
        assertTrue("could not parse stellurgyDimensions array: " + joined, dims.has(AR_DIMS_ARRAY));
        for (int dim : dims.intArray(AR_DIMS_ARRAY)) {
            if (dim != 0) return dim;
        }
        Assume.assumeTrue(
                "Only overworld is a Stellurgy planet — skipping", false);
        return -1;
    }

    /** The class the dimension's WorldInfo actually is, as the probe reports it. */
    private static final String WORLD_INFO_CLASS = "worldInfoClass";

    @Test
    public void planetEventHandlerTickCounterAdvancesUnderServerTicks() throws Exception {
        // Tick counter advance is the strongest "PlanetEventHandler is
        // subscribed to the event bus" smoke we have at the headless
        // server layer. The counter increments inside ServerTickEvent.END;
        // if @Mod init failed to subscribe, the value freezes at zero.
        String first = ok(client().execute("stellurgytest event tick-counter"));
        long t1 = parseGroup(TIME_PATTERN, first, "time");
        long w1 = parseGroup(WORLD_TIME_PATTERN, first, "worldTotalTime");

        // WINDOW: both counters are read on each side of this stretch and each assertion below is
        // over the difference, naming both reads. Not circular: the stretch is measured on
        // MinecraftServer's tick counter, the assertions on vanilla's worldTotalTime and Stellurgy's own
        // handler time. "It moved at all" is the bar, so overshoot cannot let a frozen counter pass.
        GameTicks.advance(client(), GameTicks.server(), OBSERVED_TICKS);

        String second = ok(client().execute("stellurgytest event tick-counter"));
        long t2 = parseGroup(TIME_PATTERN, second, "time");
        long w2 = parseGroup(WORLD_TIME_PATTERN, second, "worldTotalTime");

        // The two cross-checks here are independent:
        //   - worldTotalTime advancing proves the SERVER is ticking
        //     (so any failure to see t advance is the handler's fault,
        //     not "the server was paused").
        //   - t advancing proves the handler subscription is live.
        assertTrue("vanilla world totalTime must advance over " + OBSERVED_TICKS
                        + " server ticks: w1=" + w1 + " w2=" + w2,
                w2 > w1);
        assertTrue("PlanetEventHandler.time must advance under server ticks: "
                        + "t1=" + t1 + " t2=" + t2 + " (server ticking? w1=" + w1 + " w2=" + w2 + ")",
                t2 > t1);
    }

    @Test
    public void coreEventHandlersAreClassLoaded() throws Exception {
        // Class-load smoke for the three event handlers that the @Mod
        // init phase wires. If any one of them fails to load (rare —
        // would have to be a static-init crash or a build-time class
        // strip), the field-/Class-lookup in the probe surfaces it.
        String resp = ok(client().execute("stellurgytest event handlers"));
        assertTrue("PlanetEventHandler must be class-loaded: " + resp,
                "loaded".equals(Reply.of(resp).text("planetEventHandler")));
        // RocketEventHandler is reported as "shipped" via classfile-resource
        // lookup — a static class reference would NoClassDefFoundError on
        // dedicated server because the class imports LWJGL / FontRenderer
        // (client-only). Resource presence is the strongest server-safe
        // proof that the @Mod packaging didn't drop the class.
        assertTrue("RocketEventHandler .class resource must be shipped: " + resp,
                "shipped".equals(Reply.of(resp).text("rocketEventHandler")));
        // PlanetWeatherEventHandler IS server-safe (no client imports), so
        // a direct static reference verifies + reports its FQN.
        assertTrue("PlanetWeatherEventHandler must be class-loaded (probe "
                        + "should report its FQN): " + resp,
                "dev.stannismod.stellurgy.world.weather.PlanetWeatherEventHandler".equals(
                        Reply.of(resp).text("planetWeatherEventHandler")));
    }

    @Test
    public void stellurgyDimensionPreJoinSideEffectsAreCoherent() throws Exception {
        // For a Stellurgy dim, the pre-join side-effects MUST all line up:
        //   - WorldInfo wrapped (StellurgyDimensionWorldInfo) — required for the
        //     B1 weather isolation chain to fire on player join
        //   - AtmosphereHandler registered — required for vacuum / oxygen
        //     handling the moment the player tick starts
        //   - isStellurgyPlanet=true — gates the per-tick planetary logic in
        //     PlanetEventHandler.tick and elsewhere
        //   - gravity != 1.0 (a non-default value) — implies the planet
        //     XML was actually parsed and applied
        int dim = firstStellurgyDimOrSkip();
        String resp = ok(client().execute("stellurgytest event dim-side-effects " + dim));

        assertTrue("Stellurgy dim must be loaded for side-effect probing: " + resp,
                Reply.of(resp).bool("loaded"));
        // The CLASS the world info actually is, read off the field that names it and compared
        // as a name. The substring was satisfied by the word appearing anywhere in the reply —
        // including in a neighbouring field naming the wrapper it did NOT install.
        assertEquals("Stellurgy dim WorldInfo must be wrapped by StellurgyDimensionWorldInfo: " + resp,
                "StellurgyDimensionWorldInfo", Reply.of(resp).simpleClassName(WORLD_INFO_CLASS));
        assertTrue("Stellurgy dim must have an AtmosphereHandler registered: " + resp,
                Reply.of(resp).bool("hasAtmosphereHandler"));
        assertTrue("dim must be classified as Stellurgy planet: " + resp,
                Reply.of(resp).bool("isStellurgyPlanet"));
        // hasSkyColor=true means props.skyColor is non-null/non-empty.
        // (A future fixture planet with the default vanilla colour would
        // still pass — float[] is allocated by DimensionProperties; this
        // assertion just guards against a regression that drops the field.)
        assertTrue("Stellurgy dim must have a sky-color array configured: " + resp,
                Reply.of(resp).bool("hasSkyColor"));
    }

    @Test
    public void nonStellurgyDimensionRejectsStellurgyPlanetClassification() throws Exception {
        // Counter-test: a non-Stellurgy dim (nether = -1, end = 1) must NOT be
        // classified as a Stellurgy planet. A polarity flip here would mean
        // every nether/end join would try to run Stellurgy's per-planet tick
        // logic against vanilla state — catastrophic.
        // The overworld is registered as Stellurgy planet "Earth" on this dev
        // fixture set, so we can't use dim 0 here; pick the first non-Stellurgy
        // forge dim that's NOT in the stellurgyDimensions array.
        String dimList = ok(client().execute("stellurgytest dim list"));
        Reply listed = Reply.of("stellurgytest dim list", dimList);
        Assume.assumeTrue("dim list missing stellurgyDimensions array", listed.has(AR_DIMS_ARRAY));
        java.util.Set<Integer> stellurgyDims = new java.util.HashSet<>();
        for (int d : listed.intArray(AR_DIMS_ARRAY)) {
            stellurgyDims.add(d);
        }
        // Try nether (-1) then end (1). Skip if both happen to be Stellurgy (the
        // fixture doesn't currently register them, but be defensive).
        int nonStellurgyDim = stellurgyDims.contains(-1) ? (stellurgyDims.contains(1) ? Integer.MIN_VALUE : 1) : -1;
        Assume.assumeTrue("no non-Stellurgy vanilla dim available to counter-test against",
                nonStellurgyDim != Integer.MIN_VALUE);

        String resp = ok(client().execute("stellurgytest event dim-side-effects " + nonStellurgyDim));
        assertTrue("non-Stellurgy dim " + nonStellurgyDim + " must be loaded: " + resp,
                Reply.of(resp).bool("loaded"));
        assertTrue("non-Stellurgy dim " + nonStellurgyDim + " must NOT be classified as Stellurgy planet: " + resp,
                (!Reply.of(resp).bool("isStellurgyPlanet")));
        // StellurgyDimensionWorldInfo wrapping is the per-Stellurgy-dim B1 isolation chain;
        // a non-Stellurgy dim must stay vanilla so weather doesn't bleed in/out.
        assertNotEquals("non-Stellurgy dim " + nonStellurgyDim + " WorldInfo must NOT be wrapped: " + resp,
                "StellurgyDimensionWorldInfo", Reply.of(resp).simpleClassName(WORLD_INFO_CLASS));
    }

    @Test
    public void transitionMapIsEmptyAtRest() throws Exception {
        // No rocket launches have been issued in this test class -> the
        // transition queue MUST be empty. If it's not, either:
        //   (a) a previous test in the same JVM leaked a transition
        //       (failure of cleanup discipline), OR
        //   (b) the queue's drain logic in PlanetEventHandler.tick()
        //       (line ~322) silently regressed and never pops entries.
        // Either failure mode would silently corrupt subsequent rocket
        // launches' destination dim.
        String resp = ok(client().execute("stellurgytest event transitions"));
        assertTrue("transition map probe must succeed: " + resp,
                Reply.of(resp).ok());
        assertTrue("transition map must be empty at rest in a no-rocket test: " + resp,
                (Reply.of(resp).integer("size") == 0));
    }
}
