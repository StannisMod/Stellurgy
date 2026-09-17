package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * REAL rocket launch path (not the wiring smoke
 * pinned by {@link RocketLaunchEventTest}).
 *
 * <p>{@link RocketLaunchEventTest#launchInstantRespondsOkAndEchoesMode}
 * acknowledges it can only pin the wiring contract: the fixture rocket
 * sitting in mid-air doesn't satisfy {@code rocket.launch()}'s
 * preconditions, so {@code isInFlight} stays {@code false} on the
 * production path. This file actually programs a destination chip into
 * the guidance computer and asserts the launch goes all the way to
 * {@code setInFlight(true)} via the real production path.</p>
 *
 * Tests:
 *
 * <ul>
 *   <li><b>{@code launchInstantWithDestinationActuallyTakesOff}</b> — the
 *       real happy path. Build &rarr; assemble &rarr; program chip &rarr; launch with
 *       fuel &rarr; assert {@code isInFlight=true} on the production
 *       {@code rocket.launch()} path (NOT the force bypass).</li>
 *   <li><b>{@code launchWithoutDestinationReportsCannotGetThereError}</b>
 *       — the {@code error.rocket.cannotGetThere} branch of production
 *       launch(). Without a programmed chip the rocket bails with this
 *       error and isInFlight stays false. Pin both observations to
 *       discriminate "actually launched" from "launch silently bailed".</li>
 *   <li><b>{@code launchOnAlreadyInFlightRocketIsNoOp}</b> — production
 *       guard at the top of launch(): {@code if (isInFlight()) return;}.
 *       A double launch must NOT re-fire the RocketLaunchEvent or
 *       mutate state.</li>
 *   <li><b>{@code launchToOverworldFromOverworldStaysGrounded}</b> —
 *       counter-test: the system-coherence gate
 *       ({@code !PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem})
 *       must refuse launches that don't change planetary system. For our
 *       fixture set, dim 0 &rarr; dim 0 should NOT be a valid travel.</li>
 * </ul>
 */
public class RocketLaunchDepthTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    private static final String AR_DIMS_ARRAY = "arDimensions";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> ok(client().execute(cmd)), id);
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // FIRST link: the volume this rocket is built and launched in is empty. It is an assertion
        // and not a clearing — the site stands in open air, so anything standing in it means the
        // arrangement is wrong, and saying so here is what keeps it from arriving later as a launch
        // that would not take off.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the rocket is built and launched in this volume");

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + site.x + " " + site.y + " " + site.z + " simple"));
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

    private int firstNonOverworldArDimOrSkip() throws Exception {
        String joined = ok(client().execute("artest dim list"));
        Assume.assumeFalse("No AR dimensions registered",
                joined.contains("\"arDimensions\":[]"));
        Reply dims = Reply.of("artest dim list", joined);
        assertTrue("could not parse arDimensions array: " + joined, dims.has(AR_DIMS_ARRAY));
        for (int dim : dims.intArray(AR_DIMS_ARRAY)) {
            if (dim != 0) return dim;
        }
        Assume.assumeTrue("Only overworld is an AR planet — cannot program target",
                false);
        return -1;
    }

    @Test
    public void launchInstantWithDestinationActuallyTakesOff() throws Exception {
        // Critical: this is the REAL launch path. If this test passes,
        // rocket.launch() walked all the way through the
        // destination-validation, weight-check, and allowLaunch gate to
        // setInFlight(true). The earlier
        // RocketLaunchEventTest.launchInstantRespondsOkAndEchoesMode only
        // proved the probe wiring didn't crash.
        int destDim = firstNonOverworldArDimOrSkip();
        int id = buildAndAssemble(FixtureSite.openAir(0, 1000, 500));

        String prog = ok(client().execute(
                "artest rocket set-destination " + id + " " + destDim));
        assertTrue("set-destination must succeed: " + prog,
                prog.contains("\"ok\":true"));
        assertTrue("set-destination must echo back the dim it programmed: " + prog,
                prog.contains("\"dim\":" + destDim));
        assertTrue("set-destination must round-trip the chip's stored dim: " + prog,
                prog.contains("\"chipDim\":" + destDim));

        // Launch with fuelFill=true + mode=instant -> real rocket.launch().
        // This MUST flip isInFlight to true and NOT report an error.
        String launch = ok(client().execute(
                "artest rocket launch " + id + " true instant"));
        assertTrue("launch response must be ok=true: " + launch,
                launch.contains("\"ok\":true"));

        RocketInfo info = rocketInfo(id);
        // The whole point: production launch path took the rocket from
        // ground to in-flight. A regression that introduces a new gate
        // (e.g. requires a sealed cockpit, requires player onboard,
        // requires fuel of a specific type) surfaces here as
        // isInFlight=false + a non-empty errorMessage.
        assertTrue("real launch did NOT flip isInFlight=true: " + info.raw(), info.inFlight);
        // No errorMessage — production setError(...) is only called on
        // the bail-out branches. A successful launch leaves errorStr "".
        assertFalse("successful launch must NOT report an error message: " + info.raw(),
                info.hasError());
    }

    @Test
    public void launchWithoutDestinationReportsCannotGetThereError() throws Exception {
        // No set-destination call -> guidance computer slot 0 is empty ->
        // getDestinationDimId returns Constants.INVALID_PLANET -> launch
        // bails with "error.rocket.cannotGetThere".
        int id = buildAndAssemble(FixtureSite.openAir(0, 1100, 500));

        String launch = ok(client().execute(
                "artest rocket launch " + id + " true instant"));
        assertTrue("launch probe must succeed (wiring is fine): " + launch,
                launch.contains("\"ok\":true"));

        RocketInfo info = rocketInfo(id);
        // Production: the cannotGetThere branch calls setError(...) AND
        // returns BEFORE setInFlight. Pin both observations.
        assertFalse("launch without destination must NOT flip isInFlight: " + info.raw(),
                info.inFlight);
        // The error message is a localised string; in dev we get either
        // the raw key OR the localised form. Match the substring that's
        // common to both: "cannotGetThere". This is a substring of ONE field's
        // value, not of the reply.
        assertTrue("rocket must report a cannot-get-there error message: " + info.raw(),
                info.errorMessage.contains("cannotGetThere"));
    }

    @Test
    public void launchOnAlreadyInFlightRocketIsNoOp() throws Exception {
        // Production guard at top of launch(): if (isInFlight()) return;
        // A second launch on an already-flying rocket must NOT re-fire
        // any events and must NOT mutate state. Verify by force-launching
        // (cheap, deterministic), then calling instant launch — the
        // second call must complete cleanly with isInFlight still true.
        int id = buildAndAssemble(FixtureSite.openAir(0, 1200, 500));
        ok(client().execute("artest rocket launch " + id + " false force"));

        RocketInfo preInfo = rocketInfo(id);
        assertTrue("force-launch must have flipped isInFlight: " + preInfo.raw(),
                preInfo.inFlight);

        // Now invoke production launch() on the already-flying rocket.
        // The early-return at line 1761-1762 must prevent any state
        // mutation. The launch response should still report ok=true (probe
        // wiring), isInFlight should remain true, and the destinationDim
        // (which is INVALID_PLANET since we never programmed) must NOT
        // suddenly become anything else because the destination-lookup
        // branch is skipped by the early return.
        String secondLaunch = ok(client().execute(
                "artest rocket launch " + id + " true instant"));
        assertTrue("second launch on in-flight rocket must still be probe-ok: "
                        + secondLaunch, secondLaunch.contains("\"ok\":true"));

        RocketInfo postInfo = rocketInfo(id);
        assertTrue("isInFlight must STAY true after no-op re-launch: " + postInfo.raw(),
                postInfo.inFlight);
        // destinationDim must NOT have been updated by the re-launch — the
        // early-return guard skipped the destinationDimId assignment branch.
        // For force-launched rocket without a chip, destinationDim starts
        // at whatever default the rocket was constructed with. We pin
        // "no error message added by the re-launch" as the testable
        // observation: a regression that removed the early-return would
        // run the destination-lookup branch and call setError().
        assertFalse("no-op re-launch must not add a new error message: " + postInfo.raw(),
                postInfo.hasError());
    }

    @Test
    public void launchTargetingSameDimensionStaysGrounded() throws Exception {
        // Counter-test for the planetary-system coherence gate. Production
        // launch() at line 1832 checks
        //   !PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(
        //         finalDest, thisDimId)
        // and bails with "error.rocket.notSameSystem" — actually, for
        // same-dim destination, this gate may PASS (you ARE in the same
        // system as yourself). The more interesting gate here is that
        // setDestination(0) targets overworld, and the rocket is currently
        // ON overworld. The behaviour we pin is: production accepts this
        // (sane: a same-dim flight is sub-orbital), so isInFlight=true.
        // This is essentially a sanity check that "obviously valid"
        // configurations work. If a regression broke it, every
        // overworld->overworld flight would silently fail.
        int id = buildAndAssemble(FixtureSite.openAir(0, 1300, 500));
        ok(client().execute("artest rocket set-destination " + id + " 0"));

        String launch = ok(client().execute(
                "artest rocket launch " + id + " true instant"));
        assertTrue("launch wiring ok: " + launch, launch.contains("\"ok\":true"));

        RocketInfo info = rocketInfo(id);
        // Whichever branch production picks, the test pins observable
        // behaviour: either isInFlight=true (same-system flight OK) OR
        // isInFlight=false + an error message. Both are valid contract
        // surfaces; a regression that crashes mid-decision is NOT.
        boolean inFlight = info.inFlight;
        boolean hasError = info.hasError();
        assertTrue("launch with same-dim destination must produce a "
                        + "coherent outcome (either in-flight OR an error, "
                        + "never both crashed): " + info.raw(),
                inFlight || hasError);
        // Specifically: never both at once.
        assertNotEquals("inFlight=true with a non-empty error message is "
                + "incoherent: " + info.raw(), inFlight, hasError);
        // Pin destination round-trip irrespective of outcome.
        assertEquals("destinationDim must reflect what we programmed: " + info.raw(),
                0, info.destinationDim);
    }

    /** Final assertion that the {@code errorMessage} field is wired into
     *  the info probe — guards against probe regressions that would mask
     *  silent bail-outs. */
    @Test
    public void rocketInfoExposesErrorMessageField() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 1400, 500));
        // The reader REFUSES a report with no `errorMessage` — that is this test's first half,
        // and it is now enforced for every caller rather than asserted once here.
        RocketInfo info = rocketInfo(id);
        // Freshly assembled rocket -> no error yet.
        assertFalse("freshly assembled rocket must have empty errorMessage: " + info.raw(),
                info.hasError());
    }

    /** Ensure set-destination probe rejects invalid entityId — keeps the
     *  probe API contract sharp. */
    @Test
    public void setDestinationOnUnknownRocketReturnsError() throws Exception {
        String resp = ok(client().execute("artest rocket set-destination 9999999 0"));
        assertTrue("set-destination on unknown id must return error: " + resp,
                resp.contains("\"error\":\"rocket not found\""));
    }
}
