package dev.stannismod.stellurgy.test.milestone;

import dev.stannismod.stellurgy.test.EvictionReports;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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

import dev.stannismod.stellurgy.space.TerrainHeightFinder;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;
import dev.stannismod.stellurgy.test.SubsystemStatus;
import dev.stannismod.stellurgy.test.Chains;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.LedgerEntry;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.OrbitLine;
import dev.stannismod.stellurgy.test.CellInfo;
import dev.stannismod.stellurgy.test.DriveInfo;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.TelescopeReading;
import dev.stannismod.stellurgy.test.client.ClientEvents;
import dev.stannismod.stellurgy.test.client.SeatDelivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import dev.stannismod.stellurgy.test.ArrangementFailure;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

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
 * <p><b>The one arrangement knob</b> is the home world's orbit line, stated at its lowest so the
 * powered climb takes seconds instead of minutes. It changes how LONG the climb is, not what the crossing
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
     * The eviction announcements already made for this test's own logs. Per test INSTANCE: this class
     * boots its harness per test (or manages it itself), so the server and client whose counters it
     * compares live no longer than this instance.
     */
    private final EvictionReports evictions = new EvictionReports();

    private EvictionReports evictionReports() {
        return evictions;
    }

    /**
     * How many addresses the console must list for the pilot to have SOMEWHERE to fly.
     *
     * <p>The TEST'S OWN: one address is a list with no choice in it, so two is the smallest number
     * at which the screen is doing its job.</p>
     */
    private static final int MIN_DESTINATIONS_LISTED = 2;

    /**
     * How near the body a pilot must get by flying at it, in blocks of remaining offset.
     *
     * <p>The TEST'S OWN: six blocks is inside the craft's own length, which is what "he arrived"
     * means for a body he then has to descend onto.</p>
     */
    private static final double ARRIVED_BESIDE_IT_BLOCKS = 6;

    /**
     * How far above the pad's surface the client may report the body and still be STANDING on it.
     *
     * <p>The TEST'S OWN: still falling here means the descent has not finished, which is a
     * different finding from having landed badly.</p>
     */
    private static final double STANDING_ON_THE_PAD_BLOCKS = 1.5;

    /**
     * How long the jump trigger's verdict may take to appear after the key goes down, in ticks.
     *
     * <p>A deadline for a discrete decision, not a settle: the press is answered on the tick the
     * server handles it, and the whole budget is there so a loaded box cannot turn a decision that
     * happened into one that did not. An expiry means the press never reached the ship.</p>
     */
    private static final int JUMP_PRESS_BUDGET_TICKS = 200;

    /**
     * How long a container slot click is given to have been APPLIED, in ticks.
     *
     * <p>A bound on a round trip, not a settle and not a poll budget: the click goes to the server,
     * the container applies it, the inventory comes back. Two seconds is generous for one exchange
     * on any box this suite runs on, and a click that has not landed in that time has not landed.
     * A server-side {@code give} is the second half of the same exchange alone — the slot write
     * coming back — so it is bounded by the same number.</p>
     */
    private static final int SLOT_APPLIED_TICKS = 40;

    private static final String COUNT = "count";
    private static final String LEDGER = "ledger";
    private static final String SLOT_DIMS = "slotDims";
    private static final String SHIP_ID = "shipId";
    private static final String NAV_TARGET = "target";
    private static final String NAV_ARMED = "armed";
    private static final String NAV_SHIP_ADDRESSES = "ship";
    /** One body of a {@code space bodies} ship entry, by the names the probe writes. */
    private static final String SHIPS = "ships";
    private static final String BODY_DIM = "dim";
    private static final String BODY_DESCEND_TARGET = "descendTarget";
    private static final String BODY_DISTANCE = "distance";
    /** The SKY each live cell is sent, from {@code space bodies}: keyed by the slot world it is for. */
    private static final String FEED = "feed";
    private static final String FEED_SLOT_DIM = "slotDim";
    private static final String FEED_BODIES = "bodies";
    private static final String FEED_DESCEND = "descend";
    private static final String FEED_DIR = "dir";
    /** A sky body's descent shell as sent, the radius its descent trigger fires inside. */
    private static final String FEED_SHELL = "shellRadius";
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
        Reply aim = Reply.of("stellurgytest nav status", navStatus);
        return aim.bool(NAV_TARGET_DESCEND_TARGET)
                && !aim.bool(NAV_TARGET_SLOT_WORLD);
    }

    /** The body the console is aimed at, from {@code nav status}. */
    private static final String NAV_TARGET_DIM = "targetDim";
    /** The bodies of ONE cell, from {@code space cell-info} (not the whole system's list). */
    private static final String CELL_BODIES = "cellBodies";
    /** Slot dimension ids that Stellurgy also holds a body for — always empty. */
    private static final String SLOT_DIMS_ALSO_BODIES = "slotDimsAlsoBodies";

    /**
     * A jump-capable craft with a walkable deck that carries its own power: the ship this milestone is
     * about. The plug on its capacitor is the player's generation — the bank fills from the ship's grid,
     * never from a probe.
     */
    private static final String VARIANT = "with-powered-jump-drive";

    /**
     * Where the player's own build stands: at the place he enters the world, on its ground.
     *
     * <p><b>He walks to everything he uses.</b> An e2e never teleports the player (maintainer,
     * 2026-10-08: "tp действительно надо запретить"), so the build has to stand where he already is:
     * the observatory and the craft's pad are laid a few blocks from the spot the server spawns him
     * on, on one flat floor at the height of the ground he stands on. Laying that floor and those
     * structures is a FIXTURE — a fast-forward of blocks he would have placed one by one, the named
     * exception — and nothing a fixture lays acts on its own: he presses every button.</p>
     *
     * <p>The ground is the subject of nothing here but it is real terrain, so the site is a GROUND
     * site and the volume above it is cleared and reported, never asserted empty. Assigned once, at
     * leg 0, from the client's own reading of where he stands.</p>
     */
    private FixtureSite site;
    /** The craft site's corner and floor: the pad is laid at {@code by}, its top surface is {@code by + 1}. */
    private int bx, by, bz;
    /** The observatory's controller, laid on the same floor. */
    private int[] observatoryAt;
    /** The block he spawned standing on: x, floor y, z. */
    private int[] spawnFloor;

    /**
     * The HOME world's atmosphere ceiling, stated in leg 0 as its planet file would state it: the lowest
     * line production accepts. The ONE arrangement knob in this test — it shortens the first climb from
     * minutes to seconds and does not touch the entry predicate, which asks whether the ship is above
     * the line, not where the line is. The destination is minted during the loop and keeps its BODY's
     * line; leg 9 reads that one from the server.
     */
    private static final int ORBIT_LINE = dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y;

    /** Seat and standing square, as offsets from the ship's FLIGHT COMPUTER (the deck layout). */
    private final int[] offSeat = {1, 0, 0};
    private final int[] offStand = {1, 0, 1};
    /**
     * The navigation console, as an offset from the flight computer: two cells beyond the seat over
     * the one square the drive bay leaves to stand on, so the seated pilot has a clear sightline to
     * it without leaving his seat.
     */
    private final int[] offNav = {1, 0, 2};

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
    /**
     * The DEADLINE for one of the rocket assembler's timed passes to end, in server ticks — not how
     * long a pass is expected to take, which is production's function of the fixture's volume. This
     * is the 90 x 40 the Build-re-pressing loop it replaced was given for the scan and the build
     * together, now given to each, so it binds only on a pass that never ends.
     */
    private static final int PASS_TICKS = 3600;

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
    private static final String CRYSTAL_ITEM = "stellurgy:memoryCrystal";

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
        Path stellurgyConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(stellurgyConfigDir);
        // Seeded BEFORE the server boots, because the config is read once at load. The file key is
        // `rocketsRequireFuel`; the field (and the probe that reads it back) is `rocketRequireFuel`
        // — the assertion in leg 0 is what proves this file was actually parsed rather than silently
        // ignored for a syntax the config reader did not recognise.
        String cfg = "# seeded by the milestone e2e\n"
                + "rockets {\n"
                + "    B:rocketsRequireFuel=false\n"
                + "}\n";
        Files.write(stellurgyConfigDir.resolve("stellurgy.cfg"), cfg.getBytes(StandardCharsets.UTF_8));
        seedTheHomeSystemWithItsOrbitLine(stellurgyConfigDir);

        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startFailed) {
            serverHarness.close();
            serverHarness = null;
            throw startFailed;
        }
        // The loop's pilot-input delivery chain, both halves, for the failure messages below: a
        // window opened with the pair, so its counts are this run's.
        seatDelivery = SeatDelivery.open(this::exec, bot(),
                serverEvents(), clientEvents());
    }

    /**
     * The home world's orbit line, STATED the one way a world states it: {@code <orbitHeight>} in the
     * planet file (Earth's own line is 100 000 world blocks, a climb of minutes).
     *
     * <p>A planet file replaces the whole built-in universe, so the file this world loads must be that
     * universe and nothing else. It is not written by hand: a throwaway server boots the built-in
     * universe on the same seed and saves it, the game writes its own {@code planetDefs.xml} (a writer
     * made to round-trip), and that file — with one {@code <orbitHeight>} added to Earth — is what this
     * world is created from. So the copy cannot drift from what the mod ships: it is taken afresh
     * every run. Refused loudly if Earth is not there exactly once, already carries a line, or the
     * file holds no Luna.</p>
     */
    private static void seedTheHomeSystemWithItsOrbitLine(Path configDir) throws Exception {
        Path harvestRoot = Files.createTempDirectory("forge-m1-universe-");
        try {
            RealDedicatedServerHarness.startWith(harvestRoot, false).close();
            Path written = harvestRoot.resolve("world").resolve("advRocketry").resolve("planetDefs.xml");
            requireArranged("the built-in universe's server must have written its planet file at "
                    + written, Files.isRegularFile(written));
            org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(written.toFile());
            org.w3c.dom.NodeList planets = doc.getElementsByTagName("planet");
            org.w3c.dom.Element earth = null;
            boolean luna = false;
            for (int i = 0; i < planets.getLength(); i++) {
                org.w3c.dom.Element planet = (org.w3c.dom.Element) planets.item(i);
                if ("0".equals(planet.getAttribute("DIMID"))) {
                    requireArranged("the planet file must hold Earth (DIMID 0) exactly once", earth == null);
                    earth = planet;
                }
                luna |= "Luna".equals(planet.getAttribute("name"));
            }
            requireArranged("the planet file must hold Earth (DIMID 0): " + written, earth != null);
            requireArranged("the planet file must hold Luna, or it is not the built-in home system: "
                    + written, luna);
            requireArranged("Earth must carry no line of its own before this one is stated",
                    earth.getElementsByTagName("orbitHeight").getLength() == 0);
            org.w3c.dom.Element line = doc.createElement("orbitHeight");
            line.setTextContent(Integer.toString(ORBIT_LINE));
            earth.appendChild(line);
            javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
                    new javax.xml.transform.dom.DOMSource(doc),
                    new javax.xml.transform.stream.StreamResult(configDir.resolve("planetDefs.xml").toFile()));
            System.out.println("[M1] home system: the built-in universe's own planet file, Earth's"
                    + " <orbitHeight> stated at " + ORBIT_LINE);
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(harvestRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** This run's pilot-input delivery windows — see {@link SeatDelivery}. */
    private SeatDelivery seatDelivery;

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

    /**
     * The whole tier-2 loop a player drives: learn the Moon's address with the telescope at home,
     * build at the assembler, board, fly up into space, aim at the console, arm, jump beside the Moon,
     * be taken into its zone and keep station there, approach, descend onto it, and leave it.
     *
     * <p>red-witnessed, the MOON links (2026-09-30, one inversion per run, each red at its own line with
     * the earlier legs green): {@code CrystalSeeding#starterFor} at
     * {@code if (home != null && isInZoneOf(coord, home))} — the zone skip — reverted to cell equality —
     * leg T, "…and must NOT carry the home world's moon … starter … [0,2]"; {@code CrystalSeeding#starterFor}
     * at {@code memory.record(new CrystalEntry(home, nameOf(0), SystemBodyKind.PLANET,} — the home record —
     * disabled — leg T, "a first crystal must carry the home world … crystalDims:[]";
     * {@code TelescopeScan#characterise} at {@code for (SystemBody body : registry.systemBodiesAt(anchor))}
     * — the body loop — skipping a {@code MOON} — leg T, "the observatory's
     * local radar, run at home, must write the home world's moon … crystal names [0]";
     * {@code SpaceSubsystem.arrivalStandoff} back on the flat 1 024 ring — leg 7b, "a jump must
     * stand the ship OFF its destination … range=1024 shell=7066"; {@code CellSeam#hasEnteredZone} at {@code return zoneRadiusBlocks > 0d}
     * answering {@code false} — leg 7b, "…no `carry_requested` a granted carry into the moon's zone";
     * {@code UniverseRegistry#originAt} at {@code return originAt(zone, tick).plus(name.sectorX() * width, name.sectorY() * width,} placing a moon-zone cell on the PLANET's origin — leg 7b,
     * "must keep station with it … range 15235 -> 76112 (drift 60877.0) while the moon travelled
     * 60878"; {@code TileAdvancedFlightComputer#descendTargetsIn} at
     * {@code for (dev.stannismod.stellurgy.universe.SystemBody b : reg.skyBodiesAt(shipCoord))} reading the cell ({@code bodiesAt}) instead of
     * the sky — leg 8, "…must be taken DOWN off the space cell … rangeAtArrival=14132 rangeNow=43".
     * NOT witnessed at their own lines: leg 7b's client-follows-into-the-zone wait and seat check, the
     * console listing the moon (leg 6's pick), and the ledger still naming the moon's zone after the
     * station window — each sits behind a link above whose inversion fails first.</p>
     *
     * <p>red-witnessed: one inversion per rung, each red at its own rung with the earlier ones green.
     * THE BUILD (2026-09-28, taken on the pre-2026-10-07 form that handed over a pasted snapshot) —
     * the assembler's hand-over ({@code TileRocketAssemblingMachine#assembleRocket} at {@code VSIntegration.assembleBuiltTier2Ship(world, rocketBB);}) skipped: the rung's helper link fails first, "the BUILD
     * pass must add a ship to the registry — no `ship_spawned` was recorded within 3600 ticks"; the
     * rung's own ship count restates that link. THE CRYSTAL'S ADDRESSES —
     * {@code TileNavigationComputer.shipCrystal} reading an empty stack: "putting a memory crystal into
     * the console's SHIP slot must give the pilot a list of places he can fly to … listed=0"; the wait
     * before it is the server's receipt of the click, a barrier. THE PICK — the {@code NET_PICK} branch
     * of {@code TileNavigationComputer.useNetworkData} not calling {@code setTargetBody}: "a click on a
     * listed address must reach the navigation computer … no `nav_target_picked`". THE ARM — the
     * {@code NET_ARM} branch never calling {@code arm()}: "pressing ARM must reach the navigation
     * computer and be ANSWERED … no `nav_arm_decided`". THE JUMP KEY —
     * {@code TileAdvancedFlightComputer.onJumpKey} returning at once: "the jump key, pressed by a
     * seated pilot of a ARMED ship, must be ANSWERED … no `jump_press_decided`". THE LATCH
     * (2026-09-24) — {@code TileAdvancedFlightComputer#update} at {@code entryLatched = false} removed: leg
     * 9 fails with "no `entry_latch_released` carrying ship = …" after the pilot has flown down through
     * the line. Leg 9's stay-put verdict after it and its {@code STARTED} control have no witness at
     * their own lines: without the latch the ship bounces on arrival and leg 8 fails first, and
     * without the on-ramp the client-world wait fails before the {@code STARTED} read.</p>
     *
     * <p>THE BANK, fed by the craft's own grid — red-witnessed: with {@code TileJumpCapacitor#acceptCharge} at {@code charge = charge() + accepted;} replaced by {@code }, fails: "the ship's own power must have filled its jump bank" (2026-10-08).</p>
     */
    @Test
    public void aPlayerBuildsHisShipAtTheAssemblerBoardsItAndFliesItOffThePlanet() throws Exception {

        // THE MULTIPLIER STAYS. What it waits on is VS building the ship on its OWN thread, off the
        // game loop: that work finishes in wall-clock time, so a busy box genuinely needs more game
        // ticks to elapse before it is done. Measured at 8 forks on the sibling gate test.
        int budget = 40;
        // The server's ordered log, opened at the top of the loop rather than at leg 4. Every leg
        // from the boarding onward reads it now — the seat's verdict, the console's, the crossings'
        // — and a reader that only exists from halfway down is one more reason for the early legs to
        // keep polling values.
        Events events = serverEvents();
        long tLeg = System.currentTimeMillis();

        // Leg 4 says why, and it holds for the whole loop: an observer is aboard the whole way, so
        // keeping the ship loaded is production's job here — a harness affordance doing it would
        // hide the failure to.
        dev.stannismod.stellurgy.test.ShipReadiness.letShipsUnload(this::exec,
                "this milestone walks a player's own loop with an observer aboard, and production is"
                + " what must keep the ship loaded across the crossing");

        // ---- LEG 0: the world this loop is walked in is the one the test asked for. -------------
        String fuelCfg = exec("stellurgytest config get rocketRequireFuel");
        requireArranged("the seeded config file must have been PARSED. This reads the live "
                        + "field back through the same config object production uses, so a stale "
                        + "`true` here means the file was written in a syntax the config reader "
                        + "skipped and every later leg would be running against defaults: " + fuelCfg,
                (!Reply.of(fuelCfg).bool("value")));
        // The home world's line, stated in the planet file this world was created from (see
        // seedTheHomeSystemWithItsOrbitLine) and read back as production reads it. `stated` says the
        // file's <orbitHeight> is what production holds, not a line derived from Earth's radius.
        OrbitLine homeLine = OrbitLine.of(this::exec, 0);
        requireArranged("the home world's orbit line must be the one its planet file states — a"
                        + " line that is not `stated` means the seeded file was never loaded: " + homeLine,
                homeLine.stated && homeLine.line() == ORBIT_LINE);

        SubsystemStatus status = SubsystemStatus.read(this::exec);
        requireArranged("the production space subsystem must be REGISTERED — it owns the "
                        + "cells a ship arrives in, and without it the climb leg has nowhere to go: "
                        + status.raw(),
                status.registered);
        // No dimension id may be owned twice. A space slot is a void world the subsystem rebinds at
        // will; a body is a place the universe registry describes, a crystal can name and a ship can
        // be flown to. One id doing both makes the registry's description a lie — it advertises a
        // planet whose world is empty space — and the descent that follows lands the ship nowhere.
        int[] collided = status.slotDimsAlsoBodies();
        assertTrue("no space-slot dimension may also be a Stellurgy body. Forge's free-id "
                        + "scan cannot see Stellurgy's own body ids (a surface-less body is never registered "
                        + "with Forge), so the pool can take one unless it asks Stellurgy too. "
                        + "slotDimsAlsoBodies=" + java.util.Arrays.toString(collided)
                        + " status=" + status.raw(),
                collided.length == 0);
        System.out.println("[M1] leg 0 (config + subsystem) " + elapsed(tLeg) + " status=" + status.raw());
        layTheHomeSite();

        // ---- LEG T: at home, he looks at the sky and writes the Moon down. --------------------------
        // The rule this link walks (maintainer ruling 2026-09-30): a moon has a cell of its own inside
        // its planet's zone, so the home world's address does not cover it. A first crystal carries
        // the home world and nothing inside its zone; the observatory's local radar, standing in the
        // home system, is where a player learns the Moon's address. So the crystal he later flies by
        // is the one this telescope wrote, carried by hand.
        tLeg = System.currentTimeMillis();
        homeMoon = homeMoon();
        String starter = exec("stellurgytest telescope starter 0");
        int[] starterDims = Reply.of("stellurgytest telescope starter", starter).intArray("crystalDims");
        assertTrue("a first crystal must carry the home world — without it the absence below is also"
                + " what an empty crystal says: " + starter, containsInt(starterDims, 0));
        assertTrue("…and must NOT carry the home world's moon (" + homeMoon + "): its address is"
                + " found with a telescope. starter=" + starter, !containsInt(starterDims, homeMoon.dim));

        int[] observatory = placeHomeObservatory();
        standOnFloor(observatory[0] + 0.5, observatory[2] - 1.5,
                new int[]{observatory[0], observatory[1] - 1, observatory[2] - 2}, "iron_block");
        emptyTheHand();
        handTheBlankCrystal();
        holdNothing();
        String observatoryScreen = openScreenByRealKeyPress(observatory, "observatory",
                "observatory", budget);
        assertTrue("a real use-key press aimed at the OBSERVATORY must open its screen — every act of"
                        + " a survey is performed there. screen=\"" + observatoryScreen + "\"",
                observatoryScreen.startsWith("dev.stannismod.stellurgy.libvulpes.inventory.GuiModular"));
        TelescopeReading surveyed = surveyTheHomeSkyOntoTheCrystal(observatory, events, budget);
        assertTrue("the observatory's local radar, run at home, must write the home world's moon onto"
                        + " the crystal (" + homeMoon + ") — it is the only place a player learns"
                        + " that address. crystal names " + java.util.Arrays.toString(surveyed.crystalDims())
                        + ": " + surveyed.raw(),
                containsInt(surveyed.crystalDims(), homeMoon.dim));
        takeTheCrystalBackIntoTheHotbar(budget);
        System.out.println("[M1] leg T (telescope at home) " + elapsed(tLeg) + " moon=" + homeMoon
                + " crystal=" + java.util.Arrays.toString(surveyed.crystalDims()));

        // ---- LEG 1: stand the craft up on a pad. Blocks only — no interaction happens here. -----
        tLeg = System.currentTimeMillis();
        int[] builderPos = placeFixture();
        layTheStepsToTheDeck();
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
                        + " spawnDiag=" + exec("stellurgytest invoke-static dev.stannismod.stellurgy.test.trace.SpawnMemory snapshot")
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
        String nameReply = exec("stellurgytest vs ship-name 0 " + describeArgs(afcSub));
        requireArranged("the built ship's flight computer must carry a durable name, or nothing"
                + " after the first crossing can be addressed to this craft: " + nameReply,
                Reply.of(nameReply).bool("found"));
        builtShipName = readString(nameReply, SHIP_ID);

        // The subspace copy is a RIGID relocation of the pad build, so the seat must sit at exactly
        // the offset it was built at. Without this control the deck square the pilot is placed on
        // below is a guess, and a failed press could just as easily be a bot standing nowhere.
        requireArranged("CONTROL: the ship's subspace copy must preserve the build's own "
                        + "geometry — seat minus flight computer should be " + describe(offSeat)
                        + " but is " + describe(new int[]{seatSub[0] - afcSub[0],
                                seatSub[1] - afcSub[1], seatSub[2] - afcSub[2]}) + ": " + found,
                seatSub[0] - afcSub[0] == offSeat[0]
                        && seatSub[1] - afcSub[1] == offSeat[1]
                        && seatSub[2] - afcSub[2] == offSeat[2]);

        // A held stack consumes the use press before the block ever sees it.
        emptyTheHand();

        Aim seatAim = aimAt(afcSub, seatSub, offStand, 0.5, 0.2, 0.5, budget);
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
        // The RECORD, not the reply: `await` answers with the whole `events since` envelope, whose
        // top level carries no `managed` at all. Read as a reply this field resolved to null, and
        // `parseBoolean(null)` is false — so the assertion could not pass, whatever the seat decided.
        assertEquals("…and the seat must know it belongs to a SHIP. An unmanaged seat is a chair: it "
                        + "seats him and carries no flight input at all, which is a green boarding "
                        + "followed by a craft that will not answer its controls. decision="
                        + sitDecision,
                "true", Events.text(Events.lastRecord(sitDecision), "managed"));

        JsonObject riding = awaitClientMount(seatClientMark,
                "a real use-key press aimed at the ship's PILOT SEAT must seat the pilot, as the "
                        + "CLIENT itself performs it — the crosshair was proven to be on that block "
                        + "and the seat agreed it belongs to a ship, so a silence here is the mount "
                        + "never happening on his side", budget, seatAim.diagnosis);
        String serverRiding = exec("stellurgytest player riding-entity");
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
        int climbBudget = 4000;
        bot().holdKey(Keyboard.KEY_R);
        try {
            events.assertChain(entryMark, "a ship climbing under its own power past the orbit line ("
                    + ORBIT_LINE + ") must be taken by the entry crossing and SETTLE in a space cell —"
                    + " that is the on-ramp a real player flies, and holding one key is the whole of"
                    + " his input", climbBudget, Chains.grantedEntry());
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        SubsystemStatus statusAfter = SubsystemStatus.read(this::exec);
        String entryDecisions = events.since(entryMark, "entry_decided");
        // The RECORD again: `decision` is a field of an entry_decided record, and asking the reply
        // for it reads the envelope's top level, where it is not. The subject here is the window —
        // marked immediately before the climb, and this scenario flies one ship — so the last
        // record in it is this climb's decision and no other's.
        assertEquals("the entry gate must have GRANTED this entry (STARTED): " + entryDecisions
                        + " status=" + statusAfter.raw(),
                "STARTED", Events.text(Events.lastRecord(entryDecisions), "decision"));
        System.out.println("[M1] leg 4 (powered climb to the cell) " + elapsed(tLeg)
                + " status=" + statusAfter.raw());

        // ---- LEG 5: the arrival, measured from the CLIENT. --------------------------------------
        tLeg = System.currentTimeMillis();
                String slotDims = "," + joinInts(statusAfter.slotDims()) + ",";

        // (1) The client's OWN world is a space cell — the pilot followed his ship through the seam
        // or he did not, and nothing server-side can answer that for him. Read off the client's own
        // record of its dimension changes since the climb began, in order: a world rebuilt twice
        // between two samples shows one change or none, and the records show both.
        int clientDim = awaitClientWorld(entryClientMark, slotDims,
                "after the crossing the CLIENT itself must be in a space-cell dimension — a pilot "
                        + "whose ship left without him is the exact failure this leg exists to catch."
                        + " slotDims=[" + slotDims + "] status=" + statusAfter.raw(),
                budget * 5);

        // (2) Still seated — and the crossing's own seat chain says how.
        JsonObject arrivalRiding = assertStillSeated(events, entryMark, entryClientMark,
                "the pilot who FLEW his own ship into space must still be in his seat on arrival — a "
                        + "crossing must never stand him up. clientDim=" + clientDim
                        + " delivery=" + seatDelivery.reading(),
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
        String afcProbe = exec("stellurgytest space find-afc " + slotDim + " " + builtShipName);
        requireArranged("the ship the pilot flew up must be findable in the space cell he "
                        + "arrived in — the ledger says he is here, so a ship that cannot be located "
                        + "means the arrival left no body behind: " + afcProbe,
                Reply.of(afcProbe).bool("found"));
        String shipId = readString(afcProbe, SHIP_ID);
        // The reader refuses a ship the ledger has no entry for, and refuses to answer a cell for
        // one — which is what this arrangement check stood for, and it no longer has to ask the
        // probe a second time to say so.
        String launchCell = LedgerEntry.forShip(this::exec, shipId)
                .requireFound("the ledger must name the cell the jump departs FROM — without it the"
                        + " arrival assertion below cannot tell a jump from a no-op. shipId="
                        + shipId)
                .cellKey();

        // Read while he is still SEATED, so the ship's own world point is on record before the one
        // moment in this loop when the pilot is not a reliable pointer to his craft.
        String seatProbe = findSeatAboard(slotDim, budget);
        int[] navAfcSub = readTriple(seatProbe, "afcX", "afcY", "afcZ");
        requireArranged("the arrived ship must still expose the seat and the flight computer "
                        + "it is linked to — the console the pilot reaches for is addressed from that "
                        + "computer: " + seatProbe,
                navAfcSub != null);
        int[] navSub = add(navAfcSub, offNav);

        // The drive's bank, READ — never filled by a probe. The craft carries its own power: the plug
        // standing on the capacitor has pushed into it every tick since it first ticked, at the bank's
        // own accept rate, so the window this leg opens is paid for by the ship's grid, the way a
        // player's generation pays for it. A bank short of its burst here is that grid failing to
        // feed it — the link between "he built power aboard" and "his drive can jump".
        DriveInfo bank = DriveInfo.of(exec("stellurgytest drive info " + slotDim + " "
                + describeArgs(navAfcSub)));
        System.out.println("[M1] drive bank fed by the ship's own grid: charge=" + bank.charge
                + " burst=" + bank.burstCost + " capacity=" + bank.capacity);
        assertTrue("the ship's own power must have filled its jump bank by the time the pilot reaches"
                        + " the console: the plug on the capacitor pushes into it every tick, so a bank"
                        + " below its burst means the grid aboard is not feeding the drive and no window"
                        + " could open. " + bank,
                bank.charge >= bank.burstCost);

        // He stands up to navigate. Not a convenience: while a pilot is at the controls the client
        // holds his view ON THE SHIP'S ATTITUDE every tick, so his crosshair is locked dead ahead and
        // level and cannot be put on anything — the console included. Navigation is therefore deck
        // work, which is what the one clear square between the seat and the console is for. Standing
        // up is an ACT, so it is a real key: sneak, the way anyone leaves a vehicle.
        standUp(events, budget);

        // The crystal is the one the telescope wrote at leg T, carried in his hotbar through both
        // crossings; putting it IN the console is the act that gives the ship its addresses.
        // Hold nothing: the console is opened with a bare hand, so nothing can eat the use press, and
        // the crystal is then moved by real slot clicks rather than by being used from the hand.
        holdNothing();

        String consoleScreen = openConsoleFromTheDeck(slotDim, navAfcSub, navSub, budget);
        assertTrue("a real use-key press aimed at the NAVIGATION CONSOLE must open its screen on the "
                        + "client — every choice the pilot makes about where to fly is made on that "
                        + "screen, so a console that swallows the press strands a jump-capable ship. "
                        + "screen=\"" + consoleScreen + "\"",
                consoleScreen.startsWith("dev.stannismod.stellurgy.libvulpes.inventory.GuiModular"));

        // Move the crystal into the SHIP slot with an explicit pick-up / put-down pair. A shift-click
        // would merge it into the source slot instead, where the address list is never read from —
        // silently, with no error — so the two clicks are what a player actually performs here.
        JsonObject slots = bot().reportSlots();
        int crystalSlot = slotHolding(slots, CRYSTAL_ITEM);
        requireArranged("the crystal handed to the pilot must be reachable from the console's "
                        + "own window — the console shows the hotbar and nothing else, so a crystal "
                        + "anywhere but the hotbar could never be inserted. slots=" + slots,
                crystalSlot >= 0);
        // No advance between the two clicks: both travel on one connection and the server applies
        // them in the order sent, each against the client's prediction of the one before.
        bot().clickSlotAt(crystalSlot, 0);
        long insertMark = clientEvents().mark();
        bot().clickSlotAt(CONSOLE_SLOT_SHIP, 0);

        // A WINDOW, then ONE READ — and NOT a wait for `crystal_copied`, which is a different
        // mechanic entirely. Measured 2026-09-12, by getting this wrong: the console's copy
        // (`copySourceIntoShipCrystal`) has exactly ONE caller in production, the COPY button
        // (`useNetworkData`, `NET_COPY`), and it merges the SOURCE slot into the ship's crystal. It
        // is not what an insertion triggers, and nothing triggers it here. The ship's address list
        // is not the product of any action at all: `shipCrystal()` reads
        // `memoryOf(getStackInSlot(SLOT_SHIP))`, so once the click has been applied the list simply
        // IS what the crystal in that slot knows.
        //
        // So nothing in the CONSOLE records the insertion — but the click itself has a receipt:
        // the server answers every container click it handles, after its own slotClick ran. That
        // is the barrier, and only that: its `accepted` compares the stacks the click RETURNED,
        // which for a pickup into an empty slot are empty on both sides whatever happened. What
        // the insertion did is the list the server reads next.
        clientEvents().await(insertMark, "client_click_confirmed",
                "the server must handle the click that puts the crystal into the console's SHIP slot",
                SLOT_APPLIED_TICKS);
        String navStatus = exec("stellurgytest nav status " + slotDim + " " + describeArgs(navSub));
        int listed = readIntOr(navStatus, NAV_SHIP_ADDRESSES, 0);
        assertTrue("putting a memory crystal into the console's SHIP slot must give the pilot a list "
                        + "of places he can fly to — the ship's addresses ARE that crystal's, so an "
                        + "empty list here means the insertion never landed, and a list of ONE leaves "
                        + "him only the cell he is already in. listed=" + listed
                        + " nav=" + navStatus + " slots=" + bot().reportSlots()
                        + " | screen=" + screenOf(bot().reportState()),
                listed >= MIN_DESTINATIONS_LISTED);

        // Reopen the window: its buttons are built when the screen is, so the list the pilot clicks
        // on is the one he sees after the crystal is in. Closing and looking again is what he does.
        // No settle: the close and the right-click that reopens travel in order on one connection.
        bot().pressScreenKey(Keyboard.KEY_ESCAPE);
        consoleScreen = openConsoleFromTheDeck(slotDim, navAfcSub, navSub, budget);
        requireArranged("the console must reopen once the crystal is in it: " + consoleScreen,
                consoleScreen.startsWith("dev.stannismod.stellurgy.libvulpes.inventory.GuiModular"));

        // He picks where to go. Not blind: he clicks an address, sees what the console says is at it,
        // and moves on if that is not somewhere he can land — which is what the pick button's own body
        // readout is for. Every pick is a real button click; only the "what is there" is read by probe.
        // An address naming the cell he is already in is passed over by the cell check below: the
        // gate refuses a jump there, and nothing about the list's order says where it sits.
        int pickIndex = -1;
        int targetDim = Integer.MIN_VALUE;
        String targetCell = "";
        String targetInfo = "";
        StringBuilder considered = new StringBuilder();
        for (int candidate = 0; candidate < LISTED_ADDRESSES && pickIndex < 0; candidate++) {
            // The CLICK IS AWAITED, not slept off. Ten ticks was a guess that has to cover a button
            // press travelling to the server and the computer answering it; when it did not, the
            // reading below was of the PREVIOUS candidate's target and the search silently
            // considered the same address twice.
            long pickMark = events.mark();
            bot().clickButtonAt(BUTTON_PICK_FIRST + candidate);
            events.await(pickMark, "nav_target_picked",
                    "a click on a listed address must reach the navigation computer — the console's "
                            + "buttons are the only way a pilot chooses where to go, and a press that "
                            + "is swallowed leaves him reading the target he picked last time",
                    budget * 5);
            String picked = exec("stellurgytest nav status " + slotDim + " " + describeArgs(navSub));
            String cell = unquote(readString(picked, NAV_TARGET));
            considered.append(' ').append(candidate).append("->").append(cell)
                    .append(picked.substring(Math.max(0, picked.indexOf('{'))));
            if (cell.isEmpty() || "null".equals(cell) || cell.equals(launchCell)) {
                continue;
            }
            // He is flying to the MOON the telescope showed him, so that is the address he takes.
            if (isLandable(picked)
                    && readIntOr(picked, NAV_TARGET_DIM, Integer.MIN_VALUE) == homeMoon.dim) {
                pickIndex = candidate;
                targetCell = cell;
                targetDim = readIntOr(picked, NAV_TARGET_DIM, Integer.MIN_VALUE);
                targetInfo = picked;
            }
        }
        assertTrue("the addresses the telescope wrote must offer the pilot the MOON (" + homeMoon
                        + ") as somewhere he can fly to and land on — the survey named it at leg T,"
                        + " so a console that does not list it lost what the crystal carried."
                        + " considered=" + considered,
                pickIndex >= 0);
        assertTrue("a listed address must name a BODY, not merely a point in space — that identity is "
                        + "what keeps the ship aimed at the planet the pilot chose while it flies, and "
                        + "without it the console is handing him a coordinate the destination has "
                        + "already left. nav=" + targetInfo,
                targetDim != Integer.MIN_VALUE && targetDim >= 0);
        // No settle before ARM: the pick it depends on was already read back from the server above.
        long armMark = events.mark();
        bot().clickButtonAt(BUTTON_ARM);

        // The console's OWN VERDICT on the press, not a poll of the flag it sets. `arm()` answers
        // ARMED or REFUSED_NO_TARGET, and the difference is the whole diagnosis: a poll that spends
        // its budget reports "armed=false" for a press that never arrived, for a press that arrived
        // and was refused, and for a console that armed a tick after the last sample — three
        // different faults behind one sentence.
        String armDecision = events.await(armMark, "nav_arm_decided",
                "pressing ARM must reach the navigation computer and be ANSWERED — that press is how "
                        + "a player commits to a destination, and an unarmed ship refuses the jump "
                        + "key outright", budget * 5);
        String armedStatus = exec("stellurgytest nav status " + slotDim + " " + describeArgs(navSub));
        // Two claims, so two asserts. `armDecision` is the console's answer to the press, read off
        // the event window; `armedStatus` is a live status fetched afterwards. Conjoined, a red said
        // only that one of the two was false and handed the reader two rendered replies to diff —
        // and the two are not even the same kind of fault: a press that was REFUSED and a press that
        // was accepted onto a computer that then forgot it are different bugs.
        assertEquals("pressing ARM must be ANSWERED with ARMED — that press is the whole of how a "
                        + "player commits to a destination, and a refusal here is the console "
                        + "declining the commit. The console's own verdict: " + armDecision,
                "ARMED", Events.text(Events.lastRecord(armDecision), "outcome"));
        assertEquals("...and the arming the console reported must be the state the ship is actually "
                        + "in — a computer that answers ARMED and does not hold it refuses the jump "
                        + "key with nothing to show the pilot why. nav=" + armedStatus,
                "true", readString(armedStatus, NAV_ARMED));
        // A second clause stood here: that the pilot is TOLD, in his own chat, that the ship is
        // armed. It is gone — the chat line is a rendering of the arming, and the arming itself is
        // what the line above reads, off the navigation computer's own state.

        // Judged on the BODY, not on the aim cell. The aim is the computer's prediction of where that
        // body will be when the ship arrives, so it legitimately moves between the pick and the arm —
        // the planet is orbiting. What must NOT change is which planet.
        assertTrue("…and the destination it is armed at must still be the BODY he picked — a ARM that "
                        + "quietly re-aimed the ship would send him somewhere he never chose. "
                        + "pickedDim=" + targetDim + " nav=" + armedStatus,
                targetDim == readIntOr(armedStatus, NAV_TARGET_DIM, Integer.MIN_VALUE));
        assertTrue("…and the ship must still be able to say WHERE that body is: an armed jump whose "
                        + "target cannot be located is a burst about to be spent on nothing. nav="
                        + armedStatus,
                Reply.of(armedStatus).bool("targetResolved"));
        System.out.println("[M1] leg 6 (target picked + armed at the console) " + elapsed(tLeg)
                + " launchCell=" + launchCell + " pick=" + pickIndex + " targetDim=" + targetDim
                + " target=" + targetCell
                + " targetInfo=" + targetInfo + " considered=" + considered
                + " drive=" + exec("stellurgytest drive info " + slotDim + " " + describeArgs(navAfcSub)));

        // ---- LEG 7: he fires the jump with the real jump key. -----------------------------------
        tLeg = System.currentTimeMillis();
        // The key handler bails outright while any screen is up, so the console is shut first — the
        // same thing a player does before reaching for the controls: escape. The screen's own key
        // handler closes it on the client thread before the verb answers, which is where the key
        // handler looks.
        bot().pressScreenKey(Keyboard.KEY_ESCAPE);

        // And he sits back down: the jump key is the PILOT's, routed through the seat he occupies, so
        // a player standing on his own deck cannot fire the drive he just armed.
        JsonObject reboarded = sitBackDown(events, slotDim, navAfcSub, budget);
        requireArranged("the pilot must be able to take his seat again after navigating — the "
                        + "jump he armed is fired from the controls, not from the deck. riding="
                        + reboarded + " serverRiding=" + exec("stellurgytest player riding-entity"),
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
                "the jump key, pressed by a seated pilot of a ARMED ship, must be ANSWERED — the trigger"
                        + " decides something for every press, and a silence here means the"
                        + " press never reached the ship at all",
                JUMP_PRESS_BUDGET_TICKS);
        String outcome = Events.text(Events.lastRecord(pressed), "outcome");
        String jumpBranch = "spooling";
        if (!"SPOOLING".equals(outcome)) {
            assertEquals("a press that did not spool must be the gate's ADVISORY, which is an 'are"
                            + " you sure' rather than a refusal — anything else is the jump being"
                            + " turned down. presses=" + pressed
                            + " delivery=" + seatDelivery.reading()
                            + " riding=" + bot().reportRidingEntity()
                            + " drive=" + exec("stellurgytest drive info " + slotDim + " "
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
                            + exec("stellurgytest drive info " + slotDim + " " + describeArgs(navAfcSub)),
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
        // The FIRST settle after the press is the jump's own commit. A later one is a crossing the
        // arrival itself triggers — beside a moon, the sphere carry into the moon's own zone, whose
        // cells hold no body by construction — and reading that one as "where the jump went" made
        // the verdict below a race against the carry.
        for (String record : Events.recordsWhere(settled, "ship", shipId)) {
            String cell = Events.text(record, "cell");
            if (cell != null && !cell.isEmpty()) {
                arrivedCell = cell;
                break;
            }
        }
        String ledgerAfterJump = exec("stellurgytest space ledger-get " + shipId);
        assertTrue("a jump the pilot armed and fired must MOVE the ship to another cell — the whole "
                        + "point of the hyperdrive is that the craft is somewhere else afterwards. "
                        + "launchCell=" + launchCell + " arrivedCell=" + arrivedCell
                        + " target=" + targetCell + " ledger=" + ledgerAfterJump
                        + " status=" + exec("stellurgytest space subsystem-status"),
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
        SubsystemStatus statusAfterJump = SubsystemStatus.read(this::exec);
                String jumpSlotDims = "," + joinInts(statusAfterJump.slotDims()) + ",";
        int jumpDim = awaitClientWorld(jumpClientMark, jumpSlotDims,
                "the pilot who fired the jump must come out of it in a space cell too — a drive "
                        + "that carries the hull and leaves the crew behind has not moved the SHIP. "
                        + "slotDims=[" + jumpSlotDims + "] ledger=" + ledgerAfterJump
                        + " status=" + statusAfterJump.raw(),
                budget * 5);

        JsonObject jumpRiding = assertStillSeated(events, jumpMark, jumpClientMark,
                "the pilot who FIRED the jump must still be in his seat when it ends — he never "
                        + "stood up, so nothing about crossing a cell may stand him up. A red here is "
                        + "the crew capture, the re-seat, or the dimension hand-off, in that order — "
                        + "and the mount chain below says which. clientDim=" + jumpDim
                        + " delivery=" + seatDelivery.reading()
                        + " ledger=" + ledgerAfterJump,
                budget);
        System.out.println("[M1] leg 7 (jump fired on the key) " + elapsed(tLeg)
                + " branch=" + jumpBranch + " " + launchCell + " -> " + arrivedCell
                + " clientDim=" + jumpDim + " riding=" + jumpRiding);

        // ---- LEG 7b: beside the moon — outside its trigger, inside its own zone, and kept there. ----
        tLeg = System.currentTimeMillis();
        // (1) Arriving is not landing. The jump stands the ship off its destination, so the sky he
        // comes out under shows the moon OUTSIDE the radius its descent trigger fires inside — the
        // shell as the packet carries it, never a number this test derives.
        String arrivalSky = exec("stellurgytest space bodies");
        long[] moonAtArrival = skyReadingOf(arrivalSky, homeMoon.dim);
        requireArranged("the moon he jumped to must be in his sky on arrival: " + arrivalSky,
                moonAtArrival != null);
        assertTrue("a jump must stand the ship OFF its destination, outside the moon's descent"
                        + " shell — inside it, the pilot's first key takes him down to a surface he"
                        + " never chose to land on. range=" + moonAtArrival[0] + " shell="
                        + moonAtArrival[1] + " sky=" + arrivalSky,
                moonAtArrival[0] > moonAtArrival[1]);

        // (2) Beside a moon he is inside its sphere of influence, so the moon's OWN zone takes the
        // craft: a carry, granted, into a cell whose key lies inside the moon's.
        String carried = events.awaitMatching(jumpMark, "carry_requested",
                reply -> Events.anyRecordHasAll(reply, "ship", shipId, "granted", "true")
                        && Events.recordsWhere(reply, "ship", shipId).stream().anyMatch(r ->
                                String.valueOf(Events.text(r, "cell")).startsWith(homeMoon.cell + ".")),
                "a granted carry into the moon's zone",
                "a craft that arrives inside the moon's sphere of influence must be taken into the"
                        + " moon's own zone — that is what makes the moon's frame the one he flies in",
                budget * 5);
        // …and HE goes with it, still in his seat: the pilot's client follows the craft into the world
        // that holds the moon's zone, and the crossing's own seat chain says he never stood up.
        final int zoneDim = shipSlotDim(exec("stellurgytest space bodies"));
        final int cellDim = awaitClientWorldMatching(jumpClientMark, dim -> dim == zoneDim,
                "the moon's zone world " + zoneDim,
                "the pilot must be carried into the moon's zone WITH his ship — a carry that moves"
                        + " the hull and leaves the crew in the old cell has not moved the SHIP",
                budget * 5);
        JsonObject zoneRiding = assertStillSeated(events, jumpMark, jumpClientMark,
                "the pilot must still be in his seat after the carry into the moon's zone — a"
                        + " sphere crossing, like any other, must never stand him up. clientDim=" + cellDim
                        + " delivery=" + seatDelivery.reading(),
                budget);

        // (3) He lets go of the controls, and the moon does not leave him. Read as a WINDOW: the range
        // on his sky and where the moon is RELATIVE TO ITS PLANET, at two ticks. The alternative to
        // riding the moon's frame is riding the planet's — the frame a craft beside a moon was carried
        // by before moons had zones — and such a craft sees the range change by at least the moon's
        // travel around the planet less twice the range (triangle inequality); one that is carried
        // sees it hold. The window is sized from the moon's measured speed to twice the least travel
        // (2 × range) at which the two predictions stop overlapping, so the verdict below is not
        // decided at the edge of its own premise.
        long[] moonAt0 = frameOf(homeMoon.cell, homeMoon.homeCell);
        // STIMULUS: a short, fixed sample of the moon's own motion, to size the window below.
        advanceServerAndClient(20);
        long[] moonAt1 = frameOf(homeMoon.cell, homeMoon.homeCell);
        double sampleTravel = travel(moonAt0, moonAt1);
        double perTick = sampleTravel / Math.max(1L, moonAt1[3] - moonAt0[3]);
        requireArranged("the moon must be MOVING, or keeping station with it is no test at all:"
                + " travel=" + sampleTravel + " over clock " + moonAt0[3] + ".." + moonAt1[3],
                perTick > 0.0);
        long[] before = skyReadingOf(exec("stellurgytest space bodies"), homeMoon.dim);
        long[] moonBefore = frameOf(homeMoon.cell, homeMoon.homeCell);
        int stationTicks = (int) Math.ceil(4.0 * before[0] / perTick);
        // EXPERIMENT: the dose is the moon's travel, sized just above; no key is held. Given in pieces
        // because one bridge wait is bounded below the bot's read timeout
        // (ForgeTestClientBootstrap's CLIENT_SIDE_BUDGET_MILLIS), and a dose of minutes does not fit
        // in one.
        for (int given = 0; given < stationTicks; given += STATION_DOSE_PIECE_TICKS) {
            advanceServerAndClient(Math.min(STATION_DOSE_PIECE_TICKS, stationTicks - given));
        }
        long[] after = skyReadingOf(exec("stellurgytest space bodies"), homeMoon.dim);
        long[] moonAfter = frameOf(homeMoon.cell, homeMoon.homeCell);
        double moonTravel = travel(moonBefore, moonAfter);
        requireArranged("the window must be long enough to tell the two cases apart: the moon"
                        + " travelled " + moonTravel + " blocks against twice the range " + (2 * before[0]),
                moonTravel > 2.0 * before[0]);
        double drift = Math.abs(after[0] - before[0]);
        assertTrue("a craft parked beside the moon, with nobody at the controls, must keep station"
                        + " with it: the range on his sky held within half of what a craft left behind"
                        + " would see. range " + before[0] + " -> " + after[0] + " (drift " + drift
                        + ") while the moon travelled " + moonTravel + " blocks over clock "
                        + moonBefore[3] + ".." + moonAfter[3],
                drift < (moonTravel - 2.0 * before[0]) / 2.0);
        String stillThere = LedgerEntry.forShip(this::exec, shipId)
                .requireFound("the ledger must still hold the parked ship").cellKey();
        assertTrue("…and he is still in the moon's own zone after it: " + stillThere,
                stillThere.startsWith(homeMoon.cell + "."));
        System.out.println("[M1] leg 7b (beside the moon) " + elapsed(tLeg) + " arrival="
                + java.util.Arrays.toString(moonAtArrival) + " carry=" + Events.lastRecord(carried)
                + " station: range " + before[0] + "->" + after[0] + " moonTravel=" + moonTravel
                + " ticks=" + stationTicks + " cell=" + stillThere + " zoneDim=" + cellDim
                + " riding=" + zoneRiding);

        // ---- LEG 8: he takes the ship down to the planet, on a held key. ------------------------
        tLeg = System.currentTimeMillis();
        // What the flight computer will see when it looks around this cell. It takes the NEAREST body
        // inside the descent radius, so that is the one the test must load and the one it must judge —
        // reading it here rather than deciding it keeps the test measuring the production choice.
        String bodies = exec("stellurgytest space bodies");
        int nearestDim = nearestDescendTargetDim(bodies);
        // The cell he left is the control: it is a body's cell too (he took off from it), so if BOTH
        // read empty the registry cannot attribute any cell, and if only the destination does, the
        // console offered an address the rest of the game does not agree exists.
        String arrivedInfo = exec("stellurgytest space cell-info " + cellArgs(arrivedCell));
        String launchInfo = exec("stellurgytest space cell-info " + cellArgs(launchCell));
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

        // The destination world is NOT loaded here: nobody lives on it, and bringing it up is the
        // descent's own job (it pins the target dimension before resolving the arrival). A probe that
        // loaded it would stand in for exactly that, and this loop is where it is walked.

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
        // The burst is a count of GAME ticks, sized from the component it is flying, and it is the
        // same count on every box.
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
        // The cell he is in NOW — the moon's zone, after 7b's carry — is the one a descent takes him
        // out of; the jump's cell is behind him already.
        int descentDim = cellDim;
        // The whole approach, burst by burst, so a red says which component was left standing.
        StringBuilder flown = new StringBuilder();

        // Taken before the first burst: the descent fires from inside this loop, and the pilot's
        // side of it is a world he is carried into — a record, not a value to sample once the
        // carrying is over. The SERVER's mark is taken for the same instant, so the seat chain the
        // arrival is judged on covers the crossing that produced it and nothing earlier.
        long descentClientMark = clientEvents().mark();
        long descentMark = events.mark();

        while (bursts < burstBudget && descentDim == cellDim) {
            aim = nearestDescendTargetVector(feed);
            if (aim == null) {
                // No body left in this cell: the descent has cut the ship out of it. That is this
                // leg SUCCEEDING — but the crossing settles over several ticks and the CLIENT is
                // carried at the end of it, so wait for him. Breaking on the dimension he was in
                // when the trigger fired reads a completed descent as a failed approach.
                descentDim = awaitClientWorldMatching(descentClientMark, dim -> dim != cellDim,
                        "a change out of the cell " + cellDim,
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
                            + " ledger=" + exec("stellurgytest space ledger-get " + shipId)
                            + " bodies=" + feed,
                    stalled < ARRIVED_BESIDE_IT_BLOCKS);

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
                        + "approach. clientDim=" + descentDim + " cellDim=" + cellDim
                        + " rangeAtArrival=" + rangeAtArrival
                        + " rangeNow=" + nearestDescendTargetDistance(exec("stellurgytest space bodies"))
                        + " flown=" + flown
                        + " nearestBodyDim=" + nearestDim
                        + " descentStatus=" + exec("stellurgytest space descent-status")
                        + " ledger=" + exec("stellurgytest space ledger-get " + shipId)
                        + " bodies=" + bodies + " delivery=" + seatDelivery.reading(),
                descentDim != cellDim);
        assertTrue("…and where it puts him down must be a real WORLD, with ground under it. The space "
                        + "subsystem's own slot worlds are empty voids that exist to hold a cell; a "
                        + "descent that ends in one has landed the ship nowhere, and the pilot who flew "
                        + "across a system to reach a planet steps out into nothing. clientDim="
                        + descentDim + " slotDims=[" + jumpSlotDims + "] nearestBodyDim=" + nearestDim
                        + " bodies=" + bodies,
                !jumpSlotDims.contains("," + descentDim + ","));
        assertTrue("…and the world he steps out onto must be the PLANET HE PICKED at the console. "
                        + "That is the whole loop: choose a body, fly to it, land on it. Landing on "
                        + "something else in the neighbourhood means the aim, the arrival or the "
                        + "trigger's choice of body disagreed with the pilot. pickedDim=" + targetDim
                        + " landedDim=" + descentDim + " nearestBodyDim=" + nearestDim
                        // The client's own dimension changes since the descent mark: a landedDim
                        // that reads as no dimension at all has to be told from a real wrong one.
                        + " clientDimensionChanges="
                        + clientEvents().since(descentClientMark, "client_dimension_changed")
                        + " bodies=" + bodies,
                descentDim == targetDim);

        JsonObject landedRiding = assertStillSeated(events, descentMark, descentClientMark,
                "and the pilot must still be flying his ship when it comes out over the planet he "
                        + "set out for — the loop is only closed if the man who took off is the man "
                        + "who arrives. clientDim=" + descentDim
                        + " delivery=" + seatDelivery.reading(),
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
                        + " serverRiding=" + exec("stellurgytest player riding-entity")
                        + " | the pose the descent actually wrote: " + posed,
                arrivalY > TerrainHeightFinder.MAX_BUILD_Y);

        System.out.println("[M1] leg 8 (descent onto the planet) " + elapsed(tLeg)
                + " arrivedDim=" + descentDim + " nearestBodyDim=" + nearestDim
                + " arrivalY=" + arrivalY + " riding=" + landedRiding);

        // ---- LEG 9: he stays put, and can still leave later. ------------------------------------
        // The descent puts the ship down IN THE AIR, at the destination's arrival altitude: under its
        // BODY's own orbit line (the destination was minted in leg 8, so nothing stated a line for
        // it), except for a body so small that its line sits inside the block band, where the arrival
        // is lifted above the band and therefore above the line. The entry on-ramp fires on "the ship
        // is above the line", whoever is at the controls, so the descent sets a hold that only being
        // at or below the line releases — for a body with a real atmosphere it releases on the first
        // tick, and the leg pins what holds either way: no bounce, and the on-ramp works again.
        //
        // NO WINDOW in this leg: both halves are bounded by production's own records. The release is
        // a record, read from the DESCENT's mark because it can have happened during leg 8; the
        // bounce is an absence between the descent and that record.
        tLeg = System.currentTimeMillis();
        events.markInstrumented();
        long latchClientMark = clientEvents().mark();
        int destinationLine = OrbitLine.of(this::exec, descentDim).line();
        // A link's ceiling, from the ship's own numbers: straight down from the arrival at
        // SHIP_MAX_SPEED (m/s, a twentieth of it per tick) after its 60-tick ramp from rest. Four
        // times that, so the ceiling is never a claim about how fast the box is.
        double blocksPerTick = TileAdvancedFlightComputer.SHIP_MAX_SPEED / 20.0;
        int descentTicks = 60 + (int) Math.ceil(Math.max(0d, arrivalY - destinationLine) / blocksPerTick);
        String released;
        String entriesWhileHeld;
        String bounceChanges;
        bot().holdKey(Keyboard.KEY_F);          // vertical-down
        try {
            released = events.awaitRecordWithField(descentMark, "entry_latch_released", "ship", shipId,
                    "…and the hold must RELEASE once he has flown down through the orbit line. A "
                            + "latch that never clears turns \"bounces off instantly\" into \"can never "
                            + "leave this planet again\", which is strictly worse. No release means he "
                            + "never got below the line (read the pilot inputs and cruise setpoints "
                            + "below) or the latch ignored it. arrivalY=" + arrivalY
                            + " orbitLine=" + destinationLine,
                    4 * descentTicks);
            // Read at the release, before the key is let go: from here on the ship is below the
            // line, so nothing later can land in this span.
            entriesWhileHeld = events.since(descentMark, "entry_decided");
            bounceChanges = clientEvents().since(latchClientMark, "client_dimension_changed");
        } finally {
            bot().releaseKey(Keyboard.KEY_F);
        }
        assertEquals("a ship that has just been PUT somewhere by a descent must stay there while its "
                        + "pilot flies. The on-ramp reads altitude alone, so between the descent and "
                        + "the latch's release it must not have been asked to enter even once. An entry "
                        + "here is the bounce: the pilot crossed a system to reach this body and was "
                        + "thrown back off it. arrivalY="
                        + arrivalY + " orbitLine=" + destinationLine + " release=" + released
                        + " entry decisions since the descent: " + entriesWhileHeld,
                0, Events.countRecords(entriesWhileHeld, "ship", shipId));
        assertEquals("…and the CLIENT was never carried off the planet in that span either — a "
                        + "bounce and return would leave the entry log above clean only if a second "
                        + "path moved him. arrivedDim=" + descentDim + " slotDims=[" + jumpSlotDims
                        + "] client world changes since leg 9 began: " + bounceChanges,
                0, Events.records(bounceChanges).size());

        // THE SENSITIVITY CONTROL for both absences above, as well as this leg's own subject: the
        // same two record types over the same logs — and here each is REQUIRED. A recorder that had
        // died would pass the absences and fail here.
        long releaseMark = events.mark();
        long releaseClientMark = clientEvents().mark();
        int releasedDim;
        bot().holdKey(Keyboard.KEY_R);          // climb back through the line under power
        try {
            releasedDim = awaitClientWorld(releaseClientMark, jumpSlotDims,
                    "…and once he HAS been below the line, the on-ramp must work again — a ship that "
                            + "landed on a planet has to be able to leave it. If this stays on the "
                            + "planet the hold never released and the descent has stranded him "
                            + "instead of bouncing him. arrivedDim=" + descentDim + " slotDims=["
                            + jumpSlotDims + "] release=" + released + " orbitLine=" + destinationLine,
                    budget * 10);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        String entriesAfterRelease = events.since(releaseMark, "entry_decided");
        assertTrue("…and it left through the ENTRY ramp, the one the latch held off — the record whose "
                        + "absence above is the bounce verdict. entry decisions since the release: "
                        + entriesAfterRelease,
                Events.anyRecordHasAll(entriesAfterRelease, "ship", shipId, "decision", "STARTED"));
        System.out.println("[M1] leg 9 (stays put, then can leave) " + elapsed(tLeg)
                + " release=" + released + " dimAfterSecondClimb=" + releasedDim);
    }

    // ---- leg T: the telescope at home ----------------------------------------------------------

    /** The observatory's tab that holds the survey and its crystal slot (tab buttons are 0..2). */
    private static final int OBSERVATORY_TAB_SURVEY = 2;
    /** The survey tab's "passive" control: the local radar over the system the machine stands in. */
    private static final int OBSERVATORY_BUTTON_PASSIVE = 8;
    /**
     * How long the radar's completion record may take. With research off (this run seeds nothing
     * else) production resolves the whole region in the first {@code completeRegionScanIfDue}, which
     * the tile runs every server tick; the rest is the reader's own 5-tick step. Running out is red.
     */
    private static final int RADAR_RECORD_TICKS = 20;

    /**
     * One piece of a long no-input dose, in ticks: thirty seconds at the game's 20 per second, well
     * inside the bridge's own per-wait budget (three quarters of the bot's read timeout).
     */
    private static final int STATION_DOSE_PIECE_TICKS = 600;

    /** A body of the home world's system whose own cell lies INSIDE the home world's zone. */
    private static final class ZoneBody {
        final int dim;
        final String homeCell;
        final String cell;

        ZoneBody(int dim, String homeCell, String cell) {
            this.dim = dim;
            this.homeCell = homeCell;
            this.cell = cell;
        }

        @Override
        public String toString() {
            return "dim " + dim + " at " + cell + " inside " + homeCell;
        }
    }

    /** The moon this loop is walked to, read off the registry at leg T. */
    private ZoneBody homeMoon;

    /**
     * The home world's moon, found by the rule the loop is about: the registry names the home
     * world's cell, and a body of its system whose cell key extends it by the zone separator lies
     * inside its zone.
     */
    private ZoneBody homeMoon() throws Exception {
        CellInfo home = CellInfo.atSector(this::exec, 0, 0, 0, 0);
        requireArranged("the registry places no home world, so it has no zone to hold a moon: "
                + home, home.dimCell != null);
        CellInfo system = CellInfo.atKey(this::exec, home.dimCell);
        for (CellInfo.Body body : system.systemBodies) {
            if (body.dim != 0 && body.descendTarget && body.cell != null
                    && body.cell.startsWith(home.dimCell + ".")) {
                return new ZoneBody(body.dim, home.dimCell, body.cell);
            }
        }
        requireArranged("the home system has no body a ship can land on inside the home world's"
                + " zone, so this loop has no moon to fly to: " + system, false);
        throw new AssertionError("unreachable");
    }

    private static boolean containsInt(int[] values, int wanted) {
        for (int v : values) {
            if (v == wanted) {
                return true;
            }
        }
        return false;
    }

    /**
     * The home observatory, on a plot of its own beside the craft's: the whole multiblock, and a
     * platform on its north side for the operator. Blocks only — standing a structure up is not an
     * act any interface lets a test perform. Returns the controller's position.
     */
    private int[] placeHomeObservatory() throws Exception {
        // On the home floor laid at leg 0: the multiblock spans x±2, y from the controller's -1 to
        // +3, z from the controller to +4, and he operates it from the floor north of it.
        int cx = observatoryAt[0], cy = observatoryAt[1], cz = observatoryAt[2];
        String built = exec("stellurgytest fixture multiblock observatory 0 " + cx + " " + cy + " " + cz);
        requireArranged("the observatory multiblock must stand: " + built, Reply.of(built).ok());
        System.out.println("[M1] observatory at (" + cx + "," + cy + "," + cz + "): " + built);
        return new int[]{cx, cy, cz};
    }

    /**
     * A blank crystal, handed over the way any item is handed to a player. An ARRANGEMENT: a vanilla
     * {@code give} that never lands says nothing about this mod. Linked, because the hotbar is read
     * next and the server picks the slot when it runs the command.
     */
    private void handTheBlankCrystal() throws Exception {
        long giveMark = clientEvents().mark();
        exec("give @a " + CRYSTAL_ITEM + " 1");
        try {
            clientEvents().awaitMatching(giveMark, "client_slot_set",
                    reply -> Events.records(reply).stream().anyMatch(record ->
                            CRYSTAL_ITEM.equalsIgnoreCase(String.valueOf(Events.text(record, "item")))),
                    "the crystal arriving in a slot",
                    "the blank crystal handed to the player must reach his client's inventory",
                    SLOT_APPLIED_TICKS);
        } catch (AssertionError neverLanded) {
            requireArranged(neverLanded.getMessage(), false);
        }
    }

    /**
     * On the observatory's screen: switch to the survey tab, put the crystal into the machine by two
     * real slot clicks, and press the local radar. Returns the instrument's own reading once the
     * machine has recorded the survey complete.
     */
    private TelescopeReading surveyTheHomeSkyOntoTheCrystal(int[] observatory, Events events, int budget)
            throws Exception {
        // A tab click is answered by the SERVER re-opening the window with that tab's modules, so the
        // crystal slot exists only after that re-open — a record on the client's own log.
        long reopenMark = clientEvents().mark();
        bot().clickButtonAt(OBSERVATORY_TAB_SURVEY);
        clientEvents().awaitMatching(reopenMark, "client_gui_opened",
                reply -> Events.records(reply).size()
                        > Events.recordsWhere(reply, "gui", "none").size(),
                "the survey tab opening", "the observatory must re-open on its survey tab", 360);

        JsonObject slots = bot().reportSlots();
        int machineSlot = firstSlot(slots, false);
        int crystalSlot = slotHolding(slots, CRYSTAL_ITEM);
        requireArranged("the survey tab must show the machine's crystal slot beside the hotbar that"
                + " holds the crystal. slots=" + slots, machineSlot >= 0 && crystalSlot >= 0);
        bot().clickSlotAt(crystalSlot, 0);
        long insertMark = clientEvents().mark();
        bot().clickSlotAt(machineSlot, 0);
        clientEvents().await(insertMark, "client_click_confirmed",
                "the server must handle the click that puts the crystal into the observatory",
                SLOT_APPLIED_TICKS);

        String where = "0 " + observatory[0] + " " + observatory[1] + " " + observatory[2];
        long radarMark = events.markInstrumented();
        bot().clickButtonAt(OBSERVATORY_BUTTON_PASSIVE);
        events.awaitRecordWithFields(radarMark, "region_scan_advanced",
                "the local radar, started by its own button, must finish", RADAR_RECORD_TICKS,
                "pos", observatory[0] + "," + observatory[1] + "," + observatory[2],
                "complete", "true");
        return TelescopeReading.at(this::exec, where);
    }

    /**
     * Take the crystal out of the machine into the LAST hotbar slot. Hotbar 0 and 1 are the hands
     * the later legs empty and select, so the crystal must ride elsewhere or it is cleared.
     */
    private void takeTheCrystalBackIntoTheHotbar(int budget) throws Exception {
        JsonObject slots = bot().reportSlots();
        int machineSlot = firstSlot(slots, false);
        int lastHotbar = lastSlot(slots, true);
        requireArranged("the survey tab must still show the machine's slot and the hotbar. slots="
                + slots, machineSlot >= 0 && lastHotbar >= 0);
        bot().clickSlotAt(machineSlot, 0);
        long backMark = clientEvents().mark();
        bot().clickSlotAt(lastHotbar, 0);
        clientEvents().await(backMark, "client_click_confirmed",
                "the server must handle the click that puts the crystal back into the hotbar",
                SLOT_APPLIED_TICKS);
        JsonObject after = bot().reportSlots();
        requireArranged("the crystal must now ride in the player's hotbar, not in the machine. slots="
                + after, slotHolding(after, CRYSTAL_ITEM) == lastHotbar);
        bot().pressScreenKey(Keyboard.KEY_ESCAPE);
    }

    /**
     * {@code {range, shell}} of body {@code dim} in the sky of the ledgered ship's cell, or
     * {@code null} when that sky does not show it.
     */
    private static long[] skyReadingOf(String bodies, int dim) {
        Reply reply = Reply.of("stellurgytest space bodies", bodies);
        for (String ship : reply.objectArray(SHIPS)) {
            int slotDim = Reply.of("one ledgered ship", ship).integer(FEED_SLOT_DIM);
            for (String sky : reply.objectArray(FEED)) {
                Reply one = Reply.of("one cell's sky", sky);
                // Read bare: the producer always writes `slotDim` on a feed entry, so a refusal here
                // is a broken probe, not a sky without the body.
                if (one.integer(FEED_SLOT_DIM) != slotDim) {
                    continue;
                }
                for (String body : one.objectArray(FEED_BODIES)) {
                    Reply b = Reply.of("one sky body", body);
                    // ...and the producer always writes `dim` on each of the feed's bodies.
                    if (b.integer(BODY_DIM) == dim) {
                        return new long[]{(long) b.number(BODY_DISTANCE), (long) b.number(FEED_SHELL)};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Where the cell named {@code cellKey} IS relative to the cell named {@code relativeToKey}, as
     * {@code {dx, dy, dz, spaceClock}} in blocks — production's frame lookup for both, read at ONE
     * clock in one call. A cell a body stands in rides it, so for two bodies' own cells this is
     * where one body is from the other.
     */
    private long[] frameOf(String cellKey, String relativeToKey) throws Exception {
        Reply f = Reply.of("stellurgytest space frame",
                exec("stellurgytest space frame " + cellKey + " " + relativeToKey));
        long[] at = new long[4];
        for (int i = 0; i < 3; i++) {
            at[i] = (long) f.arrayNumber("relative", i);
        }
        at[3] = (long) f.number("clock");
        return at;
    }

    /** The slot world of the one ship this loop has on the ledger, from {@code space bodies}. */
    private static int shipSlotDim(String bodies) {
        String[] ships = Reply.of("stellurgytest space bodies", bodies).objectArray(SHIPS);
        requireArranged("exactly one ship must be on the ledger — this loop flies one: " + bodies,
                ships.length == 1);
        return Reply.of("one ledgered ship", ships[0]).integer(FEED_SLOT_DIM);
    }

    private static double travel(long[] a, long[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The container number of the first slot that is (or is not) the player's own. */
    private static int firstSlot(JsonObject slots, boolean playerSlot) {
        com.google.gson.JsonArray all = slots.getAsJsonArray("slots");
        for (int i = 0; i < all.size(); i++) {
            JsonObject s = all.get(i).getAsJsonObject();
            if (s.get("playerSlot").getAsBoolean() == playerSlot) {
                return s.get("slot").getAsInt();
            }
        }
        return -1;
    }

    /** The container number of the last slot that is (or is not) the player's own. */
    private static int lastSlot(JsonObject slots, boolean playerSlot) {
        com.google.gson.JsonArray all = slots.getAsJsonArray("slots");
        for (int i = all.size() - 1; i >= 0; i--) {
            JsonObject s = all.get(i).getAsJsonObject();
            if (s.get("playerSlot").getAsBoolean() == playerSlot) {
                return s.get("slot").getAsInt();
            }
        }
        return -1;
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
        // The CLIENT's own mark too: the claim below is about where he is RENDERED, and the
        // client records its dismounts as the server records its own.
        long standClientMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_LSHIFT);
        try {
            log.await(standMark, "dismount",
                    "a pilot must be able to LEAVE his seat on the dismount key — the navigation "
                            + "console is deck work, and a seat he cannot get out of would end the "
                            + "loop here", budget * 5);
        } finally {
            bot().releaseKey(Keyboard.KEY_LSHIFT);
        }
        // WAS `waitTicks(10)`. A budget between the server's dismount and the client's
        // render asserts how fast this box replicates; the client's own dismount record
        // says the thing itself, and cannot be sampled past.
        clientEvents().await(standClientMark, "dismount",
                "the client must APPLY the dismount the server recorded, or the read below is"
                        + " about a client that never got the message", budget * 5);
        assertTrue("…and the CLIENT must render him on his feet: the deck hold that keeps a standing "
                        + "pilot aboard runs on the server, so a dismount the client never applied "
                        + "leaves him riding a seat the server says he left. riding="
                        + bot().reportRidingEntity() + " serverRiding="
                        + exec("stellurgytest player riding-entity")
                        + " | dismounts since the key went down: "
                        + log.since(standMark, "dismount"),
                !isRiding(bot().reportRidingEntity()));
    }

    /** He takes the seat again, exactly the way he took it the first time: aim at it and press use. */
    private JsonObject sitBackDown(Events log, int dim, int[] afcSub, int budget) throws Exception {
        int[] seatSub = add(afcSub, offSeat);
        holdNothing();
        Aim aim = aimAt(dim, afcSub, seatSub, offStand, 0.5, 0.2, 0.5, budget);
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
        // The RECORD, not the reply — same reading as the first boarding above.
        assertEquals("…and the seat must still know it belongs to a ship: an unmanaged seat carries no "
                        + "flight input, so the jump he is about to fire would go nowhere. decision="
                        + decision,
                "true", Events.text(Events.lastRecord(decision), "managed"));
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
        String already = screenOf(bot().reportState());
        if (!already.isEmpty()) {
            return already;
        }
        // The aim-and-press is the STIMULUS and `client_gui_opened` is the link. The crosshair is
        // re-derived before every press because a freshly settled craft moves under it, and the
        // press is re-issued every 60 ticks while the log is read every 5 — a press that never
        // registered is not recoverable by reading longer, which is what makes this a stimulus.
        Aim[] aim = {new Aim()};
        long mark = clientEvents().mark();
        try {
            clientEvents().awaitMatching(mark, "client_gui_opened",
                    reply -> Events.records(reply).size()
                            > Events.recordsWhere(reply, "gui", "none").size(),
                    "opening any screen", "the navigation console must open", 360,
                    () -> {
                        aim[0] = aimAt(dim, afcSub, navSub, offStand, 0.5, 0.5, 0.5, budget);
                        assertAimed(aim[0], navSub, "navigation console", "navigationcomputer");
                        pressUse();
                    }, 60);
        } catch (ArrangementFailure alreadyTyped) {
            // The aim ran inside the stimulus and its premises are arrangement READS: a ship with no
            // pose or a client with no world is a failure of the arrangement, and it is TYPED as one.
            // `ArrangementFailure` is an `AssertionError`, so the catch below would swallow it and
            // turn it into "the console never opened" — a premise refusal read as a broken mechanic.
            throw alreadyTyped;
        } catch (AssertionError neverOpened) {
            return "ARRANGEMENT: console never opened;" + aim[0].diagnosis + " | "
                    + neverOpened.getMessage();
        }
        return screenOf(bot().reportState());
    }

    /**
     * The player's way to pick a hotbar slot: its number key, pressed and released.
     *
     * <p>Vanilla takes the press in the NEXT client tick ({@code processKeyBinds}, which runs only
     * while no screen is up), so the read is one world tick after it — the tick the press is defined
     * to land in, not a budget — and the slot it reports is required to be the one asked for. The
     * server learns the index from {@code CPacketHeldItemChange}, which {@code syncCurrentPlayItem}
     * sends ahead of any use press on the same connection.</p>
     *
     * @return the client's items, read after that tick
     */
    private JsonObject pressHotbarKey(int slot) throws Exception {
        bot().setKey(Keyboard.KEY_1 + slot, true);
        bot().setKey(Keyboard.KEY_1 + slot, false);
        bot().waitWorldTicks(1);
        JsonObject items = bot().reportPlayerItems();
        int selected = items.has("selectedHotbar") ? items.get("selectedHotbar").getAsInt() : -1;
        requireArranged("the hotbar key " + (slot + 1) + " must select slot " + slot + ": vanilla"
                        + " takes the press in the next client tick, and only with no screen up."
                        + " selected=" + selected + " items=" + items,
                selected == slot);
        return items;
    }

    /**
     * An empty main hand, without wiping the inventory the pilot is carrying his crystal in: he
     * presses the number key of the first EMPTY hotbar slot, as his own client shows the hotbar.
     *
     * <p><b>One read after the key's tick, because there is nothing else to wait for.</b> Once
     * {@link #pressHotbarKey} returns, the hand holds that slot's contents. Those change only when
     * the SERVER sets the slot ({@code client_slot_set}), and nothing between here and the use press
     * does — so a slot that is not empty now stays not empty however long anyone waits. Whatever he
     * picked up on the way (seeds, on a grassy spawn) stays in his inventory: it is his.</p>
     *
     * <p>A world that is not ready here is a failure of whatever link brought the pilot here, not a
     * state to sit through: the key's one world tick refuses it with its own diagnosis.</p>
     */
    private void holdNothing() throws Exception {
        JsonObject carried = bot().reportPlayerItems();
        requireArranged("the client must report the player's inventory before he picks a slot: "
                + carried, isWorldReady(carried) && carried.has("main"));
        com.google.gson.JsonArray main = carried.getAsJsonArray("main");
        int empty = -1;
        for (int slot = 0; slot < 9 && empty < 0; slot++) {
            JsonObject stack = main.get(slot).getAsJsonObject();
            if (!stack.has("id") || stack.get("id").getAsString().isEmpty()) {
                empty = slot;
            }
        }
        requireArranged("the pilot's hotbar must hold an empty slot for an empty hand: " + carried,
                empty >= 0);
        JsonObject items = pressHotbarKey(empty);
        boolean ready = isWorldReady(items);
        String heldId = ready && items.has("held")
                ? items.getAsJsonObject("held").get("id").getAsString() : null;
        requireArranged("the pilot's main hand must be EMPTY so the use press reaches the block"
                        + " rather than being consumed by a held item; worldReady=" + ready
                        + " held=" + heldId + " items=" + items,
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
     * still waited for is the crossing finishing — on production's own record of the named hull
     * becoming usable in that world.</p>
     */
    // NOT on PilotSeat, and deliberately: this returns a `find-seat` reply on the happy path and a
    // `vs ship-uuid` reply when the hull cannot be named, through one variable — two producers with
    // one type, which is its own defect and not one to be rewritten blind while converting another.
    private String findSeatAboard(int dim, int budget) throws Exception {
        assertNotNull("the build must have named its ship before the arrival side can ask about it",
                builtShipName);
        // A LINK: the hull carrying that name becoming USABLE in THIS cell is `ship_usable`, which
        // carries the dimension (it was `ship_spawned`, which does not, that made this a loop) —
        // awaited over the chain, so a load undone by an unload does not answer. A usable hull is one
        // whose blocks are in its subspace, which is what the seat search needs. Then ONE search.
        String hullId = ShipIdentity.awaitPhysicsIdOf(this::exec,
                serverEvents(), dim, builtShipName, budget * 5);
        lastSeatProbe = exec("stellurgytest vs find-seat " + dim + " id " + hullId);
        rememberAnchor(lastSeatProbe);
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
        // STIMULUS: down across client ticks, then up across client ticks — so that a second press
        // right after this one is a new edge and not a continuation of this one.
        bot().waitWorldTicks(5);
        bot().setKey(Keyboard.KEY_J, false);
        // STIMULUS: the key-up half of the edge.
        bot().waitWorldTicks(20);
    }

    // `chatText` lived here: the client's last N chat lines flattened and lower-cased, for substring
    // checks. Its two callers are gone — one asserted that the pilot is TOLD the ship is armed (the
    // arming itself is read off the navigation computer), and one decided which JUMP BRANCH the run
    // took by looking for the words "spooling" and "confirm" (the trigger names its own outcome, and
    // `jump_press_decided` carries it). A milestone whose control flow turned on prose turned on the
    // language file.

    /**
     * The dimension of the NEAREST body in the pilot's sky the ship may descend onto, or MIN_VALUE.
     *
     * <p>Nearest because that is production's own choice: the flight computer hands its sky's
     * descend targets to {@code DescentController.nearestDescentTarget} (checked 2026-09-30, called
     * from {@code TileAdvancedFlightComputer:613}), so the world loaded here is the one the trigger
     * will pick.</p>
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
     * Every descend-target body in the SKY of each ledgered ship's cell — the {@code feed} entry keyed
     * by that ship's slot world, which is the packet the pilot's client draws.
     *
     * <p>The sky and not the cell's own body list: a moon's zone is a set of cells none of which the
     * moon is named in, so a craft parked beside it holds an EMPTY cell list while its pilot sees the
     * moon a thousand blocks off. The pilot flies by what he sees, and so does the descent trigger.
     * Walked per ship rather than over the whole reply, so two ledgered ships never pool their
     * skies.</p>
     */
    private static java.util.List<String> descendTargets(String bodies) {
        Reply reply = Reply.of("stellurgytest space bodies", bodies);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String ship : reply.objectArray(SHIPS)) {
            int slotDim = Reply.of("one ledgered ship", ship).integer(FEED_SLOT_DIM);
            for (String sky : reply.objectArray(FEED)) {
                Reply one = Reply.of("one cell's sky", sky);
                // Read bare: the producer always writes `slotDim` on a feed entry.
                if (one.integer(FEED_SLOT_DIM) != slotDim) {
                    continue;
                }
                for (String body : one.objectArray(FEED_BODIES)) {
                    // The producer always writes the flag on every element, so its absence is a
                    // broken probe and not a body that is no descend target.
                    if (Reply.of("one sky body", body).bool(FEED_DESCEND)) {
                        out.add(body);
                    }
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
                best = new long[]{(long) b.arrayNumber(FEED_DIR, 0),
                        (long) b.arrayNumber(FEED_DIR, 1),
                        (long) b.arrayNumber(FEED_DIR, 2), distance};
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
            // STIMULUS: the burst.
            advanceServerAndClient(burstTicks);
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
            // STIMULUS: the throttle cut.
            advanceServerAndClient(cutTicks);
        } finally {
            bot().releaseKey(Keyboard.KEY_X);
        }
        return exec("stellurgytest space bodies");
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
                screen.startsWith("dev.stannismod.stellurgy.libvulpes.inventory.GuiModular"));

        // Marked BEFORE the Scan click: the first thing awaited below is the scan pass ENDING, and
        // that pass starts on this click.
        Events spawnEvents = serverEvents();
        long spawnMark = spawnEvents.markInstrumented();
        bot().clickButtonAt(BUTTON_SCAN);

        // TWO PASSES, each waited for on the machine's own record. Scan and Build each start a TIMED
        // pass that the assembler counts down one tick at a time, and production IGNORES a Build
        // press made while a pass is still running — it returns on `isScanning()` with nothing said.
        // The loop that stood here pressed Build every forty ticks for up to 3 600, so the press that
        // "took" was simply the first to land after the scan had happened to end, and each press
        // before it was thrown away unseen. `assembler_pass_finished` IS that end, so Build is
        // pressed once, after it.
        //
        // The loop defended itself with "VS builds the ship on its OWN thread, off the game loop,
        // so a busy box needs more ticks". That is false of this tree: the physics mod's spawn queue
        // is drained on the game thread inside a world tick. The defence was a claim, and it hid
        // that the wait had never been for the ship at all — it was for the scan.
        String machinePos = builderPos[0] + "," + builderPos[1] + "," + builderPos[2];
        spawnEvents.awaitRecordWithFields(spawnMark, "assembler_pass_finished",
                "the SCAN pass must end before Build can take - production discards a Build press"
                        + " made during it", PASS_TICKS,
                "pos", machinePos, "building", "false");
        // The screen is READ, not re-opened: the loop re-opened it when "something knocked it shut",
        // which turned a closed screen — a player who could no longer press Build — into a retry.
        String openScreen = screenOf(bot().reportState());
        assertTrue("the assembler's screen must still be open for the Build press; a screen that"
                        + " closed on its own between Scan and Build leaves the player unable to"
                        + " build at all. screen=\"" + openScreen + "\"",
                openScreen.startsWith("dev.stannismod.stellurgy.libvulpes.inventory.GuiModular"));
        bot().clickButtonAt(BUTTON_BUILD);

        // The registry's own record of the ship being added — not a count of ships in dim 0, which
        // could not say WHICH ship, while the record names it.
        String spawned = spawnEvents.await(spawnMark, "ship_spawned",
                "the BUILD pass must add a ship to the registry", PASS_TICKS);
        int ships = Events.countRecordsWithField(spawned, "vsShip");
        // WHICH ship. The record names it, and a count that threw the name away sent every later
        // question about "the ship" back to a position or to "the first settled row in the cell".
        // It is kept from the moment of creation.
        String namedShip = Events.lastField(spawned, "vsShip");
        if (namedShip != null && !namedShip.isEmpty()) {
            builtShipVsId = namedShip;
        }
        bot().pressScreenKey(Keyboard.KEY_ESCAPE);
        return ships;
    }

    /**
     * Put the crosshair on the assembler and press the use key, retrying the whole aim-and-press
     * until a screen opens. The crosshair is confirmed on the machine's own block before every
     * press, so a red names the hop that failed rather than merely the outcome.
     */
    private String openBuilderScreenByRealKeyPress(int[] builderPos, int budget) throws Exception {
        return openScreenByRealKeyPress(builderPos, "rocket assembler", "rocketbuilder", budget);
    }

    /**
     * The same act on any machine standing in the world: aim at its block, press use, and take the
     * screen that opens. {@code blockNeedle} is the registry-path fragment the crosshair must report
     * before a press counts as aimed at THIS machine.
     */
    private String openScreenByRealKeyPress(int[] machinePos, String what, String blockNeedle,
                                            int budget) throws Exception {
        String already = screenOf(bot().reportState());
        if (!already.isEmpty()) {
            return already;
        }
        // Same pair as openConsoleFromTheDeck: aim-and-press is the stimulus, `client_gui_opened`
        // is the link, and the crosshair is re-derived every press because the machine's world
        // position is read fresh.
        Aim[] aim = {new Aim()};
        long mark = clientEvents().mark();
        try {
            clientEvents().awaitMatching(mark, "client_gui_opened",
                    reply -> Events.records(reply).size()
                            > Events.recordsWhere(reply, "gui", "none").size(),
                    "opening any screen", "the " + what + " must open", 360,
                    () -> {
                        aim[0] = aimAtWorldBlock(machinePos, 0.5, 0.5, 0.5, budget);
                        assertAimed(aim[0], machinePos, what, blockNeedle);
                        pressUse();
                    }, 60);
        } catch (ArrangementFailure alreadyTyped) {
            // As above: the aim's premises are arrangement READS, and this catch must not turn a
            // client with no world into "a machine that swallows the press" three frames up.
            throw alreadyTyped;
        } catch (AssertionError neverOpened) {
            return "";
        }
        return screenOf(bot().reportState());
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
        // STIMULUS: each pass aims and reads back the pick, ending on the goal state — the argument
        // is in the javadoc above.
        for (int attempt = 0; attempt < budget; attempt++) {
            JsonObject state = bot().reportState();
            // A READ, not a wait: this used to sleep five ticks and retry when the client reported
            // no world. The player is already standing in this world when the aim starts, so a
            // client without one is a finding about the arrangement, not a reason to keep aiming.
            requireArranged("the client must have its world while it aims at the assembler: " + state,
                    isWorldReady(state));
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();
            aim.distSq = look(targetWorld, px, py, pz);
            // STIMULUS: the controller's step — five client ticks between the aim and the read of the
            // pick. MEASURED that one is not enough (2026-09-23: a one-tick step turned all three aim
            // controllers red, deterministically, the pick read back from a look tens of degrees off
            // the aim; five, alone, green). The mechanism is NOT established — Minecraft.runTick does
            // call getMouseOver inside the tick the harness counts, so the lag is somewhere after
            // it (the deck look, the physics mod's ship pick, the render frame). An open question,
            // not a settled number.
            bot().waitWorldTicks(AIM_STEP_TICKS);
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

        // STIMULUS: each pass stands and aims against the ship's live pose, ending on the goal
        // state — the argument is in the javadoc above.
        for (int attempt = 0; attempt < budget; attempt++) {
            // READS, not waits — all three checks below used to sleep five ticks and retry. The ship
            // was resolved before this method was called (its computer's subspace address is an
            // argument), so a ship that then reports no world pose, or whose points will not map to
            // world coordinates, went away mid-arrangement; and a same-world teleport cannot cost the
            // client its world. Each is news about the arrangement, not a pause.
            double[] shipAnchor = readTripleD(
                    dim == 0 ? findSeat().raw() : findSeatAboard(dim, budget),
                    "shipWorldX", "shipWorldY", "shipWorldZ");
            requireArranged("the ship was resolved before aiming began, so it must still report a"
                    + " world pose in dim " + dim + " on attempt " + attempt, shipAnchor != null);
            // The floor of the stand cell is the deck's top surface, so the feet go at its y with a
            // sliver of clearance rather than at its centre.
            standWorld = toWorld(dim, shipAnchor, standSub, 0.5, 0.05, 0.5);
            targetWorld = toWorld(dim, shipAnchor, targetSub, tx, ty, tz);
            requireArranged("the ship's stand and target points must map to world coordinates off"
                    + " its reported pose " + java.util.Arrays.toString(shipAnchor),
                    standWorld != null && targetWorld != null);
            // He WALKS to the stand square. Below the deck — the first boarding, at home — he climbs
            // the steps onto it first; aboard, he walks across the deck from wherever he stood up.
            JsonObject before = bot().reportState();
            requireArranged("the client's world must be ready before he walks: " + before,
                    isWorldReady(before));
            if (before.get("playerY").getAsDouble() < standWorld[1] - 1.0D) {
                climbOntoTheDeck(dim, shipAnchor, afcSub);
            }
            walkTo(standWorld[0], standWorld[2], STAND_WITHIN_BLOCKS, WALK_TICKS,
                    "the deck square he works the " + describe(targetSub) + " from");

            JsonObject state = bot().reportState();
            requireArranged("he must be standing ON the deck after the walk, not beside or under it:"
                            + " the deck's top is y=" + standWorld[1] + ", the client reports " + state,
                    isWorldReady(state)
                            && Math.abs(state.get("playerY").getAsDouble() - standWorld[1]) < 0.6D);
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();
            aim.distSq = look(targetWorld, px, py, pz);
            // STIMULUS: the controller's step — why five ticks and not one: see the same step in the
            // aim controller above.
            bot().waitWorldTicks(AIM_STEP_TICKS);

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

    /**
     * From the home floor up onto the deck: off the pad to its north, east along the floor and south
     * down the craft's east side well clear of the deck's overhang, west to the foot of the steps, up
     * them, and onto the deck's south edge beside the flight computer. The floor and the steps are
     * world blocks at the home site's coordinates; the deck is the ship, so its two squares are read
     * off the ship's live pose. (The first route walked under the deck's east column and stopped
     * against the ship at x = bx+5.)
     */
    private void climbOntoTheDeck(int dim, double[] shipAnchor, int[] afcSub) throws Exception {
        requireArranged("he can only be below the deck at home, where the steps stand; in dim " + dim
                + " he must have stood up ON it", dim == 0);
        double stepsX = bx + 2.5D;
        walkTo(bx + 4.5D, bz - 2.5D, STAND_WITHIN_BLOCKS, WALK_TICKS, "the floor north of the pad");
        walkTo(bx + 7.5D, bz - 2.5D, STAND_WITHIN_BLOCKS, WALK_TICKS, "the floor north-east of the craft");
        walkTo(bx + 7.5D, bz + 15.5D, STAND_WITHIN_BLOCKS, WALK_TICKS, "the floor south-east of the steps");
        walkTo(stepsX, bz + 15.5D, STAND_WITHIN_BLOCKS, WALK_TICKS, "the foot of the steps");
        walkTo(stepsX, bz + 6.5D, STAND_WITHIN_BLOCKS, WALK_TICKS, "the top of the steps");
        double[] entry = toWorld(dim, shipAnchor, add(afcSub, new int[]{0, 0, 2}), 0.5, 0.05, 0.5);
        double[] aisle = toWorld(dim, shipAnchor, add(afcSub, new int[]{0, 0, 1}), 0.5, 0.05, 0.5);
        requireArranged("the deck's south edge must map to world coordinates off the ship's pose "
                + java.util.Arrays.toString(shipAnchor), entry != null && aisle != null);
        walkTo(entry[0], entry[2], STAND_WITHIN_BLOCKS, WALK_TICKS, "the deck's south edge");
        walkTo(aisle[0], aisle[2], STAND_WITHIN_BLOCKS, WALK_TICKS, "the deck beside the flight computer");
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
        // STIMULUS: the use key held down across client ticks, as a mouse button is.
        bot().waitWorldTicks(5);
        bot().setKey(KEY_USE_ITEM, false);
    }

    // ---- arrangement ----------------------------------------------------------------------------

    /** Warm the chunks, clear the site and stand the craft up; returns the assembler's position. */
    private int[] placeFixture() throws Exception {
        // A GROUND site on the home floor: the volume above it is cleared and its count reported.
        // The height covers the craft plus the deck the player walks to reach its console; the climb
        // to the orbit line is production's business and no pre-clear could cover it.
        int[] bp = RocketFixture.placeAt(site, this::exec, VARIANT, 2, 20,
                "the jump-capable craft, and the deck the player boards and works it from");
        return new int[]{bp[0], bp[1],
                bp[2]};
    }

    /** How far the home floor reaches from the spawn block, and how much air is cleared above it. */
    private static final int HOME_WEST = 3, HOME_EAST = 19, HOME_NORTH = 6, HOME_SOUTH = 15, HOME_AIR = 32;

    /**
     * Read where the client stands and lay the home floor around it: one flat level the observatory,
     * the craft's pad and the steps up to its deck all stand on, with the air above it cleared.
     *
     * <p>The layout, from the spawn block (fx, fz), x east and z south: the observatory's controller
     * at (fx+4, fz+6), operated from the floor north of it; the craft's pad at fx+10..fx+15 ×
     * fz-2..fz+3, its assembler on the north side, its tower on the west; the steps up to the deck
     * on the pad's south side, outside the assembler's scan. Every structure is reached on foot.</p>
     */
    private void layTheHomeSite() throws Exception {
        JsonObject state = bot().reportState();
        requireArranged("the client must report where the player stands before anything is built"
                        + " around him: " + state,
                state.has("playerX") && state.has("playerY") && state.has("playerZ"));
        int fx = (int) Math.floor(state.get("playerX").getAsDouble());
        int fy = (int) Math.floor(state.get("playerY").getAsDouble() + 1e-3) - 1;
        int fz = (int) Math.floor(state.get("playerZ").getAsDouble());
        spawnFloor = new int[]{fx, fy, fz};
        observatoryAt = new int[]{fx + 4, fy + 1, fz + 6};
        bx = fx + 10;
        by = fy;
        bz = fz - 2;
        site = FixtureSite.onGround(0, bx, by, bz, "the craft stands on the floor laid where the player"
                + " spawned, because he walks to it");
        int x1 = fx - HOME_WEST, x2 = fx + HOME_EAST, z1 = fz - HOME_NORTH, z2 = fz + HOME_SOUTH;
        String air = exec("stellurgytest fill 0 " + x1 + " " + (fy + 1) + " " + z1 + " " + x2 + " "
                + (fy + HOME_AIR) + " " + z2 + " minecraft:air");
        requireArranged("the air above the home floor must be cleared: " + air, Reply.of(air).ok());
        String floor = exec("stellurgytest fill 0 " + x1 + " " + fy + " " + z1 + " " + x2 + " " + fy
                + " " + z2 + " minecraft:iron_block");
        requireArranged("the home floor must be laid: " + floor, Reply.of(floor).ok());
        System.out.println("[M1] home site: spawned on " + describe(spawnFloor) + "; cleared "
                + Reply.of(air).integer("placed") + " blocks above the floor (" + x1 + ".." + x2 + ", "
                + z1 + ".." + z2 + ") and laid it: " + floor);
    }

    /**
     * Steps from the floor up to the deck's south edge, a half block each, so he climbs them by
     * walking. They stand south of the pad, outside the assembler's scan: they are world blocks, not
     * part of the ship, and the ship's mass does not move.
     *
     * <p>Column k (k = 0 next to the deck) stands at z = bz+6+k and its walking surface is
     * 0.5·k below the deck's top (by+5); k = 7 is a single slab on the floor.</p>
     */
    private void layTheStepsToTheDeck() throws Exception {
        int deckTop = by + 5;
        for (int k = 0; k < 8; k++) {
            int z = bz + 6 + k;
            double surface = deckTop - 0.5 * k;
            int fullTop = (int) Math.floor(surface) - 1;
            if (fullTop >= by + 1) {
                exec("fill " + (bx + 1) + " " + (by + 1) + " " + z + " " + (bx + 2) + " " + fullTop + " "
                        + z + " minecraft:stone");
            }
            if (surface != Math.floor(surface)) {
                exec("fill " + (bx + 1) + " " + (fullTop + 1) + " " + z + " " + (bx + 2) + " "
                        + (fullTop + 1) + " " + z + " minecraft:stone_slab 0");
            }
        }
    }

    /**
     * He walks to a point the way a player does: faces it and holds forward, reading where his own
     * client puts him, until he stands within {@code within} blocks of it. Within a block of it he
     * sneaks, which is how a player closes the last step without running past it — and on a deck,
     * how he keeps from walking off its edge.
     */
    private double[] walkTo(double x, double z, double within, int maxTicks, String what) throws Exception {
        double px = Double.NaN, pz = Double.NaN;
        boolean sneaking = false;
        try {
            // STIMULUS: the iterations ARE the input — each pass turns his head toward the point and
            // holds the forward key for one world tick, against his own client-read position, the
            // same closed loop a player's hands run. No record could answer "has he arrived": the
            // arrival is this loop's own doing, and it ends on the goal state or refuses below.
            for (int tick = 0; tick < maxTicks; tick++) {
                JsonObject state = bot().reportState();
                px = state.get("playerX").getAsDouble();
                pz = state.get("playerZ").getAsDouble();
                double dx = x - px, dz = z - pz, distance = Math.sqrt(dx * dx + dz * dz);
                if (distance <= within) {
                    bot().releaseKey(Keyboard.KEY_W);
                    return new double[]{px, state.get("playerY").getAsDouble(), pz};
                }
                if (distance < 1.0D && !sneaking) {
                    bot().holdKey(Keyboard.KEY_LSHIFT);
                    sneaking = true;
                }
                bot().setLook((float) Math.toDegrees(Math.atan2(-dx, dz)), 0.0F);
                bot().holdKey(Keyboard.KEY_W);
                bot().waitWorldTicks(1);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
            if (sneaking) {
                bot().releaseKey(Keyboard.KEY_LSHIFT);
            }
        }
        // What stopped him, as his own client holds it: the cell just ahead, at his feet and his head.
        double dx = x - px, dz = z - pz, d = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        int aheadX = (int) Math.floor(px + 0.6D * dx / d), aheadZ = (int) Math.floor(pz + 0.6D * dz / d);
        JsonObject at = bot().reportState();
        int feetY = at.has("playerY") ? (int) Math.floor(at.get("playerY").getAsDouble()) : 0;
        requireArranged("he must be able to walk to " + what + " (" + x + ", " + z + ") within "
                + maxTicks + " ticks; he stopped at (" + px + ", " + pz + ") state=" + at
                + " | ahead at feet " + bot().blockState(aheadX, feetY, aheadZ)
                + " | ahead at head " + bot().blockState(aheadX, feetY + 1, aheadZ), false);
        return null;
    }


    /** The aim controllers' step between an aim and the read of the pick — measured, see its use. */
    private static final int AIM_STEP_TICKS = 5;

    /** How close to a stand point counts as standing on it, in blocks: well inside one block cell. */
    private static final double STAND_WITHIN_BLOCKS = 0.35D;
    /**
     * The most world ticks one walk may take. The longest walk on the home floor is ~20 blocks, under
     * 100 ticks at a walk; a sneaked last block is ~15. The bound only ends a walk that cannot arrive.
     */
    private static final int WALK_TICKS = 600;

    /**
     * Walk the player onto the launchpad AND ESTABLISH THAT HE IS ON IT — measured through the CLIENT,
     * which is the side that decides whether he falls. Vanilla movement is client authoritative, so
     * the client's own position and the block it holds under him are what is read.
     */
    private void standOnThePad(double standX, double standZ, int[] builderPos) throws Exception {
        standOnFloor(standX, standZ, new int[]{builderPos[0], by, builderPos[2] + 2}, "launchpad");
    }

    /**
     * Put the player on the floor block at {@code floorPos} and establish, through the CLIENT, that he
     * is standing on it. {@code floorNeedle} is the registry-path fragment that floor block reports.
     * The pad form above and the observatory's platform are the two callers.
     */
    private void standOnFloor(double standX, double standZ, int[] floorPos, String floorNeedle)
            throws Exception {
        final int floorX = floorPos[0], floorY = floorPos[1], floorZ = floorPos[2];

        // He walks there over the home floor; the client has every chunk of it, since he spawned on
        // it. What the CLIENT holds under the spot is read, because that block is what he stands on.
        walkTo(standX, standZ, STAND_WITHIN_BLOCKS, WALK_TICKS, "the " + floorNeedle + " he works from");
        JsonObject floor = bot().blockState(floorX, floorY, floorZ);
        requireArranged("the CLIENT must hold the " + floorNeedle + " he is standing on at (" + floorX
                        + "," + floorY + "," + floorZ + "): " + floor,
                floor != null && floor.has("block")
                        && floor.get("block").getAsString().contains(floorNeedle));

        JsonObject state = bot().reportState();
        // `playerY`, checked against the harness rather than assumed: a `y` that is absent reads as
        // NaN, every comparison against NaN is false, and the assertion would then fail for the
        // wrong reason — or, written the other way round, pass on a field nobody ever sent.
        double y = state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
        requireArranged("and he must STAY on it: the client reports y=" + y + " where the pad's"
                        + " surface is " + (floorY + 1) + ". Still falling here means the floor arrived"
                        + " and something else is taking him off it. state=" + state,
                Math.abs(y - (floorY + 1)) < STANDING_ON_THE_PAD_BLOCKS);
    }

    /**
     * An empty main hand (a held stack eats the use press), the way a player gets one: he selects an
     * EMPTY hotbar slot. Nothing is cleared — a server {@code clear} used to stand here, and on a run
     * whose spawn lay in grass he had picked up seeds into slot 0, so the clear took his crystal with
     * them and the console leg found nothing to insert (2026-10-08).
     */
    private void emptyTheHand() throws Exception {
        holdNothing();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    /**
     * A WINDOW in which one side drives what the other observes; one whose subject is the client's
     * own simulation alone is {@code bot().waitWorldTicks}.
     */
    private void advanceServerAndClient(int ticks) throws Exception {
        GameTicks.serverAndClient(serverHarness.client(), GameTicks.server(), bot()::waitWorldTicks)
                .ticks(ticks);
    }

    private Events serverEvents() {
        return new Events(this::exec, GameTicks.serverAndClient(serverHarness.client(), GameTicks.server(), bot()::waitWorldTicks),
                evictionReports());
    }

    /** The CLIENT's own ordered event log, behind the same verbs the server's is read through.
     *  {@link Events#mark} refuses a sequence unless a recorder is subscribed, which is what keeps
     *  an empty log later from reading as "it never happened". */
    private Events clientEvents() {
        return ClientEvents.of(bot(), GameTicks.serverAndClient(serverHarness.client(), GameTicks.server(), bot()::waitWorldTicks),
                evictionReports());
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
        // An EMPTY window is NOT YET, whatever the caller's predicate says about a missing number: it
        // used to read no record as MIN_VALUE and hand that to the predicate, so "a change OUT of
        // cell N" was satisfied before the client had changed at all — and returned MIN_VALUE as the
        // world it landed in.
        String reply = clientEvents().awaitMatching(clientMark, "client_dimension_changed",
                records -> Events.lastField(records, "dim") != null
                        && wanted.test(readIntOr(Events.lastField(records, "dim"),
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
                        + " serverRiding=" + exec("stellurgytest player riding-entity")
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
                    + serverEvents().since(0L, "mount") + diagnosis, never);
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
        return exec("stellurgytest energy stored 0 " + pos[0] + " " + pos[1] + " " + pos[2]);
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
        String hull = exec("stellurgytest vs ship-uuid " + dim + " " + builtShipName);
        // absence is the answer, as in the seat probe above: the loop waits for a hull to
        // carry the name.
        String namedHull = Reply.of("stellurgytest vs ship-uuid", hull).textOr("id", null);
        // absence is the answer for `found` too, and for a shape the line above does not reach:
        // the verb answers `{"error":"world not loaded"}` — with no `found` at all — for a cell
        // whose world is not up, which is one of the states this mapping is asked in. Both arms
        // here are non-failing (the caller reads a null as "could not map"), so a refusal would
        // be the only thing able to end the run, and it would end it about the instrument.
        if (!Reply.of(hull).boolOr("found", false) || namedHull == null) {
            lastToWorldProbe = hull;
            return null;
        }
        lastToWorldProbe = exec("stellurgytest vs to-world " + dim + " id " + namedHull
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
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).textOr(field, null);
    }

    /** A probe field that may come back quoted or as a bare {@code null}, as a plain string. */
    private static String unquote(String raw) {
        return raw == null ? "" : raw.replace("\"", "");
    }

    private static int readIntOr(String json, String field, int fallback) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb takes the
        // default as an argument, so every call site names what a missing field means there.
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
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        double x = reply.numberOr(xField, Double.NaN);
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        double y = reply.numberOr(yField, Double.NaN);
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        double z = reply.numberOr(zField, Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return null;
        }
        return new double[]{x, y, z};
    }
}
