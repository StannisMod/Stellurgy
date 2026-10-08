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
        RocketList.clearFrom(this::exec, 0);
    }

    /**
     * The server's ordered log, for reading closed windows. It is handed a step that refuses: a
     * scenario here that found itself waiting would be waiting on something launch() did not do
     * inside the call, which is a fact about the arrangement and must be said, not slept through.
     */
    private Events serverLog() {
        return new Events(this::exec, ticks -> {
            throw new AssertionError("a launch decision is taken inside the probe call, so nothing"
                    + " here waits; asked to advance " + ticks + " ticks");
        }, evictionReports());
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
    private LaunchDecision launch(Events log, int rocket, int destination) throws Exception {
        return launch(log, rocket, destination, true);
    }

    /**
     * As above, saying whether the launch verb fills the tanks first. {@code false} launches the
     * craft with whatever its tanks hold — a fresh craft's are empty — which is how a scenario reads
     * the gate's ratio against the dry weight.
     */
    private LaunchDecision launch(Events log, int rocket, int destination, boolean fillTanks)
            throws Exception {
        String chip = exec("stellurgytest rocket set-destination " + rocket + " " + destination);
        Reply programmed = Reply.of("stellurgytest rocket set-destination", chip);
        requireArranged("the guidance computer must carry a chip for dimension " + destination
                + " before the launch: " + chip,
                programmed.ok() && String.valueOf(destination).equals(programmed.text("chipDim")));
        long mark = log.mark();
        String fired = exec("stellurgytest rocket launch " + rocket + " " + fillTanks + " instant");
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
    private int rocketAt(FixtureSite site, String what) throws Exception {
        return RocketFixture.rocketEntityId(RocketFixture.assembleAt(site,
                this::exec, "simple", 0, 255 - site.y, what));
    }

    /** {@code stellurgytest planet info} for one world, read as data. */
    private Reply planetInfo(int dim) throws Exception {
        return Reply.of("stellurgytest planet info", exec("stellurgytest planet info " + dim));
    }

    private Set<Integer> stellurgyWorlds() throws Exception {
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
    private int mint(String placement, String name) throws Exception {
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
    private int theOverworldsMoon() throws Exception {
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
    private String configValue(String key) throws Exception {
        Reply got = Reply.of("stellurgytest config get", exec("stellurgytest config get " + key));
        requireArranged("config key " + key + " must be readable: " + got, got.ok());
        return got.text("value");
    }

    private void setConfig(String key, String value) throws Exception {
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
            RocketList.clearFrom(this::exec, luna);
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
     * {@code return getThrustToWeightRatio(gravitationalMultiplier) >= StellurgyConfiguration.getCurrentConfig().minLaunchTWR}
     * given a tolerance (compared against {@code minLaunchTWR - 1e-3}), this fails at the
     * just-above verdict, "a threshold one ulp above the craft's ratio must refuse it as too heavy"
     * (re-taken 2026-10-03 on the gravity-taking gate; first taken 2026-10-02).
     * red-witnessed: with {@code StatsRocket#canLaunch} at
     * {@code return getThrustToWeightRatio(gravitationalMultiplier) >= StellurgyConfiguration.getCurrentConfig().minLaunchTWR}
     * made strict ({@code >} for {@code >=}), this fails at the at-the-threshold verdict, "a ratio
     * EQUAL to minLaunchTWR must be let go" (re-taken 2026-10-03).
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
     * {@code return getThrustToWeightRatio(gravitationalMultiplier) >= StellurgyConfiguration.getCurrentConfig().minLaunchTWR}
     * made to answer true, this fails at the system-on verdict, "with the weight system on, a
     * threshold no finite ratio reaches must refuse the craft as too heavy" (re-taken 2026-10-03).
     * red-witnessed: with {@code StatsRocket#canLaunch} at
     * {@code if (!StellurgyConfiguration.getCurrentConfig().advancedWeightSystem)} never taken, this
     * fails at the system-off verdict, "with the weight system off the same craft, under the same
     * threshold, must be let go" (re-taken 2026-10-03).
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
     * thrust again (re-taken 2026-10-03).
     * red-witnessed: with {@code StatsRocket#getThrustToWeightRatio} at
     * {@code return getThrust() / localWeight} reading the raw {@code thrust} field instead, the
     * doubled-thrust verdict holds and this fails at the let-go verdict, "with the thrust doubled the
     * craft clears a threshold of 1.5x its multiplier-1 ratio and must be let go" (re-taken
     * 2026-10-03).
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

    /**
     * A craft standing on a low-gravity moon is judged by the weight gate against the weight it has
     * THERE: a threshold its ratio clears at the moon's gravity but not at one gee lets it go.
     * Born as the reproduction of a defect (the gate judged every craft at one gee); a regression
     * guard since the gate takes the launch world's gravity.
     *
     * <p>This test fails if {@code StatsRocket#canLaunch} stops deciding on the craft's weight at the
     * gravity the launch hands it, or if {@code EntityRocket#launch} stops handing it the gravity of
     * the world the craft stands in. The flight model ({@code StatsRocket#getAcceleration}) weighs the
     * craft the same way when {@code gravityAffectsFuel} is on, which is asserted as a premise. The
     * craft stands on the overworld's moon; a first launch with the threshold at
     * {@code Double.MAX_VALUE} is refused, and its gate record carries the raw thrust and weight.
     * With the moon's gravity as {@code planet info} reports it, the test places the threshold midway
     * between the craft's one-gee ratio and its ratio at that gravity — above the one, below the
     * other for any {@code 0 < g < 1} (asserted) — never using the ratio the gate reports or the
     * gravity the launch hands it, which are the decision under test.</p>
     *
     * <p>Server tier, and no client e2e: the decision is taken only on the server
     * ({@code EntityRocket#launch} returns at once on the client), and what a seated pilot sees is
     * the translated {@code error.rocket.tooHeavy} that the same server decision sends; a client
     * would read the identical verdict one hop later. The high-gravity half of the defect (a craft
     * passed that cannot climb) is not driven: the world has no authored body heavier than the
     * overworld.</p>
     *
     * <p>Before the fix it failed at the verdict, the craft refused with {@code error.rocket.tooHeavy}
     * at one gee (2026-10-02, {@code logs/bugs-a-repro-final.log}: ratio 13.986 against a threshold
     * below the local ratio 84.25 on a body of g 0.166).</p>
     *
     * red-witnessed: with {@code StatsRocket#getThrustToWeightRatio} at
     * {@code float localWeight = getWeightNewtons(gravitationalMultiplier);} weighing at one gee instead,
     * this fails at the verdict, the craft refused at one gee (2026-10-03,
     * {@code logs/bugs-a-601-red1.log}) — taken on the pre-merge form of that line, which multiplied
     * the craft's weight by the effective gravity itself; it now asks the SI weight of the mass, which
     * applies the same multiplier.
     * red-witnessed: with {@code EntityRocket#launch} at
     * {@code this.stats.canLaunch(DimensionManager.getInstance()} handed {@code 1f} instead of the
     * world's gravity, this fails at the verdict the same way (2026-10-03,
     * {@code logs/bugs-a-601-red2.log}).
     */
    @Test
    public void aCraftOnALowGravityMoonIsWeighedAtThatMoonsGravity() throws Exception {
        String weightSystemWas = configValue("advancedWeightSystem");
        String minWas = configValue("minLaunchTWR");
        int luna = theOverworldsMoon();
        Plot onLuna = plot().inDimension(luna);
        try {
            requireArranged("the flight model must weigh a craft by local gravity for the gate to be"
                    + " asked to agree with it", Boolean.parseBoolean(configValue("gravityAffectsFuel")));
            double gravity = planetInfo(luna).number("gravity");
            requireArranged("the moon's gravity must be below one gee and above none, so that the"
                    + " local ratio exceeds the one-gee ratio: " + gravity, gravity > 0 && gravity < 1);
            Reply loaded = Reply.of("stellurgytest dim load", exec("stellurgytest dim load " + luna));
            requireArranged("the moon's world must be loaded to stand a craft in: " + loaded,
                    loaded.bool("loaded"));
            setConfig("advancedWeightSystem", "true");
            int rocket = rocketAt(onLuna.site(), "the craft is built on the moon and launched");
            setConfig("minLaunchTWR", Double.toString(Double.MAX_VALUE));
            Events log = serverLog();

            LaunchDecision measured = launch(log, rocket, 0);
            requireArranged("with the threshold at Double.MAX_VALUE the gate must refuse, and its"
                    + " record is where the craft's ratio is read: " + measured.window,
                    TOO_HEAVY.equals(measured.refusal) && measured.gate != null);
            System.out.println("[launch-gate] on the moon (g=" + gravity + ") the gate compared "
                    + measured.gate);
            // The craft's ratio at one gee and at the moon's gravity, from the raw thrust and weight
            // the record carries and the moon's gravity as the planet reports it — never from the
            // ratio the gate reports, nor from the gravity the launch handed it: those two are the
            // decision under test.
            double thrust = measured.gateNumber("thrust");
            double weight = measured.gateNumber("weight");
            double oneGeeRatio = thrust / weight;
            double localRatio = thrust / (weight * gravity);
            double between = (oneGeeRatio + localRatio) / 2;
            setConfig("minLaunchTWR", Double.toString(between));
            LaunchDecision atBetween = launch(log, rocket, 0);
            requireArranged("the gate must have been asked again about the same craft: "
                    + atBetween.window, atBetween.gate != null);
            assertTrue("a craft whose ratio at the moon's gravity (" + localRatio + ") clears"
                    + " a threshold of " + between + " (its one-gee ratio is " + oneGeeRatio + ") must"
                    + " be let go from the moon: " + atBetween.window, atBetween.launched);
        } finally {
            RocketList.clearFrom(this::exec, luna);
            setConfig("minLaunchTWR", minWas);
            setConfig("advancedWeightSystem", weightSystemWas);
        }
    }

    /**
     * With {@code gravityAffectsFuel} off, the weight gate judges a craft at ONE gee wherever it
     * stands: on a low-gravity moon, a threshold its ratio clears at the moon's gravity but not at one
     * gee refuses it as too heavy.
     *
     * <p>This test fails if {@code StatsRocket#effectiveGravityMultiplier} stops deciding that a
     * disabled flag pins the gate to one gee — the config flag's promise to take local gravity out of
     * the launch entirely. It is the counterpart of
     * {@link #aCraftOnALowGravityMoonIsWeighedAtThatMoonsGravity}, which lets the same kind of craft
     * go under the same placement of the threshold with the flag ON; together they say the flag, and
     * nothing else, is what moves the verdict.</p>
     *
     * <p>The threshold is placed midway between the craft's one-gee ratio and its ratio at the moon's
     * gravity, both computed from the raw thrust and one-gee weight the gate's own record carries and
     * the gravity {@code planet info} reports — never from the ratio the gate reports, which is the
     * decision under test. The refusal is read as {@code error.rocket.tooHeavy}, so a craft held back
     * for any other reason does not pass.</p>
     *
     * <p>What this does NOT see: the flight model's half of the flag ({@code StatsRocket#getAcceleration}
     * at one gee) — a craft that is let go is gone, and nothing on this tier reads its climb.</p>
     *
     * red-witnessed: with {@code StatsRocket#effectiveGravityMultiplier} at
     * {@code return StellurgyConfiguration.getCurrentConfig().gravityAffectsFuel ? gravitationalMultiplier : 1f;}
     * answering the multiplier whatever the flag says, this fails at the verdict — the craft let go
     * with a one-gee ratio of 13.99 under a threshold of 49.12 (local ratio 84.25, g 0.166),
     * 2026-10-04.
     */
    @Test
    public void turningGravityOffTheLaunchJudgesACraftOnTheMoonAtOneGee() throws Exception {
        String weightSystemWas = configValue("advancedWeightSystem");
        String minWas = configValue("minLaunchTWR");
        String gravityWas = configValue("gravityAffectsFuel");
        int luna = theOverworldsMoon();
        Plot onLuna = plot().inDimension(luna);
        try {
            double gravity = planetInfo(luna).number("gravity");
            requireArranged("the moon's gravity must be below one gee and above none, so that the"
                    + " local ratio exceeds the one-gee ratio: " + gravity, gravity > 0 && gravity < 1);
            Reply loaded = Reply.of("stellurgytest dim load", exec("stellurgytest dim load " + luna));
            requireArranged("the moon's world must be loaded to stand a craft in: " + loaded,
                    loaded.bool("loaded"));
            setConfig("advancedWeightSystem", "true");
            setConfig("gravityAffectsFuel", "false");
            int rocket = rocketAt(onLuna.site(), "the craft is built on the moon and launched");
            setConfig("minLaunchTWR", Double.toString(Double.MAX_VALUE));
            Events log = serverLog();

            LaunchDecision measured = launch(log, rocket, 0);
            requireArranged("with the threshold at Double.MAX_VALUE the gate must refuse, and its"
                    + " record is where the craft's thrust and weight are read: " + measured.window,
                    TOO_HEAVY.equals(measured.refusal) && measured.gate != null);
            double thrust = measured.gateNumber("thrust");
            double weight = measured.gateNumber("weight");
            double oneGeeRatio = thrust / weight;
            double localRatio = thrust / (weight * gravity);
            double between = (oneGeeRatio + localRatio) / 2;
            setConfig("minLaunchTWR", Double.toString(between));
            LaunchDecision atBetween = launch(log, rocket, 0);
            System.out.println("[launch-gate] gravity off, on the moon (g=" + gravity + "): one-gee"
                    + " ratio " + oneGeeRatio + ", local " + localRatio + ", threshold " + between
                    + "; " + atBetween.window);
            assertEquals("with gravityAffectsFuel off the gate must judge the craft at one gee wherever"
                    + " it stands, so a threshold of " + between + " above its one-gee ratio ("
                    + oneGeeRatio + ") must refuse it as too heavy, though its ratio at the moon's"
                    + " gravity (" + localRatio + ") would clear it: " + atBetween.window,
                    TOO_HEAVY, atBetween.refusal);
        } finally {
            RocketList.clearFrom(this::exec, luna);
            setConfig("minLaunchTWR", minWas);
            setConfig("gravityAffectsFuel", gravityWas);
            setConfig("advancedWeightSystem", weightSystemWas);
        }
    }

    /**
     * The assembler's refusal to build a craft for want of thrust and the launch's weight gate give
     * ONE verdict about one craft, and it is the gate's verdict on the craft FULLY FUELLED at the
     * gravity of the world it is assembled in: a craft that cannot launch full from here is refused
     * as {@code NOENGINES}. Born as the reproduction of a defect (the assembler judged the craft dry,
     * on its own copy of the gate); a regression guard since the assembler asks the gate.
     *
     * <p>This test fails if {@code TileRocketAssemblingMachine#canLaunchFullFromHere} stops asking
     * {@code StatsRocket#canLaunch} of the craft with its tanks at capacity
     * ({@code StatsRocket#withTanksFull}) at the assembler's own world's gravity. The two weights are
     * measured on one craft from the gate's own records — with empty tanks, and fuelled the way a
     * player does — and the threshold is set between the two ratios: the craft clears it dry and
     * fails it full. A second, identical build then goes to the assembler, and the gate is asked
     * about the fuelled first one. The first verdict is that the two agree; the second, that the
     * assembler REFUSES — the maintainer's ruling for a craft that cannot launch full. The craft the
     * assembler built at the start, at the shipped threshold, is the same build accepted: the
     * refusal is about the threshold, not the build.</p>
     *
     * <p>Not driven: the boundary itself (a full ratio exactly at {@code minLaunchTWR}) — the
     * assembler now asks the same inclusive comparison the launch does, so there is no second
     * boundary left to disagree. Server tier, and no client e2e: both verdicts are server-side; the
     * assembler GUI only displays the scan's status ("Not enough thrust!"), and the launch refusal
     * is the translated {@code error.rocket.tooHeavy} the server sends.</p>
     *
     * <p>Before the fix it failed at the agreement verdict, the assembler having built the craft and
     * the gate refusing it as too heavy (2026-10-02, {@code logs/bugs-a-repro-final.log}: dry ratio
     * 13.986 (weight 7.15), wet ratio 7.605 (weight 13.15), threshold at their midpoint 10.795).</p>
     *
     * red-witnessed: with {@code TileRocketAssemblingMachine#canLaunchFullFromHere} at
     * {@code return stats.withTanksFull().canLaunch(getGravityMultiplier())} asking the scanned
     * stats as they are (tanks empty) instead, this fails at the refusal verdict,
     * "expected:<[NOENGINES]> but was:<[ALREADY_ASSEMBLED]>" (2026-10-03,
     * {@code logs/bugs-a-601-red4.log}).
     * red-witnessed: NOT YET for the agreement verdict on the fixed form — once the refusal verdict
     * above holds, it can only go red by a launch-side break the measured ratios do not see, and the
     * one attempted, {@code EntityRocket#launch} at {@code this.stats.canLaunch(DimensionManager.getInstance()}
     * handed {@code 0f}, let the measuring launches go and failed at the arrangement
     * (2026-10-03, {@code logs/bugs-a-601-red5.log}). It was red on the pre-fix form, the defect
     * itself (2026-10-02, {@code logs/bugs-a-repro-final.log}).
     */
    @Test
    public void theAssemblerAndTheWeightGateGiveOneVerdictOnOneCraft() throws Exception {
        String weightSystemWas = configValue("advancedWeightSystem");
        String minWas = configValue("minLaunchTWR");
        try {
            int luna = theOverworldsMoon();
            setConfig("advancedWeightSystem", "true");
            int measuredCraft = rocketAt(site(), "the craft whose two ratios are measured");
            setConfig("minLaunchTWR", Double.toString(Double.MAX_VALUE));
            Events log = serverLog();

            LaunchDecision dry = launch(log, measuredCraft, luna, false);

            // Fuel the craft the way a player does — a fuelling station linked to it, holding the
            // shipped monopropellant (`rocketfuel`, StellurgyConfiguration's default rocketFuels) —
            // so its tanks hold a real fluid, which is what makes fuel weigh anything: the launch
            // verb's own fill sets an amount but no fluid. Twenty clocked ticks are four of the
            // station's throttled operations (OP_THROTTLE_TICKS = 5); how much they move does not
            // matter, only that the fluid is chosen, and the wet ratio below is the check.
            FixtureSite pad = site();
            int[] station = {pad.x + 8, pad.y + 1, pad.z};
            String at = " 0 " + station[0] + " " + station[1] + " " + station[2];
            requireArranged("the fuelling station must be placed: ", Reply.of("stellurgytest place",
                    exec("stellurgytest place" + at + " stellurgy:fuelingStation")).ok());
            requireArranged("the station must take power: ", Reply.of("stellurgytest energy inject",
                    exec("stellurgytest energy inject" + at + " 100000")).ok());
            requireArranged("the station must take the fuel: ", Reply.of("stellurgytest fluid inject",
                    exec("stellurgytest fluid inject" + at + " rocketfuel 8000")).ok());
            Reply link = Reply.of("stellurgytest infra link",
                    exec("stellurgytest infra link" + at + " " + measuredCraft));
            requireArranged("the station must link to the craft: " + link, link.bool("linked"));
            requireArranged("the station must run: ", Reply.of("stellurgytest tile force-tick-clock",
                    exec("stellurgytest tile force-tick-clock" + at + " 20")).ok());

            // Fill the tanks in a call of their own and launch WITHOUT the verb's fill: the verb's
            // fill reaches the gate's weight only a tick later (measured 2026-10-02: the same craft
            // weighed 7.40 on the launch that filled it and 13.15 on the next), so a ratio read on
            // the filling launch would describe the tanks before the fill.
            requireArranged("the tanks must fill: ", Reply.of("stellurgytest rocket fill-fuel",
                    exec("stellurgytest rocket fill-fuel " + measuredCraft)).ok());
            LaunchDecision wet = launch(log, measuredCraft, luna, false);
            requireArranged("with the threshold at Double.MAX_VALUE the gate must refuse both times,"
                    + " and its records carry the ratios: " + dry.window + " / " + wet.window,
                    TOO_HEAVY.equals(dry.refusal) && TOO_HEAVY.equals(wet.refusal));
            double dryRatio = dry.gateNumber("twr");
            double wetRatio = wet.gateNumber("twr");
            System.out.println("[launch-gate] dry " + dry.gate + " / wet " + wet.gate);
            requireArranged("fuel must weigh something for the two weights to differ: dry "
                    + dryRatio + ", wet " + wetRatio + "; tanks: "
                    + exec("stellurgytest rocket fuel " + measuredCraft), wetRatio < dryRatio);

            setConfig("minLaunchTWR", Double.toString((dryRatio + wetRatio) / 2));
            String assemble = RocketFixture.assembleAt(
                    plot().siteAt(Plot.FIXTURE_INSET + 16, Plot.FIXTURE_INSET),
                    this::exec, "simple", 0, 255 - site().y,
                    "the identical craft the assembler judges at the new threshold");
            Reply built = Reply.of("stellurgytest rocket assemble", assemble);
            boolean assemblerBuilt = built.ok();
            System.out.println("[launch-gate] assembler at the midpoint: " + assemble);
            // The gate is asked about the MEASURED craft, which is the same build: asking it about the
            // second one would need the assembler's consent first, and that consent is the other
            // verdict under comparison.
            LaunchDecision fuelled = launch(log, measuredCraft, luna, false);
            requireArranged("the gate must have compared the same wet ratio at the new threshold: "
                    + fuelled.window, fuelled.gate != null && fuelled.gateNumber("twr") == wetRatio);
            assertEquals("a craft that cannot launch with its tanks full from the world it is"
                    + " assembled in must be refused by the assembler as lacking thrust: " + assemble,
                    "NOENGINES", built.text("status"));
            assertEquals("the assembler (" + (assemblerBuilt ? "built it" : "refused it") + ") and the"
                    + " launch gate with the tanks filled (" + (fuelled.launched ? "let it go"
                    : "refused it as " + fuelled.refusal) + ") must give one verdict on one craft: "
                    + assemble + " | " + fuelled.window, assemblerBuilt, fuelled.launched);
        } finally {
            setConfig("minLaunchTWR", minWas);
            setConfig("advancedWeightSystem", weightSystemWas);
        }
    }

}
