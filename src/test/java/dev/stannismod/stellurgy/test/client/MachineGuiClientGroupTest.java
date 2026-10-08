package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import dev.stannismod.stellurgy.test.NavStatus;
import dev.stannismod.stellurgy.test.PlayerState;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;

import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.RocketList;
import dev.stannismod.stellurgy.test.TelescopeReading;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.client.ClientGuiTestSupport.findSlotWithItem;
import static dev.stannismod.stellurgy.test.client.ClientGuiTestSupport.screenOf;

/**
 * A machine stands in the world, the player right-clicks it open, and drives it with nothing but
 * clicks. Every scenario here shares one client.
 *
 * <p>What binds them is the instrument, not the subsystem: every one of these needs a REAL client
 * because the contract lives in the client&harr;server round trip — a libVulpes {@code GuiModular}
 * that must actually open, a slot click that must reach {@code Container.transferStackInSlot}, a
 * button id that must reach {@code useNetworkData}, an open container that must survive (or not
 * survive) a distance check on the server's own tick.</p>
 *
 * <h2>Why these seven share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: 119.3 + 88.3 + 116.6 + 120.3 + 126.8 +
 * 228.9 s across seven client boots — <b>13.3 minutes</b> for seven interactions.</p>
 *
 * <h2>What the sharing makes dangerous here</h2>
 *
 * <ul>
 *   <li><b>Two of the source classes stood on the SAME block.</b> {@code GuidanceComputerGuiE2ETest}
 *       and {@code PlanetSelectorGuiE2ETest} both placed their machine at (8, 64, 8) — harmless
 *       under one world per method, a straight overwrite when the world is shared. The plot
 *       allocator removes that without anyone having to notice it.</li>
 *   <li><b>An open screen outlives its scenario.</b> Every scenario here opens one; each closes it,
 *       and the shared reset closes anything left and then asserts the screen is gone.</li>
 *   <li><b>A GLOBAL query answering with a neighbour's object.</b>
 *       {@link #clickingScanThenBuildAssemblesRocket()} reads {@code stellurgytest rocket list}, which is
 *       world-wide, and narrows it to {@link Plot#contains}.</li>
 *   <li><b>A GUI that must stay open.</b> {@link #thePilotCopiesPicksAndArmsAtTheConsoleWithNothingButClicks()}
 *       works with the console still open — the full client reset would close the very screen it is
 *       about to click. It reads what each click DID (the command reaching the console, the
 *       console's own arm/disarm/refuse decision, and the armed state that survives it) rather than
 *       the reply it produced: the replies were chat, and a chat line is a rendering of the thing
 *       the click changed.</li>
 * </ul>
 *
 * <p>The lane is wide (128) because two members need more than a 64-block box: the railgun pair
 * stands 60 blocks apart, and the rocket fixture builds a pad plus a launch clearance.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code GuidanceComputerGuiE2ETest}, {@code PlanetSelectorGuiE2ETest},
 * {@code NavigationComputerGuiE2ETest}, {@code InventoryBypassRedirectE2ETest},
 * {@code RocketBuilderGuiE2ETest}, {@code RailgunCargoTransitE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MachineGuiClientGroupTest extends AbstractSharedClientE2ETest {

    /**
     * The cargo this scenario FIRES, and therefore what the destination must hold.
     *
     * <p>Not a threshold: it is the arrangement's own stack, read back at the other end.</p>
     */
    private static final int FIRED_CARGO_COUNT = 16;

    private static final int Y = Plot.DEFAULT_Y;

    /** Where a scenario's machine stands inside its plot, and where the player stands to reach it. */
    private static final int MACHINE_DX = 16;
    private static final int MACHINE_DZ = 16;

    /**
     * The DEADLINE for one of the rocket assembler's timed passes to end, in server ticks — not how
     * long a pass is expected to take. Its length is production's
     * {@code buildSpeedMultiplier * volume / 10 * MAXSCANDELAY}, a function of the fixture; this is
     * the 3 600 the re-pressing loop it replaced was given for BOTH passes together, and it is now
     * given to each, so it binds only on a pass that never ends — an unpowered machine, or a press
     * that never reached it.
     */
    private static final int PASS_TICKS = 3600;

    private static final String GUI_MODULAR = "dev.stannismod.stellurgy.libvulpes.inventory.GuiModular";
    private static final String GUI_CHEST = "net.minecraft.client.gui.inventory.GuiChest";
    private static final String CHIP = "stellurgy:planetidchip";

    /** {@code dev.stannismod.stellurgy.api.Constants.STAR_ID_OFFSET}. */
    private static final int STAR_ID_OFFSET = 10000;


    // Navigation console button ids — the console's own module ids, which libVulpes puts straight
    // on the GuiButton.
    private static final int BUTTON_COPY = 0;
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_PICK_FIRST = 10;
    /** Addresses the probe seeds into the brought crystal: sectors 100..102, named {@code probe-N}. */
    private static final int SEEDED = 3;
    private static final int FIRST_SECTOR = 100;

    /** The console's hold-fire packet id, {@code TileWeaponConsole#NET_TOGGLE_HOLD_FIRE}. */
    private static final int CONSOLE_TOGGLE_HOLD_FIRE = 0;
    /** The sensor's mode packet id, {@code TileFireControlSensor#NET_TOGGLE_MODE}. */
    private static final int SENSOR_TOGGLE_MODE = 0;
    /**
     * How far down the plot the far press leaves from, in blocks: three times vanilla's 8-block chest
     * reach ({@code TileEntityLockableLoot#isUsableByPlayer}'s 64.0 squared), so no rounding of the
     * player's position can bring him within it, and inside the plot's 128.
     */
    private static final int FAR_FROM_CONSOLE = 24;
    /** Vanilla's container reach, squared: {@code TileEntityLockableLoot#isUsableByPlayer}'s 64.0. */
    private static final double VANILLA_CONTAINER_REACH_SQ = 64.0D;

    private static final String SHIP_COUNT = "ship";
    private static final String SOURCE_COUNT = "source";
    private static final String TARGET = "target";
    private static final String ARMED = "armed";

    // Railgun probe fields.
    private static final String FIRED = "fired";
    private static final String DEST_MATCHED = "destMatched";
    private static final String SRC_REMAINING = "srcInputRemaining";
    private static final String FIRE_STATUS = "fireStatus";
    private static final String DEST_LOADED = "destLoaded";

    @Override
    protected String subsystem() {
        return "machine-gui";
    }

    /**
     * A wide lane: the railgun pair needs 60 blocks between its two multiblocks and the rocket
     * fixture wants a pad plus clearance, neither of which fits the default 64-block box.
     */
    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(4000, 4000, 128, 128);
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    private void warmupPlotChunks() throws Exception {
        int dim = plot().dim;
        int cx0 = plot().originX >> 4;
        int cz0 = plot().originZ >> 4;
        int cx1 = (plot().originX + plot().size - 1) >> 4;
        int cz1 = (plot().originZ + plot().size - 1) >> 4;
        for (int cx = cx0 - 1; cx <= cx1 + 1; cx++) {
            for (int cz = cz0 - 1; cz <= cz1 + 1; cz++) {
                exec("stellurgytest chunk forceload " + dim + " " + cx + " " + cz);
            }
        }
    }

    /**
     * Places {@code blockId} on a stone footing at the machine offset and stands the player one
     * block above it, looking straight down — the pose every one of these GUIs is opened from.
     * Returns the machine's world position as {@code {x, y, z}}.
     */
    private int[] placeMachineAndStandOnIt(String blockId) throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("place " + blockId + " at " + x + "," + Y + "," + z);
        warmupPlotChunks();
        String place = exec("stellurgytest place " + dim + " " + x + " " + Y + " " + z + " " + blockId);
        // absence is the answer on the first half: the place verb writes `placed` only on
        // its success path, and the `ok()` beside it accepts the other shape it answers.
        scenario().requireArranged("could not place " + blockId + ": " + place,
                Reply.of(place).boolOr("placed", false) || Reply.of(place).ok());

        // Stand ON the machine's own column, one block up, looking down at its top face. The
        // source classes all used exactly this pose; in open air it needs no terrain at all.
        //
        // A LINK on the CLIENT applying the move. The note this replaces was right about the CHUNK
        // record and wrong to conclude there was nothing to wait on: `chunk_data_applied` is
        // change-gated, so a chunk the client already holds sends nothing and a wait for it would
        // never return — but `client_pos_look_applied` is written for EVERY server-driven placement,
        // gated by nothing, so this one always has something to close on.
        long standMark = clientEvents().mark();
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5) + " 0 90");
        awaitClientPlacedNear(standMark, x + 0.5, z + 0.5,
                "every scenario in this class opens a GUI from where the player stands");
        return new int[]{x, Y, z};
    }

    /**
     * Right-clicks the machine open, and asserts the open as the CHAIN it is: the click reached the
     * server, the server opened a container for it, and the client displayed a screen.
     *
     * <p>The re-click stays, because it is a stimulus and not an observation: a single interaction
     * is occasionally dropped (the packet lands a tick before the chunk and the player have settled)
     * and no amount of waiting recovers a click that never registered. What changed is what the loop
     * WATCHES — the client's own record of a GUI being displayed, read from a mark taken before the
     * first click, instead of sampling {@code report_state} and hoping the sample lands while the
     * screen is there.</p>
     *
     * <p><b>Does not await {@code gui_container_served}.</b> Since libVulpes was folded into
     * Stellurgy's container, every machine here (libVulpes' own {@code BlockTile} /
     * {@code BlockMultiblockMachine} do the {@code openGui}) is served by Stellurgy's gui handler, so
     * that record IS written; this loop simply does not need it. Its server link is
     * {@code container_opened}, which Forge posts only once the handler has answered with a
     * container; a request the handler refused is an ABSENCE of that record beside a present
     * {@code right_click_block}, which is exactly the distinction the old screen poll could not
     * make.</p>
     */
    /** How long a GUI round trip may take — a deadline for one discrete record, never a settle. */
    private static final int GUI_LINK_BUDGET_TICKS = 200;

    /**
     * Press a GUI control whose server handler answers by RE-OPENING the machine's screen, and wait
     * for the client to display that screen. The re-open is the press's receipt: it happens inside
     * the handler that applied the press, so the state the press changed is on the server by the
     * time the client records it.
     */
    private void clickAndAwaitReopen(int buttonId, String what) throws Exception {
        long mark = clientEvents().mark();
        bot().clickButtonById(buttonId);
        // The record names the screen by its SIMPLE class name, not GUI_MODULAR's qualified one.
        clientEvents().awaitField(mark, "client_gui_opened", "gui", "GuiModular",
                what + " must be applied by the server, which re-opens the screen when it is",
                GUI_LINK_BUDGET_TICKS);
    }

    private String openMachineGui(int[] at) throws Exception {
        Events events = serverEvents();
        long serverMark = events.mark();
        long clientMark = clientEvents().mark();

        // The CLICK is a stimulus and the screen is a LINK, so they go into the pair built for it:
        // `awaitMatching(…, stimulus)` re-clicks between reads until the client records a GUI of its
        // own. What stood here was a retry loop around a wait that returned its last reading either
        // way, which meant the six attempts were really six budgets spent in series with no verdict
        // between them. The failure stays a ARRANGEMENT one and keeps its whole diagnostic.
        String displayed = "";
        try {
            displayed = clientEvents().awaitMatching(clientMark, "client_gui_opened",
                    reply -> !Events.recordsContainingAll(reply, "\"gui\":\"Gui").isEmpty(),
                    "carrying a Gui* screen",
                    "right-clicking the machine must open its GUI on the CLIENT", 6 * 60,
                    () -> bot().rightClickBlock(at[0], at[1], at[2], EnumFacing.UP,
                            EnumHand.MAIN_HAND));
        } catch (AssertionError never) {
            displayed = clientEvents().since(clientMark, "client_gui_opened");
        }

        String screen = screenOf(bot().reportState());
        if (!screen.startsWith(GUI_MODULAR)) {
            long directMark = clientEvents().mark();
            JsonObject direct = bot().interactBlock(at[0], at[1], at[2]);
            String afterDirect;
            try {
                afterDirect = clientEvents().await(directMark, "client_gui_opened",
                        "a direct interact on the machine, for the failure message", GUI_LINK_BUDGET_TICKS);
            } catch (AssertionError none) {
                afterDirect = "no screen opened for it";
            }
            scenario().arrangementFailed("right-clicking the machine must open its GUI, and the"
                    + " chain says WHERE it stopped rather than that the screen was empty."
                    + " screen=\"" + screen + "\""
                    + " clicksThatReachedTheServer=" + events.since(serverMark, "right_click_block")
                    + " containersTheServerOpened=" + events.since(serverMark, "container_opened")
                    + " screensTheClientDisplayed=" + displayed
                    + " afterDirectClick=\"" + screenOf(bot().reportState())
                    + "\" (" + afterDirect + ") clickResult=" + direct
                    + " blockAtMachine=" + bot().blockState(at[0], at[1], at[2])
                    + " playerState=" + bot().reportState());
        }
        // The ORDER is one server call stack — the interact event is posted before the block is
        // activated, and the container is opened from inside that activation — so it is asserted as
        // a chain rather than as two independent facts.
        events.assertChain(serverMark, "a right-click that opens a machine's GUI must REACH the"
                        + " server and make it open a container: the screen the player ends up"
                        + " looking at is the end of that chain, not the whole of it", 60,
                "right_click_block", "container_opened");
        return screen;
    }

    /**
     * The CLIENT's own records of {@code type} since {@code mark}, waited for until one carries
     * {@code needle} (case-insensitively) or the budget runs out — the client half of a chain, which
     * the server's event log cannot see.
     *
     * <p>Returns the reply, so a caller's failure prints what the client DID record instead of one
     * stale sample.</p>
     *
     * <p><b>The loop is gone.</b> This was a hand-rolled `awaitCarrying` — sample the log every five
     * ticks until a needle appears — and the shared reader has had that verb the whole time, plus a
     * case-folding record matcher ({@code recordsContainingAllIgnoringCase}, which exists because prose
     * case belongs to a translation and not to a contract). What the copy left behind: no failure
     * narrative, no four-cause triage for an empty window, and its own budget arithmetic to keep
     * right. The old justification — "the shared base offers Events over the server probe only" —
     * was already false: {@code clientEvents()} is on the base and this method called it.</p>
     */
    private String awaitClientRecords(long mark, String type, String field, Object value,
                                      int tickBudget) throws Exception {
        return clientEvents().awaitField(mark, type, field, value,
                "the CLIENT must record a `" + type + "` whose " + field + " is " + value,
                tickBudget);
    }

    private static int readInt(String json, String field) {
        Reply reply = Reply.of(json);
        assertTrue("expected an int `" + field + "` in: " + json, reply.has(field));
        return reply.integer(field);
    }

    /**
     * Whether any {@code region_scan_advanced} in a {@code since} reply ended on a discovery.
     *
     * <p>The instrument records a completion pass whenever it CHANGED something, which includes a
     * pass that only replaced the survey object, so a record on its own does not mean a look
     * resolved — the count does.</p>
     */
    private static boolean anyDiscoveryResolved(String sinceReply) {
        for (String record : Events.records(sinceReply)) {
            if (Events.number(record, "discoveries") >= 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean readBoolean(String json, String field) {
        Reply reply = Reply.of(json);
        assertTrue("expected a flag `" + field + "` in: " + json, reply.has(field));
        return reply.bool(field);
    }

    /** {@code field} as the text the verb wrote, refusing when the reply carries none. */
    private static String readGroup(String json, String field) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        String value = Reply.of(json).textOr(field, null);
        return value;
    }

    // ── rocket assembler ──────────────────────────────────────────────────────

    /**
     * From {@code RocketBuilderGuiE2ETest}. Builds the rocket structure with
     * {@code /stellurgytest fixture rocket}, opens the assembler's GUI by right-click, and drives the real
     * two-button assembly flow entirely through the client GUI: {@code clickButtonById(0)} (Scan),
     * then {@code clickButtonById(1)} (Build) pressed on a poll —
     * {@code TileRocketAssemblingMachine.useNetworkData} ignores a Build press while
     * {@code isScanning()}, so it simply "takes" once the scan pass finishes.
     *
     * <p>Unlike the headless {@code server/RocketAssemblySmokeTest} — which calls
     * {@code scanRocket}/{@code assembleRocket} directly — this exercises the machine's real
     * energy-gated {@code performFunction} tick loop, so the builder is kept powered via
     * {@code /stellurgytest energy inject}.</p>
     */
    @Test
    public void clickingScanThenBuildAssemblesRocket() throws Exception {
        int dim = plot().dim;
        int baseX = plot().x(8);
        int baseZ = plot().z(8);

        scenario().arranging("build the rocket fixture on a platform inside the plot");
        warmupPlotChunks();
        // The pad is built in open air on ground of the scenario's own making, so no world seed can
        // put a hill under it — the failure mode FreeFlightModeTest carries a pre-clear for.
        String footing = exec("stellurgytest fill " + dim + " " + baseX + " " + (Y - 1) + " " + baseZ
                + " " + (baseX + 12) + " " + (Y - 1) + " " + (baseZ + 12) + " minecraft:stone");
        scenario().requireArranged("pad footing fill must succeed: " + footing,
                Reply.of(footing).ok());

        // NO ASSEMBLE HERE, and that is the scenario: the player clicks SCAN and then BUILD on the
        // assembler's own GUI, so the craft must be LAID and left. The variant is written out as
        // "simple" rather than left to the probe's default — the command carried no variant at all
        // until 2026-09-21, and a default is a decision taken by whoever is not looking.
        // The site is ALLOCATED from this scenario's plot rather than built from the same two
        // coordinates by hand: an allocated site is the one whose working volume is checked against
        // the plot's own bounds, and the halo below (7, sized to the 12-block footing laid above)
        // is exactly the kind of reach that check exists for.
        int[] bp = RocketFixture.placeAt(plot().siteAt(8, 8), this::exec, "simple", 7, 12,
                "the craft the player builds from the assembler's GUI, and the platform it stands on");
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];
        String builder = dim + " " + bx + " " + by + " " + bz;
        scenario().record("builderPos", bx + "," + by + "," + bz)
                .describeOnFailureWith("stellurgytest rocket list " + dim);

        scenario().arranging("stand on the launchpad within reach of the builder");
        long standMark = clientEvents().mark();
        exec("tp @a " + (baseX + 2.5) + " " + (Y + 1) + " " + (baseZ + 2.5) + " 0 0");
        awaitClientPlacedNear(standMark, baseX + 2.5, baseZ + 2.5,
                "\"within reach of the builder\" is a claim about where the client stands, and the"
                        + " GUI below is opened from there");

        String screen = openMachineGui(new int[]{bx, by, bz});
        scenario().requireArranged("expected the assembler GUI to open, got: " + screen,
                screen.startsWith(GUI_MODULAR));

        scenario().asserting("Scan then Build, clicked on the real GUI, assemble a rocket");
        Events events = serverEvents();
        long buildMark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        exec("stellurgytest energy inject " + builder + " 100000000");
        bot().clickButtonById(0);

        // TWO PASSES, and each is now waited for on the machine's own record. Scan and Build each
        // start a TIMED pass that `performFunction` counts down one tick at a time, and production
        // IGNORES a Build press that arrives while a pass is still running — `useNetworkData`
        // returns on `isScanning()` with nothing said. The loop that stood here re-pressed Build
        // every forty ticks for up to 3 600, so the press that "took" was simply the first one to
        // land after the scan had happened to end, and every press before it was discarded unseen.
        // `assembler_pass_finished` IS that end, so Build is pressed once, after it.
        //
        // Success is then read off the ROCKET standing in this scenario's own plot, not off
        // `rocket_assembled`'s status, and the reason is a property of the recorder rather than a
        // preference: `rocket_assembled` is taken at
        // assembleRocket's RETURN and carries the tile's status AS OF THAT RETURN, and the last
        // thing a SUCCESSFUL ordinary build does before returning is re-scan its own pad "so the UI
        // immediately reflects the post-build state" — which finds the rocket it has just spawned
        // and overwrites FINISHED with ALREADY_ASSEMBLED. So on this path FINISHED is a status no
        // reader at that seam can ever observe: waiting for it spins the full budget out on a
        // machine that built the rocket on its first press. (Only the tier-2 fork returns straight
        // after setting FINISHED, and every record here says tier2:false.) Measured 2026-09-06: 29
        // assembly exits, all ALREADY_ASSEMBLED, the first of them the successful build.
        //
        // What the events still buy over the pre-migration form — which polled this same list and,
        // after three minutes, printed it — is the failure: every press that reached the machine
        // with the canScan/isScanning pair it was judged on, and every attempt at an assembly with
        // the status it ended on. That tells a dropped packet from a refused build from a build
        // that never ran, which the list alone cannot.
        String machinePos = bx + "," + by + "," + bz;
        events.awaitRecordWithFields(buildMark, "assembler_pass_finished",
                "the SCAN pass must end before Build can take - production discards a Build press"
                        + " made during it", PASS_TICKS,
                "pos", machinePos, "building", "false");
        bot().clickButtonById(1);
        // The build pass's own verdict. Awaited as the ASSEMBLY ending at this machine, not as a
        // status: which status it ends on is explained above and is not the success test.
        events.awaitRecordWithFields(buildMark, "rocket_assembled",
                "the BUILD pass must end in an assembly at this machine", PASS_TICKS,
                "pos", machinePos);
        String assemblies = events.since(buildMark, "rocket_assembled");
        String list = exec("stellurgytest rocket list " + dim);
        int rocketId = rocketIdInThisPlot(list);
        String presses = events.since(buildMark, "assembler_command_received");
        assertTrue("clicking Scan then Build on the real GUI must ASSEMBLE a rocket standing in "
                        + plot() + "; rocket list was " + list
                        + " | every exit of assembleRocket since the first click, with the status it"
                        + " ended on: " + assemblies
                        + " | every press that reached the machine, with the canScan/isScanning pair"
                        + " that decides whether it was acted on or silently ignored: " + presses,
                rocketId >= 0);
        events.assertChain(buildMark, "a rocket assembled at this machine must have been COMMANDED"
                + " through the GUI - an assembly with no press behind it would be some other"
                + " scenario's machine answering", 40,
                "assembler_command_received", "rocket_assembled");
        scenario().record("rocketId", rocketId);

        // Player truth: the CLIENT world receives the assembled rocket entity — the spawn reached
        // the player's own world, not just the server's registry. That is a LINK (the spawn packet
        // applied), so it is the client's own record of the entity joining, not a proximity count
        // that reads 0 both for "no rocket" and for "a rocket the client has not been told about".
        //
        // The record has to survive until this asks, and `entity_joined_world` is the chattiest type
        // the client keeps — its ring holds the last 256 of them. That is what makes the loop's EARLY
        // EXIT above load-bearing rather than merely tidy: it returns on the tick the rocket appears,
        // so only a few hundred ticks of joins can sit between the spawn and this read.
        String joined = awaitClientRecords(clientMark, "entity_joined_world",
                "cls", "EntityRocket", 200);
        assertTrue("the assembled rocket must arrive in the CLIENT's world - a rocket only the"
                        + " server knows about is not one the player can board. Entities the client"
                        + " saw join since the first click: " + joined,
                Events.anyRecordHas(joined, "cls", "EntityRocket"));

        bot().closeScreen();
    }

    /**
     * The id of a rocket standing in THIS scenario's plot, or -1.
     *
     * <p>{@code stellurgytest rocket list} is a GLOBAL query. Taking "the one that is there" is correct
     * only while the world holds exactly one rocket, which is precisely what a shared world stops
     * guaranteeing.</p>
     */
    /**
     * Every rocket the PREVIOUS scenario left behind goes, before this one builds its own — see
     * {@link RocketList#clearFrom} for what a plot cannot contain and why.
     *
     * <p>A {@code @Before} for the same reason the base's whole reset is one: JUnit runs
     * {@code @After} before the rules finish, so cleanup the next scenario must see belongs at the
     * next scenario's start.</p>
     */
    @Before
    public void clearRocketsLeftByTheLastScenario() throws Exception {
        System.out.println("[reset] rockets cleared from this class's world: "
                + RocketList.clearFrom(this::exec, 0));
    }

    private int rocketIdInThisPlot(String list) {
        java.util.List<RocketList.Entry> mine = RocketList.inPlot(list, plot());
        return mine.isEmpty() ? -1 : mine.get(0).id;
    }

    // ── railgun ───────────────────────────────────────────────────────────────

    /**
     * From {@code RailgunCargoTransitE2ETest}. Issue #61 ("[BUG] Railgun does not work"): a
     * same-dimension shot fires with a real client connected — cargo leaves the source input and
     * arrives at the destination output (status FIRED).
     *
     * <p>{@code RailgunFiringContractTest} pins these contracts on a dedicated server; this re-pins
     * the player-visible ones with a live client, so a client/server desync in the teleport path
     * would surface here where the server-only test is blind.</p>
     */
    @Test
    public void cargoTransitsBetweenLinkedRailgunsClientSide() throws Exception {
        int dim = plot().dim;
        scenario().arranging("build and validate two linked railguns 60 blocks apart");
        warmupPlotChunks();
        int sx = plot().x(4), sz = plot().z(32);
        int dx = plot().x(64), dz = plot().z(32);
        buildAndCompleteRailgun(sx, sz);
        buildAndCompleteRailgun(dx, dz);
        scenario().record("source", sx + "," + Y + "," + sz).record("dest", dx + "," + Y + "," + dz);

        scenario().asserting("cargo transits from the source's input to the destination's output");
        String fire = exec("stellurgytest infra railgun-fire " + dim + " " + sx + " " + Y + " " + sz
                + " " + dim + " " + dx + " " + Y + " " + dz + " minecraft:cobblestone 16");
        scenario().requireArranged("railgun-fire probe must succeed: " + fire,
                Reply.of(fire).ok());

        assertTrue("railgun MUST fire to a linked railgun in the same dimension with a client "
                + "connected (issue #61 baseline); fire=" + fire,
                "true".equals(readGroup(fire, FIRED)));
        assertTrue("status must read FIRED after a successful shot; fire=" + fire,
                "FIRED".equals(readGroup(fire, FIRE_STATUS)));
        assertTrue("destination output port must contain >= 16 cobblestone after firing; fire="
                + fire, readInt(fire, DEST_MATCHED) >= FIRED_CARGO_COUNT);
        assertEquals("source input port must be drained after firing; fire=" + fire,
                0, readInt(fire, SRC_REMAINING));
    }

    /**
     * From {@code RailgunCargoTransitE2ETest}. Under a live client, a genuinely unavailable
     * destination (an unregistered dimension that cannot be loaded) does NOT fire and REPORTS the
     * reason (TARGET_UNAVAILABLE) — the #61 fix's "no more silent no-op" — with the cargo preserved.
     */
    @Test
    public void railgunReportsUnavailableForUnloadableDestinationClientSide() throws Exception {
        int dim = plot().dim;
        // Not registered on the harness server, so production cannot load it however hard it tries.
        final int unregisteredDim = 31337;

        scenario().arranging("build and validate one railgun");
        warmupPlotChunks();
        int sx = plot().x(4), sz = plot().z(32);
        buildAndCompleteRailgun(sx, sz);

        scenario().asserting("an unloadable destination is refused, out loud, with the cargo kept");
        String fire = exec("stellurgytest infra railgun-fire " + dim + " " + sx + " " + Y + " " + sz
                + " " + unregisteredDim + " 0 64 0 minecraft:cobblestone 16");
        scenario().requireArranged("railgun-fire probe must succeed: " + fire,
                Reply.of(fire).ok());

        assertTrue("railgun must NOT fire at an unloadable (unregistered) destination; fire=" + fire,
                "false".equals(readGroup(fire, FIRED)));
        assertTrue("unregistered dim cannot be loaded -> destLoaded:false; fire=" + fire,
                "false".equals(readGroup(fire, DEST_LOADED)));
        assertTrue("status must report TARGET_UNAVAILABLE (not a silent no-op); fire=" + fire,
                "TARGET_UNAVAILABLE".equals(readGroup(fire, FIRE_STATUS)));
        assertEquals("cargo must be preserved on a failed shot; fire=" + fire,
                16, readInt(fire, SRC_REMAINING));
    }

    private void buildAndCompleteRailgun(int x, int z) throws Exception {
        int dim = plot().dim;
        String fixture = exec("stellurgytest fixture multiblock railgun " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("fixture multiblock railgun failed at " + x + "," + Y + "," + z
                + ": " + fixture, Reply.of(fixture).ok());
        String tryComplete = exec("stellurgytest machine try-complete " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("railgun must validate at " + x + "," + Y + "," + z + ": "
                + tryComplete, Reply.of(tryComplete).bool("isComplete"));
    }

    // ── guidance computer ─────────────────────────────────────────────────────

    /**
     * From {@code GuidanceComputerGuiE2ETest}. A planet-id chip handed to the player is
     * shift-clicked ({@code ClickType.QUICK_MOVE}) out of the player inventory and into the guidance
     * computer's own slot; {@code report_slots} confirms the chip crossed from a {@code playerSlot}
     * into a machine slot — i.e. the click drove {@code Container.transferStackInSlot} on the server
     * and the result synced back. Slots are addressed by the container slot number the report gives,
     * never by guessed coordinates.
     *
     * <p>red-witnessed: with {@code TileGuidanceComputer#setInventorySlotContents} at {@code super.setInventorySlotContents(slot, stack)} not storing what is put into its slot:
     * "`slots` holds no element whose `item` is stellurgy:planetidchip — it holds 0",
     * 2026-09-28. Making {@code isItemValidForSlot} refuse the chip left this GREEN: the container's
     * quick-move never asks it.</p>
     */
    @Test
    public void shiftClickingChipMovesItIntoTheGuidanceComputer() throws Exception {
        int[] at = placeMachineAndStandOnIt("stellurgy:guidanceComputer");
        // No advance: the give's slot packet leaves before the window the GUI below opens, on one
        // connection, so a client showing that window already holds the chip.
        exec("give @a " + CHIP + " 1");

        scenario().arranging("open the guidance computer's GUI");
        String screen = openMachineGui(at);
        scenario().record("screen", screen);

        scenario().measuring("find the chip in the player half of the open container");
        JsonObject before = bot().reportSlots();
        int chipSlot = findSlotWithItem(before, CHIP, true);
        scenario().requireArranged("chip not found in the player inventory portion of the GUI: "
                + before, chipSlot != -1);

        scenario().asserting("a shift-click quick-moves the chip into the machine's own slot");
        // `report_slots` reads the CLIENT's copy of the container, and the client applies its own
        // prediction of a quick-move before the packet is even sent — so the slots below would show
        // the move whether or not the server made it. The server answers every click it handles with
        // a confirmation, sent after its own slotClick ran: that record is the barrier. It is NOT the
        // verdict — for a quick-move `accepted` compares two empty stacks whatever happened — so the
        // verdict is read from the machine's own inventory on the server.
        long clickMark = clientEvents().mark();
        bot().clickSlot(chipSlot, 0, "QUICK_MOVE");
        clientEvents().await(clickMark, "client_click_confirmed",
                "the server must handle the shift-click at all", GUI_LINK_BUDGET_TICKS);
        String machineInventory = exec("stellurgytest hatch read " + plot().dim + " " + at[0] + " " + at[1]
                + " " + at[2]);
        // The SERVER must have moved the chip into the guidance computer — a positive claim, so the
        // reply's refusing reader: it fails naming the machine's whole inventory if the chip is not
        // one of its slots.
        Reply.of("stellurgytest hatch read " + at[0] + " " + at[1] + " " + at[2], machineInventory)
                .element("slots", "item", CHIP);

        JsonObject after = bot().reportSlots();
        assertTrue("shift-click did not move the chip into a guidance computer slot: " + after,
                findSlotWithItem(after, CHIP, false) != -1);
        assertTrue("chip still left behind in the player inventory: " + after,
                findSlotWithItem(after, CHIP, true) == -1);

        bot().closeScreen();
    }

    // ── observatory: the region scan ──────────────────────────────────────────

    /**
     * Builds the observatory as a REAL multiblock and stands the player beside its controller.
     *
     * <p>A bare controller block is not enough here, and the difference is not cosmetic: a
     * multiblock machine refuses to open its GUI until its structure validates, so a lone block
     * right-clicks to nothing at all (measured — the click returned PASS with no screen). The
     * fixture's footprint runs from the controller towards +Z and +Y, so the player is placed on the
     * -Z side, which is the one face left in the open.</p>
     */
    private int[] buildObservatoryAndStandBesideIt() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("build the observatory multiblock at " + x + "," + Y + "," + z);
        warmupPlotChunks();
        String fixture = exec("stellurgytest fixture multiblock observatory " + dim + " " + x + " " + Y
                + " " + z);
        scenario().requireArranged("fixture multiblock observatory failed: " + fixture,
                Reply.of(fixture).ok());

        // ONE ask: libVulpes' `attemptCompleteStructure` walks the structure's blocks synchronously
        // inside the probe call, so no amount of ticking turns a refusal into a success. An eight-ask
        // retry stood here; with every ask logged (2026-09-28), this validation and the 26 others in
        // the server tier succeeded on the first. A refusal is the fixture's to explain.
        String completed = exec("stellurgytest machine try-complete " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("the observatory structure must validate on the first ask: "
                        + completed,
                Reply.of(completed).bool("isComplete"));

        // Stand on the structure's open face, one block from the controller, looking at it. The
        // footing is not decoration: the fixture clears its own footprint to air, and a player
        // teleported into that air FALLS — measured, and it reads exactly like a dead click,
        // because the server silently drops an interaction from out of reach.
        StringBuilder footing = new StringBuilder();
        for (int dx = -1; dx <= 1; dx++) {
            footing.append(exec("stellurgytest place " + dim + " " + (x + dx) + " " + Y + " " + (z - 1)
                    + " minecraft:stone")).append(' ');
        }
        // Stand in the MIDDLE of the footing block, not at its edge: the block at z-1 spans
        // [z-1, z), so z-1.5 is half a block beyond it and over open air.
        //
        // The fall this used to suffer under load (y ≈ 148.8 with the footing at 150) is a placement
        // into a chunk column the client had not been sent: it shows a blank stand-in there, sees air
        // under its feet and falls, and the server accepts the fall. Re-issuing the teleport and
        // ending the wait on ANY applied position write did not ask the question that matters. The
        // placement now waits for the client to hold the real column first (its own
        // `chunk_data_applied`), then places once and links on the placement landing near the stand.
        standOnFloorTheClientHolds(x + 0.5, Y + 1, z - 0.5, 0f, 0f,
                "the player must be put on the footing beside the machine");
        double standingY = bot().reportState().get("playerY").getAsDouble();
        scenario().requireArranged("the player fell off the footing (y=" + standingY + ", wanted "
                + (Y + 1) + ") — every click from here would be out of reach."
                + " footingPlacements=" + footing
                + " blockUnderFoot=" + bot().blockState(x, Y, z - 1)
                + " controller=" + bot().blockState(x, Y, z),
                Math.abs(standingY - (Y + 1)) <= 1.0);
        return new int[]{x, Y, z};
    }

    /**
     * The telescope's third tab, driven with nothing but clicks: switch to it, aim the instrument
     * farther out, press Observe, and let the observation finish — then check the crystal sitting in
     * the machine holds an address it did not have before.
     *
     * <p>The button ids are the tile's own module ids, which libVulpes puts straight on the
     * GuiButton: 2 is the third tab (the tab strip numbers itself from 0), 5 is the distance
     * increment and 6 is Observe. The tab strip and the machine share one id space, which is why the
     * scan controls were given ids above the strip's.</p>
     *
     * <p>The aim is read back from the SERVER after the clicks and the fixture system is placed at
     * whatever distance the clicks actually produced — so the arrangement follows the GUI rather
     * than assuming it worked.</p>
     *
     * <p>red-witnessed: with the distance button's write ({@code TileObservatory#useNetworkData} at {@code scanDistance = Math.max(1, Math.min(reach, scanDistance + nbt.getInteger("d")))}) skipped:
     * "clicking the distance button twice must move the aim out from 1: … aimDistance:1",
     * 2026-09-28.</p>
     *
     * <p>red-witnessed: NOT YET, with {@code ClientEvents#placeOntoGroundItHolds} at
     * {@code awaitPlacedNear(clientLog, placeMark, x, z, what, tickBudget);} for the stand wait in
     * {@link #buildObservatoryAndStandBesideIt}: it links on the client's {@code chunk_data_applied}
     * and {@code client_pos_look_applied}, both of which vanilla decides (the chunk send and the
     * teleport's position packet) — no Stellurgy code sits on that path to invert, so the only red
     * this wait can show is an arrangement failure, which is the outcome it exists to name.</p>
     */
    @Test
    public void theOperatorAimsTheTelescopeAndObservesWithNothingButClicks() throws Exception {
        int dim = plot().dim;
        int[] at = buildObservatoryAndStandBesideIt();
        String where = dim + " " + at[0] + " " + at[1] + " " + at[2];

        scenario().arranging("a blank crystal in the machine, and the default (no-research) regime");
        // The default game: without the research master switch a survey is not a matter of time —
        // what the instrument reaches, it resolves. That is what the player at this GUI sees, so it
        // is what this drives.
        exec("stellurgytest config set planetsMustBeDiscovered false");
        exec("stellurgytest config set telescopeScanBaseTicks 0");
        exec("stellurgytest config set telescopeLimitingMagnitude 30");
        exec("stellurgytest config set telescopeConeHalfAngleDegrees 20");
        String crystal = exec("stellurgytest telescope crystal " + where);
        scenario().requireArranged("could not put a crystal in the observatory: " + crystal,
                Reply.of(crystal).ok());

        // The reader REFUSES a world with no galactic address, so the arrangement check that used to
        // stand here — a `contains` on the rendered `"origin":"` — is the read itself.
        TelescopeReading before = TelescopeReading.at(this::exec, where);
        long[] home = before.originSectors();
        scenario().record("origin", before.originCellKey());

        scenario().arranging("open the observatory and switch to its region-scan tab");
        String screen = openMachineGui(at);
        scenario().record("screen", screen)
                .describeOnFailureWith("stellurgytest telescope info " + where);
        // Each of these presses is a round trip the server closes by RE-OPENING the GUI from inside
        // the handler that applied it (TileObservatory.useNetworkData: tab switch, aim distance),
        // so the client's own record of that screen is the press's receipt — and the next press
        // must land on the re-opened screen anyway.
        clickAndAwaitReopen(2, "the region-scan tab");

        scenario().asserting("the aim buttons reach the machine, and Observe starts the look");
        clickAndAwaitReopen(5, "the first aim-distance press");
        clickAndAwaitReopen(5, "the second aim-distance press");

        TelescopeReading aimed = TelescopeReading.at(this::exec, where);
        long aimDistance = aimed.aimDistance;
        assertTrue("clicking the distance button twice must move the aim out from 1: " + aimed.raw(),
                aimDistance > 1);

        // Put a system where the operator has it pointed — the default aim is +X, and the distance is
        // whatever his clicks produced. The aim is counted in STEPS of one star's territory, so the
        // cell it lands on is that many strides out; the seat is offset inside the territory, since
        // what a look must find is the system that OWNS the cell and not a star standing on it.
        long stepCells = aimed.stepCells;
        String system = exec("stellurgytest telescope system "
                + (home[0] + aimDistance * stepCells + 13L)
                + " " + home[1] + " " + home[2]);
        scenario().requireArranged("could not place a system to be found: " + system,
                Reply.of(system).ok());

        // Observe. The survey is a chain and is asserted as one: the aim ACCEPTED the region (it can
        // refuse — no origin, or a region it will not look at, and production only logs that), and
        // then the survey step actually MOVED (a completion pass that finds no crystal, no cell due
        // or too little distance data returns changing nothing, and says nothing). A red used to be
        // 400 ticks of telescope JSON that could not tell those apart.
        Events events = serverEvents();
        long scanMark = events.markInstrumented();
        bot().clickButtonById(6);
        events.assertChain(scanMark, "pressing Observe must reach the instrument, be ACCEPTED as a"
                        + " region to look at, and then advance the survey", 600,
                "region_scan_begun", "region_scan_advanced");
        String begun = events.since(scanMark, "region_scan_begun");
        assertTrue("the instrument must ACCEPT the region the operator aimed it at - a refusal here"
                + " is production's own verdict and the crystal below could never fill: " + begun,
                Events.anyRecordHas(begun, "accepted", "true"));

        scenario().measuring("the crystal in the machine, after a survey driven only by clicks");
        // The addresses ARE an accumulation and stay a single read — but what the test was waiting
        // for is not the accumulation, it is the completion pass that feeds it, and production
        // records that: `region_scan_advanced` is taken when a pass CHANGES what the instrument
        // holds, and carries the discovery count it ended on. So the wait is that record with a
        // non-zero count, and the crystal is read once afterwards. The old poll asked the crystal
        // twenty times and, on expiry, reported the same zero it had read at the start.
        events.awaitMatching(scanMark, "region_scan_advanced",
                MachineGuiClientGroupTest::anyDiscoveryResolved,
                "carrying a non-zero discovery count",
                "a survey driven entirely from the GUI must resolve at least one look into a"
                        + " discovery before the crystal can hold an address", 400);
        TelescopeReading done = TelescopeReading.at(this::exec, where);
        assertTrue("a survey driven entirely from the GUI left the crystal empty: " + done.raw()
                        + " surveySteps=" + events.since(scanMark, "region_scan_advanced"),
                done.addressesOnCrystal() >= 1);

        bot().closeScreen();
    }

    // ── planet selector ───────────────────────────────────────────────────────

    /**
     * From {@code PlanetSelectorGuiE2ETest}. Introspects the open GUI's buttons via
     * {@code report_buttons}, then clicks a planet button <em>by its stable mod-assigned id</em>
     * ({@code GuiButton.id} == the planet's dimension id; see {@code ModulePlanetSelector}).
     * Clicking a planet fires {@code TilePlanetSelector.onSelected} &rarr; {@code PacketMachine}
     * &rarr; server {@code useNetworkData} &rarr; {@code dimCache}, which the
     * {@code /stellurgytest selector info} probe then confirms — the whole client&rarr;server selection
     * round-trip rather than just "the GUI opened".
     *
     * <p>red-witnessed: with {@code TilePlanetSelector#useNetworkData} at {@code container.setSelectedSystem(dimId)} (the selection writes) skipped:
     * "clicking planet button 0 did not register a selection server-side: … hasSelection:false",
     * 2026-09-28. The link before it records at the handler's RETURN and stays green by design —
     * it says the packet arrived, not that it was applied.</p>
     * Pins INV-IVC-03 (clicking a planet button on the selector registers a server-side selection).
     */
    @Test
    public void selectingPlanetUpdatesServerSelection() throws Exception {
        int[] at = placeMachineAndStandOnIt("stellurgy:planetSelector");

        scenario().arranging("open the planet selector's GUI");
        String screen = openMachineGui(at);
        scenario().record("screen", screen)
                .describeOnFailureWith("stellurgytest selector info " + plot().dim + " " + at[0] + " "
                        + at[1] + " " + at[2]);

        scenario().measuring("pick a planet button by id range (control buttons sit outside it)");
        JsonObject buttons = bot().reportButtons();
        int planetId = ClientGuiTestSupport.findButtonId(buttons, 0, STAR_ID_OFFSET);
        scenario().requireArranged("no clickable planet button in selector GUI: " + buttons,
                planetId != Integer.MIN_VALUE);
        scenario().record("planetButtonId", planetId);

        scenario().asserting("clicking it registers the selection server-side");
        long selectMark = serverEvents().markInstrumented();
        bot().clickButtonById(planetId);
        serverEvents().awaitField(selectMark, "selector_selection_set", "pos",
                at[0] + "," + at[1] + "," + at[2],
                "clicking planet button " + planetId + " must reach THIS selector's server copy",
                GUI_LINK_BUDGET_TICKS);

        String selectorInfo = exec("stellurgytest selector info " + plot().dim + " " + at[0] + " " + at[1]
                + " " + at[2]);
        assertTrue("clicking planet button " + planetId
                + " did not register a selection server-side: " + selectorInfo,
                Reply.of(selectorInfo).bool("hasSelection"));
        assertTrue("selection did not resolve to a planet: " + selectorInfo,
                Reply.of(selectorInfo).has("selectedDim"));

        bot().closeScreen();
    }

    /**
     * The planet selector's star map, as it opens: 100 GUI pixels per AU of a planet's orbit,
     * measured from the edge of a 50-pixel stellar disc. That is the scale the map was drawn at
     * before the distance unit became a length — then a unit was a hundredth of an AU and one unit
     * was one pixel — and the change was ratified as one of representation, so the picture did not
     * move.
     *
     * <p>Acceptance, stated before the code: Earth's button centre stands {@code 100 x a + 50} pixels
     * from Sol's, {@code a} being Earth's orbit in AU as the server holds it, within 2 x sqrt(2)
     * pixels (derived beside the assertion). Read raw, a 100 km unit puts it 1.5 million pixels out on a
     * 2 000-pixel map, which is why the rocket's selector could not be clicked.</p>
     *
     * <p>Earth's orbit of one AU is also the default a body takes when nothing states one. That
     * coincidence hides nothing here: the subject is the SCALE, and the raw form misplaces a planet
     * at any orbit.</p>
     *
     * <p>What this does not see: the planetary view (a planet and its moons), pinned by
     * {@link #thePlanetaryViewDrawsAMoonWhereItDrewLunaAt150}, and the rocket's own selector, which
     * opens the same module over a rocket.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code ModulePlanetSelector#renderStarSystem} at {@code double orbitPx = properties.getOrbitalDist() / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU} — the orbit
     * radius — back on the raw unit ({@code properties.getOrbitalDist() * distanceZoomMultiplier}):
     * "Earth (1.0 AU) must be drawn 150.0 pixels from its star, slack 2.8284271247461903: … expected:
     * &lt;150.0&gt; but was:&lt;1496028.93842148&gt;". The two {@code requireArranged} lines are
     * arrangements.</p>
     */
    @Test
    public void theStarMapDrawsAPlanetAtAHundredPixelsPerAu() throws Exception {
        int[] at = placeMachineAndStandOnIt("stellurgy:planetSelector");

        scenario().arranging("open the planet selector's star map");
        String screen = openMachineGui(at);
        scenario().record("screen", screen);

        Reply earth = Reply.of(exec("stellurgytest planet info 0"));
        double orbitAu = earth.longInteger("orbitalDistance")
                / (double) dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        scenario().record("earthOrbitAu", orbitAu);

        scenario().measuring("the star's button and Earth's, as the open GUI lays them out");
        JsonObject buttons = bot().reportButtons();
        int solButtonId = STAR_ID_OFFSET + earth.integer("starId");
        JsonObject sol = null;
        JsonObject earthButton = null;
        for (JsonElement element : buttons.getAsJsonArray("buttons")) {
            JsonObject button = element.getAsJsonObject();
            int id = button.get("id").getAsInt();
            if (id == solButtonId) {
                sol = button;
            } else if (id == 0) {
                earthButton = button;
            }
        }
        scenario().requireArranged("the star map must carry Earth's star and Earth itself: " + buttons,
                sol != null && earthButton != null);
        // The disc a planet's orbit is measured from: the star view's `(int) (planetSizeMultiplier *
        // 100)` at the 0.5 the map opens with (ModulePlanetSelector.java:156, :331). Sol's own button
        // is drawn that wide too, which is what this reads, so a map that opened at another size is
        // refused here rather than read as a wrong scale.
        scenario().requireArranged("Sol's disc must be the 50 pixels the orbit is measured from: " + sol,
                sol.get("width").getAsInt() == STAR_DISC_PX);

        double dx = centreOf(earthButton, "x", "width") - centreOf(sol, "x", "width");
        double dy = centreOf(earthButton, "y", "height") - centreOf(sol, "y", "height");
        double expected = orbitAu * 100d + STAR_DISC_PX;
        // The slack, derived from how the buttons are placed: per axis the planet's offset is an int
        // truncation (below 1 pixel) and each of the two buttons' half-widths can round by half a
        // pixel, so each axis is off by under 2 and the distance by under 2 x sqrt(2).
        double tolerance = 2d * Math.sqrt(2d);

        scenario().asserting("Earth stands 100 pixels per AU beyond the star's disc");
        assertEquals("Earth (" + orbitAu + " AU) must be drawn " + expected + " pixels from its star, slack "
                + tolerance + ": sol=" + sol + " earth=" + earthButton, expected, Math.hypot(dx, dy), tolerance);

        bot().closeScreen();
    }

    /**
     * The planet selector's PLANETARY view — a planet and its moons, entered by clicking the planet
     * twice on the star map — draws each moon as it drew one while Luna stood at 150: half a pixel
     * per moon-view unit at the zoom the view opens with, measured from the edge of the planet's
     * disc, the moon-view distance being 150 for a moon at Luna's distance.
     *
     * <p>Acceptance, stated before the code: each moon's button centre stands
     * {@code 0.5 x 150 x orbit / 3 844 + W} pixels from Earth's, {@code W} being Earth's button width
     * as the open GUI reports it and {@code orbit} the moon's distance as the server holds it — 75 +
     * W for Luna — within 2 x sqrt(2) pixels, the same button-placement rounding as the star map's.
     * Read raw, Luna stands 1 922 + W pixels out, off the 2 000-pixel map.</p>
     *
     * <p>What this does not see: the rocket's own selector, which opens the same module; and a moon
     * at any distance but the one the world's moons stand at — the proportion is pinned in
     * {@code AstronomicalBodyHelperTest}.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code ModulePlanetSelector#renderPlanetarySystem} at
     * {@code AstronomicalBodyHelper.moonViewUnits(properties.orbitalDist) * MAP_PIXELS_PER_MOON_VIEW_UNIT}
     * back on the raw distance ({@code properties.orbitalDist}), this fails with "each moon must be
     * drawn 0.5 x moonViewUnits + Earth's width (150) pixels from Earth, slack 2.8284271247461903:
     * expected 2=225.0 drawn 2=2071.27907342299 …: arrays first differed at element [0];
     * expected:&lt;225.0&gt; but was:&lt;2071.27907342299&gt;". The {@code requireArranged} lines are
     * arrangements.</p>
     */
    @Test
    public void thePlanetaryViewDrawsAMoonWhereItDrewLunaAt150() throws Exception {
        int[] at = placeMachineAndStandOnIt("stellurgy:planetSelector");

        scenario().arranging("open the planet selector and click Earth twice to enter its planetary view");
        scenario().record("screen", openMachineGui(at));
        java.util.Map<Integer, Long> moonOrbits = new java.util.HashMap<>();
        for (int dim : dev.stannismod.stellurgy.test.DimList.from(this::exec).registered()) {
            Reply body = Reply.of(exec("stellurgytest planet info " + dim));
            // `planet info` on a registered dimension: the producer always writes parent.
            if (body.integer("parent") == 0) {
                moonOrbits.put(dim, body.longInteger("orbitalDistance"));
            }
        }
        scenario().requireArranged("Earth must have a moon for the planetary view to draw one", !moonOrbits.isEmpty());
        scenario().record("moonOrbits", moonOrbits.toString());

        // A player's click on a map button is two things at once: the button's action (select, then
        // zoom) and the mouse press the map rebuilds itself on. The harness cannot aim the hardware
        // mouse the map's own button loop reads (ModuleContainerPan.onMouseClicked reads
        // Mouse.getX/getY, not the click's coordinates), so the two halves are driven apart: the
        // action by button id, twice, then one press at the screen corner for the rebuild. The
        // rebuild runs inside that press, on the client thread, so the report below reads its result.
        scenario().requireArranged("the star map must carry Earth's button to click",
                buttonWithId(bot().reportButtons(), 0) != null);
        bot().clickButtonById(0);
        bot().clickButtonById(0);
        bot().clickScreenPoint(0, 0, 0);

        scenario().measuring("Earth's button and each moon's, as the planetary view lays them out");
        JsonObject buttons = bot().reportButtons();
        JsonObject earth = buttonWithId(buttons, 0);
        scenario().requireArranged("the planetary view must carry Earth and no star: " + buttons,
                earth != null && buttonWithId(buttons, STAR_ID_OFFSET) == null);
        int earthWidth = earth.get("width").getAsInt();

        StringBuilder expectedText = new StringBuilder();
        StringBuilder drawnText = new StringBuilder();
        double[] expected = new double[moonOrbits.size()];
        double[] drawn = new double[moonOrbits.size()];
        int i = 0;
        for (java.util.Map.Entry<Integer, Long> moon : moonOrbits.entrySet()) {
            JsonObject button = buttonWithId(buttons, moon.getKey());
            scenario().requireArranged("the planetary view must carry moon " + moon.getKey() + ": " + buttons,
                    button != null);
            // 0.5, 150 and 3 844: the view's zoom-1 distance multiplier (production's redrawSystem),
            // Luna's view distance as it stood before her distance was corrected, and her real one.
            expected[i] = 0.5d * 150d * moon.getValue()
                    / dev.stannismod.stellurgy.util.AstronomicalBodyHelper.MOON_REFERENCE_UNITS + earthWidth;
            drawn[i] = Math.hypot(centreOf(button, "x", "width") - centreOf(earth, "x", "width"),
                    centreOf(button, "y", "height") - centreOf(earth, "y", "height"));
            expectedText.append(moon.getKey()).append('=').append(expected[i]).append(' ');
            drawnText.append(moon.getKey()).append('=').append(drawn[i]).append(' ');
            i++;
        }
        // The slack, derived from how the buttons are placed: per axis the moon's offset is an int
        // truncation (below 1 pixel) and each of the two buttons' half-widths can round by half a
        // pixel, so each axis is off by under 2 and the distance by under 2 x sqrt(2).
        double tolerance = 2d * Math.sqrt(2d);

        scenario().asserting("each moon stands where the view drew Luna while she stood at 150");
        org.junit.Assert.assertArrayEquals("each moon must be drawn 0.5 x moonViewUnits + Earth's width ("
                + earthWidth + ") pixels from Earth, slack " + tolerance + ": expected " + expectedText
                + "drawn " + drawnText + "earth=" + earth, expected, drawn, tolerance);

        bot().closeScreen();
    }

    /** The button carrying {@code id} in a {@code report_buttons} reply, or null when there is none. */
    private static JsonObject buttonWithId(JsonObject report, int id) {
        for (JsonElement element : report.getAsJsonArray("buttons")) {
            JsonObject button = element.getAsJsonObject();
            if (button.get("id").getAsInt() == id) {
                return button;
            }
        }
        return null;
    }

    /** The star view's stellar disc, in GUI pixels, at the size the map opens with. */
    private static final int STAR_DISC_PX = 50;

    /** A reported button's centre along one axis. */
    private static double centreOf(JsonObject button, String edge, String extent) {
        return button.get(edge).getAsInt() + button.get(extent).getAsInt() / 2d;
    }

    // ── navigation console ────────────────────────────────────────────────────

    /**
     * From {@code NavigationComputerGuiE2ETest}. The navigation console driven the way a pilot
     * drives it: right-click it open, then nothing but button clicks.
     *
     * <p>What is pinned, in the order the pilot does it:</p>
     * <ol>
     *   <li><b>Arming with nowhere to go is refused.</b> The negative comes first because it
     *       doubles as the proof that a click on this GUI reaches the server at all — and that
     *       proof is the console's own record of the command arriving, with the console's REFUSAL
     *       as the second half of the pair rather than the sentence it answers with.</li>
     *   <li><b>Copying a brought crystal does not empty it.</b></li>
     *   <li><b>The console lists what the ship now knows</b>, read off the real GUI's buttons.</li>
     *   <li><b>Picking a listed address aims the ship at THAT address.</b></li>
     *   <li><b>Arm, then disarm</b> — each a decision the console records, each reflected in the
     *       state that outlives it.</li>
     * </ol>
     *
     * <p>Runs on a console standing in the world, NOT on an assembled ship: the harness's
     * right-click takes literal coordinates and has no raycast, so a block that lives in a ship's
     * subspace cannot be clicked. That is a limit of the instrument, not of the contract — and the
     * pilot can legitimately arm before assembly, which is what this does.</p>
     */
    @Test
    public void thePilotCopiesPicksAndArmsAtTheConsoleWithNothingButClicks() throws Exception {
        int dim = plot().dim;
        int[] at = placeMachineAndStandOnIt("stellurgy:navigationComputer");
        int navX = at[0], navY = at[1], navZ = at[2];
        String where = dim + " " + navX + " " + navY + " " + navZ;
        scenario().describeOnFailureWith("stellurgytest nav status " + where,
                "stellurgytest nav modules " + where);

        scenario().arranging("seed the brought crystal and give the ship a blank one to copy into");
        String seed = exec("stellurgytest nav crystal " + where + " 0 " + SEEDED + " " + FIRST_SECTOR);
        scenario().requireArranged("the source slot must hold a crystal carrying " + SEEDED
                + " addresses: " + seed, String.valueOf(SEEDED).equals(Reply.of(seed).text("addresses")));
        // The ship's own crystal is the DESTINATION, and the copy is add-only into it: with that
        // slot empty there is nowhere to copy to and the button is a silent no-op.
        String shipCrystal = exec("stellurgytest nav crystal " + where + " 1 0");
        scenario().requireArranged("the ship slot must hold a (blank) crystal to copy INTO: "
                + shipCrystal, (Reply.of(shipCrystal).integer("addresses") == 0));
        NavStatus before = NavStatus.of(exec("stellurgytest nav status " + where));
        scenario().requireArranged("ARRANGEMENT CONTROL: the ship's own crystal must start EMPTY, "
                + "or the copy leg below cannot tell a successful copy from a pre-loaded console: "
                + before.raw(), before.shipCrystals == 0);

        emptyTheHand();
        String screen = openMachineGui(at);
        scenario().record("screen", screen);
        Events events = serverEvents();

        // ---- 1) Try to arm with nowhere to go. ------------------------------------------------
        // The chat overlay is no longer drained first. A mark taken before the click is what makes a
        // matching line belong to THIS stimulus, and it does it better than a clear: a clear leaves
        // a line already in flight, and it cannot see a reply that arrived and scrolled away.
        scenario().asserting("arming with no destination leaves the console unarmed");
        long refusalMark = events.markInstrumented();
        bot().clickButtonById(BUTTON_ARM);
        // The click REACHES the console, and the console REFUSES it. The two message links that
        // stood between those — the console's own tell, matched on a key, and the resolved line
        // hunted in the client's chat — are gone: what they said is that a refusal was announced,
        // and what this leg is about is that nothing was armed.
        //
        // This was ONE link for a while, because arming published nothing of its own: it is a state
        // on the tile, so once the announcement was struck out the ordered pair had no second half.
        // The COMMITMENT is now a decision of its own (`nav_arm_decided`, off the return of
        // production's `arm()`), which is what makes the refusal assertable as a refusal rather
        // than as an unchanged flag — and it separates the two failures the settled read below
        // cannot: a console that refused, and a console that armed something and lost it.
        events.assertChain(refusalMark, "a ARM click with nowhere to go must REACH the console and"
                        + " be REFUSED there — a click that never arrived, one that was refused,"
                        + " and one that armed and forgot are three different failures", 150,
                "nav_command_received", "nav_arm_decided");
        String refusals = events.since(refusalMark, "nav_arm_decided");
        // Printed on a GREEN run: `nav_arm_decided` and its three outcomes are new here, and a
        // reading that only ever appears inside a failure is a reading nobody has checked.
        System.out.println("[nav-console] arm decisions after the empty ARM click: "
                + Events.records(refusals));
        assertTrue("arming with nowhere to go must be refused FOR WANT OF A DESTINATION — the only"
                        + " meaning production's arm() has for false: " + refusals,
                Events.countRecords(refusals, "outcome", "REFUSED_NO_TARGET") > 0);
        NavStatus afterRefusal = NavStatus.of(exec("stellurgytest nav status " + where));
        assertFalse("arming with no destination chosen must leave the console UNARMED: "
                + afterRefusal.raw(), afterRefusal.armed);

        // ---- 2) Copy the brought crystal into the ship's own. ---------------------------------
        scenario().asserting("COPY writes the addresses across and leaves the source holding them");
        long copyMark = events.markInstrumented();
        bot().clickButtonById(BUTTON_COPY);
        events.assertChain(copyMark, "a COPY click must reach the console and the console must"
                        + " perform the copy - with no crystal in the ship slot the button is a"
                        + " silent no-op, which is the one thing polling the count could not tell"
                        + " from a slow round trip", 150,
                "nav_command_received", "crystal_copied");
        String copies = events.since(copyMark, "crystal_copied");
        assertTrue("the console must have had a ship crystal to copy INTO, or the counts below are"
                + " measuring the arrangement: " + copies, Events.anyRecordHas(copies, "shipCrystal", "true"));
        NavStatus copied = NavStatus.of(exec("stellurgytest nav status " + where));
        assertEquals("clicking COPY must write the brought crystal's addresses into the ship's own "
                + "crystal: " + copied.raw() + " copies=" + copies, SEEDED, copied.shipCrystals);
        assertEquals("and the brought crystal must KEEP them — the console exchanges knowledge, it "
                + "does not move it: " + copied.raw(), SEEDED, copied.sourceCrystals);

        // ---- 3) The console lists what the ship now knows. -------------------------------------
        // Reopened, because the address list is built when the screen is. The close is waited for as
        // the link it is — the server letting go of the container — rather than as a screen that
        // has gone blank, because a screen that only LOOKS closed re-opens on a stale list.
        long closeMark = events.mark();
        bot().closeScreen();
        events.await(closeMark, "container_closed", "closing the console must reach the SERVER: the"
                + " address list is rebuilt when the screen is, so a re-open over a container the"
                + " server still holds would list what the console knew before the copy", 60);
        openMachineGui(at);

        scenario().asserting("the console LISTS the addresses the ship now knows");
        JsonObject buttons = bot().reportButtons();
        int listed = countAddressButtons(buttons);
        assertEquals("the console must LIST the addresses the ship now knows — the pilot picks a "
                + "destination off this list, so a copy he cannot see is a copy he cannot use: "
                + buttons, SEEDED, listed);
        // NOT asserted here: the labels themselves. Every libVulpes module button is a
        // GuiImageButton built with an empty displayString and draws its caption itself, so the
        // harness's button report is structurally blind to it. What the list CONTAINS is pinned
        // below instead, by picking off it.

        // ---- 4) Pick one: the ship is aimed at THAT address. ------------------------------------
        scenario().asserting("picking the first listed address aims the ship at that address");
        long pickMark = events.markInstrumented();
        bot().clickButtonById(BUTTON_PICK_FIRST);
        events.assertChain(pickMark, "a PICK click must reach the console and the console must AIM:"
                        + " an index it cannot resolve is a silent no-op, and a target that stays"
                        + " null looks the same as a slow round trip", 150,
                "nav_command_received", "nav_target_picked");
        String picked = events.since(pickMark, "nav_target_picked");
        NavStatus aimed = NavStatus.of(exec("stellurgytest nav status " + where));
        String expected = FIRST_SECTOR + "_0_0";
        assertEquals("clicking the first listed address must aim the ship at THAT address — the "
                + "list's order is what the pilot picks by, so aiming at some other entry is the "
                + "same defect as not aiming at all: " + aimed.raw() + " aims=" + picked,
                expected, aimed.targetCell());

        // ---- 5) Arm, and stand down again. Both answered. ---------------------------------------
        scenario().asserting("arming a chosen destination is accepted, confirmed, and real");
        long armMark = events.markInstrumented();
        bot().clickButtonById(BUTTON_ARM);
        // The click reaches the console; the console then COMMITS. Two links stood between those —
        // `nav_console_told` matched on the message key, and the resolved line hunted in the
        // client's own chat — and both were about the ANSWER rather than about the act. What
        // replaces them is the act itself: production's own arm() verdict, in order after the
        // command that asked for it. The settled state is still read afterwards, because the
        // decision and the flag that survives it are two facts and a jump fires off the second.
        events.assertChain(armMark, "a ARM click on a chosen destination must reach the console and"
                        + " the console must COMMIT to it", 150,
                "nav_command_received", "nav_arm_decided");
        String arms = events.since(armMark, "nav_arm_decided");
        assertTrue("clicking ARM with a destination chosen must ARM the console, not refuse it: "
                        + arms, Events.countRecords(arms, "outcome", "ARMED") > 0);
        NavStatus armedStatus = NavStatus.of(exec("stellurgytest nav status " + where));
        assertTrue("arming a chosen destination must leave the console ARMED: " + armedStatus.raw(),
                armedStatus.armed);

        scenario().asserting("pressing the same button again stands the jump down, and says so");
        long disarmMark = events.markInstrumented();
        bot().clickButtonById(BUTTON_ARM);
        events.assertChain(disarmMark, "a second ARM click must reach the console and STAND IT"
                        + " DOWN", 150, "nav_command_received", "nav_arm_decided");
        String disarms = events.since(disarmMark, "nav_arm_decided");
        System.out.println("[nav-console] arm decisions: armed=" + Events.records(arms)
                + " disarmed=" + Events.records(disarms));
        // DISARMED and not "ARMED again": the record is taken on the EDGE, so a console that was
        // already unarmed produces nothing here at all — which is exactly the failure a state read
        // alone cannot see, because an unarmed console reads unarmed either way.
        assertTrue("the second click must be the console STANDING DOWN — a record of anything else"
                        + " means the first click did not leave it armed: " + disarms,
                Events.countRecords(disarms, "outcome", "DISARMED") > 0);
        NavStatus disarmedStatus = NavStatus.of(exec("stellurgytest nav status " + where));
        assertFalse("a disarmed console must not stay armed: " + disarmedStatus.raw(),
                disarmedStatus.armed);

        bot().closeScreen();
    }

    /** How many address-pick buttons the open console shows. */
    private static int countAddressButtons(JsonObject reportButtons) {
        JsonArray list = reportButtons.getAsJsonArray("buttons");
        int found = 0;
        for (JsonElement element : list) {
            JsonObject button = element.getAsJsonObject();
            if (button.get("id").getAsInt() >= BUTTON_PICK_FIRST
                    && button.get("visible").getAsBoolean()) {
                found++;
            }
        }
        return found;
    }

    /** Server-side clear + client-observed empty hand (a held stack can eat the right-click). */
    private void emptyTheHand() throws Exception {
        emptyTheHandOnClient("the bot's hand must be observably empty");
    }

    // ── inventory-bypass mixin ────────────────────────────────────────────────

    /**
     * Building the biome scanner's GUI off any space station does not throw on the client.
     *
     * <p>{@code TileBiomeScanner.getModules} runs on the client and asks
     * {@code SpaceObjectManager.getSpaceStationFromBlockCoords(pos)} for the orbiting planet; off a
     * station that lookup is null, and the deref crashed the client of a player opening the GUI. The
     * branch is reached only while {@code suitable} holds — the column below the scanner all air.
     * The overworld has no stations, so any overworld position is off-station.</p>
     *
     * <p>{@code getModules} is driven directly on the client's own tile through the bridge: the GUI
     * opens only on a complete multiblock, and the scanner's structure needs an aluminium-oredict
     * block only an external mod provides. What this does not see: the GUI being opened by a click.</p>
     *
     * <p>Everything the client must hold — the scanner and the cleared column — is changed BEFORE the
     * teleport, and each command is its own round trip, so those block changes were flushed to the
     * client on an earlier tick than the move; the client applying the move is the receipt for them.</p>
     */
    /**
     * A machine packet's address is the CLIENT's to write, so a press can name a weapon console its
     * sender is nowhere near. The server answers it by the console's own usability rule — the same
     * world, within a chest's reach — and a press from beyond it changes nothing; the same press from
     * beside the console still holds the battery's fire.
     *
     * <p>Both presses are forged by the real client ({@link ForgedMachinePress}) rather than clicked:
     * a far player has no screen to click, and a modified client does not need one. That is the
     * subject — what the SERVER does with a press it did not see a screen for. What this does not see:
     * the screen's own button, which {@code WeaponGuiButtonsReachTheServerE2ETest} drives, and a press
     * from another dimension (the forger can only address its own world).</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#canInteractWithContainer} at {@code <= CONTAINER_REACH_SQ}
     * answering {@code true} unconditionally (the shape it shipped with), this fails at "the console
     * judged a press from 24 blocks away as within reach: {...weapon_console_press_judged,
     * pos:4016,150,4016,player:ForgeTestClient,reachable:true}" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#useNetworkData} at {@code if (!canInteractWithContainer(player))}
     * keeping its log line but not its {@code return}, this fails at "a press from beyond reach held the
     * battery's fire anyway: {...network:true,...holdFire:true...}" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#canInteractWithContainer} at {@code <= CONTAINER_REACH_SQ}
     * answering {@code false} unconditionally, this fails at "the console refused a press from the
     * player standing on it: {...reachable:false}" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#useNetworkData} at {@code setHoldFire(!isHoldFire());}
     * removed, this fails at "a press from the player standing on the console did not hold fire:
     * {...holdFire:false...}" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#update} at {@code SubsystemNetworkManager.of(world).register(this);}
     * removed, this fails at the arrangement wait "the console never joined a weapon network ... no
     * `weapon_orders_seeded` carrying consoles = 4144,150,4016"; with {@code TileWeaponConsole#useNetworkData}
     * returning before {@code if (!canInteractWithContainer(player))}, at the far wait "the console never
     * judged the far press: it never reached the server" (2026-10-06).</p>
     *
     * <p>red-witnessed: NOT YET, with {@code TileWeaponConsole#useNetworkData} at
     * {@code if (!canInteractWithContainer(player))} the seam both press waits link on, for the NEAR
     * {@code weapon_console_press_judged} wait and the two
     * {@code awaitClientPlacedNear} waits, for the reasons given on
     * {@link #aFireControlSensorPressFromBeyondReachChangesNothing}: a break silencing the near press
     * silences the far one first, and the placement waits link on vanilla's teleport.</p>
     */
    @Test
    public void aWeaponConsolePressFromBeyondReachChangesNothing() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);
        String console = dim + " " + x + " " + Y + " " + z;
        Events events = serverEvents();

        scenario().arranging("a weapon console with a turret beside it, so it commands a network");
        warmupPlotChunks();
        String turret = exec("stellurgytest place " + dim + " " + (x + 1) + " " + Y + " " + z + " stellurgy:turret");
        scenario().requireArranged("the turret must place: " + turret, Reply.of(turret).bool("placed"));
        long joined = events.markInstrumented();
        String placed = exec("stellurgytest place " + console + " stellurgy:weaponConsole");
        scenario().requireArranged("the console must place: " + placed, Reply.of(placed).bool("placed"));
        events.awaitRecordWithFields(joined, "weapon_orders_seeded",
                "the console never joined a weapon network, so a press has nothing to change",
                Weapons.ARRANGEMENT_TICKS, "consoles", Weapons.at(x, Y, z));
        Reply before = Reply.of(exec("stellurgytest weaponconsole read " + console)).requireOk("read the console");
        scenario().requireArranged("the console must command a network that is not holding fire: " + before,
                before.bool("network") && !before.bool("holdFire"));

        // FAR: on a block of its own, down the plot — FAR_FROM_CONSOLE squared against the 64 a chest allows.
        int farZ = z + FAR_FROM_CONSOLE;
        exec("stellurgytest place " + dim + " " + x + " " + Y + " " + farZ + " minecraft:stone");
        long farStand = clientEvents().mark();
        exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (farZ + 0.5) + " 0 0");
        awaitClientPlacedNear(farStand, x + 0.5, farZ + 0.5, "the press must leave from where he now stands");
        double farSq = serverDistanceSqTo(x, Y, z);
        scenario().requireArranged("the far press must leave from beyond reach: squared distance " + farSq
                + " on the server, against " + VANILLA_CONTAINER_REACH_SQ, farSq > VANILLA_CONTAINER_REACH_SQ);

        scenario().asserting("a press from beyond reach is judged out of reach and changes nothing");
        long farPress = events.mark();
        ForgedMachinePress.send(bot(), x, Y, z, CONSOLE_TOGGLE_HOLD_FIRE);
        String farJudged = events.awaitRecordWithFields(farPress, "weapon_console_press_judged",
                "the console never judged the far press: it never reached the server", GUI_LINK_BUDGET_TICKS,
                "pos", Weapons.at(x, Y, z));
        assertEquals("the console judged a press from " + FAR_FROM_CONSOLE + " blocks away as within reach: "
                + farJudged, "false", Events.text(farJudged, "reachable"));
        Reply afterFar = Reply.of(exec("stellurgytest weaponconsole read " + console)).requireOk("read the console");
        scenario().requireArranged("the console lost its network during the far press: " + afterFar,
                afterFar.bool("network"));
        assertFalse("a press from beyond reach held the battery's fire anyway: " + afterFar,
                afterFar.bool("holdFire"));

        scenario().asserting("the same press from the player standing on the console holds fire");
        long nearStand = clientEvents().mark();
        exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (z + 0.5) + " 0 90");
        awaitClientPlacedNear(nearStand, x + 0.5, z + 0.5, "the press must leave from beside the console");
        double nearSq = serverDistanceSqTo(x, Y, z);
        scenario().requireArranged("the near press must leave from within reach: squared distance " + nearSq
                + " on the server, against " + VANILLA_CONTAINER_REACH_SQ, nearSq <= VANILLA_CONTAINER_REACH_SQ);
        long nearPress = events.mark();
        ForgedMachinePress.send(bot(), x, Y, z, CONSOLE_TOGGLE_HOLD_FIRE);
        String nearJudged = events.awaitRecordWithFields(nearPress, "weapon_console_press_judged",
                "the console never judged the near press: it never reached the server", GUI_LINK_BUDGET_TICKS,
                "pos", Weapons.at(x, Y, z));
        assertEquals("the console refused a press from the player standing on it: " + nearJudged,
                "true", Events.text(nearJudged, "reachable"));
        Reply afterNear = Reply.of(exec("stellurgytest weaponconsole read " + console)).requireOk("read the console");
        assertTrue("a press from the player standing on the console did not hold fire: " + afterNear,
                afterNear.bool("holdFire"));
    }

    /**
     * The fire-control sensor answers a press the way the weapon console does: by vanilla's usability
     * rule, so a press from beyond a chest's reach leaves its mode alone, and the same press from the
     * player standing on it switches it to illuminating. Both presses are forged by the real client
     * ({@link ForgedMachinePress}); the screen's own button is {@code WeaponGuiButtonsReachTheServerE2ETest}'s.
     *
     * <p>red-witnessed (2026-10-06, one inversion per run):
     * {@code TileFireControlSensor#canInteractWithContainer} at {@code <= CONTAINER_REACH_SQ} answering
     * true fails "the sensor judged a press from 24 blocks away as within reach"; answering false fails
     * "the sensor refused a press from the player standing on it";
     * {@code TileFireControlSensor#useNetworkData} at {@code if (!canInteractWithContainer(player))}
     * logging without its {@code return} fails "a press from beyond reach switched the sensor anyway
     * {...mode:ACTIVE...}"; the same method at {@code setMode(mode == SensorMode.ACTIVE ? SensorMode.PASSIVE : SensorMode.ACTIVE);}
     * removed fails "a press from the player standing on the sensor did not switch it {...mode:PASSIVE...}";
     * the same method returning before {@code if (!canInteractWithContainer(player))} fails at the far
     * wait, "the sensor never judged the far press: it never reached the server".</p>
     *
     * <p>red-witnessed: NOT YET, with {@code TileFireControlSensor#useNetworkData} at
     * {@code if (!canInteractWithContainer(player))} the seam both press waits link on, for the NEAR
     * {@code sensor_press_judged} wait and the two
     * {@code awaitClientPlacedNear} waits. Any production break that silences the near press silences
     * the far one first (one path, one seam), so it reds the far wait instead; the only break that
     * reaches the near wait alone is one conditional on distance — the reach decision itself, which the
     * near verdict after it pins. The placement waits link on vanilla's own position packet
     * ({@code client_pos_look_applied} after {@code /tp}); no Stellurgy code decides it, so there is
     * nothing of ours to break.</p>
     */
    @Test
    public void aFireControlSensorPressFromBeyondReachChangesNothing() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);
        String sensor = dim + " " + x + " " + Y + " " + z;
        Events events = serverEvents();

        scenario().arranging("a fire-control sensor, listening");
        warmupPlotChunks();
        String placed = exec("stellurgytest place " + sensor + " stellurgy:fireControlSensor");
        scenario().requireArranged("the sensor must place: " + placed, Reply.of(placed).bool("placed"));
        Reply before = Reply.of(exec("stellurgytest sensor read " + sensor)).requireOk("read the sensor");
        scenario().requireArranged("a fresh sensor must be listening, or switching it proves nothing: " + before,
                "PASSIVE".equals(before.text("mode")));

        int farZ = z + FAR_FROM_CONSOLE;
        exec("stellurgytest place " + dim + " " + x + " " + Y + " " + farZ + " minecraft:stone");
        long farStand = clientEvents().mark();
        exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (farZ + 0.5) + " 0 0");
        awaitClientPlacedNear(farStand, x + 0.5, farZ + 0.5, "the press must leave from where he now stands");
        double farSq = serverDistanceSqTo(x, Y, z);
        scenario().requireArranged("the far press must leave from beyond reach: squared distance " + farSq
                + " on the server, against " + VANILLA_CONTAINER_REACH_SQ, farSq > VANILLA_CONTAINER_REACH_SQ);

        scenario().asserting("a press from beyond reach is judged out of reach and leaves the mode alone");
        long farPress = events.mark();
        ForgedMachinePress.send(bot(), x, Y, z, SENSOR_TOGGLE_MODE);
        String farJudged = events.awaitRecordWithFields(farPress, "sensor_press_judged",
                "the sensor never judged the far press: it never reached the server", GUI_LINK_BUDGET_TICKS,
                "pos", Weapons.at(x, Y, z));
        assertEquals("the sensor judged a press from " + FAR_FROM_CONSOLE + " blocks away as within reach: "
                + farJudged, "false", Events.text(farJudged, "reachable"));
        Reply afterFar = Reply.of(exec("stellurgytest sensor read " + sensor)).requireOk("read the sensor");
        assertEquals("a press from beyond reach switched the sensor anyway: " + afterFar,
                "PASSIVE", afterFar.text("mode"));

        scenario().asserting("the same press from the player standing on the sensor switches it");
        long nearStand = clientEvents().mark();
        exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (z + 0.5) + " 0 90");
        awaitClientPlacedNear(nearStand, x + 0.5, z + 0.5, "the press must leave from beside the sensor");
        double nearSq = serverDistanceSqTo(x, Y, z);
        scenario().requireArranged("the near press must leave from within reach: squared distance " + nearSq
                + " on the server, against " + VANILLA_CONTAINER_REACH_SQ, nearSq <= VANILLA_CONTAINER_REACH_SQ);
        long nearPress = events.mark();
        ForgedMachinePress.send(bot(), x, Y, z, SENSOR_TOGGLE_MODE);
        String nearJudged = events.awaitRecordWithFields(nearPress, "sensor_press_judged",
                "the sensor never judged the near press: it never reached the server", GUI_LINK_BUDGET_TICKS,
                "pos", Weapons.at(x, Y, z));
        assertEquals("the sensor refused a press from the player standing on it: " + nearJudged,
                "true", Events.text(nearJudged, "reachable"));
        Reply afterNear = Reply.of(exec("stellurgytest sensor read " + sensor)).requireOk("read the sensor");
        assertEquals("a press from the player standing on the sensor did not switch it: " + afterNear,
                "ACTIVE", afterNear.text("mode"));
    }

    /**
     * The player's squared distance to the centre of the block at (x, y, z), from the position the
     * SERVER holds for him ({@code player position-of}) — the quantity a usability check compares, at
     * the side that compares it.
     */
    private double serverDistanceSqTo(int x, int y, int z) throws Exception {
        Reply at = Reply.of(exec("stellurgytest player position-of " + PlayerState.botName(this::exec)))
                .requireOk("read the player's position on the server");
        double dx = at.number("playerPosX") - (x + 0.5D);
        double dy = at.number("playerPosY") - (y + 0.5D);
        double dz = at.number("playerPosZ") - (z + 0.5D);
        double sq = dx * dx + dy * dy + dz * dz;
        System.out.println("[reach] server position " + at + " -> squared distance " + sq + " to " + x + "," + y + "," + z);
        return sq;
    }

    @Test
    public void buildingScannerGuiOffStationDoesNotThrowOnClient() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("place a biome scanner with an all-air column below it at " + x + "," + Y + "," + z);
        warmupPlotChunks();
        String place = exec("stellurgytest place " + dim + " " + x + " " + Y + " " + z + " stellurgy:biomeScanner");
        scenario().requireArranged("scanner must place: " + place, Reply.of(place).bool("placed"));
        exec("fill " + x + " 1 " + z + " " + x + " " + (Y - 1) + " " + z + " minecraft:air");

        long standMark = clientEvents().mark();
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5) + " 0 90");
        awaitClientPlacedNear(standMark, x + 0.5, z + 0.5,
                "the scanner and its cleared column reach the client before the move does");

        scenario().asserting("building the scanner's GUI off-station does not throw on the client");
        JsonObject res = bot().tileModulesThrows(x, Y, z);
        assertFalse("building the biome-scanner GUI off-station must not throw on the client"
                        + " (getModules must null-guard the absent space station): " + res,
                res.get("threw").getAsBoolean());
    }

    /**
     * From {@code InventoryBypassRedirectE2ETest}. Live end-to-end pin for the
     * {@code MixinEntityPlayer(MP)InventoryAccess} {@code @Redirect}.
     *
     * <p>The unit-level pin ({@code testUnit.RocketInventoryHelperRedirectTest}) covers the
     * boolean-logic surface of {@code RocketInventoryHelper.shouldAllowContainerInteract}, but it
     * cannot prove the mixin's {@code @Redirect} actually intercepts vanilla's
     * {@code Container.canInteractWith} call inside {@code EntityPlayerMP.onUpdate}. That needs a
     * live {@code EntityPlayer} with an open container GUI, ticked by the dedicated server's normal
     * loop.</p>
     *
     * <p>Two phases: bypass ON — teleport far past vanilla's 8-block reach, GUI must stay open;
     * bypass OFF — the GUI must close on the next tick. A vanilla chest is the container so the
     * redirect target is the exact vanilla signature the mixin pins.</p>
     * Pins INV-HLP-02 (a bypass player keeps a container open regardless of distance).
     */
    @Test
    public void mixinRedirectKeepsContainerOpenAcrossDistance() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("place a vanilla chest and stand on it");
        warmupPlotChunks();
        exec("stellurgytest player inv-bypass remove");
        String place = exec("stellurgytest place " + dim + " " + x + " " + Y + " " + z + " minecraft:chest");
        scenario().requireArranged("chest place must succeed: " + place,
                Reply.of(place).bool("placed"));
        long standMark = clientEvents().mark();
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5) + " 0 90");
        awaitClientPlacedNear(standMark, x + 0.5, z + 0.5,
                "the container below is opened for a player standing at the chest, and the GUI it"
                        + " produces is rendered by the client that got there");

        // Opened SERVER-side (mirrors BlockChest.onBlockActivated -> player.displayGUIChest) rather
        // than by right-click: the right-click packet was dropped before the chunk/player settled
        // in the original class, a settle-timing race orthogonal to the mixin contract under test.
        // The S2C open-window packet makes the real client render GuiChest.
        Events events = serverEvents();
        long openMark = events.mark();
        long openOnClient = clientEvents().mark();
        String open = exec("stellurgytest player open-chest " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("server-side open-chest must succeed: " + open,
                Reply.of(open).ok());
        events.await(openMark, "container_opened", "the server must actually OPEN a container: with"
                + " none open there is nothing for vanilla's distance check to close and both legs"
                + " below would be measuring an empty screen", 100);
        String displayed = awaitClientRecords(openOnClient, "client_gui_opened",
                "gui", "GuiChest", 200);
        scenario().requireArranged("the real client must DISPLAY the chest GUI — this scenario is"
                + " about a screen surviving a distance, so a screen that never arrived is an"
                + " arrangement failure, not a verdict on the redirect. openResp=" + open
                + " screensDisplayed=" + displayed, Events.anyRecordHas(displayed, "gui", "GuiChest"));

        scenario().asserting("with the bypass on, the GUI survives a 200-block teleport");
        String addResp = exec("stellurgytest player inv-bypass add");
        scenario().requireArranged("inv-bypass add must report inBypass:true: " + addResp,
                Reply.of(addResp).bool("inBypass"));

        long farMark = events.markInstrumented();
        long farClientMark = clientEvents().mark();
        exec("tp @a " + (x + 200) + " " + (Y + 1) + " " + (z + 200) + " 0 0");
        // The claim is that the GUI SURVIVES this teleport, so the teleport has to have reached the
        // client before its screen is read — forty ticks were a bet on that, and on a loaded box the
        // bet decides the verdict.
        awaitClientPlacedNear(farClientMark, x + 200, z + 200,
                "the 200-block teleport must have reached the client before its screen is read");

        JsonObject afterTpWithBypass = bot().reportState();
        // Diagnostic: re-check bypass status post-teleport so a failure can distinguish "bypass
        // dropped from the set" from "the mixin redirect didn't fire". The bypass map uses
        // WeakReferences.
        String statusAfterTp = exec("stellurgytest player inv-bypass status");
        // This half is an ABSENCE — "the server did not close it" — so the instrument has to prove
        // it was listening, or the silence says nothing. The redirect's observation point runs on
        // EVERY EntityPlayerMP.onUpdate tick whether or not its answer changed, so its presence in
        // `instruments` is what makes the missing `container_closed` a statement about the DECISION.
        // (The record itself is edge-only, by design: an answer that does not change says nothing,
        // so there is deliberately no record to count here — only the absence of a close.)
        String sinceFar = events.since(farMark);
        Events.assertInstrumentRan(sinceFar, "container_interact_events",
                "vanilla's reach check was consulted at all while the player stood 200 blocks away");
        assertEquals("with inv-bypass active the server must not CLOSE the container: a close here"
                        + " is the redirect having failed to answer true. events since the teleport: "
                        + sinceFar, 0,
                Events.countRecords(events.since(farMark, "container_closed"),
                        "type", "container_closed"));
        assertEquals("with inv-bypass active, the chest GUI must remain open across a 200-block "
                + "teleport (the mixin redirect should force canInteractWith -> true on every "
                + "EntityPlayerMP.onUpdate tick); reportState=" + afterTpWithBypass
                + " bypassStatus=" + statusAfterTp,
                GUI_CHEST, screenOf(afterTpWithBypass));

        scenario().asserting("with the bypass off, vanilla's distance check closes it");
        long closeMark = events.markInstrumented();
        long closeOnClient = clientEvents().mark();
        String removeResp = exec("stellurgytest player inv-bypass remove");
        scenario().requireArranged("inv-bypass remove must report inBypass:false: " + removeResp,
                (!Reply.of(removeResp).bool("inBypass")));

        // The close is a chain, and its ORDER is one server call stack: the redirect answers, and
        // vanilla's own `if` closes the screen on that answer. Asserting it as a chain is what
        // distinguishes "the redirect said no and the close followed" from "something else closed
        // the chest", which an empty screen cannot.
        events.assertChain(closeMark, "removing the bypass must let vanilla's reach check REFUSE the"
                        + " interaction, and that refusal must be what closes the container", 200,
                "container_interact_checked", "container_closed");
        String checks = events.since(closeMark, "container_interact_checked");
        assertTrue("the reach check must have come back FALSE — a close for any other reason pins"
                + " nothing about the redirect: " + checks,
                Events.anyRecordHas(checks, "allowed", "false"));
        // The wait IS the assertion now: it fails carrying every screen the client recorded, which
        // is what the `assertTrue` below it used to print after re-checking the wait's own exit
        // condition.
        awaitClientRecords(closeOnClient, "client_gui_opened", "gui", "none", 200);
        assertEquals("after removing inv-bypass, vanilla's distance check must close the chest "
                + "GUI; final screen=" + screenOf(bot().reportState()), "",
                screenOf(bot().reportState()));
    }
}
