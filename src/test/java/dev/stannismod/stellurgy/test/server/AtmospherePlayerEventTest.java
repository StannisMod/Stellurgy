package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.client.GameDirSeed;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code AtmosphereHandler} per-player cache bookkeeping — server tier.
 * Relabeled down the pyramid from the old client-harness
 * {@code AtmospherePlayerEventE2ETest} the
 * contract (onTick populates {@code prevAtmosphere} for players in Stellurgy dims;
 * {@code onPlayerChangeDim} clears the entry so the next dim repopulates)
 * is server-side handler state the old test read through server probes
 * anyway.
 *
 * <p>Player supply: {@code ensure-fake} (cross-dim moves fire the same
 * {@code PlayerChangedDimensionEvent} Forge's transfer fires);
 * {@code tick-living} supplies the per-tick {@code LivingUpdateEvent}
 * cadence {@code AtmosphereHandler.onTick} subscribes to.</p>
 *
 * <p>One server for the class, over the two planets {@link Galaxy} declares. The fake player is
 * shared, so every scenario that waits for a resolution first takes him through the overworld
 * ({@link #startFromTheOverworld}): the dim change clears his entry, which is what guarantees the
 * resolution it waits for is a change and therefore recorded.</p>
 */
@SeededWorld(AtmospherePlayerEventTest.Galaxy.class)
public class AtmospherePlayerEventTest extends AbstractSharedServerTest {

    /**
     * Server ticks granted beyond the living updates requested. The handler resolves inside the
     * update's own event, so nothing trails it; the slack only covers the command that starts the
     * ticker landing a tick before or after the clock read that starts the advance.
     */
    private static final int TICK_SLACK = 10;

    /** Living updates the overworld baseline is given — the dose its negative claim is about. */
    private static final int OVERWORLD_UPDATES = 10;

    /** Server ticks a handler is given to resolve a freshly stationed player: its first living
     *  update resolves him, so this is a deadline and never spent on a healthy run. */
    private static final int RESOLVE_TICKS = 200;

    private static final int DIM_VAC = 9411;
    private static final int DIM_AIR = 9412;

    private static final String HAS_CACHED = "hasCachedAtmosphere";
    private static final String CACHED_ATMOS = "cachedAtmosphere";

    /** A vacuum planet and a breathable one, otherwise identical. */
    public static final class Galaxy implements WorldSeed {
        @Override
        public void seed(GameDirSeed seed) {
            seed.planetDefs("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<galaxy>\n"
                    + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                    + "          isBlackHole=\"false\" diskAngle=\"70\" "
                    + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                    + planetXml("VacuumPlanet", DIM_VAC, 0)
                    + planetXml("AirPlanet", DIM_AIR, 100)
                    + "    </star>\n"
                    + "</galaxy>\n", AtmospherePlayerEventTest.class);
        }
    }

    private static String planetXml(String name, int dim, int atmosDensity) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
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
                + "            <atmosphereDensity>" + atmosDensity + "</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    /** This class's reader of the server's ordered event log. */
    private Events events() {
        return new Events(this::exec,
                ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());
    }

    /** Stations the fake player in {@code dim} and starts {@code ticks} living updates there. */
    private void enterDim(int dim, int ticks) throws Exception {
        String fake = exec("stellurgytest player ensure-fake " + dim + " 8.5 120 8.5");
        assertTrue("ensure-fake must succeed: " + fake, Reply.of(fake).ok());
        assertTrue(Reply.of(exec("stellurgytest player tick-living " + ticks)).ok());
    }

    /**
     * The shared fake player starts this scenario in the overworld, with his entry cleared by the dim
     * change — whatever world and cache a sibling scenario left him with.
     */
    private void startFromTheOverworld() throws Exception {
        enterDim(0, 0);
    }

    /**
     * Stations the fake player in {@code dim} and waits for that dimension's handler to RESOLVE him,
     * on the cache write it records ({@code player_atmosphere_changed}, carrying the resolver's dim).
     *
     * <p>The write happens only on a change, and every caller arrives with a change owed: the scenario
     * began in the overworld with his entry cleared, and the one move this class makes — vacuum to
     * breathable — both clears his entry and changes the answer. Marked before the station, because
     * the first living update may resolve him before a later mark could be taken.</p>
     */
    private String enterDimAndAwaitResolution(int dim) throws Exception {
        Events events = events();
        long mark = events.markInstrumented();
        enterDim(dim, 40);
        // Answers the LAST matching record itself, not a `since` reply.
        return events.awaitRecordWithFields(mark, "player_atmosphere_changed",
                "the atmosphere handler of dim " + dim + " must resolve the player standing in it",
                RESOLVE_TICKS, "dim", String.valueOf(dim));
    }

    private String field(String field, String src) {
        String value = Reply.of(src).text(field);
        return value;
    }

    /**
     * Overworld baseline: no Stellurgy atmosphere may be cached for the player.
     *
     * <p>red-witnessed: with {@code AtmosphereHandler#getAtmosphereType(Entity)} at
     * {@code return DimensionManager.getInstance().getDimensionProperties(dimId).getAtmosphere()}
     * answering VACUUM for dimension 0: "overworld baseline: cache must be empty or non-Stellurgy;
     * hasCached=true atmos=vacuum", 2026-09-28; re-taken 2026-10-04 on the shared server, same text
     * plus {@code "cachedInDims":[0]}. NOT YET for the other break: with
     * {@code AtmosphereHandler#onTick} at {@code entity.world.provider.getDimension() == this.dimId}
     * removed, this stays GREEN — re-taken 2026-10-04 on the shared server, where both resolving
     * scenarios ran before this one (class run 3/3 green with the break in place). The earlier reason,
     * "no other world's handler exists", no longer describes the arrangement; why the other worlds'
     * handlers still cache nothing for an overworld player is not measured.</p>
     */
    @Test
    public void stellurgyDimWithoutVisitDoesNotCacheAtmosphereForPlayer() throws Exception {
        enterDim(0, OVERWORLD_UPDATES);
        // EXPERIMENT: the dose is OVERWORLD_UPDATES living updates in the overworld, and the claim
        // below is about what they left in the cache. The ticker posts one per server tick and then
        // stops, so this many server ticks (plus the slack) deliver all of them; overshoot delivers
        // none extra, so the verdict does not depend on the box's speed.
        GameTicks.advance(client(), GameTicks.server(), OVERWORLD_UPDATES + TICK_SLACK);
        String cache = exec("stellurgytest atmosphere cached-for-player");
        String has = field(HAS_CACHED, cache);
        String atmos = field(CACHED_ATMOS, cache);
        assertTrue("overworld baseline: cache must be empty or non-Stellurgy; hasCached=" + has
                + " atmos=" + atmos + " " + cache,
                "false".equals(has) || atmos.isEmpty() || !atmos.contains("vacuum"));
    }

    /** Ticking in a Stellurgy dim populates the per-player cache. */
    @Test
    public void stellurgyDimTickPopulatesPerPlayerCache() throws Exception {
        startFromTheOverworld();
        enterDimAndAwaitResolution(DIM_VAC);
        String cache = exec("stellurgytest atmosphere cached-for-player");
        assertEquals("after >=1 living-update in a Stellurgy dim the per-player cache "
                + "MUST be populated; cache=" + cache, "true", field(HAS_CACHED, cache));
        assertFalse("cached atmosphere name must be non-empty: " + cache,
                field(CACHED_ATMOS, cache).isEmpty());
    }

    /** Dim change clears the entry; the new dim repopulates with its own. */
    @Test
    public void dimChangeClearsAtmosphereCacheForPlayer() throws Exception {
        startFromTheOverworld();
        enterDimAndAwaitResolution(DIM_VAC);
        String cacheVac = exec("stellurgytest atmosphere cached-for-player");
        String atmoVac = field(CACHED_ATMOS, cacheVac);
        assertFalse("vacuum-dim cache must populate before the dim change: " + cacheVac,
                atmoVac.isEmpty());

        // Straight from vacuum to air, never through the overworld: the clear asserted below has to
        // be the one THIS dim change makes.
        String airResolution = enterDimAndAwaitResolution(DIM_AIR);
        // THE CLEAR ITSELF, read off the write that followed it. A cache entry that survived the
        // dim change is still overwritten here — air differs from the cached vacuum — so the two
        // cached names below differ with or without the clear. What only the clear produces is the
        // write finding NOTHING in the slot: the record's `from` is "none" exactly then.
        assertEquals("the dim change must CLEAR the player's cached atmosphere before the breathable"
                        + " dim resolves him - the write found " + Events.text(airResolution, "from")
                        + " in his slot: " + airResolution,
                "none", Events.text(airResolution, "from"));
        String cacheAir = exec("stellurgytest atmosphere cached-for-player");
        String atmoAir = field(CACHED_ATMOS, cacheAir);
        assertFalse("breathable-dim cache must repopulate after dim change: " + cacheAir,
                atmoAir.isEmpty());
        assertFalse("the vacuum-dim atmosphere must NOT carry over into the breathable "
                + "dim's cache slot (onPlayerChangeDim must clear); vacuumAtmos=" + atmoVac
                + " breathableAtmos=" + atmoAir, atmoVac.equals(atmoAir));
    }
}
