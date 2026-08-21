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
        // Poll the client's own view WHILE HOLDING HIM THERE. Chunks stream to the client around
        // where the player IS, so waiting before putting him on the platform waits for nothing; and
        // once he is on it he falls, taking himself out of range of the chunk he is waiting for. So
        // the teleport is repeated each iteration — this is a wait for a real precondition, not a
        // tolerance.
        //
        // NOTE ON THE PROBE: its `loaded` field is worthless on the client —
        // `WorldClient.isChunkLoaded` returns `allowEmpty || …` and `isBlockLoaded` passes true, so
        // it is unconditionally true everywhere, and an unreceived chunk reads as AIR from the
        // EmptyChunk. The block identity is the only part of that reply worth reading.
        String clientBlock = "";
        for (int attempt = 0; attempt < 60; attempt++) {
            exec("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
            clientHarness.bot().waitTicks(5);
            clientBlock = clientHarness.bot().blockState(BED_X, PLAT_Y, 7).toString();
            if (clientBlock.contains("stone")) {
                break;
            }
        }
        // Read the client's view AFTER putting him over the platform, not before: the earlier form
        // of this check ran while he was still at the arrival point, so "the client shows air" could
        // equally have meant "he is nowhere near that chunk yet". The server-side pair below is the
        // discriminator that no client reply can supply — the same block out of the player's OWN
        // world and out of `server.getWorld(dim)`, plus whether those are one object.
        exec("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
        clientHarness.bot().waitTicks(5);
        String worldCheck = exec("artest player world-check " + BED_X + " " + PLAT_Y + " 7");
        clientBlock = clientHarness.bot().blockState(BED_X, PLAT_Y, 7).toString();
        assertTrue("ARRANGEMENT: the client never received the sleeping platform, so it will keep"
                + " simulating a fall through it. client=" + clientBlock
                + " worldCheck=" + worldCheck
                // WHICH WORLD IS THE CLIENT LOOKING AT. `blockState` reads mc.world, so a client
                // still rendering the dimension it came FROM answers about that one — and (8,150,7)
                // is air in most of them. This is the last way "the client shows air" can mean
                // something other than a desync.
                + " clientDim=" + clientHarness.bot().reportWeather()
                + " clientState=" + clientHarness.bot().reportState()
                // BOTH SIDES, because "the client has not got it yet" and "it was never written"
                // are different bugs and the client's answer alone cannot tell them apart. The fill
                // reported ok:true either way — that only says the command ran.
                + " server=" + exec("artest space get-block " + DIM + " " + BED_X + " " + PLAT_Y + " 7")
                + " fill=" + platform,
                clientBlock.contains("stone"));

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
        JsonObject click = clientHarness.bot().interactBlock(BED_X, BED_Y, BED_FOOT_Z);
        assertTrue("bed right-click must not error: " + click, click.has("result"));

        // THE PLAYER MUST ACTUALLY GET INTO THE BED, asserted before the clock is ever consulted.
        // Without this the only witness is the world time, and a clock that did not jump reports
        // "the click missed", "trySleep refused" and "the sleep was broken before it completed" with
        // the same silence. Measured 2026-08-21: this test failed for a month at
        // `worldTime=20622` — the staged 20000 plus exactly the ticks the poll ran, i.e. the clock
        // simply ticking — and the message could only offer the reader a choice of two causes.
        String sleepState = awaitSleeping();
        assertTrue("the player never got into the bed, so nothing downstream is about the sleep"
                        + " SKIP at all — it is about the boarding. click=" + click
                        + " state=" + sleepState
                        // vanilla's BlockBed sends every trySleep refusal to the player as a STATUS
                        // message ("you may not rest now…", "you can only sleep at night"), so the
                        // reason is already in his own client. Carrying it here is what turns "he
                        // did not sleep" into a named cause.
                        + " chat=" + clientHarness.bot().reportChat(6)
                        // AR refuses the sleep SILENTLY when the air at the bed is not breathable
                        // (PlanetEventHandler sets SleepResult.OTHER_PROBLEM, and vanilla's BlockBed
                        // sends the player no message for that one result), so the atmosphere is the
                        // only witness that separates it from a vanilla refusal.
                        + " air=" + String.join("\n",
                                serverHarness.client().execute("artest oxygen player " + PLAYER)),
                sleepState.contains("\"sleeping\":true"));

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

    /**
     * Polls for the player to be IN the bed, and returns the last state read either way.
     *
     * <p>Deliberately not an assertion of its own: the caller reports it beside the click result, so
     * a failure says which of the two halves of "he did not sleep" actually happened.</p>
     */
    private String awaitSleeping() throws Exception {
        String last = "";
        for (int waited = 0; waited < 200; waited += 10) {
            last = String.join("\n", serverHarness.client().execute("artest player sleeping"));
            if (last.contains("\"sleeping\":true")) {
                return last;
            }
            clientHarness.bot().waitTicks(10);
        }
        return last;
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
