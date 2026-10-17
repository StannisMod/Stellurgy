package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a nuclear core gives the nozzle it stands behind, read through the rocket assembler's scan (C10
 * STAT-35, STAT-36): a core delivers power only while it holds fuel, and only to the nozzle whose
 * column it belongs to; a nozzle with no powered core behind it gives no thrust, and a rocket with no
 * thrust is refused {@code NOENGINES}.
 *
 * <p>The three fixtures share one chassis — two nuclear nozzles — and differ only in the cores: one
 * fuelled core directly above each nozzle ({@code with-nuclear-stack}), the same two cores left empty
 * ({@code with-nuclear-stack-unfuelled}), and one fuelled core in the centre column where nothing but
 * air is below it ({@code with-nuclear-misplaced}). The fixture loads a fuel element through the core's
 * own slot, as a player's right-click does.</p>
 *
 * <p>The weight gate is switched off in each scenario: two NERVA-class cores lift about 390 kN, and
 * whether that clears this chassis's weight is the launch gate's question (C10 STAT-26..29), not this
 * one's. With the gate off, {@code NOENGINES} means exactly "no thrust".</p>
 *
 * <p>Does not see: a tier-2 nozzle's stack (the same rule, built by {@code ChemicalMotorActuators}),
 * burn-up and spent fuel (STAT-35's other regions), or the magnitude of the thrust.</p>
 */
public class NuclearEngineRocketAssemblyTest extends AbstractSharedServerTest {

    /** A fuelled core directly behind each nozzle powers it: the rocket builds with thrust. */
    @Pins({"STAT-35", "STAT-36"})
    @RedWitness(member = "TileNuclearCore#isFuelled", at = "return ItemNuclearFuelElement.hasBurnupLeft(fuel.getStackInSlot(0));",
            replacedBy = "return false;", fails = "STAT-36 fuelled cores", on = "2026-10-17")
    @Test
    public void nuclearCoreAboveMotorContributesNuclearThrust() throws Exception {
        try (ConfigFlag gateOff = ConfigFlag.set(commands(), "advancedWeightSystem", false)) {
            FixtureSite site = FixtureSite.openAir(0, 1700, 500);
            int[] bp = RocketFixture.placeAt(site, cmd -> String.join("\n", client().arrange(cmd)),
                    "with-nuclear-stack", 2, 10, "the craft whose thrust is read stands in this volume");
            long mark = rockets.mark();
            String assemble = RocketFixture.assembleBuilt(site, cmd -> String.join("\n", client().act(cmd)), bp);
            java.util.List<String> born = rockets.allBornAt(mark, bp);
            assertTrue("STAT-36 fuelled cores behind the nozzles must give thrust, so the rocket builds: "
                    + assemble, Reply.of(assemble).ok() && born.size() == 1);
            RocketInfo info = RocketInfo.byId(
                    cmd -> String.join("\n", client().read(cmd)), (int) Events.number(born.get(0), "e"));
            assertEquals("with-nuclear-stack must register both nuclear motors: " + info.raw(),
                    2, info.engineCount);
            assertTrue("STAT-36 fuelled cores behind the nozzles must give thrust: " + info.raw(), info.thrust > 0);
        }
    }

    /** The same stack with its cores empty gives no power, so no thrust. */
    @Pins("STAT-35")
    @RedWitness(member = "BlockNuclearCore#getThermalPower", at = "core != null && core.isFuelled()",
            replacedBy = "core != null", fails = "STAT-35 an empty core", on = "2026-10-17")
    @Test
    public void anEmptyCoreGivesTheNozzleNothing() throws Exception {
        try (ConfigFlag gateOff = ConfigFlag.set(commands(), "advancedWeightSystem", false)) {
            // R11-OK: BlockNuclearCore#getThermalPower deciding an empty core gives no power, read as
            // TileRocketAssemblingMachine#scanRocket's NOENGINES verdict, asserted in pressExpectingNoEngines.
            pressExpectingNoEngines(FixtureSite.openAir(0, 1900, 500), "with-nuclear-stack-unfuelled",
                    "STAT-35 an empty core must give no power, so the scan finds no thrust");
        }
    }

    /** A fuelled core that is not in a nozzle's column feeds nothing. */
    @Pins("STAT-36")
    @RedWitness(member = "RocketEngineTally#coreFeedsNozzle",
            at = "return below.getY() >= 0 && world.getBlockState(below).getBlock() instanceof BlockNuclearRocketMotor;",
            replacedBy = "return true;", fails = "STAT-36 a core no nozzle", on = "2026-10-17")
    @Test
    public void misplacedNuclearCoreFailsAssemblyWithNoEngines() throws Exception {
        try (ConfigFlag gateOff = ConfigFlag.set(commands(), "advancedWeightSystem", false)) {
            // R11-OK: RocketEngineTally#coreFeedsNozzle deciding which cores feed a nozzle, read as
            // TileRocketAssemblingMachine#scanRocket's NOENGINES verdict, asserted in pressExpectingNoEngines.
            pressExpectingNoEngines(FixtureSite.openAir(0, 1800, 500), "with-nuclear-misplaced",
                    "STAT-36 a core no nozzle stands under must feed none");
        }
    }

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }

    /**
     * Lay {@code variant} and press it, and judge the press: the scan's status is {@code NOENGINES}
     * (the verdict, under {@code verdict}), and — checked after it, so a press that DID build fails on
     * the verdict and not here — the press made no rocket.
     */
    private void pressExpectingNoEngines(FixtureSite site, String variant, String verdict) throws Exception {
        // FIRST link: the volume this build stands in is EMPTY. The site is in open air, so this
        // ASSERTS rather than digs, and its fill force-loads every chunk in the box — the warmup it
        // replaces lost nothing. Nothing flies here: the subject is the assembly scan's REFUSAL.
        int[] bp = RocketFixture.placeAt(site, cmd -> String.join("\n", client().arrange(cmd)),
                variant, 2, 10,
                "the build the assembly scan must reject stands in this volume");
        long mark = rockets.mark();
        String assemble = RocketFixture.assembleBuilt(site, cmd -> String.join("\n", client().act(cmd)), bp);
        java.util.List<String> born = rockets.allBornAt(mark, bp);
        assertEquals(verdict + ": " + assemble, "NOENGINES", Reply.of("assemble", assemble).text("status"));
        assertTrue("a rejected build must make no rocket, but the press wrote " + born
                + "; assemble " + assemble, born.isEmpty());
    }

}
