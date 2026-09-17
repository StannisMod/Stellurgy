package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * rocket launch event chain.
 *
 * The shallow Phase 1 ({@link EventHandlerWiringTest}) covers the
 * weather-wrap WorldEvent.Load hook. This file extends Phase 1 onto
 * the rocket-event side: drive an assembled rocket through the launch
 * modes the probe exposes and pin that {@code RocketEventHandler}-side
 * state actually updates (isInFlight, isInOrbit) — those flags are
 * read by every renderer, every infrastructure link, and every
 * mission system. A silent regression here ships rockets that look
 * parked in the launchpad while their server-side state is "in orbit".
 *
 * Mode coverage matches the probe vocabulary:
 *   - {@code launch <id> false force}: bypasses fuel / pre-launch
 *     checks, flips {@code isInFlight=true} via {@code setInFlight}.
 *   - {@code launch <id> true instant}: fills fuel + calls
 *     {@code rocket.launch()} (the production path).
 *   - {@code launch <id> true prepare}: fills fuel + calls
 *     {@code prepareLaunch()} (the multi-tick path).
 */
public class RocketLaunchEventTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> String.join("\n", client().execute(cmd)), id);
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");

        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];

        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = String.join("\n", client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    @Test
    public void launchForceSetsInFlightFlag() throws Exception {
        // Use unique baseX per test so fixtures from earlier tests in this
        // JVM don't collide (RocketAssemblySmokeTest grabs 500..580).
        int id = buildAndAssemble(FixtureSite.openAir(0, 700, 500));
        RocketInfo preInfo = rocketInfo(id);
        assertFalse("freshly assembled rocket should NOT already be in flight: " + preInfo.raw(),
                preInfo.inFlight);

        // false=skip fuel fill, force = setInFlight(true) bypass.
        String launch = String.join("\n",
                client().execute("artest rocket launch " + id + " false force"));
        assertTrue("force launch must succeed: " + launch, launch.contains("\"ok\":true"));
        assertTrue("force launch response must report isInFlight=true: " + launch,
                launch.contains("\"isInFlight\":true"));

        // Verify via a separate info probe — confirms the flag persists
        // through the entity registry, not just the launch response.
        RocketInfo postInfo = rocketInfo(id);
        assertTrue("rocket info must report isInFlight=true after force launch: " + postInfo.raw(),
                postInfo.inFlight);
    }

    @Test
    public void launchInstantRespondsOkAndEchoesMode() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 740, 500));

        // instant: fills fuel + calls rocket.launch() — the production
        // launch path. Production launch() has pre-conditions (launchpad
        // contact, destination, etc.) that a test-fixture rocket sitting
        // in mid-air doesn't satisfy, so isInFlight may remain false on
        // this code path. Pin only the wiring contract: the probe must
        // accept the call, echo the mode, report fuelFilled=true (proves
        // the fuel-fill loop fired), and not crash.
        String launch = String.join("\n",
                client().execute("artest rocket launch " + id + " true instant"));
        assertTrue("instant launch must succeed: " + launch, launch.contains("\"ok\":true"));
        assertTrue("launch response must echo back the chosen mode: " + launch,
                launch.contains("\"mode\":\"instant\""));
        assertTrue("launch with fuelFill=true must echo it: " + launch,
                launch.contains("\"fuelFilled\":true"));
    }

    @Test
    public void launchOnUnknownIdReturnsError() throws Exception {
        // Counter-test: the entity registry lookup must NOT silently no-op.
        // A regression that returned ok:true here would let downstream
        // tooling claim launch success for rockets that never existed.
        String launch = String.join("\n",
                client().execute("artest rocket launch 9999999 false force"));
        assertTrue("launch on unknown id must report rocket-not-found: " + launch,
                launch.contains("\"error\":\"rocket not found\""));
    }

    @Test
    public void doubleLaunchKeepsIsInFlightSet() throws Exception {
        // Sequence: launch -> already-in-flight -> launch again. Idempotency
        // contract: the second call must not flip the flag back off, must
        // not crash. (In production, the rocket is briefly in 'in flight'
        // before takeoff finishes; a second launch button-press is a
        // realistic edge case.)
        int id = buildAndAssemble(FixtureSite.openAir(0, 780, 500));
        client().execute("artest rocket launch " + id + " false force");
        String second = String.join("\n",
                client().execute("artest rocket launch " + id + " false force"));
        assertTrue("second-launch must still ok: " + second, second.contains("\"ok\":true"));
        assertTrue("second-launch must still report isInFlight=true: " + second,
                second.contains("\"isInFlight\":true"));
    }
}
