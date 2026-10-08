package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import org.junit.Test;

import dev.stannismod.stellurgy.test.DimInfo;
import dev.stannismod.stellurgy.test.DimWeather;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a real client is told about the planet worlds it joins, enters and leaves: each planet's own
 * weather, the spawn point of the world it is in, and a galaxy that leaves with the connection.
 *
 * <p>NEW-GROUP: the client's replicated state of authored planet worlds. Every scenario needs planets
 * declared in {@code planetDefs.xml} BEFORE the server boots, which no existing client group seeds;
 * the four source classes each ran their own server and client for it. One catalogue carries all of
 * them ({@link #seedGameDirectory}), every scenario on dimensions of its own.</p>
 *
 * <p>What a scenario changes it puts back: the world spawn, the overworld's and its planets' weather,
 * an op grant, and — for the scenario whose subject is being kicked — the client's connection.</p>
 *
 * <p>Source classes, method names preserved: {@code ClientDimensionClearOnDisconnectE2ETest},
 * {@code SpawnPointReachesClientE2ETest}, {@code WeatherClientSyncE2ETest},
 * {@code WeatherCommandRedirectE2ETest} — mechanics tests driven by probes (a kick, a transfer, an op
 * grant), so none of them is a player's path and none keeps {@code E2E} in a name.</p>
 */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class PlanetWorldOnTheClientGroupTest extends AbstractSharedClientTest {

    /** The framework's single-client username; the op grant and the kick key on it. */
    private static final String PLAYER = "ForgeTestClient";

    // Weather sync: A raining, B clear, C NEVER touched before its own leg (its WorldServer must be
    // constructed mid-teleport while the overworld rains).
    private static final int SYNC_A = 9301;
    private static final int SYNC_B = 9302;
    private static final int SYNC_C = 9303;
    /** The planet a player types {@code /weather} on. */
    private static final int REDIRECT_DIM = 9304;
    /** The planet a transfer must carry the spawn point to. */
    private static final int SPAWN_DIM = 9401;
    /** Two planets the client is told on joining, and must forget on leaving. */
    private static final int LEAVE_A = 9701;
    private static final int LEAVE_B = 9702;
    private static final String DM_CLASS = "dev.stannismod.stellurgy.dimension.DimensionManager";

    // Both spawn Ys are deliberately not 64: a generated overworld spawn always has Y == 64 on
    // level-type=DEFAULT, and the client placeholder is (8,64,8).
    private static final int SPAWN_A_X = 1337, SPAWN_A_Y = FixtureSite.OPEN_AIR_Y, SPAWN_A_Z = -424;
    private static final int SPAWN_B_X = -2048, SPAWN_B_Y = FixtureSite.OPEN_AIR_Y, SPAWN_B_Z = 777;

    /** Deadlines for discrete client hand-offs — a transfer's far side, a told rain, a told spawn. */
    private static final int RAIN_LINK_BUDGET_TICKS = 200;
    private static final int SPAWN_LINK_BUDGET_TICKS = 200;
    private static final int SKY_LINK_BUDGET_TICKS = 200;
    /** One link of the disconnect chain: a round trip and the client's own handling of it. */
    private static final int LEAVE_LINK_BUDGET_TICKS = 400;
    /** The phantom fade ran for about five seconds: 100 ticks of the world it would run in. */
    private static final int FADE_WINDOW_TICKS = 100;

    @Override
    protected String subsystem() {
        return "planet-world-client";
    }

    @Override
    protected void seedGameDirectory(GameDirSeed seed) {
        seed.planetDefs("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"7\" numGasGiants=\"0\">\n"
                + planetXml("ClientPlanetA", SYNC_A, 100)
                + planetXml("ClientPlanetB", SYNC_B, 100)
                + planetXml("ClientPlanetC", SYNC_C, 100)
                + planetXml("RedirectPlanet", REDIRECT_DIM, 100)
                + planetXml("SpawnProbePlanet", SPAWN_DIM, 100)
                + planetXml("PlanetA", LEAVE_A, 0)
                + planetXml("PlanetB", LEAVE_B, 0)
                + "    </star>\n"
                + "</galaxy>\n", getClass());
    }

    private static String planetXml(String name, int dim, int atmosphereDensity) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>" + dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU + "</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>24000</rotationalPeriod>\n"
                + "            <atmosphereDensity>" + atmosphereDensity + "</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    // ── leaving a server ──────────────────────────────────────────────────────────────────────

    /**
     * Leaving a REMOTE server withdraws the dimension registrations its planets made on the client,
     * and the client then holds no galaxy at all — the galaxy belongs to the connection.
     *
     * <p>{@code PlanetEventHandler.connectToServer} lacked {@code @SubscribeEvent}, and the live
     * {@code disconnected} handler had its {@code unregisterAllDimensions()} commented out, so a
     * client leaving a server kept Stellurgy's client-side dimension list; since {@code PacketDimInfo}
     * only merges per id, dims unique to the previous server lingered as ghost planets. The client here
     * is a separate JVM on a dedicated server, so the remote-only guard holds.</p>
     *
     * <p>The scenario takes its own login (a relog, marked first) so the planets it watches arrive in
     * its own window, is kicked, and is put back in the world for the next scenario.</p>
     */
    @Test
    public void remoteDisconnectClearsClientDimensions() throws Exception {
        Events client = clientEvents();
        long joinMark = client.mark();
        bot().reconnect();
        bot().waitForWorld();
        try {
            // BOTH fixture dims by id: "a count above zero" would be satisfied by a build that synced
            // one of the two, and the clear below would then be measured on a smaller registry.
            client.awaitField(joinMark, "client_dim_registered", "dim", LEAVE_A,
                    "a joining client must be told the server's planets: PlanetA (" + LEAVE_A + ")",
                    LEAVE_LINK_BUDGET_TICKS);
            client.awaitField(joinMark, "client_dim_registered", "dim", LEAVE_B,
                    "a joining client must be told the server's planets: PlanetB (" + LEAVE_B + ")",
                    LEAVE_LINK_BUDGET_TICKS);
            JsonObject registered = bot().invokeStaticChain(DM_CLASS, "getInstance,getRegisteredDimensions");
            int before = registered.has("size") ? registered.get("size").getAsInt() : -1;
            assertTrue("client must have Stellurgy dimensions synced while connected (got " + before + ")",
                    before > 0);

            // Mark BEFORE the kick: the disconnect and the clear are one call apart on a netty thread.
            // Both records land after the world is gone.
            Events leaving = clientConnectionEvents();
            long kickMark = leaving.mark();
            String kicked = exec("kick " + PLAYER + " c031-remote-disconnect");

            // The chain production commits: the mod's disconnect handler ran, and from inside it the
            // registry was emptied.
            leaving.assertChain(kickMark, "leaving a REMOTE server must clear the client's Stellurgy dimension"
                            + " registry, so the previous server's planets cannot linger as ghosts"
                            + " (kick='" + kicked.trim() + "')",
                    LEAVE_LINK_BUDGET_TICKS, "client_disconnected", "client_dimensions_unregistered");
            // SILENCE-IS-THE-ANSWER: neither window below is read for an absence — the chain just above
            // required a client_disconnected and a client_dimensions_unregistered after kickMark, so both
            // are non-empty, and what is asserted is a field of a record that is there.
            String disconnects = leaving.since(kickMark, "client_disconnected");
            assertTrue("the clearing branch is guarded on the server being REMOTE, so a run in which the"
                    + " client reports remote:false has not exercised this contract: " + disconnects,
                    Events.anyRecordHas(disconnects, "remote", "true"));
            // The sizes are read at the clear's HEAD: what the registry held going in.
            String cleared = leaving.since(kickMark, "client_dimensions_unregistered");
            String clearedDims = Events.firstField(cleared, "dims");
            assertTrue("the clear must have had this server's dimensions to remove — a clear of an"
                    + " already-empty registry proves nothing about the ghost: " + cleared,
                    clearedDims != null && !clearedDims.trim().isEmpty() && Integer.parseInt(clearedDims.trim()) > 0);

            // Read ONCE from production's own accessor: a client that has left holds no galaxy at all.
            String afterLeaving;
            try {
                afterLeaving = "answered " + bot().invokeStaticChain(DM_CLASS, "getInstance,getRegisteredDimensions");
            } catch (java.io.IOException refused) {
                afterLeaving = refused.getMessage();
            }
            assertTrue("a client that has left a server must hold no galaxy of it (had " + before
                            + " dimensions; the clear recorded " + cleared + "): " + afterLeaving,
                    afterLeaving.contains("No open connection"));
        } finally {
            bot().connect();
            bot().waitForWorld();
        }
    }

    // ── the spawn point ───────────────────────────────────────────────────────────────────────

    /**
     * A player who joins a world has his client told where that world's spawn point is — so the
     * compass points at it from the first frame instead of at the (8,64,8) placeholder.
     *
     * <p>The spawn is moved while NOBODY is connected: {@code /setworldspawn}'s own broadcast reaches
     * nobody then, so the login ({@code PlayerList.updateTimeAndWeatherForPlayer}) is the only carrier.
     * The scenario disconnects the shared client for it and waits for the server's own logout record.</p>
     */
    @Test
    public void joiningPlayerIsToldTheSpawnPointOfTheWorldItJoined() throws Exception {
        DimInfo original = DimInfo.forDim(this::exec, 0);
        try {
            Events server = connectionEvents();
            long logoutMark = server.mark();
            bot().disconnect();
            server.await(logoutMark, "player_logged_out",
                    "the client must have left before the spawn moves, or /setworldspawn's broadcast"
                            + " reaches him and the login carries nothing", SPAWN_LINK_BUDGET_TICKS);
            String set = exec("setworldspawn " + SPAWN_A_X + " " + SPAWN_A_Y + " " + SPAWN_A_Z);
            DimInfo oracle = DimInfo.forDim(this::exec, 0);
            assertTrue("server-side overworld spawn must be the value we set (setworldspawn output="
                            + set + "): " + oracle.raw(),
                    oracle.spawnX() == SPAWN_A_X && oracle.spawnY() == SPAWN_A_Y && oracle.spawnZ() == SPAWN_A_Z);

            long joinMark = clientEvents().mark();
            bot().connect();
            bot().waitForWorld();
            JsonObject spawn = waitForClientSpawn(joinMark, SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);
            assertEquals("client should be in the overworld: " + spawn, 0, spawn.get("dim").getAsInt());
            assertSpawnEquals("client world spawn after login", spawn, SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);
            assertTrue("client player must have a spawn location after login — the same packet writes it: "
                    + spawn, spawn.get("hasBedLocation").getAsBoolean());
            assertEquals("client player spawn X after login: " + spawn, SPAWN_A_X, spawn.get("bedX").getAsInt());
            assertEquals("client player spawn Y after login: " + spawn, SPAWN_A_Y, spawn.get("bedY").getAsInt());
            assertEquals("client player spawn Z after login: " + spawn, SPAWN_A_Z, spawn.get("bedZ").getAsInt());
        } finally {
            exec("setworldspawn " + original.spawnX() + " " + original.spawnY() + " " + original.spawnZ());
        }
    }

    /**
     * A player who crosses a dimension boundary has his client told the destination's spawn point,
     * rather than keeping a placeholder from a freshly constructed client world.
     *
     * <p>red-witnessed: taken on the pre-fix form, with {@code MixinPlayerList} cancelling
     * {@code updateTimeAndWeatherForPlayer} for a copy that dropped vanilla's spawn packet; the mixin now
     * stands as {@code MixinPlayerList#rainFlagInsteadOfLerpedStrength} at
     * {@code return world.getWorldInfo().isRaining()}, redirecting one call instead of replacing the method:
     * "the client must be TOLD the world spawn — no `client_spawn_set` carrying x = -2048 …" at the wait
     * after the transfer, with the positive control before it green, 2026-09-28.</p>
     */
    @Test
    public void transferredPlayerIsToldTheDestinationDimensionSpawnPoint() throws Exception {
        DimInfo original = DimInfo.forDim(this::exec, 0);
        try {
            // Positive control through vanilla's own broadcast: a failure here is the probe or the client.
            long broadcast = clientEvents().mark();
            exec("setworldspawn " + SPAWN_A_X + " " + SPAWN_A_Y + " " + SPAWN_A_Z);
            JsonObject synced = waitForClientSpawn(broadcast, SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);
            assertSpawnEquals("POSITIVE CONTROL: a broadcast SPacketSpawnPosition must reach the client",
                    synced, SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);

            // Now move the spawn SILENTLY — no packet. The client must still hold A.
            long silentMark = clientEvents().mark();
            String silent = exec("stellurgytest dim set-spawn 0 " + SPAWN_B_X + " " + SPAWN_B_Y + " " + SPAWN_B_Z);
            assertTrue("silent set-spawn must have taken effect server-side: " + silent,
                    Reply.of(silent).ok()
                            && String.valueOf(SPAWN_B_X).equals(Reply.of(silent).text("spawnX"))
                            && String.valueOf(SPAWN_B_Y).equals(Reply.of(silent).text("spawnY"))
                            && String.valueOf(SPAWN_B_Z).equals(Reply.of(silent).text("spawnZ")));

            // A planet's WorldInfo delegates spawn to the overworld, so the destination's spawn is B.
            String load = exec("stellurgytest dim load " + SPAWN_DIM);
            assertTrue("destination dim must load before it can be inspected: " + load,
                    Reply.of(load).bool("loaded"));
            DimInfo destOracle = DimInfo.forDim(this::exec, SPAWN_DIM);
            assertTrue("destination server-side spawn must be B: " + destOracle.raw(),
                    destOracle.spawnX() == SPAWN_B_X && destOracle.spawnY() == SPAWN_B_Y
                            && destOracle.spawnZ() == SPAWN_B_Z);

            long toPlanet = clientEvents().mark();
            exec("stellurgytest tp " + SPAWN_DIM);
            awaitClientDim(toPlanet, SPAWN_DIM, "the spawn read below is otherwise the world he LEFT");
            JsonObject onPlanet = waitForClientSpawn(toPlanet, SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z);
            // One connection delivers in order: a packet sent by the silent set-spawn lands BEFORE the
            // respawn the later tp sends, so any B record ordered before the dimension change is a leak.
            String dimChange = clientEvents().since(toPlanet, "client_dimension_changed");
            double dimChangeSeq = Events.number(Events.records(dimChange).get(0), "seq");
            for (String told : Events.recordsWhereAll(clientEvents().since(silentMark, "client_spawn_set"),
                    "x", String.valueOf(SPAWN_B_X), "y", String.valueOf(SPAWN_B_Y),
                    "z", String.valueOf(SPAWN_B_Z))) {
                assertTrue("silent set-spawn must NOT have pushed a packet: the client was told B before it"
                        + " changed dimension. Leaked record " + told + " | dimension change " + dimChange,
                        Events.number(told, "seq") > dimChangeSeq);
            }
            assertEquals("client should be on the planet: " + onPlanet, SPAWN_DIM, onPlanet.get("dim").getAsInt());
            assertSpawnEquals("client world spawn after cross-dim transfer", onPlanet, SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z);

            long toOverworld = clientEvents().mark();
            exec("stellurgytest tp 0");
            awaitClientDim(toOverworld, 0, "the spawn read below is otherwise the world he LEFT");
            assertSpawnEquals("client world spawn after transferring back to the overworld",
                    waitForClientSpawn(toOverworld, SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z), SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z);
        } finally {
            exec("setworldspawn " + original.spawnX() + " " + original.spawnY() + " " + original.spawnZ());
        }
    }

    /**
     * Wait until the CLIENT has been TOLD this spawn triple ({@code client_spawn_set}, written at the
     * tail of {@code handleSpawnPosition}), then read what it holds ONCE.
     */
    private JsonObject waitForClientSpawn(long mark, int x, int y, int z) throws Exception {
        try {
            clientEvents().awaitRecordWithFields(mark, "client_spawn_set", "the client must be TOLD the world spawn",
                    SPAWN_LINK_BUDGET_TICKS, "x", String.valueOf(x), "y", String.valueOf(y), "z", String.valueOf(z));
        } catch (AssertionError never) {
            Events.assertInstrumentRan(clientEvents().since(mark, "client_spawn_set"), "client_spawn_set",
                    "the client's own spawn writes must be observed at all before an absent one can be read"
                            + " as a spawn that never reached it");
            throw new AssertionError(never.getMessage() + " | the client currently holds " + bot().reportSpawn(), never);
        }
        return bot().reportSpawn();
    }

    private static void assertSpawnEquals(String what, JsonObject s, int x, int y, int z) {
        assertEquals(what + " — X: " + s, x, s.get("spawnX").getAsInt());
        assertEquals(what + " — Y: " + s, y, s.get("spawnY").getAsInt());
        assertEquals(what + " — Z: " + s, z, s.get("spawnZ").getAsInt());
    }

    // ── each planet's own weather ─────────────────────────────────────────────────────────────

    /**
     * Each planet keeps its own weather through a real client, and a fresh planet never inherits the
     * overworld's rain.
     *
     * <p>red-witnessed: the fresh-dim packet verdicts and the overworld control, 2026-09-28, one
     * inversion per verdict. THE OVERWORLD CONTROL — dim C's wrap clearing the overworld's own rain flag:
     * "overworld should still be raining". NEVER TOLD IT IS RAINING — {@code
     * PlanetWeatherManager.wrapWorldInfoIfNeeded} no longer re-seeding the rain strength after wrapping:
     * "client must never be told it is raining on fresh clear dim C". NEVER TOLD A STRENGTH — the re-seed
     * ({@code PlanetWeatherManager#wrapWorldInfoIfNeeded} at {@code float rain = wrapped.isRaining() ? 1.0F : 0.0F})
     * leaving dim C at 0.15: "client must never be told a rain strength above 0 on fresh dim C". The
     * told-rain link on A has not been reddened: the rain reaches an arriving client by more paths than
     * any one inversion removed.</p>
     */
    @Test
    public void weatherIsolatedAcrossDimsThroughRealClient() throws Exception {
        try {
            String setA = exec("stellurgytest weather set " + SYNC_A + " rain 12000");
            assertTrue("set rain on dim A failed: " + setA, Reply.of(setA).ok());
            String setB = exec("stellurgytest weather set " + SYNC_B + " clear 12000");
            assertTrue("set clear on dim B failed: " + setB, Reply.of(setB).ok());
            // The wrapper on BOTH dims: without it the isolation below could pass because vanilla shared
            // weather happened to differ on this sample tick.
            DimWeather getA = serverWeather(SYNC_A);
            DimWeather getB = serverWeather(SYNC_B);
            assertTrue("dim A WorldInfo class should be StellurgyDimensionWorldInfo: " + getA.raw(),
                    getA.usesStellurgyWorldInfo());
            assertTrue("dim B WorldInfo class should be StellurgyDimensionWorldInfo: " + getB.raw(),
                    getB.usesStellurgyWorldInfo());
            assertTrue("dim A should be raining after explicit set: " + getA.raw(), getA.raining);
            assertFalse("dim B should NOT be raining after explicit clear: " + getB.raw(), getB.raining);

            Events clientLog = clientEvents();
            long toA = clientLog.mark();
            exec("stellurgytest tp " + SYNC_A);
            awaitClientDim(toA, SYNC_A, "the weather read below is the weather of the world he is IN");
            // rainStrength is server-driven, every ramp step a packet the client applies.
            String toldA = clientLog.awaitMatching(toA, "client_game_state_changed",
                    seen -> ClientEvents.toldRainStrengthAtLeast(seen, 0.05),
                    "telling the client a rain strength of at least 0.05",
                    "the client arriving in raining dim A must be told its rain", RAIN_LINK_BUDGET_TICKS);
            JsonObject onA = bot().reportWeather();
            assertTrue("client should be in dim A after goto: " + onA,
                    onA.has("dim") && onA.get("dim").getAsInt() == SYNC_A);
            assertTrue("client-visible isRaining must be true on dim A: " + onA, onA.get("isRaining").getAsBoolean());
            assertTrue("client rainStrength must climb above 0 on dim A; what it was told: " + toldA,
                    onA.get("rainStrength").getAsFloat() > 0f);

            long toB = clientLog.mark();
            exec("stellurgytest tp " + SYNC_B);
            awaitClientDim(toB, SYNC_B, "the weather read below is the weather of the world he is IN");
            JsonObject onB = bot().reportWeather();
            assertTrue("client should be in dim B after goto: " + onB,
                    onB.has("dim") && onB.get("dim").getAsInt() == SYNC_B);
            assertFalse("client-visible isRaining must be FALSE on dim B (A->B must not carry A's rain): " + onB,
                    onB.get("isRaining").getAsBoolean());
            // An end-raining packet alone leaves the client at strength 1.0; the transfer sync must zero it.
            assertEquals("client rainStrength must be 0 on clear dim B: " + onB,
                    0f, onB.get("rainStrength").getAsFloat(), 0f);
            DimWeather getBAgain = serverWeather(SYNC_B);
            assertTrue("dim B wrapper must persist across teleports: " + getBAgain.raw(),
                    getBAgain.usesStellurgyWorldInfo());
            assertFalse("server-side dim B must remain clear: " + getBAgain.raw(), getBAgain.raining);

            // Phantom fade: dim C's WorldServer is constructed mid-teleport while the OVERWORLD rains, its
            // constructor seeding rainingStrength from the raining overworld unless re-seeded after wrap.
            String setOver = exec("stellurgytest weather set 0 rain 12000");
            assertTrue("set rain on overworld failed: " + setOver, Reply.of(setOver).ok());
            long toC = clientLog.mark();
            exec("stellurgytest tp " + SYNC_C);
            awaitClientDim(toC, SYNC_C, "the weather read below is the weather of the world he is IN");
            // WINDOW: dim C's clock and the client's, which applies what dim C sends. What it cannot see:
            // a packet sent in the window's last tick and not yet applied.
            advanceWorldAndClient(SYNC_C, FADE_WINDOW_TICKS);
            String toldC = clientLog.since(toC, "client_game_state_changed");
            Events.assertInstrumentRan(toldC, "client_game_state_changed",
                    "the client's weather packets must be observed at all before their absence on dim C can"
                            + " be read as dry");
            assertTrue("client must never be told it is raining on fresh clear dim C; game-state packets since"
                            + " the arrival: " + toldC,
                    Events.recordsWhere(toldC, "state", ClientEvents.BEGIN_RAINING_STATE).isEmpty());
            assertFalse("client must never be told a rain strength above 0 on fresh dim C; game-state packets"
                    + " since the arrival: " + toldC, ClientEvents.toldRainStrengthAtLeast(toldC, Double.MIN_VALUE));
            JsonObject onC = bot().reportWeather();
            assertTrue("client should be in dim C at the end of the window: " + onC,
                    onC.has("dim") && onC.get("dim").getAsInt() == SYNC_C);
            assertFalse("client must not see rain on fresh clear dim C: " + onC, onC.get("isRaining").getAsBoolean());
            assertEquals("client rainStrength must be 0 on fresh dim C at the end of the window: " + onC,
                    0f, onC.get("rainStrength").getAsFloat(), 0f);
            DimWeather overAfter = serverWeather(0);
            assertTrue("overworld should still be raining: " + overAfter.raw(), overAfter.raining);
        } finally {
            exec("stellurgytest weather set 0 clear 12000");
        }
    }

    /**
     * A player standing on a planet who types vanilla {@code /weather rain} rains THAT planet and not the
     * overworld ({@code PlanetWeatherEventHandler.redirectWeatherCommand}). Vanilla {@code CommandWeather}
     * hard-codes {@code server.worlds[0]}; a console sender stands in the overworld, so this needs the
     * real chat path with the planet-standing player as sender.
     *
     * <p>red-witnessed: one inversion per leg, 2026-09-28. RAIN — {@code WeatherCommand#execute} at
     * {@code worldinfo.setRaining(true)} not setting the flag: "no `planet_weather_changed` carrying dim =
     * 9304 and raining = true". CLEAR — the clear branch ({@code WeatherCommand#execute} at
     * {@code if ("clear".equals(action))}) writing nothing: "… raining = false was recorded within 200
     * ticks". Dropping only its {@code setRaining(false)} stays GREEN: the {@code setRainTime(0)} beside it
     * makes vanilla's weather cycle flip the flag off on the next tick.</p>
     */
    @Test
    public void slashWeatherOnPlanetRainsThePlanetNotTheOverworld() throws Exception {
        // /weather (and the redirect target) require permission level 2 — granted as an admin would, and
        // taken back at the end: the server is shared with the class run.
        exec("op " + PLAYER);
        try {
            exec("stellurgytest weather set 0 clear 12000");
            exec("stellurgytest weather set " + REDIRECT_DIM + " clear 12000");
            DimWeather before = serverWeather(REDIRECT_DIM);
            assertTrue("planet must be wrapped before the command test: " + before.raw(), before.usesStellurgyWorldInfo());
            assertFalse("planet must start clear: " + before.raw(), before.raining);

            long transferMark = clientEvents().mark();
            exec("stellurgytest tp " + REDIRECT_DIM);
            awaitClientDim(transferMark, REDIRECT_DIM, "he must be standing on the planet before he types the"
                    + " command, since the redirect is keyed to the world he is IN");

            Events server = serverEvents();
            long rainMark = server.markInstrumented();
            long clientRainMark = clientEvents().mark();
            bot().sendChat("/weather rain 600");
            awaitPlanetSky(server, rainMark, true, "the player's /weather rain on a planet must start rain on"
                    + " THAT planet (redirect to /stellurgy weather missing?)");
            DimWeather planetAfter = serverWeather(REDIRECT_DIM);
            assertTrue("planet did not start raining after player /weather rain: " + planetAfter.raw(),
                    planetAfter.raining);
            DimWeather overworld = serverWeather(0);
            assertFalse("player /weather rain on a planet leaked to the overworld (vanilla worlds[0] path,"
                    + " redirect not applied): " + overworld.raw(), overworld.raining);

            // Strength streams per tick (code 7); the begin-raining FLAG (code 1) is broadcast when the
            // server-side strength crosses 0.2 — both packets the client records applying.
            String told = clientEvents().awaitMatching(clientRainMark, "client_game_state_changed",
                    seen -> !Events.recordsWhere(seen, "state", ClientEvents.BEGIN_RAINING_STATE).isEmpty()
                            && ClientEvents.toldRainStrengthAtLeast(seen, 0.25),
                    "telling the client it is raining, at a strength of at least 0.25",
                    "the client standing on the planet must be told the rain its command started",
                    RAIN_LINK_BUDGET_TICKS);
            JsonObject onPlanet = bot().reportWeather();
            assertTrue("client should still be in the planet dim: " + onPlanet,
                    onPlanet.has("dim") && onPlanet.get("dim").getAsInt() == REDIRECT_DIM);
            assertTrue("client-visible isRaining must flip true on the planet: " + onPlanet,
                    onPlanet.get("isRaining").getAsBoolean());
            assertTrue("client rainStrength must start climbing on the planet; what it was told: " + told,
                    onPlanet.get("rainStrength").getAsFloat() > 0f);

            long clearMark = server.markInstrumented();
            bot().sendChat("/weather clear 600");
            awaitPlanetSky(server, clearMark, false, "the player's /weather clear on a planet must end the rain on"
                    + " THAT planet");
            DimWeather overworldAfterClear = serverWeather(0);
            assertFalse("overworld must remain clear after planet /weather clear: " + overworldAfterClear.raw(),
                    overworldAfterClear.raining);
        } finally {
            exec("deop " + PLAYER);
        }
    }

    /** The planet's own weather cycle announcing that its sky became {@code raining} since {@code mark}. */
    private void awaitPlanetSky(Events server, long mark, boolean raining, String what) throws Exception {
        server.awaitRecordWithFields(mark, "planet_weather_changed", what, SKY_LINK_BUDGET_TICKS,
                "dim", String.valueOf(REDIRECT_DIM), "raining", String.valueOf(raining));
    }

    /** What the SERVER says one world's sky is doing, as opposed to what the client is shown. */
    private DimWeather serverWeather(int dim) throws Exception {
        return DimWeather.forDim(this::exec, dim).requireDim(dim);
    }
}
