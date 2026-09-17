package zmaster587.advancedRocketry.test.milestone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import zmaster587.advancedRocketry.space.TerrainHeightFinder;
import zmaster587.advancedRocketry.test.Chains;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.CellInfo;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.Plot;
import zmaster587.advancedRocketry.test.client.ClientEvents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

/**
 * The first milestone loop, walked the way a player walks it: build a jump-capable craft, turn it
 * into a ship at the assembler's own screen, sit down in the pilot seat and fly it off the planet.
 *
 * <p><b>What makes this a MILESTONE test rather than another e2e.</b> Every earlier test in this
 * repo is allowed to take a shortcut somewhere — a probe assembles the ship, a probe mounts the
 * seat, a probe starts the crossing — because each one is aimed at a single mechanic and the rest
 * is arrangement. Here nothing a player does may be done for him. Probes may place blocks, seed
 * config and take readings; every ACT is the client's: the crosshair is put on a block and the real
 * use key is pressed, the assembler's Scan and Build are real button clicks on the real screen, the
 * seat is boarded by aiming at it, and the climb to orbit is a key held down. What the probes are
 * used for here is the opposite of driving — they are the instruments the test reads the world
 * with.</p>
 *
 * <p><b>The legs, in order.</b></p>
 * <ol>
 *   <li>The craft is placed on a pad (blocks only — placing blocks is not an act a player performs
 *       through any interface a test can drive, and the fixture is the settled way to stand a
 *       structure up).</li>
 *   <li>The client walks up to the rocket assembler, opens its screen with a real use-key press,
 *       and clicks Scan and then Build. The completion signal is a VS ship existing where there was
 *       none — a tier-2 craft spawns no {@code EntityRocket}, so a rocket list would report success
 *       for a build that produced nothing.</li>
 *   <li>The client boards the pilot seat of the ship he just built, by aiming at the seat block and
 *       pressing use — and the seating is read back from the CLIENT, not from the server.</li>
 *   <li>He holds the vertical-up key until the ship crosses the atmosphere ceiling, and the arrival
 *       is measured from the client too: his own dimension changed, the ship is on the space
 *       ledger, and he is still in his seat.</li>
 * </ol>
 *
 * <p><b>The one arrangement knob</b> is the orbit line, seeded to the config minimum so the powered
 * climb takes seconds instead of minutes. It changes how LONG the climb is, not what the crossing
 * decides: the trigger predicate is "the ship is above the dimension's orbit line", whatever that
 * line happens to be. Fuel is turned off for the same class of reason — a fuel-adequacy check on
 * the pad is a different mechanic with its own tests, and leaving it on would make this loop fail
 * for a reason that has nothing to do with the loop.</p>
 *
 * <p>Manual server + client lifecycle: the config has to be written into the game directory before
 * the server boots.</p>
 */
public class M1PlanetToPlanetMilestoneE2ETest {

    /**
     * How long the jump trigger's verdict may take to appear after the key goes down, in ticks.
     *
     * <p>A deadline for a discrete decision, not a settle: the press is answered on the tick the
     * server handles it, and the whole budget is there so a loaded box cannot turn a decision that
     * happened into one that did not. An expiry means the press never reached the ship.</p>
     */
    private static final int JUMP_PRESS_BUDGET_TICKS = 200;

    /**
     * How long a container slot click is given to have been APPLIED, in client ticks.
     *
     * <p>A bound on a round trip, not a settle and not a poll budget: the click goes to the server,
     * the container applies it, the inventory comes back. Two seconds is generous for one exchange
     * on any box this suite runs on, and a click that has not landed in that time has not landed.</p>
     */
    private static final int SLOT_APPLIED_TICKS = 40;

    private static final String BUILDER_POS = "builderPos";
    private static final String COUNT = "count";
    private static final String LEDGER = "ledger";
    private static final String SLOT_DIMS = "slotDims";
    private static final String SHIP_ID = "shipId";
    private static final String CELL = "cell";
    private static final String NAV_TARGET = "target";
    private static final String NAV_ARMED = "armed";
    private static final String NAV_SHIP_ADDRESSES = "ship";
    /** One body of a {@code space bodies} ship entry, by the names the probe writes. */
    private static final String SHIPS = "ships";
    private static final String BODY_DIM = "dim";
    private static final String BODY_DESCEND_TARGET = "descendTarget";
    private static final String BODY_BEARING = "bearing";
    private static final String BODY_DISTANCE = "distance";
    /**
     * The console is aimed at somewhere a ship can put down: the BODY it names may be descended to,
     * and its dimension is a real world rather than one of the space subsystem's own slot worlds.
     * The slot term matters — a slot world is void, and a ship "landing" in one has not reached a
     * planet at all.
     *
     * <p>Asked of the BODY rather than of the aim CELL on purpose. The aim is a prediction of where
     * that body will be when the ship arrives, so the cell is empty right now and will be empty
     * again later; the body is what the pilot picked and what he expects to find.</p>
     */
    private static final String NAV_TARGET_DESCEND_TARGET = "targetDescendTarget";
    private static final String NAV_TARGET_SLOT_WORLD = "targetSlotWorld";

    /** Whether {@code nav status} is aimed at a body a ship can actually put down on. */
    private static boolean isLandable(String navStatus) {
        Reply aim = Reply.of("artest nav status", navStatus);
        return aim.bool(NAV_TARGET_DESCEND_TARGET, false)
                && !aim.bool(NAV_TARGET_SLOT_WORLD, true);
    }

    /** The body the console is aimed at, from {@code nav status}. */
    private static final String NAV_TARGET_DIM = "targetDim";
    /** The bodies of ONE cell, from {@code space cell-info} (not the whole system's list). */
    private static final String CELL_BODIES = "cellBodies";
    /** Slot dimension ids that Advanced Rocketry also holds a body for — always empty. */
    private static final String SLOT_DIMS_ALSO_BODIES = "slotDimsAlsoBodies";

    /** A jump-capable craft with a walkable deck: the ship this milestone is about. */
    private static final String VARIANT = "with-jump-drive";

    /**
     * This milestone's own patch of world.
     *
     * <p><b>The lane keeps the coordinates this test's green runs were taken on</b> — 8400/8400,
     * chosen to be far from every other fixture site so a stray ship from another run can never be
     * read here — while the SITE inside it is allocated rather than typed. What that buys is the
     * pair of refusals the plot carries: the volume this fixture clears is asserted to lie inside
     * the plot, and a second structure on it could not reach into the first. This class boots its
     * own server and runs one scenario, so index 0 is the whole allocation it will ever need.</p>
     *
     * <p>Not static: a plot records the ground its scenario has cleared, and that record belongs to
     * the test instance that made it, not to the JVM.</p>
     *
     * <p><b>The lane origin is the proven coordinate MINUS the inset, and the subtraction is the
     * point.</b> A lane names a plot's corner; a site stands {@link Plot#FIXTURE_INSET} blocks
     * inside it, so writing the proven number as the ORIGIN would build the craft twenty blocks
     * away from where every green run put it, silently.</p>
     */
    private static final int PROVEN_X = 8400, PROVEN_Z = 8400;
    /** @see #plot */
    private final Plot plot = Plot.forScenario(0, "the milestone's craft", 0,
            new Plot.Lane(PROVEN_X - Plot.FIXTURE_INSET, PROVEN_Z - Plot.FIXTURE_INSET, Plot.SIZE));
    private final FixtureSite site = plot.site();
    /** The site owns the coordinates; these aliases keep the body below unchanged. */
    private final int bx = site.x, by = site.y, bz = site.z;

    /**
     * The seeded atmosphere ceiling: the config key's own minimum. The ONE arrangement knob in this
     * test — it shortens the climb from minutes to seconds and does not touch the entry predicate,
     * which asks whether the ship is above the line, not where the line is.
     */
    private static final int ORBIT_LINE = 255;

    /**
     * How many 10-tick samples the post-descent leg watches for a bounce back into space. The entry
     * on-ramp is evaluated on every flight-computer tick, so an unheld trigger fires within a tick
     * or two of the ship being above the line under power — this is many times the window it needs,
     * so a green means "it did not happen", not "we did not look long enough".
     */
    private static final int LATCH_WATCH_SAMPLES = 40;

    /** Seat and standing square, as offsets from the ship's FLIGHT COMPUTER (the deck layout). */
    private static final int[] OFF_SEAT = {1, 0, 0};
    private static final int[] OFF_STAND = {1, 0, 1};
    /**
     * The navigation console, as an offset from the flight computer: two cells beyond the seat over
     * the one square the drive bay leaves to stand on, so the seated pilot has a clear sightline to
     * it without leaving his seat.
     */
    private static final int[] OFF_NAV = {1, 0, 2};

    /** Vanilla eye height for a standing player — a raytrace starts here, not at the feet. */
    private static final double EYE_HEIGHT = 1.62;

    /** The server drops a block interaction beyond (reach + 3), so the bot must observably be closer. */
    private static final double MAX_INTERACT_DIST_SQ = 64.0;

    /**
     * The use key's code. Mouse buttons enter {@code KeyBinding} as {@code -100 + button}, so RMB is
     * {@code -99} — the code the real mouse handler writes and the default binding of
     * {@code keyBindUseItem}.
     */
    private static final int KEY_USE_ITEM = -99;

    /** Button ids the assembler assigns its own controls: 0 = Scan, 1 = Build. */
    private static final int BUTTON_SCAN = 0;
    private static final int BUTTON_BUILD = 1;

    /**
     * Button ids the navigation console assigns its own controls: 5 = ARM, and one "pick" button per
     * listed address starting at 10.
     */
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_PICK_FIRST = 10;

    /** How many addresses the console puts a pick button next to. */
    private static final int LISTED_ADDRESSES = 4;

    /** The console's own slots, in container numbering: 0 = the source slot, 1 = the SHIP's slot. */
    private static final int CONSOLE_SLOT_SHIP = 1;

    /** The item the address list lives on. */
    private static final String CRYSTAL_ITEM = "advancedrocketry:memoryCrystal";

    private Path root;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue("Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled - set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        root = Files.createTempDirectory("forge-m1-milestone-");
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Both knobs are seeded BEFORE the server boots, because the config is read once at load.
        // The file key is `rocketsRequireFuel`; the field (and the probe that reads it back) is
        // `rocketRequireFuel` — the assertion below is what proves this file was actually parsed
        // rather than silently ignored for a syntax the config reader did not recognise.
        String cfg = "# seeded by the milestone e2e\n"
                + "rockets {\n"
                + "    B:rocketsRequireFuel=false\n"
                + "    I:orbitHeight=" + ORBIT_LINE + "\n"
                + "}\n";
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"), cfg.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startFailed) {
            serverHarness.close();
            serverHarness = null;
            throw startFailed;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception first = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                first = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
            serverHarness = null;
        }
        if (first != null) {
            throw first;
        }
    }

    @Test
    public void aPlayerBuildsHisShipAtTheAssemblerBoardsItAndFliesItOffThePlanet() throws Exception {

        // THE MULTIPLIER STAYS. What it waits on is VS building the ship on its OWN thread, off the
        // game loop: that work finishes in wall-clock time, so a busy box genuinely needs more game
        // ticks to elapse before it is done. Measured at 8 forks on the sibling gate test.
        int budget = (int) (40 * TestTimeouts.factor());
        // The server's ordered log, opened at the top of the loop rather than at leg 4. Every leg
        // from the boarding onward reads it now — the seat's verdict, the console's, the crossings'
        // — and a reader that only exists from halfway down is one more reason for the early legs to
        // keep polling values.
        Events events = new Events(this::exec, bot()::waitTicks);
        long tLeg = System.currentTimeMillis();

        // Leg 4 says why, and it holds for the whole loop: an observer is aboard the whole way, so
        // keeping the ship loaded is production's job here — a harness affordance doing it would
        // hide the failure to.
        zmaster587.advancedRocketry.test.ShipReadiness.letShipsUnload(this::exec,
                "this milestone walks a player's own loop with an observer aboard, and production is"
                + " what must keep the ship loaded across the crossing");

        // ---- LEG 0: the world this loop is walked in is the one the test asked for. -------------
        String fuelCfg = exec("artest config get rocketRequireFuel");
        requireArranged("the seeded config file must have been PARSED. This reads the live "
                        + "field back through the same config object production uses, so a stale "
                        + "`true` here means the file was written in a syntax the config reader "
                        + "skipped and every later leg would be running against defaults: " + fuelCfg,
                fuelCfg.contains("\"value\":false"));

        String status = exec("artest space subsystem-status");
        requireArranged("the production space subsystem must be REGISTERED — it owns the "
                        + "cells a ship arrives in, and without it the climb leg has nowhere to go: "
                        + status,
                status.contains("\"registered\":true"));
        // No dimension id may be owned twice. A space slot is a void world the subsystem rebinds at
        // will; a body is a place the universe registry describes, a crystal can name and a ship can
        // be flown to. One id doing both makes the registry's description a lie — it advertises a
        // planet whose world is empty space — and the descent that follows lands the ship nowhere.
        Reply subsystem = Reply.of("artest space subsystem-status", status);
        requireArranged("subsystem-status must report the slot/body id overlap: " + status,
                subsystem.has(SLOT_DIMS_ALSO_BODIES));
        int[] collided = subsystem.intArray(SLOT_DIMS_ALSO_BODIES);
        assertTrue("no space-slot dimension may also be an Advanced Rocketry body. Forge's free-id "
                        + "scan cannot see AR's own body ids (a surface-less body is never registered "
                        + "with Forge), so the pool can take one unless it asks AR too. "
                        + "slotDimsAlsoBodies=" + java.util.Arrays.toString(collided)
                        + " status=" + status,
                collided.length == 0);
        System.out.println("[M1] leg 0 (config + subsystem) " + elapsed(tLeg) + " status=" + status);

        // ---- LEG 1: stand the craft up on a pad. Blocks only — no interaction happens here. -----
        tLeg = System.currentTimeMillis();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);
        int[] builderPos = placeFixture();
        System.out.println("[M1] leg 1 (fixture placed) " + elapsed(tLeg)
                + " builder=" + describe(builderPos));

        // ---- LEG 2: the CLIENT turns that pile of blocks into a ship, at the machine's screen. --
        tLeg = System.currentTimeMillis();
        int ships = assembleThroughTheAssemblersScreen(builderPos, budget);
        assertTrue("the craft a player built on the pad must become a SHIP when he presses Scan and "
                        + "then Build on the assembler's own screen. This is the whole tier-2 build "
                        + "route as a human drives it, and its completion signal has to be the ship "
                        + "itself: a tier-2 craft spawns no rocket entity, so the rocket list would "
                        + "report a healthy nothing. ships=" + ships
                        + " spawnDiag=" + exec("artest vs spawn-diag")
                        + " builderEnergy=" + energyAt(builderPos),
                ships >= 1);
        System.out.println("[M1] leg 2 (client-driven assembly) " + elapsed(tLeg) + " ships=" + ships);

        // ---- LEG 3: the CLIENT sits down in the seat of the ship he just built. -----------------
        tLeg = System.currentTimeMillis();
        PilotSeat found = findSeat();
        requireArranged("the assembled ship must expose a pilot seat AND the flight computer "
                        + "it was linked to — the deck square the pilot works from is addressed from "
                        + "that computer; searched yard " + found.describeYard() + ": " + found.raw(),
                found.found && found.hasAfc);
        int[] seatSub = {found.seatX, found.seatY, found.seatZ};
        int[] afcSub = {found.afcX, found.afcY, found.afcZ};

        // The craft's DURABLE name, read off its own flight computer while that computer is still
        // here to ask. The physics id captured at assembly dies at the first crossing, and this run
        // crosses three times; the durable name rides in the tile's NBT through every one of them,
        // so it is what leg 6 and the arrival-side seat lookups are keyed on. Without it those two
        // fell back to "the first settled row in the cell" and "whatever is nearest the pilot".
        String nameReply = exec("artest vs ship-name 0 " + describeArgs(afcSub));
        requireArranged("the built ship's flight computer must carry a durable name, or nothing"
                + " after the first crossing can be addressed to this craft: " + nameReply,
                nameReply.contains("\"found\":true"));
        builtShipName = readString(nameReply, SHIP_ID);

        // The subspace copy is a RIGID relocation of the pad build, so the seat must sit at exactly
        // the offset it was built at. Without this control the deck square the pilot is placed on
        // below is a guess, and a failed press could just as easily be a bot standing nowhere.
        requireArranged("CONTROL: the ship's subspace copy must preserve the build's own "
                        + "geometry — seat minus flight computer should be " + describe(OFF_SEAT)
                        + " but is " + describe(new int[]{seatSub[0] - afcSub[0],
                                seatSub[1] - afcSub[1], seatSub[2] - afcSub[2]}) + ": " + found,
                seatSub[0] - afcSub[0] == OFF_SEAT[0]
                        && seatSub[1] - afcSub[1] == OFF_SEAT[1]
                        && seatSub[2] - afcSub[2] == OFF_SEAT[2]);

        // A held stack consumes the use press before the block ever sees it.
        emptyTheHand();

        Aim seatAim = aimAt(afcSub, seatSub, OFF_STAND, 0.5, 0.2, 0.5, budget);
        assertAimed(seatAim, seatSub, "pilot seat", "pilotseat");

        // THE PRESS, THE SEAT'S VERDICT, AND THE MOUNT — three links that a single "is he riding yet"
        // poll reports as one sentence. A press that never reached the block, a seat that refused
        // because it does not consider itself part of a ship, and a mount that happened but never
        // replicated to the client are three different faults with three different owners, and the
        // old loop timed out identically on all of them.
        long seatMark = events.mark();
        long seatClientMark = clientEvents().mark();
        pressUse();
        String sitDecision = events.await(seatMark, "pilot_seat_sit_decided",
                "a real use-key press aimed at the ship's PILOT SEAT must REACH that seat — the "
                        + "crosshair was proven to be on that very block above, so a silence here is "
                        + "the interaction never arriving rather than a missed aim" + seatAim.diagnosis,
                budget * 5);
        assertTrue("…and the seat must know it belongs to a SHIP. An unmanaged seat is a chair: it "
                        + "seats him and carries no flight input at all, which is a green boarding "
                        + "followed by a craft that will not answer its controls. decision="
                        + sitDecision, Boolean.parseBoolean(Events.text(sitDecision, "managed")));

        JsonObject riding = awaitClientMount(seatClientMark,
                "a real use-key press aimed at the ship's PILOT SEAT must seat the pilot, as the "
                        + "CLIENT itself performs it — the crosshair was proven to be on that block "
                        + "and the seat agreed it belongs to a ship, so a silence here is the mount "
                        + "never happening on his side", budget, seatAim.diagnosis);
        String serverRiding = exec("artest player riding-entity");
        assertTrue("a real use-key press aimed at the ship's PILOT SEAT must seat the pilot, as the "
                        + "CLIENT itself renders him. The crosshair was proven to be on that very "
                        + "block, so a red here is the interaction being refused rather than a missed "
                        + "aim. clientRiding=" + riding + " serverRiding=" + serverRiding
                        + seatAim.diagnosis,
                isRiding(riding));
        assertTrue("…and what he is riding must be the seat's own mount entity, not some other body "
                        + "he happened to bump into. clientRiding=" + riding,
                riding.has("entityClass")
                        && riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertTrue("…and the SERVER must agree he is aboard that same mount — a seat that exists only "
                        + "on the client carries no input at all. serverRiding=" + serverRiding
                        + " clientRiding=" + riding,
                serverRiding.contains("EntityDummy"));
        System.out.println("[M1] leg 3 (boarded by key press) " + elapsed(tLeg) + " riding=" + riding);

        // ---- LEG 4: he flies it off the planet, by HOLDING the key. -----------------------------
        // No permaload here on purpose: a client e2e already has an observer aboard the ship, and a
        // harness affordance that kept the ship loaded would hide a production failure to keep its
        // own ship loaded during the crossing.
        tLeg = System.currentTimeMillis();
        // The entry as the server's own chain of events, awaited while the key is held: the hull is
        // cut into the cell, the gate records STARTED, the pose is written, the crew is put back, the
        // ledger is told. The old form polled the ledger's SIZE and reported "ledger=0" for a
        // refusal, a failed cut, a stalled re-seat and a slow climb alike.
        // THE MULTIPLIER STAYS: a held key is sampled and re-sent per CLIENT TICK (on change, plus a
        // re-assert every PilotInputCadence.REPEAT_TICKS), so a loaded box stretches the climb through
        // the client's TICK rate. NOT per rendered frame - that reading was refuted 2026-08-21.
        // 4 000 ticks is the old 800 polls of 5.
        long entryMark = events.markInstrumented();
        long entryClientMark = clientEvents().mark();
        int climbBudget = (int) (4000 * TestTimeouts.factor());
        bot().holdKey(Keyboard.KEY_R);
        try {
            events.assertChain(entryMark, "a ship climbing under its own power past the orbit line ("
                    + ORBIT_LINE + ") must be taken by the entry crossing and SETTLE in a space cell —"
                    + " that is the on-ramp a real player flies, and holding one key is the whole of"
                    + " his input", climbBudget, Chains.GRANTED_ENTRY);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        String statusAfter = exec("artest space subsystem-status");
        String entryDecisions = events.since(entryMark, "entry_decided");
        assertTrue("the entry gate must have GRANTED this entry (STARTED): " + entryDecisions
                        + " status=" + statusAfter,
                entryDecisions.contains("\"decision\":\"STARTED\""));
        System.out.println("[M1] leg 4 (powered climb to the cell) " + elapsed(tLeg)
                + " status=" + statusAfter);

        // ---- LEG 5: the arrival, measured from the CLIENT. --------------------------------------
        tLeg = System.currentTimeMillis();
        Reply after = Reply.of("artest space subsystem-status", statusAfter);
        requireArranged("subsystem-status must list its slot dims: " + statusAfter,
                after.has(SLOT_DIMS));
        String slotDims = "," + joinInts(after.intArray(SLOT_DIMS)) + ",";

        // (1) The client's OWN world is a space cell — the pilot followed his ship through the seam
        // or he did not, and nothing server-side can answer that for him. Read off the client's own
        // record of its dimension changes since the climb began, in order: a world rebuilt twice
        // between two samples shows one change or none, and the records show both.
        int clientDim = awaitClientWorld(entryClientMark, slotDims,
                "after the crossing the CLIENT itself must be in a space-cell dimension — a pilot "
                        + "whose ship left without him is the exact failure this leg exists to catch."
                        + " slotDims=[" + slotDims + "] status=" + statusAfter,
                budget * 5);

        // (2) Still seated — and the crossing's own seat chain says how.
        JsonObject arrivalRiding = assertStillSeated(events, entryMark, entryClientMark,
                "the pilot who FLEW his own ship into space must still be in his seat on arrival — a "
                        + "crossing must never stand him up. clientDim=" + clientDim
                        + " delivery=" + exec("artest vs seat-delivery"),
                budget);
        System.out.println("[M1] leg 5 (arrival, client-observed) " + elapsed(tLeg)
                + " clientDim=" + clientDim + " riding=" + arrivalRiding);

        // ---- LEG 6: the pilot picks a destination and arms the jump, at the console, by hand. ---
        tLeg = System.currentTimeMillis();
        int slotDim = clientDim;

        // The ship as the ledger knows it, and the cell it is about to leave. The default galaxy is
        // generated from a wall-clock seed, so NOTHING about the destination may be assumed: every
        // cell and dimension this leg and the next work with is READ from the world the pilot is in.
        // NAMED. Without the id this asked for "the first settled row bound to this slot", which is
        // an iteration order over every ship the ledger holds — and the answer to a question about
        // somebody else's craft is indistinguishable from the answer to this one.
        String afcProbe = exec("artest space find-afc " + slotDim + " " + builtShipName);
        requireArranged("the ship the pilot flew up must be findable in the space cell he "
                        + "arrived in — the ledger says he is here, so a ship that cannot be located "
                        + "means the arrival left no body behind: " + afcProbe,
                afcProbe.contains("\"found\":true"));
        String shipId = readString(afcProbe, SHIP_ID);
        String launchCell = readString(exec("artest space ledger-get " + shipId), CELL);
        requireArranged("the ledger must name the cell the jump departs FROM — without it "
                        + "the arrival assertion below cannot tell a jump from a no-op. shipId="
                        + shipId + " ledger=" + exec("artest space ledger-get " + shipId),
                launchCell != null && !launchCell.isEmpty());

        // Read while he is still SEATED, so the ship's own world point is on record before the one
        // moment in this loop when the pilot is not a reliable pointer to his craft.
        String seatProbe = findSeatAboard(slotDim, budget);
        int[] navAfcSub = readTriple(seatProbe, "afcX", "afcY", "afcZ");
        requireArranged("the arrived ship must still expose the seat and the flight computer "
                        + "it is linked to — the console the pilot reaches for is addressed from that "
                        + "computer: " + seatProbe,
                navAfcSub != null);
        int[] navSub = add(navAfcSub, OFF_NAV);

        // The drive's capacitor holds no charge on a freshly built craft and this ship carries no
        // generator, so the window it needs is paid for here. Seeding stored energy is the same class
        // of arrangement as the assembler's power above — a player flies with a charged drive, and
        // the acts this leg is about are the crystal, the two clicks and the key press.
        String charged = exec("artest drive charge " + slotDim + " " + describeArgs(navAfcSub) + " full");
        System.out.println("[M1] drive charged: " + charged);

        // He stands up to navigate. Not a convenience: while a pilot is at the controls the client
        // holds his view ON THE SHIP'S ATTITUDE every tick, so his crosshair is locked dead ahead and
        // level and cannot be put on anything — the console included. Navigation is therefore deck
        // work, which is what the one clear square between the seat and the console is for. Standing
        // up is an ACT, so it is a real key: sneak, the way anyone leaves a vehicle.
        standUp(events, budget);

        // The crystal is handed over the way any item is handed to a player; putting it IN the console
        // is the act, and it is the act that seeds the addresses (nothing else in the game does).
        exec("give @a " + CRYSTAL_ITEM + " 1");
        // Hold nothing: the console is opened with a bare hand, so nothing can eat the use press, and
        // the crystal is then moved by real slot clicks rather than by being used from the hand.
        holdNothing(budget);

        String consoleScreen = openConsoleFromTheDeck(slotDim, navAfcSub, navSub, budget);
        assertTrue("a real use-key press aimed at the NAVIGATION CONSOLE must open its screen on the "
                        + "client — every choice the pilot makes about where to fly is made on that "
                        + "screen, so a console that swallows the press strands a jump-capable ship. "
                        + "screen=\"" + consoleScreen + "\"",
                consoleScreen.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        // Move the crystal into the SHIP slot with an explicit pick-up / put-down pair. A shift-click
        // would merge it into the source slot instead, where the address list is never read from —
        // silently, with no error — so the two clicks are what a player actually performs here.
        JsonObject slots = bot().reportSlots();
        int crystalSlot = slotHolding(slots, CRYSTAL_ITEM);
        requireArranged("the crystal handed to the pilot must be reachable from the console's "
                        + "own window — the console shows the hotbar and nothing else, so a crystal "
                        + "anywhere but the hotbar could never be inserted. slots=" + slots,
                crystalSlot >= 0);
        bot().clickSlot(crystalSlot, 0, "PICKUP");
        bot().waitTicks(5);
        bot().clickSlot(CONSOLE_SLOT_SHIP, 0, "PICKUP");

        // A WINDOW, then ONE READ — and NOT a wait for `crystal_copied`, which is a different
        // mechanic entirely. Measured 2026-09-12, by getting this wrong: the console's copy
        // (`copySourceIntoShipCrystal`) has exactly ONE caller in production, the COPY button
        // (`useNetworkData`, `NET_COPY`), and it merges the SOURCE slot into the ship's crystal. It
        // is not what an insertion triggers, and nothing triggers it here. The ship's address list
        // is not the product of any action at all: `shipCrystal()` reads
        // `memoryOf(getStackInSlot(SLOT_SHIP))`, so once the click has been applied the list simply
        // IS what the crystal in that slot knows.
        //
        // So there is no link to await, and a poll would be asking until the answer looks right. The
        // window bounds what a slot click costs — one packet to the server and the container's own
        // apply — and the read after it is the measurement.
        bot().waitTicks(SLOT_APPLIED_TICKS);
        String navStatus = exec("artest nav status " + slotDim + " " + describeArgs(navSub));
        int listed = readInt(navStatus, NAV_SHIP_ADDRESSES, 0);
        assertTrue("putting a memory crystal into the console's SHIP slot must give the pilot a list "
                        + "of places he can fly to — the ship's addresses ARE that crystal's, so an "
                        + "empty list here means the insertion never landed, and a list of ONE leaves "
                        + "him only the cell he is already in. listed=" + listed
                        + " nav=" + navStatus + " slots=" + bot().reportSlots()
                        + " | screen=" + screenOf(bot().reportState()),
                listed >= 2);

        // Reopen the window: its buttons are built when the screen is, so the list the pilot clicks
        // on is the one he sees after the crystal is in. Closing and looking again is what he does.
        bot().closeScreen();
        bot().waitTicks(10);
        consoleScreen = openConsoleFromTheDeck(slotDim, navAfcSub, navSub, budget);
        requireArranged("the console must reopen once the crystal is in it: " + consoleScreen,
                consoleScreen.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        // He picks where to go. Not blind: he clicks an address, sees what the console says is at it,
        // and moves on if that is not somewhere he can land — which is what the pick button's own body
        // readout is for. Every pick is a real button click; only the "what is there" is read by probe.
        // Entry 0 is skipped outright: it is the ship's own home cell, and the gate refuses a jump to
        // the cell you are already in.
        int pickIndex = -1;
        int targetDim = Integer.MIN_VALUE;
        String targetCell = "";
        String targetInfo = "";
        StringBuilder considered = new StringBuilder();
        for (int candidate = 1; candidate < LISTED_ADDRESSES && pickIndex < 0; candidate++) {
            // The CLICK IS AWAITED, not slept off. Ten ticks was a guess that has to cover a button
            // press travelling to the server and the computer answering it; when it did not, the
            // reading below was of the PREVIOUS candidate's target and the search silently
            // considered the same address twice.
            long pickMark = events.mark();
            bot().clickButtonById(BUTTON_PICK_FIRST + candidate);
            events.await(pickMark, "nav_target_picked",
                    "a click on a listed address must reach the navigation computer — the console's "
                            + "buttons are the only way a pilot chooses where to go, and a press that "
                            + "is swallowed leaves him reading the target he picked last time",
                    budget * 5);
            String picked = exec("artest nav status " + slotDim + " " + describeArgs(navSub));
            String cell = unquote(readString(picked, NAV_TARGET));
            considered.append(' ').append(candidate).append("->").append(cell)
                    .append(picked.substring(Math.max(0, picked.indexOf('{'))));
            if (cell.isEmpty() || "null".equals(cell) || cell.equals(launchCell)) {
                continue;
            }
            if (isLandable(picked)) {
                pickIndex = candidate;
                targetCell = cell;
                targetDim = readInt(picked, NAV_TARGET_DIM, Integer.MIN_VALUE);
                targetInfo = picked;
            }
        }
        assertTrue("the addresses a starter crystal gives a pilot must include somewhere he can "
                        + "actually FLY TO AND LAND ON — a list of destinations none of which holds a "
                        + "planet is a map with no places on it, and the milestone loop has nowhere to "
                        + "go. considered=" + considered,
                pickIndex >= 0);
        assertTrue("a listed address must name a BODY, not merely a point in space — that identity is "
                        + "what keeps the ship aimed at the planet the pilot chose while it flies, and "
                        + "without it the console is handing him a coordinate the destination has "
                        + "already left. nav=" + targetInfo,
                targetDim != Integer.MIN_VALUE && targetDim >= 0);
        bot().waitTicks(10);
        long armMark = events.mark();
        bot().clickButtonById(BUTTON_ARM);

        // The console's OWN VERDICT on the press, not a poll of the flag it sets. `arm()` answers
        // ARMED or REFUSED_NO_TARGET, and the difference is the whole diagnosis: a poll that spends
        // its budget reports "armed=false" for a press that never arrived, for a press that arrived
        // and was refused, and for a console that armed a tick after the last sample — three
        // different faults behind one sentence.
        String armDecision = events.await(armMark, "nav_arm_decided",
                "pressing ARM must reach the navigation computer and be ANSWERED — that press is how "
                        + "a player commits to a destination, and an unarmed ship refuses the jump "
                        + "key outright", budget * 5);
        String armedStatus = exec("artest nav status " + slotDim + " " + describeArgs(navSub));
        assertTrue("picking a listed address and pressing ARM must leave the ship ARMED at that "
                        + "address — those two clicks are the whole of how a player commits to a "
                        + "destination. The console's own verdict on the press: " + armDecision
                        + " nav=" + armedStatus,
                armDecision.contains("\"outcome\":\"ARMED\"")
                        && "true".equals(readString(armedStatus, NAV_ARMED)));
        // A second clause stood here: that the pilot is TOLD, in his own chat, that the ship is
        // armed. It is gone — the chat line is a rendering of the arming, and the arming itself is
        // what the line above reads, off the navigation computer's own state.

        // Judged on the BODY, not on the aim cell. The aim is the computer's prediction of where that
        // body will be when the ship arrives, so it legitimately moves between the pick and the arm —
        // the planet is orbiting. What must NOT change is which planet.
        assertTrue("…and the destination it is armed at must still be the BODY he picked — an ARM that "
                        + "quietly re-aimed the ship would send him somewhere he never chose. "
                        + "pickedDim=" + targetDim + " nav=" + armedStatus,
                targetDim == readInt(armedStatus, NAV_TARGET_DIM, Integer.MIN_VALUE));
        assertTrue("…and the ship must still be able to say WHERE that body is: an armed jump whose "
                        + "target cannot be located is a burst about to be spent on nothing. nav="
                        + armedStatus,
                armedStatus.contains("\"targetResolved\":true"));
        System.out.println("[M1] leg 6 (target picked + armed at the console) " + elapsed(tLeg)
                + " launchCell=" + launchCell + " pick=" + pickIndex + " targetDim=" + targetDim
                + " target=" + targetCell
                + " targetInfo=" + targetInfo + " considered=" + considered
                + " drive=" + exec("artest drive info " + slotDim + " " + describeArgs(navAfcSub)));

        // ---- LEG 7: he fires the jump with the real jump key. -----------------------------------
        tLeg = System.currentTimeMillis();
        // The key handler bails outright while any screen is up, so the console is shut first — the
        // same thing a player does before reaching for the controls.
        bot().closeScreen();
        bot().waitTicks(10);

        // And he sits back down: the jump key is the PILOT's, routed through the seat he occupies, so
        // a player standing on his own deck cannot fire the drive he just armed.
        JsonObject reboarded = sitBackDown(events, slotDim, navAfcSub, budget);
        requireArranged("the pilot must be able to take his seat again after navigating — the "
                        + "jump he armed is fired from the controls, not from the deck. riding="
                        + reboarded + " serverRiding=" + exec("artest player riding-entity"),
                isRiding(reboarded));

        // The mark BEFORE the key: the arrival's ledger write is awaited from here.
        long jumpMark = events.markInstrumented();
        // ...and the CLIENT's own, for the same instant. The pilot's side of a jump is a world he is
        // carried into, and that is a record on his log, not a value to sample afterwards.
        long jumpClientMark = clientEvents().mark();
        pressJumpKey();
        // Two legitimate branches, both real player paths: a clean ship spools straight up, and a
        // ship the gate has only an ADVISORY about asks for a second press to confirm. WHICH one
        // this run took is read off the trigger's own verdict — the press is answered with one of
        // eight named outcomes, and `jump_press_decided` carries the name.
        //
        // This used to be decided by looking for the words "spooling" and "confirm" in the client's
        // chat. That is a rendering of this verdict: it moves with the language file, it cannot see
        // a line the ring has dropped, and it made the milestone's own control flow depend on prose.
        String pressed = events.awaitField(jumpMark, "jump_press_decided","phase", "press",
                "the jump key, pressed by a seated pilot of an ARMED ship, must be ANSWERED — the"
                        + " trigger decides something for every press, and a silence here means the"
                        + " press never reached the ship at all",
                JUMP_PRESS_BUDGET_TICKS);
        String outcome = Events.text(Events.lastRecord(pressed), "outcome");
        String jumpBranch = "spooling";
        if (!"SPOOLING".equals(outcome)) {
            assertEquals("a press that did not spool must be the gate's ADVISORY, which is an 'are"
                            + " you sure' rather than a refusal — anything else is the jump being"
                            + " turned down. presses=" + pressed
                            + " delivery=" + exec("artest vs seat-delivery")
                            + " riding=" + bot().reportRidingEntity()
                            + " drive=" + exec("artest drive info " + slotDim + " "
                            + describeArgs(navAfcSub)),
                    "WARNED", outcome);
            jumpBranch = "confirm-then-commit";
            long confirmMark = events.mark();
            pressJumpKey();
            String confirmed = events.awaitField(confirmMark, "jump_press_decided","phase", "press",
                    "the CONFIRMING press must reach the trigger too", JUMP_PRESS_BUDGET_TICKS);
            assertEquals("…and the CONFIRMING press must spool the drive: the gate's advisory is an"
                            + " 'are you sure', not a refusal, so a second press has to carry the"
                            + " ship. presses=" + confirmed + " drive="
                            + exec("artest drive info " + slotDim + " " + describeArgs(navAfcSub)),
                    "SPOOLING", Events.text(Events.lastRecord(confirmed), "outcome"));
        }
        System.out.println("[M1] jump branch: " + jumpBranch + " firstPress=" + pressed);

        // The flight is short but the wind-up is not instant. The arrival is the ledger's own WRITE:
        // `ledger_settled` for this ship, since the mark taken before the fire button — whichever
        // mechanism the drive chose (a hyperspace flight, or the direct crossing a short hop is
        // performed as), it ends by telling the ledger where the ship now is. The record names the
        // cell; a poll of the ledger's row could only see the row once it had changed.
        // No fork multiplier: a jump's duration is distance over speed, which is a number of
        // server TICKS fixed by the game. Scaling it by how many forks share this box granted the
        // flight extra world on a busy machine and made two runs different experiments. 2 000 ticks
        // is the old 400 polls of 5.
        String arrivedCell = launchCell;
        String settled = events.await(jumpMark, "ledger_settled", "a jump the pilot armed and fired"
                + " must end with the ledger told where the ship now is — the arrival's own commit",
                2000);
        for (String record : Events.recordsWhere(settled, "ship", shipId)) {
            String cell = Events.text(record, "cell");
            if (cell != null && !cell.isEmpty()) {
                arrivedCell = cell;
            }
        }
        String ledgerAfterJump = exec("artest space ledger-get " + shipId);
        assertTrue("a jump the pilot armed and fired must MOVE the ship to another cell — the whole "
                        + "point of the hyperdrive is that the craft is somewhere else afterwards. "
                        + "launchCell=" + launchCell + " arrivedCell=" + arrivedCell
                        + " target=" + targetCell + " ledger=" + ledgerAfterJump
                        + " status=" + exec("artest space subsystem-status"),
                !arrivedCell.equals(launchCell));

        // THE promise of the loop: the pilot picked a planet, and when the drive stops he is at that
        // planet. Asserted on the BODY rather than on the cell string armed earlier, because the
        // planet moves while the ship flies — that is precisely why the computer aims ahead of it.
        // A ship aimed at where the body WAS lands in a cell the body has left, which reads here as
        // an arrived cell whose own body list does not contain the destination.
        CellInfo arrivedCellInfo = CellInfo.atKey(this::exec, arrivedCell);
        // The AT-CELL list, read as a list. What stood here took the field's rendered text, stripped
        // its brackets, and asked whether `"dim":N,` appeared in what was left — a substring of a
        // rendering of an array, pinning that `dim` is written first in each body and followed by a
        // comma. Neither is part of any contract.
        boolean destinationIsHere = false;
        for (CellInfo.Body body : arrivedCellInfo.cellBodies) {
            destinationIsHere |= body.dim == targetDim;
        }
        String arrivedBodies = String.valueOf(arrivedCellInfo.cellBodies);
        assertTrue("…and the cell it arrives in must be the one the destination BODY is in when it "
                        + "gets there — a destination the pilot chose at the console is a promise the "
                        + "drive has to keep, and it is only kept if the planet is there on arrival. "
                        + "The navigation computer aims at where the body WILL be, so a red here is "
                        + "that prediction being wrong (or absent): compare the cell armed at leg 6 "
                        + "with where the body actually is now. targetDim=" + targetDim
                        + " armedCell=" + targetCell + " arrivedCell=" + arrivedCell
                        + " arrived=" + arrivedCellInfo + " ledger=" + ledgerAfterJump,
                destinationIsHere);

        // The pilot, observed from the CLIENT: same two questions leg 5 asks, because a jump is the
        // second world transition of the loop and a seat lost in it is lost just as silently.
        String statusAfterJump = exec("artest space subsystem-status");
        Reply afterJump = Reply.of("artest space subsystem-status", statusAfterJump);
        requireArranged("subsystem-status must list its slot dims: " + statusAfterJump,
                afterJump.has(SLOT_DIMS));
        String jumpSlotDims = "," + joinInts(afterJump.intArray(SLOT_DIMS)) + ",";
        int jumpDim = awaitClientWorld(jumpClientMark, jumpSlotDims,
                "the pilot who fired the jump must come out of it in a space cell too — a drive "
                        + "that carries the hull and leaves the crew behind has not moved the SHIP. "
                        + "slotDims=[" + jumpSlotDims + "] ledger=" + ledgerAfterJump
                        + " status=" + statusAfterJump,
                budget * 5);

        JsonObject jumpRiding = assertStillSeated(events, jumpMark, jumpClientMark,
                "the pilot who FIRED the jump must still be in his seat when it ends — he never "
                        + "stood up, so nothing about crossing a cell may stand him up. A red here is "
                        + "the crew capture, the re-seat, or the dimension hand-off, in that order — "
                        + "and the mount chain below says which. clientDim=" + jumpDim
                        + " delivery=" + exec("artest vs seat-delivery")
                        + " ledger=" + ledgerAfterJump,
                budget);
        System.out.println("[M1] leg 7 (jump fired on the key) " + elapsed(tLeg)
                + " branch=" + jumpBranch + " " + launchCell + " -> " + arrivedCell
                + " clientDim=" + jumpDim + " riding=" + jumpRiding);

        // ---- LEG 8: he takes the ship down to the planet, on a held key. ------------------------
        tLeg = System.currentTimeMillis();
        // What the flight computer will see when it looks around this cell. It takes the NEAREST body
        // inside the descent radius, so that is the one the test must load and the one it must judge —
        // reading it here rather than deciding it keeps the test measuring the production choice.
        String bodies = exec("artest space bodies");
        int nearestDim = nearestDescendTargetDim(bodies);
        // The cell he left is the control: it is a body's cell too (he took off from it), so if BOTH
        // read empty the registry cannot attribute any cell, and if only the destination does, the
        // console offered an address the rest of the game does not agree exists.
        String arrivedInfo = exec("artest space cell-info " + cellArgs(arrivedCell));
        String launchInfo = exec("artest space cell-info " + cellArgs(launchCell));
        assertTrue("an address the navigation console OFFERED, and the drive actually flew the pilot "
                        + "to, must still have at it the body whose address it was WHEN HE GETS "
                        + "THERE. A crystal that lists a planet, a gate that clears the jump to it and "
                        + "a drive that spends the charge, all ending at a cell the game reports as "
                        + "empty, strand the ship: the descent trigger reads this same list and can "
                        + "never fire. If the cell held a body when the target was armed and holds "
                        + "none now, the body MOVED while the ship was in flight — compare the "
                        + "targetInfo leg 6 printed against `arrived` below, and see which cell the "
                        + "destination's dimension occupies now. arrivedCell=" + arrivedCell
                        + " arrived=" + arrivedInfo
                        + " launchCell(control)=" + launchCell + " launch=" + launchInfo
                        + " bodies=" + bodies
                        // The ship section of `space bodies` is built from the LEDGER, so an empty
                        // one means this craft has no row — and the loudest way a row disappears
                        // between the arrival and here is a descent having ALREADY fired. That is not
                        // a failure of this leg's subject, it is this leg's subject having happened
                        // without it; the two read identically off `shipCount:0` alone.
                        + " | descents requested since the jump: "
                        + events.since(jumpMark, "descent_requested")
                        + " | ledger removals since the jump: "
                        + events.since(jumpMark, "ledger_removed"),
                nearestDim != Integer.MIN_VALUE);

        // Pin the destination world. The descent resolver asks Forge for it and refuses in silence if
        // it is not loaded — a reading/arrangement probe, not an act: in a live game the world is
        // already up because somebody lives there.
        String loaded = exec("artest dim load " + nearestDim);
        requireArranged("the destination world must be loaded before the descent is attempted, "
                        + "or the resolver refuses quietly and the leg measures nothing: " + loaded,
                loaded.contains("\"loaded\":true"));

        // He CLOSES THE RANGE, then descends. A jump does not end on top of its destination: it ends
        // on a standoff ring around it, outside the descent trigger on purpose, because arriving in a
        // system is not the same act as landing on a world. Flying that last stretch is the pilot's,
        // and it is part of the loop this test exists to walk.
        //
        // His instruments are the ones his own cell sky gives him — WHICH WAY the body lies (the sky
        // draws each body along the observer→body direction) and HOW FAR (waves 2-3 put the range on
        // its label) — and the six translation keys: nose (W/S), lateral (Q/E), vertical (R/F). He
        // points at the largest part of the offset and flies it off, then looks again. Flight assist
        // ramps a velocity SETPOINT rather than thrusting directly, so every burst is followed by a
        // throttle cut — without it the ship keeps coasting and the next reading measures the
        // previous burst.
        //
        // The search doubles as this leg's positive control. Under the old arrangement the ship
        // arrived at zero range and the descent fired on the first tick of any input, so the leg
        // never established that a pilot can reach a body at all — it measured the arrival, not the
        // approach. If the range never falls now, the assertion below says so before the descent
        // assertion gets a chance to blame the trigger.
        //
        // WHY THE DIRECTION AND NOT JUST THE RANGE — three refuted designs' worth of reason.
        //
        // The craft flies a STRAIGHT LINE on a held key (the pilot commands no rotation here), so one
        // key can only ever null its OWN component of the offset. A search that keeps "whichever key
        // still closes the range" never LEAVES the first axis it tried: once past that axis's closest
        // approach, the opposite key of the SAME axis closes the range again, so the two ping-pong
        // across the foot of the perpendicular forever. The residual they never attack is
        // 1024·sin(bearing) against a 512-block trigger, and the bearing is drawn fresh each run from
        // the ship's id — that shape reaches the body on about a third of the draws and orbits at
        // ~740 blocks on the rest.
        //
        // Working the three axes in TURN fixes that for a body that HOLDS STILL, and only for one.
        // The destination generally does not: a moon orbits its planet at up to 0.75 blocks/tick
        // (`6.545·√(g/orbitalDist)`, with the generator drawing orbitalDist from [100,199] and a
        // parent gravity of at most 1.3). The craft out-runs that comfortably — 240 blocks a burst
        // against at most 157 — but a blind search cannot SPEND that speed: while it takes ten
        // bursts to close the lateral component, the body's own motion re-opens the nose component by
        // more than the next pass can recover, and the range settles into a limit cycle around
        // 740-1100 blocks. Measured, not argued: that is exactly what the fourth design did.
        //
        // So the pilot does what a pilot does — he LOOKS. The direction to the body is not a
        // privileged reading: the cell sky draws every body along it, and since waves 2-3 the label
        // carries the range too. Each burst he takes the largest remaining component, flies about
        // half of it, and looks again; because the reading is refreshed every burst, a body that
        // moves is simply a body whose bearing has changed, and the chase converges for the same
        // reason the foot race does.
        //
        // The burst is a count of GAME ticks, sized from the component it is flying. {@code
        // TestTimeouts.factor()} stretches WALL-CLOCK ceilings so concurrent forks do not time out; a
        // tick count is not one. Scaling the burst by it made each step three times LONGER on an
        // 8-fork box than on a 1-fork box — it made this leg's geometry a function of machine load.
        //
        // What that does NOT remove: the ship is integrated on the physics mod's own wall-clock
        // thread while the body's position advances on server ticks, so how far a burst of N client
        // ticks actually carries the craft still moves with load. The leg survives that by re-sizing
        // every burst from the component it can still see rather than from a plan — a burst that
        // fell short is simply a larger component next time round.
        String feed = bodies;
        long[] aim = nearestDescendTargetVector(feed);
        requireArranged("the arrived cell must report WHERE its descend-target body is, not "
                        + "only how far — the pilot's own sky draws it along that direction: " + feed,
                aim != null);
        long rangeAtArrival = aim[3];
        long range = rangeAtArrival;
        // One entry per world axis of the offset, each as the key that REDUCES a positive component
        // and the key that reduces a negative one. A crossing re-assembles a ship at the identity
        // attitude and this leg commands no rotation, so the craft's own axes are the world's: nose
        // is +Z, its right is +X, its up is +Y.
        final int[][] axisKeys = {
                {Keyboard.KEY_Q, Keyboard.KEY_E},   // dx — lateral (strafe left commands +right)
                {Keyboard.KEY_R, Keyboard.KEY_F},   // dy — vertical
                {Keyboard.KEY_W, Keyboard.KEY_S},   // dz — nose
        };
        // Long enough for the deadbeat to actually stop the ship, so a reading is taken from rest.
        final int cutTicks = 60;
        // The whole approach costs at most this many bursts. Sized off the geometry, not off a
        // timeout: each burst takes out about half of the largest component, so a 1024-block standoff
        // is a handful, and a chase still running after this many is not converging — which is a
        // finding, and the trace below is how it gets read.
        final int burstBudget = 40;
        int bursts = 0;
        int stalled = 0;
        long bestRange = Long.MAX_VALUE;
        int descentDim = jumpDim;
        // The whole approach, burst by burst, so a red says which component was left standing.
        StringBuilder flown = new StringBuilder();

        // Taken before the first burst: the descent fires from inside this loop, and the pilot's
        // side of it is a world he is carried into — a record, not a value to sample once the
        // carrying is over. The SERVER's mark is taken for the same instant, so the seat chain the
        // arrival is judged on covers the crossing that produced it and nothing earlier.
        long descentClientMark = clientEvents().mark();
        long descentMark = events.mark();

        while (bursts < burstBudget && descentDim == jumpDim) {
            aim = nearestDescendTargetVector(feed);
            if (aim == null) {
                // No body left in this cell: the descent has cut the ship out of it. That is this
                // leg SUCCEEDING — but the crossing settles over several ticks and the CLIENT is
                // carried at the end of it, so wait for him. Breaking on the dimension he was in
                // when the trigger fired reads a completed descent as a failed approach.
                descentDim = awaitClientWorldMatching(descentClientMark, dim -> dim != jumpDim,
                        "a change out of the cell " + jumpDim,
                        "the descent cut the ship out of its cell, so the PILOT has to be carried "
                                + "out of it too — a hull that lands without its crew has not "
                                + "completed the crossing, and the approach below would then read a "
                                + "finished descent as a chase that never converged",
                        budget * 5);
                break;
            }
            range = aim[3];
            if (range < bestRange) {
                bestRange = range;
                stalled = 0;
            } else {
                stalled++;
            }
            assertTrue("a pilot who has arrived beside a body must be able to FLY AT IT: he points at "
                            + "the largest part of the offset, holds a translation key, and the range "
                            + "falls. It has now failed to beat its own best over " + stalled
                            + " consecutive bursts, so he is not closing on anything: either the craft "
                            + "does not answer its controls in a space cell, or the readout does not "
                            + "follow it, or the body is out-running him — the trace gives the aim and "
                            + "the range on both sides of every burst. rangeAtArrival=" + rangeAtArrival
                            + " best=" + bestRange + " rangeNow=" + range + " bursts=" + bursts
                            + " flown=" + flown
                            + " ledger=" + exec("artest space ledger-get " + shipId)
                            + " bodies=" + feed,
                    stalled < 6);

            int comp = 0;
            for (int i = 1; i < 3; i++) {
                if (Math.abs(aim[i]) > Math.abs(aim[comp])) {
                    comp = i;
                }
            }
            int key = aim[comp] > 0 ? axisKeys[comp][0] : axisKeys[comp][1];
            // Take about HALF the component: enough that no burst can overshoot what it aims at, and
            // — through the floor — never so little that the body covers more ground than the ship.
            int burstTicks = (int) Math.max(80L, Math.min(300L, 30L + Math.abs(aim[comp]) / 4L));
            flown.append(" aim(").append(aim[0]).append(',').append(aim[1]).append(',')
                    .append(aim[2]).append(")=").append(range)
                    .append(" fly[c=").append(comp).append(",b=").append(burstTicks).append(']');
            feed = flyBurst(key, burstTicks, cutTicks);
            bursts++;
            descentDim = clientDim(descentDim);
        }
        range = nearestDescendTargetDistance(feed);
        assertTrue("a piloted ship that has CLOSED ON a body must be taken DOWN off the space cell — "
                        + "that entry is the only way a tier-2 craft reaches a surface, and the pilot's "
                        + "whole input is flying at the body he arrived beside. The CLIENT's own "
                        + "dimension is what answers. If the range fell but the trigger never fired, "
                        + "read the two ranges below against the descent radius before suspecting the "
                        + "approach. clientDim=" + descentDim + " cellDim=" + jumpDim
                        + " rangeAtArrival=" + rangeAtArrival
                        + " rangeNow=" + nearestDescendTargetDistance(exec("artest space bodies"))
                        + " flown=" + flown
                        + " nearestBodyDim=" + nearestDim + " dimLoad=" + loaded
                        + " descentStatus=" + exec("artest space descent-status")
                        + " ledger=" + exec("artest space ledger-get " + shipId)
                        + " bodies=" + bodies + " delivery=" + exec("artest vs seat-delivery"),
                descentDim != jumpDim);
        assertTrue("…and where it puts him down must be a real WORLD, with ground under it. The space "
                        + "subsystem's own slot worlds are empty voids that exist to hold a cell; a "
                        + "descent that ends in one has landed the ship nowhere, and the pilot who flew "
                        + "across a system to reach a planet steps out into nothing. clientDim="
                        + descentDim + " slotDims=[" + jumpSlotDims + "] nearestBodyDim=" + nearestDim
                        + " dimLoad=" + loaded + " bodies=" + bodies,
                !jumpSlotDims.contains("," + descentDim + ","));
        assertTrue("…and the world he steps out onto must be the PLANET HE PICKED at the console. "
                        + "That is the whole loop: choose a body, fly to it, land on it. Landing on "
                        + "something else in the neighbourhood means the aim, the arrival or the "
                        + "trigger's choice of body disagreed with the pilot. pickedDim=" + targetDim
                        + " landedDim=" + descentDim + " nearestBodyDim=" + nearestDim
                        + " bodies=" + bodies,
                descentDim == targetDim);

        JsonObject landedRiding = assertStillSeated(events, descentMark, descentClientMark,
                "and the pilot must still be flying his ship when it comes out over the planet he "
                        + "set out for — the loop is only closed if the man who took off is the man "
                        + "who arrives. clientDim=" + descentDim
                        + " delivery=" + exec("artest vs seat-delivery"),
                budget);

        // CONTRACT (changed): a descent no longer hunts for a clear pad and sets the ship down. It
        // brings the ship out HIGH IN THE AIR over the destination and hands it back to the pilot to
        // fly down — which is why no arrival can be refused for "nothing fits below". So the arrival
        // is asserted where it now happens: the CLIENT's own altitude, above everything the world is
        // able to build. This is a strictly stronger reading than the old leg made (which never
        // checked altitude at all), not a relaxed one.
        // READ ONCE, because the thing this was waiting for has already been waited for. The
        // altitude is a physical value and a value is measured, not polled — but the old loop was
        // not measuring it either: it was waiting for the CROSSING that puts the ship there, and
        // re-reading a number until it liked it is what that looked like from inside. The crossing's
        // own pose write is a record, and the re-seat above already required the client to have
        // followed the whole arrival, so by here the pose is settled and one read is the measurement.
        String posed = events.await(descentMark, "crossing_pose_settled",
                "the descent must WRITE the arrived ship's pose — until it does there is no altitude "
                        + "to read, and a number sampled before it is the paste band's, not the "
                        + "arrival's", budget * 5);
        JsonObject arrivalState = bot().reportState();
        double arrivalY = arrivalState.has("playerY")
                ? arrivalState.get("playerY").getAsDouble() : Double.NEGATIVE_INFINITY;
        assertTrue("…and he must come out IN THE SKY over it, not on the ground and never inside it. "
                        + "The arrival pose is placed above the whole vanilla block band on purpose: "
                        + "a ship's blocks cannot exist above the build height, so an arrival that "
                        + "reads at or below it means the pose teleport never carried the ship (and "
                        + "its rider) up off the paste band, and the pilot is sitting in the terrain "
                        + "he was supposed to fly down to. clientY=" + arrivalY
                        + " buildHeight=" + TerrainHeightFinder.MAX_BUILD_Y
                        + " clientDim=" + descentDim + " riding=" + landedRiding
                        + " serverRiding=" + exec("artest player riding-entity")
                        + " | the pose the descent actually wrote: " + posed,
                arrivalY > TerrainHeightFinder.MAX_BUILD_Y);

        System.out.println("[M1] leg 8 (descent onto the planet) " + elapsed(tLeg)
                + " arrivedDim=" + descentDim + " nearestBodyDim=" + nearestDim
                + " arrivalY=" + arrivalY + " riding=" + landedRiding);

        // ---- LEG 9: he stays put, and can still leave later. ------------------------------------
        // The descent puts the ship down IN THE AIR, and that can be above this body's own orbit
        // line (this run seeds the line to the config minimum, so it certainly is). The entry
        // on-ramp fires on "a piloted ship is above the orbit line" — the arrival matches it
        // exactly. Without a hysteresis the ship is taken straight back to space on the tick it
        // arrives and the body can never be reached at all.
        //
        // The key stays DOWN for this whole leg: `flying` is what arms the entry trigger, so a leg
        // that let go of it would prove nothing — the trigger it is watching for would be switched
        // off. Leg 8 released the key, which is exactly why leg 8's green was never evidence here.
        tLeg = System.currentTimeMillis();
        // WATCHED AS AN ABSENCE OVER THE WHOLE WINDOW, not sampled at the end of it. A bounce is a
        // world the client was carried into and then out of again, and a sample taken every ten
        // ticks can miss exactly that — which is the failure this leg exists to catch, so the one
        // reading that must not be missable was the one being sampled.
        //
        // An absence proves nothing on its own: the SENSITIVITY CONTROL for this instrument is the
        // release half immediately below, which reads the same record type over the same client and
        // REQUIRES a change. If this half is silent because the recorder is dead, that half fails.
        long latchClientMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_R);          // vertical-up: still flying, still climbing
        try {
            bot().waitTicks(LATCH_WATCH_SAMPLES * 10);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        String bounceChanges = clientEvents().since(latchClientMark, "client_dimension_changed");
        int bounceDim = readIntOr(Events.lastField(bounceChanges, "dim"), descentDim);
        assertTrue("a ship that has just been PUT somewhere by a descent must stay there while its "
                        + "pilot flies, even though the arrival is above this body's orbit line. The "
                        + "on-ramp reads altitude alone, so the arrival looks exactly like a climb to "
                        + "orbit unless the descent holds it off until the ship has been below the "
                        + "line once. A dim that flipped to a space cell here is that bounce: the "
                        + "pilot crossed a system to reach this body and was thrown back off it "
                        + "without touching anything. dimAfterArrival=" + bounceDim
                        + " arrivedDim=" + descentDim + " slotDims=[" + jumpSlotDims + "]"
                        + " arrivalY=" + arrivalY + " orbitLine=" + ORBIT_LINE
                        + " client world changes while he flew: " + bounceChanges,
                bounceDim == descentDim);

        // …and the hold must RELEASE. A latch that never clears turns "bounces off instantly" into
        // "can never leave this planet again", which is strictly worse. Fly down through the line,
        // which is the release condition, then climb back through it and entry must fire normally.
        double downY = arrivalY;
        bot().holdKey(Keyboard.KEY_F);          // vertical-down
        try {
            for (int attempt = 0; attempt < budget && downY > ORBIT_LINE; attempt++) {
                bot().waitTicks(10);
                JsonObject state = bot().reportState();
                if (state.has("playerY")) {
                    downY = state.get("playerY").getAsDouble();
                }
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_F);
        }
        requireArranged("the pilot must actually get the ship back below the orbit line, or "
                        + "the release half of this leg never gets its stimulus. downY=" + downY
                        + " orbitLine=" + ORBIT_LINE,
                downY <= ORBIT_LINE);

        // THE SENSITIVITY CONTROL for the absence above, as well as this leg's own subject: the same
        // record type, the same client, the same helper — and here a change is REQUIRED. A recorder
        // that had died would pass the bounce half and fail here, which is what keeps the silence
        // above from being able to mean nothing.
        long releaseClientMark = clientEvents().mark();
        int releasedDim;
        bot().holdKey(Keyboard.KEY_R);          // climb back through the line under power
        try {
            releasedDim = awaitClientWorld(releaseClientMark, jumpSlotDims,
                    "…and once he HAS been below the line, the on-ramp must work again — a ship that "
                            + "landed on a planet has to be able to leave it. If this stays on the "
                            + "planet the hold never released and the descent has stranded him "
                            + "instead of bouncing him. arrivedDim=" + descentDim + " slotDims=["
                            + jumpSlotDims + "] downY=" + downY + " orbitLine=" + ORBIT_LINE,
                    budget * 10);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        // No restatement of that verdict here. The wait above IS the assertion — it carries the
        // sentence and the numbers, and it fails with the client's whole record of the window rather
        // than with one last sample. A second `assertTrue` on the value it returned could only ever
        // pass.
        System.out.println("[M1] leg 9 (stays put, then can leave) " + elapsed(tLeg)
                + " dimAfterArrival=" + bounceDim + " downY=" + downY
                + " dimAfterSecondClimb=" + releasedDim);
    }

    // ---- legs 6-8: the console, the jump key and the descent ------------------------------------

    /**
     * The pilot leaves his seat on the real dismount key, and the CLIENT is what confirms he is on
     * his feet: the deck hold that keeps a standing pilot aboard runs on the server, so "he stood up"
     * has to be read where he is rendered.
     */
    private void standUp(Events log, int budget) throws Exception {
        // The DISMOUNT is the link, and the server records it with the caller that performed it. The
        // old form watched the client's riding flag go false, which is the same reading for "the key
        // reached the server and he got up" and for "something else threw him off" — and on this deck
        // the second is a real failure mode, since a standing pilot is held aboard by the server.
        long standMark = log.mark();
        bot().holdKey(Keyboard.KEY_LSHIFT);
        try {
            log.await(standMark, "dismount",
                    "a pilot must be able to LEAVE his seat on the dismount key — the navigation "
                            + "console is deck work, and a seat he cannot get out of would end the "
                            + "loop here", budget * 5);
        } finally {
            bot().releaseKey(Keyboard.KEY_LSHIFT);
        }
        bot().waitTicks(10);
        assertTrue("…and the CLIENT must render him on his feet: the deck hold that keeps a standing "
                        + "pilot aboard runs on the server, so a dismount the client never applied "
                        + "leaves him riding a seat the server says he left. riding="
                        + bot().reportRidingEntity() + " serverRiding="
                        + exec("artest player riding-entity")
                        + " | dismounts since the key went down: "
                        + log.since(standMark, "dismount"),
                !isRiding(bot().reportRidingEntity()));
    }

    /** He takes the seat again, exactly the way he took it the first time: aim at it and press use. */
    private JsonObject sitBackDown(Events log, int dim, int[] afcSub, int budget) throws Exception {
        int[] seatSub = add(afcSub, OFF_SEAT);
        holdNothing(budget);
        Aim aim = aimAt(dim, afcSub, seatSub, OFF_STAND, 0.5, 0.2, 0.5, budget);
        assertAimed(aim, seatSub, "pilot seat", "pilotseat");
        long sitMark = log.mark();
        long sitClientMark = clientEvents().mark();
        pressUse();
        // Same three links as the first boarding, in the same order: the press reaches the seat, the
        // seat knows it belongs to a ship, the mount reaches the client.
        String decision = log.await(sitMark, "pilot_seat_sit_decided",
                "the use press must REACH the seat when the pilot takes it again — the crosshair was "
                        + "confirmed on the block, so a silence is the interaction and not the aim",
                budget * 5);
        assertTrue("…and the seat must still know it belongs to a ship: an unmanaged seat carries no "
                        + "flight input, so the jump he is about to fire would go nowhere. decision="
                        + decision, Boolean.parseBoolean(Events.text(decision, "managed")));
        return awaitClientMount(sitClientMark,
                "the pilot must be back in his seat on the CLIENT before he fires the drive — the "
                        + "jump key is routed through the seat he occupies, and a seating that only "
                        + "the server performed carries no input", budget, aim.diagnosis);
    }

    /**
     * Put the crosshair on the navigation console from the deck square beside it and press use until
     * its screen opens. The crosshair is confirmed on the console's own block before every press, so
     * a red names the hop that failed rather than merely the outcome.
     */
    private String openConsoleFromTheDeck(int dim, int[] afcSub, int[] navSub, int budget)
            throws Exception {
        Aim aim = new Aim();
        for (int attempt = 0; attempt < 6; attempt++) {
            String already = screenOf(bot().reportState());
            if (!already.isEmpty()) {
                return already;
            }
            aim = aimAt(dim, afcSub, navSub, OFF_STAND, 0.5, 0.5, 0.5, budget);
            assertAimed(aim, navSub, "navigation console", "navigationcomputer");
            pressUse();
            for (int waited = 0; waited < 6; waited++) {
                bot().waitTicks(10);
                String screen = screenOf(bot().reportState());
                if (!screen.isEmpty()) {
                    return screen;
                }
            }
        }
        return "ARRANGEMENT: console never opened;" + aim.diagnosis;
    }

    /** An empty main hand, without wiping the inventory the pilot is carrying his crystal in. */
    private void holdNothing(int budget) throws Exception {
        bot().selectHotbar(1);
        String heldId = null;
        for (int attempt = 0; attempt < budget; attempt++) {
            bot().waitTicks(5);
            JsonObject items = bot().reportPlayerItems();
            if (isWorldReady(items) && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
        }
        requireArranged("the pilot's main hand must be EMPTY so the use press reaches the "
                + "block rather than being consumed by a held item; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }

    /**
     * The last world point the ship was known to occupy. After a crossing the craft is somewhere the
     * test never chose, so the FIRST handle on it is the pilot aboard it — but a pilot who has stood
     * up is no longer a reliable handle (vanilla's dismount can leave him beside the hull rather than
     * on it), so every successful find is remembered and re-used as the fallback probe point.
     */
    private double[] shipAnchorHint;

    /** The last find-seat answer, verbatim, so a failed aim can name the probe that went quiet. */
    private String lastSeatProbe = "";

    /** The PHYSICS id of the craft this run assembled, from the registry record of its own spawn. */
    private String builtShipVsId;

    /**
     * The DURABLE name of that same craft, read off its flight computer's NBT on the pad. Every
     * crossing re-mints the physics id and carries this one through verbatim, so this is the handle
     * that still works three crossings later.
     */
    private String builtShipName;

    /**
     * The seat of the ship the client is aboard — resolved from that ship's NAME, in whichever world
     * it is now in.
     *
     * <p>This used to probe from the pilot's own position and then, failing that, from {@link
     * #shipAnchorHint} — a REMEMBERED earlier pose. That second probe asks "what craft is nearest a
     * place this one has left", which the yard lookup always answers with something; and the aim loop
     * re-ran it every attempt, so the ship under the crosshair could change identity mid-aim. What is
     * still retried is the crossing finishing, which is a fact about time.</p>
     */
    // NOT on PilotSeat, and deliberately: this returns a `find-seat` reply on the happy path and a
    // `vs ship-uuid` reply when the hull cannot be named, through one variable — two producers with
    // one type, which is its own defect and not one to be rewritten blind while converting another.
    private String findSeatAboard(int dim, int budget) throws Exception {
        assertNotNull("the build must have named its ship before the arrival side can ask about it",
                builtShipName);
        for (int attempt = 0; attempt < budget; attempt++) {
            String hull = exec("artest vs ship-uuid " + dim + " " + builtShipName);
            String hullId = Reply.of("artest vs ship-uuid", hull).text("id");
            if (hull.contains("\"found\":true") && hullId != null) {
                lastSeatProbe = exec("artest vs find-seat " + dim + " id " + hullId);
                if (rememberAnchor(lastSeatProbe)) {
                    return lastSeatProbe;
                }
            } else {
                lastSeatProbe = hull;
            }
            bot().waitTicks(5);
        }
        return lastSeatProbe;
    }

    /** Keep the ship's live world position from a successful probe; false when it found nothing. */
    private boolean rememberAnchor(String probe) {
        double[] anchor = readTripleD(probe, "shipWorldX", "shipWorldY", "shipWorldZ");
        if (anchor == null) {
            return false;
        }
        shipAnchorHint = anchor;
        return true;
    }

    /** One press of the jump key, edge-triggered the way the real keyboard delivers it. */
    private void pressJumpKey() throws Exception {
        bot().setKey(Keyboard.KEY_J, true);
        bot().waitTicks(5);
        bot().setKey(Keyboard.KEY_J, false);
        bot().waitTicks(20);
    }

    // `chatText` lived here: the client's last N chat lines flattened and lower-cased, for substring
    // checks. Its two callers are gone — one asserted that the pilot is TOLD the ship is armed (the
    // arming itself is read off the navigation computer), and one decided which JUMP BRANCH the run
    // took by looking for the words "spooling" and "confirm" (the trigger names its own outcome, and
    // `jump_press_decided` carries it). A milestone whose control flow turned on prose turned on the
    // language file.

    /**
     * The dimension of the NEAREST body in this cell the ship may descend onto, or MIN_VALUE.
     *
     * <p>Nearest is the pilot's choice, not production's: the flight computer walks its cell's
     * bodies in registry order and takes the FIRST descend-target inside the radius, which is a
     * different body whenever a cell holds more than one — a planet and its moons share a cell. The
     * two coincide on this loop's arrivals because a jump stands the ship off around the body it was
     * armed at, leaving that one an order of magnitude nearer than any sibling; they would not
     * coincide in a cell whose bodies are close together, and the leg would then load one world and
     * be descended into another.</p>
     */
    private static int nearestDescendTargetDim(String bodies) {
        int best = Integer.MIN_VALUE;
        long bestDistance = Long.MAX_VALUE;
        for (String body : descendTargets(bodies)) {
            Reply b = Reply.of("one cell body", body);
            long distance = (long) b.number(BODY_DISTANCE);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = b.integer(BODY_DIM);
            }
        }
        return best;
    }

    /**
     * Every descend-target body of every ship's cell in a {@code space bodies} reply.
     *
     * <p>The nesting is walked rather than flattened by one expression over the whole reply: bodies
     * belong to a CELL and the reply carries them per ship, so a scan of the raw text pools one
     * ship's cell with another's the moment two are ledgered.</p>
     */
    private static java.util.List<String> descendTargets(String bodies) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String ship : Reply.of("artest space bodies", bodies).objectArray(SHIPS)) {
            for (String body : Reply.of("one ship's cell", ship).objectArray(CELL_BODIES)) {
                if (Reply.of("one cell body", body).bool(BODY_DESCEND_TARGET, false)) {
                    out.add(body);
                }
            }
        }
        return out;
    }

    /**
     * Where the nearest descend-target body is FROM THE SHIP: {@code {dx, dy, dz, range}}, or
     * {@code null} when the cell reports no such body — which, mid-approach, means the descent has
     * already taken the ship out of the cell.
     *
     * <p>This is the range readout with its three components still separate. The pilot has both:
     * the cell sky draws each body along this very direction and labels it with this very range, so
     * reading them together is what he does when he looks out of the cockpit, and reading only the
     * scalar is the one thing he cannot do.</p>
     */
    private static long[] nearestDescendTargetVector(String bodies) {
        long[] best = null;
        for (String body : descendTargets(bodies)) {
            Reply b = Reply.of("one cell body", body);
            long distance = (long) b.number(BODY_DISTANCE);
            if (best == null || distance < best[3]) {
                best = new long[]{(long) b.arrayNumber(BODY_BEARING, 0),
                        (long) b.arrayNumber(BODY_BEARING, 1),
                        (long) b.arrayNumber(BODY_BEARING, 2), distance};
            }
        }
        return best;
    }

    /**
     * Hold one translation key for a burst, then cut the throttle, and report the range that follows.
     * The cut matters: flight assist ramps a velocity SETPOINT rather than thrusting directly, so a
     * released key leaves the craft coasting and the next burst would measure the previous one.
     */
    private String flyBurst(int key, int burstTicks, int cutTicks) throws Exception {
        bot().holdKey(key);
        try {
            bot().waitTicks(burstTicks);
        } finally {
            bot().releaseKey(key);
        }
        return cutThrottle(cutTicks);
    }

    /** The dimension the CLIENT believes it is in, or {@code fallback} when it does not say. */
    private int clientDim(int fallback) throws Exception {
        JsonObject weather = bot().reportWeather();
        return weather.has("dim") ? weather.get("dim").getAsInt() : fallback;
    }

    /** Brake to a stop and read the cell back, from rest. */
    private String cutThrottle(int cutTicks) throws Exception {
        bot().holdKey(Keyboard.KEY_X);
        try {
            bot().waitTicks(cutTicks);
        } finally {
            bot().releaseKey(Keyboard.KEY_X);
        }
        return exec("artest space bodies");
    }

    /**
     * The RANGE to that same body — the readout a pilot closing on a planet watches, and this test's
     * only measure of whether he is getting anywhere. {@link Long#MAX_VALUE} when the cell reports no
     * body to descend onto, which keeps "no target" from reading as "range zero".
     */
    private static long nearestDescendTargetDistance(String bodies) {
        long[] vector = nearestDescendTargetVector(bodies);
        return vector == null ? Long.MAX_VALUE : vector[3];
    }

    /**
     * The container slot number of the first slot holding {@code item}, or -1. Matched without
     * regard to case: the client renders the registry name through {@code ResourceLocation}, which
     * lower-cases it, so the id a slot reports is not character-identical to the one a command takes.
     */
    private static int slotHolding(JsonObject slots, String item) {
        if (slots == null || !slots.has("slots")) {
            return -1;
        }
        for (int i = 0; i < slots.getAsJsonArray("slots").size(); i++) {
            JsonObject slot = slots.getAsJsonArray("slots").get(i).getAsJsonObject();
            if (slot.has("item")
                    && item.equalsIgnoreCase(slot.get("item").getAsString())) {
                return slot.get("slot").getAsInt();
            }
        }
        return -1;
    }

    // ---- leg 2: the assembler, driven entirely from the client's screen -------------------------

    /**
     * Open the assembler's screen with a real use-key press, click Scan, then click Build until a
     * ship exists. Returns the ship count reached.
     *
     * <p>Build is pressed on a poll because the machine silently drops a Build press while a scan
     * pass is running: the press "takes" the moment the scan finishes, which is exactly how a human
     * clicking the button experiences it.</p>
     */
    private int assembleThroughTheAssemblersScreen(int[] builderPos, int budget) throws Exception {
        // Walk up to the machine: two blocks south of it on the pad, face-on, well inside the
        // server's interaction reach.
        double standX = builderPos[0] + 0.5, standZ = builderPos[2] + 2.5;
        standOnThePad(standX, standZ, builderPos);
        emptyTheHand();

        // The builder's own stored energy, read before a single button is pressed. The fixture
        // stands libVulpes' creative power plug on top of the assembler and that plug pushes into
        // every adjacent accepting tile each tick, so the machine is already full here — a creative
        // power source is something a player has, and the acts in this leg are the key press and the
        // two button clicks below. Printed rather than asserted on: the CONTRACT this leg pins is
        // that the two clicks produce a ship, and a machine that ran on a different power arrangement
        // would still have to satisfy it.
        System.out.println("[M1] assembler energy at the machine: " + energyAt(builderPos));

        String screen = openBuilderScreenByRealKeyPress(builderPos, budget);
        assertTrue("a real use-key press aimed at the ROCKET ASSEMBLER must open its screen on the "
                        + "client. Every act of the build is performed on that screen, so a machine "
                        + "that swallows the press leaves the player with no way to build a ship at "
                        + "all. screen=\"" + screen + "\"",
                screen.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        bot().clickButtonById(BUTTON_SCAN);

        int ships = 0;
        // The registry's own record of the ship being added (`ship_spawned`), since a mark taken
        // before the first BUILD click: a count of ships in dim 0 was an absolute on a world nothing
        // else builds in here, but it could not say WHICH ship, and the record names it.
        Events spawnEvents = new Events(this::exec, bot()::waitTicks);
        long spawnMark = spawnEvents.markInstrumented();
        // CLASSIFIED, and it stays a loop: every pass PRESSES BUILD again, and re-opens the screen
        // first when something knocked it shut. Delete it and the build stops being ATTEMPTED, not
        // merely stop being watched — so there is nothing for a chain to attach to, and the record
        // it exits on (`ship_spawned`) is already production's own verdict rather than a sample.
        //
        // THE MULTIPLIER STAYS. What it waits on is VS building the ship on its OWN thread, off the
        // game loop: that work finishes in wall-clock time, so a busy box genuinely needs more game
        // ticks to elapse before it is done. Measured at 8 forks on the sibling gate test.
        int assembleBudget = (int) (90 * TestTimeouts.factor());
        for (int attempt = 0; attempt < assembleBudget && ships < 1; attempt++) {
            // The screen can be knocked shut (a chunk reload, a stray escape); re-open it rather
            // than clicking into nothing, so a red names the machine and not a lost window.
            if (screenOf(bot().reportState()).isEmpty()) {
                screen = openBuilderScreenByRealKeyPress(builderPos, budget);
                if (!screen.startsWith("zmaster587.libVulpes.inventory.GuiModular")) {
                    continue;
                }
            }
            bot().clickButtonById(BUTTON_BUILD);
            bot().waitTicks(40);
            String spawned = spawnEvents.since(spawnMark, "ship_spawned");
            ships = Events.countRecordsWithField(spawned, "vsShip");
            // WHICH ship. The record names it and this loop was counting the records and throwing the
            // name away — after which every later question about "the ship" went back to a position
            // or to "the first settled row in the cell". It is kept from the moment of creation now.
            String namedShip = Events.lastField(spawned, "vsShip");
            if (namedShip != null && !namedShip.isEmpty()) {
                builtShipVsId = namedShip;
            }
        }
        bot().closeScreen();
        return ships;
    }

    /**
     * Put the crosshair on the assembler and press the use key, retrying the whole aim-and-press
     * until a screen opens. The crosshair is confirmed on the machine's own block before every
     * press, so a red names the hop that failed rather than merely the outcome.
     */
    private String openBuilderScreenByRealKeyPress(int[] builderPos, int budget) throws Exception {
        Aim aim = new Aim();
        for (int attempt = 0; attempt < 6; attempt++) {
            String already = screenOf(bot().reportState());
            if (!already.isEmpty()) {
                return already;
            }
            aim = aimAtWorldBlock(builderPos, 0.5, 0.5, 0.5, budget);
            assertAimed(aim, builderPos, "rocket assembler", "rocketbuilder");
            pressUse();
            for (int waited = 0; waited < 6; waited++) {
                bot().waitTicks(10);
                String screen = screenOf(bot().reportState());
                if (!screen.isEmpty()) {
                    return screen;
                }
            }
        }
        return "";
    }

    // ---- aiming ---------------------------------------------------------------------------------

    /** One aim attempt's outcome: where the bot ended up, what it was looking at, and why. */
    private static final class Aim {
        JsonObject mouseOver;
        double distSq = Double.POSITIVE_INFINITY;
        String diagnosis = "";
    }

    /**
     * Aim at a block that stands in the world (the assembler), from wherever the bot is standing.
     *
     * <p>CLASSIFIED, and it stays a loop: the loop IS the stimulus, not an observation of one. Each
     * pass aims the client and then reads back what the crosshair actually hit — a feedback
     * controller terminating on its own goal state. Delete it and the aiming stops HAPPENING, not
     * merely stop being watched. No link could replace it either: nothing in production DECIDES
     * that a crosshair is on a block, the pick is re-derived every frame from where the player
     * stands, and where he stands is still settling.</p>
     */
    private Aim aimAtWorldBlock(int[] target, double tx, double ty, double tz, int budget)
            throws Exception {
        double[] targetWorld = {target[0] + tx, target[1] + ty, target[2] + tz};
        Aim aim = new Aim();
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        for (int attempt = 0; attempt < budget; attempt++) {
            JsonObject state = bot().reportState();
            if (!isWorldReady(state)) {
                bot().waitTicks(5);
                continue;
            }
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();
            aim.distSq = look(targetWorld, px, py, pz);
            bot().waitTicks(5);
            aim.mouseOver = bot().reportMouseOver();
            if (isUnderCrosshair(aim.mouseOver, target)) {
                break;
            }
        }
        aim.diagnosis = " observedPlayer=(" + px + "," + py + "," + pz + ")"
                + " target=" + describe(target)
                + " targetWorld=" + java.util.Arrays.toString(targetWorld)
                + " distSq=" + aim.distSq + " mouseOver=" + aim.mouseOver;
        return aim;
    }

    /**
     * Stand on the ship's deck square and put the crosshair on {@code targetSub}, retrying until the
     * client itself confirms the pick. Both the stand and the aim are re-derived from the ship's live
     * pose every attempt: a freshly assembled ship settles for a while, and a position computed once
     * against a stale pose leaves the bot in mid-air beside a ship that has since moved.
     */
    private Aim aimAt(int[] afcSub, int[] targetSub, int[] standOffset,
                      double tx, double ty, double tz, int budget) throws Exception {
        return aimAt(0, afcSub, targetSub, standOffset, tx, ty, tz, budget);
    }

    /**
     * The aboard form: stand on the ship's own deck, then aim at a block of it.
     *
     * <p>CLASSIFIED for the same reason as {@link #aimAtWorldBlock}, plus one of its own — every
     * pass re-derives the stand AND the target from the ship's LIVE pose, because a freshly
     * assembled craft is still settling and a position computed once against a stale pose puts the
     * bot in mid-air beside a ship that has moved. So each iteration performs two stimuli (a
     * teleport and an aim) and reads the result back; it is a chase, not a wait.</p>
     *
     * <p>Note the order INSIDE the iteration, which is load-bearing: the teleport comes first and
     * the aim last. A server teleport arrives as a pos-look packet and vanilla applies it with
     * {@code setPositionAndRotation}, so an aim set before it would be overwritten by it.</p>
     */
    private Aim aimAt(int dim, int[] afcSub, int[] targetSub, int[] standOffset,
                      double tx, double ty, double tz, int budget) throws Exception {
        Aim aim = new Aim();
        int[] standSub = add(afcSub, standOffset);
        double[] standWorld = null;
        double[] targetWorld = null;
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;

        for (int attempt = 0; attempt < budget; attempt++) {
            double[] shipAnchor = readTripleD(
                    dim == 0 ? findSeat().raw() : findSeatAboard(dim, budget),
                    "shipWorldX", "shipWorldY", "shipWorldZ");
            if (shipAnchor == null) {
                bot().waitTicks(5);
                continue;
            }
            // The floor of the stand cell is the deck's top surface, so the feet go at its y with a
            // sliver of clearance rather than at its centre.
            standWorld = toWorld(dim, shipAnchor, standSub, 0.5, 0.05, 0.5);
            targetWorld = toWorld(dim, shipAnchor, targetSub, tx, ty, tz);
            if (standWorld == null || targetWorld == null) {
                bot().waitTicks(5);
                continue;
            }
            exec("tp @a " + standWorld[0] + " " + standWorld[1] + " " + standWorld[2] + " 0 0");
            bot().waitTicks(20);

            JsonObject state = bot().reportState();
            if (!isWorldReady(state)) {
                bot().waitTicks(5);
                continue;
            }
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();
            aim.distSq = look(targetWorld, px, py, pz);
            // The raytrace is refreshed once per client tick (Minecraft.runTick), so the new
            // rotation needs at least one tick before objectMouseOver can reflect it.
            bot().waitTicks(5);

            aim.mouseOver = bot().reportMouseOver();
            if (isUnderCrosshair(aim.mouseOver, targetSub)) {
                break;
            }
        }
        aim.diagnosis = " observedPlayer=(" + px + "," + py + "," + pz + ")"
                + " standSubspace=" + describe(standSub)
                + " standWorld=" + java.util.Arrays.toString(standWorld)
                + " targetSubspace=" + describe(targetSub)
                + " targetWorld=" + java.util.Arrays.toString(targetWorld)
                + " distSq=" + aim.distSq + " mouseOver=" + aim.mouseOver
                // A null standWorld/targetWorld means the SHIP was never located, not that the aim
                // maths went wrong — so both probes that answered have to be in the message.
                + " seatProbe=" + lastSeatProbe
                + " toWorldProbe=" + lastToWorldProbe
                + " anchorHint=" + java.util.Arrays.toString(shipAnchorHint);
        return aim;
    }

    /** Point the client's head at a world point from an observed stance; returns the squared reach. */
    private double look(double[] targetWorld, double px, double py, double pz) throws Exception {
        double dx = targetWorld[0] - px;
        double dy = targetWorld[1] - (py + EYE_HEIGHT);
        double dz = targetWorld[2] - pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        bot().setLook((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                (float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Every hop of the aim, asserted separately, so a red says which one broke. */
    private void assertAimed(Aim aim, int[] target, String what, String blockNeedle) {
        requireArranged("the bot must OBSERVABLY stand within the server's interaction reach "
                + "of the " + what + ", or the press is discarded before the block ever sees it."
                + aim.diagnosis, aim.distSq < MAX_INTERACT_DIST_SQ);
        assertTrue("HOP 1 (aim at the " + what + "): the client's crosshair must resolve to a BLOCK. "
                + "A MISS here means the sightline is obstructed or the aim maths is wrong, not that "
                + "the block is unclickable." + aim.diagnosis,
                aim.mouseOver != null && aim.mouseOver.has("typeOfHit")
                        && "BLOCK".equals(aim.mouseOver.get("typeOfHit").getAsString()));
        assertTrue("HOP 2 (aim at the " + what + "): the block under the crosshair must be the "
                + what + " as the CLIENT's own world reports it." + aim.diagnosis,
                aim.mouseOver.has("block") && aim.mouseOver.get("block").getAsString()
                        .toLowerCase(Locale.ROOT).contains(blockNeedle));
        assertTrue("HOP 3 (aim at the " + what + "): the raytrace must report the address "
                + describe(target) + " — that is what the interaction is handed." + aim.diagnosis,
                isUnderCrosshair(aim.mouseOver, target));
    }

    private static boolean isUnderCrosshair(JsonObject aim, int[] pos) {
        return aim != null
                && aim.has("typeOfHit") && "BLOCK".equals(aim.get("typeOfHit").getAsString())
                && aim.has("blockX")
                && aim.get("blockX").getAsInt() == pos[0]
                && aim.get("blockY").getAsInt() == pos[1]
                && aim.get("blockZ").getAsInt() == pos[2];
    }

    /** One real use-key press, the way the mouse handler writes it. */
    private void pressUse() throws Exception {
        bot().setKey(KEY_USE_ITEM, true);
        bot().waitTicks(5);
        bot().setKey(KEY_USE_ITEM, false);
    }

    // ---- arrangement ----------------------------------------------------------------------------

    /** Warm the chunks, clear the site and stand the craft up; returns the assembler's position. */
    private int[] placeFixture() throws Exception {
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed` — the number the
        // pre-clear it replaces was throwing away. The site stands in the open-air band, so the
        // craft rests on the launchpad the fixture lays at its own Y and this ASSERTS rather than
        // digging a shaft. The height covers the tallest variant in the catalogue plus the deck the
        // player walks to reach its console; the climb to the orbit line is production's business
        // and no pre-clear could cover it.
        site.requireClear(this::exec, 2, 20,
                "the jump-capable craft, and the deck the player boards and works it from");
        String fixture = exec("artest fixture rocket 0 " + bx + " " + by + " " + bz + " " + VARIANT);
        requireArranged("fixture (" + VARIANT + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        requireArranged("fixture missing builderPos: " + fixture, bp != null);
        return new int[]{bp[0], bp[1],
                bp[2]};
    }

    /**
     * How long the client is given to receive the pad it is standing on, in ticks.
     *
     * <p>A bounded poll and not a link, because what is being waited for is not a decision this game
     * publishes: it is a chunk arriving over a socket. The blind spot is named at the failure — a
     * budget that runs out cannot tell a slow client from a client that will never be sent the
     * chunk, and the reply printed there is what distinguishes them.</p>
     */
    private static final int CLIENT_FLOOR_BUDGET_TICKS = 200;

    /**
     * Put the player on the launchpad AND ESTABLISH THAT HE IS ON IT — measured through the CLIENT,
     * which is the side that decides whether he falls.
     *
     * <p><b>Why this is not a teleport and a wait.</b> Vanilla player movement is client
     * authoritative: the server takes the position the client sends. After a long teleport the
     * client has no blocks at the destination for some number of ticks, and a client with no blocks
     * under it is falling — so the server is handed a fall and accepts it. The pad's chunk is
     * force-loaded on the SERVER by {@code requireClear}; nothing in that says the client has it.</p>
     *
     * <p><b>This was invisible until the fixtures left the landscape.</b> At the old {@code y = 64}
     * the pad rested ON the terrain, so a player who never received the pad came to rest at the same
     * height anyway and every assertion downstream was satisfied. Lifting the site into the open-air
     * band removed the floor that was doing the work, and the leg failed with the player 87 blocks
     * below a machine the test's own prints show standing there with full energy. The arrangement
     * was never right; it was being propped up by ground nobody had named.</p>
     *
     * <p>The FIRST read is taken before the teleport and is a control: the client is 600 blocks away
     * at that point, so it must NOT have the pad. Without it, a green here could mean "the wait
     * works" or "the client had the chunk all along", and those are different worlds.</p>
     */
    private void standOnThePad(double standX, double standZ, int[] builderPos) throws Exception {
        final int floorX = builderPos[0], floorY = by, floorZ = builderPos[2] + 2;

        JsonObject before = bot().blockState(floorX, floorY, floorZ);
        System.out.println("[M1] client's view of the pad BEFORE the teleport (600 blocks away): "
                + before);

        exec("tp @a " + standX + " " + (by + 1) + " " + standZ + " 0 0");

        JsonObject floor = null;
        for (int attempt = 0; attempt < CLIENT_FLOOR_BUDGET_TICKS / 5; attempt++) {
            bot().waitTicks(5);
            floor = bot().blockState(floorX, floorY, floorZ);
            if (floor.has("block") && floor.get("block").getAsString().contains("launchpad")) {
                break;
            }
        }
        requireArranged("the CLIENT must receive the launchpad it is being stood on before anything"
                        + " is measured at the machine. Until it arrives the client sees air under"
                        + " itself, falls, and the server takes the fall — the pad being present on"
                        + " the SERVER is not the question. Asked at (" + floorX + "," + floorY + ","
                        + floorZ + ") for " + CLIENT_FLOOR_BUDGET_TICKS + " ticks; last reply "
                        + floor + ". A reply with \"loaded\":false is a chunk that never arrived,"
                        + " which is a different failure from a block that arrived as something else",
                floor != null && floor.has("block")
                        && floor.get("block").getAsString().contains("launchpad"));
        System.out.println("[M1] client has the pad: " + floor);

        // Put him back on it. The first teleport happened while the client had nothing to stand on,
        // so wherever he has fallen to is where he is; this is the one that lands on a floor both
        // sides agree exists.
        exec("tp @a " + standX + " " + (by + 1) + " " + standZ + " 0 0");
        bot().waitTicks(20);

        JsonObject state = bot().reportState();
        // `playerY`, checked against the harness rather than assumed: a `y` that is absent reads as
        // NaN, every comparison against NaN is false, and the assertion would then fail for the
        // wrong reason — or, written the other way round, pass on a field nobody ever sent.
        double y = state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
        requireArranged("and he must STAY on it: the client reports y=" + y + " where the pad's"
                        + " surface is " + (by + 1) + ". Still falling here means the floor arrived"
                        + " and something else is taking him off it. state=" + state,
                Math.abs(y - (by + 1)) < 1.5);
    }

    /** Server-side clear plus a client-observed empty hand (a held stack eats the use press). */
    private void emptyTheHand() throws Exception {
        exec("clear @a");
        bot().selectHotbar(0);
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonObject items = bot().reportPlayerItems();
            if (isWorldReady(items) && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
            bot().waitTicks(5);
        }
        requireArranged("the bot's main hand must be EMPTY so the use press reaches the "
                + "block rather than being consumed by a held item; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    /** The CLIENT's own ordered event log, behind the same verbs the server's is read through.
     *  {@link Events#mark} refuses a sequence unless a recorder is subscribed, which is what keeps
     *  an empty log later from reading as "it never happened". */
    private Events clientEvents() {
        return ClientEvents.of(bot());
    }

    /**
     * Wait until the CLIENT's own world is one of {@code dimList}, and answer which — read off the
     * client's record of the worlds its connection has built, never off a sample of where it is now.
     *
     * <p><b>Why one helper and not the four this class had.</b> "Which world is the pilot in" was
     * asked four different ways here: the entry leg read {@code client_dimension_changed} records,
     * the jump and latch legs sampled {@code reportWeather().dim}, the descent leg went through
     * {@code clientDim()}, and each carried its own retry loop. Three of those are a SAMPLE of a
     * value, and a sample cannot see a world that was built and left between two reads — which is
     * precisely what a bounce, a refused arrival or a double hand-off looks like. The records can,
     * and every other client class in this suite already reads them.</p>
     *
     * <p>The LAST record is the answer: a crossing can legitimately build more than one world on the
     * way (the pilot is carried through), and where he ended up is the newest one that matches.</p>
     *
     * @param dimList the accepted dimensions, comma-delimited AND comma-terminated at both ends
     *                ({@code ",4,5,"}), which is how this file already carries a slot-dim set
     */
    private int awaitClientWorld(long clientMark, String dimList, String what, int budget)
            throws Exception {
        return awaitClientWorldMatching(clientMark, dim -> dimList.contains("," + dim + ","),
                "a change into one of " + dimList, what, budget);
    }

    /**
     * The same, for the legs whose question is not a LIST but a relation — "out of the cell he
     * jumped into", "off the planet he landed on". Those cannot name their destination in advance:
     * a descent's target world may not have existed when the leg began.
     */
    private int awaitClientWorldMatching(long clientMark, java.util.function.IntPredicate wanted,
                                         String matching, String what, int budget) throws Exception {
        String reply = clientEvents().awaitMatching(clientMark, "client_dimension_changed",
                records -> wanted.test(readIntOr(Events.lastField(records, "dim"),
                        Integer.MIN_VALUE)),
                matching, what, budget);
        return readIntOr(Events.lastField(reply, "dim"), Integer.MIN_VALUE);
    }

    /**
     * The pilot is STILL IN HIS SEAT after a world transition — read from the client, and explained
     * by the server's own record of who was put on what and who was taken off it.
     *
     * <p><b>What this replaces, and why the replacement is stronger.</b> Three legs each sampled
     * {@code reportRidingEntity} twice with a wait between, on the reasoning that a seat lost in a
     * crossing "can read riding=true for one packet-lag frame, but never twice in a row". That
     * catches a flicker; it cannot catch the thing a crossing actually does wrong. A crossing
     * LEGITIMATELY takes the crew off and puts them back — so two trues in a row are equally
     * satisfied by a pilot who was dismounted and re-seated correctly, by one who was dismounted and
     * re-seated onto the WRONG mount, and by one whose dismount simply happened between the two
     * samples and was answered after them. The mount chain distinguishes all three, and its
     * {@code by} field names the caller that un-seated him.</p>
     *
     * <p>The verdict is "he is riding now, AND no dismount since the mark is left unanswered by a
     * later mount". An untouched pilot — no records at all — passes, because never having been moved
     * is the strongest form of still being seated.</p>
     */
    private JsonObject assertStillSeated(Events log, long serverMark, long clientMark, String what,
                                         int budget) throws Exception {
        // WAIT FOR THE CHAIN TO END SEATED, not for its first link. A crossing takes the crew off and
        // puts them back, so the client's own records across one arrival read
        // dismount -> mount -> dismount -> mount; a wait that returns on the first `mount` reads the
        // world in the MIDDLE of that, and the read after it can honestly say riding:false.
        // *Measured 2026-09-13, by writing it the short way first*: the jump leg failed with the
        // server holding the pilot on the arrived hull (`ridingEntityId:2299`) and the client
        // rendering `riding:false` — which is not the defect it looks like, it is a question asked
        // one link too early. The old two-samples-in-a-row poll covered this by accident.
        try {
            clientEvents().awaitMatching(clientMark, "mount",
                    seen -> endsSeated(clientEvents(), clientMark),
                    "the client's own mount chain ENDING in a mount", what
                            + " — and the CLIENT has to perform the re-seat, not merely be told about"
                            + " it", budget * 5);
        } catch (AssertionError never) {
            Events.assertInstrumentRan(clientEvents().since(clientMark, "mount"),
                    "entity_mount_writes", "the client's own mounts must be observed AT ALL before an"
                            + " absent one can be read as a re-seat the client never performed");
            throw new AssertionError(never.getMessage()
                    + " | the client's own chain: mounts=" + clientEvents().since(clientMark, "mount")
                    + " ||| dismounts=" + clientEvents().since(clientMark, "dismount"), never);
        }
        JsonObject riding = bot().reportRidingEntity();
        assertTrue(what + " clientRiding=" + riding
                        + " serverRiding=" + exec("artest player riding-entity")
                        + " | he was taken off a mount and not put back. The SERVER's chain —"
                        + " dismounts=" + log.since(serverMark, "dismount")
                        + " ||| mounts=" + log.since(serverMark, "mount"),
                isRiding(riding) && endsSeated(log, serverMark));
        return riding;
    }

    /**
     * Whether {@code log}'s mount chain since {@code mark} ENDS seated: either nothing touched him,
     * or every dismount was answered by a LATER mount.
     *
     * <p>Compared by sequence, which is the only thing that carries order once the rings are per
     * type. "Nothing touched him" passes on purpose: never having been moved is the strongest form of
     * still being seated, and the caller established he was seated when it took the mark.</p>
     */
    private static boolean endsSeated(Events log, long mark) throws Exception {
        long lastMount = readLongOr(Events.lastField(log.since(mark, "mount"), "seq"),
                Long.MIN_VALUE);
        long lastDismount = readLongOr(Events.lastField(log.since(mark, "dismount"), "seq"),
                Long.MIN_VALUE);
        return lastDismount == Long.MIN_VALUE || lastMount > lastDismount;
    }

    /**
     * Wait for THE CLIENT to have seated the bot itself, and hand back what it renders.
     *
     * <p><b>The client's own mount HAS a record</b>, and this file said otherwise for a while.
     * {@code MixinEntityPositionWriters} sits in the COMMON mixin list, and {@code TestTrace.record}
     * routes by the entity's own {@code world.isRemote} — so a {@code startRiding} performed on the
     * client is written to the CLIENT's log, carrying the verdict its caller got back
     * ({@code ok:true} for a mount that took). Polling {@code reportRidingEntity} instead watches the
     * SHADOW of that act: it cannot say when the mount happened, cannot distinguish a refusal from a
     * mount that has not replicated, and on expiry reports the last sample as though it were the
     * finding.</p>
     *
     * <p>An absent record is separated from a dead recorder before it is allowed to mean anything —
     * a silence here is read as "the client never seated him", and that reading is only available
     * once the roster says somebody was listening.</p>
     */
    private JsonObject awaitClientMount(long clientMark, String what, int budget, String diagnosis)
            throws Exception {
        try {
            clientEvents().awaitMatching(clientMark, "mount",
                    seen -> Events.countRecords(seen, "ok", "true") > 0,
                    "a mount the client's own startRiding accepted (ok:true)", what, budget * 5);
        } catch (AssertionError never) {
            Events.assertInstrumentRan(clientEvents().since(clientMark, "mount"),
                    "entity_mount_writes", "the client's own mounts must be observed AT ALL before an"
                            + " absent one can be read as a seating the client never performed");
            throw new AssertionError(never.getMessage()
                    + " | the client renders: " + bot().reportRidingEntity()
                    + " | every mount the SERVER has recorded this boot: "
                    + new Events(this::exec, bot()::waitTicks).since(0L, "mount") + diagnosis, never);
        }
        // Read ONCE, now that the link says the mount happened: a settled state, not a wait.
        return bot().reportRidingEntity();
    }

    /** A record's numeric field as a sequence, or {@code fallback} when it is absent. */
    private static long readLongOr(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** A record's numeric field, or {@code fallback} when it is absent — {@code null} is not 0. */
    private static int readIntOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String energyAt(int[] pos) throws Exception {
        return exec("artest energy stored 0 " + pos[0] + " " + pos[1] + " " + pos[2]);
    }

    /**
     * The seat's subspace address, its flight computer's, and the ship's live world position — of
     * the craft this run BUILT, named by the registry record its assembly wrote.
     */
    private PilotSeat findSeat() throws Exception {
        assertNotNull("the build must have named its ship before anything asks about that ship's"
                + " seat", builtShipVsId);
        return PilotSeat.byId(this::exec, 0, builtShipVsId);
    }

    /**
     * A subspace point mapped into the world through the ship's own transform.
     *
     * <p>Mapped through the craft this run BUILT, translated into {@code dim}'s physics id from the
     * durable name — every crossing re-mints that id, and this method is called on both sides of
     * three of them. {@code shipAnchor} no longer selects the ship: it is kept as the proof that the
     * craft's live pose was resolved at all, since mapping points for a ship nobody could find would
     * answer with numbers about nothing.</p>
     */
    private double[] toWorld(int dim, double[] shipAnchor, int[] sub, double dx, double dy, double dz)
            throws Exception {
        if (shipAnchor == null) {
            return null;
        }
        String hull = exec("artest vs ship-uuid " + dim + " " + builtShipName);
        String namedHull = Reply.of("artest vs ship-uuid", hull).text("id");
        if (!hull.contains("\"found\":true") || namedHull == null) {
            lastToWorldProbe = hull;
            return null;
        }
        lastToWorldProbe = exec("artest vs to-world " + dim + " id " + namedHull
                + " " + (sub[0] + dx) + " " + (sub[1] + dy) + " " + (sub[2] + dz));
        return readTripleD(lastToWorldProbe, "worldX", "worldY", "worldZ");
    }

    /** The last subspace-to-world mapping answer, so a null world point can name what refused it. */
    private String lastToWorldProbe = "";

    private static String screenOf(JsonObject state) {
        return state != null && state.has("screen") ? state.get("screen").getAsString() : "";
    }

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private static String elapsed(long since) {
        return "took " + ((System.currentTimeMillis() - since) / 1000L) + "s";
    }

    private static int[] add(int[] base, int[] offset) {
        return new int[]{base[0] + offset[0], base[1] + offset[1], base[2] + offset[2]};
    }

    private static String describe(int[] triple) {
        return "(" + triple[0] + "," + triple[1] + "," + triple[2] + ")";
    }

    /** The same triple as three space-separated command arguments. */
    private static String describeArgs(int[] triple) {
        return triple[0] + " " + triple[1] + " " + triple[2];
    }

    /** A {@code sx_sy_sz} cell key as the three sector arguments a probe takes. */
    /**
     * The cell key as the probe's {@code cell-info} takes it: VERBATIM. The probe reads a key form
     * whenever the argument carries a level separator, and a nested key such as
     * {@code 19_0_0.213_0_0} (a moon's own cell inside its planet's zone) has no numeric form at all —
     * splitting it on underscores handed the probe {@code 19 0 0.213 0 0}, which it read as the
     * PARENT cell and answered about the planet instead of the moon.
     */
    private static String cellArgs(String cellKey) {
        return cellKey;
    }

    private static String readString(String json, String field) {
        return Reply.of(json).text(field);
    }

    /** A probe field that may come back quoted or as a bare {@code null}, as a plain string. */
    private static String unquote(String raw) {
        return raw == null ? "" : raw.replace("\"", "");
    }

    private static int readInt(String json, String field, int fallback) {
        return Reply.of(json).integerOr(field, fallback);
    }

    /**
     * Three coordinate fields of one reply, read by NAME.
     *
     * <p>The regex this replaces matched all three in one expression — {@code
     * "seatX":…,"seatY":…,"seatZ":…} — so it held only while the producer kept them adjacent and in
     * that order, and answered "no seat" the moment anything was written between them.</p>
     */
    private static int[] readTriple(String json, String xField, String yField, String zField) {
        Reply reply = Reply.of(json);
        if (!reply.has(xField) || !reply.has(yField) || !reply.has(zField)) {
            return null;
        }
        return new int[]{reply.integer(xField), reply.integer(yField), reply.integer(zField)};
    }

    /** A slot-dim list as the comma-joined text the membership checks here are written against. */
    private static String joinInts(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(values[i]);
        }
        return out.toString();
    }

    /** @see #readTriple */
    private static double[] readTripleD(String json, String xField, String yField, String zField) {
        Reply reply = Reply.of(json);
        double x = reply.number(xField);
        double y = reply.number(yField);
        double z = reply.number(zField);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return null;
        }
        return new double[]{x, y, z};
    }
}
