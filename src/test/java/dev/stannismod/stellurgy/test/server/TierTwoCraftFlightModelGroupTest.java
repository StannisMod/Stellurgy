package dev.stannismod.stellurgy.test.server;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.integration.vs.PhysicsUnits;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.AssembledCraft;
import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.DriveInfo;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ServerWindow;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.ShipLift;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.TransitSetup;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The flight model of a tier-2 craft, in a running game: what it WEIGHS, the field it FALLS in, and the
 * authority its own actuators give it.
 *
 * <p>NEW-GROUP: the flight model of a tier-2 craft — the mass the engine keeps for it, the gravity of
 * the world it is in, and the forces its hull can deliver against both. No existing group holds this
 * cluster: {@code WeightSystemTest} is the block table and the tier-1 rocket, and the crossing and
 * entry groups take a craft's flight as given.</p>
 *
 * <p>Three families of scenario, one world. Each states what it needs of that world rather than
 * inheriting it from the scenario before: the actuator family stands the overworld in vacuum
 * ({@link #standInVacuum}), and {@link #restoreTheAir} puts the air back, because the falling family
 * measures falls in the overworld as it boots.</p>
 *
 * <h2>1. Authority — a craft moves only by what its own hull can push and turn with, and exactly as
 * its readout says it can</h2>
 *
 * <p>The flight computer hands the flight law's wanted accelerations to the control scheme, which
 * delivers each axis through the hull's own actuators up to that axis's authority; the readout is
 * those authorities over the hull's mass. So the readout is a PREDICTION about the craft, and these
 * scenarios compare it with the craft: an acceleration measured on the physics clock
 * ({@code PhysicsStepWindow}, fed from the controller itself) against the figure the readout gives,
 * and the absence of motion where the readout gives nothing.</p>
 *
 * <h2>Why the server tier</h2>
 *
 * <p>Every fact here belongs to the server: the craft's velocity is the physics solver's, the
 * readout is the server's flight computer's, the assembler's decision is the server's. The server
 * holds these craft loaded and steps them without a player, so no client is needed to see any of it.
 * What this tier cannot see: a pilot's key reaching the controller (these commands enter on the
 * computer's own probe channel, which outranks the pilot's), and what the pilot is SHOWN.</p>
 *
 * <h2>Why vacuum</h2>
 *
 * <p>The readout does not model air. A craft in the overworld's one atmosphere loses a share of its
 * velocity to drag every step, so a measured acceleration there is the readout's figure minus a drag
 * term that grows with speed, and a comparison would be a comparison with a drag model this test
 * would have to carry. Each scenario therefore stands the overworld in vacuum first — gravity stays,
 * because a hull that hovers while it is driven is the ordinary case and hover is part of what the
 * scheme allocates.</p>
 *
 * <h2>2. Mass — the craft weighs what its blocks weigh, and is weighed again on a cadence</h2>
 *
 * <p>A ship's mass is kept two ways: incrementally, a delta per block that arrives or leaves, and by an
 * authoritative full walk over the hull at the moments the incremental path cannot be trusted (an
 * assembly, a paste, a load). The scenarios read production's own verdicts as records —
 * {@code ship_mass_compared} where the two totals are compared, {@code ship_mass_measured} for every
 * measurement with the path that ran it — keyed by the craft's physics identity.</p>
 *
 * <h2>3. Gravity — the field is the world's: a body's own multiplier, zero in a space cell, and Flight
 * Assist the switch that decides whether an unpiloted craft holds against it</h2>
 *
 * <p>Every "it did not move" here has a control that shows the craft was being simulated, because a
 * craft nobody simulates is exactly as still as one that is held, and as one that is weightless.</p>
 *
 * <p>What this group does NOT see, on this tier: a pilot's key reaching the controller (the commands
 * here enter on the computer's own probe channel), and what any player is SHOWN.</p>
 */
public class TierTwoCraftFlightModelGroupTest extends AbstractSharedServerTest {

    private static final int DIM = 0;

    /** How far past the launchpad's footprint the working volume reaches, in blocks. */
    private static final int HALO = 2;

    /** How far above the site the subject reaches: the hull's own top plus the seat. */
    private static final int HEIGHT = 12;

    /**
     * The deadline of a LINK on a record production writes about a craft — its naming, its flight
     * model, a hull change reaching the model. Expiry means the record never came. It covers the
     * slowest of those production paths: the flight model's load round, {@code MASS_ROUND_TICKS}
     * (100) apart.
     */
    private static final int LINK_BUDGET_TICKS = 200;

    /**
     * The surge velocity each drive is commanded to, in blocks per engine second. Its only job is
     * to be far enough away that the controller works at the hull's authority for many physics
     * steps; the measurement reads only the steps in which the controller reported that it was.
     */
    private static final double SURGE_COMMAND = 40.0;

    /**
     * EXPERIMENT dose of a drive: the game ticks the window stays open after the command. It bounds
     * nothing the verdict reads — the verdict is over the saturated steps inside it, whatever their
     * number — and only has to be long enough that there ARE some.
     */
    private static final int DRIVE_DOSE_TICKS = 30;

    /** STIMULUS: ticks of a zero-velocity command after a drive, so the next drive starts near rest. */
    private static final int BRAKE_TICKS = 40;

    /**
     * How closely a measured acceleration must match the readout's, as a relative difference.
     *
     * <p>Measured 2026-09-30, full-precision window, four comparisons in one run: decked surge
     * 17.440000000000012 against the readout's 17.44 m/s² (7e-16); laden-to-empty acceleration ratio
     * 0.8245614035087708 against the mass ratio 0.8245614035087719 (1.3e-15); a hull sinking in a
     * raised field at −1.600214682619349 against the readout's −1.6002146826193524 m/s² (2.2e-15);
     * free fall at −31.99999999999993 against the field's 32 blocks per engine second² (2.2e-15).
     * What is left is the summation of some forty double-precision steps. The band is six orders of
     * magnitude outside that and seven inside the smallest defect it exists to catch — a global
     * scale-down of a combined command, or a physics mass that disagrees with the readout's, costs
     * percents.</p>
     */
    private static final double ACCELERATION_BAND = 1.0e-9;

    /**
     * How far a velocity may move under a command a hull cannot deliver, and how far one that can may
     * miss the rate it was told to reach, in blocks (or radians) per engine second. Measured
     * 2026-09-30, full-precision window: 0.0 exactly for both motorless hulls' horizontal velocity,
     * 40.0 and 2.0 exactly for a commanded speed and turn rate reached, 2.3e-15 for a hovering
     * craft's vertical velocity. The band is floating-point room only.
     */
    private static final double NO_MOTION_BAND = 1.0e-9;

    private final Events events = new Events(this::exec,
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * The overworld's air as this scenario found it, read before anything here changed it, so
     * {@link #restoreTheAir} can put back exactly that.
     */
    private double airAtStart;

    /**
     * Each scenario starts with an empty sky, at standard gravity, and remembers the air it found.
     *
     * <p>The craft a previous scenario flew go on moving, so they are cleared here, at the start of
     * the one that must not meet them. One scenario raises the field; every scenario starts from the
     * standard one, so a scenario that failed with the field raised cannot hand it on. Both are READ
     * back, because the command's own answer is a chat line and not a value.</p>
     */
    @Before
    public void emptySkyAtStandardGravity() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, DIM);
        exec("ar planet set " + DIM + " gravitationalMultiplier 1");
        Reply planet = Reply.of(exec("stellurgytest planet info " + DIM));
        requireArranged("the overworld must be at standard gravity, which every scenario here"
                + " leaves it at: " + planet, planet.number("gravity") == 1.0);
        airAtStart = planet.number("atmosphereDensity");
    }

    /**
     * The actuator family's premise: the overworld in vacuum (see the class note on why). Stated by
     * each scenario that needs it, never by the class, because the falling family measures falls in
     * the air the overworld boots with.
     */
    private void standInVacuum() throws Exception {
        exec("ar planet set " + DIM + " atmosphereDensity 0");
        Reply planet = Reply.of(exec("stellurgytest planet info " + DIM));
        requireArranged("the overworld must be airless for a readout to be the whole prediction: "
                + planet, planet.number("atmosphereDensity") == 0.0);
    }

    /**
     * Put the overworld's air back as {@link #emptySkyAtStandardGravity} found it, and read it back: a
     * scenario that left the world airless would hand every later fall a different medium.
     */
    @After
    public void restoreTheAir() throws Exception {
        exec("ar planet set " + DIM + " atmosphereDensity " + Math.round(airAtStart));
        Reply planet = Reply.of(exec("stellurgytest planet info " + DIM));
        requireArranged("the overworld's air must be put back to " + airAtStart + ": " + planet,
                planet.number("atmosphereDensity") == airAtStart);
    }

    /** Lay {@code variant} at {@code site} and assemble it ({@link AssembledCraft}). */
    private AssembledCraft assemble(FixtureSite site, String variant, String what) throws Exception {
        return assembleLaid(site, lay(site, variant, what), what);
    }

    /** {@link AssembledCraft#lay} with this group's working volume, {@link #HALO} by {@link #HEIGHT}. */
    private Reply lay(FixtureSite site, String variant, String what) throws Exception {
        return layWithHeadroom(site, variant, HEIGHT, what);
    }

    /** {@link #lay}, clearing {@code height} blocks above the site instead of {@link #HEIGHT}. */
    private Reply layWithHeadroom(FixtureSite site, String variant, int height, String what)
            throws Exception {
        return AssembledCraft.lay(site, this::exec, variant, HALO, height, what);
    }

    /** {@link AssembledCraft#assemble}: press the assembler the fixture laid and resolve the craft. */
    private AssembledCraft assembleLaid(FixtureSite site, Reply fixture, String what) throws Exception {
        return AssembledCraft.assemble(site, this::exec, events, fixture, what);
    }

    /** Command a world-frame velocity on the craft's own flight computer, and require it took. */
    private void command(AssembledCraft craft, String verb, double x, double y, double z, String what)
            throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs " + verb + " " + DIM + " " + craft.physicsId
                + " " + x + " " + y + " " + z));
        // absence is the answer "refused": when the verb cannot reach a flight computer it answers
        // an error object, or shipFound false, carrying neither field — which is what this rejects.
        requireArranged(what + " — the command must reach this craft's flight computer: " + r,
                r.boolOr("commanded", false) && r.boolOr("afcResolved", false));
    }

    /**
     * Take the launchpad out from under a craft, so nothing touches it but what it does itself.
     * Answers how many pad blocks there were.
     */
    private int removePad(FixtureSite site, String what) throws Exception {
        Reply fill = Reply.of(exec("stellurgytest fill " + DIM + " " + site.x + " " + site.y + " "
                + site.z + " " + (site.x + 5) + " " + site.y + " " + (site.z + 5) + " minecraft:air"));
        requireArranged(what + " — the pad under the craft must be removed: " + fill,
                fill.ok() && fill.integer("placed") > 0);
        return fill.integer("placed");
    }

    /** The readout the craft's flight computer holds now, as the {@code model} object. */
    private Reply readout(AssembledCraft craft, String what) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs flight-model-by-id " + DIM + " " + craft.physicsId));
        requireArranged(what + " — the craft's flight computer must hold a model: " + r,
                r.bool("found") && r.object("model") != null);
        return Reply.of(r.object("model"));
    }

    /** One direction's LIVE figures out of a readout. */
    private static Reply live(Reply model, String direction) {
        return Reply.of(Reply.of(Reply.of(model.object("directions")).object(direction)).object("live"));
    }

    /**
     * Drive the craft with {@code verb} inside a physics-step window opened BEFORE the command, and
     * answer the window's record.
     *
     * <p>The window is closed by the test after {@code doseTicks} of the server's clock — the dose of
     * the experiment — and its record is read once. That it describes THIS craft is structural: it was
     * opened on this craft's flight computer's address.</p>
     */
    private String drive(AssembledCraft craft, String verb, double x, double y, double z, int doseTicks,
                         String what) throws Exception {
        ServerWindow window = ServerWindow.open(this::exec,
                "dev.stannismod.stellurgy.test.trace.PhysicsStepWindow", DIM,
                craft.afcX, craft.afcY, craft.afcZ);
        command(craft, verb, x, y, z, what);
        GameTicks.advance(client(), GameTicks.server(), doseTicks);
        long closeMark = events.mark();
        window.close();
        String reply = events.since(closeMark, "physics_step_window");
        List<String> records = Events.records(reply);
        requireArranged(what + " — closing the window must write exactly its one record: " + reply,
                records.size() == 1);
        Events.assertInstrumentRan(reply, "server_flight_controller_physics_step",
                what + " — the controller's own steps are what the window reads");
        String record = records.get(0);
        requireArranged(what + " — the physics solver must have stepped this craft's controller"
                + " while the window was open: " + record, Events.number(record, "steps") > 1);
        return record;
    }

    // ---- 1. the readout predicts the craft --------------------------------------------------------

    /**
     * A built ship accelerates as its readout predicts: driven forward, its acceleration while at
     * its authority is the readout's live surge acceleration.
     *
     * <p>The prediction is computed before the drive, from the readout alone: {@code SURGE_POSITIVE}
     * live sustained acceleration, in m/s², times {@link PhysicsUnits#ACCELERATION} into the blocks
     * per engine second² the solver integrates. The with-pilot-deck hull is chosen because its surge
     * has ONE figure — sustained and burst are the same four motors — so which of the two the scheme
     * picks when the demand exceeds the sustained figure cannot change the number.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a craft delivers the authority
     * its readout reports.</p>
     *
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#onPhysicsTick} at {@code force.set(command.force()).mul(k);} (the allocated force scaled by 0.5 on its
     * way into the solver) fails "driven forward at its authority, the craft must accelerate at the
     * readout's live surge figure (17.44 m/s²)" with 8.456 m/s², 2026-09-30</p>
     */
    @Test
    public void aBuiltShipAcceleratesAsItsReadoutPredicts() throws Exception {
        standInVacuum();
        FixtureSite site = site();
        AssembledCraft craft = assemble(site, "with-pilot-deck", "a decked craft driven forward");
        command(craft, "force-vel-by-id", 0, 0, 0, "hold it where it was built");
        removePad(site, "a craft measured in free air");

        Reply surge = live(readout(craft, "the prediction"), "SURGE_POSITIVE");
        double predictedSi = surge.number("sustainedAccel");
        requireArranged("the prediction must be one number — the hull's surge sustained and burst"
                + " figures must agree, or the scheme's choice between them decides the reading: "
                + surge, predictedSi > 0.0 && surge.number("burstAccel") == predictedSi);

        String window = drive(craft, "force-vel-by-id", 0, 0, SURGE_COMMAND, DRIVE_DOSE_TICKS,
                "a forward drive at authority");
        requireArranged("the controller must have worked at this craft's authority for some of the"
                + " window, or there is nothing to compare with the readout: " + window,
                Events.number(window, "satEngineSeconds") > 0.0);
        double measuredSi = Events.number(window, "satEngineAccelZ") / PhysicsUnits.ACCELERATION;
        measured("surge", "predictedSi=" + predictedSi, "measuredSi=" + measuredSi,
                "ratio=" + (measuredSi / predictedSi), window);
        assertEquals("driven forward at its authority, the craft must accelerate at the readout's"
                        + " live surge figure (" + predictedSi + " m/s²); it accelerated at "
                        + measuredSi + " m/s². Window: " + window,
                1.0, measuredSi / predictedSi, ACCELERATION_BAND);
    }

    // ---- 2. cargo lowers acceleration, not force --------------------------------------------------

    /**
     * The same craft carrying cargo accelerates less, by its mass ratio: the readout's mass grows by
     * the cargo, its surge force does not, and the measured acceleration falls by exactly the ratio
     * of the two masses.
     *
     * <p>The cargo is stowed in a chest standing on the deck — content, weighed by the flight
     * computer's load round, not a change to the hull — so the model learns it on its own and the
     * test links on the record of that. Twelve iron blocks: measured 2026-09-30 at 5 000 kg each,
     * sixty tonnes against a 282-tonne craft, enough to move the ratio well clear of the band and
     * little enough that the hull still hovers while it drives (sixty-four of them, 320 tonnes, did
     * not: the scheme scaled the whole command down and the craft sank while it surged).</p>
     *
     * <p>Contract: this fails if production breaks the contract that cargo lowers a craft's
     * acceleration and never its force.</p>
     *
     * <p>red-witnessed: {@code ShipHullMass#addContents} at {@code double held = dev.stannismod.stellurgy.Stellurgy.weights().getTEWeight(tile);} (a hull's contents weighed at zero) fails "the flight
     * computer must weigh the cargo that was stowed aboard — the readout's mass must GROW by it" with
     * no heavier model inside 200 ticks, 2026-09-30 — taken on the pre-merge form of that line, which
     * read the table through the engine's former singleton; the line is otherwise unchanged</p>
     * <p>red-witnessed: {@code ShipReadout#of} at {@code a[v][e.ordinal()][d.ordinal()] = views[v].authority(d, e);} (each authority scaled by the structural share of the
     * mass) fails "cargo must not change the craft's surge force" with empty 4 905 000 N, laden
     * 4 044 474 N, 2026-09-30</p>
     * <p>red-witnessed: {@code ShipHullMass#addContents} at {@code builder.add(MassContributor.ofBlock(x + 0.5 - ox, y + 0.5 - oy, z + 0.5 - oz,} (content mass placed ten blocks off the block that holds
     * it) fails "laden, the craft must accelerate less by exactly the ratio of its masses" with empty
     * 56.74, laden 28.54 blocks per engine second², 2026-09-30</p>
     */
    @Test
    public void theSameShipCarryingCargoAcceleratesLessByItsMassRatio() throws Exception {
        standInVacuum();
        FixtureSite site = site();
        Reply fixture = lay(site, "with-pilot-deck-and-hold", "a decked craft with a hold");
        // WHERE the hold is aboard is the fixture's to say: the chest stands at a fixed offset from
        // the flight computer, and the two ride into the ship's yard together.
        int[] hold = fixture.intArray("holdFromFlightComputer");
        requireArranged("the fixture must say where its hold stands: " + fixture,
                hold != null && hold.length == 3);
        AssembledCraft craft = assembleLaid(site, fixture, "a decked craft with a hold");
        command(craft, "force-vel-by-id", 0, 0, 0, "hold it where it was built");
        removePad(site, "a craft measured in free air");

        Reply empty = readout(craft, "the empty craft");
        double emptyKg = Reply.of(empty.object("mass")).number("totalKg");
        double emptyForce = live(empty, "SURGE_POSITIVE").number("sustained");
        String emptyDrive = drive(craft, "force-vel-by-id", 0, 0, SURGE_COMMAND, DRIVE_DOSE_TICKS,
                "the empty craft driven forward");
        requireArranged("the empty craft must have been driven at its authority: " + emptyDrive,
                Events.number(emptyDrive, "satEngineSeconds") > 0.0);
        double emptyAccel = Events.number(emptyDrive, "satEngineAccelZ");
        command(craft, "force-vel-by-id", 0, 0, 0, "bring it back to rest");
        GameTicks.advance(client(), GameTicks.server(), BRAKE_TICKS);

        long stowMark = events.mark();
        Reply stow = Reply.of(exec("stellurgytest vs stow " + DIM + " " + (craft.afcX + hold[0])
                + " " + (craft.afcY + hold[1]) + " " + (craft.afcZ + hold[2])
                + " minecraft:iron_block " + CARGO_BLOCKS));
        requireArranged("the cargo must go into the hold: " + stow,
                stow.bool("inventory") && stow.integer("stowed") == CARGO_BLOCKS);
        String laden = events.awaitMatching(stowMark, "flight_model_changed",
                reply -> anyHeavier(reply, craft.durable, emptyKg),
                "for this craft, solved for more than the empty " + emptyKg + " kg",
                "the flight computer must weigh the cargo that was stowed aboard — the readout's mass"
                        + " must GROW by it", LINK_BUDGET_TICKS);
        Reply loaded = readout(craft, "the laden craft");
        double ladenKg = Reply.of(loaded.object("mass")).number("totalKg");
        double ladenForce = live(loaded, "SURGE_POSITIVE").number("sustained");
        measured("cargo readout", "emptyKg=" + emptyKg, "ladenKg=" + ladenKg,
                "emptyForce=" + emptyForce, "ladenForce=" + ladenForce, laden);
        assertEquals("cargo must not change the craft's surge force: empty " + emptyForce
                + " N, laden " + ladenForce + " N", 1.0, ladenForce / emptyForce, ACCELERATION_BAND);

        String ladenDrive = drive(craft, "force-vel-by-id", 0, 0, SURGE_COMMAND, DRIVE_DOSE_TICKS,
                "the laden craft driven forward");
        requireArranged("the laden craft must have been driven at its authority: " + ladenDrive,
                Events.number(ladenDrive, "satEngineSeconds") > 0.0);
        double ladenAccel = Events.number(ladenDrive, "satEngineAccelZ");
        measured("cargo drive", "emptyAccel=" + emptyAccel, "ladenAccel=" + ladenAccel,
                "accelRatio=" + (ladenAccel / emptyAccel), "massRatio=" + (emptyKg / ladenKg),
                emptyDrive, ladenDrive);
        assertEquals("laden, the craft must accelerate less by exactly the ratio of its masses ("
                        + emptyKg + " / " + ladenKg + "); empty " + emptyAccel + ", laden " + ladenAccel
                        + " blocks per engine second². Windows: " + emptyDrive + " | " + ladenDrive,
                emptyKg / ladenKg, ladenAccel / emptyAccel, ACCELERATION_BAND);
    }

    // ---- 3. no actuators, no motion ---------------------------------------------------------------

    /**
     * A hull with nothing aboard that can push does not move under command — and the same command,
     * on a craft that has motors, does.
     *
     * <p>Both craft get one command: hold altitude and go forward. The motorless hull's readout gives
     * it nothing in any direction (read as the premise), so the flight law's wish has nowhere to go:
     * its forward velocity must not change, and its fall must be gravity's alone — nothing held it
     * up. The CONTROL is a decked craft under the identical command in the same world, which must
     * accelerate at its readout's figure; without it "did not move" could be a command that reached
     * nobody.
     * That the command did reach the motorless hull's computer is read too: its controller ran every
     * step of the window and reported, every step, that it could not deliver.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a hull with no actuators has no
     * authority and does not move under command (no transition mode).</p>
     *
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#onPhysicsTick} at {@code calc.addForceAndTorque(force, torque);} (the controller's wrench never handed to
     * the solver) fails "the CONTROL — a craft with motors under the same command — must accelerate
     * at its readout's forward figure, 17.44 m/s²" (re-witnessed 2026-09-30 on this form, which reads
     * the saturated steps' acceleration instead of the speed reached after the dose)</p>
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#onPhysicsTick} at {@code force.set(command.force()).mul(k);} (the force taken as wanted acceleration
     * times mass, bypassing the hull's actuators — the pre-actuator controller) fails "under a command
     * to go forward and hold its altitude, the motorless hull must not move forward or sideways, and
     * must fall exactly as gravity takes it", 2026-09-30</p>
     */
    @Test
    public void aHullWithNothingThatPushesDoesNotMoveUnderCommand() throws Exception {
        standInVacuum();
        FixtureSite controlSite = plot().siteAt(4, 4);
        FixtureSite bareSite = plot().siteAt(36, 4);
        AssembledCraft control = assemble(controlSite, "with-pilot-deck", "the control: a craft with motors");
        AssembledCraft bare =assemble(bareSite, "hull-without-actuators", "a hull with nothing that pushes");
        Reply bareModel = readout(bare, "the motorless hull");
        for (String direction : new String[]{"SURGE_POSITIVE", "HEAVE_POSITIVE"}) {
            Reply d = live(bareModel, direction);
            requireArranged("the motorless hull must have no authority at all, or it is not the"
                    + " subject: " + direction + " " + d,
                    d.number("sustained") == 0.0 && d.number("burst") == 0.0);
        }
        command(control, "force-vel-by-id", 0, 0, 0, "hold the control where it was built");
        removePad(controlSite, "the control measured in free air");

        double controlPredictedSi = live(readout(control, "the control"), "SURGE_POSITIVE")
                .number("sustainedAccel");
        String controlDrive = drive(control, "force-vel-by-id", 0, 0, SURGE_COMMAND, DRIVE_DOSE_TICKS,
                "the control driven forward");
        requireArranged("the control must have worked at its authority for some steps, or its"
                + " acceleration there is not measured: " + controlDrive,
                Events.number(controlDrive, "saturatedSteps") > 0);
        // Over the SATURATED steps only, per physics second: how many steps the dose held depends on
        // the box, this ratio does not — whereas the speed reached after the dose would.
        assertEquals("the CONTROL — a craft with motors under the same command — must accelerate at"
                        + " its readout's forward figure, " + controlPredictedSi + " m/s², or nothing"
                        + " here says the command can move anything: " + controlDrive,
                1.0, Events.number(controlDrive, "satEngineAccelZ") / PhysicsUnits.ACCELERATION
                        / controlPredictedSi, ACCELERATION_BAND);

        // Only now: nothing holds this hull up, so from here it falls, and the drive below must
        // happen while it is still in free air.
        removePad(bareSite, "the motorless hull measured in free air");
        String bareDrive = drive(bare, "force-vel-by-id", 0, 0, SURGE_COMMAND, DRIVE_DOSE_TICKS,
                "the motorless hull driven forward");
        requireArranged("the motorless hull's controller must have been asked every step and have"
                + " reported every step that it delivered less than asked: " + bareDrive,
                Events.number(bareDrive, "saturatedSteps") == Events.number(bareDrive, "returnedSteps")
                        && Events.number(bareDrive, "returnedSteps") > 0);
        measured("no actuators", controlDrive, bareDrive);
        double seconds = Events.number(bareDrive, "engineSeconds");
        double gravity = bareModel.number("gravity") * PhysicsUnits.ACCELERATION;
        assertTrue("under a command to go forward and hold its altitude, the motorless hull must not"
                        + " move forward or sideways, and must fall exactly as gravity takes it ("
                        + gravity + " blocks per engine second² for " + seconds + " s) — nothing"
                        + " aboard can push it or hold it up: " + bareDrive,
                Math.abs(Events.number(bareDrive, "v1Z") - Events.number(bareDrive, "v0Z"))
                                <= NO_MOTION_BAND
                        && Math.abs(Events.number(bareDrive, "v1X") - Events.number(bareDrive, "v0X"))
                                <= NO_MOTION_BAND
                        && Math.abs(Events.number(bareDrive, "v1Y") - Events.number(bareDrive, "v0Y")
                                + gravity * seconds) <= gravity * seconds * ACCELERATION_BAND);
    }

    // ---- 4. a wheel turns, never pushes -----------------------------------------------------------

    /**
     * A hull whose only actuator is a reaction wheel turns when told to, and does not translate.
     *
     * <p>Told to turn about the vertical at a rate while holding altitude, the wheel-only hull must
     * reach that rate — the wheel's torque is its only way to — and its velocity must change by
     * gravity's pull and nothing else, because a wheel carries no force in any direction. The rate is
     * the positive half: it proves the command reached the hull and the hull answered, which is what
     * makes the absence of translation beside it mean something.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a reaction wheel turns a hull
     * and never pushes it.</p>
     *
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#onPhysicsTick} at {@code calc.addForceAndTorque(force, torque);} (the controller's wrench never handed to
     * the solver) fails "told to turn about the vertical at 2.0 rad per engine second, the wheel-only
     * hull must reach that rate" with no turn, 2026-09-30</p>
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#onPhysicsTick} at {@code force.set(command.force()).mul(k);} (the force taken as wanted acceleration
     * times mass, bypassing the hull's actuators) fails "turning, the wheel-only hull must not
     * translate", 2026-09-30</p>
     */
    @Test
    public void aWheelOnlyHullTurnsAndDoesNotTranslate() throws Exception {
        standInVacuum();
        FixtureSite site = site();
        AssembledCraft craft = assemble(site, "wheel-only-hull", "a hull whose only actuator is a wheel");
        Reply model = readout(craft, "the wheel-only hull");
        requireArranged("the wheel-only hull must have no force in any direction and torque about"
                + " the vertical, or it is not the subject: " + model,
                live(model, "SURGE_POSITIVE").number("burst") == 0.0
                        && live(model, "SWAY_POSITIVE").number("burst") == 0.0
                        && live(model, "HEAVE_POSITIVE").number("burst") == 0.0
                        && live(model, "YAW_POSITIVE").number("burst") > 0.0);
        command(craft, "force-rot-by-id", 0, 0, 0, "hold it still where it was built");
        removePad(site, "a hull measured in free air");

        String turn = drive(craft, "force-rot-by-id", 0, TURN_RATE, 0, DRIVE_DOSE_TICKS,
                "the wheel-only hull told to turn");
        measured("wheel only", turn);
        // Not a dose-dependent reading: the deadbeat asks for (TURN_RATE - w)/dt, about 120 rad per
        // engine second² at the solver's step, below this hull's wheel authority (139 rad/s² SI =
        // 453 in engine units, readout 2026-09-30) — so the rate is reached on the FIRST physics step,
        // unsaturated, and drive() refuses a window of fewer than two steps. Measured 2026-09-30:
        // w1Y exactly 2.0 on every run.
        assertEquals("told to turn about the vertical at " + TURN_RATE + " rad per engine second,"
                        + " the wheel-only hull must reach that rate: " + turn,
                TURN_RATE, Events.number(turn, "w1Y"), NO_MOTION_BAND);
        double seconds = Events.number(turn, "engineSeconds");
        double gravity = model.number("gravity") * PhysicsUnits.ACCELERATION;
        assertTrue("turning, the wheel-only hull must not translate: its velocity may change only"
                        + " by gravity's " + gravity + " blocks per engine second² over " + seconds
                        + " s. Window: " + turn,
                Math.abs(Events.number(turn, "v1X") - Events.number(turn, "v0X")) <= NO_MOTION_BAND
                        && Math.abs(Events.number(turn, "v1Z") - Events.number(turn, "v0Z"))
                                <= NO_MOTION_BAND
                        && Math.abs(Events.number(turn, "v1Y") - Events.number(turn, "v0Y")
                                + gravity * seconds) <= gravity * seconds * ACCELERATION_BAND);
    }

    /**
     * The commanded turn, in radians per engine second: a quarter-turn and more within the window,
     * and a small fraction of what one wheel's stored momentum can give this hull (measured
     * 2026-09-30: 139 rad/s² of burst about the vertical, for 0.89 s).
     */
    private static final double TURN_RATE = 2.0;

    // ---- 5. a craft that cannot hold its weight sinks ---------------------------------------------

    /**
     * A craft whose heave authority is below its weight in the local field sinks under a hover
     * command — at the rate its readout predicts — while a craft whose heave exceeds its weight, in
     * the same field under the same command, holds.
     *
     * <p>The field is raised under two hovering craft to three standard gravities. The seat craft's
     * heave (4.9 MN) then falls short of its 5.2 MN weight and its readout says it cannot hover; the
     * decked craft's (9.7 MN sustained) still clears its 8.3 MN weight — both read as the premise. The
     * prediction for the one that sinks is computed from its readout before it is measured: the
     * acceleration its heave can give it (the burst figure, which is what the scheme reaches for once
     * the demand exceeds the sustained one) minus the field. The one that holds is the CONTROL: the
     * same command in the same field, answered.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a hull delivers no more than its
     * authority, and that a hull with enough of it hovers.</p>
     *
     * <p>red-witnessed: {@code ShipInertiaWriter#applyTo} at {@code record.setGameTickMass(frame.getTotalMass());} (the physics mass written 10% heavier than the
     * frame the readout is solved for) fails "told to hover with less heave than weight, the weak craft
     * must sink at what its readout leaves it (-1.6002 m/s²)" with -4.130 m/s², 2026-09-30</p>
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#onPhysicsTick} at {@code gx = g.x(); gy = g.y(); gz = g.z();} (the gravity feed-forward zeroed) fails
     * "the CONTROL — a craft whose heave exceeds its weight — must hold its altitude" with a vertical
     * velocity of -1.6 blocks per engine second, 2026-09-30</p>
     */
    @Test
    public void aCraftWhoseHeaveIsBelowItsWeightSinksUnderAHoverCommand() throws Exception {
        standInVacuum();
        FixtureSite weakSite = plot().siteAt(4, 4);
        FixtureSite strongSite = plot().siteAt(36, 4);
        AssembledCraft weak = assemble(weakSite, "with-pilot-seat", "a craft that will be too weak to hover");
        AssembledCraft strong =assemble(strongSite, "with-pilot-deck", "the control: a craft that will hover");
        command(weak, "force-vel-by-id", 0, 0, 0, "hover the weak craft");
        command(strong, "force-vel-by-id", 0, 0, 0, "hover the strong craft");
        removePad(weakSite, "the weak craft measured in free air");
        removePad(strongSite, "the strong craft measured in free air");

        exec("ar planet set " + DIM + " gravitationalMultiplier " + RAISED_FIELD);
        Reply planet = Reply.of(exec("stellurgytest planet info " + DIM));
        requireArranged("the field must be raised to " + RAISED_FIELD + " standard gravities: "
                + planet, planet.number("gravity") == RAISED_FIELD);
        Reply weakModel = readout(weak, "the weak craft in the raised field");
        Reply strongModel = readout(strong, "the strong craft in the raised field");
        requireArranged("in the raised field the weak craft's readout must say it cannot hover and the"
                + " strong craft's that it can: " + weakModel + " | " + strongModel,
                !weakModel.bool("canHoverLIVE") && strongModel.bool("canHoverLIVE"));
        double predictedSi = live(weakModel, "HEAVE_POSITIVE").number("burstAccel")
                - weakModel.number("gravity");

        String strongHover = drive(strong, "force-vel-by-id", 0, 0, 0, DRIVE_DOSE_TICKS,
                "the strong craft told to hover");
        String weakHover = drive(weak, "force-vel-by-id", 0, 0, 0, DRIVE_DOSE_TICKS,
                "the weak craft told to hover");
        requireArranged("the weak craft must have been at its authority while told to hover: "
                + weakHover, Events.number(weakHover, "satEngineSeconds") > 0.0);
        double measuredSi = Events.number(weakHover, "satEngineAccelY") / PhysicsUnits.ACCELERATION;
        measured("hover in a raised field", "predictedSi=" + predictedSi, "measuredSi=" + measuredSi,
                strongHover, weakHover);
        assertEquals("told to hover with less heave than weight, the weak craft must sink at what its"
                        + " readout leaves it (" + predictedSi + " m/s²); it accelerated at "
                        + measuredSi + " m/s². Window: " + weakHover,
                1.0, measuredSi / predictedSi, ACCELERATION_BAND);
        // Not a dose-dependent reading: a craft whose heave exceeds its weight cancels the field
        // within one physics step (the deadbeat's demand is inside its authority, TWR > 1 read above),
        // and drive() refuses a window of fewer than two steps — so the last step's vertical speed is
        // the held state however many steps the dose contained. Measured 2026-09-30: ~2e-15.
        assertEquals("the CONTROL — a craft whose heave exceeds its weight — must hold its altitude"
                        + " under the same command in the same field: " + strongHover,
                0.0, Events.number(strongHover, "v1Y"), NO_MOTION_BAND);
    }

    /**
     * The raised field, in standard gravities: the smallest whole multiple at which the seat craft's
     * heave (4.905 MN, measured 2026-09-30) falls below its weight (176 250 kg × 3 × 9.81 m/s² =
     * 5.19 MN) while the decked craft's sustained heave (9.72 MN) still clears its own (8.28 MN).
     */
    private static final double RAISED_FIELD = 3.0;

    // ---- 6. a removed motor lowers live authority -------------------------------------------------

    /**
     * Removing a working motor from an assembled ship lowers its live authority, without
     * re-assembly: the flight computer re-surveys the hull it is standing on and its readout drops.
     *
     * <p>The motor is chosen from the ship's own actuator survey — a working one that pushes
     * forward — and removed from the ship's yard in place. The verdict is a LINK on what the craft
     * publishes: a {@code flight_model_changed} for this craft whose live forward authority
     * ({@code liveSurgeN}, the console's figure) is below the one read before. No rebuild is asked for
     * by name — a model rebuilt on the round before the removal reaches the hull is not an answer, and
     * a production that lowers the figure some other way still passes. The same physics id answers
     * throughout: the ship was never re-built.</p>
     *
     * <p>Contract: this fails if production breaks the contract that the live readout follows the
     * hull as it changes.</p>
     *
     * <p>red-witnessed: 2026-10-05, on the form whose wait IS the verdict. With
     * {@code TileAdvancedFlightComputer#tickFlightModel} at {@code boolean due = flightModel == null}
     * (a model rebuilt only when the computer has none), it fails "with one forward motor gone, the
     * craft's live forward authority must drop below the 4905000.0 N it had, without re-assembly". With
     * {@code TileAdvancedFlightComputer#rebuildFlightModel} at
     * {@code survey.mass(), survey.design(), survey.live(), HELM_FRAME);} solving every rebuild over the
     * FIRST survey's live actuators instead of the new survey's, the same verdict fails. Earlier records
     * (2026-09-30) were taken on a form that waited for the next rebuild and then asserted.</p>
     */
    @Test
    public void removingAWorkingMotorLowersLiveAuthorityWithoutReassembly() throws Exception {
        standInVacuum();
        FixtureSite site = site();
        AssembledCraft craft = assemble(site, "with-pilot-deck", "a decked craft losing a motor");
        command(craft, "force-vel-by-id", 0, 0, 0, "hold it where it was built");

        Reply before = readout(craft, "before the motor is removed");
        long revision = before.longInteger("revision");
        double massBefore = Reply.of(before.object("mass")).number("totalKg");
        double forceBefore = live(before, "SURGE_POSITIVE").number("sustained");
        Reply survey = Reply.of(exec("stellurgytest vs actuators " + DIM + " " + craft.physicsId));
        requireArranged("the craft must be surveyable: " + survey, survey.bool("survey"));
        Reply motor = null;
        for (String one : survey.objectArray("actuators")) {
            Reply a = Reply.of(one);
            if (a.has("sustained") && a.has("working") && a.has("forceZ")
                    && a.bool("sustained") && a.bool("working") && a.number("forceZ") > 0.0) {
                motor = a;
                break;
            }
        }
        requireArranged("the craft must carry a working motor that pushes it forward: " + survey,
                motor != null && forceBefore > 0.0);

        long mark = events.mark();
        Reply removed = Reply.of(exec("stellurgytest fill " + DIM + " " + motor.integer("x") + " "
                + motor.integer("y") + " " + motor.integer("z") + " " + motor.integer("x") + " "
                + motor.integer("y") + " " + motor.integer("z") + " minecraft:air"));
        requireArranged("the motor must come out of the ship's yard: " + removed,
                removed.ok() && removed.integer("placed") == 1);
        // THE WAIT IS THE VERDICT: the readout this craft publishes — what its console shows — must
        // come to read less forward authority than before. Not "the model was rebuilt": the computer
        // also rebuilds on a fixed round whatever the hull did, so a link on the next rebuild took one
        // of the untouched hull at six forks on 2026-10-04 (revision 2, still 4 905 000 N).
        String dropped = events.awaitMatching(mark, "flight_model_changed",
                reply -> anyLiveSurgeBelow(reply, craft.durable, forceBefore),
                "for this craft, publishing a live forward authority below " + forceBefore + " N",
                "with one forward motor gone, the craft's live forward authority must drop below the "
                        + forceBefore + " N it had, without re-assembly. Removed: " + motor,
                LINK_BUDGET_TICKS);
        measured("motor removed", "forceBefore=" + forceBefore, "revisionBefore=" + revision,
                "massBefore=" + massBefore, motor, dropped);
    }

    /**
     * Print a scenario's readings on a green run too, so the numbers a band was set from can be read
     * off any run and not only off a red one.
     */
    private static void measured(String what, Object... readings) {
        StringBuilder line = new StringBuilder("[measured] ").append(what);
        for (Object r : readings) {
            line.append(" | ").append(r);
        }
        System.out.println(line);
    }

    /**
     * Whether a {@code flight_model_changed} for this craft published a live sustained forward
     * authority below {@code newtons} — by more than the record's own rendering, six significant
     * figures, so by more than a hundred-thousandth of the figure (a removed motor here is half of it).
     */
    private static boolean anyLiveSurgeBelow(String reply, String durable, double newtons) {
        for (String record : Events.recordsWhere(reply, "ship", durable)) {
            if (newtons - Events.number(record, "liveSurgeN") > newtons * 1e-5) {
                return true;
            }
        }
        return false;
    }

    // ---- 6b. an idle craft gives its wheel back ---------------------------------------------------

    /**
     * How long the window between the two wheel reads is, in server ticks. Any decrease is the
     * verdict, so the window only has to hold at least one physics step; a slower box gives fewer
     * steps and a smaller drop, never a different sign.
     */
    private static final int UNLOAD_WINDOW_TICKS = 20;

    /**
     * The yaw momentum the wheel is built holding, N·m·s, about the ship's vertical: a round number
     * well inside the fixture wheel's capacity — measured 2026-10-05 as 0.0588 of it, so the capacity
     * is 1.7 MN·m·s.
     */
    private static final double WOUND_YAW_MOMENTUM = 100_000.0;

    /**
     * A craft whose reaction wheel holds momentum, left holding station with nothing else asked of
     * it, gives that momentum back: its thrusters unload the wheel. The wheel is built already wound —
     * its stored momentum written into the block before assembly, which is how a saved world brings
     * one back — and holding station asks the wheel for nothing, so the only thing in the window that
     * can empty it is what the flight computer's allocation does with a wheel the command leaves
     * idle.
     *
     * <p>A WINDOW of two reads of the computer's own wheel figure, and the verdict names both: the
     * fullest wheel aboard holds less at the second read than at the first.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a craft at rest gives its wheels
     * back their momentum.</p>
     *
     * <p>red-witnessed: 2026-10-05, after a healthy run (0.0588 → 0.0) — with
     * {@code CleanAxisScheme#allocate} at {@code desaturate(capability, u, momentum, dt);} removed it
     * fails "an idle craft must give its wheel back: the fullest wheel aboard read 0.058823529411764705
     * of its capacity, and 20 ticks later 0.058823529411764705".</p>
     */
    @Test
    public void anIdleCraftGivesItsWheelBack() throws Exception {
        standInVacuum();
        FixtureSite site = site();
        Reply fixture = lay(site, "with-pilot-deck", "a decked craft with a wound wheel");
        int[] wheel = fixture.intArray("wheelPos");
        requireArranged("the fixture must say where its wheel stands: " + fixture,
                wheel != null && wheel.length == 3);
        String wound = exec("blockdata " + wheel[0] + " " + wheel[1] + " " + wheel[2]
                + " {storedMomentumY:" + WOUND_YAW_MOMENTUM + "d}");
        AssembledCraft craft = assembleLaid(site, fixture, "a decked craft with a wound wheel");
        command(craft, "force-vel-by-id", 0, 0, 0, "hold it where it was built");
        removePad(site, "a craft left in free air");

        Reply model = readout(craft, "the craft built with a wound wheel");
        Reply yaw = Reply.of(Reply.of(model.object("directions")).object("YAW_POSITIVE"));
        double sustainedYaw = Reply.of(yaw.object("live")).number("sustained");
        requireArranged("the craft must be able to turn about yaw with its thrusters alone, or nothing "
                + "aboard can cancel an unwinding wheel: " + yaw, sustainedYaw > 0.0);

        // WINDOW: the fill is a value that converges; two reads, and the verdict names both.
        double first = wheelFill(craft);
        requireArranged("the wheel must hold momentum once the craft is built (blockdata: " + wound
                + ")", first > 0.0);
        GameTicks.advance(client(), GameTicks.server(), UNLOAD_WINDOW_TICKS);
        double second = wheelFill(craft);
        measured("idle wheel", "first=" + first, "second=" + second, "sustainedYaw=" + sustainedYaw);
        assertTrue("an idle craft must give its wheel back: the fullest wheel aboard read " + first
                        + " of its capacity, and " + UNLOAD_WINDOW_TICKS + " ticks later " + second,
                second < first);
    }

    // ---- 6c. the drive weighs the hull ----------------------------------------------------------

    /**
     * How tall the jump-drive craft stands, in blocks of headroom to clear: its generator rises
     * above the hull the other fixtures stop at, and the milestone that builds the same craft clears
     * twenty.
     */
    private static final int JUMP_CRAFT_HEADROOM = 20;

    /**
     * The jump drive is forecast to carry a lighter hull faster, by exactly the ratio of the two
     * masses: a motor is taken off the milestone's own jump craft, the drive is untouched, and the
     * speed the pilot is shown rises as the hull's mass falls.
     *
     * <p>The masses are read from the flight model's readout, which weighs the hull by its own survey
     * and not through the drive — so a drive that ignored the hull, or weighed every craft the same,
     * would show the same speed at two different masses and fail here. The link is on the model
     * rebuilt LIGHTER, which is when the readout's mass is the hull without the motor.</p>
     *
     * <p>The tolerance is the two rounding steps between the quantities: a speed is a whole number of
     * blocks per tick, and a recorded mass carries six significant figures.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a jump's speed is set by the mass
     * of the hull it carries.</p>
     *
     * <p>red-witnessed: 2026-10-05, after a healthy run (321 250 → 311 250 kg, 1556 → 1606
     * blocks/tick) — with {@code ShipMassProvider#massOf} at
     * {@code return OptionalLong.of(Math.max(1L, Math.round(frame.getTotalMass())));} answering
     * {@code DriveTuning.BASELINE_SHIP_MASS} for every hull, as the placeholder did, it fails "a lighter
     * hull must be forecast faster by its mass ratio: 321250.0 kg at 125000 blocks/tick, 311250.0 kg
     * at 125000 expected:&lt;1.0321285140562249&gt; but was:&lt;1.0&gt;".</p>
     */
    @Test
    public void theDriveForecastsALighterHullFasterByItsMassRatio() throws Exception {
        FixtureSite site = site();
        Reply fixture = layWithHeadroom(site, "with-jump-drive", JUMP_CRAFT_HEADROOM,
                "the milestone's jump craft");
        AssembledCraft craft = assembleLaid(site, fixture, "the milestone's jump craft");
        command(craft, "force-vel-by-id", 0, 0, 0, "hold it where it was built");
        String where = DIM + " " + craft.afcX + " " + craft.afcY + " " + craft.afcZ;

        DriveInfo before = DriveInfo.at(this::exec, where);
        requireArranged("the craft must carry a drive that forecasts a speed: " + before,
                before.drivePower > 0L && before.speedBlocksPerTick > 0L);
        Reply model = readout(craft, "the jump craft with every motor");
        double massBefore = Reply.of(model.object("mass")).number("totalKg");
        Reply survey = Reply.of(exec("stellurgytest vs actuators " + DIM + " " + craft.physicsId));
        Reply motor = null;
        for (String one : survey.objectArray("actuators")) {
            Reply a = Reply.of(one);
            // the producer always writes `sustained` and `working` on every actuator it lists.
            if (a.bool("sustained") && a.bool("working")) {
                motor = a;
                break;
            }
        }
        requireArranged("the craft must carry a working motor to take off: " + survey, motor != null);

        long mark = events.mark();
        Reply removed = Reply.of(exec("stellurgytest fill " + DIM + " " + motor.integer("x") + " "
                + motor.integer("y") + " " + motor.integer("z") + " " + motor.integer("x") + " "
                + motor.integer("y") + " " + motor.integer("z") + " minecraft:air"));
        requireArranged("the motor must come out of the ship's yard: " + removed,
                removed.ok() && removed.integer("placed") == 1);
        String lighter = events.awaitMatching(mark, "flight_model_changed",
                reply -> anyLighter(reply, craft.durable, massBefore),
                "for this craft, solved for less than the " + massBefore + " kg it weighed",
                "the craft's model must be rebuilt for the hull without the motor",
                LINK_BUDGET_TICKS);
        double massAfter = Double.NaN;
        for (String record : Events.recordsWhere(lighter, "ship", craft.durable)) {
            double kg = Events.number(record, "totalKg");
            if (massBefore - kg > massBefore * 1e-5) {
                massAfter = kg; // the last lighter model is the one the drive is read against
            }
        }
        DriveInfo after = DriveInfo.at(this::exec, where);
        requireArranged("taking off a motor must leave the drive as it was: " + before + " / " + after,
                after.drivePower == before.drivePower);

        measured("drive and hull", "massBefore=" + massBefore, "massAfter=" + massAfter,
                "speedBefore=" + before.speedBlocksPerTick, "speedAfter=" + after.speedBlocksPerTick,
                "drivePower=" + before.drivePower);
        double speedRatio = after.speedBlocksPerTick / (double) before.speedBlocksPerTick;
        double massRatio = massBefore / massAfter;
        double tolerance = 2.0e-5 + 2.0 / before.speedBlocksPerTick;
        assertEquals("a lighter hull must be forecast faster by its mass ratio: " + massBefore + " kg at "
                        + before.speedBlocksPerTick + " blocks/tick, " + massAfter + " kg at "
                        + after.speedBlocksPerTick, massRatio, speedRatio, tolerance * massRatio);
    }

    /**
     * Whether a {@code flight_model_changed} for this craft was solved for less than {@code kg} — by
     * more than the record's six significant figures can blur.
     */
    private static boolean anyLighter(String reply, String durable, double kg) {
        for (String record : Events.recordsWhere(reply, "ship", durable)) {
            if (kg - Events.number(record, "totalKg") > kg * 1e-5) {
                return true;
            }
        }
        return false;
    }

    /** How full the fullest wheel aboard is, 0 to 1, as the craft's flight computer reports it. */
    private double wheelFill(AssembledCraft craft) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs flight-model-by-id " + DIM + " " + craft.physicsId));
        requireArranged("the craft's flight computer must answer for its wheels: " + r,
                r.bool("found") && r.has("wheelFill"));
        return r.number("wheelFill");
    }

    // ---- 7. the assembler's thrust-to-weight gate ---------------------------------------------------

    /**
     * The assembler builds a craft that can hover on the first press; one that cannot hold its
     * weight in the local field is not built on the first press, and IS built by a second press on
     * the same build.
     *
     * <p>Read off the press itself: the probe answers whether a press cut the craft out of its pad
     * ({@code shipCut}) beside the scan's own verdict on it ({@code scanReadout.canHoverLIVE}). The
     * hover-capable craft is the CONTROL — without it a gate that refused everything would pass the
     * first half. The build the second press made is then named like any craft, which is the record
     * that it became one.</p>
     *
     * <p>What this does NOT see: the warning itself. It is a chat line to whoever pressed, and on
     * this tier nobody did; the gate is observable here only as the press that did not build.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a ship under unit
     * thrust-to-weight is built only on confirmation.</p>
     *
     * <p>red-witnessed: {@code TileRocketAssemblingMachine#assembleRocket} at {@code if (tier2Readout != null && !tier2Readout.canHover(} (the gate's condition inverted, so a
     * craft that CAN hover is the one warned about) fails "a craft that can hover must be built on the
     * FIRST press" with shipCut false, 2026-09-30</p>
     * <p>red-witnessed: {@code TileRocketAssemblingMachine#assembleRocket} at {@code if (tier2Readout != null && !tier2Readout.canHover(} (the gate disabled) fails "a ship that
     * cannot hold its weight here must NOT be built on the first press" with shipCut true,
     * 2026-09-30</p>
     * <p>red-witnessed: {@code TileRocketAssemblingMachine#assembleRocket} at {@code warnedThrustToWeight = twr;} (the warned thrust-to-weight never
     * remembered, so every press warns afresh) fails "a second press on the SAME build must build it"
     * with shipCut false, 2026-09-30</p>
     * <p>red-witnessed: {@code TileRocketAssemblingMachine#assembleRocket} at {@code VSIntegration.assembleTier2Ship(world, shipStructure,} (the cut build never handed to the
     * physics mod) fails "the build the second press made must become a named ship" with no
     * ship_lifecycle named inside 200 ticks, 2026-09-30</p>
     */
    @Test
    public void theAssemblerAsksTwiceBeforeBuildingAShipThatCannotHover() throws Exception {
        standInVacuum();
        FixtureSite hoverSite = plot().siteAt(4, 4);
        FixtureSite heavySite = plot().siteAt(36, 4);
        Reply hoverFixture = lay(hoverSite, "with-pilot-seat", "the control: a craft that can hover");
        Reply hoverPress = Reply.of(RocketFixture.assembleBuilt(hoverSite, this::exec,
                hoverFixture.blockPos("builderPos")));
        requireArranged("the control's scan must say it can hover: " + hoverPress,
                hoverPress.ok() && Reply.of(hoverPress.object("scanReadout")).bool("canHoverLIVE"));
        assertTrue("a craft that can hover must be built on the FIRST press: " + hoverPress,
                hoverPress.bool("shipCut"));

        Reply heavyFixture = lay(heavySite, "hull-without-actuators", "a hull that cannot hover");
        int[] builder = heavyFixture.blockPos("builderPos");
        Reply first = Reply.of(RocketFixture.assembleBuilt(heavySite, this::exec, builder));
        requireArranged("the heavy build's scan must say it cannot hover: " + first,
                first.ok() && !Reply.of(first.object("scanReadout")).bool("canHoverLIVE"));
        assertTrue("a ship that cannot hold its weight here must NOT be built on the first press: "
                + first, !first.bool("shipCut"));

        long mark = events.mark();
        Reply second = Reply.of(RocketFixture.assembleBuilt(heavySite, this::exec, builder));
        measured("assembler presses", hoverPress, first, second);
        assertTrue("a second press on the SAME build must build it: " + second,
                second.ok() && second.bool("shipCut"));
        events.awaitRecordWithFields(mark, "ship_lifecycle",
                "the build the second press made must become a named ship", LINK_BUDGET_TICKS,
                "durable", second.text("shipId"), "edge", "named");
    }

    /** How many iron blocks are stowed as cargo; see the scenario for why this many. */
    private static final int CARGO_BLOCKS = 12;

    /**
     * Whether a {@code flight_model_changed} for this craft was solved for more than {@code kg}.
     * "More" is by over a kilogram: the record renders its mass to six significant figures, and a
     * kilogram is a thousandth of the lightest block aboard.
     */
    private static boolean anyHeavier(String reply, String durable, double kg) {
        for (String record : Events.recordsWhere(reply, "ship", durable)) {
            if (Events.number(record, "totalKg") - kg > 1.0) {
                return true;
            }
        }
        return false;
    }

    // ================================================================================================
    // 2. MASS
    // ================================================================================================

    /**
     * 25 iron deck blocks at the table's default for the IRON material, 5000 kg a block (the
     * {@code WeightEngine} material table). Everything else on the fixture only adds.
     */
    private static final double IRON_DECK_KG = 25 * 5000.0;

    /**
     * How long a round may take to come, in server ticks: TWO of production's periods. A ship's round
     * falls on a phase of its own inside every {@link TileAdvancedFlightComputer#MASS_ROUND_TICKS},
     * so one period always contains one; the second is the slack for a mark that lands just after a
     * round and for the probe round trips between reads. Its expiry means no round ran.
     */
    private static final int ROUND_BUDGET_TICKS = 2 * TileAdvancedFlightComputer.MASS_ROUND_TICKS;

    /**
     * A full pass over a ship's hull agrees with the running total the engine kept while building it.
     *
     * <p>A ship's mass is maintained two ways, and only one of them is cheap. The working path is
     * incremental — a delta applied as each block arrives or leaves — and it is correct exactly as long
     * as nothing is ever missed. The authority is a full walk over the hull, run at the few moments where
     * the incremental path cannot be trusted to have kept up: an assembly, a paste, a load from disk.
     * This asserts they agree on a craft that was just assembled — the one case where they really
     * ought to, because the deltas were fed the whole hull moments earlier. A disagreement here is not a
     * rounding complaint: it means the two halves of the mass model are pricing the same ship
     * differently, and every derived number downstream (thrust-to-weight, turn rate, fuel per manoeuvre)
     * inherits whichever one it happened to read.</p>
     *
     * <p>How it sees: the comparison's own verdict, as a record. {@code ship_mass_compared} is written
     * where production decides whether the two totals agree, and it carries that decision
     * ({@code agrees}, beside the production description of any drift). So the test waits for the
     * comparison of THIS craft and reads its answer — there is no silence to interpret. The drift is
     * never thrown by production — it runs inside the world tick, and a dead server would report that
     * the process exited rather than that a hull was 4% light — so the number arrives here in the
     * record and the failure can carry it.</p>
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 (the {@code Drift} form — with
     * {@code ShipMassTrigger#recompute} at {@code if (event.cause != ShipLifecycleEvent.Cause.LOADED)} comparing only on a LOAD, the wait for this craft's comparison fails —
     * "the assembly never compared the full hull pass against the running total"; with
     * {@code ShipInertiaWriter#compare} at {@code if (Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE)}'s agreement test inverted, the verdict fails on {@code agrees}
     * ("disagree").</p>
     */
    @Test
    public void theAuthoritativeRecomputeAgreesWithTheIncrementalTotalOnAFreshAssembly()
            throws Exception {

        // Marked before the assemble: the comparison is made on the tick the craft is first named,
        // and a mark taken after the assembly could land behind it.
        long mark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(site(), this::exec,
                "with-pilot-seat", 4, 12, "the craft whose hull is weighed stands in this volume");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: "
                + asm, Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                DIM, ShipIdentity.nameFromAssembly(asm), LINK_BUDGET_TICKS));

        String compared = events.awaitRecordWithFields(mark, "ship_mass_compared",
                "the assembly never compared the full hull pass against the running total, so nothing"
                        + " measures the incremental path at all", LINK_BUDGET_TICKS, "ship", shipId);

        assertTrue("the full hull pass and the running total the engine kept while assembling this"
                        + " craft disagree. They price the same blocks from the same table, so a"
                        + " difference here means one of the two write paths is not seeing part of the"
                        + " ship - and every derived figure downstream inherits whichever it read: "
                        + compared,
                Reply.of("ship_mass_compared", compared).bool("agrees"));
    }

    /**
     * The mass the physics record carries is Stellurgy's block table, not the physics engine's own
     * flat per-block default.
     *
     * <p>The witness the whole server suite could not provide for a long time: it stayed green across
     * the change that replaced how EVERY block's mass is decided, because nothing in it ever asked a
     * ship what it weighed.</p>
     *
     * <p>The bound is one-sided on purpose: the decked fixture carries a 5x5 iron deck, which the
     * table denominates at 5000 kg a block, and the engine's default cannot reach that figure with the
     * whole fixture. So this separates the two models without pinning the table's exact numbers,
     * which are balance and may be tuned.</p>
     *
     * <p>red-witnessed: with {@code StellurgyBlockMass.of} ({@code StellurgyBlockMass#of} at {@code return Stellurgy.weights().getWeight(asItem);}, read by both the hull pass
     * and the per-block path) answering 1.0 for every block, the verdict fails — "recorded mass is
     * 35.0 kg, below the 125000.0 kg of iron deck" — 2026-09-29, taken on the pre-merge form of that
     * line, which read the table through the engine's former singleton.</p>
     */
    @Test
    public void anAssembledShipWeighsWhatTheBlockTableSays() throws Exception {
        long mark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(site(), this::exec,
                "with-pilot-deck", 4, 12, "the decked craft that is weighed stands in this volume");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: "
                + asm, Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                DIM, ShipIdentity.nameFromAssembly(asm), LINK_BUDGET_TICKS));
        // The authoritative frame is written right after this record, on the same tick: the read
        // below is of the mass that settles the question, not of whatever the paste had accumulated.
        ArrangementFailure.arranged(() -> events.awaitRecordWithFields(mark, "ship_mass_measured",
                "the assembly's own measurement must be on the record before its mass is read",
                LINK_BUDGET_TICKS, "ship", shipId, "path", "event"));

        String info = exec("stellurgytest vs ship-info " + DIM + " id " + shipId);
        double massKg = Reply.of("stellurgytest vs ship-info", info).number("massKg");
        assertTrue("this ship's recorded mass is " + massKg + " kg, below the " + IRON_DECK_KG
                + " kg of iron deck it carries. A mass that low is the physics engine's flat per-block"
                + " default, which means the block table Stellurgy owns is not the one deciding ship"
                + " mass: " + info, massKg >= IRON_DECK_KG);
    }

    /**
     * A ship is re-weighed on a CADENCE, with no event to trigger it.
     *
     * <p>Why a cadence has to exist at all: the authoritative recompute used to run only where the
     * engine announced something — a craft assembled, pasted, or loaded from disk. That covers every
     * moment a hull's STRUCTURE changes wholesale, and nothing else, because the other two halves of a
     * ship's mass change with no block ever changing. A tank empties over a burn. A crate is filled.
     * Somebody steps aboard carrying a stack of ore. There is no block event under any of it, so a mass
     * model that only listens to events reports what the craft weighed when it was built.</p>
     *
     * <p>It pins the mechanism: once a craft exists and its assembly's own measurement is on the
     * record, a further measurement of THAT craft runs on the round path, with nothing at all happening
     * to the ship. The record names its path, so the assembly's measurement can never be mistaken for
     * the round's. The consequence — that cargo makes its ship heavier — is
     * {@link #theSameShipCarryingCargoAcceleratesLessByItsMassRatio}'s.</p>
     *
     * <p>red-witnessed: with {@code TileAdvancedFlightComputer.tickMassRound} returning before its
     * {@code backgroundRound} call ({@code TileAdvancedFlightComputer#tickMassRound} at {@code dev.stannismod.stellurgy.integration.vs.ShipMassTrigger.backgroundRound(world, uuid);}), the round wait fails, "no record
     * carrying ship = … and path = round was recorded within 200 ticks", 2026-09-29.</p>
     */
    @Test
    public void aSettledShipIsMeasuredAgainWithNothingHappeningToIt() throws Exception {

        long assemblyMark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(site(), this::exec,
                "with-pilot-seat", 4, 12, "the craft that is re-weighed stands in this volume");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: "
                + asm, Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                DIM, ShipIdentity.nameFromAssembly(asm), LINK_BUDGET_TICKS));

        // The assembly's own measurement first, on the record. Marking the round window only after it
        // is what keeps the assembly's recompute out of the round's count — without this the test
        // would pass on a build with no cadence at all.
        ArrangementFailure.arranged(() -> events.awaitRecordWithFields(assemblyMark, "ship_mass_measured",
                "the craft's assembly must be measured before a round can be told apart from it",
                LINK_BUDGET_TICKS, "ship", shipId, "path", "event"));

        long roundMark = events.markInstrumented();
        events.awaitRecordWithFields(roundMark, "ship_mass_measured",
                "a settled ship must be re-measured on its own cadence, with no event and nothing"
                        + " happening to it, or content and crew can never reach its mass - a tank"
                        + " empties and a crew member boards without a single block changing, so no"
                        + " block event exists to catch either",
                ROUND_BUDGET_TICKS, "ship", shipId, "path", "round");
    }

    /**
     * How much water is poured into the deck tank, in millibuckets: one bucket, which any tank holds.
     * What it weighs is the table's business, not this test's — the verdict is only that the craft
     * grew heavier by more than the record's own rounding.
     */
    private static final int POURED_MB = 1000;

    /**
     * A filled tank outweighs an empty one: water poured into a tank aboard is weighed into the craft,
     * with no block changing.
     *
     * <p>A machine's contents are priced in two halves, the items it holds and the fluid it holds, and
     * {@link #theSameShipCarryingCargoAcceleratesLessByItsMassRatio} weighs the first. This is the
     * second. Pouring changes no block, so nothing about the hull tells the flight computer; the model
     * can learn it only from its own load round, which re-weighs what the craft carries.</p>
     *
     * <p>The verdict is a LINK on what the craft publishes: a flight model of this craft solved for
     * more than the craft weighed with its tank empty.</p>
     *
     * <p>Contract: this fails if production breaks the contract that the fluid a craft carries is part
     * of its mass.</p>
     *
     * <p>red-witnessed: 2026-10-05, after a healthy run (306 250 => 311 250 kg for 1000 mB) — with
     * {@code WeightEngine#heldWeight} at {@code weight += getWeight(info.getContents());} adding nothing
     * for a tank's contents, it fails "the flight computer must weigh the water poured into the craft's
     * tank — the readout's mass must GROW by it — no `flight_model_changed` for this craft, solved for
     * more than the 306250.0 kg it weighed with its tank empty was recorded within 200 ticks".</p>
     */
    @Test
    public void aFilledTankOutweighsAnEmptyOne() throws Exception {
        FixtureSite site = site();
        Reply fixture = lay(site, "with-pilot-deck-and-tank", "a decked craft with a tank");
        // WHERE the tank stands aboard is the fixture's to say, as an offset from the flight computer.
        int[] tank = fixture.intArray("tankFromFlightComputer");
        requireArranged("the fixture must say where its tank stands: " + fixture,
                tank != null && tank.length == 3);
        AssembledCraft craft = assembleLaid(site, fixture, "a decked craft with a tank");

        Reply empty = readout(craft, "the craft with its tank empty");
        double emptyKg = Reply.of(empty.object("mass")).number("totalKg");

        long pourMark = events.mark();
        Reply pour = Reply.of(exec("stellurgytest fluid inject " + DIM + " " + (craft.afcX + tank[0])
                + " " + (craft.afcY + tank[1]) + " " + (craft.afcZ + tank[2]) + " water " + POURED_MB));
        requireArranged("the water must go into the tank: " + pour,
                pour.ok() && pour.integer("filled") == POURED_MB);
        String full = events.awaitMatching(pourMark, "flight_model_changed",
                reply -> anyHeavier(reply, craft.durable, emptyKg),
                "for this craft, solved for more than the " + emptyKg + " kg it weighed with its tank"
                        + " empty",
                "the flight computer must weigh the water poured into the craft's tank — the readout's"
                        + " mass must GROW by it", LINK_BUDGET_TICKS);
        measured("tank", "emptyKg=" + emptyKg, "pour=" + pour, full);
    }

    /**
     * How far from the middle of the seat's own block a point may lie and still be in the seat's
     * column, in blocks: half a block, the cell's own half-width.
     */
    private static final double SEAT_COLUMN_HALF_WIDTH = 0.5;

    /**
     * How far above the floor of the seat's own block the occupant's mass may sit, in blocks: the
     * height of that one cell. Measured 2026-10-05: 0.30 above it, and 1.5e-6 / 5e-9 off the middle
     * of the cell horizontally.
     */
    private static final double SEATED_HEIGHT = 1.0;

    /**
     * How closely the physics record's growth must match the crew mass the craft's own readout
     * reports, in kilograms: floating-point room only. Measured 2026-10-05: 80.0 against 80.0 exactly,
     * on a 281 250 kg craft.
     */
    private static final double CREW_MASS_BAND = 1.0e-6;

    /**
     * A body seated aboard is weighed WHERE it sits: the craft's physics record grows by the occupant's
     * mass, and its centre of mass moves toward the seat by exactly what that mass at the seat makes
     * it.
     *
     * <p>Two things are decided here, and both are the mass model's. Who is crew: someone seated on
     * the ship, or held by its deck — a body that is merely inside the hull is not weighed. And where:
     * an entity's position is a WORLD position, while the hull's mass is measured in the ship's own
     * subspace frame, so the occupant must be carried from one to the other before its mass is placed.
     * A body placed at its world position would pull the centre of mass toward a point the hull is
     * nowhere near.</p>
     *
     * <p>How it sees: the occupant is an armor stand seated on the pilot seat — no AI, so nothing it
     * does on its own can move it out of the seat. Seating changes no block, so the craft learns it on
     * its load round, and the verdict links on the model of this craft solved heavier. The physics
     * record — the mass and centre the engine integrates — is then read from the round that wrote it,
     * linked on the round's own record for this craft. From the record before and after, the point the
     * added mass sits at is solved for directly, {@code p = c1 + M0 (c1 - c0) / m}; and the seat's
     * subspace block is known independently of the conversion under test, as the fixture's offset from
     * the flight computer.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a seated occupant's mass is part
     * of the craft and sits where the occupant sits aboard.</p>
     *
     * <p>Witnessed 2026-10-05, after a healthy run (80.0 kg added, solved at the seat block's middle,
     * 0.30 above its floor), one break per verdict:</p>
     * <p>red-witnessed: with {@code ShipHullMass#isCarried} at {@code if (body.isRiding())} never
     * taken, it fails "the flight computer must weigh the occupant seated aboard — the readout's mass
     * must GROW by him — no `flight_model_changed` for this craft, solved for more than the 281250.0 kg
     * it weighed empty was recorded within 200 ticks".</p>
     * <p>red-witnessed: with {@code ShipInertiaWriter#applyTo} at
     * {@code record.setGameTickMass(frame.getTotalMass());} writing the total less the crew, it fails
     * "the craft's physics record must grow by the occupant's mass its readout reports (80.0 kg); it
     * grew by 0.0 kg".</p>
     * <p>red-witnessed: with {@code ShipHullMass#addCrew} at
     * {@code builder.add(MassContributor.ofBlock(local[0] - ox, local[1] - oy, local[2] - oz,} placing
     * the occupant at its WORLD position instead, it fails "the occupant's mass must sit where the
     * occupant sits aboard — in the column of the pilot seat at 19200001,128,51200 (subspace), inside
     * that block — and the centre of mass moved as if it sat at 4023.500004246831,156.30000000148743,
     * 4023.4999999994398".</p>
     */
    @Test
    public void aSeatedOccupantIsWeighedWhereItSits() throws Exception {
        FixtureSite site = site();
        Reply fixture = lay(site, "with-pilot-deck", "a decked craft that takes an occupant");
        int[] seatOffset = fixture.intArray("pilotSeatFromFlightComputer");
        requireArranged("the fixture must say where its pilot seat stands: " + fixture,
                seatOffset != null && seatOffset.length == 3);
        AssembledCraft craft = assembleLaid(site, fixture, "a decked craft that takes an occupant");
        int seatX = craft.afcX + seatOffset[0];
        int seatY = craft.afcY + seatOffset[1];
        int seatZ = craft.afcZ + seatOffset[2];

        Reply unoccupied = readout(craft, "the craft before anybody sits");
        double unoccupiedKg = Reply.of(unoccupied.object("mass")).number("totalKg");
        requireArranged("the craft must carry nobody before the occupant sits: " + unoccupied,
                Reply.of(unoccupied.object("mass")).number("crewKg") == 0.0);
        Reply before = Reply.of(exec("stellurgytest vs ship-info " + DIM + " id " + craft.physicsId));
        requireArranged("the craft's physics record must answer its mass and centre: " + before,
                before.has("massKg") && before.has("comX"));

        Reply occupied = Reply.of(exec("stellurgytest vs seat-occupy " + DIM + " " + seatX + " " + seatY
                + " " + seatZ));
        // absence is the answer "refused": when the verb cannot seat anybody it answers an error
        // object carrying neither field — which is what this rejects.
        requireArranged("an occupant must be seated on the craft's pilot seat: " + occupied,
                occupied.boolOr("spawned", false) && occupied.boolOr("mounted", false));
        // Marked AFTER the occupant sits: a round run between the mark and the seating would be a
        // round of the craft without him.
        long mark = events.markInstrumented();
        String weighed = events.awaitMatching(mark, "flight_model_changed",
                reply -> anyHeavier(reply, craft.durable, unoccupiedKg),
                "for this craft, solved for more than the " + unoccupiedKg + " kg it weighed empty",
                "the flight computer must weigh the occupant seated aboard — the readout's mass must"
                        + " GROW by him", LINK_BUDGET_TICKS);
        ArrangementFailure.arranged(() -> events.awaitRecordWithFields(mark, "ship_mass_measured",
                "a round must write the physics record with the occupant aboard before the record is"
                        + " read", ROUND_BUDGET_TICKS, "ship", craft.physicsId, "path", "round"));
        double crewKg = Reply.of(readout(craft, "the craft with its occupant").object("mass"))
                .number("crewKg");
        Reply after = Reply.of(exec("stellurgytest vs ship-info " + DIM + " id " + craft.physicsId));
        requireArranged("the craft's physics record must answer its mass and centre: " + after,
                after.has("massKg") && after.has("comX"));

        double m0 = before.number("massKg");
        double added = after.number("massKg") - m0;
        double[] c0 = {before.number("comX"), before.number("comY"), before.number("comZ")};
        double[] c1 = {after.number("comX"), after.number("comY"), after.number("comZ")};
        double[] at = new double[3];
        for (int i = 0; i < 3; i++) {
            at[i] = c1[i] + m0 * (c1[i] - c0[i]) / added;
        }
        measured("occupant", "crewKg=" + crewKg, "added=" + added, "seat=" + seatX + "," + seatY + ","
                + seatZ, "solvedAt=" + at[0] + "," + at[1] + "," + at[2], before, after, weighed);
        assertEquals("the craft's physics record must grow by the occupant's mass its readout reports ("
                        + crewKg + " kg); it grew by " + added + " kg. Before: " + before + " After: "
                        + after, crewKg, added, CREW_MASS_BAND);
        assertTrue("the occupant's mass must sit where the occupant sits aboard — in the column of the"
                        + " pilot seat at " + seatX + "," + seatY + "," + seatZ + " (subspace), inside"
                        + " that block — and the centre of mass moved as if it sat"
                        + " at " + at[0] + "," + at[1] + "," + at[2] + ". Before: " + before
                        + " After: " + after,
                Math.abs(at[0] - (seatX + 0.5)) <= SEAT_COLUMN_HALF_WIDTH
                        && Math.abs(at[2] - (seatZ + 0.5)) <= SEAT_COLUMN_HALF_WIDTH
                        && at[1] >= seatY && at[1] <= seatY + SEATED_HEIGHT);
    }

    // ================================================================================================
    // 3. GRAVITY
    // ================================================================================================

    /**
     * Where a released craft is lifted to: a hundred blocks above the open-air band. The launchpad
     * stays behind as WORLD blocks directly under the lift, and at fifty the Earth craft landed on it —
     * measured 2026-09-29: 42.85 blocks in one run against 59.43 in the next, the difference being the
     * pad.
     */
    private static final int RELEASE_ALTITUDE = FixtureSite.OPEN_AIR_Y + 100;

    /**
     * How much a HELD craft may drift either way, in blocks. A hold that leaks this much is not one;
     * measured 0.0 over 61 ticks (2026-09-29).
     */
    private static final double HELD_TOLERANCE = 2.0;

    /**
     * How far a released craft must sink to count as falling, in blocks. Far above the drift of a hold
     * and far below what free fall covers in the window — at the configured field a released craft
     * covers about 110 blocks in 60 ticks (measured 2026-08-19; 88.7 to 128.3 in 61 ticks from a
     * standing release across four runs on 2026-09-29 — a spread whose cause is not established, and
     * which this bound does not care about) — so the assertion is about WHETHER the craft is released,
     * not how fast; the rate is a balance number and not pinned here.
     */
    private static final double FELL = 4.0;

    /**
     * The window each leg of the Flight Assist scenario is watched over, in server ticks. Measured
     * 2026-10-04: held drift 0.0 and a released fall of 85.4 blocks in 61 ticks — twenty times
     * {@link #FELL}, and still short of the hundred blocks of air under {@link #RELEASE_ALTITUDE}.
     */
    private static final int ASSIST_SAMPLE_TICKS = 60;

    /**
     * The gravity the authored body is given, as a fraction of the configured field. A quarter is far
     * enough from one that no plausible tolerance can hide it, and far enough from zero that the
     * craft must still visibly fall — a body a test cannot tell apart from a void would witness
     * nothing about magnitude.
     */
    private static final double LOW_GRAVITY = 0.25;

    /**
     * How wide a ratio band counts as agreement, as a factor either side of {@link #LOW_GRAVITY}. It
     * refuses everything the scenario exists to catch: an unscaled field lands at 1.0, twice the upper
     * bound, and a multiplier applied twice lands at 0.0625, half the lower one. The measured ratio
     * sits near the multiplier — 0.2500 on 2026-09-29, 0.2134 and 0.2137 on two runs of 2026-10-04 in
     * the shared world; the slack is for the call-apart reads, not for the physics.
     */
    private static final double RATIO_SLACK = 2.0;

    /**
     * How far the Earth craft must sink for the comparison to mean anything, in blocks. Measured
     * 2026-09-29: 59.43 in 41 ticks; a craft that is held, parked or unsimulated covers none of it.
     */
    private static final double EARTH_FELL_MIN = 10.0;

    /**
     * How long each craft is watched in the two-world comparison, in server ticks — sized against the
     * MEASURED fall rate. A craft released at one standard gravity covers about 110 blocks in 60 ticks
     * here (measured 2026-08-19), which from the release altitude would reach the ground and turn the
     * Earth reading into a landing rather than a fall. Forty leaves it around fifty blocks down with
     * clear air beneath it, and still drops the low-gravity craft far enough that its fall cannot be
     * confused with a hold's drift.
     */
    private static final int COMPARISON_SAMPLE_TICKS = 40;

    /**
     * How far the cell craft may drift vertically and still count as not falling, in blocks. At the
     * configured field a released craft covers about 110 blocks in 60 ticks (measured 2026-08-19), so
     * a cell handed a planet's gravity sinks tens of blocks in this window, not two. Measured in a cell
     * 2026-09-29: 0.27 over 41 ticks.
     */
    private static final double STILL_TOLERANCE = 2.0;

    /** The velocity the cell craft's flight computer is commanded to realize for the control, blocks/second. */
    private static final double DRIVE_VZ = 4.0;

    /**
     * How far the cell craft must travel under that command before its stillness means anything, in
     * blocks. At the commanded rate the window covers {@code DRIVE_VZ x CELL_SAMPLE_TICKS / 20} = 8
     * blocks; the bar is well under that so the controller's ramp-up does not decide it, and far above
     * the drift of a craft nobody simulates. Measured 2026-09-29: 7.9 over 41 ticks.
     */
    private static final double DRIVE_MIN_TRAVEL = 3.0;

    /**
     * The window each leg of the cell scenario is watched over, in server ticks. Measured 2026-10-04:
     * the released craft sank 0.0 and the driven one travelled 7.91 blocks in 41 ticks, against the
     * {@link #DRIVE_MIN_TRAVEL} of 3.0 the control needs.
     */
    private static final int CELL_SAMPLE_TICKS = 40;

    /**
     * What the rocket fixture leaves behind after an assembly takes its craft: the 6x6 launchpad (36),
     * the structure tower (7 high, since the variant's scan tops out there), the builder and its power
     * plug — {@code handleFixture}'s own placements.
     */
    private static final int SCAFFOLDING_BLOCKS = 36 + 7 + 2;

    /**
     * Flight Assist is the unmanned mode switch: with it ON an unpiloted craft holds, with it OFF the
     * craft is released and falls.
     *
     * <p>A ship left at altitude with nobody at the controls used not to fall — and not because anything
     * was holding it up. The solver steps only bodies whose physics has been switched on, and that switch
     * was thrown by the flight computer only after a craft had been FLOWN once. A newly built ship
     * therefore hung in the air, and the state read from outside as station-keeping while in fact the
     * craft was not being simulated at all. An assembled craft is simulated from its assembly now, and
     * Flight Assist alone decides whether it holds.</p>
     *
     * <p>Both directions, in one run. "It fell" alone would pass on a build where a craft can no longer
     * hold at all — which would be a worse defect than the one being fixed, and invisible to a one-sided
     * test. So the same craft is held with Flight Assist ON first and asserted NOT to move, then released
     * and asserted to fall. The control comes first deliberately: if the hold is already broken, the test
     * says so instead of reporting a successful fall.</p>
     *
     * <p>red-witnessed: with the unmanned branch's {@code !flightAssistEnabled} release in
     * {@code TileAdvancedFlightComputer} ({@code TileAdvancedFlightComputer#update} at {@code if (!flightAssistEnabled)}) never taken, the fall verdict fails — "sank only
     * 0.0 blocks in 61 ticks" — after the hold passed, 2026-09-29. With that branch taken ALWAYS
     * ({@code TileAdvancedFlightComputer#update} at {@code if (!flightAssistEnabled)} to {@code if (true)}), the hold verdict fails — "must keep station, and this one moved
     * from 235.7 to 57.1" — 2026-09-30.</p>
     */
    @Test
    public void flightAssistDecidesWhetherAnUnpilotedCraftHoldsOrFalls() throws Exception {

        long buildMark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(site(), this::exec,
                "with-pilot-seat", 4, 12, "the craft that is held and then released is built here");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: "
                + asm, Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec,
                events, DIM, ShipIdentity.nameFromAssembly(asm), LINK_BUDGET_TICKS));
        // Usable, not merely named: an unsimulated craft passes the HOLD leg below for free.
        ShipIdentity.awaitUsable(events, buildMark, shipId, DIM,
                "the craft must be simulated before its hold can be measured", LINK_BUDGET_TICKS);
        ShipLift.toAltitude(this::exec, events, client(), DIM, shipId, RELEASE_ALTITUDE,
                "a craft released on its pad lands on the pad at once, which reads as holding");

        // --- first half: Flight Assist ON must HOLD ----------------------------------------------
        Released craft = new Released(DIM, shipId);
        hold(craft);
        double heldFrom = altitudeOf(craft);
        // WINDOW: the altitude is read before and after this stretch and the claim is an UPPER bound
        // on the difference, so a longer stretch than asked can only make a leaking hold show.
        long heldTicks = GameTicks.advanceObserved(client(), GameTicks.server(), ASSIST_SAMPLE_TICKS);
        double heldTo = altitudeOf(craft);
        assertTrue("with Flight Assist ON an unpiloted craft must keep station,"
                        + " and this one moved from " + heldFrom + " to " + heldTo + " in " + heldTicks
                        + " ticks. Without a working hold, the fall asserted below would say nothing - a"
                        + " craft that cannot hold falls whatever the mode switch does.",
                Math.abs(heldFrom - heldTo) <= HELD_TOLERANCE);

        // --- the subject: Flight Assist OFF must RELEASE ------------------------------------------
        release(craft);
        double releasedFrom = altitudeOf(craft);
        // EXPERIMENT: the dose is ASSIST_SAMPLE_TICKS of release. The claim is a LOWER bound, which
        // extra ticks make easier — but only for a craft that is falling at all: a held one does not
        // sink further for being watched longer, and a released one clears FELL many times over.
        long fallTicks = GameTicks.advanceObserved(client(), GameTicks.server(), ASSIST_SAMPLE_TICKS);
        double fellTo = altitudeOf(craft);
        measured("flight assist", "held drift " + (heldFrom - heldTo) + " in " + heldTicks + " ticks",
                "released fall " + (releasedFrom - fellTo) + " in " + fallTicks + " ticks");

        assertTrue("with Flight Assist OFF and nobody at the controls the craft must be RELEASED and"
                        + " fall, but it sank only " + (releasedFrom - fellTo) + " blocks in "
                        + fallTicks + " ticks (from " + releasedFrom + " to " + fellTo + "). Either the"
                        + " controller is still commanding a hover with the assist off, or the craft's"
                        + " physics was never switched on - the two produce the same reading from here"
                        + " and both mean the mode switch is not the mode switch.",
                releasedFrom - fellTo >= FELL);
    }

    /**
     * Gravity is a property of the world a craft is in, and its MAGNITUDE is that world's own
     * gravitational multiplier: a released ship over a quarter-gravity body sinks a quarter as far in
     * the same time as one released over Earth.
     *
     * <p>{@link #flightAssistDecidesWhetherAnUnpilotedCraftHoldsOrFalls} pins that a released craft
     * falls at all — on Earth. That leaves the whole point of a per-world field unobserved: every craft
     * could be falling at one standard gravity everywhere and that scenario would be just as green.</p>
     *
     * <p>Two worlds at once, not one world twice. The two craft are released in the same window, in two
     * worlds, and read separately. Two sequential runs in one world — flip the multiplier, drop again —
     * would pass equally well on a build where gravity is a single global that the last write wins,
     * which is precisely the defect this mechanic exists to rule out.</p>
     *
     * <p>The measurement is a RATIO, and that is what makes it a contract test rather than a pin on
     * today's numbers. The solver adds {@code gravity x mass x dt} and then scales velocity by a drag
     * factor, so the fall is linear in the field and mass-invariant: measured 2026-09-29 over 41 ticks,
     * Earth 59.43 blocks and the quarter-gravity body 14.86, a ratio of 0.2500.</p>
     *
     * <p>The premises are gated before the subject is measured, in order: the low-gravity body really
     * carries the multiplier asked for; both craft became ships; both HOLD with Flight Assist on; and the
     * Earth craft, once released, really falls. The hold is an ABSOLUTE drift, in both directions: if
     * the feed-forward were left on Earth's field while the solver used the body's, a held craft over a
     * quarter-gravity body would CLIMB by the difference.</p>
     *
     * <p>red-witnessed: with {@code StellurgyWorldGravity#of} at {@code return VSConfig.gravity().mul(multiplier, new Vector3d());} answering the configured vector instead of
     * scaling it by the body's multiplier, the ratio verdict fails at 1.015 ("outside [0.125, 0.5]"),
     * 2026-09-29.</p>
     */
    @Test
    public void aCraftOverALowGravityBodyFallsInProportionToThatBodysGravity() throws Exception {

        int lowGravityDim = authorLowGravityBody();

        Released earth = buildAndLift(plot().site(), "the craft released over Earth is built here");
        Released low = buildAndLift(plot().inDimension(lowGravityDim).site(),
                "the craft released over the low-gravity body is built here");

        // --- premise: both craft are simulated, controllable, and at rest ---------------------------
        hold(earth);
        hold(low);
        double earthFrom = altitudeOf(earth), lowFrom = altitudeOf(low);
        // WINDOW: both altitudes are read before and after this stretch and the claim is an UPPER
        // bound on each difference, so a longer stretch than asked can only make a leaking hold show.
        GameTicks.advanceObserved(client(), GameTicks.server(), COMPARISON_SAMPLE_TICKS);
        double earthHeldY = altitudeOf(earth), lowHeldY = altitudeOf(low);
        requireHeld(earth, earthFrom, earthHeldY);
        requireHeld(low, lowFrom, lowHeldY);
        // From here the held reading is the release point: it is the last one taken while the craft
        // was demonstrably at rest, so the distance measured below is a fall and not the tail of
        // whatever the craft was doing when it arrived.

        // --- release both in the same window --------------------------------------------------------
        release(earth);
        release(low);
        // EXPERIMENT: the dose is COMPARISON_SAMPLE_TICKS of release for both craft at once. The
        // subject is a RATIO of two falls over the same ticks, so the box delivering more of them
        // changes both numbers together and not the verdict.
        long fallTicks = GameTicks.advanceObserved(client(), GameTicks.server(), COMPARISON_SAMPLE_TICKS);
        double earthFell = earthHeldY - altitudeOf(earth);
        double lowFell = lowHeldY - altitudeOf(low);
        measured("gravity", "earth fell " + earthFell, "dim " + low.dim + " (gravity " + LOW_GRAVITY
                + ") fell " + lowFell, fallTicks + " ticks", "ratio " + (lowFell / earthFell));

        // --- control: the Earth craft must fall, or the comparison below is between two non-events --
        requireArranged("CONTROL: released over Earth the craft must fall, and this one moved "
                        + earthFell + " blocks in " + fallTicks + " ticks. Until a released craft"
                        + " demonstrably falls here, the low-gravity craft holding still would say"
                        + " nothing about gravity - it is what a craft that was never released, or"
                        + " never simulated, looks like too.",
                earthFell >= EARTH_FELL_MIN);

        // --- the subject: the same release over a quarter-gravity body ------------------------------
        double ratio = lowFell / earthFell;
        assertTrue("a craft released over a body of gravity " + LOW_GRAVITY + " must fall that"
                        + " fraction of what the same release covers over Earth, but it fell "
                        + lowFell + " blocks against Earth's " + earthFell + " - a ratio of " + ratio
                        + ", outside [" + (LOW_GRAVITY / RATIO_SLACK) + ", "
                        + (LOW_GRAVITY * RATIO_SLACK) + "]. A ratio near 1 means the body's"
                        + " multiplier never reached the solver and every world is still one standard"
                        + " gravity; a ratio near 0 means the craft over the low-gravity body is not"
                        + " falling at all, which is a different defect from a weak field.",
                ratio >= LOW_GRAVITY / RATIO_SLACK && ratio <= LOW_GRAVITY * RATIO_SLACK);
    }

    /**
     * A space cell has no gravity, so a craft released in one does not fall.
     *
     * <p>The per-world gravity field answers three ways: a registered body scales the configured vector
     * by its own multiplier, a foreign or vanilla world gets that vector unchanged, and a space cell gets
     * ZERO. The cell is the case a player spends the whole tier-2 game inside.</p>
     *
     * <p>Why "it did not move" needs a control, here more than anywhere: in a cell there is nothing to
     * hold against, and <b>a craft in zero gravity is indistinguishable from a craft nobody is
     * simulating</b> — both sit exactly still, both report zero velocity. So stillness is believed only
     * once the same craft is shown to be under the solver's hand: it is DRIVEN through its own flight
     * computer, and it must translate. The drive is the computer's realized-force channel
     * ({@code force-vel-by-id}) and not a raw velocity write, which the substrate overwrites every step
     * and which moves nothing ({@code VSShipMotionServerTest} pins exactly that).</p>
     *
     * <p>red-witnessed: with {@code StellurgyWorldGravity#of} at {@code return WEIGHTLESS;} answering the configured vector for a space
     * slot instead of zero, the stillness verdict fails — "moved 74.2 blocks vertically in 41 ticks" —
     * on a craft that was {@code ready:true} when released, 2026-09-29; again on the piloted fixture
     * assembled in the cell with its scaffolding removed, "This one moved 64.53 blocks vertically in 41
     * ticks", 2026-09-30. With the scaffolding left in place that break stayed GREEN — sank 1.0 and
     * stopped on the launchpad — which is why the arrangement removes it.</p>
     *
     * <p>The release is a premise, linked on the branch the flight computer took ({@code unmanned_mode},
     * a test mixin at that branch): in a cell a HELD craft is as still as a released one and cancels any
     * field it is given, so without the link a broken release would leave this verdict unable to fail.</p>
     *
     * <p>red-witnessed: with {@code TileAdvancedFlightComputer#update} at {@code if (!flightAssistEnabled)} never taken,
     * the scenario stops at that premise — "no `unmanned_mode` released, for this craft's flight
     * computer … within 200 ticks" — 2026-10-04.</p>
     */
    @Test
    public void aReleasedCraftInACellKeepsItsAltitude() throws Exception {

        // A craft that can FLY, built in the cell: the transit stack's empty origin cell, and the
        // catalogue's piloted fixture assembled in it by the real assembler. The control below drives
        // the craft, and a craft is driven only through actuators aboard — the piloted transit setup's
        // bare 3x3 deck has none (the probe's own note on `transit-setup-empty` says so), and against
        // it the control measured 0.0 blocks in 41 ticks on 2026-09-30.
        TransitSetup setup = TransitSetup.empty(this::exec);
        int cellDim = setup.originDim;
        FixtureSite site = plot().inDimension(cellDim).site();
        long setupMark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(site, this::exec,
                "with-pilot-seat", 2, 12, "the craft released in the cell stands in this volume");
        requireArranged("an AFC-bearing build in the cell must route to a ship, not a rocket: " + asm,
                Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                cellDim, ShipIdentity.nameFromAssembly(asm), LINK_BUDGET_TICKS));
        // USABLE, as its own link, and not merely named. The id above answers as soon as the craft
        // is REGISTERED; a registered craft whose physics has not started yet sits exactly as still
        // as a weightless one. Measured 2026-09-29: the subject window opened on `ready:false`, and
        // under load a planet's gravity in the cell then sank the craft 1.48 blocks in 76 ticks —
        // green, because it was not being simulated for most of the window.
        ShipIdentity.awaitUsable(events, setupMark, shipId, cellDim,
                "the craft must be USABLE before its stillness can mean anything", LINK_BUDGET_TICKS);

        // THE ASSEMBLER'S SCAFFOLDING GOES, because it is a floor. The fixture lays a launchpad one
        // block under the hull, a structure tower beside it and a builder with its power plug, and the
        // assembly takes only the craft — so a released craft that "keeps its altitude" might only be
        // standing on the pad. The volume is the fixture's own footprint (pad x..x+5 / z..z+5 at the
        // site's y, tower at x-1 up to y+6, builder and plug at z-1), and the fill's `placed` is how
        // many blocks were standing in it.
        String cleared = exec("stellurgytest fill " + cellDim + " " + (site.x - 1) + " " + site.y + " "
                + (site.z - 1) + " " + (site.x + 5) + " " + (site.y + 6) + " " + (site.z + 5)
                + " minecraft:air");
        requireArranged("the assembler's pad, tower, builder and plug must be removed from under the"
                        + " craft — " + SCAFFOLDING_BLOCKS + " blocks — before it is released: " + cleared,
                Reply.of(cleared).ok() && Reply.of(cleared).integer("placed") == SCAFFOLDING_BLOCKS);

        // A craft placed by a paste is rigid until something hands it back, and a rigid craft is
        // exactly as still as a weightless one.
        String unparked = exec("stellurgytest vs unpark-by-id " + cellDim + " " + shipId);
        requireArranged("the pasted craft must be handed back to physics: " + unparked,
                Reply.of(unparked).ok());

        // Release it: Flight Assist off is what hands an unpiloted craft to the field, whatever the
        // field turns out to be. Over a planet this is what makes it fall.
        // In a cell the release cannot be SEEN in the craft's motion — a held craft is as still as a
        // released one — and a held craft would cancel any field the cell were wrongly given, so the
        // stillness below would be true for the wrong reason. So the release is linked on the branch
        // the flight computer TOOK, recorded by a test mixin at that branch, for this craft's own
        // computer, from a mark taken before the command.
        Reply model = Reply.of(exec("stellurgytest vs flight-model-by-id " + cellDim + " " + shipId));
        requireArranged("the craft must resolve its own flight computer, whose decision is read: "
                + model, model.bool("found"));
        String afcX = String.valueOf(model.integer("afcX"));
        String afcY = String.valueOf(model.integer("afcY"));
        String afcZ = String.valueOf(model.integer("afcZ"));
        long releaseMark = events.markInstrumented();
        Released craft = new Released(cellDim, shipId);
        release(craft);
        ArrangementFailure.arranged(() -> events.awaitMatching(releaseMark, "unmanned_mode",
                reply -> Events.anyRecordHasAll(reply, "mode", "released", "afcX", afcX, "afcY", afcY,
                        "afcZ", afcZ),
                "released, for this craft's flight computer at " + afcX + "," + afcY + "," + afcZ,
                "the craft's flight computer must take the release branch before its stillness can"
                        + " say anything about the field — a held craft cancels any field it is given",
                LINK_BUDGET_TICKS));

        // --- the subject: released, over nothing, it must keep its altitude ------------------------
        ShipInfo atRelease = ShipInfo.byId(this::exec, cellDim, shipId);
        double before = atRelease.y;
        // WINDOW: the altitude is read before and after this stretch and the claim is an UPPER bound
        // on the difference, so a longer stretch than asked can only make a real field show.
        long stillTicks = GameTicks.advanceObserved(client(), GameTicks.server(), CELL_SAMPLE_TICKS);
        ShipInfo afterStill = ShipInfo.byId(this::exec, cellDim, shipId);
        double after = afterStill.y;
        double sank = before - after;

        // --- the control, taken AFTER the reading and asserted BEFORE it ---------------------------
        // Taken second so the drive cannot disturb the altitude it is vouching for, and asserted first
        // so a craft nobody is simulating fails HERE, on a leg that says so.
        double zBefore = ShipInfo.byId(this::exec, cellDim, shipId).z;
        requireArranged("could not command the craft's flight computer: the control leg cannot run",
                Reply.of(exec("stellurgytest vs force-vel-by-id " + cellDim + " " + shipId + " 0 0 "
                        + DRIVE_VZ)).bool("afcResolved"));
        // EXPERIMENT: the dose is CELL_SAMPLE_TICKS of commanded drive, and the claim is a LOWER bound
        // — so the bar is scaled by the ticks this box delivered and the rate it demands is fixed.
        long driven = GameTicks.advanceObserved(client(), GameTicks.server(), CELL_SAMPLE_TICKS);
        double travelled = Math.abs(ShipInfo.byId(this::exec, cellDim, shipId).z - zBefore);
        double required = DRIVE_MIN_TRAVEL * driven / CELL_SAMPLE_TICKS;
        measured("cell", "sank " + sank + " in " + stillTicks + " ticks",
                "driven " + travelled + " in " + driven + " ticks", "at release " + atRelease.raw(),
                "after " + afterStill.raw());

        requireArranged("CONTROL: the craft must be under the solver's hand for its stillness to"
                        + " mean anything. Commanded at " + DRIVE_VZ + " blocks/s it moved " + travelled
                        + " blocks in " + driven + " ticks, needing " + required + ". In zero gravity a"
                        + " craft nobody simulates sits exactly as still as one that is weightless, so"
                        + " the stillness measured above would be true for the wrong reason.",
                travelled >= required);

        assertTrue("a released craft in a space cell must keep its altitude: there is nothing for it to"
                        + " fall towards, and the field a cell supplies is zero. This one moved " + sank
                        + " blocks vertically in " + stillTicks + " ticks (from " + before + " to "
                        + after + "). Tens of blocks is what the configured field would produce, i.e."
                        + " the cell being handed a planet's gravity.",
                Math.abs(sank) <= STILL_TOLERANCE);
    }

    /**
     * A freshly generated planet, given a known gravity through the ordinary planet command. The
     * generator rolls a multiplier at random, so the body is authored rather than searched for: a
     * test whose discriminating power depends on what the world generator happened to produce is
     * not a test.
     */
    private int authorLowGravityBody() throws Exception {
        DimList before = DimList.from(this::exec);
        exec("ar planet generate 0 LowGravityWitness");
        int[] added = DimList.from(this::exec).addedSince(before);
        requireArranged("planet generate must add exactly one dim - got "
                + java.util.Arrays.toString(added), added.length == 1);
        int dim = added[0];

        String load = exec("stellurgytest dim load " + dim);
        requireArranged("the authored body never loaded: " + load, Reply.of(load).bool("loaded"));

        // The SHIPPED command, not a test-only setter: it is what an operator would use, it refuses
        // a dimension that is not a registered body instead of silently writing to Earth's
        // properties, and it publishes the change the way production does.
        exec("ar planet set " + dim + " gravitationalMultiplier " + LOW_GRAVITY);
        // Read back off the planet registry rather than trusting the command's own reply: what the
        // solver will ask is the registry, and this is the one moment the arrangement can be checked
        // against the same source.
        double gravity = Reply.of(exec("stellurgytest planet info " + dim)).number("gravity");
        // Exact: the multiplier is stored as a float, and 0.25 is exact in one.
        requireArranged("the authored body does not carry the gravity it was given (" + gravity + ")",
                gravity == LOW_GRAVITY);
        return dim;
    }

    /** Build a craft at {@code site}, then lift it into clear sky and hand it to physics. */
    private Released buildAndLift(FixtureSite site, String what) throws Exception {
        int dim = site.dim;
        long buildMark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(site, this::exec, "with-pilot-seat", 4, 12, what);
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket (dim "
                + dim + "): " + asm, Reply.of(asm).integer("rocketCount") == 0);
        String id = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                dim, ShipIdentity.nameFromAssembly(asm), LINK_BUDGET_TICKS));
        // Usable, not merely named: an unsimulated craft passes the HOLD control for free.
        ShipIdentity.awaitUsable(events, buildMark, id, dim,
                "the craft in dim " + dim + " must be simulated before its hold is measured",
                LINK_BUDGET_TICKS);
        ShipLift.toAltitude(this::exec, events, client(), dim, id, RELEASE_ALTITUDE,
                "a craft released on its pad lands on the pad at once, which reads as not falling");
        return new Released(dim, id);
    }

    private void hold(Released craft) throws Exception {
        requireArranged("could not reach the flight computer of the craft in dim " + craft.dim,
                Reply.of(exec("stellurgytest vs fa-by-id " + craft.dim + " " + craft.id + " true"))
                        .bool("afcResolved"));
    }

    private void release(Released craft) throws Exception {
        requireArranged("could not release the craft in dim " + craft.dim,
                Reply.of(exec("stellurgytest vs fa-by-id " + craft.dim + " " + craft.id + " false"))
                        .bool("afcResolved"));
    }

    private void requireHeld(Released craft, double fromY, double heldY) {
        requireArranged("PREMISE: with Flight Assist on the craft in dim " + craft.dim
                        + " must keep station, and this one moved to " + heldY + " from " + fromY
                        + ". Drift DOWN means it is not being held at all, so the fall measured"
                        + " afterwards would not be caused by the release; drift UP means the flight"
                        + " computer is cancelling a field larger than the one the solver applies,"
                        + " which is exactly the disagreement the shared gravity function exists to"
                        + " prevent.",
                Math.abs(fromY - heldY) <= HELD_TOLERANCE);
    }

    /** The craft's own Y, by identity — never "whichever ship is nearest", which a fall would outrun. */
    private double altitudeOf(Released craft) throws Exception {
        return ShipInfo.byId(this::exec, craft.dim, craft.id).y;
    }

    /** A craft the gravity family holds and releases: its world and its identity, nothing nearer. */
    private static final class Released {
        final int dim;
        final String id;

        Released(int dim, String id) {
            this.dim = dim;
            this.id = id;
        }
    }
}
