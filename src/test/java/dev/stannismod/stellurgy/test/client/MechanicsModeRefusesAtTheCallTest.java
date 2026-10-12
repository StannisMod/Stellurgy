package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.ClientCommandRefused;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.AbstractMechanicsTest;
import com.github.stannismod.forge.testing.junit.AbstractOwnBootClientTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerCommandFailed;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stannismod.stellurgy.test.contract.OwnBoot;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * A pair launched for a MECHANICS test refuses, at the call, the two channels a mechanics test may not
 * use — a static call on anything but a named mechanics instrument, and tile, entity or item NBT written by
 * command — and still answers the arrangements, reads and instruments a mechanics test is made of.
 *
 * <p><b>The subject</b> is the decision each judge takes when a command arrives in MECHANICS mode: the
 * server gate's ({@code E2EModeGate#mechanicsRefusalOf}, against the {@code MechanicsInstrument} table) and
 * the client bridge's ({@code ForgeTestClientBootstrap#refusedInMechanics}, against
 * {@code ClientInstruments#mechanicsRows}). Each form below is EXECUTED against the booted pair, so what is
 * judged is the command that arrives. A refusal is read as data: the server's by the thrown class and its
 * refusal object, the client's by the {@code refusedCommand} / {@code refusedKind} of its reply.</p>
 *
 * <p><b>What it does not see.</b> Whether an admitted instrument's body only reads (the build walks it for
 * writes); whether an admitted probe verb arranges or acts (no mechanics verb is classified yet); the static
 * FIELD read the client also refuses (no test may write that call at all); and a tag-writing command this
 * list does not send.</p>
 *
 * <p>SEPARATE-BOOT: a judge's refusal is the subject, and the planted calls leave things behind (an item in
 * his hand) that a shared pair would hand to every other scenario.</p>
 */
@OwnBoot(reason = OwnBoot.Reason.GLOBAL_MUTATION)
public class MechanicsModeRefusesAtTheCallTest extends AbstractMechanicsTest {

    /** The server gate's refusal, as the bridge names the thrown class. */
    private static final String SERVER_REFUSAL =
            "dev.stannismod.stellurgy.command.test.E2EModeGate$E2EModeRefusal";
    /** The harness client's player name. */
    private static final String PLAYER = "ForgeTestClient";
    /** A recorder of the test source set on the server — a row of the mechanics table. */
    private static final String SERVER_LOG = "dev.stannismod.stellurgy.test.trace.ServerEventLog";
    /** A recorder of the test source set on the client — a row of the mechanics table. */
    private static final String CLIENT_WINDOW = "dev.stannismod.stellurgy.test.trace.SeatGateWindow";

    private RealDedicatedServerHarness server;
    private RealClientHarness client;

    @Before
    public void bootBothInMechanicsMode() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled",
                Boolean.parseBoolean(System.getProperty(AbstractOwnBootClientTest.PROP_CLIENT_ENABLED, "false")));
        server = RealDedicatedServerHarness.start(harnessBoot());
        try {
            client = RealClientHarness.start(harnessBoot(), server);
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
     * Every planted static call and tag-writing command is refused at the call with the kind its judge gives
     * it, and every arrangement, read and named instrument a mechanics test is made of is answered.
     */
    @Pins("INV-ART-10")
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#mechanicsRefusalOf",
            at = "if (MechanicsInstrument.named(className, methodName) == null) {", replacedBy = "if (false) {",
            fails = "these were not: [`stellurgytest invoke-static dev.stannismod.stellurgy.dimension.DimensionManager",
            on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "E2EModeGate#mechanicsRefusalOf",
            at = "if (arg.indexOf('{') >= 0) {", replacedBy = "if (false) {",
            fails = "these were not: [`give ForgeTestClient minecraft:stone 1 0 {display", on = "2026-10-12")
    @RedWitness(verdict = "refused at the call", member = "ForgeTestClientBootstrap#refusedInMechanics",
            at = "if (ClientInstruments.mechanicsKindOf(className, methodName) == null) {", replacedBy = "if (false) {",
            fails = "these were not: [invoke_static_int RAN", on = "2026-10-12")
    @Test
    public void aMechanicsPairRefusesStaticCallsAndTagWritesAtTheCallAndAnswersItsArrangements() throws Exception {
        ClientBot bot = client.bot();
        List<String> notRefused = new ArrayList<>();

        // ---- the server: static calls on no named instrument, NBT by command ----
        expectServerRefusal(notRefused, "stellurgytest invoke-static dev.stannismod.stellurgy.dimension.DimensionManager"
                + " getInstance", "STATIC_CALL");
        expectServerRefusal(notRefused, "stellurgytest invoke-static dev.stannismod.stellurgy.test.client.ClientDimensions"
                + " forgeRegisteredOnClient 0", "STATIC_CALL");
        expectServerRefusal(notRefused, "blockdata 0 64 0 {storedMomentumY:1.0d}", "COMMAND");
        expectServerRefusal(notRefused, "entitydata @e[type=minecraft:item] {Age:0}", "COMMAND");
        expectServerRefusal(notRefused, "give " + PLAYER + " minecraft:stone 1 0 {display:{Name:\"forged\"}}", "COMMAND");
        expectServerRefusal(notRefused, "summon minecraft:zombie 0 80 0 {NoAI:1b}", "COMMAND");
        expectServerRefusal(notRefused, "fill 0 90 0 0 90 0 minecraft:chest 0 replace {Lock:\"k\"}", "COMMAND");
        expectServerRefusal(notRefused, "clear " + PLAYER + " minecraft:stone 0 1 {display:{}}", "COMMAND");
        expectServerRefusal(notRefused, "replaceitem entity " + PLAYER + " slot.hotbar.0 minecraft:stone 1 0 {display:{}}",
                "COMMAND");

        // ---- the client: a chain, a static call on no named instrument ----
        expectClientRefusal(notRefused, "invoke_static_chain", "STATIC_CHAIN",
                () -> bot.invokeStaticChain("dev.stannismod.stellurgy.client.ServerView", "current,clock,now"));
        expectClientRefusal(notRefused, "invoke_static_int", "STATIC_TARGET",
                () -> bot.invokeStaticInt("dev.stannismod.stellurgy.client.ClientProxy", "currentClientDimension", 0));

        assertTrue("every static call on no named instrument, and every tile, entity or item tag written by"
                + " command, must be refused at the call in mechanics mode with the kind its judge gives it;"
                + " these were not: " + notRefused, notRefused.isEmpty());

        // ---- the positive half, on the same judges ----
        List<String> notAnswered = new ArrayList<>();
        expectServerAnswered(notAnswered, "stellurgytest invoke-static " + SERVER_LOG + " markReply");
        expectServerAnswered(notAnswered, "give " + PLAYER + " minecraft:stone 1");
        expectServerAnswered(notAnswered, "stellurgytest arrange charged-item " + PLAYER + " stellurgy:repairWelder 1000");
        try {
            JsonObject opened = bot.invokeStaticInt(CLIENT_WINDOW, "open");
            bot.invokeStaticInt(CLIENT_WINDOW, "close", Integer.parseInt(opened.get("returned").getAsString()));
            bot.reads().reportState();
        } catch (ClientCommandRefused refused) {
            notAnswered.add("the client refused an admitted command: " + refused.reply());
        }
        assertTrue("every arrangement, read and named instrument a mechanics test is made of must be answered;"
                + " these were not: " + notAnswered, notAnswered.isEmpty());
    }

    private interface Act {
        void run() throws Exception;
    }

    private void expectServerRefusal(List<String> notRefused, String command, String kind) throws Exception {
        try {
            server.client().execute(command);
            notRefused.add("`" + command + "` RAN");
        } catch (ServerCommandFailed failed) {
            JsonObject reply = failed.reply();
            if (!reply.has("thrown") || !SERVER_REFUSAL.equals(reply.get("thrown").getAsString())) {
                notRefused.add("`" + command + "` failed, but not by the mode gate: " + reply);
                return;
            }
            JsonObject refusal = new JsonParser().parse(reply.get("thrownMessage").getAsString()).getAsJsonObject();
            if (!refusal.get("refused").getAsBoolean() || !kind.equals(refusal.get("kind").getAsString())) {
                notRefused.add("`" + command + "` refused as " + refusal.get("kind") + ", not " + kind + ": " + refusal);
            }
        }
    }

    private void expectServerAnswered(List<String> notAnswered, String command) throws Exception {
        try {
            String reply = String.join("\n", server.client().execute(command));
            if (reply.contains("\"error\"")) {
                notAnswered.add("`" + command + "` answered an error: " + reply);
            }
        } catch (ServerCommandFailed failed) {
            notAnswered.add("`" + command + "` was refused: " + failed.reply());
        }
    }

    private void expectClientRefusal(List<String> notRefused, String command, String kind, Act act) throws Exception {
        try {
            act.run();
            notRefused.add(command + " RAN");
        } catch (ClientCommandRefused refused) {
            JsonObject reply = refused.reply();
            if (!command.equals(reply.get("refusedCommand").getAsString())
                    || !kind.equals(reply.get("refusedKind").getAsString())) {
                notRefused.add(command + " refused as " + reply.get("refusedKind") + ", not " + kind + ": " + reply);
            }
        } catch (java.io.IOException failed) {
            notRefused.add(command + " failed, but not by the mechanics judge: " + failed.getMessage());
        }
    }
}
