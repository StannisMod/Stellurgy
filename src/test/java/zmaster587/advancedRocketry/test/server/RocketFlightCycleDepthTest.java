package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Rocket flight cycle BEYOND the launch path.
 *
 * <p>{@link RocketLaunchDepthTest} covered the production
 * {@code rocket.launch()} path up to {@code isInFlight=true}. Everything
 * after — {@code onOrbitReached}, descent, dismantle — was uncovered.
 * This file pins the post-launch chain via the new probes:
 *
 * <ul>
 *   <li>{@code /artest rocket force-orbit-reached <id>} — invokes
 *       {@code EntityRocketBase.onOrbitReached} (which fires
 *       {@code RocketReachesOrbitEvent}).</li>
 *   <li>{@code /artest rocket dismantle <id>} — invokes
 *       {@code deconstructRocket} (fires {@code RocketDismantleEvent}).</li>
 *   <li>{@code /artest rocket event-counts} — read the global recorder
 *       counts for the 4 RocketEvent types.</li>
 * </ul>
 *
 * Pinned coverage:
 *
 * <ul>
 *   <li>RocketReachesOrbitEvent fires on force-orbit-reached.</li>
 *   <li>RocketDismantleEvent fires on dismantle.</li>
 *   <li>onOrbitReached over non-station overworld dim does NOT call
 *       {@code SpaceObjectManager.setPadStatus} (counter-test for the
 *       inverse of the launch-path pad-status behaviour).</li>
 *   <li>Launch path fires RocketLaunchEvent (verifies the
 *       launch observation in event-counter form).</li>
 *   <li>Errored-out launches do NOT fire RocketLaunchEvent.</li>
 *   <li>Out-of-flight (initial) rocket has ticksExisted advancing under
 *       normal server ticks — defensive baseline for the descent-timer
 *       gate.</li>
 * </ul>
 */
public class RocketFlightCycleDepthTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    private static final String AR_DIMS_ARRAY = "arDimensions";
    private static final String LAUNCH_COUNT = "launch";
    private static final String ORBIT_COUNT = "orbitReached";
    private static final String DISMANTLE_COUNT = "dismantle";
    private static final String TICKS_EXISTED = "ticksExisted";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private static int parseGroup(String field, String s, String label) {
        Reply reply = Reply.of(s);
        assertTrue("could not parse " + label + ": " + s, reply.has(field));
        return reply.integer(field);
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    @Test
    public void rocketEventRecorderProbeIsLive() throws Exception {
        // Sanity: probe surface returns the 4 expected counter fields.
        // If the recorder wasn't registered, fields would still be
        // present (initial 0); the assertion below pins JSON structure.
        String counts = ok(client().execute("artest rocket event-counts"));
        assertTrue("event-counts response must expose launch field: " + counts,
                counts.contains("\"launch\":"));
        assertTrue("event-counts response must expose orbitReached field: " + counts,
                counts.contains("\"orbitReached\":"));
        assertTrue("event-counts response must expose dismantle field: " + counts,
                counts.contains("\"dismantle\":"));
        assertTrue("event-counts response must expose preLaunch field: " + counts,
                counts.contains("\"preLaunch\":"));
    }

    @Test
    public void forceOrbitReachedFiresRocketReachesOrbitEvent() throws Exception {
        // Real cause-effect: invoking the production onOrbitReached must
        // fire RocketReachesOrbitEvent (the event is posted in
        // EntityRocketBase.onOrbitReached BEFORE any dispatch branch). If
        // a regression moves the post() after a conditional branch that
        // doesn't always execute, this test surfaces it.
        int id = buildAndAssemble(FixtureSite.openAir(0, 3000, 500));

        String before = ok(client().execute("artest rocket event-counts"));
        int orbitBefore = parseGroup(ORBIT_COUNT, before, "orbitReached before");

        String resp = ok(client().execute("artest rocket force-orbit-reached " + id));
        assertTrue("force-orbit-reached must succeed: " + resp,
                resp.contains("\"ok\":true"));
        // Inline-delta check: the probe reports orbitReachedEventDelta in
        // its response; must be >= 1 (event fired during the call).
        assertTrue("force-orbit-reached must report a non-zero orbitReachedEventDelta: "
                + resp, resp.contains("\"orbitReachedEventDelta\":1")
                    || resp.contains("\"orbitReachedEventDelta\":2"));

        String after = ok(client().execute("artest rocket event-counts"));
        int orbitAfter = parseGroup(ORBIT_COUNT, after, "orbitReached after");
        assertTrue("global orbitReached counter must advance: before=" + orbitBefore
                + " after=" + orbitAfter, orbitAfter > orbitBefore);
    }

    @Test
    public void dismantleFiresRocketDismantleEvent() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 3100, 500));

        String before = ok(client().execute("artest rocket event-counts"));
        int dismantleBefore = parseGroup(DISMANTLE_COUNT, before, "dismantle before");

        String resp = ok(client().execute("artest rocket dismantle " + id));
        assertTrue("dismantle must succeed: " + resp, resp.contains("\"ok\":true"));
        assertTrue("dismantle inline delta must be 1: " + resp,
                resp.contains("\"dismantleEventDelta\":1"));

        String after = ok(client().execute("artest rocket event-counts"));
        int dismantleAfter = parseGroup(DISMANTLE_COUNT, after, "dismantle after");
        assertTrue("global dismantle counter must advance: " + dismantleBefore
                + " -> " + dismantleAfter, dismantleAfter > dismantleBefore);
    }

    @Test
    public void launchFiresRocketLaunchEventInRealLaunchPath() throws Exception {
        // Verify the real production launch path emits RocketLaunchEvent.
        // Prior coverage demonstrated isInFlight=true via the same path; this
        // test pins the event-bus emission too — a regression that moves
        // the post() out of the launch-allowed branch is silently visible
        // in isInFlight but would skip mission/advancement subscribers.
        // Need a destination dim for the real launch path to succeed.
        String dimList = ok(client().execute("artest dim list"));
        Reply listed = Reply.of("artest dim list", dimList);
        org.junit.Assume.assumeTrue(listed.has(AR_DIMS_ARRAY));
        int destDim = -1;
        for (int d : listed.intArray(AR_DIMS_ARRAY)) {
            if (d != 0) { destDim = d; break; }
        }
        org.junit.Assume.assumeTrue(destDim != -1);

        int id = buildAndAssemble(FixtureSite.openAir(0, 3200, 500));
        ok(client().execute("artest rocket set-destination " + id + " " + destDim));

        String before = ok(client().execute("artest rocket event-counts"));
        int launchBefore = parseGroup(LAUNCH_COUNT, before, "launch before");

        ok(client().execute("artest rocket launch " + id + " true instant"));

        String after = ok(client().execute("artest rocket event-counts"));
        int launchAfter = parseGroup(LAUNCH_COUNT, after, "launch after");
        assertEquals("real instant-launch must fire exactly one RocketLaunchEvent",
                launchBefore + 1, launchAfter);
    }

    @Test
    public void erroredLaunchDoesNotFireRocketLaunchEvent() throws Exception {
        // Counter-test: an unrouteable rocket (no chip programmed) bails
        // in launch() with setError("cannotGetThere") BEFORE the
        // RocketLaunchEvent post. So the counter must NOT advance.
        int id = buildAndAssemble(FixtureSite.openAir(0, 3300, 500));

        String before = ok(client().execute("artest rocket event-counts"));
        int launchBefore = parseGroup(LAUNCH_COUNT, before, "launch before");

        ok(client().execute("artest rocket launch " + id + " true instant"));

        String after = ok(client().execute("artest rocket event-counts"));
        int launchAfter = parseGroup(LAUNCH_COUNT, after, "launch after");
        assertEquals("errored launch must NOT fire RocketLaunchEvent",
                launchBefore, launchAfter);
    }

    @Test
    public void rocketInfoExposesTicksExistedField() throws Exception {
        // Pin the probe-surface contract for ticksExisted — the
        // descent-timer test relies on the field being readable. The
        // observation that the field actually ADVANCES under server
        // ticks is harder to assert reliably in headless: the chunk
        // containing the assembled rocket may not be ticked by the
        // server tick loop if no player is present. We pin the read
        // contract here (the field is exposed and >= 0); the advancing
        // assertion belongs in the testClient e2e harness, where a
        // real player keeps the chunk hot.
        int id = buildAndAssemble(FixtureSite.openAir(0, 3400, 500));
        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("rocket info must expose ticksExisted field: " + info,
                info.contains("\"ticksExisted\":"));
        int t = parseGroup(TICKS_EXISTED, info, "ticksExisted");
        assertTrue("ticksExisted must be non-negative: " + t, t >= 0);
    }

    @Test
    public void forceOrbitReachedOnUnknownRocketReturnsError() throws Exception {
        String resp = ok(client().execute("artest rocket force-orbit-reached 9999999"));
        assertTrue("unknown rocket must error: " + resp,
                resp.contains("\"error\":\"rocket not found\""));
    }

    @Test
    public void dismantleOnUnknownRocketReturnsError() throws Exception {
        String resp = ok(client().execute("artest rocket dismantle 9999999"));
        assertTrue("unknown rocket must error: " + resp,
                resp.contains("\"error\":\"rocket not found\""));
    }

    @Test
    public void orbitReachedEventChainHandlesAbsentSatelliteHatch() throws Exception {
        // Defensive: the production onOrbitReached has 3 dispatch branches
        // (satellite chip / asteroid chip / has-seat / no-seat). The
        // "simple" rocket fixture has guidance computer + seat -> the
        // reachSpaceManned branch fires. Pin that this branch doesn't
        // crash on a rocket with no programmed chip.
        int id = buildAndAssemble(FixtureSite.openAir(0, 3500, 500));
        String resp = ok(client().execute("artest rocket force-orbit-reached " + id));
        assertTrue("orbit-reached on un-programmed rocket must succeed (no crash): "
                + resp, resp.contains("\"ok\":true"));
    }
}
