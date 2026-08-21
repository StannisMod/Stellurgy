package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * The live bot-sleep e2e for per-dimension time + planetary dawn rounding
 * (dercodeKoenig/AdvancedRocketry#66) — the player-truth layer the
 * unit ({@code SleepWakeTimeTest}) and integration
 * ({@code ARDimensionWorldInfoTest}) pins could not reach before the
 * framework grew {@code interact_block}.
 *
 * <p>A real client player stands on an AR planet whose day is
 * {@code rotationalPeriod = 30000} ticks (deliberately ≠ 24000), right-clicks
 * a real bed at planet-night, and falls asleep through the production
 * {@code trySleep} path. The sleep skip must then land on the PLANET's next
 * dawn — a multiple of 30000, where vanilla's hard-coded rounding would put
 * 24000 (still night on this planet, the original #66 symptom) — and the
 * overworld's clock must not move beyond normal ticking, proving the per-dim
 * clock isolation.</p>
 */
public class PlanetBedSleepE2ETest {

    private static final int DIM = 9501;
    private static final int ROTATIONAL_PERIOD = 30000;
    private static final String PLAYER = "ForgeTestClient";

    /** Mid-air stone platform well above worldgen terrain — no mobs, flat, deterministic. */
    private static final int PLAT_Y = 150;
    private static final int BED_X = 8, BED_Y = PLAT_Y + 1, BED_FOOT_Z = 9, BED_HEAD_Z = 10;

    private Path workDir;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-client-bed-sleep-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"SleepPlanet\" DIMID=\"" + DIM + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>" + ROTATIONAL_PERIOD + "</rotationalPeriod>\n"
                + "            <atmosphereDensity>100</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
        // The skip this test is ABOUT is off by default now: a planet's day is the turning of a body
        // in an orbit, and a bed no longer fast-forwards it. This class pins the other side of that
        // flag — with the arcade mechanic opted back in, the skip must still land on the PLANET's
        // own dawn rather than vanilla's 24000 rounding. The locked default is pinned by
        // PlanetBedSleepLockedE2ETest; both sides are needed, or a build that ignored the flag
        // entirely would satisfy whichever one happened to match its hard-coded behaviour.
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"),
                ("# seeded by PlanetBedSleepE2ETest\n"
                        + "planet {\n"
                        + "    B:allowTimeSkipOnPlanets=true\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startupException) {
            try {
                serverHarness.close();
            } catch (Exception cleanup) {
                startupException.addSuppressed(cleanup);
            }
            serverHarness = null;
            throw startupException;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                deferred = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            serverHarness = null;
        }
        if (deferred != null) throw deferred;
    }

    @Test
    public void sleepingOnPlanetSkipsToPlanetaryDawnOnly() throws Exception {
        clientHarness.bot().waitForWorld();

        // trySleep's mob scan (±8 around the bed) must stay empty; the mid-air
        // platform handles existing worldgen mobs, this handles new spawns.
        serverHarness.client().execute("gamerule doMobSpawning false");

        // Load + pin the planet, then stage the sleeping site: stone platform
        // and a bed (foot at z=9, head at z=10, both facing south — meta 0/8).
        // EVERY arrangement step is now ASSERTED. None of them was, and that is why this class was
        // red for a month with a message about the world CLOCK: the staging silently did not stage,
        // the player ended up 87 blocks below the bed, and the server dropped his right-click on its
        // reach check — which it does WITHOUT telling anybody, while the client still reports
        // SUCCESS because its own prediction ran. Measured 2026-08-21: `posY:64.0` against a bed at
        // 151, with an empty chat and breathable air.
        String weather = exec("artest weather set " + DIM + " clear 12000");
        assertTrue("ARRANGEMENT: could not clear the planet's weather: " + weather,
                weather.contains("\"ok\":true"));
        String platform = exec("artest fill " + DIM + " 4 " + PLAT_Y + " 4 12 " + PLAT_Y
                + " 12 minecraft:stone");
        assertTrue("ARRANGEMENT: the sleeping platform was not built, so there is nothing to lie"
                + " down on: " + platform, platform.contains("\"ok\":true"));
        String foot = exec("artest place " + DIM + " " + BED_X + " " + BED_Y + " " + BED_FOOT_Z
                + " minecraft:bed 0");
        assertTrue("ARRANGEMENT: the bed's FOOT was not placed: " + foot, foot.contains("\"ok\":true"));
        String head = exec("artest place " + DIM + " " + BED_X + " " + BED_Y + " " + BED_HEAD_Z
                + " minecraft:bed 8");
        assertTrue("ARRANGEMENT: the bed's HEAD was not placed: " + head, head.contains("\"ok\":true"));

        exec("artest tp " + DIM);
        waitForClientDim(DIM);

        // WAIT FOR THE CLIENT TO HAVE THE PLATFORM before standing on it. Movement in Minecraft is
        // driven by the client: a client that has not received these chunks yet simulates a fall
        // through blocks the server has, and its movement packets carry the SERVER's player down
        // with them. Measured 2026-08-21: teleported to y=151, read back at 142.6 twenty ticks
        // later (free fall) and at 64 by the time the sleep was attempted. Waiting on the arrival
        // of the block is waiting for the precondition; a longer fixed sleep would only be a guess.
        // WAIT FOR THE EVENT, not for a value and not for a number of ticks. The client records
        // `chunk_data_applied` at the TAIL of its chunk-data handler — the first instant it can see
        // these blocks. No Forge event reports that moment: the load event fires on an EMPTY chunk,
        // before the data is applied, so a recorder on it would confidently report a chunk that
        // contains nothing.
        //
        // He is teleported inside the wait because chunks stream around where the player IS: waiting
        // before putting him there waits for nothing, and once he is there he falls out of range of
        // the very chunk he is waiting for. This is a wait for a PRECONDITION; the previous form
        // waited 20 ticks and hoped, which is why this class was red for a month.
        long chunkMark = readLong(clientHarness.bot().eventMark().toString(), "seq");
        assertTrue("ARRANGEMENT: the client event recorder is not running, so an empty log below"
                + " would mean nothing: " + clientHarness.bot().eventMark(),
                clientHarness.bot().eventMark().get("recording").getAsBoolean());
        String chunkSeen = "";
        for (int attempt = 0; attempt < 60; attempt++) {
            exec("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
            clientHarness.bot().waitTicks(5);
            chunkSeen = clientHarness.bot()
                    .eventsSince(chunkMark, "chunk_data_applied").toString();
            if (chunkSeen.contains("\"cx\":0") && chunkSeen.contains("\"cz\":0")) {
                break;
            }
        }
        assertTrue("ARRANGEMENT: the client never applied the platform's chunk data, so it keeps"
                + " simulating a fall through blocks the server has: " + chunkSeen,
                chunkSeen.contains("\"cx\":0") && chunkSeen.contains("\"cz\":0"));

        // Vanilla console /tp (same-dim) puts the player on the platform, a
        // bed-reach-range step north of the bed head (|Δz| = 2.5 ≤ 3).
        exec("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
        clientHarness.bot().waitTicks(20);

        // AND HE MUST STILL BE THERE. A /tp onto a platform that is not there drops him to the
        // terrain, and every downstream observation then describes a player standing in a field
        // somewhere else. The bed reach is ~4.5 blocks, so anything past a couple of blocks of
        // settle has already broken the scenario.
        String where = exec("artest oxygen player " + PLAYER);
        double standingY = readDouble(where, "posY");
        assertTrue("ARRANGEMENT: the player is not on the sleeping platform (expected y≈" + BED_Y
                        + ", got " + standingY + "). He cannot reach the bed from there and the"
                        + " server will drop his right-click on its reach check without a word: "
                        + where,
                Math.abs(standingY - BED_Y) < 2.0);

        // Night on every clock: vanilla /time set writes ALL loaded worlds, and
        // on the wrapped planet that lands in the per-dim state. Phase
        // 20000/30000 ≈ 0.67 is night on the planet; 20000/24000 is night in
        // the overworld.
        serverHarness.client().execute("time set 20000");
        clientHarness.bot().waitTicks(30); // let skylightSubtracted catch up (isDaytime gate)

        JsonObject before = dimTime(DIM);
        long staged = before.get("worldTime").getAsLong();
        assertTrue("planet clock must be at the staged night time (~20000, tick drift "
                + "tolerated): " + before, staged >= 20000 && staged < 22000);

        // The real player right-clicks the bed foot (server normalizes to the
        // head) -> production trySleep -> fully asleep after 100 ticks -> the
        // sleep skip runs WorldServer's setWorldTime through MixinWorldServer's
        // rotationalPeriod rounding.
        // THE MARK, taken BEFORE the click. Everything the server records from here on is readable
        // afterwards, so nothing can be missed between two samples and there is no race at the start
        // — which is what a poll on the world clock could never give this test.
        zmaster587.advancedRocketry.test.Events events = new zmaster587.advancedRocketry.test.Events(
                this::exec, clientHarness.bot()::waitTicks);
        long mark = events.mark();

        JsonObject click = clientHarness.bot().interactBlock(BED_X, BED_Y, BED_FOOT_Z);
        assertTrue("bed right-click must not error: " + click, click.has("result"));

        // THE CHAIN, in order. `result:SUCCESS` above is the CLIENT's own prediction and says
        // nothing about the server; `right_click_block` is only recorded if the click actually
        // reached it, and the reach check drops a far-away click before that event is ever fired.
        // So a failure here names WHICH link broke instead of reporting a world clock that did not
        // move: for a month this test could only say `worldTime=20622`.
        events.assertChain(mark, "a player who right-clicks a bed at planet-night must reach the"
                        + " server, be offered the bed, and wake from it", 260,
                "right_click_block", "sleep_in_bed", "player_wake_up");

        // WHY THERE IS NO "is he asleep now?" POLL HERE ANY MORE.
        //
        // There was one, and the chain above made it fail on a HEALTHY run — which is the clearest
        // demonstration of what a poll cannot do. Sleeping is TRANSIENT: it lasts about a hundred
        // ticks. `assertChain` legitimately waits until `player_wake_up`, by which time the state a
        // poll would sample is over, and the poll reports "he never got into the bed" about a player
        // who slept and woke. The event log recorded all three events; the poll saw none of them.
        //
        // `sleep_in_bed` and `player_wake_up` in the chain are the same fact, asserted where it
        // cannot evaporate between two samples.

        // Poll for the planetary dawn: next multiple of 30000 after 20000 is
        // exactly 30000. Vanilla's hard-coded rounding would give 24000 —
        // mid-night on this planet — which the modulo assertion rejects.
        long planetTime = waitForPlanetDawn();
        assertTrue("sleep skip must land at/after the next planetary dawn (30000), got "
                + planetTime, planetTime >= ROTATIONAL_PERIOD);
        assertTrue("sleep skip must land ON planetary dawn (multiple of " + ROTATIONAL_PERIOD
                        + ", vanilla 24000-rounding would miss it): " + planetTime,
                planetTime % ROTATIONAL_PERIOD < 2400);

        // Per-dim isolation: the overworld's clock keeps ticking from 20000 —
        // the planet's sleep skip must NOT touch it.
        long overworldTime = dimTime(0).get("worldTime").getAsLong();
        assertTrue("overworld clock must be unaffected by the planet's sleep skip "
                        + "(expected ~20000 + elapsed, got " + overworldTime + ")",
                overworldTime >= 20000 && overworldTime < 24000);
    }

    /** One probe call, joined — the arrangement asserts on these replies rather than discarding them. */
    private String exec(String command) throws Exception {
        return String.join("\n", serverHarness.client().execute(command));
    }

    /** A long field of a JSON reply, failing loudly rather than substituting a plausible zero. */
    private static long readLong(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("expected \"" + key + "\" in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    /** A numeric field of a probe reply, failing loudly rather than substituting a plausible zero. */
    private static double readDouble(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":(-?[0-9.eE+\\-]+)").matcher(json);
        assertTrue("expected a number \"" + key + "\" in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private JsonObject dimTime(int dim) throws Exception {
        String raw = String.join("\n",
                serverHarness.client().execute("artest dim time " + dim));
        int start = raw.indexOf('{');
        assertTrue("dim time probe must return JSON: " + raw, start >= 0);
        return new JsonParser().parse(raw.substring(start)).getAsJsonObject();
    }

    /** Polls ~30 s for the planet clock to jump past the staged night (sleep takes 100+ ticks). */
    private long waitForPlanetDawn() throws Exception {
        long last = -1;
        for (int waited = 0; waited < 600; waited += 20) {
            last = dimTime(DIM).get("worldTime").getAsLong();
            if (last >= ROTATIONAL_PERIOD) {
                return last;
            }
            clientHarness.bot().waitTicks(20);
        }
        throw new AssertionError("planet never reached its dawn — either the player "
                + "never fell asleep (trySleep rejected?) or the sleep skip landed off "
                + "planetary dawn (vanilla 24000-rounding instead of rotationalPeriod); "
                + "last planet worldTime=" + last);
    }

    private void waitForClientDim(int expectedDim) throws Exception {
        for (int waited = 0; waited < 200; waited += 10) {
            clientHarness.bot().waitTicks(10);
            JsonObject w = clientHarness.bot().reportWeather();
            if (w != null && w.has("dim") && w.get("dim").getAsInt() == expectedDim) {
                return;
            }
        }
        throw new AssertionError("client never reached dim " + expectedDim
                + " (last weather report: " + clientHarness.bot().reportWeather() + ")");
    }
}
