package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.ClientCommandRefused;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.AbstractMechanicsTest;
import com.github.stannismod.forge.testing.junit.AbstractOwnBootClientTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerCommandFailed;
import com.github.stannismod.forge.testing.server.ServerRoot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.EvictionReports;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.OwnBoot;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * A run in E2E MODE refuses, at the call, every act an e2e may not perform — whatever form the test wrote
 * it in — and still answers the reads and the input an e2e is made of.
 *
 * <p><b>The subject</b> is the decisions taken when a command arrives: the server gate's
 * ({@code E2EModeGate#refusalOf}, with the probe's {@code ProbeVerbKind} table, the {@code E2EInstrument}
 * table, the data-tag shapes of {@code give} / {@code clear} / {@code fill} and the blocks no player
 * obtains) and the client bridge's ({@code ForgeTestClientBootstrap#refusedInE2E}, by {@code BridgeCommand}'s
 * kinds and the client's instrument table; and, in every mode, the input and look no hand produces). Every
 * call below was once written into an e2e in a form a text scan of the test did not see — a verb assembled
 * at run time, a constant of another class, an instance helper, a method reference, a lambda in a field
 * initialiser, a method named like an instrument, a read word as an argument, a static call on a production
 * class, a root the scan had no name for — or was found admitted by adversarial round
 * 4: a static of the test source set that is no instrument (one could clear the mode's own property), an
 * item or tile tag in a vanilla command, a block nobody obtains, a "read" that adopted machines and minted
 * an id, a chunk ticket filed as a build, a head turned to NaN, an input burst no tick reads — or by round 5:
 * a build with no time or place (after the player's first input, in another dimension, out of his reach, at
 * relative coordinates, a fixture other than the named one), a turn at NaN, a menu option no slider reaches,
 * three presses of one key in a tick, and a refusal at the console the test never heard. Here each form
 * is EXECUTED against a booted server and client, so what is judged is the command that arrives, never the
 * text that sent it. A refusal is read as data: the server's by the thrown class and the refusal object it
 * carries, the client's by the {@code refusedCommand} / {@code refusedKind} of its reply. And a refused
 * command arriving through the server's CONSOLE is refused without crashing the server: its refusal is
 * the gate's published record ({@code command_refused}, from the server's own event log), and the server
 * answers the next command.</p>
 *
 * <p><b>What it does not see.</b> Whether a READ row of the probe's table writes nothing (a claim checked
 * against its handler when the row is added); whether a BUILD lays only what the player's start holds
 * beyond the deny-list of unobtainable blocks (a reading of the fixture); whether a named instrument's body
 * reaches the mod (a reading of the row); and a bypass command this list does not send — a NEW bridge
 * command is refused only if its kind says so. A call made by reflection is not sent from here: the build
 * refuses reflection in the e2e's code and in the mechanics tier's alike ({@code E2EConfinement}), so this
 * class could not hold one.</p>
 *
 * <p>SEPARATE-BOOT: e2e mode is a JVM property of the server and client children, set at their launch, so
 * no shared client base can carry it.</p>
 */
@OwnBoot(reason = OwnBoot.Reason.JVM_PROPERTY)
public class E2EModeRefusesAtTheCallTest extends AbstractMechanicsTest {

    /** The server gate's refusal, as the bridge names the thrown class. */
    private static final String SERVER_REFUSAL =
            "dev.stannismod.stellurgy.command.test.E2EModeGate$E2EModeRefusal";

    /** A static of the mod (not of the test source set) that takes ints, on the server. */
    private static final String MOD_CLASS_SERVER = "dev.stannismod.stellurgy.atmosphere.LifeSupportNetwork";
    /** A static of the mod (not of the test source set) that takes ints, on the client. */
    private static final String MOD_CLASS_CLIENT = "dev.stannismod.stellurgy.client.ClientProxy";
    /** A recorder of the test source set, on the client — a named instrument of the client's table. */
    private static final String TEST_WINDOW_CLIENT = "dev.stannismod.stellurgy.test.trace.SeatGateWindow";
    /**
     * A static of the test source set that is NO instrument, on the client: it arms a mixin to shove the
     * player's body. A package rule admitted it.
     */
    private static final String TEST_STATIC_NOT_AN_INSTRUMENT_CLIENT = "dev.stannismod.stellurgy.test.trace.ShoveArming";
    /** A static of the test source set that is NO instrument, on the server: it resets a recorder. */
    private static final String TEST_STATIC_NOT_AN_INSTRUMENT_SERVER = "dev.stannismod.stellurgy.test.trace.SpawnMemory";

    /**
     * How long a command typed into the server's console is given to be judged, in server ticks. A deadline
     * for a discrete decision, not a settle: the console thread hands the line to the server, which runs it
     * at its next tick, and the gate decides before it runs. An expiry means the command never reached the
     * command handler at all.
     */
    private static final int CONSOLE_JUDGED_TICKS = 200;

    /** One key event more than a client tick reads from LWJGL's keyboard buffer (50): 26 characters typed. */
    private static final String ONE_EVENT_PAST_A_TICK = "abcdefghijklmnopqrstuvwxyz";
    /** Exactly LWJGL's keyboard buffer: 25 characters typed, a press and a release each. */
    private static final String A_FULL_TICK_OF_KEYS = "abcdefghijklmnopqrstuvwxy";

    /**
     * One block past the farthest a build may lay from the player on an axis ({@code E2EModeGate#BUILD_REACH},
     * 32): the reach is the gate's decision, and this is the first distance it must refuse.
     */
    private static final int PAST_REACH = 33;

    /**
     * One chunk past the render-distance slider's top on this client: vanilla's {@code GameSettings} raises
     * the slider to 32 on a 64-bit JVM with at least 1 GB of heap, which the harness client always has
     * (2560 MB). The first value no menu reaches.
     */
    private static final int PAST_THE_RENDER_DISTANCE_SLIDER = 33;

    /** Three presses of one key in one call: one more than a hand makes in a client tick (2). */
    private static final String THREE_PRESSES_OF_ONE_KEY = "aaa";

    /** A constant of the test, held outside any method body, as one planted bypass held its command. */
    private static final String SPAWN_A_ZOMBIE = "summon minecraft:zombie 300 85 300";

    private RealDedicatedServerHarness server;
    private RealClientHarness client;

    /** A bypass held in a field initialiser, as one planted bypass held its act. */
    private final Act clickTheBlockFromAField = () -> client.bot().interactBlock(1, 2, 3);

    private interface Act {
        void run() throws Exception;
    }

    @Before
    public void bootBothInE2EMode() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled",
                Boolean.parseBoolean(System.getProperty(AbstractOwnBootClientTest.PROP_CLIENT_ENABLED, "false")));
        server = RealDedicatedServerHarness.startE2E(harnessBootInE2EMode(), ServerRoot.fresh("forge-e2e-mode-"));
        try {
            client = RealClientHarness.startE2E(harnessBootInE2EMode(), server);
        } catch (Exception startFailed) {
            server.close();
            server = null;
            throw startFailed;
        }
    }

    @After
    public void closeBoth() throws Exception {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    /**
     * Every planted bypass is refused at the call with the kind its gate gives it, every read, build,
     * admitted static call and input event an e2e is made of is answered, and a refusal typed into the
     * server's console leaves the server running.
     */
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#judged",
            at = ": refusalOf(command, args, sender);", replacedBy = ": null;",
            fails = "these were not: [`stellurgytest place 0 ", on = "2026-10-13")
    @RedWitness(verdict = "refused at the call", member = "ForgeTestClientBootstrap#handleCommand",
            at = "JsonObject refusal = refusedInE2E(known, request);", replacedBy = "JsonObject refusal = null;",
            fails = "these were not: [interact_block RAN", on = "2026-10-12")
    @LegacyWitness(prose = "round 4, one run with every new refusal inverted at its own branch (2026-10-09,"
            + " logs/t564r4-refusal-witnessW1.log): {@code E2EModeGate#staticCallRefusal} at"
            + " {@code if (E2EInstrument.named(className, methodName) != null)} -> {@code if (true)}; the {@code give} tag"
            + " branch, the {@code clear} tag branch and the {@code fill} tag test {@code args[i].indexOf('{') >= 0}"
            + " -> {@code false}; {@code E2EModeGate#unobtainableBlock} answering no refusal and"
            + " {@code #unobtainableItem} returning at once; {@code drive info}, {@code chunk forceload} and"
            + " {@code vs permaload} filed READ in {@code ProbeVerbKind#row}; {@code ForgeTestClientBootstrap#refusedInE2E} at"
            + " {@code if (ClientInstruments.kindOf(className, methodName) == null)} -> {@code if (false)} (taken on the"
            + " pre-static-table form, which asked an instance field {@code instruments} the same question), the set_look NaN /"
            + " infinity test -> {@code false}, and {@code #deliverInput} ignoring {@code inputNoHandProduces}. Fails:"
            + " \"every act an e2e may not perform must be refused … these were not: [`stellurgytest vs permaload true`"
            + " RAN, `stellurgytest vs permaload false` RAN, … SpawnMemory reset` RAN, … clearProperty forge.test.e2e`"
            + " RAN, `give @a minecraft:stone 1 0 {display:…}` RAN, `give @a minecraft:bedrock 1` RAN, … creativePowerBattery"
            + " 1` RAN, `clear …{display:{}}` RAN, `fill … {Lock:\"k\"}` RAN, `fill … bedrock` RAN, `stellurgytest place …"
            + " minecraft:bedrock` RAN, `stellurgytest fill … creativePowerBattery` RAN, `stellurgytest drive info …` RAN,"
            + " `stellurgytest chunk forceload …` RAN, invoke_static_int RAN, invoke_static_int RAN, set_look RAN,"
            + " set_look RAN, input failed, but not by the e2e gate … key code 256 …, input failed … mouse button 16 …,"
            + " input RAN]\" — each new form at its own entry. And the run shows the mode FIXED at the start: with"
            + " {@code forge.test.e2e} cleared in the server child by the inverted static call, every later bypass"
            + " ({@code summon}, {@code tp}, {@code execute}, {@code forge tps}, the reflective {@code tp}) was still"
            + " refused.")
    @LegacyWitness(prose = "round 4, the console (2026-10-09, logs/t564r4-refusal-witnessW2.log):"
            + " {@code E2EModeGate#onCommand} at {@code if (HARNESS_BRIDGE_SENDER.equals(sender.getClass().getName()))}"
            + " -> {@code if (true)} — the refusal thrown at every sender, as before round 4 — fails at the console"
            + " wait: \"Server bridge exchange failed for: stellurgytest invoke-static … ServerEventLog sinceReply …"
            + " command_refused\", the server having crashed with \"Encountered an unexpected exception"
            + " … E2EModeRefusal: {\\\"refused\\\":true,\\\"command\\\":\\\"tp\\\"…\".")
    @LegacyWitness(prose = "with {@code ProbeVerbKind#admittedInE2E} at {@code return admittedInE2E;} replaced by {@code return false;}"
            + " and {@code Kind#admittedInE2E} at {@code return admittedInE2E;}"
            + " replaced by {@code return this == HARNESS;}, the refusal verdict"
            + " holds and fails: \"every read, build and input an e2e is made of must be answered … `stellurgytest"
            + " space bodies` was refused … report_state … READS\" (2026-10-08, logs/t564-refusal-witnessB.log)."
            + " Both runs were taken before the two"
            + " {@code vs permaload} rows were added (the release is admitted, its opposite is not); each row sits in"
            + " the list the corresponding inversion reddened.")
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#placeRefusal",
            at = "if (dx > BUILD_REACH || dy > BUILD_REACH || dz > BUILD_REACH)", replacedBy = "if (false)",
            fails = "these were not: [`stellurgytest place 0 ", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#placeRefusal",
            at = "if (dim != playerDim)", replacedBy = "if (false)",
            fails = "these were not: [`stellurgytest place -1 ", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#vanillaFillPlace",
            at = "if (box == null) {", replacedBy = "if (box == null) { return null; } if (false) {",
            fails = "these were not: [`fill ~1 ~1 ~1 ~1 ~1 ~1 minecraft:stone`", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#buildRefusal",
            at = "if (!CREATIVE_PLUG_FIXTURE.equals(variant))", replacedBy = "if (false)",
            fails = "these were not: [`stellurgytest fixture rocket 0 ", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#afterTheWalk",
            at = "return walkBegan ?", replacedBy = "return false ?",
            fails = "`give @a minecraft:stone 1` RAN", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "ForgeTestClientBootstrap#handleCommand",
            at = "if (Float.isNaN(askedDeltaYaw) || Float.isInfinite(askedDeltaYaw)"
                    + " || Float.isNaN(askedDeltaPitch) || Float.isInfinite(askedDeltaPitch))",
            replacedBy = "if (false)",
            fails = "these were not: [turn_look RAN, turn_look RAN", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "ForgeTestClientBootstrap#offTheSlider",
            at = "if (value < min || value > max || option.snapToStepClamp(value) != value)", replacedBy = "if (false)",
            fails = "these were not: [set_render_distance RAN", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "ForgeTestClientBootstrap#inputNoHandProduces",
            at = "if (count > HUMAN_PRESSES_PER_TICK)", replacedBy = "if (false)",
            fails = "these were not: [input RAN", on = "2026-10-12")
    @RedWitness(verdict = "refused at the console", member = "E2EModeGate#refuse",
            at = "CONSOLE_JUDGED + \"refused \"", replacedBy = "CONSOLE_JUDGED + \"admitted \"",
            fails = "a command the e2e gate refuses at the CONSOLE must reach the test as a refusal too", on = "2026-10-13")
    @Test
    public void everyActAnE2EMayNotPerformIsRefusedAtTheCallAndEveryReadItIsMadeOfIsAnswered() throws Exception {
        ClientBot bot = client.bot();
        List<String> notRefused = new ArrayList<>();

        // ---- the start: what an e2e builds before its player's first input, around him ----
        JsonObject standing = bot.reads().reportState();
        int px = (int) Math.floor(standing.get("playerX").getAsDouble());
        int py = (int) Math.floor(standing.get("playerY").getAsDouble());
        int pz = (int) Math.floor(standing.get("playerZ").getAsDouble());
        String beside = (px + 2) + " " + (py + 3) + " " + (pz + 2);
        List<String> notAnswered = new ArrayList<>();
        expectServerAnswered(notAnswered, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "give @a minecraft:stone 1", false);
        expectServerAnswered(notAnswered, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "fill " + beside + " " + beside + " minecraft:stone", false);
        expectServerAnswered(notAnswered, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest place 0 " + (px + 3) + " " + (py + 3) + " " + (pz + 3)
                + " minecraft:iron_block", true);
        // ---- round 5: a build has a place — his dimension, within reach, absolute, the named fixture only ----
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest place 0 " + (px + PAST_REACH) + " " + py + " " + pz
                + " minecraft:stone", "OUT_OF_REACH");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest fill 0 " + px + " " + (py + 1) + " " + pz + " " + px + " "
                + (py + PAST_REACH) + " " + pz + " minecraft:air", "OUT_OF_REACH");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest place -1 " + beside + " minecraft:stone",
                "NOT_THE_PLAYERS_DIMENSION");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "fill ~1 ~1 ~1 ~1 ~1 ~1 minecraft:stone", "ARGUMENTS");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest fixture rocket 0 " + beside + " simple", "NOT_THE_NAMED_FIXTURE");

        // ---- the server: probe verbs, vanilla and other roots, static calls ----
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest drive charge status", "ARRANGE");
        String assembled = String.join(" ", "turret", "charge");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest " + assembled + " 0 300 84 300", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest turret target 0 300 84 300 320.5 84.5 300.5", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, new MissionHelper().finishingCommand(), "DECIDE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ACT, "stellurgytest tile force-tick 0 300 84 300 1", "TRIGGER");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest fixture machine lathe 0 " + beside, "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, MissionHelper.CHARGE, "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest planet state-orbit-line 0 255", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest config set rocketRequireFuel true", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest tp 0 0 100 0", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest no-such-group no-such-action", "UNCLASSIFIED");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest vs permaload true", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest vs permaload false", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest invoke-static " + MOD_CLASS_SERVER + " perTick 20",
                "STATIC_CALL");
        // ---- round 4: what a package rule, a tag, a block id and a misfiled row let through ----
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest invoke-static " + TEST_STATIC_NOT_AN_INSTRUMENT_SERVER
                + " reset", "STATIC_CALL");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest invoke-static java.lang.System clearProperty forge.test.mode",
                "STATIC_CALL");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "give @a minecraft:stone 1 0 {display:{Name:\"forged\"}}", "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "give @a minecraft:bedrock 1", "UNOBTAINABLE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "give @a libvulpes:creativePowerBattery 1", "UNOBTAINABLE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "clear @a minecraft:stone 0 1 {display:{}}", "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "fill 300 90 300 300 90 300 minecraft:chest 0 destroy {Lock:\"k\"}",
                "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "fill 300 90 300 300 90 300 bedrock", "UNOBTAINABLE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest place 0 300 90 300 minecraft:bedrock", "UNOBTAINABLE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest fill 0 300 90 300 300 90 300 libvulpes:creativePowerBattery",
                "UNOBTAINABLE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest drive info 0 300 84 300", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest chunk forceload 0 18 18", "ARRANGE");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, SPAWN_A_ZOMBIE, "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "tp @a 300 85 300", "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "/tp @a 300 85 300", "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "execute @a ~ ~ ~ tp @s 0 100 0", "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgy goto 5", "COMMAND");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "forge tps", "COMMAND");

        // ---- the client: the verbs that name their target or write what input would have produced ----
        expectClientRefusal(notRefused, "interact_block", "BYPASS", () -> bot.interactBlock(1, 2, 3));
        expectClientRefusal(notRefused, "interact_block", "BYPASS", clickTheBlockFromAField);
        expectClientRefusal(notRefused, "interact_block", "BYPASS", () -> fenceWhatTheClientSent(bot));
        java.util.concurrent.Callable<?> useByReference = bot::useItem;
        expectClientRefusal(notRefused, "use_item", "BYPASS", useByReference::call);
        expectClientRefusal(notRefused, "send_chat", "BYPASS", () -> bot.sendChat("/stellurgy go"));
        expectClientRefusal(notRefused, "right_click_block", "BYPASS",
                () -> bot.rightClickBlock(1, 2, 3, net.minecraft.util.EnumFacing.UP, net.minecraft.util.EnumHand.MAIN_HAND));
        expectClientRefusal(notRefused, "set_key", "BYPASS", () -> bot.setKey(Keyboard.KEY_W, true));
        expectClientRefusal(notRefused, "select_hotbar", "BYPASS", () -> bot.selectHotbar(3));
        expectClientRefusal(notRefused, "use_mouse_over", "BYPASS", bot::useMouseOver);
        expectClientRefusal(notRefused, "click_screen_point", "BYPASS", () -> bot.clickScreenPoint(10, 10, 0));
        expectClientRefusal(notRefused, "click_button_id", "BYPASS", () -> bot.clickButtonById(0));
        expectClientRefusal(notRefused, "click_slot", "BYPASS", () -> bot.clickSlot(0, 0, "PICKUP"));
        expectClientRefusal(notRefused, "close_screen", "BYPASS", bot::closeScreen);
        expectClientRefusal(notRefused, "invoke_static_chain", "STATIC_CHAIN",
                () -> bot.invokeStaticChain("net.minecraft.client.Minecraft", "getMinecraft"));
        expectClientRefusal(notRefused, "invoke_static_int", "STATIC_TARGET",
                () -> bot.invokeStaticInt(MOD_CLASS_CLIENT, "currentClientDimension", 0));
        // ---- round 4: a test-source static that is no instrument; input and look no hand produces ----
        expectClientRefusal(notRefused, "invoke_static_int", "STATIC_TARGET",
                () -> bot.reads().invokeStaticInt(TEST_STATIC_NOT_AN_INSTRUMENT_CLIENT, "open", 40));
        expectClientRefusal(notRefused, "set_look", "ARGUMENTS", () -> bot.input().setLook(Float.NaN, 0.0F));
        expectClientRefusal(notRefused, "set_look", "ARGUMENTS",
                () -> bot.input().setLook(0.0F, Float.POSITIVE_INFINITY));
        expectClientRefusal(notRefused, "input", "ARGUMENTS", () -> bot.input().setKey(Keyboard.KEYBOARD_SIZE, true));
        expectClientRefusal(notRefused, "input", "ARGUMENTS", () -> bot.input().setKey(-100 + 16, true));
        expectClientRefusal(notRefused, "input", "ARGUMENTS", () -> bot.input().typeText(ONE_EVENT_PAST_A_TICK));
        // ---- round 5: look, menu options and presses no hand makes ----
        expectClientRefusal(notRefused, "turn_look", "ARGUMENTS", () -> bot.input().turnLook(Float.NaN, 0.0F));
        expectClientRefusal(notRefused, "turn_look", "ARGUMENTS",
                () -> bot.input().turnLook(0.0F, Float.NEGATIVE_INFINITY));
        expectClientRefusal(notRefused, "set_render_distance", "ARGUMENTS", () -> bot.setRenderDistance(1));
        expectClientRefusal(notRefused, "set_render_distance", "ARGUMENTS",
                () -> bot.setRenderDistance(PAST_THE_RENDER_DISTANCE_SLIDER));
        expectClientRefusal(notRefused, "set_frame_rate", "ARGUMENTS", () -> bot.setFrameRate(1000));
        expectClientRefusal(notRefused, "set_frame_rate", "ARGUMENTS", () -> bot.setFrameRate(15));
        expectClientRefusal(notRefused, "input", "ARGUMENTS", () -> bot.input().typeText(THREE_PRESSES_OF_ONE_KEY));
        // ---- round 5: after his first input (any of the above) the walk has begun, and no build runs ----
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "stellurgytest place 0 " + beside + " minecraft:stone", "AFTER_THE_WALK_BEGAN");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "give @a minecraft:stone 1", "AFTER_THE_WALK_BEGAN");
        expectServerRefusal(notRefused, com.github.stannismod.forge.testing.server.CommandRole.ARRANGE, "fill " + beside + " " + beside + " minecraft:stone", "AFTER_THE_WALK_BEGAN");

        assertTrue("every act an e2e may not perform must be refused at the call, with the kind its gate"
                + " gives it; these were not: " + notRefused, notRefused.isEmpty());

        // ---- the positive half, on the same gates: what an e2e is made of is answered ----
        expectServerAnswered(notAnswered, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest space bodies", true);
        expectServerAnswered(notAnswered, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest invoke-static dev.stannismod.stellurgy.test.trace.SpawnMemory snapshot", true);
        expectServerAnswered(notAnswered, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest planet orbit-line 0", false);
        // `drive observe` answers about an empty block too (`afc:false`), so no `ok` is asked of it.
        expectServerAnswered(notAnswered, com.github.stannismod.forge.testing.server.CommandRole.READ, "stellurgytest drive observe 0 300 84 300", false);
        try {
            JsonObject state = bot.reads().reportState();
            if (!state.get("worldReady").getAsBoolean()) {
                notAnswered.add("report_state answered worldReady=false: " + state);
            }
            JsonObject opened = bot.reads().invokeStaticInt(TEST_WINDOW_CLIENT, "open");
            bot.reads().invokeStaticInt(TEST_WINDOW_CLIENT, "close", Integer.parseInt(opened.get("returned").getAsString()));
            JsonObject pressed = bot.input().setKey(Keyboard.KEY_LSHIFT, true);
            bot.input().setKey(Keyboard.KEY_LSHIFT, false);
            if (pressed.get("delivered").getAsInt() != 1) {
                notAnswered.add("input delivered " + pressed.get("delivered") + " events of 1: " + pressed);
            }
            JsonObject fullTick = bot.input().typeText(A_FULL_TICK_OF_KEYS);
            if (fullTick.get("delivered").getAsInt() != 2 * A_FULL_TICK_OF_KEYS.length()) {
                notAnswered.add("a full tick of keys delivered " + fullTick.get("delivered") + ": " + fullTick);
            }
            // Past straight down is no refusal: the head stops where a mouse would stop it.
            JsonObject lookedDown = bot.input().setLook(0.0F, 1000.0F);
            if (!lookedDown.get("applied").getAsBoolean() || lookedDown.get("pitch").getAsFloat() != 90.0F) {
                notAnswered.add("a look past straight down must turn the head to 90 and no further: " + lookedDown);
            }
        } catch (ClientCommandRefused refused) {
            notAnswered.add("the client refused an admitted command: " + refused.reply());
        }
        assertTrue("every read, build and input an e2e is made of must be answered; these were not: "
                + notAnswered, notAnswered.isEmpty());

        // ---- the console: a refused command typed into the server's stdin is refused, not a crash ----
        Events serverLog = new Events(command -> String.join("\n", server.client().read(command)),
                GameTicks.serverAndClient(server.client(), GameTicks.server(), bot.reads()::waitWorldTicks),
                evictions);
        long beforeConsole = serverLog.mark();
        try {
            server.client().sendRaw("tp @a 0 200 0");
            notRefused.add("`tp` typed into the console RAN, or its refusal did not reach the test");
        } catch (ServerCommandFailed refusedAtTheConsole) {
            JsonObject reply = refusedAtTheConsole.reply();
            if (!reply.has("refused") || !reply.get("refused").getAsBoolean() || !reply.has("console")) {
                notRefused.add("`tp` at the console failed, but not as the gate's console refusal: " + reply);
            }
        }
        assertTrue("a command the e2e gate refuses at the CONSOLE must reach the test as a refusal too: "
                + notRefused, notRefused.isEmpty());
        serverLog.awaitMatching(beforeConsole, "command_refused",
                reply -> Events.anyRecordHasAll(reply, "command", "tp", "thrown", "false"),
                "carrying command = tp and thrown = false",
                "a command the e2e gate refuses, typed into the server's console, must be refused by being"
                        + " cancelled and TOLD — a refusal thrown at the console lands in the server's own tick",
                CONSOLE_JUDGED_TICKS);
        String afterConsole = String.join("\n", server.client().read("stellurgytest clock"));
        assertTrue("...and the server must still be running and answering after it: alive="
                        + server.client().isAlive() + " clock=" + afterConsole,
                server.client().isAlive() && Reply.of("stellurgytest clock", afterConsole).has("tick"));
    }

    /** The eviction announcements made for this test's own logs: its server lives no longer than it. */
    private final EvictionReports evictions = new EvictionReports();

    /** Named like the harness's packet-order fence, in a class that is not the fence. */
    private void fenceWhatTheClientSent(ClientBot bot) throws Exception {
        bot.interactBlock(1, 2, 3);
    }

    /** An instance helper holding a refused probe, and a class holding one as a constant. */
    private static final class MissionHelper {
        static final String CHARGE = "stellurgytest drive charge 0 1 2 3";

        String finishingCommand() {
            return "stellurgytest mission complete-now 0";
        }
    }

    /**
     * {@code command} sent in {@code role} — one that ADMITS what the command is, so that the refusal judged is
     * the mode's and not the role's.
     */
    private List<String> sent(com.github.stannismod.forge.testing.server.CommandRole role, String command)
            throws Exception {
        switch (role) {
            case READ:
                return server.client().read(command);
            case ARRANGE:
                return server.client().arrange(command);
            default:
                return server.client().act(command);
        }
    }

    private void expectServerRefusal(List<String> notRefused, com.github.stannismod.forge.testing.server.CommandRole role,
                                     String command, String kind) throws Exception {
        try {
            sent(role, command);
            notRefused.add("`" + command + "` RAN");
        } catch (ServerCommandFailed failed) {
            judgeServerRefusal(notRefused, command, kind, failed.reply());
        }
    }

    private void judgeServerRefusal(List<String> notRefused, String command, String kind, JsonObject reply) {
        if (!reply.has("thrown") || !SERVER_REFUSAL.equals(reply.get("thrown").getAsString())) {
            notRefused.add("`" + command + "` failed, but not by the e2e gate: " + reply);
            return;
        }
        JsonObject refusal = new JsonParser().parse(reply.get("thrownMessage").getAsString()).getAsJsonObject();
        if (!refusal.get("refused").getAsBoolean() || !kind.equals(refusal.get("kind").getAsString())) {
            notRefused.add("`" + command + "` refused as " + refusal.get("kind") + ", not " + kind + ": " + refusal);
        }
    }

    private void expectClientRefusal(List<String> notRefused, String wireName, String kind, Act act) throws Exception {
        try {
            act.run();
            notRefused.add(wireName + " RAN");
        } catch (ClientCommandRefused refused) {
            JsonObject reply = refused.reply();
            if (!wireName.equals(reply.get("refusedCommand").getAsString())
                    || !kind.equals(reply.get("refusedKind").getAsString())) {
                notRefused.add(wireName + " refused as " + reply.get("refusedCommand") + "/" + reply.get("refusedKind")
                        + ", not " + wireName + "/" + kind);
            }
        } catch (java.io.IOException failedOtherwise) {
            notRefused.add(wireName + " failed, but not by the e2e gate: " + failedOtherwise.getMessage());
        }
    }

    private void expectServerAnswered(List<String> notAnswered, com.github.stannismod.forge.testing.server.CommandRole role,
                                      String command, boolean repliesOk) throws Exception {
        try {
            String reply = String.join("\n", sent(role, command));
            if (repliesOk && !Reply.of(command, reply).ok()) {
                notAnswered.add("`" + command + "` answered without ok: " + reply);
            }
        } catch (ServerCommandFailed failed) {
            notAnswered.add("`" + command + "` was refused or failed: " + failed.reply());
        }
    }
}
