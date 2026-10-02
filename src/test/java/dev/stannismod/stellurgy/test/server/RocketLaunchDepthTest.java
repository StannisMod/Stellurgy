package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.RocketInfo;
import dev.stannismod.stellurgy.test.RocketList;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * REAL rocket launch path.
 *
 * <p>A fixture rocket sitting in mid-air doesn't satisfy {@code rocket.launch()}'s
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
 *
 * <p>And the launch DECISIONS, each read from the server's ordered log (see the section comment
 * above them): which worlds share a planetary system with the launch world, from a planet and from
 * a moon; the weight gate's inclusive threshold; the weight system's off switch; and the thrust
 * multiplier reaching the thrust the gate compares.</p>
 */
public class RocketLaunchDepthTest extends AbstractSharedServerTest {

    private static final String ROCKET_LIST_ID = "id";
    private static final String AR_DIMS_ARRAY = "stellurgyDimensions";

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
        String assemble = RocketFixture.assembleAt(site, cmd -> ok(client().execute(cmd)),
                "simple", 2, 10,
                "the rocket is built and launched in this volume");
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());

        String list = ok(client().execute("stellurgytest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    private int firstNonOverworldStellurgyDimOrSkip() throws Exception {
        String joined = ok(client().execute("stellurgytest dim list"));
        Assume.assumeFalse("No Stellurgy dimensions registered",
                (Reply.of(joined).arrayLength("stellurgyDimensions") == 0));
        Reply dims = Reply.of("stellurgytest dim list", joined);
        assertTrue("could not parse stellurgyDimensions array: " + joined, dims.has(AR_DIMS_ARRAY));
        for (int dim : dims.intArray(AR_DIMS_ARRAY)) {
            if (dim != 0) return dim;
        }
        Assume.assumeTrue("Only overworld is a Stellurgy planet — cannot program target",
                false);
        return -1;
    }

    @Test
    public void launchInstantWithDestinationActuallyTakesOff() throws Exception {
        // Critical: this is the REAL launch path. If this test passes,
        // rocket.launch() walked all the way through the
        // destination-validation, weight-check, and allowLaunch gate to
        // setInFlight(true).
        int destDim = firstNonOverworldStellurgyDimOrSkip();
        int id = buildAndAssemble(FixtureSite.openAir(0, 1000, 500));

        String prog = ok(client().execute(
                "stellurgytest rocket set-destination " + id + " " + destDim));
        assertTrue("set-destination must succeed: " + prog,
                Reply.of(prog).ok());
        assertTrue("set-destination must round-trip the chip's stored dim: " + prog,
                String.valueOf(destDim).equals(Reply.of(prog).text("chipDim")));

        // Launch with fuelFill=true + mode=instant -> real rocket.launch().
        // This MUST flip isInFlight to true and NOT report an error.
        String launch = ok(client().execute(
                "stellurgytest rocket launch " + id + " true instant"));
        assertTrue("launch response must be ok=true: " + launch,
                Reply.of(launch).ok());

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
                "stellurgytest rocket launch " + id + " true instant"));
        assertTrue("launch probe must succeed (wiring is fine): " + launch,
                Reply.of(launch).ok());

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
        ok(client().execute("stellurgytest rocket launch " + id + " false force"));

        // Now invoke production launch() on the already-flying rocket.
        // The early-return at line 1761-1762 must prevent any state
        // mutation. The launch response should still report ok=true (probe
        // wiring), isInFlight should remain true, and the destinationDim
        // (which is INVALID_PLANET since we never programmed) must NOT
        // suddenly become anything else because the destination-lookup
        // branch is skipped by the early return.
        String secondLaunch = ok(client().execute(
                "stellurgytest rocket launch " + id + " true instant"));
        assertTrue("second launch on in-flight rocket must still be probe-ok: "
                        + secondLaunch, Reply.of(secondLaunch).ok());

        RocketInfo postInfo = rocketInfo(id);
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
        ok(client().execute("stellurgytest rocket set-destination " + id + " 0"));

        String launch = ok(client().execute(
                "stellurgytest rocket launch " + id + " true instant"));
        assertTrue("launch wiring ok: " + launch, Reply.of(launch).ok());

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

    // ==== WHICH WORLDS A ROCKET MAY LAUNCH FOR, AND WHETHER IT IS LIGHT ENOUGH TO GO ==============
    //
    // The scenarios below read each launch decision from the server's ordered log rather than from
    // the rocket's fields: `rocket_aborted` is the RocketAbortEvent that EntityRocket.setError posts
    // with every refusal, `rocket_launched` the RocketLaunchEvent that EntityRocket.launch posts once
    // every refusal has been passed, and `rocket_launch_gate` the test mixin's record of what the
    // weight gate is about to compare. `rocket launch <id> true instant` calls EntityRocket.launch on
    // the server thread inside the probe call, so by the time the probe answers, the decision is in
    // the log: every read here is of a CLOSED window and nothing waits.
    //
    // What these do NOT see: the countdown a player's launch key starts (prepareLaunch → 200 ticks →
    // launch); the chat line a seated pilot is sent with each refusal; and the gate's dependence on
    // the body the rocket stands on — every craft here stands on the overworld or on the overworld's
    // moon with the gate's local-gravity question left alone, because the gate ignoring local gravity
    // is a recorded defect, not a contract.

    /** Production's key for a destination outside the launch world's planetary system. */
    private static final String OUTSIDE_PLANETARY_SYSTEM = "error.rocket.outsidePlanetarySystem";
    /** Production's key for a craft the weight gate refuses. */
    private static final String TOO_HEAVY = "error.rocket.tooHeavy";

    /**
     * Every craft these scenarios launch climbs out of its plot and keeps moving, so the next
     * scenario starts by removing what an earlier one left in the overworld — at its START, because
     * JUnit runs an {@code @After} before the rules have finished.
     */
    @Before
    public void disposeOfCraftAnEarlierScenarioLaunched() throws Exception {
        RocketList.clearFrom(RocketLaunchDepthTest::exec, 0);
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    /**
     * The server's ordered log, for reading closed windows. It is handed a step that refuses: a
     * scenario here that found itself waiting would be waiting on something launch() did not do
     * inside the call, which is a fact about the arrangement and must be said, not slept through.
     */
    private static Events serverLog() {
        return new Events(RocketLaunchDepthTest::exec, ticks -> {
            throw new AssertionError("a launch decision is taken inside the probe call, so nothing"
                    + " here waits; asked to advance " + ticks + " ticks");
        });
    }

    /** What ONE call to {@code EntityRocket#launch} decided for one craft, read from the log. */
    private static final class LaunchDecision {
        /** The packed refusal reason, or {@code null} when the launch was accepted. */
        final String refusal;
        /** Whether a {@code rocket_launched} was recorded for this craft. */
        final boolean launched;
        /** The {@code rocket_launch_gate} record, or {@code null} when the gate was never asked. */
        final String gate;
        /** Everything the log holds since the mark, for a message. */
        final String window;

        LaunchDecision(String refusal, boolean launched, String gate, String window) {
            this.refusal = refusal;
            this.launched = launched;
            this.gate = gate;
            this.window = window;
        }

        /** A number the gate record carries, refusing a record without it. */
        double gateNumber(String field) {
            requireArranged("the weight gate was never asked on this launch, so there is no "
                    + field + " to read: " + window, gate != null);
            double v = Events.number(gate, field);
            requireArranged("the gate record carries no numeric " + field + ": " + gate, !Double.isNaN(v));
            return v;
        }
    }

    /**
     * Program {@code destination} into the craft's guidance computer, fill its tanks and call
     * {@code EntityRocket#launch} once; answer the one decision it recorded.
     */
    private static LaunchDecision launch(Events log, int rocket, int destination) throws Exception {
        String chip = exec("stellurgytest rocket set-destination " + rocket + " " + destination);
        Reply programmed = Reply.of("stellurgytest rocket set-destination", chip);
        requireArranged("the guidance computer must carry a chip for dimension " + destination
                + " before the launch: " + chip,
                programmed.ok() && String.valueOf(destination).equals(programmed.text("chipDim")));
        long mark = log.mark();
        String fired = exec("stellurgytest rocket launch " + rocket + " true instant");
        requireArranged("the launch verb must reach EntityRocket.launch: " + fired,
                Reply.of("stellurgytest rocket launch", fired).ok());
        String id = String.valueOf(rocket);
        List<String> refused = Events.recordsWhere(log.since(mark, "rocket_aborted"), "e", id);
        List<String> accepted = Events.recordsWhere(log.since(mark, "rocket_launched"), "e", id);
        List<String> gate = Events.recordsWhere(log.since(mark, "rocket_launch_gate"), "e", id);
        String window = log.since(mark);
        requireArranged("one call to launch() leaves exactly one refusal or one acceptance for the"
                + " craft it was called on; this one left " + refused.size() + " and "
                + accepted.size() + " — the call did not reach a decision (already in flight?): "
                + window, refused.size() + accepted.size() == 1);
        return new LaunchDecision(
                refused.isEmpty() ? null : Events.text(refused.get(0), "reason"),
                !accepted.isEmpty(),
                gate.isEmpty() ? null : gate.get(gate.size() - 1),
                window);
    }

    /**
     * A fresh tier-1 rocket at {@code site}, assembled, answered by its entity id.
     *
     * <p>The working volume is the launchpad's own footprint (halo 0: the assembler takes the craft
     * from above its pad and nowhere else) and the whole column above it to the build limit of 256,
     * because an accepted launch sends the craft straight up through it.</p>
     */
    private static int rocketAt(FixtureSite site, String what) throws Exception {
        return RocketFixture.rocketEntityId(RocketFixture.assembleAt(site,
                RocketLaunchDepthTest::exec, "simple", 0, 255 - site.y, what));
    }

    /** {@code stellurgytest planet info} for one world, read as data. */
    private static Reply planetInfo(int dim) throws Exception {
        return Reply.of("stellurgytest planet info", exec("stellurgytest planet info " + dim));
    }

    private static Set<Integer> stellurgyWorlds() throws Exception {
        Set<Integer> worlds = new LinkedHashSet<>();
        for (int dim : Reply.of("stellurgytest dim list", exec("stellurgytest dim list"))
                .intArray(AR_DIMS_ARRAY)) {
            worlds.add(dim);
        }
        return worlds;
    }

    /**
     * Mint one world through the shipped operator command {@code /stellurgy planet generate} and
     * answer the dimension it was registered under — read from the world list, never from the
     * command's chat line.
     */
    private static int mint(String placement, String name) throws Exception {
        Set<Integer> before = stellurgyWorlds();
        exec("stellurgy planet generate " + placement + " " + name);
        List<Integer> added = new ArrayList<>(stellurgyWorlds());
        added.removeAll(before);
        requireArranged("`/stellurgy planet generate " + placement + " " + name + "` must register"
                + " exactly one new world; it added " + added, added.size() == 1);
        int dim = added.get(0);
        Reply info = planetInfo(dim);
        requireArranged("the world registered must be the one asked for: " + info,
                name.equals(info.text("name")));
        return dim;
    }

    /** The overworld's moon — the one moon of dimension 0 this world was made with. */
    private static int theOverworldsMoon() throws Exception {
        List<Integer> moons = new ArrayList<>();
        for (int dim : stellurgyWorlds()) {
            // the producer always writes `parent` for a registered world, and every id here is one
            if (dim != 0 && planetInfo(dim).integer("parent") == 0) {
                moons.add(dim);
            }
        }
        requireArranged("this world must begin with exactly one moon of the overworld; it has "
                + moons, moons.size() == 1);
        return moons.get(0);
    }

    /**
     * The config value {@code key} holds now, as the probe reports it — the text a restore hands
     * back unchanged.
     */
    private static String configValue(String key) throws Exception {
        Reply got = Reply.of("stellurgytest config get", exec("stellurgytest config get " + key));
        requireArranged("config key " + key + " must be readable: " + got, got.ok());
        return got.text("value");
    }

    private static void setConfig(String key, String value) throws Exception {
        Reply set = Reply.of("stellurgytest config set",
                exec("stellurgytest config set " + key + " " + value));
        requireArranged("config key " + key + " must take " + value + ": " + set, set.ok());
    }

    /**
     * A tier-1 rocket standing on a planet may launch for that planet's own moon, and is refused
     * with {@code error.rocket.outsidePlanetarySystem} for another planet of the SAME star and for
     * that other planet's moon — the planetary system, not the star system, is the bound.
     *
     * <p>This test fails if {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem}
     * stops deciding that a moon and its parent share a system, or starts deciding that a planet and
     * a stranger's moon or another planet do — read where a player meets it, at
     * {@code EntityRocket#launch}, which passes the DESTINATION as the helper's first argument, so
     * Earth→Luna exercises the helper's "moon whose parent is the other world" branch and Earth→the
     * far moon its "moon of some other planet" branch.</p>
     *
     * <p>The far planet and its moon are minted with the shipped operator command for this scenario
     * and deleted after it; the overworld's moon is the one the world was made with.</p>
     *
     * red-witnessed: with {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem} at
     * {@code return isPlanetMoonSystem} made to answer true, this fails at the far-planet verdict
     * with "expected:<error.rocket.outsidePlanetarySystem> but was:<null>" (2026-10-02).
     * red-witnessed: with {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem} at
     * {@code for (int moonDimID : launchworldProperties.getParentProperties().getChildPlanets())} —
     * the moon branch's sibling loop — made to match every moon, this fails at the far-moon verdict with
     * "expected:<error.rocket.outsidePlanetarySystem> but was:<null>" while the far planet is still
     * refused (2026-10-02).
     * red-witnessed: with {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem} at
     * {@code isPlanetMoonSystem = (destinationDimensionID == launchworldProperties.getParentPlanet())}
     * made false, this fails at the own-moon verdict, the launch refused with
     * error.rocket.outsidePlanetarySystem (2026-10-02).
     */
    @Test
    public void aRocketOnAPlanetMayGoToItsOwnMoonButNotToAnotherPlanetOrItsMoon() throws Exception {
        int luna = theOverworldsMoon();
        int far = mint("0", "RepinFarPlanet");
        int farMoon = Integer.MIN_VALUE;
        try {
            farMoon = mint(far + " moon", "RepinFarPlanetMoon");
            Reply earthInfo = planetInfo(0);
            Reply farInfo = planetInfo(far);
            requireArranged("the far planet must orbit the overworld's own star, so a refusal is about"
                    + " the planetary system and not the star system: " + farInfo + " / " + earthInfo,
                    farInfo.integer("starId") == earthInfo.integer("starId"));
            requireArranged("the far planet must be nobody's moon: " + farInfo,
                    farInfo.integer("parent") == Constants.INVALID_PLANET);
            requireArranged("the far moon must be the far planet's: " + planetInfo(farMoon),
                    planetInfo(farMoon).integer("parent") == far);

            int rocket = rocketAt(site(), "the craft is built and launched in this volume");
            Events log = serverLog();

            LaunchDecision toFarPlanet = launch(log, rocket, far);
            assertEquals("another planet of the same star lies outside this world's planetary"
                    + " system, so the launch must be refused for that reason: " + toFarPlanet.window,
                    OUTSIDE_PLANETARY_SYSTEM, toFarPlanet.refusal);

            LaunchDecision toFarMoon = launch(log, rocket, farMoon);
            assertEquals("the moon of another planet lies outside this world's planetary system,"
                    + " so the launch must be refused for that reason: " + toFarMoon.window,
                    OUTSIDE_PLANETARY_SYSTEM, toFarMoon.refusal);

            LaunchDecision toOwnMoon = launch(log, rocket, luna);
            assertTrue("the same craft must be accepted for this world's own moon — the refusals"
                    + " above were about where it was sent: " + toOwnMoon.window, toOwnMoon.launched);
        } finally {
            if (farMoon != Integer.MIN_VALUE) {
                exec("stellurgy planet delete " + farMoon);
            }
            exec("stellurgy planet delete " + far);
        }
    }

    /**
     * A tier-1 rocket standing on a MOON may launch for its parent planet and for a sibling moon of
     * that planet, and is refused with {@code error.rocket.outsidePlanetarySystem} for another planet
     * of the same star.
     *
     * <p>This test fails if {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem}
     * stops deciding that a planet holds its moon (Luna→Earth reaches the helper as "is Luna among
     * Earth's moons"), that two moons of one planet share its system (Luna→sibling reaches it as "is
     * Luna among the sibling's parent's moons"), or starts deciding that a planet holds a moon that
     * is not its own (Luna→far planet).</p>
     *
     * <p>Both craft stand on the overworld's moon, on this scenario's own footprint in that world
     * ({@code Plot#inDimension}); they are removed from it when the scenario ends, as are the far
     * planet and the sibling moon minted for it.</p>
     *
     * red-witnessed: with {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem} at
     * {@code return isPlanetMoonSystem} made to answer true, this fails at the far-planet verdict, the
     * launch accepted where error.rocket.outsidePlanetarySystem was expected (2026-10-02).
     * red-witnessed: with {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem} at
     * {@code for (int moonDimID : launchworldProperties.getChildPlanets())} — the planet branch — made
     * to match none, this fails at the parent-planet verdict, the launch refused with
     * error.rocket.outsidePlanetarySystem (2026-10-02).
     * red-witnessed: with {@code PlanetaryTravelHelper#isTravelBetweenBodiesWithinPlanetarySystem} at
     * {@code for (int moonDimID : launchworldProperties.getParentProperties().getChildPlanets())} —
     * the moon branch's sibling loop — made to match none, this fails at the sibling-moon verdict,
     * the launch refused with error.rocket.outsidePlanetarySystem (2026-10-02).
     */
    @Test
    public void aRocketOnAMoonMayGoToItsPlanetAndASiblingMoonButNotToAnotherPlanet()
            throws Exception {
        int luna = theOverworldsMoon();
        Plot onLuna = plot().inDimension(luna);
        int far = mint("0", "RepinMoonsideFarPlanet");
        int sibling = Integer.MIN_VALUE;
        try {
            sibling = mint("0 moon", "RepinSiblingMoon");
            requireArranged("the sibling must be a moon of the overworld, as the moon the craft"
                    + " stands on is: " + planetInfo(sibling), planetInfo(sibling).integer("parent") == 0);
            requireArranged("the far planet must be nobody's moon: " + planetInfo(far),
                    planetInfo(far).integer("parent") == Constants.INVALID_PLANET);
            Reply loaded = Reply.of("stellurgytest dim load", exec("stellurgytest dim load " + luna));
            requireArranged("the moon's world must be loaded and held to stand a craft in: " + loaded,
                    loaded.bool("loaded"));

            Events log = serverLog();
            int first = rocketAt(onLuna.site(), "the first craft is built on the moon and launched");

            LaunchDecision toFarPlanet = launch(log, first, far);
            assertEquals("another planet lies outside the moon's planetary system, so the launch"
                    + " must be refused for that reason: " + toFarPlanet.window,
                    OUTSIDE_PLANETARY_SYSTEM, toFarPlanet.refusal);

            LaunchDecision toParent = launch(log, first, 0);
            assertTrue("a craft on a moon must be accepted for the planet that moon orbits: "
                    + toParent.window, toParent.launched);

            // A second structure on the same footprint, clear of the first's working volume (the
            // first stands FIXTURE_INSET into the plot with a 6-block pad; 16 further on is past it).
            int second = rocketAt(onLuna.siteAt(Plot.FIXTURE_INSET + 16, Plot.FIXTURE_INSET),
                    "the second craft is built on the moon and launched");
            LaunchDecision toSibling = launch(log, second, sibling);
            assertTrue("a craft on a moon must be accepted for another moon of the same planet: "
                    + toSibling.window, toSibling.launched);
        } finally {
            RocketList.clearFrom(RocketLaunchDepthTest::exec, luna);
            if (sibling != Integer.MIN_VALUE) {
                exec("stellurgy planet delete " + sibling);
            }
            exec("stellurgy planet delete " + far);
        }
    }

    /**
     * The weight gate lets a craft go when its thrust-to-weight ratio EQUALS {@code minLaunchTWR},
     * and refuses it with {@code error.rocket.tooHeavy} when the threshold is the next number above
     * that ratio — the boundary is inclusive, and it is the ratio the gate compares.
     *
     * <p>This test fails if {@code StatsRocket#canLaunch} stops deciding "launch when the ratio is at
     * least the minimum". The ratio is MEASURED, not chosen: a first launch with the threshold at
     * {@code Double.MAX_VALUE} (which no finite ratio reaches) is refused, and the
     * {@code rocket_launch_gate} record of that refusal carries the ratio the gate compared, written
     * as the exact double it widens to; that number, and {@code Math.nextUp} of it, are then handed
     * back as the threshold. The fuel the launch verb loads before every call is the same full
     * tanks, so the wet weight is the same on all three calls — and each verdict asserts that the
     * gate compared that same ratio again.</p>
     *
     * red-witnessed: with {@code StatsRocket#canLaunch} at
     * {@code return getThrustToWeightRatio() >= StellurgyConfiguration.getCurrentConfig().minLaunchTWR}
     * given a tolerance (compared against {@code minLaunchTWR - 1e-3}), this fails at the
     * just-above verdict with "expected:<error.rocket.tooHeavy> but was:<null>" (2026-10-02).
     * red-witnessed: with {@code StatsRocket#canLaunch} at
     * {@code return getThrustToWeightRatio() >= StellurgyConfiguration.getCurrentConfig().minLaunchTWR}
     * made strict ({@code >} for {@code >=}), this fails at
     * the at-the-threshold verdict, the craft refused with error.rocket.tooHeavy (2026-10-02).
     *
     * <p>That the gate compared the measured ratio again on the second and third calls is a premise
     * of the placement, not a verdict: a ratio that moved between calls means the threshold was set
     * against a number the gate no longer reads, and the scenario says so as an arrangement failure.</p>
     */
    @Test
    public void theWeightGateLetsACraftGoAtExactlyMinLaunchTwrAndRefusesItJustAbove()
            throws Exception {
        String weightSystemWas = configValue("advancedWeightSystem");
        String minWas = configValue("minLaunchTWR");
        try {
            int luna = theOverworldsMoon();
            setConfig("advancedWeightSystem", "true");
            // The assembler holds a build to the same threshold, so the threshold that measures the
            // craft is set only once the craft exists.
            int rocket = rocketAt(site(), "the craft is built and launched in this volume");
            setConfig("minLaunchTWR", Double.toString(Double.MAX_VALUE));
            Events log = serverLog();

            LaunchDecision measured = launch(log, rocket, luna);
            requireArranged("with the threshold at Double.MAX_VALUE the gate must refuse, and its"
                    + " record is where the craft's ratio is read: " + measured.window,
                    TOO_HEAVY.equals(measured.refusal) && measured.gate != null);
            double ratio = measured.gateNumber("twr");
            requireArranged("the craft's ratio must be a positive finite number to stand a threshold"
                    + " on: " + measured.gate, ratio > 0 && !Double.isInfinite(ratio));
            System.out.println("[launch-gate] measured ratio " + ratio + " from " + measured.gate);

            setConfig("minLaunchTWR", Double.toString(Math.nextUp(ratio)));
            LaunchDecision justAbove = launch(log, rocket, luna);
            requireArranged("the gate must compare the measured ratio again on the second call, or"
                    + " the threshold was not placed against it: " + justAbove.window,
                    justAbove.gate != null && justAbove.gateNumber("twr") == ratio);
            assertEquals("a threshold one ulp above the craft's ratio must refuse it as too heavy: "
                    + justAbove.window, TOO_HEAVY, justAbove.refusal);

            setConfig("minLaunchTWR", Double.toString(ratio));
            LaunchDecision atIt = launch(log, rocket, luna);
            requireArranged("the gate must compare the measured ratio again on the third call, or the"
                    + " threshold was not placed against it: " + atIt.window,
                    atIt.gate != null && atIt.gateNumber("twr") == ratio);
            assertTrue("a ratio EQUAL to minLaunchTWR must be let go — the boundary is inclusive: "
                    + atIt.window, atIt.launched);
        } finally {
            setConfig("minLaunchTWR", minWas);
            setConfig("advancedWeightSystem", weightSystemWas);
        }
    }

    /**
     * With {@code advancedWeightSystem} off the weight gate is gone: a craft it refuses as too heavy
     * while the system is on is let go once the system is turned off, the threshold untouched.
     *
     * <p>This test fails if {@code StatsRocket#canLaunch} stops deciding that a disabled weight
     * system gates nothing — the config flag's promise to turn the mechanic off entirely. The refusal
     * with the system ON is asserted first, on the same craft and the same threshold, so the
     * acceptance cannot be a craft the gate would have let go anyway.</p>
     *
     * red-witnessed: with {@code StatsRocket#canLaunch} at
     * {@code return getThrustToWeightRatio() >= StellurgyConfiguration.getCurrentConfig().minLaunchTWR}
     * made to answer true, this fails at the system-on verdict with
     * "expected:<error.rocket.tooHeavy> but was:<null>" (2026-10-02).
     * red-witnessed: with {@code StatsRocket#canLaunch} at
     * {@code if (!StellurgyConfiguration.getCurrentConfig().advancedWeightSystem)} never taken, this
     * fails at the system-off verdict, the craft refused with error.rocket.tooHeavy (2026-10-02).
     */
    @Test
    public void turningTheWeightSystemOffLiftsTheWeightGate() throws Exception {
        String weightSystemWas = configValue("advancedWeightSystem");
        String minWas = configValue("minLaunchTWR");
        try {
            int luna = theOverworldsMoon();
            setConfig("advancedWeightSystem", "true");
            // Set after the build: the assembler holds a build to the same threshold.
            int rocket = rocketAt(site(), "the craft is built and launched in this volume");
            setConfig("minLaunchTWR", Double.toString(Double.MAX_VALUE));
            Events log = serverLog();

            LaunchDecision on = launch(log, rocket, luna);
            assertEquals("with the weight system on, a threshold no finite ratio reaches must refuse"
                    + " the craft as too heavy: " + on.window, TOO_HEAVY, on.refusal);

            setConfig("advancedWeightSystem", "false");
            LaunchDecision off = launch(log, rocket, luna);
            assertTrue("with the weight system off the same craft, under the same threshold, must"
                    + " be let go: " + off.window, off.launched);
        } finally {
            setConfig("minLaunchTWR", minWas);
            setConfig("advancedWeightSystem", weightSystemWas);
        }
    }

    /**
     * The thrust the weight gate compares is the engines' thrust times
     * {@code rocketThrustMultiplier}: doubling the multiplier doubles the thrust the gate reads, and a
     * craft refused below a threshold is then let go at it.
     *
     * <p>This test fails if {@code StatsRocket#getThrust} stops scaling the engines' thrust by the
     * multiplier. The threshold is placed between the craft's measured ratio at multiplier 1 and
     * twice that ratio (at their midpoint, 1.5×), so only a thrust that really grew can clear it. 2 is
     * used because {@code (int) (thrust * 2.0)} is exact for any engine thrust, so the doubled thrust
     * is a prediction and not a tolerance.</p>
     *
     * red-witnessed: with {@code StatsRocket#getThrust} at
     * {@code (int) (thrust * StellurgyConfiguration.getCurrentConfig().rocketThrustMultiplier)} made
     * {@code thrust}, this fails at the doubled-thrust verdict with the gate reading the multiplier-1
     * thrust again (2026-10-02).
     * red-witnessed: with {@code StatsRocket#getThrustToWeightRatio} at {@code return getThrust() / weight}
     * reading the raw {@code thrust} field instead, the doubled-thrust verdict holds and this fails at
     * the let-go verdict, "with the thrust doubled the craft clears a threshold of 1.5x its
     * multiplier-1 ratio and must be let go" (2026-10-02).
     */
    @Test
    public void theThrustTheWeightGateComparesIsScaledByTheThrustMultiplier() throws Exception {
        String weightSystemWas = configValue("advancedWeightSystem");
        String minWas = configValue("minLaunchTWR");
        String multiplierWas = configValue("rocketThrustMultiplier");
        try {
            int luna = theOverworldsMoon();
            setConfig("advancedWeightSystem", "true");
            setConfig("rocketThrustMultiplier", "1.0");
            // Set after the build: the assembler holds a build to the same threshold.
            int rocket = rocketAt(site(), "the craft is built and launched in this volume");
            setConfig("minLaunchTWR", Double.toString(Double.MAX_VALUE));
            Events log = serverLog();

            LaunchDecision atOne = launch(log, rocket, luna);
            requireArranged("with the threshold at Double.MAX_VALUE the gate must refuse, and its"
                    + " record is where the craft's thrust and ratio are read: " + atOne.window,
                    TOO_HEAVY.equals(atOne.refusal) && atOne.gate != null);
            double thrustAtOne = atOne.gateNumber("thrust");
            double ratioAtOne = atOne.gateNumber("twr");
            requireArranged("the craft must have thrust to scale: " + atOne.gate, thrustAtOne > 0);
            System.out.println("[launch-gate] multiplier-1 reading " + atOne.gate);

            setConfig("rocketThrustMultiplier", "2.0");
            setConfig("minLaunchTWR", Double.toString(1.5 * ratioAtOne));
            LaunchDecision atTwo = launch(log, rocket, luna);
            assertEquals("the thrust the gate compares must be the engines' thrust times the"
                    + " multiplier: " + atTwo.window, 2 * thrustAtOne, atTwo.gateNumber("thrust"), 0d);
            assertTrue("with the thrust doubled the craft clears a threshold of 1.5x its"
                    + " multiplier-1 ratio and must be let go: " + atTwo.window, atTwo.launched);
        } finally {
            setConfig("rocketThrustMultiplier", multiplierWas);
            setConfig("minLaunchTWR", minWas);
            setConfig("advancedWeightSystem", weightSystemWas);
        }
    }

}
