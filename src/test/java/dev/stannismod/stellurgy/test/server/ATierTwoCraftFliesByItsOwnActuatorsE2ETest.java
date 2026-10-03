package dev.stannismod.stellurgy.test.server;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.integration.vs.PhysicsUnits;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ServerWindow;
import dev.stannismod.stellurgy.test.ShipReadiness;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A tier-2 craft moves only by what its own hull can push and turn with, and exactly as its readout
 * says it can.
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
 */
public class ATierTwoCraftFliesByItsOwnActuatorsE2ETest extends AbstractSharedServerTest {

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

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    private final Events events = new Events(this::exec,
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /**
     * Each scenario starts with an empty sky and in vacuum.
     *
     * <p>The craft a previous scenario flew go on moving, so they are cleared here, at the start of
     * the one that must not meet them. The vacuum is the class's premise (see the class note) and is
     * re-stated per scenario so that no scenario depends on another having run first; it is READ
     * back, because the command's own answer is a chat line and not a value.</p>
     */
    @Before
    public void emptySkyInVacuum() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, DIM);
        exec("ar planet set " + DIM + " atmosphereDensity 0");
        // One scenario raises the field; every scenario starts from the standard one, so a scenario
        // that failed with the field raised cannot hand it on.
        exec("ar planet set " + DIM + " gravitationalMultiplier 1");
        Reply planet = Reply.of(exec("stellurgytest planet info " + DIM));
        requireArranged("the overworld must be airless for a readout to be the whole prediction: "
                + planet, planet.number("atmosphereDensity") == 0.0);
        requireArranged("the overworld must be at standard gravity, which every scenario here"
                + " leaves it at: " + planet, planet.number("gravity") == 1.0);
    }

    /** One assembled craft, by every name a scenario addresses it with. */
    private static final class Craft {
        final String durable;
        final String physicsId;
        final int afcX;
        final int afcY;
        final int afcZ;

        Craft(String durable, String physicsId, int afcX, int afcY, int afcZ) {
            this.durable = durable;
            this.physicsId = physicsId;
            this.afcX = afcX;
            this.afcY = afcY;
            this.afcZ = afcZ;
        }
    }

    /**
     * Lay a fixture at {@code site}, assemble it, and wait for the two records that make it
     * addressable: its naming (which hands over the physics id) and its first flight model (which
     * hands over the flight computer's address aboard).
     *
     * <p>A build the assembler warns about first — a hull that cannot hold its weight — is pressed a
     * second time; that is how a player builds one, and whether it WAS warned about is not this
     * arrangement's question.</p>
     */
    private Craft assemble(FixtureSite site, String variant, String what) throws Exception {
        return assembleLaid(site, lay(site, variant, what), what);
    }

    /**
     * The first half alone: make room and lay the fixture, answering the fixture's own reply — which
     * carries what a scenario may need beyond where to press, such as where the hold stands.
     */
    private Reply lay(FixtureSite site, String variant, String what) throws Exception {
        site.makeRoom(this::exec, HALO, HEIGHT, what);
        Reply fixture = Reply.of(exec("stellurgytest fixture rocket " + site.dim + " " + site.x + " "
                + site.y + " " + site.z + " " + variant));
        requireArranged(what + " — the fixture (" + variant + ") must be laid: " + fixture,
                fixture.ok() && fixture.blockPos("builderPos") != null);
        return fixture;
    }

    /**
     * The second half: press the assembler the fixture laid, and wait for the craft to be named.
     *
     * <p>red-witnessed: {@code TileRocketAssemblingMachine#assembleRocket} at {@code VSIntegration.assembleTier2Ship(world, shipStructure,} (the cut build never handed to the
     * physics mod) fails "the craft must be named once it is assembled" in every scenario that builds,
     * 2026-09-30</p>
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#rebuildFlightModel} at {@code net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(} (a rebuilt flight model never announced)
     * fails "the craft's flight computer must build its first flight model" in every scenario that
     * builds, 2026-09-30</p>
     */
    private Craft assembleLaid(FixtureSite site, Reply fixture, String what) throws Exception {
        long mark = events.mark();
        int[] builder = fixture.blockPos("builderPos");
        Reply press = Reply.of(RocketFixture.assembleBuilt(site, this::exec, builder));
        requireArranged(what + " — the assemble press must answer whether it built the ship: "
                + press, press.ok() && press.has("shipCut"));
        if (press.has("shipCut") && !press.bool("shipCut")) {
            press = Reply.of(RocketFixture.assembleBuilt(site, this::exec, builder));
            requireArranged(what + " — a second press on the same build must build it: " + press,
                    press.ok() && press.bool("shipCut"));
        }
        String durable = press.text("shipId");
        String named = events.awaitRecordWithFields(mark, "ship_lifecycle",
                what + " — the craft must be named once it is assembled", LINK_BUDGET_TICKS,
                "durable", durable, "edge", "named");
        String model = events.awaitRecordWithFields(mark, "flight_model_changed",
                what + " — the craft's flight computer must build its first flight model",
                LINK_BUDGET_TICKS, "ship", durable);
        return new Craft(durable, Events.text(named, "ship"), (int) Events.number(model, "afcX"),
                (int) Events.number(model, "afcY"), (int) Events.number(model, "afcZ"));
    }

    /** Command a world-frame velocity on the craft's own flight computer, and require it took. */
    private void command(Craft craft, String verb, double x, double y, double z, String what)
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
    private Reply readout(Craft craft, String what) throws Exception {
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
    private String drive(Craft craft, String verb, double x, double y, double z, int doseTicks,
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
        FixtureSite site = site();
        Craft craft = assemble(site, "with-pilot-deck", "a decked craft driven forward");
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
     * <p>red-witnessed: {@code ShipHullMass#addContents} at {@code double held = dev.stannismod.stellurgy.util.WeightEngine.INSTANCE.getTEWeight(tile);} (a hull's contents weighed at zero) fails "the flight
     * computer must weigh the cargo that was stowed aboard — the readout's mass must GROW by it" with
     * no heavier model inside 200 ticks, 2026-09-30</p>
     * <p>red-witnessed: {@code ShipReadout#of} at {@code a[v][e.ordinal()][d.ordinal()] = views[v].authority(d, e);} (each authority scaled by the structural share of the
     * mass) fails "cargo must not change the craft's surge force" with empty 4 905 000 N, laden
     * 4 044 474 N, 2026-09-30</p>
     * <p>red-witnessed: {@code ShipHullMass#addContents} at {@code builder.add(MassContributor.ofBlock(x + 0.5 - ox, y + 0.5 - oy, z + 0.5 - oz,} (content mass placed ten blocks off the block that holds
     * it) fails "laden, the craft must accelerate less by exactly the ratio of its masses" with empty
     * 56.74, laden 28.54 blocks per engine second², 2026-09-30</p>
     */
    @Test
    public void theSameShipCarryingCargoAcceleratesLessByItsMassRatio() throws Exception {
        FixtureSite site = site();
        Reply fixture = lay(site, "with-pilot-deck-and-hold", "a decked craft with a hold");
        // WHERE the hold is aboard is the fixture's to say: the chest stands at a fixed offset from
        // the flight computer, and the two ride into the ship's yard together.
        int[] hold = fixture.intArray("holdFromFlightComputer");
        requireArranged("the fixture must say where its hold stands: " + fixture,
                hold != null && hold.length == 3);
        Craft craft = assembleLaid(site, fixture, "a decked craft with a hold");
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
        FixtureSite controlSite = plot().siteAt(4, 4);
        FixtureSite bareSite = plot().siteAt(36, 4);
        Craft control = assemble(controlSite, "with-pilot-deck", "the control: a craft with motors");
        Craft bare = assemble(bareSite, "hull-without-actuators", "a hull with nothing that pushes");
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
        FixtureSite site = site();
        Craft craft = assemble(site, "wheel-only-hull", "a hull whose only actuator is a wheel");
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
        FixtureSite weakSite = plot().siteAt(4, 4);
        FixtureSite strongSite = plot().siteAt(36, 4);
        Craft weak = assemble(weakSite, "with-pilot-seat", "a craft that will be too weak to hover");
        Craft strong = assemble(strongSite, "with-pilot-deck", "the control: a craft that will hover");
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
     * forward — and removed from the ship's yard in place. The test links on the flight computer's
     * next model announcement for this craft (a revision past the one it read before), then reads the
     * readout once. The same physics id still answers throughout: the ship was never re-built.</p>
     *
     * <p>Contract: this fails if production breaks the contract that the live readout follows the
     * hull as it changes.</p>
     *
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#tickFlightModel} at {@code boolean due = flightModel == null} (a model rebuilt only when the
     * computer has none — neither a hull change nor the load round triggers one) fails "the flight
     * computer must re-survey its hull once a motor is taken off it" with no model past revision 1
     * inside 200 ticks, 2026-09-30</p>
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#rebuildFlightModel} at {@code survey.mass(), survey.design(), survey.live(), HELM_FRAME);} (a rebuild solved over the previous
     * model's live actuators instead of the new survey's) fails "with one forward motor gone, the
     * craft's live forward authority must drop" with 4 905 000 N at revision 2, 2026-09-30</p>
     */
    @Test
    public void removingAWorkingMotorLowersLiveAuthorityWithoutReassembly() throws Exception {
        FixtureSite site = site();
        Craft craft = assemble(site, "with-pilot-deck", "a decked craft losing a motor");
        command(craft, "force-vel-by-id", 0, 0, 0, "hold it where it was built");

        Reply before = readout(craft, "before the motor is removed");
        long revision = before.longInteger("revision");
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
        events.awaitMatching(mark, "flight_model_changed",
                reply -> anyLaterRevision(reply, craft.durable, revision),
                "for this craft, at a revision past " + revision,
                "the flight computer must re-survey its hull once a motor is taken off it",
                LINK_BUDGET_TICKS);
        Reply after = readout(craft, "after the motor is removed");
        double forceAfter = live(after, "SURGE_POSITIVE").number("sustained");
        measured("motor removed", "forceBefore=" + forceBefore, "forceAfter=" + forceAfter,
                "revisionBefore=" + revision, "revisionAfter=" + after.longInteger("revision"), motor);
        assertTrue("with one forward motor gone, the craft's live forward authority must drop below"
                        + " the " + forceBefore + " N it had; it reads " + forceAfter + " N at revision "
                        + after.longInteger("revision") + ". Removed: " + motor,
                forceAfter < forceBefore);
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

    private static boolean anyLaterRevision(String reply, String durable, long revision) {
        for (String record : Events.recordsWhere(reply, "ship", durable)) {
            if (Events.number(record, "revision") > revision) {
                return true;
            }
        }
        return false;
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
}
