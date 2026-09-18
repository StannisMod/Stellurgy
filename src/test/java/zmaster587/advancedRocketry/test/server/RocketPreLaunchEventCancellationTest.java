package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Contract of
 * {@link zmaster587.advancedRocketry.api.RocketEvent.RocketPreLaunchEvent}'s
 * {@code @Cancelable} annotation.
 *
 * <p>The event is part of AR's public {@code api/} surface and companion
 * mods subscribe to it expecting cancellation to actually prevent the
 * launch. Production flow at
 * {@code EntityRocket.prepareLaunch}:{@code 1705-1712}:</p>
 *
 * <pre>{@code
 *   RocketPreLaunchEvent event = new RocketPreLaunchEvent(this);
 *   MinecraftForge.EVENT_BUS.post(event);
 *   if (!event.isCanceled()) {
 *       // ... send launch packet, set LAUNCH_COUNTER = 200
 *   }
 * }</pre>
 *
 * <p>If the {@code !event.isCanceled()} guard is ever removed or
 * inverted, every companion mod's cancellation logic breaks silently.
 * This test pins the contract via a probe-installed listener that
 * conditionally cancels the event:</p>
 *
 * <ul>
 *   <li>armed &rarr; prepareLaunch fires event &rarr; cancelled &rarr; LAUNCH_COUNTER
 *       stays at default -1 (countdown never starts).</li>
 *   <li>disarmed &rarr; prepareLaunch fires event &rarr; not cancelled &rarr;
 *       LAUNCH_COUNTER set to 200 (countdown started).</li>
 *   <li>The probe-side counter (events observed vs cancelled) proves
 *       the listener actually received both fires.</li>
 * </ul>
 *
 * <p><b>Why this is contract-level, not impl</b>: {@code @Cancelable}
 * is a Forge framework annotation tied to the event-bus dispatch
 * mechanism. AR's javadoc at the event declaration says
 * "Cancelling the event aborts the launch" — that's the public
 * promise to API consumers. Without this test, the contract is
 * implicit and could regress on the next refactor.</p>
 */
public class RocketPreLaunchEventCancellationTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ENTITY_ID = "entityId";
    private static final String OBSERVED = "observed";
    private static final String CANCELLED = "cancelled";

    private static final int CY = FixtureSite.OPEN_AIR_Y;
    /** Two well-separated rocket fixtures so the cancel test and the
     *  no-cancel test each have their own pad — same shared harness,
     *  different geometry, no cross-state. */
    private static final int CX_CANCEL    = 6000;
    private static final int CX_NO_CANCEL = 6300;
    private static final int CZ           = 6000;

    @After
    public void disarmCancellation() throws Exception {
        // Belt-and-braces: even if @Test threw before its finally ran,
        // disarm here. A leaked-armed canceller would break every
        // subsequent rocket-launch test in the shared harness.
        exec("artest rocket disarm-prelaunch-cancel");
    }

    @Test
    public void cancellingPreLaunchPreventsLaunchCountdown() throws Exception {
        int entityId = buildAndAssemble(CX_CANCEL);
        try {
            // Arm the canceller. Subsequent prepareLaunch calls fire
            // the event; the test listener cancels it.
            String arm = exec("artest rocket arm-prelaunch-cancel");
            assertTrue("arm probe failed: " + arm,
                    Reply.of(arm).bool("armed", false));

            String launch = exec("artest rocket launch " + entityId + " true prepare");
            assertTrue("rocket launch (prepare mode) must not error even when "
                            + "cancelled: " + launch,
                    Reply.of(launch).ok() || Reply.of(launch).has("entityId"));

            RocketInfo info = RocketInfo.byId(WorldCommandFixtures::exec, entityId);
            assertEquals("cancelled prepareLaunch must leave LAUNCH_COUNTER "
                            + "at its default (-1) — countdown must NOT have "
                            + "started: " + info.raw(),
                    -1, info.launchCounter);
            assertFalse("isInFlight must remain false after cancelled launch: "
                            + info.raw(),
                    info.inFlight);

            // The listener must have observed the event and cancelled it —
            // proves the test toggle actually wired through.
            String counts = exec("artest rocket prelaunch-cancel-counts");
            assertTrue("listener observed count must be >= 1: " + counts,
                    extract(counts, OBSERVED) >= 1);
            assertTrue("listener cancelled count must be >= 1: " + counts,
                    extract(counts, CANCELLED) >= 1);
        } finally {
            exec("artest rocket disarm-prelaunch-cancel");
        }
    }

    @Test
    public void nonCancelledPreLaunchSetsCountdownAndProceeds() throws Exception {
        int entityId = buildAndAssemble(CX_NO_CANCEL);
        // Explicit disarm (idempotent with default) so a stale @After
        // from an unrelated test ordering can't leak armed state in.
        exec("artest rocket disarm-prelaunch-cancel");

        String launch = exec("artest rocket launch " + entityId + " true prepare");
        assertTrue("rocket launch (prepare mode) must succeed when not cancelled: "
                        + launch,
                Reply.of(launch).ok() || Reply.of(launch).has("entityId"));

        RocketInfo info = RocketInfo.byId(WorldCommandFixtures::exec, entityId);
        assertEquals("uncancelled prepareLaunch must seed LAUNCH_COUNTER to 200 "
                        + "(the countdown tick budget): " + info.raw(),
                200, info.launchCounter);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private int buildAndAssemble(int baseX) throws Exception {
        // Reproduces RocketAssemblySmokeTest.buildAndAssemble's hygiene
        // without depending on its package-private helper.
        int cx1 = (baseX - 2) >> 4, cz1 = (CZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (CZ + 7) >> 4;
        exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        exec("artest fill 0 " + (baseX - 2) + " " + (CY + 1) + " " + (CZ - 2)
                + " " + (baseX + 7) + " " + (CY + 10) + " " + (CZ + 7)
                + " minecraft:air");

        String fixture = exec("artest fixture rocket 0 " + baseX + " " + CY + " " + CZ
                + " simple");
        assertTrue("fixture build failed: " + fixture,
                Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);

        String assemble = exec("artest rocket assemble 0 "
                + bp[0] + " " + bp[1] + " " + bp[2]);
        assertTrue("assemble must succeed: " + assemble,
                Reply.of(assemble).ok());

        Reply eimReply = Reply.of(assemble);
        assertTrue("no entityId in assemble response: " + assemble, eimReply.has(ENTITY_ID));
        return Integer.parseInt(eimReply.text(ENTITY_ID));
    }

    private static int extract(String src, String field) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.integer(field);
    }
}
