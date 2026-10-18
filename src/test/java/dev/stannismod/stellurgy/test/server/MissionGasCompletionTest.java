package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * MissionGasCollection.onMissionComplete contract.
 *
 * <p>Pins the player-visible cause-effect of completing a gas-collection
 * mission:</p>
 * <ul>
 *   <li>Fluid tiles in the respawned rocket are filled with 64000 mB of
 *       the configured {@code gasFluid} type.</li>
 *   <li>The respawned entity is {@code EntityStationDeployedRocket},
 *       NOT a plain {@code EntityRocket} — distinguishes the gas
 *       completion path from the ore path.</li>
 *   <li>Production guard {@code (int)getStatTag("intakePower") > 0}
 *       short-circuits the fluid fill — no intakePower set &rarr; no fill,
 *       even if the rocket otherwise has fluid tiles.</li>
 * </ul>
 *
 * <p>The returned rocket is the one the completion's own {@code rocket_born} record names
 * ({@link MissionCompletion}); nothing looks for it near the launch site.</p>
 */
public class MissionGasCompletionTest extends AbstractSharedServerTest {

    /** The oxygen the mission delivers, in mB — the arrangement's own amount, read back. */
    private static final int DELIVERED_OXYGEN_MB = 64000;

    private static final String MISSION_ID = "missionId";

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        return buildAndAssembleRocket(baseX, "simple");
    }

    /** The rocket this scenario's press made, named by that press's own {@code rocket_born} record. */
    private int buildAndAssembleRocket(int baseX, String variant) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 600);
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        return rockets.assembleAt(site, cmd -> ok(client().act(cmd)), variant, 2, 10,
                "the craft is built and flown in this volume");
    }

    private long startGasMission(int rocketId, long duration, String fluid, int intakePower) throws Exception {
        String start = ok(client().arrange(
                "stellurgytest mission start-gas 0 " + rocketId + " " + duration + " " + fluid
                        + " " + intakePower));
        assertFalse("start-gas must not error: " + start, Reply.of(start).has("error"));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId in start response: " + start, mmReply.has(MISSION_ID));
        return rockets.missionStarted(0, Long.parseLong(mmReply.text(MISSION_ID)));
    }

    private MissionCompletion completeNow(long missionId) throws Exception {
        return MissionCompletion.now(commands(), rockets, missionId);
    }

    /** With intakePower > 0 the gas mission completes WITHOUT crashing
     *  even when the rocket's storage chunk has no fluid-tile entities
     *  (BlockFuelTank in the `simple` fixture is a pure block — no
     *  TileEntity &rarr; not added to StorageChunk.liquidTiles &rarr; the fill
     *  loop iterates zero times). The strong "64000 mB of oxygen
     *  appears in cargo" assertion needs a fluid-cargo rocket fixture
     *  variant that doesn't exist yet.
     *  This test pins the no-crash safety contract on the intake>0
     *  branch as a regression net against e.g. a future NPE on
     *  null-fluid or empty-tile-list. */
    @Test
    public void gasCompletionWithIntakeAboveZeroCompletesWithoutCrash() throws Exception {
        int rid = buildAndAssembleRocket(8000);
        long mid = startGasMission(rid, 1000, "oxygen", 10);
        MissionCompletion cargo = completeNow(mid);
        assertTrue("completion must mark mission dead: " + cargo.raw(),
                cargo.isDeadAfter);
        assertTrue("completion must report fired (wasDeadBefore=false): " + cargo.raw(),
                !cargo.wasDeadBefore && cargo.completed);
    }

    /** Production gate: if `(int)stats.getStatTag("intakePower") > 0`
     *  is false, the fluid-fill loop is skipped (MissionGasCollection
     *  line 46). Counter-test pinning the gate.
     * Pins INV-MSN-07 (gas completion fills fluid tiles only when intake power is above zero).
     */
    @Pins("INV-MSN-07")
    @Test
    public void gasCompletionDoesNotFillFluidWhenIntakePowerZero() throws Exception {
        int rid = buildAndAssembleRocket(8100);
        long mid = startGasMission(rid, 1000, "water", 0);
        MissionCompletion cargo = completeNow(mid);
        // The completion returns its rocket on this path too; the gate is that its tanks stay empty.
        assertTrue("intakePower=0 -> no fluid entries: " + cargo.raw(),
                cargo.fluidEntries() == 0);
    }

    /** The gas completion path constructs an EntityStationDeployedRocket
     *  (MissionGasCollection line 60), distinguishing it from the ore
     *  path (which spawns a plain EntityRocket). Pinned on the completion's
     *  own birth records: it returns exactly one rocket, and the server
     *  holds it. */
    @Test
    public void gasCompletionRespawnsRocketInLaunchDim() throws Exception {
        int rid = buildAndAssembleRocket(8200);
        long mid = startGasMission(rid, 1000, "water", 10);
        MissionCompletion cargo = completeNow(mid);
        // The type discrimination (StationDeployed vs plain) is checked
        // via the ore counter-test in MissionOreCompletionTest.
        assertEquals("the gas completion must return exactly one rocket: " + cargo.raw(),
                1, cargo.returnedRocketCount());
        cargo.requireCargo("the returned rocket must be in the world");
    }

    /** Strong contract: with intakePower>0 AND a rocket carrying fluid
     *  tiles (TileFluidTank, exposing FLUID_HANDLER capability) the
     *  gas completion fills each fluid tile with exactly 64000 mB of
     *  the configured fluid (MissionGasCollection line 50:
     *  {@code fill(new FluidStack(type, 64000), true)}).
     *  Uses the `with-fluid-cargo` fixture variant that swaps 2 of 6
     *  fuel tanks for liquidTank blocks so StorageChunk.liquidTiles is
     *  non-empty.
     * Pins INV-MSN-07 (gas completion fills fluid tiles only when intake power is above zero).
     */
    @Pins("INV-MSN-07")
    @Test
    public void gasCompletionFillsRocketFluidTilesWithConfiguredFluid() throws Exception {
        int rid = buildAndAssembleRocket(8300, "with-fluid-cargo");
        long mid = startGasMission(rid, 1000, "oxygen", 10);
        MissionCompletion cargo = completeNow(mid);
        assertFalse("fluidEntries must be > 0 (production filled fluid tiles): " + cargo.raw(),
                cargo.fluidEntries() == 0);
        // Each filled tile holds 64000 mB of oxygen — production literal
        // at MissionGasCollection.java:50 (FluidStack(type, 64000)).
        assertTrue("fluid contents must include oxygen 64000 mB: " + cargo.raw(),
                cargo.fluidAmount("oxygen") == DELIVERED_OXYGEN_MB);
    }

    /** What a gas harvester may collect is what the world's air holds — no list beside it. Empty air
     *  offers nothing; a single nano-atmosphere of hydrogen is offered (presence, not quantity, decides);
     *  oxygen added beside it is offered too, and nothing the air does not hold is.
     *
     *  <p>The air changes through the planet's own gas exchange, and the offer is the planet's answer as
     *  {@code planet info} reports it. Does NOT see the rocket's selector or the mission it plans — only
     *  the offer they both read. Hydrogen and oxygen are used because Stellurgy itself registers their
     *  fluids; a gas no mod has bottled is measured and not offered, and that half is unpinned here.</p>
     */
    @RedWitness(verdict = "nothing to offer", member = "DimensionProperties#getHarvestableGases",
            at = "air.partialPressure(gas) > 0L", replacedBy = "air.partialPressure(gas) >= 0L",
            fails = "air emptied, so nothing is there to offer", on = "2026-10-17")
    @RedWitness(verdict = "the trace is offered", member = "DimensionProperties#getHarvestableGases",
            at = "return offered;", replacedBy = "return new ArrayList<>();",
            fails = "one nano-atmosphere of hydrogen is in the air, so hydrogen is offered", on = "2026-10-17")
    @Test
    public void aHarvesterIsOfferedExactlyTheGasesTheAirHolds() throws Exception {
        final int dim = 0;
        PlanetAir.Probe reader = cmd -> ok(client().read(cmd));
        PlanetAir.Probe arranger = cmd -> ok(client().arrange(cmd));
        PlanetAir before = PlanetAir.snapshot(reader, dim);
        try {
            run(arranger, "stellurgytest atmosphere set-density " + dim + " 0");
            assertEquals("air emptied, so nothing is there to offer", set(), offered(reader, dim));

            run(arranger, "stellurgytest planet add-gas " + dim + " hydrogen 1");
            assertEquals("one nano-atmosphere of hydrogen is in the air, so hydrogen is offered",
                    set("hydrogen"), offered(reader, dim));

            run(arranger, "stellurgytest planet add-gas " + dim + " oxygen 200000000");
            assertEquals("oxygen joined the hydrogen, so both are offered and nothing else",
                    set("hydrogen", "oxygen"), offered(reader, dim));
        } finally {
            before.restore(arranger);
        }
    }

    private static Set<String> offered(PlanetAir.Probe probe, int dim) throws Exception {
        String command = "stellurgytest planet info " + dim;
        return set(Reply.of(command, probe.run(command)).textArray("harvestable"));
    }

    private static void run(PlanetAir.Probe probe, String command) throws Exception {
        Reply.of(command, probe.run(command)).requireOk(command);
    }

    private static Set<String> set(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /**
     * Ticks between the two missions' durations that the rounding of {@code GasHarvest#missionSeconds}
     * allows: one second. With the planned harvest equal to the 64 000 mB a single tank holds, the
     * seconds are {@code floor(64000 / rate)}, and {@code 2 * floor(x / 2)} differs from
     * {@code floor(x)} by at most one — so twice the second mission is the first within a second.
     */
    private static final int ONE_SECOND_OF_TICKS = 20;

    /**
     * A gas mission over a giant takes as long as the chosen gas's PARTIAL PRESSURE says: the same
     * harvest, planned once at a pressure and once at twice it, takes half as long the second time.
     *
     * <p>The subject is the joint in {@code EntityStationDeployedRocket#onOrbitReached}: the selected
     * gas is taken out of the giant's offer and ITS partial pressure handed to
     * {@code GasHarvest.missionSeconds}, whose law {@code unit/GasHarvestTest} pins on its own. Read where
     * production keeps the plan: the {@code duration} of the {@code MissionGasCollection} it registers
     * on the giant ({@code mission state}).</p>
     *
     * <p>The arrangement is a derived gas giant realized ({@code space find-giant}, {@code space
     * realize}) — properties with air and no world, as a giant has no surface — a station orbiting it, and two unmanned rockets built and
     * assembled on that station — two, because a rocket that plans a mission is spent by it. Between the
     * two, the giant's air gains exactly as much of the offered gas as it already held. The rocket
     * reaching orbit is ARRANGED ({@code rocket force-orbit-reached}, which calls the production method):
     * the flight up is not the subject, and nothing in it bears on the plan.</p>
     *
     * <p>What it does not see: a player choosing a gas in the rocket's menu (both rockets plan the first
     * gas offered), the mission completing, and the flight to orbit.</p>
     */
    @Test
    public void aHarvestPlannedAtTwiceThePartialPressureTakesHalfAsLong() throws Exception {
        Reply found = read("stellurgytest space find-giant 8").requireOk();
        Reply realized = act("stellurgytest space realize " + found.text("cellKey") + " "
                + found.integer("variant")).requireOk();
        int giant = realized.integer("dim");
        Reply planet = read("stellurgytest planet info " + giant);
        String[] offered = planet.textArray("harvestable");
        requireArranged("the giant must offer a gas to harvest: " + planet, offered.length > 0);
        String gas = offered[0];
        long held = (long) Reply.of("planet info gases", planet.object("gases")).number(gas);

        // The space dimension's id: the shipped default, read off a fresh configuration (the probe's
        // config verb does not expose it). A server running another id would leave onOrbitReached's
        // dimension check false, and the "exactly one mission" arrangement below would say so.
        int space = new dev.stannismod.stellurgy.api.StellurgyConfiguration().spaceDimId;
        arrange("stellurgytest dim time " + space);
        Reply station = arrange("stellurgytest station create " + giant).requireOk();
        Reply info = read("stellurgytest station info " + station.integer("id")).requireOk();
        int x = info.integer("spawnX"), y = info.integer("spawnY"), z = info.integer("spawnZ");

        long atHeld = plannedDurationTicks(giant, space, x, y, z);
        arrange("stellurgytest planet add-gas " + giant + " " + gas + " " + held).requireOk();
        long atTwiceHeld = plannedDurationTicks(giant, space, x + 16, y, z);

        requireArranged("the shorter mission must be clear of the one-second floor `missionSeconds` clamps"
                        + " to, or halving the time cannot show; " + atHeld + " / " + atTwiceHeld + " ticks",
                atTwiceHeld > ONE_SECOND_OF_TICKS);
        assertTrue("a mission planned at twice the chosen gas's partial pressure must take half as long,"
                        + " within a second of rounding: " + atHeld + " ticks at " + held + ", "
                        + atTwiceHeld + " ticks at " + 2 * held + " (" + gas + ")",
                Math.abs(2 * atTwiceHeld - atHeld) <= ONE_SECOND_OF_TICKS);
    }

    /**
     * Builds an unmanned rocket on the station at {@code (x, y, z)} in the space dimension, assembles
     * it, brings it to orbit through the production method, and answers the duration, in ticks, of the
     * gas mission that registered on {@code giant} as a result.
     */
    private long plannedDurationTicks(int giant, int space, int x, int y, int z) throws Exception {
        arrange("stellurgytest fixture uv-rocket " + space + " " + x + " " + y + " " + z).requireOk();
        long mark = rockets.mark();
        act("stellurgytest rocket assemble " + space + " " + x + " " + y + " " + z).requireOk();
        int id = rockets.bornAt(mark, new int[]{x, y, z});
        Reply rocketInfo = read("stellurgytest rocket info " + id);
        requireArranged("the assembled craft must be a station-deployed rocket in the space dimension: "
                + rocketInfo, rocketInfo.text("entityClass").endsWith("EntityStationDeployedRocket")
                && rocketInfo.integer("dim") == space);
        Reply tanks = read("stellurgytest rocket storage-fluid " + id);
        requireArranged("its tanks must be empty, so both missions plan the same harvest: " + tanks,
                tanks.integer("totalAmount") == 0);

        Set<Integer> before = satelliteIds(giant);
        arrange("stellurgytest rocket force-orbit-reached " + id).requireOk();
        Set<Integer> planned = satelliteIds(giant);
        planned.removeAll(before);
        requireArranged("reaching orbit over the giant must register exactly one mission on it: " + planned,
                planned.size() == 1);
        rockets.missionStarted(giant, planned.iterator().next());
        Reply mission = read("stellurgytest mission state " + planned.iterator().next()).requireOk();
        return (long) mission.number("duration");
    }

    private Set<Integer> satelliteIds(int dim) throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (String satellite : read("stellurgytest satellite list " + dim).objectArrayOrEmpty("satellites")) {
            ids.add(Reply.of("satellite list", satellite).integer("id"));
        }
        return ids;
    }

    private static void requireArranged(String message, boolean holds) {
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(message, holds);
    }
}
