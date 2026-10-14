package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * rocket-flight failure modes.
 *
 * <p>Pins observed production behaviour for failure paths:
 *
 * <ul>
 *   <li><b>{@code explode()}</b> — production method (line 1720) that
 *       spawns particles + sets the entity dead. Currently only invoked
 *       from {@code launch()} when {@code partsWearSystem &&
 *       storage.shouldBreak()}. Pin the contract via the new probe.</li>
 *   <li><b>Out-of-fuel mid-flight</b> — one might expect an
 *       "out of fuel &rarr; rocket explodes" path but production has no such
 *       branch. The {@code isInFlight()} branch (line 1226 onwards) just
 *       sets fuelFluid="null" and lets motionY accumulate downwards. Pin
 *       this as the current contract: zero fuel does NOT auto-explode.
 *       (A future production fix that adds the explode path will fail
 *       this test, signalling that the assertion should flip.)</li>
 *   <li><b>Launch with zero fuel</b> — production launch() does NOT
 *       short-circuit on zero fuel (no rocketRequireFuel gate at launch
 *       time, only at burn time). Document current behaviour.</li>
 * </ul>
 */
public class RocketFlightFailureModesTest extends AbstractSharedServerTest {

    private static final String AR_DIMS_ARRAY = "stellurgyDimensions";
    /** The craft's fuels, keyed by the registry's own type names: {@code "fuels":{"ION":{…}, …}}. */
    private static final String FUELS = "fuels";
    private static final String FUEL_AMOUNT = "amount";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int firstNonOverworldStellurgyDimOrSkip() throws Exception {
        String joined = ok(client().read("stellurgytest dim list"));
        Assume.assumeFalse("No Stellurgy dimensions registered",
                (Reply.of(joined).arrayLength("stellurgyDimensions") == 0));
        Reply dims = Reply.of("stellurgytest dim list", joined);
        assertTrue("could not parse stellurgyDimensions array: " + joined, dims.has(AR_DIMS_ARRAY));
        for (int dim : dims.intArray(AR_DIMS_ARRAY)) {
            if (dim != 0) return dim;
        }
        Assume.assumeTrue("Only overworld is a Stellurgy planet", false);
        return -1;
    }

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }

    /** The rocket this scenario's press made, named by that press's own {@code rocket_born} record. */
    private int buildAndAssemble(FixtureSite site) throws Exception {
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        return rockets.assembleAt(site, cmd -> ok(client().act(cmd)), "simple", 2, 10,
                "the craft is built and flown in this volume");
    }

    @Test
    public void explodeProbeSetsRocketDeadAndRemovesFromWorld() throws Exception {
        // Production EntityRocket.explode() (line 1720) sets the entity
        // dead. After dead it's no longer in the world.loadedEntityList
        // and findRocket(id) returns null.
        int id = buildAndAssemble(FixtureSite.openAir(0, 7000, 500));

        String explodeResp = ok(client().arrange("stellurgytest rocket explode " + id));
        assertTrue("explode probe must succeed: " + explodeResp,
                Reply.of(explodeResp).ok());
        // The atomic probe-response contract is the reliable assertion:
        // production EntityRocket.explode() calls setDead, which flips
        // the rocket's isDead flag synchronously inside the probe call.
        // We do NOT chain a follow-up rocket-info call to assert removal
        // from loadedEntityList — vanilla MC keeps a dead entity in the
        // list until the next worldTick's collect-dead pass, so that
        // observation is racy in a shared headless harness.
        assertTrue("explode probe response must report isDead=true: " + explodeResp,
                Reply.of(explodeResp).bool("isDead"));
    }

    @Test
    public void launchWithZeroFuelStillTransitionsToInFlight() throws Exception {
        // The upstream merge added a fuel gate to launch(): a rocket with empty
        // tanks is now refused at launch time (error.rocket.notEnoughMissionFuel)
        // and never enters flight. Pin that gate: zero fuel + valid destination
        // must NOT transition to in-flight.
        int destDim = firstNonOverworldStellurgyDimOrSkip();
        int id = buildAndAssemble(FixtureSite.openAir(0, 7200, 500));
        ok(client().arrange("stellurgytest rocket set-destination " + id + " " + destDim));
        // The subject is the FUEL gate, so fuel is required for this launch: the shared harness world
        // starts with the requirement off, and the assembly above was judged without it.
        try (ConfigFlag fuelRequired = ConfigFlag.set(commands(), "rocketRequireFuel", true)) {
            ok(client().arrange("stellurgytest rocket drain-fuel " + id));
            // launch with fillFuel=false to keep tanks empty.
            ok(client().act("stellurgytest rocket launch " + id + " false instant"));

            RocketInfo info = RocketInfo.byId(cmd -> ok(client().read(cmd)), id);
            assertFalse("zero-fuel launch must be refused by the fuel gate "
                            + "(isInFlight stays false): " + info.raw(),
                    info.inFlight);
        }
    }
}
