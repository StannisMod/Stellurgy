package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
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
 * <p>The weight gate is switched off in each assembly scenario: two NERVA-class cores lift about 390 kN,
 * and whether that clears this chassis's weight is the launch gate's question (C10 STAT-26..29), not this
 * one's. With the gate off, {@code NOENGINES} means exactly "no thrust".</p>
 *
 * <p>The fourth scenario flies the fuelled stack and burns one element out
 * ({@link #anElementBurnsOutAtItsCapacityAndItsCoreGivesNoMorePower}): burn-up, spent fuel and the thrust
 * that leaves with the power. It needs the craft weighed, so it keeps the weight system and opens only the
 * launch gate's margin for its press.</p>
 *
 * <p>Does not see: a tier-2 nozzle's stack (the same rule, built by {@code ChemicalMotorActuators}, is
 * {@code TierTwoCraftFlightModelGroupTest}'s).</p>
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
    @Regions(of = "STAT-35", value = "empty")
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

    // ---- burn-up: an element is spent at its capacity, and a spent core gives nothing --------------

    /** W: a NERVA-class core's rated thermal power — C10's component table, "a nuclear core is rated in WATTS, 1.1·10⁹". */
    private static final double CORE_POWER_W = 1.1e9;

    /**
     * J: what one fuel element gives before it is spent — C10 STAT-35, {@code mass × enrichment × 8.2·10¹³ J/kg},
     * for the element the items subsystem names: one kilogram of 93 % U-235.
     */
    private static final double ELEMENT_CAPACITY_J = 1.0 * 0.93 * 8.2e13;

    /**
     * s: one tier-1 flight tick — the tick vanilla's 0.08 blocks/tick² is 9.81 m/s² in (C10 STAT-6, units).
     * A constant: a final double computed once from two literals.
     */
    private static final double TICK_SECONDS = Math.sqrt(0.08 / 9.81);

    /** The nuclear nozzle's jet efficiency {@code F·v_e / 2P}: NERVA XE-Prime, the motor's design (C10 STAT-30). */
    private static final double JET_EFFICIENCY = 0.753;

    /**
     * m/s: hydrogen through a core at its rated 2 700 K — C10 STAT-32's {@code 8 500·sqrt((T/2 700)·(2.016/M))}
     * at its own anchor.
     */
    private static final double HYDROGEN_EXHAUST_VELOCITY = 8_500.0;

    /**
     * Relative band on an energy the cores produced and on a thrust. The free flight applies the cores'
     * thrust through a climb it computes in single precision from a thrust carried in whole newtons
     * ({@code StatsRocket#getThrust} is a long): one newton of the two cores' 389 788 N is 2.6e-6, the float
     * climb 6e-8. 1e-5 holds both; the smallest defect it exists to see — the energy taken over a tick of
     * 1/20 s instead of the 0.0903 s the flight integrates over — moves the figure by 45 %.
     */
    private static final double FLIGHT_PRECISION = 1e-5;

    /**
     * EXPERIMENT dose: the element under test is arranged this many full-power ticks short of its capacity.
     * Long enough that the ticks the server runs between the reads of a loaded box do not use it up before
     * the second window opens — 30 did, measured 2026-10-18 at nine classes in parallel
     * ({@code logs/t490u-classes-1.log}: spent by flight tick 32) — and short enough that the hydrogen aboard,
     * 4 000 mB at 58.5 mB a full-power tick (68 ticks), outlasts it.
     */
    private static final int TICKS_LEFT_IN_THE_ELEMENT = 45;

    /**
     * EXPERIMENT dose of the first window: a few ticks of full power, short of the element's
     * {@link #TICKS_LEFT_IN_THE_ELEMENT} so that it is still fresh at the window's end — what the window
     * shows is that it is NOT spent before its capacity, to within the ticks it then has left.
     */
    private static final int FUELLED_WINDOW_TICKS = 5;

    /** EXPERIMENT dose of the second window: longer than the element has left, so it burns out inside it. */
    private static final int BURN_OUT_WINDOW_TICKS = 2 * TICKS_LEFT_IN_THE_ELEMENT;

    /**
     * How far above the site the flight reaches, blocks. Two cores lift this chassis at a thrust-to-weight
     * of about one (389 788 N on 39.5 t), and one core not at all: measured 2026-10-18, the rocket rose 1.4
     * blocks and settled back on its pad after the burn-out ({@code logs/t490u-nuclear-5.log}).
     */
    private static final int FLIGHT_HEADROOM = 10;

    /**
     * A fuel element burns out at its capacity, and its core then gives nothing (C10 STAT-35): one of the
     * two fuelled cores of {@code with-nuclear-stack} is arranged {@link #TICKS_LEFT_IN_THE_ELEMENT} ticks of
     * full power short of its capacity, the rocket flies at full vertical throttle, and two windows of two
     * reads each judge what production did with the energy the burn took.
     *
     * <p>The expected numbers come from the contract alone. Two fuelled cores feed the two nozzles; the
     * thrust is power-bound, {@code F = 2ηP/v_e} per core; free flight at full throttle with Flight Assist off
     * applies exactly that thrust while it stays under the law's acceleration cap (a premise, read below);
     * the jet then takes {@code F·v_e/2η·τ = P·τ} of each core every flight tick. So over the short first
     * window the element arranged near its end stays fresh and its burn-up rises by the window's flight
     * ticks × {@code P·τ}; over the second it becomes spent fuel, its core's power falls to nothing and the
     * rocket's thrust by that core's share, {@code 2ηP/v_e}.</p>
     *
     * <p>The flight ticks are counted by the rocket itself ({@code freeFlightTicksSinceStart}), in the same
     * reply as the burn-ups, so a slow box changes how many ticks a window holds and never the verdict. Two
     * cores lift this chassis at a thrust-to-weight of about one, so the rocket rises a block and settles
     * back on its pad once one core is spent; the count stops with the burn, and so does the burn-up.</p>
     *
     * <p>A mechanics test: probes arrange the flight (mode, Flight Assist off, the start, the throttle) and
     * the element's burn-up through the element's own setter; production's flight tick does the rest. It
     * does not see a pilot's key reaching the throttle, whether the burn-out happens in exactly the tick the
     * burn-up reaches capacity (only that it has not happened {@link #FUELLED_WINDOW_TICKS}-odd ticks short of
     * it, and has a window later), the spent element's mass, the classic ascent's drain of the cores (the
     * same {@code StorageChunk#depleteCores}, entered at the rated thrust), or a core with no element at all
     * ({@link #anEmptyCoreGivesTheNozzleNothing}).</p>
     */
    @Pins("STAT-35")
    @Regions(of = "STAT-35", value = {"fuelled", "burns out this tick", "spent"})
    @RedWitness(verdict = "STAT-35 an element short of its capacity stays fresh",
            member = "TileNuclearCore#produce", at = "if (after < ItemNuclearFuelElement.CAPACITY_JOULES) {",
            replacedBy = "if (after < ItemNuclearFuelElement.CAPACITY_JOULES - 4.4e9) {",
            fails = "STAT-35 an element short of its capacity stays fresh", on = "2026-10-18")
    @RedWitness(verdict = "STAT-35 an element short of its capacity stays fresh",
            member = "TileNuclearCore#produce", at = "double after = ItemNuclearFuelElement.burnup(element) + joules;",
            replacedBy = "double after = ItemNuclearFuelElement.burnup(element);",
            fails = "STAT-35 an element short of its capacity stays fresh", on = "2026-10-18")
    @RedWitness(verdict = "STAT-35 an element short of its capacity stays fresh",
            member = "EntityRocket#burnPropellant", at = "* StatsRocket.TICK_SECONDS * Math.max(1, ticks);",
            replacedBy = "* 0.05 * Math.max(1, ticks);",
            fails = "STAT-35 an element short of its capacity stays fresh", on = "2026-10-18")
    @RedWitness(verdict = "STAT-35 an element that reached its capacity becomes spent fuel",
            member = "TileNuclearCore#produce", at = "if (after < ItemNuclearFuelElement.CAPACITY_JOULES) {",
            replacedBy = "if (true) {",
            fails = "STAT-35 an element that reached its capacity becomes spent fuel", on = "2026-10-18")
    @RedWitness(verdict = "STAT-35 the spent element's core gives no power",
            member = "BlockNuclearCore#getThermalPower", at = "core != null && core.isFuelled()",
            replacedBy = "core != null && !core.fuel().getStackInSlot(0).isEmpty()",
            fails = "STAT-35 the spent element's core gives no power", on = "2026-10-18")
    @RedWitness(verdict = "STAT-35 with one core spent the thrust is the other core's alone",
            member = "EntityRocket#burnPropellant", at = "stats.setCoreThermalPower(storage.depleteCores(joules));",
            replacedBy = "storage.depleteCores(joules);",
            fails = "STAT-35 with one core spent the thrust is the other core's alone", on = "2026-10-18")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<Reply> fuelled = Window.over(",
            why = "a burn-up is a value that rises with every tick of power; two reads and the flight ticks between them")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<Reply> burnOut = Window.over(",
            why = "no record announces a burn-out; the element's state and the stack's power at two reads name it")
    @Test
    public void anElementBurnsOutAtItsCapacityAndItsCoreGivesNoMorePower() throws Exception {
        FixtureSite site = site();
        int id;
        // Two NERVA-class cores do not lift this chassis past the launch gate's margin, and whether they do
        // is the gate's question (C10 STAT-26..29), not this one's: the gate is opened for the press alone,
        // so the scan weighs the craft as the flight will.
        try (ConfigFlag gateOpen = ConfigFlag.set(commands(), "minLaunchTWR", 0.0)) {
            id = rockets.assembleAt(site, c -> act(c).raw(), "with-nuclear-stack", 2, FLIGHT_HEADROOM,
                    "the nuclear rocket whose element burns out stands in this volume");
        }
        Reply mode = arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT");
        requireArranged("the rocket must be put in free flight mode: " + mode, mode.ok());
        Reply assist = arrange("stellurgytest rocket set-flight-assist " + id + " off");
        requireArranged("Flight Assist must be off, so the throttle is the thrust: " + assist,
                assist.ok() && !assist.bool("flightAssistOn"));

        Reply built = info(id);
        Reply[] cores = built.elements("cores");
        requireArranged("with-nuclear-stack must carry two cores: " + built, cores.length == 2);
        for (Reply core : cores) {
            requireArranged("each core must hold a fresh element and give its rated power: " + built,
                    isFresh(core) && core.number("burnupJ") == 0.0 && core.number("powerW") == CORE_POWER_W);
        }
        Reply nearlySpent = cores[0];
        Reply fresh = cores[1];
        double powerTick = CORE_POWER_W * TICK_SECONDS;
        Reply burnup = arrange("stellurgytest arrange core-burnup " + id + " " + nearlySpent.integer("x") + " "
                + nearlySpent.integer("y") + " " + nearlySpent.integer("z") + " "
                + (ELEMENT_CAPACITY_J - TICKS_LEFT_IN_THE_ELEMENT * powerTick));
        requireArranged("one element must be arranged " + TICKS_LEFT_IN_THE_ELEMENT
                + " ticks of full power short of its capacity: " + burnup, burnup.ok() && burnup.bool("fresh"));

        // Nobody stands near the rocket, so nothing ticks it until a ticket holds the chunk its column
        // stands in; the server's own entity tick then flies it. The ticket is taken only once the flight
        // has started AND the throttle is set, so the first tick the rocket flies is already at full power.
        String chunk = (int) built.number("dim") + " " + Math.floorDiv((int) Math.floor(built.number("posX")), 16) + " "
                + Math.floorDiv((int) Math.floor(built.number("posZ")), 16);
        // The harness's server runs with consumption off; a flight burns its propellant and its cores
        // only with it on, as the game ships.
        try (ConfigFlag fuelRequired = ConfigFlag.set(commands(), "rocketRequireFuel", true)) {
            Reply started = arrange("stellurgytest rocket start-free-flight " + id);
            requireArranged("the rocket must be in free flight, its tanks filled: " + started,
                    started.ok() && started.bool("isInFlight"));
            Reply throttle = act("stellurgytest rocket free-flight-input " + id + " 0 1 0 0 0");
            requireArranged("full vertical throttle must reach the rocket: " + throttle,
                    throttle.ok() && throttle.bool("applied"));

            // Read while the rocket does not tick yet: the craft as it starts the burn.
            double twoCoresThrust = 2 * JET_EFFICIENCY * (2 * CORE_POWER_W) / HYDROGEN_EXHAUST_VELOCITY;
            Reply flying = info(id);
            requireArranged("the rocket must burn hydrogen with both cores' thrust, " + twoCoresThrust + " N: "
                            + flying, "hydrogen".equals(flying.text("workingFluid"))
                            && Math.abs(flying.number("thrust") - twoCoresThrust) <= twoCoresThrust * FLIGHT_PRECISION);
            // The free-flight law caps the thrust acceleration at FreeFlightPhysics#MAX_THRUST_ACCEL
            // blocks/tick²; under it the applied force IS the thrust, which is what makes each core's tick P·τ.
            requireArranged("the thrust must accelerate the rocket under the free-flight law's cap of "
                            + dev.stannismod.stellurgy.api.FreeFlightPhysics.MAX_THRUST_ACCEL + " blocks/tick²: "
                            + flying, flying.number("thrust") / flying.number("mass_kg") * TICK_SECONDS * TICK_SECONDS
                            < dev.stannismod.stellurgy.api.FreeFlightPhysics.MAX_THRUST_ACCEL);
            Reply held = arrange("stellurgytest chunk forceload " + chunk);
            requireArranged("the rocket's chunk must be held loaded: " + held, held.ok());

            Window<Reply> fuelled = Window.over(() -> info(id), client(), GameTicks.server(),
                    t -> GameTicks.advance(client(), GameTicks.server(), t), FUELLED_WINDOW_TICKS);
            double fuelledTicks = fuelled.change(r -> r.number("freeFlightTicksSinceStart"));
            requireArranged("the rocket must fly inside the first window: " + fuelled, fuelledTicks > 0);
            double fuelledEnergy = fuelledTicks * powerTick;
            // A spent element reports its whole capacity as burn-up, so this one verdict also says the element
            // stayed fresh: spent before the window, its burn-up would not move; spent inside it, it would
            // move by what was left rather than by what the core produced.
            assertEquals("STAT-35 an element short of its capacity stays fresh, its burn-up advanced by the energy"
                            + " its core produces: " + fuelled,
                    fuelledEnergy, fuelled.change(r -> coreAt(r, nearlySpent).number("burnupJ")),
                    fuelledEnergy * FLIGHT_PRECISION);

            Window<Reply> burnOut = Window.over(() -> info(id), client(), GameTicks.server(),
                    t -> GameTicks.advance(client(), GameTicks.server(), t), BURN_OUT_WINDOW_TICKS);
            double burnOutEnergy = burnOut.change(r -> r.number("freeFlightTicksSinceStart")) * powerTick;
            // What the element had left when this window opened is at most what it was arranged with less
            // what the first window gave it; the window must give more than that, or it saw no burn-out.
            requireArranged("the second window must give the element more than it had left: " + burnOut,
                    burnOutEnergy > TICKS_LEFT_IN_THE_ELEMENT * powerTick - fuelledEnergy);
            // A spent element reports its whole capacity as burn-up, so its burn-up moves across the window
            // only if the element was still fresh when the window opened.
            requireArranged("the element must still be fresh when the second window opens: " + burnOut,
                    burnOut.change(r -> coreAt(r, nearlySpent).number("burnupJ")) > 0);
            assertEquals("STAT-35 an element that reached its capacity becomes spent fuel: " + burnOut,
                    1.0, burnOut.change(r -> spentAt(r, nearlySpent) ? 1 : 0), 0.0);
            assertEquals("STAT-35 the spent element's core gives no power: " + burnOut,
                    -CORE_POWER_W, burnOut.change(r -> coreAt(r, nearlySpent).number("powerW")), 0.0);
            // Read through the energy the jet takes from the core still fuelled, not through the thrust
            // figure: the rocket settles on its pad once one core is spent, and a touchdown rescans the
            // craft, which would set the figure right whatever the flight had been doing.
            assertEquals("STAT-35 with one core spent the thrust is the other core's alone: " + burnOut,
                    burnOutEnergy, burnOut.change(r -> coreAt(r, fresh).number("burnupJ")),
                    burnOutEnergy * FLIGHT_PRECISION);
            measured("burn-out", "fuelledTicks=" + fuelledTicks, "fuelledEnergy=" + fuelledEnergy,
                    "burnOutEnergy=" + burnOutEnergy, fuelled, burnOut);
        } finally {
            arrange("stellurgytest chunk release " + chunk);
        }
    }

    /** The readings a band or a dose here was set from, printed on a green run as on a red one. */
    private static void measured(String what, Object... readings) {
        StringBuilder line = new StringBuilder("[measured] ").append(what);
        for (Object reading : readings) {
            line.append(" | ").append(reading);
        }
        System.out.println(line);
    }

    /** One read of the rocket: its stats, its flight and every core aboard, in one reply. */
    private Reply info(int id) throws Exception {
        Reply r = read("stellurgytest rocket info " + id);
        requireArranged("the rocket must answer its info: " + r, r.has("cores"));
        return r;
    }

    /** The core at {@code core}'s storage address, in {@code reply}. */
    private static Reply coreAt(Reply reply, Reply core) {
        return reply.elementWhereAll("cores", "x", String.valueOf(core.integer("x")),
                "y", String.valueOf(core.integer("y")), "z", String.valueOf(core.integer("z")));
    }

    /** Whether a core's slot holds a fresh element. */
    private static boolean isFresh(Reply core) {
        return "stellurgy:nuclearfuelelement".equalsIgnoreCase(core.text("item"));
    }

    /** Whether the core at {@code core}'s address holds spent fuel. */
    private static boolean spentAt(Reply reply, Reply core) {
        return "stellurgy:spentnuclearfuel".equalsIgnoreCase(coreAt(reply, core).text("item"));
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
