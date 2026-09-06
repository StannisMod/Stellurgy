package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import zmaster587.advancedRocketry.test.Events;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Repro for finding C031 (MED) — the
 * player-visible client side.
 *
 * <p>{@code PlanetEventHandler.connectToServer} lacked {@code @SubscribeEvent}
 * (dead), and the live {@code disconnected} handler had its
 * {@code unregisterAllDimensions()} call commented out — so a client leaving a
 * remote server never cleared AR's JVM-global client-side dimension list. Since
 * {@code PacketDimInfo} only merges per-id, dims unique to the previous server
 * lingered as ghost planets/stars after joining another server in the same
 * session.</p>
 *
 * <p>The full server-A → server-B ghost is not reproducible in this harness (a
 * client connects to exactly one dedicated server and cannot reconnect to a
 * second). This pins the corrected clearing contract that closes it: leaving a
 * REMOTE server clears the client's AR dimension registry. The client here is a
 * separate JVM connected to a dedicated server (so the remote-only guard,
 * {@code FMLCommonHandler.getMinecraftServerInstance() == null}, holds), and the
 * dim count is read on the client via the {@code invoke_static_chain} bridge
 * probe before and after a server kick.</p>
 *
 * <p><b>Corrected contract, pinned here (C031 fix, Path B)</b>: after a remote
 * disconnect the client's {@code DimensionManager.getRegisteredDimensions()} is
 * empty (was non-empty while connected).</p>
 */
public class ClientDimensionClearOnDisconnectE2ETest {

    private static final int DIM_A = 9701;
    private static final int DIM_B = 9702;
    private static final String DM_CLASS = "zmaster587.advancedRocketry.dimension.DimensionManager";

    private Path workDir;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-client-dim-clear-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                + planetXml("PlanetA", DIM_A)
                + planetXml("PlanetB", DIM_B)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(workDir, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception ex) {
            try { serverHarness.close(); } catch (Exception cleanup) { ex.addSuppressed(cleanup); }
            serverHarness = null;
            throw ex;
        }
    }

    private static String planetXml(String name, int dim) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>24000</rotationalPeriod>\n"
                + "            <atmosphereDensity>0</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try { clientHarness.close(); } catch (Exception e) { deferred = e; }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try { serverHarness.close(); }
            catch (Exception e) { if (deferred == null) deferred = e; else deferred.addSuppressed(e); }
            serverHarness = null;
        }
        if (deferred != null) throw deferred;
    }

    /** Client-side count of AR-registered dimensions via the reflective bridge probe. */
    private int clientArDimCount() throws Exception {
        JsonObject res = clientHarness.bot().invokeStaticChain(
                DM_CLASS, "getInstance,getRegisteredDimensions");
        return res.has("size") ? res.get("size").getAsInt() : -1;
    }

    // ── the client's own event log ────────────────────────────────────────────
    //
    // This class never speaks to the server probe, so the whole chain is on the CLIENT log:
    // {@code client_dim_registered} for each dimension the login sync brought in, then
    // {@code client_disconnected} and {@code client_dimensions_unregistered} for leaving. It runs its
    // own harness rather than the shared base's, so it reaches {@link ClientEvents} directly instead
    // of through {@code clientEvents()}.

    private Events clientEvents() {
        return ClientEvents.of(clientHarness.bot());
    }

    /**
     * Wait for a record of {@code type} that CARRIES {@code needle}, failing with the whole chain
     * that DID happen.
     *
     * <p>{@code needle} must end at a field boundary ({@code "dim":9701,}): a payload's numbers are
     * not delimited on the right, so a needle without the comma matches every value it is a prefix
     * of.</p>
     */
    private String awaitRecordCarrying(Events events, long mark, String type, String needle,
                                       String what, int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, type);
            if (Events.countRecords(reply, needle) > 0) {
                return reply;
            }
            clientHarness.bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying " + needle + " was recorded"
                + " within " + tickBudget + " ticks. What DID happen since the mark: "
                + Events.typesOf(events.since(mark)) + " | raw: " + reply);
    }

    /** The first integer {@code field} of a {@code since} reply, or {@code Integer.MIN_VALUE}. */
    private static int firstInt(String reply, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(-?\\d+)").matcher(String.valueOf(reply));
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    /**
     * How long one link of the disconnect chain may take. It budgets a network round trip and the
     * client's own handling of it — a deadline for a discrete event, not a settling value. 400 ticks
     * is the old loops' 80/100, with room for a loaded box.
     */
    private static final int LINK_BUDGET_TICKS = 400;

    @Test
    public void remoteDisconnectClearsClientDimensions() throws Exception {
        clientHarness.bot().waitForWorld();

        Events client = clientEvents();
        // The mark is taken for its ASSERTION, not for its number: it fails loudly if nothing on
        // this client is recording, which is what makes the reads below able to mean anything. The
        // reads themselves start from sequence 0 on purpose — the login this test observes happened
        // in @Before, before any mark could exist, and the log is BUFFERED, so reading it from the
        // beginning is exactly the thing a poll cannot do (a registration that happened before the
        // first sample never existed as far as a poll is concerned).
        client.mark();

        // On a remote login the server pushes a PacketDimInfo per AR dim, and each id the client has
        // never seen ends in registerDimNoUpdate. BOTH fixture dims are awaited by id: the old form
        // waited for "a count above zero", which a build that synced one of the two would satisfy —
        // and the contract below ("the registry is cleared") would then be measured on a registry
        // smaller than the fixture.
        awaitRecordCarrying(client, 0L, "client_dim_registered", "\"dim\":" + DIM_A + ",",
                "a joining client must be told the server's planets: PlanetA (" + DIM_A + ")",
                LINK_BUDGET_TICKS);
        awaitRecordCarrying(client, 0L, "client_dim_registered", "\"dim\":" + DIM_B + ",",
                "a joining client must be told the server's planets: PlanetB (" + DIM_B + ")",
                LINK_BUDGET_TICKS);
        int before = clientArDimCount();
        assertTrue("client must have AR dimensions synced while connected (got " + before + ")",
                before > 0);

        // Mark BEFORE the kick: the disconnect and the clear are one call apart on a netty thread,
        // and a mark taken afterwards could not tell "already cleared" from "never cleared".
        long kickMark = client.mark();

        // Kick the (single) connected player by name: a real remote disconnect that
        // keeps the client JVM alive at the disconnect screen so its AR dimension
        // registry can still be read. (`kick @a` does not resolve a single target.)
        String list = String.join("\n", serverHarness.client().execute("list"));
        String tail = list.contains(":") ? list.substring(list.lastIndexOf(':') + 1) : list;
        String[] names = tail.trim().split("[\\s,]+");
        String player = names.length > 0 && !names[0].isEmpty() ? names[0] : "@a";
        String kicked = String.join("\n",
                serverHarness.client().execute("kick " + player + " c031-remote-disconnect"));

        // THE CONTRACT, as the chain production commits it: the mod's disconnect handler ran, and
        // then — from inside it — the registry was emptied. The order is production's own (the
        // record is taken at the handler's HEAD, the clear is a call it makes), and it is the part
        // the old form could not check at all: it inferred "the client left" from a screen name,
        // which a CRASHED client satisfies just as well, and then read the registry's failure to
        // shrink as a contract failure.
        client.assertChain(kickMark, "leaving a REMOTE server must clear the client's AR dimension"
                        + " registry, so the previous server's planets cannot linger as ghosts"
                        + " (kick='" + kicked.trim() + "', list='" + list.trim() + "')",
                LINK_BUDGET_TICKS, "client_disconnected", "client_dimensions_unregistered");

        String disconnects = client.since(kickMark, "client_disconnected");
        assertTrue("the clearing branch is guarded on the server being REMOTE, so a run in which"
                        + " the client reports remote:false has not exercised this contract at all: "
                        + disconnects, disconnects.contains("\"remote\":true"));

        // "Cleared nothing" and "never cleared" are different facts and the record keeps them apart:
        // the sizes are read at the clear's HEAD, so this is what the registry held going in.
        String cleared = client.since(kickMark, "client_dimensions_unregistered");
        int clearedDims = firstInt(cleared, "dims");
        assertTrue("the clear must have had this server's dimensions to remove — a clear of an"
                        + " already-empty registry proves nothing about the ghost it exists to"
                        + " prevent: " + cleared, clearedDims > 0);

        // The end state, read from production's own accessor. It is polled briefly and not read
        // once because the event above is recorded at the clear's HEAD, one statement BEFORE the
        // maps are emptied — the chain says the clear RAN, this says the registry is EMPTY.
        int after = before;
        for (int waited = 0; waited < 100 && after != 0; waited += 5) {
            clientHarness.bot().waitTicks(5);
            after = clientArDimCount();
        }
        assertEquals("leaving a remote server must clear the client's AR dimension "
                        + "registry (was " + before + ", the clear recorded " + cleared + ")",
                0, after);
    }
}
