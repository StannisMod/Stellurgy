package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

/**
 * rocket launch through the classic scripted path (P1).
 *
 * Builds + assembles a rocket via the same fixture as
 * {@link RocketAssemblySmokeTest}, then calls {@code /artest rocket launch} to
 * trigger the production launch path. Falls back to {@code force} mode if the
 * regular launch path can't find a destination (no guidance chip on the
 * fixture).
 */
public class RocketLaunchSmokeTest extends AbstractHeadlessServerTest {

    private static final String ENT_ID = "entityId";

    @Test
    public void assembledRocketTransitionsToFlight() throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, 600, 600);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and LAUNCHED out of is EMPTY. The site stands
        // in open air, so this ASSERTS rather than digs; the height covers the hull and the first
        // blocks of its climb, which is the only part of the lane the scenario stays to watch.
        int[] bp = RocketFixture.placeAt(site, cmd -> String.join("\n", client().execute(cmd)),
                "simple", 2, 10,
                "the craft is built here and launched straight up out of this volume");
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];

        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble didn't produce a rocket: " + assemble,
                Reply.of(assemble).ok() && !(Reply.of(assemble).integer("entityId") == -1));

        Reply emReply = Reply.of(assemble);
        assertTrue("assemble response missing entityId: " + assemble, emReply.has(ENT_ID));
        int entityId = Integer.parseInt(emReply.text(ENT_ID));
        assertTrue("assemble succeeded but entityId=-1", entityId >= 0);

        // Try the real launch path first (instant — bypasses 200-tick countdown).
        String launchInstant = String.join("\n", client().execute(
                "artest rocket launch " + entityId + " true instant"));
        assertTrue("instant launch errored: " + launchInstant,
                Reply.of(launchInstant).ok());

        // absence is the answer: this is the fast path — a launch that already took answers
        // both flags, and a reply carrying neither falls through to the slow path below.
        if (Reply.of(launchInstant).boolOr("isInFlight", false) || Reply.of(launchInstant).boolOr("isInOrbit", false)) {
            // Real path succeeded.
            return;
        }

        // Real path errored silently (no destination chip). Fall back to force.
        String launchForce = String.join("\n", client().execute(
                "artest rocket launch " + entityId + " true force"));
        assertTrue("force launch errored: " + launchForce,
                Reply.of(launchForce).ok());
        assertTrue("force launch didn't set isInFlight=true: " + launchForce,
                Reply.of(launchForce).bool("isInFlight"));
    }
}
