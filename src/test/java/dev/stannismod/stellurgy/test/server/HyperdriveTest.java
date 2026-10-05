package dev.stannismod.stellurgy.test.server;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.test.AssembledCraft;
import dev.stannismod.stellurgy.test.DriveInfo;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipReadiness;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The hyperdrive family on a real ship: what a player gets for what he builds onto his craft, and what
 * the helm does when he presses the key.
 *
 * <p><b>Every scenario flies the first milestone's own jump craft</b> — fixture {@code with-jump-drive},
 * assembled by its own assembler, which is what binds its machines to its flight computer. A
 * comparison is BEFORE against AFTER the player builds onto the finished ship: the probe
 * {@code drive extend} places coils, cells, sinks, emitters and dampeners into the ship's own yard and
 * links nothing, so a machine counts only if production adopts it ({@code ShipDrive}'s rule for a
 * machine built onto an assembled ship). The fixture's reply names where its machines stand relative
 * to the flight computer, and that is how a scenario reaches the navigation computer or the emitter.</p>
 *
 * <p>Every reading is production's: the generator's own coil scan, the capacitor's own walk, the hull
 * box the assembler recorded against the window the drive holds up, the real jump gate, and the same
 * {@code onJumpKey} the pilot seat calls. The probe places blocks, feeds energy through the Forge
 * Energy capability, arms the console and presses the key; it never computes an answer.</p>
 *
 * <p>No balance number is asserted. What is asserted is what a player can rely on while building:
 * more coils is more drive and a faster ship at the hull's real mass, more cells is more bank, more
 * sinks is a shorter wait, an emitter is what makes the hull fit the window, a machine built onto
 * another ship is not yours, the bank is filled by the ship and survives the ship being saved, and the
 * pilot is never charged for a jump that did not happen.</p>
 *
 * <p>What this tier does NOT see: a player's own hand placing a block (the probe sets it into the
 * yard), the console's GUI and the seat's key packet (the probe calls the methods they call), and what
 * the pilot is shown.</p>
 */
public class HyperdriveTest extends AbstractSharedServerTest {

    private static final int DIM = 0;

    /** The craft nearly every scenario flies: the first milestone's jump craft. */
    private static final String VARIANT = "with-jump-drive";

    /**
     * The same craft with a mast on its deck that rises out of the window its generator holds up
     * alone — the hull that needs its emitter. The two window scenarios measure that it does.
     */
    private static final String TALL_VARIANT = "with-jump-drive-and-mast";

    // What scenarios build onto the craft. Each is a DOSE: any count above zero shows the shape, and
    // these are small enough that every run stays on the pad's own square plus the halo below.
    private static final int COILS_ADDED = 4;
    private static final int CELLS_ADDED = 3;
    private static final int SINKS_ADDED = 4;
    private static final int DAMPENERS_ADDED = 3;

    /**
     * How far the working volume reaches past the launchpad: the longest straight run a scenario builds
     * onto the craft. The generator stands on the pad's east edge and its coils run east of it, so the
     * hull's world image reaches that far beyond the pad.
     *
     * <p>A constant: a final {@code int} computed once from four compile-time constants.</p>
     */
    private static final int HALO = Math.max(Math.max(COILS_ADDED, CELLS_ADDED),
            Math.max(SINKS_ADDED, DAMPENERS_ADDED));

    /**
     * How far above the site the subject reaches. The tallest thing laid is the mast variant's
     * structure tower, 13 above the site (the fixture's {@code towerTop} for it); on the plain craft
     * the capacitor stands six above the site ({@code rocketY + 5}) and a run of sinks may rise
     * straight up off it.
     *
     * <p>A constant: a final {@code int} computed once from compile-time constants.</p>
     */
    private static final int HEIGHT = Math.max(13, 6 + SINKS_ADDED);

    /** A hand-typed destination. The craft stands in no cell the ledger records, so any is legal. */
    private static final String DESTINATION = "7 0 0";
    private static final String OTHER_DESTINATION = "9 0 0";

    /**
     * What a single push through the energy port offers: more than any bank or buffer here could take
     * in one tick, so what was ACCEPTED is decided by the machine and not by the offer.
     */
    private static final long OFFER = 1_000_000_000L;

    /**
     * WINDOW length for the unfed bank, in world ticks. At this craft's accept ceiling (base plus four
     * sinks) a bank that fed itself would gain thousands in this time, against a burst it starts short
     * of.
     */
    private static final int UNFED_WINDOW_TICKS = 100;

    private final Events events = new Events(this::exec,
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * Each scenario starts with an empty sky: a craft left by the scenario before is still loaded and
     * still ticking, and it is cleared here, at the start of the one that must not meet it.
     */
    @Before
    public void emptySky() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, DIM);
    }

    /** The jump craft as assembled, and where its machines stand relative to its flight computer. */
    private static final class JumpCraft {
        final AssembledCraft craft;
        final Reply bay;

        JumpCraft(AssembledCraft craft, Reply bay) {
            this.craft = craft;
            this.bay = bay;
        }

        /** The subspace block of the bay machine the fixture names {@code machine}. */
        int[] at(String machine) {
            int[] offset = bay.blockPos(machine);
            requireArranged("the fixture must say where its " + machine + " stands: " + bay,
                    offset != null);
            return new int[]{craft.afcX + offset[0], craft.afcY + offset[1], craft.afcZ + offset[2]};
        }

        /** {@code <dim> <x> <y> <z>} of that machine, as a probe addresses a block. */
        String machine(String machine) {
            int[] p = at(machine);
            return craft.dim + " " + p[0] + " " + p[1] + " " + p[2];
        }
    }

    private JumpCraft jumpCraft(FixtureSite site, String what) throws Exception {
        return jumpCraft(site, VARIANT, what);
    }

    private JumpCraft jumpCraft(FixtureSite site, String variant, String what) throws Exception {
        Reply fixture = AssembledCraft.lay(site, this::exec, variant, HALO, HEIGHT, what);
        String bay = fixture.object("jumpBayFromFlightComputer");
        requireArranged(what + " — the fixture must say where its drive machines stand: " + fixture,
                bay != null);
        return new JumpCraft(AssembledCraft.assemble(site, this::exec, events, fixture, what),
                Reply.of(bay));
    }

    /** What the craft's drive is, as the ship's own answer. */
    private DriveInfo drive(JumpCraft ship) throws Exception {
        return DriveInfo.at(this::exec, ship.craft.flightComputer());
    }

    /** Build machines onto the assembled craft; the probe links none of them. */
    private Reply extend(JumpCraft ship, int coils, int cells, int sinks, int emitters, int dampeners,
                         String what) throws Exception {
        Reply built = Reply.of("stellurgytest drive extend", exec("stellurgytest drive extend "
                + ship.craft.flightComputer() + " " + coils + " " + cells + " " + sinks + " "
                + emitters + " " + dampeners));
        requireArranged(what + " — every machine asked for must be built onto the ship: " + built,
                built.ok() && built.blockPosArray("coils").length == coils
                        && built.blockPosArray("cells").length == cells
                        && built.blockPosArray("sinks").length == sinks
                        && built.blockPosArray("emitters").length == emitters
                        && built.blockPosArray("dampeners").length == dampeners);
        System.out.println("[built onto " + ship.craft + "] " + built);
        return built;
    }

    /** Take one of the fixture's bay machines off the ship. */
    private void remove(JumpCraft ship, String machine, String what) throws Exception {
        int[] p = ship.at(machine);
        String at = p[0] + " " + p[1] + " " + p[2];
        Reply fill = Reply.of(exec("stellurgytest fill " + DIM + " " + at + " " + at + " minecraft:air"));
        requireArranged(what + " — the " + machine + " must come off the ship: " + fill,
                fill.ok() && fill.integer("placed") == 1);
    }

    /** Fill or drain the bank through its fixture seam: the level is arrangement here, not subject. */
    private void charge(JumpCraft ship, String level, String what) throws Exception {
        Reply.of(exec("stellurgytest drive charge " + ship.craft.flightComputer() + " " + level))
                .requireOk(what + " — the bank must be set " + level);
    }

    /** Type a destination into the ship's own navigation console. */
    private void aim(JumpCraft ship, String destination, String what) throws Exception {
        Reply aimed = Reply.of(exec("stellurgytest nav target " + ship.machine("navigationComputer")
                + " " + destination));
        requireArranged(what + " — the console must take the destination: " + aimed,
                aimed.ok() && aimed.has("target"));
    }

    /** The arm switch at the console; answers whether it is armed afterwards. */
    private boolean arm(JumpCraft ship, boolean on) throws Exception {
        Reply armed = Reply.of(exec("stellurgytest drive arm " + ship.craft.flightComputer()
                + (on ? " on" : " off")));
        requireArranged("the console must answer the arm switch: " + armed, armed.ok());
        return armed.bool("armed");
    }

    /** One press of the helm's jump key; answers whether the drive is winding up afterwards. */
    private boolean press(JumpCraft ship) throws Exception {
        Reply pressed = Reply.of(exec("stellurgytest drive press " + ship.craft.flightComputer()));
        requireArranged("the helm must answer the key: " + pressed, pressed.ok());
        return pressed.bool("spooling");
    }

    /** A craft charged, aimed and ready at the helm except for whatever the scenario changes next. */
    private JumpCraft readyCraft(String what) throws Exception {
        return readyCraft(VARIANT, what);
    }

    private JumpCraft readyCraft(String variant, String what) throws Exception {
        JumpCraft ship = jumpCraft(site(), variant, what);
        charge(ship, "full", what);
        aim(ship, DESTINATION, what);
        return ship;
    }

    // ─── What the build is worth ───────────────────────────────────────────────

    /**
     * Coils welded onto a finished ship's generator make its drive stronger.
     *
     * <p>Contract: this fails if production breaks the contract that a generator's power is measured
     * from the coils welded to it on the ship.</p>
     *
     * <p>red-witnessed: {@code TileHyperdriveGenerator#stats} at {@code DriveTuning.powerForCoils(coilCount())}
     * (the coil count read as 0) fails "welding coils onto the ship's generator must make its drive
     * stronger: 1000 -> 1000", 2026-10-05</p>
     */
    @Test
    public void aBiggerGeneratorIsAStrongerDrive() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft a player adds coils to");
        long before = drive(ship).drivePower;
        requireArranged("the assembled craft must carry a drive: " + before, before > 0L);

        extend(ship, COILS_ADDED, 0, 0, 0, 0, "coils onto the generator");
        long after = drive(ship).drivePower;

        assertTrue("welding coils onto the ship's generator must make its drive stronger: " + before
                + " -> " + after, after > before);
    }

    /**
     * Coils make the same hull faster, and a bigger burst and a bigger draw come with them — at the
     * hull's real mass, which the coils add to: the power gain has to win, and this is where that is
     * measured rather than assumed.
     *
     * <p>Contract: this fails if production breaks the contract that a stronger drive crosses faster
     * and costs more to start and to hold.</p>
     *
     * <p>Measured 2026-10-05 on a healthy run: drive power 1000 -> 5000, speed 125000 -> 588369
     * blocks/tick, implied mass ratio 1.0623 — the four coils' 20 000 kg on the 321 250 kg hull.</p>
     *
     * <p>red-witnessed: {@code JumpSpeed#blocksPerTick} at {@code double ratio = (drivePower / (double) DriveTuning.BASELINE_DRIVE_POWER)}
     * (the generator's base power in place of the drive's) fails "a bigger drive crosses faster, the
     * coils' own mass included: 125000 -> 117673", 2026-10-05</p>
     * <p>red-witnessed: {@code ShipDriveStats#ofPower} at {@code (long) Math.ceil(power * DriveTuning.BURST_COST_PER_POWER)}
     * (the burst priced at the generator's base power) fails "and asks for a bigger burst to open the
     * window: 20000 -> 20000", 2026-10-05</p>
     * <p>red-witnessed: {@code ShipDriveStats#ofPower} at {@code (long) Math.ceil(power * DriveTuning.IN_FLIGHT_DRAW_PER_POWER)}
     * (the draw priced at the generator's base power) fails "and draws more while it holds the window
     * open: 50 -> 50", 2026-10-05</p>
     */
    @Test
    public void aStrongerDriveIsFasterAndCostsMoreToStart() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft a player adds coils to");
        DriveInfo weak = drive(ship);
        requireArranged("the craft must forecast a speed before it is changed — a hull the drive"
                + " cannot weigh forecasts none: " + weak, weak.speedBlocksPerTick > 0L);

        extend(ship, COILS_ADDED, 0, 0, 0, 0, "coils onto the generator");
        DriveInfo strong = drive(ship);
        System.out.println("[measured] drive power " + weak.drivePower + " -> " + strong.drivePower
                + ", speed " + weak.speedBlocksPerTick + " -> " + strong.speedBlocksPerTick
                + ", implied mass ratio after/before " + ((strong.drivePower / (double) weak.drivePower)
                / (strong.speedBlocksPerTick / (double) weak.speedBlocksPerTick)));

        assertTrue("a bigger drive crosses faster, the coils' own mass included: "
                + weak.speedBlocksPerTick + " -> " + strong.speedBlocksPerTick,
                strong.speedBlocksPerTick > weak.speedBlocksPerTick);
        assertTrue("and asks for a bigger burst to open the window: " + weak.burstCost + " -> "
                + strong.burstCost, strong.burstCost > weak.burstCost);
        assertTrue("and draws more while it holds the window open: " + weak.inFlightDraw + " -> "
                + strong.inFlightDraw, strong.inFlightDraw > weak.inFlightDraw);
    }

    /**
     * Cells built onto the bank make it hold more; sinks built onto it make an empty bank's wait for
     * the next window shorter.
     *
     * <p>Contract: this fails if production breaks the contract that cells are a bank's size and sinks
     * its rate of refill.</p>
     *
     * <p>red-witnessed: {@code TileJumpCapacitor#capacity} at {@code scan.count(KIND_CELL) * DriveTuning.CAPACITY_PER_CELL}
     * (cells counted as 0) fails "cells are what the bank holds: 20000 -> 20000", 2026-10-05</p>
     * <p>red-witnessed: {@code TileJumpCapacitor#acceptRate} at {@code scan.count(KIND_SINK) * DriveTuning.ACCEPT_RATE_PER_SINK}
     * (sinks counted as 0) fails "heat sinks are the whole of the cooling system: 2000 -> 2000",
     * 2026-10-05</p>
     */
    @Test
    public void moreCellsIsMoreBankAndMoreSinksIsAShorterWait() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft a player enlarges the bank of");
        DriveInfo lean = drive(ship);

        extend(ship, 0, CELLS_ADDED, 0, 0, 0, "cells onto the capacitor");
        DriveInfo bigBank = drive(ship);
        assertTrue("cells are what the bank holds: " + lean.capacity + " -> " + bigBank.capacity,
                bigBank.capacity > lean.capacity);

        // Same drive, same bank, more cooling: the wait for the next window must shrink. The
        // cooldown is not a timer anywhere — it is how long this bank takes to reach this burst.
        charge(ship, "empty", "an empty bank waits");
        long slowCooldown = drive(ship).cooldownTicks;
        requireArranged("an empty bank must really have a wait: " + slowCooldown, slowCooldown > 0L);
        extend(ship, 0, 0, SINKS_ADDED, 0, 0, "sinks onto the capacitor");
        charge(ship, "empty", "an empty bank waits");
        long fastCooldown = drive(ship).cooldownTicks;

        assertTrue("heat sinks are the whole of the cooling system: " + slowCooldown + " -> "
                + fastCooldown, fastCooldown >= 0L && fastCooldown < slowCooldown);
    }

    /**
     * A ship whose bank holds the burst is not waiting for anything.
     *
     * <p>Contract: this fails if production breaks the contract that a charged bank is ready.</p>
     *
     * <p>red-witnessed: {@code ShipDrive#cooldownTicks} at {@code charge >= needed} (its ready branch
     * answering 1 instead of 0) fails "a ship ready to jump is not waiting for anything ... expected:&lt;0&gt;
     * but was:&lt;1&gt;", 2026-10-05</p>
     * <p>red-witnessed: {@code ShipDrive#capacitorCharge} at {@code total += capacitor.charge();} (each
     * bank summed one short) fails "and its bank holds at least the burst: ... charge=19999/20000",
     * 2026-10-05</p>
     */
    @Test
    public void aChargedBankHasNoCooldownAtAll() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft with a full bank");
        charge(ship, "full", "a full bank");

        DriveInfo info = drive(ship);

        assertEquals("a ship ready to jump is not waiting for anything: " + info, 0L, info.cooldownTicks);
        assertTrue("and its bank holds at least the burst: " + info, info.charge >= info.burstCost);
    }

    /**
     * Machines built onto one ship are that ship's: a second ship asking first does not take them, and
     * the ship they were built onto does.
     *
     * <p>Both craft stand on one plot. The new machines are unlinked when the NEIGHBOUR's drive is
     * asked about first, which is the moment the wrong ship could adopt them; the ship they stand on is
     * asked second, and finding them there is what shows they were adoptable at all.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a ship adopts only what stands
     * inside its own claim.</p>
     *
     * <p>red-witnessed: {@code ShipDrive#componentsOfType} at {@code if (!withinXZ(yard, component.getPos()))}
     * (the claim check dropped) fails "the neighbour, asked first while the new machines were nobody's,
     * must not have taken them: emitters 1 -> 2, dampeners 0 -> 1", 2026-10-05</p>
     * <p>red-witnessed: {@code ShipDrive#componentsOfType} at {@code component.linkToFlightComputer(flightComputerPos);}
     * (nothing adopted) fails "and the craft they were built onto must have: emitters 1 -> 1, dampeners
     * 0 -> 0", 2026-10-05</p>
     */
    @Test
    public void anotherShipsMachinesAreNotYours() throws Exception {
        FixtureSite siteA = plot().siteAt(HALO, HALO);
        FixtureSite siteB = plot().siteAt(3 * HALO + FixtureSite.PAD + 1, HALO);
        JumpCraft a = jumpCraft(siteA, "the craft a player builds onto");
        JumpCraft b = jumpCraft(siteB, "the neighbouring craft");
        DriveInfo aBefore = drive(a);
        DriveInfo bBefore = drive(b);

        extend(a, 0, 0, 0, 1, 1, "an emitter and a dampener onto the first craft");
        DriveInfo bAfter = drive(b);
        DriveInfo aAfter = drive(a);

        assertTrue("the neighbour, asked first while the new machines were nobody's, must not have"
                + " taken them: emitters " + bBefore.emitters + " -> " + bAfter.emitters + ", dampeners "
                + bBefore.dampeners + " -> " + bAfter.dampeners, bAfter.emitters == bBefore.emitters
                && bAfter.dampeners == bBefore.dampeners);
        assertTrue("and the craft they were built onto must have: emitters " + aBefore.emitters
                + " -> " + aAfter.emitters + ", dampeners " + aBefore.dampeners + " -> "
                + aAfter.dampeners, aAfter.emitters == aBefore.emitters + 1
                && aAfter.dampeners == aBefore.dampeners + 1);
    }

    // ─── The window and the hull ───────────────────────────────────────────────

    /**
     * A first ship with no emitters at all can still jump: the milestone's craft, its emitter taken
     * off, fits whole inside the window its generator holds up alone.
     *
     * <p>Contract: this fails if production breaks the contract that the generator's own window
     * wraps a starter craft.</p>
     *
     * <p>Measured 2026-10-05: 0 blocks outside; at the window radius before that day (2) the same
     * craft had 90.</p>
     *
     * <p>red-witnessed: {@code JumpWindow#of} at {@code Envelope.around(generatorPos, DriveTuning.GENERATOR_BASELINE_WINDOW_RADIUS)}
     * (the radius read as 2) fails "a first ship with no emitters at all must still fit its window ...
     * expected:&lt;0&gt; but was:&lt;90&gt;", 2026-10-05</p>
     */
    @Test
    public void aSmallHullFitsInsideTheGeneratorsOwnWindow() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft without its emitter");
        remove(ship, "emitter", "the craft without its emitter");

        DriveInfo info = drive(ship);
        requireArranged("the craft must have a generator and no emitter left: " + info,
                info.drivePower > 0L && info.emitters == 0);
        System.out.println("[measured] the milestone craft without its emitter: " + info);

        assertEquals("a first ship with no emitters at all must still fit its window: " + info.raw(),
                0L, info.hullOutsideWindow());
    }

    /**
     * A hull too tall for the window its generator holds up alone — the mast craft without its
     * emitter — is a warning the pilot can confirm, never a veto.
     *
     * <p>Contract: this fails if production breaks the contract that an undersized window is an
     * advisory, named to the pilot, and not a refusal.</p>
     *
     * <p>Measured 2026-10-05: the mast craft has 0 blocks outside with its emitter and 50 without.</p>
     *
     * <p>red-witnessed: {@code JumpWindow#cover} at {@code uncovered++;} (nothing counted outside) fails
     * "part of this hull is outside the window" with hullOutsideWindow 0, 2026-10-05</p>
     * <p>red-witnessed: {@code JumpGate#clauses} at {@code new Objection(Severity.ADVISORY, MSG_WINDOW_UNDERSIZED)}
     * (the objection made HARD) fails "which is a warning, never a veto" with allowed false, 2026-10-05</p>
     * <p>red-witnessed: {@code Verdict#needsConfirmation} at {@code return allowed() && !objections.isEmpty();}
     * (answering false) fails "and he is told before he makes it" with confirm false, 2026-10-05</p>
     * <p>red-witnessed: {@code JumpGate#clauses} at {@code new Objection(Severity.ADVISORY, MSG_WINDOW_UNDERSIZED)}
     * (the key swapped for MSG_ENERGY_SHORTFALL) fails "and told THAT, in the gate's own message" with
     * message msg.jumpgate.energyshortfall, 2026-10-05</p>
     */
    @Test
    public void aHullTooBigForTheWindowWarnsAndStillLetsThePilotGo() throws Exception {
        JumpCraft ship = readyCraft(TALL_VARIANT, "the mast craft without its emitter");
        System.out.println("[measured] the mast craft with its emitter: " + drive(ship));
        remove(ship, "emitter", "the mast craft without its emitter");

        DriveInfo info = drive(ship);
        System.out.println("[measured] the mast craft without its emitter: " + info);

        assertTrue("part of this hull is outside the window: " + info.raw(),
                info.hullOutsideWindow() > 0L);
        assertTrue("which is a warning, never a veto - leaving part of the ship behind is the "
                + "pilot's decision to make: " + info.raw(), info.allowed);
        assertTrue("and he is told before he makes it: " + info.raw(), info.confirm);
        assertTrue("and told THAT, in the gate's own message: " + info.raw(),
                info.messageIs("msg.jumpgate.windowundersized"));
    }

    /**
     * An emitter built onto a craft too tall for its generator's own window is what makes more of
     * its hull fit.
     *
     * <p>Contract: this fails if production breaks the contract that a ship's emitters extend its
     * window.</p>
     *
     * <p>red-witnessed: {@code ShipDrive#window} at {@code emitterPositions.add(emitter.getPos());}
     * (emitters left out of the window) fails "emitters are an extension for a big hull, and this is
     * what they buy: 50 -> 50 outside, 1 emitter(s)", 2026-10-05</p>
     */
    @Test
    public void emittersAreWhatMakeALongHullFit() throws Exception {
        JumpCraft ship = jumpCraft(site(), TALL_VARIANT, "the mast craft that loses and regains an emitter");
        remove(ship, "emitter", "the mast craft without its emitter");
        DriveInfo bare = drive(ship);
        requireArranged("the bare generator must leave this hull sticking out: " + bare,
                bare.hullOutsideWindow() > 0L && bare.emitters == 0);

        extend(ship, 0, 0, 0, 1, 0, "an emitter onto the craft");
        DriveInfo withEmitter = drive(ship);
        System.out.println("[measured] the mast craft, bare -> one emitter built on: " + bare + " / "
                + withEmitter);

        assertTrue("emitters are an extension for a big hull, and this is what they buy: "
                + bare.hullOutsideWindow() + " -> " + withEmitter.hullOutsideWindow() + " outside, "
                + withEmitter.emitters + " emitter(s)",
                withEmitter.hullOutsideWindow() < bare.hullOutsideWindow());
    }

    // ─── The helm ──────────────────────────────────────────────────────────────

    /**
     * Choosing a destination and choosing to go are two acts: a press with nothing armed winds
     * nothing up, and the same press once the console is armed does.
     *
     * <p>Contract: this fails if production breaks the contract that only an armed jump is fired from
     * the helm.</p>
     *
     * <p>red-witnessed: {@code JumpTrigger#press} at {@code if (!computer.isArmed())} (the arming check
     * skipped) fails "with the console unarmed the press winds nothing up", 2026-10-05</p>
     * <p>red-witnessed: {@code JumpTrigger#press} at {@code spool.begin(now);} (the wind-up never
     * started) fails "and the same press, once the console is armed, does", 2026-10-05</p>
     */
    @Test
    public void thePilotCannotFireAJumpNobodyArmed() throws Exception {
        JumpCraft ship = readyCraft("the jump craft aimed but not armed");
        arm(ship, false);

        boolean unarmed = press(ship);
        arm(ship, true);
        boolean armed = press(ship);

        assertTrue("with the console unarmed the press winds nothing up", !unarmed);
        assertTrue("and the same press, once the console is armed, does", armed);
    }

    /**
     * Arming at the console and pressing at the helm winds the drive up.
     *
     * <p>Contract: this fails if production breaks the contract that an armed, charged and aimed ship
     * winds up when the pilot presses.</p>
     *
     * <p>red-witnessed: {@code TileNavigationComputer#arm} at {@code armed = true;} (the switch never
     * set) fails "the console is where the pilot commits to a destination", 2026-10-05</p>
     * <p>red-witnessed: {@code JumpTrigger#press} at {@code spool.begin(now);} (the wind-up never
     * started) fails "and the helm is where he commits to going", 2026-10-05</p>
     */
    @Test
    public void armingAtTheConsoleAndPressingAtTheHelmWindsTheDriveUp() throws Exception {
        JumpCraft ship = readyCraft("the jump craft armed and pressed");

        boolean armed = arm(ship, true);
        boolean spooling = press(ship);

        assertTrue("the console is where the pilot commits to a destination", armed);
        assertTrue("and the helm is where he commits to going", spooling);
    }

    /**
     * A second press during the wind-up stops it, and costs the pilot nothing.
     *
     * <p>Contract: this fails if production breaks the contract that the burst is the only thing ever
     * spent, and an aborted wind-up has not fired it.</p>
     *
     * <p>red-witnessed: {@code JumpTrigger#press} at {@code spool.abort();} (the abort dropped from the
     * press that meets a wind-up) fails "a second press during the wind-up stops it", 2026-10-05</p>
     * <p>red-witnessed: {@code JumpTrigger#press} at {@code return new Result(Outcome.ABORTED, MSG_ABORTED);}
     * (the burst fired on the way to it) fails "and it costs the pilot nothing - the burst has not fired
     * expected:&lt;20000&gt; but was:&lt;0&gt;", 2026-10-05</p>
     */
    @Test
    public void pressingAgainDuringTheWindUpAbortsItAndCostsNothing() throws Exception {
        JumpCraft ship = readyCraft("the jump craft wound up and stopped");
        requireArranged("the console must arm", arm(ship, true));

        long chargeBefore = drive(ship).charge;
        requireArranged("the first press must wind the drive up", press(ship));
        boolean stillSpooling = press(ship);
        long chargeAfter = drive(ship).charge;

        assertTrue("a second press during the wind-up stops it", !stillSpooling);
        assertEquals("and it costs the pilot nothing - the burst has not fired", chargeBefore,
                chargeAfter);
    }

    /**
     * A press that cannot lead to a jump is free. Armed, charged, then the destination cleared at the
     * console: the press winds nothing up, and the bank is untouched.
     *
     * <p>Contract: this fails if production breaks the contract that a refused press never spends the
     * charge.</p>
     *
     * <p>red-witnessed: {@code JumpTrigger#press} at {@code ShipNavigation nav = new ShipNavigation(world, flightComputerPos, shipId);}
     * (the burst fired before anything is decided) fails "a refusal is never a loss expected:&lt;20000&gt;
     * but was:&lt;0&gt;", 2026-10-05</p>
     */
    @Test
    public void aRefusedJumpNeverSpendsTheCharge() throws Exception {
        JumpCraft ship = readyCraft("the jump craft whose destination is cleared");
        requireArranged("the console must arm", arm(ship, true));
        Reply.of(exec("stellurgytest nav cleartarget " + ship.machine("navigationComputer")))
                .requireOk("the destination must be cleared at the console");

        long before = drive(ship).charge;
        requireArranged("the bank must hold something a press could spend: " + before, before > 0L);
        boolean spooling = press(ship);
        long after = drive(ship).charge;
        requireArranged("the press must have been refused — a ship with no destination does not wind"
                + " up", !spooling);

        assertEquals("a refusal is never a loss", before, after);
    }

    /**
     * Re-aiming disarms: a ship is never left armed at an answer the pilot has since changed.
     *
     * <p>Contract: this fails if production breaks the contract that a new destination is a new
     * decision.</p>
     *
     * <p>red-witnessed: {@code TileNavigationComputer#aim} at {@code this.armed = false;} (re-aiming left
     * armed) fails "a new destination is a new decision", 2026-10-05</p>
     */
    @Test
    public void clearingTheTargetDisarmsTheJump() throws Exception {
        JumpCraft ship = readyCraft("the jump craft re-aimed after arming");
        boolean armed = arm(ship, true);

        aim(ship, OTHER_DESTINATION, "a new destination");
        boolean spooling = press(ship);

        requireArranged("it really was armed before the new destination", armed);
        assertTrue("a new destination is a new decision", !spooling);
    }

    // ─── Dampeners ─────────────────────────────────────────────────────────────

    /**
     * Dampeners built onto the craft are found as its own, and the ones its grid has charged report
     * powered.
     *
     * <p>Contract: this fails if production breaks the contract that a ship's dampeners are its own and
     * protect only once powered.</p>
     *
     * <p>red-witnessed: {@code ShipDrive#componentsOfType} at {@code component.linkToFlightComputer(flightComputerPos);}
     * (nothing adopted) fails "every dampener built onto the craft belongs to it ... expected:&lt;3&gt; but
     * was:&lt;0&gt;", 2026-10-05</p>
     * <p>red-witnessed: {@code TileGravityDampener#isPowered} at {@code return energy.getEnergyStored() >= POWERED_THRESHOLD;}
     * (compared against 0) fails "and none protects anybody before it has power ... expected:&lt;0&gt; but
     * was:&lt;3&gt;", 2026-10-05</p>
     * <p>red-witnessed: {@code TileGravityDampener#isPowered} at {@code return energy.getEnergyStored() >= POWERED_THRESHOLD;}
     * (answering false) fails "and each one the grid has charged will ... expected:&lt;3&gt; but
     * was:&lt;0&gt;", 2026-10-05</p>
     */
    @Test
    public void dampenersAreFoundAndReportPowered() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft a player adds dampeners to");
        extend(ship, 0, 0, 0, 0, DAMPENERS_ADDED, "dampeners onto the craft");
        DriveInfo dark = drive(ship);

        Reply fed = Reply.of("stellurgytest drive push", exec("stellurgytest drive push "
                + ship.craft.flightComputer() + " " + OFFER + " dampeners"));
        requireArranged("every dampener must expose a port the grid can push into: " + fed,
                fed.ok() && fed.integer("ports") == dark.dampeners);
        DriveInfo lit = drive(ship);

        assertEquals("every dampener built onto the craft belongs to it: " + dark, DAMPENERS_ADDED,
                dark.dampeners);
        assertEquals("and none protects anybody before it has power: " + dark, 0, dark.poweredDampeners);
        assertEquals("and each one the grid has charged will: " + lit, DAMPENERS_ADDED,
                lit.poweredDampeners);
    }

    // ─── The bank is filled by the SHIP, not by the clock ──────────────────────

    /**
     * A bank nobody feeds stays where it is while the world runs — the defect this guards against WAS
     * the clock, so it is asked of a real one.
     *
     * <p>Contract: this fails if production breaks the contract that a capacitor's charge comes only
     * from what is pushed into it.</p>
     *
     * <p>red-witnessed: {@code TileJumpCapacitor#charge} at {@code return Math.min(capacity(), Math.max(0L, charge));}
     * (the level read as the stored charge plus the world clock mod 1000) fails "a running world must
     * not have put a single unit into a bank nothing is feeding ... expected:&lt;18&gt; but
     * was:&lt;121&gt;", 2026-10-05</p>
     */
    @Test
    public void aFRESHBANKSTAYSEMPTYWHILETIMEPASSES() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft with a drained bank");
        charge(ship, "empty", "a drained bank");
        DriveInfo before = drive(ship);
        requireArranged("the drained bank must be short of the burst, or a bank that filled itself"
                + " would have no room to show it: " + before, before.charge < before.burstCost);

        // WINDOW: the bank is read on both sides of a stretch of its own world's clock, and the claim
        // is that the second read equals the first. Overshoot gives an unfed bank MORE time to fill
        // itself, so a slow box can only make this stricter.
        GameTicks.advanceWorld(client(), DIM, UNFED_WINDOW_TICKS);
        DriveInfo after = drive(ship);

        assertEquals("a running world must not have put a single unit into a bank nothing is feeding: "
                + before + " / " + after, before.charge, after.charge);
    }

    /**
     * What the ship's grid pushes in through the energy port is what the bank holds, one tick's
     * throughput at a time.
     *
     * <p>Contract: this fails if production breaks the contract that a bank is a Forge Energy receiver
     * bounded by its accept rate.</p>
     *
     * <p>red-witnessed: {@code TileJumpCapacitor#acceptCharge} at {@code long accepted = Math.min(Math.min(room, acceptRate()), amount);}
     * (answering 0) fails "the bank must take some of what is offered: ...\"accepted\":0", 2026-10-05</p>
     * <p>red-witnessed: {@code TileJumpCapacitor#acceptCharge} at {@code charge = charge() + accepted;}
     * (half of it kept) fails "what it took is what it holds expected:&lt;10&gt; but was:&lt;5&gt;" (taken
     * with the sinks also counted as 0), 2026-10-05</p>
     * <p>red-witnessed: {@code TileJumpCapacitor#acceptCharge} at {@code long accepted = Math.min(Math.min(room, acceptRate()), amount);}
     * (the accept rate left out) fails "one push is one tick's worth ... (20000 of 20000)", 2026-10-05</p>
     * <p>red-witnessed: {@code TileJumpCapacitor#acceptCharge} at {@code charge = charge() + accepted;}
     * (overwritten instead of added to) fails "and a second push adds to the first: ...\"charge\":170",
     * 2026-10-05</p>
     */
    @Test
    public void whatTheSHIPPUSHESINthroughItsGridIsWhatTheBankHolds() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft whose bank the grid fills");
        charge(ship, "empty", "a drained bank");

        // `push` answers what the PORTS did, not what the drive is, so it is read as its own reply.
        Reply pushed = Reply.of("stellurgytest drive push",
                exec("stellurgytest drive push " + ship.craft.flightComputer() + " " + OFFER));
        requireArranged("the bank must expose an energy port for the ship to push into: " + pushed,
                pushed.ok() && pushed.longInteger("ports") > 0L);
        long accepted = pushed.longInteger("accepted");
        DriveInfo filled = drive(ship);

        assertTrue("the bank must take some of what is offered: " + pushed, accepted > 0L);
        assertEquals("what it took is what it holds", accepted, filled.charge);
        assertTrue("one push is one tick's worth — the accept rate is a throughput ceiling, so an offer"
                + " of " + OFFER + " does not fill the bank (" + accepted + " of " + filled.capacity
                + ")", accepted < filled.capacity);

        Reply again = Reply.of("stellurgytest drive push",
                exec("stellurgytest drive push " + ship.craft.flightComputer() + " " + OFFER));
        assertTrue("and a second push adds to the first: " + again, again.longInteger("charge") > accepted);
    }

    /**
     * A bank's charge survives the ship being unloaded and loaded again — saved with the ship's own
     * chunks and read back from them.
     *
     * <p>The ship is let unload the way a ship nobody is near unloads; the LINK that its capacitor's
     * chunk left memory is production's own chunk unload, after which nothing of the old tile exists
     * and the charge read afterwards was read back from disk. The ship is then held loaded again and
     * asked for.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a capacitor's charge is part of
     * what a ship saves.</p>
     *
     * <p>red-witnessed: {@code TileJumpCapacitor#writeToNBT} at {@code nbt.setLong(NBT_CHARGE, charge);}
     * (the charge never written) fails "a bank that came back from disk holds what it held ...
     * expected:&lt;20000&gt; but was:&lt;0&gt;", 2026-10-05</p>
     * <p>red-witnessed: {@code WorldServerShipManager#queueShipUnload} at {@code this.unloadQueue.add(shipID);}
     * (an unload never queued) fails "the ship must unload once nothing holds it — no `ship_unloaded`
     * ... within 200 ticks", 2026-10-05</p>
     * <p>red-witnessed: {@code PhysicsObject#unload} at {@code provider.queueUnload(claimedChunkCache.getChunkAt(chunkPos.x, chunkPos.z));}
     * (the ship's chunks kept in memory) fails "the capacitor's chunk must leave memory ... no
     * `chunk_unloaded` ... within 200 ticks", 2026-10-05</p>
     * <p>red-witnessed: {@code ShipLoadedAnnouncer#onWorldTick} at {@code MinecraftForge.EVENT_BUS.post(new ShipEvent.ShipLoadedEvent(}
     * (the load never announced) fails "the ship must come back when it is asked for — no `ship_usable`
     * ... within 200 ticks", 2026-10-05</p>
     */
    @Test
    public void aBanksChargeSurvivesAREALunloadAndReload() throws Exception {
        JumpCraft ship = jumpCraft(site(), "the jump craft parked with a full bank");
        charge(ship, "full", "a full bank");
        long before = drive(ship).charge;
        requireArranged("the bank must hold something to keep: " + before, before > 0L);
        int[] capacitor = ship.at("capacitor");

        long unloadMark = events.mark();
        ShipReadiness.letShipsUnload(this::exec, "the ship's own unload is what writes its chunks to disk");
        try {
            events.awaitRecordWithField(unloadMark, "ship_unloaded", "vsShip", ship.craft.physicsId,
                    "the ship must unload once nothing holds it", AssembledCraft.LINK_BUDGET_TICKS);
            events.awaitRecordWithFields(unloadMark, "chunk_unloaded",
                    "the capacitor's chunk must leave memory, or nothing below is read from disk",
                    AssembledCraft.LINK_BUDGET_TICKS, "dim", String.valueOf(DIM),
                    "cx", String.valueOf(capacitor[0] >> 4), "cz", String.valueOf(capacitor[2] >> 4));
        } finally {
            ShipReadiness.holdShipsLoaded(this::exec, "the ship is asked for again");
        }
        long loadMark = events.mark();
        exec("stellurgytest vs load-ships " + DIM);
        events.awaitRecordWithField(loadMark, "ship_usable", "vsShip", ship.craft.physicsId,
                "the ship must come back when it is asked for", AssembledCraft.LINK_BUDGET_TICKS);

        DriveInfo after = drive(ship);
        assertEquals("a bank that came back from disk holds what it held: " + after.raw(), before,
                after.charge);
    }
}
