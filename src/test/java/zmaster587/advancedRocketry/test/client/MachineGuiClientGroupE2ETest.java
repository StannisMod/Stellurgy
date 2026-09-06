package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.Events;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li><b>Chat while a GUI is open.</b> {@link #thePilotCopiesPicksAndArmsAtTheConsoleWithNothingButClicks()}
 *       reads the console's replies with the console still open — the full client reset would close
 *       the very screen it is about to click. It no longer CLEARS the overlay first: a reply is read
 *       off the client's own {@code client_chat_received} records taken from a mark that predates the
 *       click, so a line from an earlier scenario cannot be mistaken for this one and there is
 *       nothing to drain.</li>
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

    private static final int Y = Plot.DEFAULT_Y;

    /** Where a scenario's machine stands inside its plot, and where the player stands to reach it. */
    private static final int MACHINE_DX = 16;
    private static final int MACHINE_DZ = 16;

    private static final String GUI_MODULAR = "zmaster587.libVulpes.inventory.GuiModular";
    private static final String GUI_CHEST = "net.minecraft.client.gui.inventory.GuiChest";
    private static final String CHIP = "advancedrocketry:planetidchip";

    /** {@code zmaster587.advancedRocketry.api.Constants.STAR_ID_OFFSET}. */
    private static final int STAR_ID_OFFSET = 10000;

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    /** One {@code rocket list} entry: id plus the x/y/z it stands at. */
    private static final Pattern ROCKET_ENTRY = Pattern.compile(
            "\\{\"id\":(-?\\d+),\"uuid\":\"[^\"]*\",\"dim\":-?\\d+,"
                    + "\"pos\":\\[(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)]}");

    // Navigation console button ids — the console's own module ids, which libVulpes puts straight
    // on the GuiButton.
    private static final int BUTTON_COPY = 0;
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_PICK_FIRST = 10;
    /** Addresses the probe seeds into the brought crystal: sectors 100..102, named {@code probe-N}. */
    private static final int SEEDED = 3;
    private static final int FIRST_SECTOR = 100;

    private static final Pattern SHIP_COUNT = Pattern.compile("\"ship\":(\\d+)");
    private static final Pattern SOURCE_COUNT = Pattern.compile("\"source\":(\\d+)");
    private static final Pattern TARGET = Pattern.compile("\"target\":(null|\"[^\"]*\")");
    private static final Pattern ARMED = Pattern.compile("\"armed\":(true|false)");

    // Observatory region-scan probe fields.
    private static final Pattern TELESCOPE_ORIGIN = Pattern.compile("\"origin\":\"([^\"]*)\"");
    private static final Pattern TELESCOPE_AIM_DISTANCE = Pattern.compile("\"aimDistance\":(\\d+)");
    /** What one step of the aim is worth in cells — the aim is counted in star territories. */
    private static final Pattern TELESCOPE_STEP_CELLS = Pattern.compile("\"stepCells\":(\\d+)");
    private static final Pattern TELESCOPE_ADDRESSES = Pattern.compile("\"addresses\":(-?\\d+)");

    // Railgun probe fields.
    private static final Pattern FIRED = Pattern.compile("\"fired\":(true|false)");
    private static final Pattern DEST_MATCHED = Pattern.compile("\"destMatched\":(\\d+)");
    private static final Pattern SRC_REMAINING = Pattern.compile("\"srcInputRemaining\":(\\d+)");
    private static final Pattern FIRE_STATUS = Pattern.compile("\"fireStatus\":\"([A-Z_]+)\"");
    private static final Pattern DEST_LOADED = Pattern.compile("\"destLoaded\":(true|false)");

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
        scenario().requireArranged("could not place " + blockId + ": " + place,
                place.contains("\"placed\":true") || place.contains("\"ok\":true"));

        // Stand ON the machine's own column, one block up, looking down at its top face. The
        // source classes all used exactly this pose; in open air it needs no terrain at all.
        //
        // ARRANGEMENT settle, not a link: the teleport is a server write and this is the time the
        // client is given to catch up with it. Deliberately NOT gated on `chunk_data_applied` —
        // this class shares one world across seven scenarios and force-loads its plot, so a chunk
        // the client already holds sends nothing and a wait for it would never return.
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5) + " 0 90");
        bot().waitTicks(40);
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
    private String openMachineGui(int[] at) throws Exception {
        Events events = events();
        long serverMark = events.mark();
        long clientMark = clientEvents().mark();

        String displayed = "";
        for (int attempt = 0; attempt < 6 && !displayed.contains("\"gui\":\"Gui"); attempt++) {
            bot().rightClickBlock(at[0], at[1], at[2], EnumFacing.UP, EnumHand.MAIN_HAND);
            displayed = awaitClientRecords(clientMark, "client_gui_opened", "\"gui\":\"Gui", 60);
        }

        String screen = screenOf(bot().reportState());
        if (!screen.startsWith(GUI_MODULAR)) {
            JsonObject direct = bot().interactBlock(at[0], at[1], at[2]);
            bot().waitTicks(20);
            scenario().arrangementFailed("right-clicking the machine must open its GUI, and the"
                    + " chain says WHERE it stopped rather than that the screen was empty."
                    + " screen=\"" + screen + "\""
                    + " clicksThatReachedTheServer=" + events.since(serverMark, "right_click_block")
                    + " containersTheServerOpened=" + events.since(serverMark, "container_opened")
                    + " screensTheClientDisplayed=" + displayed
                    + " afterDirectClick=\"" + screenOf(bot().reportState())
                    + "\" clickResult=" + direct
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
     * <p>Returns the last reply either way, so a caller's failure prints what the client DID record
     * instead of one stale sample. Local to this class: the shared base offers {@link Events} over
     * the server probe only, and the client log is reached through the bot.</p>
     */
    private String awaitClientRecords(long mark, String type, String needle, int tickBudget)
            throws Exception {
        String wanted = needle.toLowerCase(Locale.ROOT);
        String reply = String.valueOf(bot().eventsSince(mark, type));
        for (int waited = 0; waited < tickBudget
                && !reply.toLowerCase(Locale.ROOT).contains(wanted); waited += 5) {
            bot().waitTicks(5);
            reply = String.valueOf(bot().eventsSince(mark, type));
        }
        return reply;
    }

    private static int readInt(String json, Pattern p) {
        return Integer.parseInt(readGroup(json, p));
    }

    private static boolean readBoolean(String json, Pattern p) {
        return Boolean.parseBoolean(readGroup(json, p));
    }

    private static String readGroup(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected " + p.pattern() + " in: " + json, m.find());
        return m.group(1);
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
                footing.contains("\"ok\":true"));

        String fixture = exec("artest fixture rocket " + dim + " " + baseX + " " + Y + " " + baseZ);
        scenario().requireArranged("fixture rocket failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        scenario().requireArranged("fixture response missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));
        String builder = dim + " " + bx + " " + by + " " + bz;
        scenario().record("builderPos", bx + "," + by + "," + bz)
                .describeOnFailureWith("artest rocket list " + dim);

        scenario().arranging("stand on the launchpad within reach of the builder");
        exec("tp @a " + (baseX + 2.5) + " " + (Y + 1) + " " + (baseZ + 2.5) + " 0 0");
        bot().waitTicks(40);

        String screen = openMachineGui(new int[]{bx, by, bz});
        scenario().requireArranged("expected the assembler GUI to open, got: " + screen,
                screen.startsWith(GUI_MODULAR));

        scenario().asserting("Scan then Build, clicked on the real GUI, assemble a rocket");
        Events events = events();
        long buildMark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        exec("artest energy inject " + builder + " 100000000");
        bot().clickButtonById(0);

        // Build is RE-PRESSED, and that stays a stimulus: production ignores a Build press while
        // isScanning() and says nothing about it, so the press only "takes" once the scan pass has
        // finished.
        //
        // What the loop WAITS ON is the rocket itself, standing in this scenario's own plot. The
        // events are what the FAILURE is made of, not what it is measured by, and the reason is a
        // property of the recorder rather than a preference: `rocket_assembled` is taken at
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
        String assemblies = "";
        String list = "";
        int rocketId = -1;
        for (int waited = 0; waited < 3600 && rocketId < 0; waited += 40) {
            exec("artest energy inject " + builder + " 100000000");
            bot().clickButtonById(1);
            bot().waitTicks(40);
            assemblies = events.since(buildMark, "rocket_assembled");
            list = exec("artest rocket list " + dim);
            rocketId = rocketIdInThisPlot(list);
        }
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
        String joined = awaitClientRecords(clientMark, "entity_joined_world", "\"cls\":\"EntityRocket\"",
                200);
        assertTrue("the assembled rocket must arrive in the CLIENT's world - a rocket only the"
                        + " server knows about is not one the player can board. Entities the client"
                        + " saw join since the first click: " + joined,
                joined.contains("\"cls\":\"EntityRocket\""));

        bot().closeScreen();
    }

    /**
     * The id of a rocket standing in THIS scenario's plot, or -1.
     *
     * <p>{@code artest rocket list} is a GLOBAL query. Taking "the one that is there" is correct
     * only while the world holds exactly one rocket, which is precisely what a shared world stops
     * guaranteeing.</p>
     */
    private int rocketIdInThisPlot(String list) {
        Matcher entry = ROCKET_ENTRY.matcher(list);
        while (entry.find()) {
            double px = Double.parseDouble(entry.group(2));
            double pz = Double.parseDouble(entry.group(4));
            if (plot().contains(px, pz)) {
                return Integer.parseInt(entry.group(1));
            }
        }
        return -1;
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
                fire.contains("\"ok\":true"));

        assertTrue("railgun MUST fire to a linked railgun in the same dimension with a client "
                + "connected (issue #61 baseline); fire=" + fire,
                "true".equals(readGroup(fire, FIRED)));
        assertTrue("status must read FIRED after a successful shot; fire=" + fire,
                "FIRED".equals(readGroup(fire, FIRE_STATUS)));
        assertTrue("destination output port must contain >= 16 cobblestone after firing; fire="
                + fire, readInt(fire, DEST_MATCHED) >= 16);
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
                fire.contains("\"ok\":true"));

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
                + ": " + fixture, fixture.contains("\"ok\":true"));
        String tryComplete = exec("artest machine try-complete " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("railgun must validate at " + x + "," + Y + "," + z + ": "
                + tryComplete, tryComplete.contains("\"isComplete\":true"));
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
        exec("give @a " + CHIP + " 1");
        bot().waitTicks(20);

        scenario().arranging("open the guidance computer's GUI");
        String screen = openMachineGui(at);
        scenario().record("screen", screen);

        scenario().measuring("find the chip in the player half of the open container");
        JsonObject before = bot().reportSlots();
        int chipSlot = findSlotWithItem(before, CHIP, true);
        scenario().requireArranged("chip not found in the player inventory portion of the GUI: "
                + before, chipSlot != -1);

        scenario().asserting("a shift-click quick-moves the chip into the machine's own slot");
        bot().clickSlot(chipSlot, 0, "QUICK_MOVE");
        // LEFT AS A SETTLE, and the reason is a gap rather than a choice: no event records a slot
        // transfer. `report_slots` reads the CLIENT's copy of the container, and the client applies
        // its own prediction of a quick-move before the packet is even sent, so what is pinned
        // below is the move as the player sees it — a server that dropped the click would leave the
        // prediction standing and this would still pass. The link that would close it is a record at
        // ContainerModular.transferStackInSlot's return, routed by the player's world.
        bot().waitTicks(10);

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
                fixture.contains("\"ok\":true"));

        String completed = "";
        for (int attempt = 0; attempt < 8; attempt++) {
            completed = exec("artest machine try-complete " + dim + " " + x + " " + Y + " " + z);
            if (completed.contains("\"isComplete\":true")) {
                break;
            }
            bot().waitTicks(10);
        }
        scenario().requireArranged("the observatory structure never validated: " + completed,
                completed.contains("\"isComplete\":true"));

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
        double standingY = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (z - 0.5) + " 0 0");
            bot().waitTicks(20);
            standingY = bot().reportState().get("playerY").getAsDouble();
            if (Math.abs(standingY - (Y + 1)) <= 1.0) {
                break;
            }
        }
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
                crystal.contains("\"ok\":true"));

        String before = exec("artest telescope info " + where);
        scenario().requireArranged("the observatory's world must have a galactic address: " + before,
                before.contains("\"origin\":\""));
        String[] home = readGroup(before, TELESCOPE_ORIGIN).split("_");
        scenario().record("origin", readGroup(before, TELESCOPE_ORIGIN));

        scenario().arranging("open the observatory and switch to its region-scan tab");
        String screen = openMachineGui(at);
        scenario().record("screen", screen)
                .describeOnFailureWith("artest telescope info " + where);
        bot().clickButtonById(2);
        bot().waitTicks(20);

        scenario().asserting("the aim buttons reach the machine, and Observe starts the look");
        // LEFT AS A SETTLE: nothing records a packet reaching TileObservatory.useNetworkData, so
        // these two presses have no receipt of their own; the aim read back from the server below
        // is the assertion, and a slow round trip would fail it as "the aim never moved".
        bot().clickButtonById(5);
        bot().waitTicks(15);
        bot().clickButtonById(5);
        bot().waitTicks(15);

        String aimed = exec("artest telescope info " + where);
        long aimDistance = readInt(aimed, TELESCOPE_AIM_DISTANCE);
        assertTrue("clicking the distance button twice must move the aim out from 1: " + aimed,
                aimDistance > 1);

        // Put a system where the operator has it pointed — the default aim is +X, and the distance is
        // whatever his clicks produced. The aim is counted in STEPS of one star's territory, so the
        // cell it lands on is that many strides out; the seat is offset inside the territory, since
        // what a look must find is the system that OWNS the cell and not a star standing on it.
        long stepCells = readInt(aimed, TELESCOPE_STEP_CELLS);
        String system = exec("artest telescope system "
                + (Long.parseLong(home[0]) + aimDistance * stepCells + 13L)
                + " " + home[1] + " " + home[2]);
        scenario().requireArranged("could not place a system to be found: " + system,
                system.contains("\"ok\":true"));

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
                begun.contains("\"accepted\":true"));

        scenario().measuring("the crystal in the machine, after a survey driven only by clicks");
        // Left as a bounded read: the addresses are an ACCUMULATION over the cells the look
        // resolved, not a link — and the links either side of it are now named above, so a red here
        // means the survey ran and found nothing rather than "something did not happen".
        String done = exec("artest telescope info " + where);
        for (int attempt = 0; attempt < 20 && readInt(done, TELESCOPE_ADDRESSES) < 1; attempt++) {
            bot().waitTicks(20);
            done = exec("artest telescope info " + where);
        }
        assertTrue("a survey driven entirely from the GUI left the crystal empty: " + done
                        + " surveySteps=" + events.since(scanMark, "region_scan_advanced"),
                readInt(done, TELESCOPE_ADDRESSES) >= 1);

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
        // LEFT AS A SETTLE for the same reason as the telescope's aim: nothing records a packet
        // reaching TilePlanetSelector.useNetworkData, so the server-side selection read below is
        // both the assertion and the only receipt this click has.
        bot().clickButtonById(planetId);
        bot().waitTicks(20);

        String selectorInfo = exec("artest selector info " + plot().dim + " " + at[0] + " " + at[1]
                + " " + at[2]);
        assertTrue("clicking planet button " + planetId
                + " did not register a selection server-side: " + selectorInfo,
                selectorInfo.contains("\"hasSelection\":true"));
        assertTrue("selection did not resolve to a planet: " + selectorInfo,
                selectorInfo.contains("\"selectedDim\":"));

        bot().closeScreen();
    }

    // ── navigation console ────────────────────────────────────────────────────

    /**
     * From {@code NavigationComputerGuiE2ETest}. The navigation console driven the way a pilot
     * drives it: right-click it open, then nothing but button clicks.
     *
     * <p>What is pinned, in the order the pilot does it:</p>
     * <ol>
     *   <li><b>Arming with nowhere to go is refused, and said out loud.</b> The negative comes first
     *       because it doubles as the proof that a click on this GUI reaches the server at all —
     *       and that proof is now the console's own record of the command arriving, with the
     *       refusal reaching the pilot's screen as the second half rather than as the whole of
     *       it.</li>
     *   <li><b>Copying a brought crystal does not empty it.</b></li>
     *   <li><b>The console lists what the ship now knows</b>, read off the real GUI's buttons.</li>
     *   <li><b>Picking a listed address aims the ship at THAT address.</b></li>
     *   <li><b>Arm, then disarm</b> — each answered in chat, each reflected in the console state.</li>
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
                + " addresses: " + seed, seed.contains("\"addresses\":" + SEEDED));
        // The ship's own crystal is the DESTINATION, and the copy is add-only into it: with that
        // slot empty there is nowhere to copy to and the button is a silent no-op.
        String shipCrystal = exec("artest nav crystal " + where + " 1 0");
        scenario().requireArranged("the ship slot must hold a (blank) crystal to copy INTO: "
                + shipCrystal, shipCrystal.contains("\"addresses\":0"));
        String before = exec("artest nav status " + where);
        scenario().requireArranged("ARRANGEMENT CONTROL: the ship's own crystal must start EMPTY, "
                + "or the copy leg below cannot tell a successful copy from a pre-loaded console: "
                + before, readInt(before, SHIP_COUNT) == 0);

        emptyTheHand();
        String screen = openMachineGui(at);
        scenario().record("screen", screen);
        Events events = events();

        // ---- 1) Try to arm with nowhere to go. ------------------------------------------------
        // The chat overlay is no longer drained first. A mark taken before the click is what makes a
        // matching line belong to THIS stimulus, and it does it better than a clear: a clear leaves
        // a line already in flight, and it cannot see a reply that arrived and scrolled away.
        scenario().asserting("arming with no destination is refused, and the pilot is told why");
        long refusalMark = events.markInstrumented();
        long refusalOnClient = clientEvents().mark();
        bot().clickButtonById(BUTTON_ARM);
        events.assertChain(refusalMark, "an ARM click with nowhere to go must REACH the console and"
                        + " be ANSWERED - a click that never arrived and a console that answered"
                        + " something else are different failures and the chat could not tell them"
                        + " apart", 150,
                "nav_command_received", "nav_console_told");
        String told = events.since(refusalMark, "nav_console_told");
        assertEquals("arming with no destination chosen must be REFUSED, with the reason production"
                        + " itself chose - read at the console's own tell(), not off the overlay: "
                        + told, "msg.jumpgate.notarget", Events.lastField(told, "key"));
        String refusal = awaitClientRecords(refusalOnClient, "client_chat_received",
                "no jump target", 150);
        assertTrue("...and the pilot must actually be TOLD: the refusal has to reach his own screen,"
                        + " which is the half the server's decision cannot show. client chat since"
                        + " the click: " + refusal,
                refusal.toLowerCase(Locale.ROOT).contains("no jump target"));
        String afterRefusal = exec("artest nav status " + where);
        assertFalse("and the console must not be armed: " + afterRefusal,
                readBoolean(afterRefusal, ARMED));

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
                + " measuring the arrangement: " + copies, copies.contains("\"shipCrystal\":true"));
        String copied = exec("artest nav status " + where);
        assertEquals("clicking COPY must write the brought crystal's addresses into the ship's own "
                + "crystal: " + copied + " copies=" + copies, SEEDED, readInt(copied, SHIP_COUNT));
        assertEquals("and the brought crystal must KEEP them — the console exchanges knowledge, it "
                + "does not move it: " + copied, SEEDED, readInt(copied, SOURCE_COUNT));

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
        String aimed = exec("artest nav status " + where);
        String expected = "\"" + FIRST_SECTOR + "_0_0\"";
        assertEquals("clicking the first listed address must aim the ship at THAT address — the "
                + "list's order is what the pilot picks by, so aiming at some other entry is the "
                + "same defect as not aiming at all: " + aimed + " aims=" + picked,
                expected, readGroup(aimed, TARGET));

        // ---- 5) Arm, and stand down again. Both answered. ---------------------------------------
        scenario().asserting("arming a chosen destination is accepted, confirmed, and real");
        long armMark = events.markInstrumented();
        long armOnClient = clientEvents().mark();
        bot().clickButtonById(BUTTON_ARM);
        events.assertChain(armMark, "an ARM click on a chosen destination must reach the console and"
                + " be answered", 150, "nav_command_received", "nav_console_told");
        String armedTold = events.since(armMark, "nav_console_told");
        assertEquals("arming a chosen destination must be ACCEPTED, and the acceptance is the"
                        + " message production picked: " + armedTold,
                "msg.jump.armed", Events.lastField(armedTold, "key"));
        String armedChat = awaitClientRecords(armOnClient, "client_chat_received", "jump armed", 150);
        assertTrue("...and the confirmation must reach the pilot's own screen. client chat since the"
                + " click: " + armedChat, armedChat.toLowerCase(Locale.ROOT).contains("jump armed"));
        String armedStatus = exec("artest nav status " + where);
        assertTrue("and the console must actually BE armed — the message is not the state: "
                + armedStatus, readBoolean(armedStatus, ARMED));

        scenario().asserting("pressing the same button again stands the jump down, and says so");
        long disarmMark = events.markInstrumented();
        long disarmOnClient = clientEvents().mark();
        bot().clickButtonById(BUTTON_ARM);
        events.assertChain(disarmMark, "a second ARM click must reach the console and be answered",
                150, "nav_command_received", "nav_console_told");
        String disarmedTold = events.since(disarmMark, "nav_console_told");
        assertEquals("pressing the same button again must STAND THE JUMP DOWN, and say which of the"
                        + " three answers it is: " + disarmedTold,
                "msg.jump.disarmed", Events.lastField(disarmedTold, "key"));
        String disarmedChat = awaitClientRecords(disarmOnClient, "client_chat_received",
                "jump disarmed", 150);
        assertTrue("...and the pilot must be told he is standing down. client chat since the click: "
                + disarmedChat, disarmedChat.toLowerCase(Locale.ROOT).contains("jump disarmed"));
        String disarmedStatus = exec("artest nav status " + where);
        assertFalse("a disarmed console must not stay armed: " + disarmedStatus,
                readBoolean(disarmedStatus, ARMED));

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
        exec("clear @a");
        bot().selectHotbar(0);
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonObject items = bot().reportPlayerItems();
            if (items.has("worldReady") && items.get("worldReady").getAsBoolean()
                    && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
            bot().waitTicks(5);
        }
        scenario().arrangementFailed("the bot's hand must be observably empty; held=" + heldId);
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
                place.contains("\"placed\":true"));
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5) + " 0 90");
        bot().waitTicks(40);

        // Opened SERVER-side (mirrors BlockChest.onBlockActivated -> player.displayGUIChest) rather
        // than by right-click: the right-click packet was dropped before the chunk/player settled
        // in the original class, a settle-timing race orthogonal to the mixin contract under test.
        // The S2C open-window packet makes the real client render GuiChest.
        Events events = events();
        long openMark = events.mark();
        long openOnClient = clientEvents().mark();
        String open = exec("artest player open-chest " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("server-side open-chest must succeed: " + open,
                open.contains("\"ok\":true"));
        events.await(openMark, "container_opened", "the server must actually OPEN a container: with"
                + " none open there is nothing for vanilla's distance check to close and both legs"
                + " below would be measuring an empty screen", 100);
        String displayed = awaitClientRecords(openOnClient, "client_gui_opened",
                "\"gui\":\"GuiChest\"", 200);
        scenario().requireArranged("the real client must DISPLAY the chest GUI — this scenario is"
                + " about a screen surviving a distance, so a screen that never arrived is an"
                + " arrangement failure, not a verdict on the redirect. openResp=" + open
                + " screensDisplayed=" + displayed, displayed.contains("\"gui\":\"GuiChest\""));

        scenario().asserting("with the bypass on, the GUI survives a 200-block teleport");
        String addResp = exec("artest player inv-bypass add");
        scenario().requireArranged("inv-bypass add must report inBypass:true: " + addResp,
                addResp.contains("\"inBypass\":true"));

        long farMark = events.markInstrumented();
        exec("tp @a " + (x + 200) + " " + (Y + 1) + " " + (z + 200) + " 0 0");
        bot().waitTicks(40);

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
                        "\"type\":\"container_closed\""));
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
                removeResp.contains("\"inBypass\":false"));

        // The close is a chain, and its ORDER is one server call stack: the redirect answers, and
        // vanilla's own `if` closes the screen on that answer. Asserting it as a chain is what
        // distinguishes "the redirect said no and the close followed" from "something else closed
        // the chest", which an empty screen cannot.
        events.assertChain(closeMark, "removing the bypass must let vanilla's reach check REFUSE the"
                        + " interaction, and that refusal must be what closes the container", 200,
                "container_interact_checked", "container_closed");
        String checks = events.since(closeMark, "container_interact_checked");
        assertTrue("the reach check must have come back FALSE — a close for any other reason pins"
                + " nothing about the redirect: " + checks, checks.contains("\"allowed\":false"));
        String closedOnClient = awaitClientRecords(closeOnClient, "client_gui_opened",
                "\"gui\":\"none\"", 200);
        assertTrue("...and the player's own screen must go away — the server letting go of the"
                        + " container is not yet the player seeing it close. screens the client"
                        + " displayed since: " + closedOnClient,
                closedOnClient.contains("\"gui\":\"none\""));
        assertEquals("after removing inv-bypass, vanilla's distance check must close the chest "
                + "GUI; final screen=" + screenOf(bot().reportState()), "",
                screenOf(bot().reportState()));
    }
}
