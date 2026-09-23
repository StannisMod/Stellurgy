package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.NavStatus;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;

import java.util.Locale;

import zmaster587.advancedRocketry.test.Plot;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.TelescopeReading;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.findSlotWithItem;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.screenOf;

/**
 * A machine stands in the world, the player right-clicks it open, and drives it with nothing but
 * clicks. Seven scenarios, one client.
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
 *       {@link #clickingScanThenBuildAssemblesRocket()} reads {@code artest rocket list}, which is
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
public class MachineGuiClientGroupE2ETest extends AbstractSharedClientE2ETest {

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

    private static final String GUI_MODULAR = "zmaster587.libVulpes.inventory.GuiModular";
    private static final String GUI_CHEST = "net.minecraft.client.gui.inventory.GuiChest";
    private static final String CHIP = "advancedrocketry:planetidchip";

    /** {@code zmaster587.advancedRocketry.api.Constants.STAR_ID_OFFSET}. */
    private static final int STAR_ID_OFFSET = 10000;


    // Navigation console button ids — the console's own module ids, which libVulpes puts straight
    // on the GuiButton.
    private static final int BUTTON_COPY = 0;
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_PICK_FIRST = 10;
    /** Addresses the probe seeds into the brought crystal: sectors 100..102, named {@code probe-N}. */
    private static final int SEEDED = 3;
    private static final int FIRST_SECTOR = 100;

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
                exec("artest chunk forceload " + dim + " " + cx + " " + cz);
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
        String place = exec("artest place " + dim + " " + x + " " + Y + " " + z + " " + blockId);
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
     * <p><b>Silent about {@code gui_container_served}</b>, on purpose: every machine in this class
     * opens its GUI on {@code LibVulpes.instance} (libVulpes' own {@code BlockTile} /
     * {@code BlockMultiblockMachine} do the {@code openGui}), so AR's gui handler is never asked and
     * never records. The server link available here is {@code container_opened}, which Forge posts
     * only once SOME handler has answered with a container — a request a handler refused is an
     * ABSENCE of that record beside a present {@code right_click_block}, which is exactly the
     * distinction the old screen poll could not make.</p>
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
        Events events = events();
        long serverMark = events.mark();
        long clientMark = clientEvents().mark();

        // The CLICK is a stimulus and the screen is a LINK, so they go into the pair built for it:
        // `awaitMatching(…, stimulus)` re-clicks between reads until the client records a GUI of its
        // own. What stood here was a retry loop around a wait that returned its last reading either
        // way, which meant the six attempts were really six budgets spent in series with no verdict
        // between them. The failure stays an ARRANGEMENT one and keeps its whole diagnostic.
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
     * {@code /artest fixture rocket}, opens the assembler's GUI by right-click, and drives the real
     * two-button assembly flow entirely through the client GUI: {@code clickButtonById(0)} (Scan),
     * then {@code clickButtonById(1)} (Build) pressed on a poll —
     * {@code TileRocketAssemblingMachine.useNetworkData} ignores a Build press while
     * {@code isScanning()}, so it simply "takes" once the scan pass finishes.
     *
     * <p>Unlike the headless {@code server/RocketAssemblySmokeTest} — which calls
     * {@code scanRocket}/{@code assembleRocket} directly — this exercises the machine's real
     * energy-gated {@code performFunction} tick loop, so the builder is kept powered via
     * {@code /artest energy inject}.</p>
     */
    @Test
    public void clickingScanThenBuildAssemblesRocket() throws Exception {
        int dim = plot().dim;
        int baseX = plot().x(8);
        int baseZ = plot().z(8);

        scenario().arranging("build the rocket fixture on a platform inside the plot");
        warmupPlotChunks();
        // The pad is built in open air on ground of the scenario's own making, so no world seed can
        // put a hill under it — the failure mode FreeFlightModeE2ETest carries a pre-clear for.
        String footing = exec("artest fill " + dim + " " + baseX + " " + (Y - 1) + " " + baseZ
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
                .describeOnFailureWith("artest rocket list " + dim);

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
        Events events = events();
        long buildMark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        exec("artest energy inject " + builder + " 100000000");
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
        String list = exec("artest rocket list " + dim);
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
     * <p>{@code artest rocket list} is a GLOBAL query. Taking "the one that is there" is correct
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
        String fire = exec("artest infra railgun-fire " + dim + " " + sx + " " + Y + " " + sz
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
        String fire = exec("artest infra railgun-fire " + dim + " " + sx + " " + Y + " " + sz
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
        String fixture = exec("artest fixture multiblock railgun " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("fixture multiblock railgun failed at " + x + "," + Y + "," + z
                + ": " + fixture, Reply.of(fixture).ok());
        String tryComplete = exec("artest machine try-complete " + dim + " " + x + " " + Y + " " + z);
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
     */
    @Test
    public void shiftClickingChipMovesItIntoTheGuidanceComputer() throws Exception {
        int[] at = placeMachineAndStandOnIt("advancedrocketry:guidanceComputer");
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
        String machineInventory = exec("artest hatch read " + plot().dim + " " + at[0] + " " + at[1]
                + " " + at[2]);
        // The SERVER must have moved the chip into the guidance computer — a positive claim, so the
        // reply's refusing reader: it fails naming the machine's whole inventory if the chip is not
        // one of its slots.
        Reply.of("artest hatch read " + at[0] + " " + at[1] + " " + at[2], machineInventory)
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
        String fixture = exec("artest fixture multiblock observatory " + dim + " " + x + " " + Y
                + " " + z);
        scenario().requireArranged("fixture multiblock observatory failed: " + fixture,
                Reply.of(fixture).ok());

        String completed = "";
        // STAYS A LOOP because its ITERATIONS are the stimulus: each one re-issues `try-complete`,
        // which is production being ASKED to validate the multiblock, and what makes the ask
        // succeed is the next ask rather than more patience on the last one. A link would have to
        // be a record of the validation succeeding, and the same re-ask would still be what
        // produced it. What this cannot see: which of the eight asks was the one that took.
        for (int attempt = 0; attempt < 8; attempt++) {
            completed = exec("artest machine try-complete " + dim + " " + x + " " + Y + " " + z);
            // absence is the answer: this is the wait, and "the flag is not there yet" is
            // the state it exists to sit through.
            if (Reply.of(completed).boolOr("isComplete", false)) {
                break;
            }
            bot().waitTicks(10);
        }
        scenario().requireArranged("the observatory structure never validated: " + completed,
                Reply.of(completed).bool("isComplete"));

        // Stand on the structure's open face, one block from the controller, looking at it. The
        // footing is not decoration: the fixture clears its own footprint to air, and a player
        // teleported into that air FALLS — measured, and it reads exactly like a dead click,
        // because the server silently drops an interaction from out of reach.
        StringBuilder footing = new StringBuilder();
        for (int dx = -1; dx <= 1; dx++) {
            footing.append(exec("artest place " + dim + " " + (x + dx) + " " + Y + " " + (z - 1)
                    + " minecraft:stone")).append(' ');
        }
        // Stand in the MIDDLE of the footing block, not at its edge: the block at z-1 spans
        // [z-1, z), so z-1.5 is half a block beyond it and over open air.
        //
        // Re-issued rather than waited out: a single teleport followed by a fixed wait puts the
        // player on a footing his own client has not received yet, and he falls through it — the
        // same fall, to the same fraction of a block, every time. Standing still is a convergence,
        // so it is polled.
        // The teleport is the STIMULUS and `client_pos_look_applied` is the link: the wait ends
        // when the CLIENT has applied a server position write, which is the thing the fixed wait
        // was standing in for. The re-issue stays because a click that lands before the client has
        // the footing drops him through it, and no amount of reading recovers that — so it is
        // re-sent every 20 ticks while the log is read every 5.
        long placedMark = clientEvents().mark();
        try {
            clientEvents().awaitMatching(placedMark, "client_pos_look_applied",
                    reply -> !Events.records(reply).isEmpty(), "applying a server position write",
                    "the player must be put on the footing beside the machine", 120,
                    () -> exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (z - 0.5) + " 0 0"), 20);
        } catch (AssertionError never) {
            scenario().arrangementFailed("the client never applied the teleport onto the footing: "
                    + never.getMessage());
        }
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
        exec("artest config set planetsMustBeDiscovered false");
        exec("artest config set telescopeScanBaseTicks 0");
        exec("artest config set telescopeLimitingMagnitude 30");
        exec("artest config set telescopeConeHalfAngleDegrees 20");
        String crystal = exec("artest telescope crystal " + where);
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
                .describeOnFailureWith("artest telescope info " + where);
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
        String system = exec("artest telescope system "
                + (home[0] + aimDistance * stepCells + 13L)
                + " " + home[1] + " " + home[2]);
        scenario().requireArranged("could not place a system to be found: " + system,
                Reply.of(system).ok());

        // Observe. The survey is a chain and is asserted as one: the aim ACCEPTED the region (it can
        // refuse — no origin, or a region it will not look at, and production only logs that), and
        // then the survey step actually MOVED (a completion pass that finds no crystal, no cell due
        // or too little distance data returns changing nothing, and says nothing). A red used to be
        // 400 ticks of telescope JSON that could not tell those apart.
        Events events = events();
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
                MachineGuiClientGroupE2ETest::anyDiscoveryResolved,
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
     * {@code /artest selector info} probe then confirms — the whole client&rarr;server selection
     * round-trip rather than just "the GUI opened".
     */
    @Test
    public void selectingPlanetUpdatesServerSelection() throws Exception {
        int[] at = placeMachineAndStandOnIt("advancedrocketry:planetSelector");

        scenario().arranging("open the planet selector's GUI");
        String screen = openMachineGui(at);
        scenario().record("screen", screen)
                .describeOnFailureWith("artest selector info " + plot().dim + " " + at[0] + " "
                        + at[1] + " " + at[2]);

        scenario().measuring("pick a planet button by id range (control buttons sit outside it)");
        JsonObject buttons = bot().reportButtons();
        int planetId = ClientGuiTestSupport.findButtonId(buttons, 0, STAR_ID_OFFSET);
        scenario().requireArranged("no clickable planet button in selector GUI: " + buttons,
                planetId != Integer.MIN_VALUE);
        scenario().record("planetButtonId", planetId);

        scenario().asserting("clicking it registers the selection server-side");
        long selectMark = events().markInstrumented();
        bot().clickButtonById(planetId);
        events().awaitField(selectMark, "selector_selection_set", "pos",
                at[0] + "," + at[1] + "," + at[2],
                "clicking planet button " + planetId + " must reach THIS selector's server copy",
                GUI_LINK_BUDGET_TICKS);

        String selectorInfo = exec("artest selector info " + plot().dim + " " + at[0] + " " + at[1]
                + " " + at[2]);
        assertTrue("clicking planet button " + planetId
                + " did not register a selection server-side: " + selectorInfo,
                Reply.of(selectorInfo).bool("hasSelection"));
        assertTrue("selection did not resolve to a planet: " + selectorInfo,
                Reply.of(selectorInfo).has("selectedDim"));

        bot().closeScreen();
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
        int[] at = placeMachineAndStandOnIt("advancedrocketry:navigationComputer");
        int navX = at[0], navY = at[1], navZ = at[2];
        String where = dim + " " + navX + " " + navY + " " + navZ;
        scenario().describeOnFailureWith("artest nav status " + where,
                "artest nav modules " + where);

        scenario().arranging("seed the brought crystal and give the ship a blank one to copy into");
        String seed = exec("artest nav crystal " + where + " 0 " + SEEDED + " " + FIRST_SECTOR);
        scenario().requireArranged("the source slot must hold a crystal carrying " + SEEDED
                + " addresses: " + seed, String.valueOf(SEEDED).equals(Reply.of(seed).text("addresses")));
        // The ship's own crystal is the DESTINATION, and the copy is add-only into it: with that
        // slot empty there is nowhere to copy to and the button is a silent no-op.
        String shipCrystal = exec("artest nav crystal " + where + " 1 0");
        scenario().requireArranged("the ship slot must hold a (blank) crystal to copy INTO: "
                + shipCrystal, (Reply.of(shipCrystal).integer("addresses") == 0));
        NavStatus before = NavStatus.of(exec("artest nav status " + where));
        scenario().requireArranged("ARRANGEMENT CONTROL: the ship's own crystal must start EMPTY, "
                + "or the copy leg below cannot tell a successful copy from a pre-loaded console: "
                + before.raw(), before.shipCrystals == 0);

        emptyTheHand();
        String screen = openMachineGui(at);
        scenario().record("screen", screen);
        Events events = events();

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
        events.assertChain(refusalMark, "an ARM click with nowhere to go must REACH the console and"
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
        NavStatus afterRefusal = NavStatus.of(exec("artest nav status " + where));
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
        NavStatus copied = NavStatus.of(exec("artest nav status " + where));
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
        NavStatus aimed = NavStatus.of(exec("artest nav status " + where));
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
        events.assertChain(armMark, "an ARM click on a chosen destination must reach the console and"
                        + " the console must COMMIT to it", 150,
                "nav_command_received", "nav_arm_decided");
        String arms = events.since(armMark, "nav_arm_decided");
        assertTrue("clicking ARM with a destination chosen must ARM the console, not refuse it: "
                        + arms, Events.countRecords(arms, "outcome", "ARMED") > 0);
        NavStatus armedStatus = NavStatus.of(exec("artest nav status " + where));
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
        NavStatus disarmedStatus = NavStatus.of(exec("artest nav status " + where));
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
     */
    @Test
    public void mixinRedirectKeepsContainerOpenAcrossDistance() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("place a vanilla chest and stand on it");
        warmupPlotChunks();
        exec("artest player inv-bypass remove");
        String place = exec("artest place " + dim + " " + x + " " + Y + " " + z + " minecraft:chest");
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
        Events events = events();
        long openMark = events.mark();
        long openOnClient = clientEvents().mark();
        String open = exec("artest player open-chest " + dim + " " + x + " " + Y + " " + z);
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
        String addResp = exec("artest player inv-bypass add");
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
        String statusAfterTp = exec("artest player inv-bypass status");
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
        String removeResp = exec("artest player inv-bypass remove");
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
