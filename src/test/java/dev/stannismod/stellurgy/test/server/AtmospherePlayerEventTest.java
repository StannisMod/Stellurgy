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
 * What the per-entity atmosphere gate answers for a player, and that the answer is the one belonging
 * to the dimension he is STANDING IN — server tier.
 *
 * <p>These three used to assert the shape of a per-player cache: that a dim populated it, that a
 * dimension change cleared it. That cache is gone — it existed only so an edge-triggered sync packet
 * could compare against the previous answer, and the sync is periodic now — and asserting its
 * bookkeeping was pinning an implementation detail in the first place. What a player can actually
 * feel is the resolution itself, so that is what is asserted here: the gate answers his current
 * dimension's air, and nothing of the dimension he left survives the move.</p>
 *
 * <p>Player supply: {@code ensure-fake} (cross-dim moves fire the same
 * {@code PlayerChangedDimensionEvent} Forge's transfer fires);
 * {@code tick-living} supplies the per-tick {@code LivingUpdateEvent}
 * cadence {@code AtmosphereHandler.onTick} subscribes to.</p>
 *
 * <p>One server for the class, over the two planets {@link Galaxy} declares. The fake player is
 * shared, so every scenario that waits for a resolution first takes him through the overworld
 * ({@link #startFromTheOverworld}): resolved as breathable there, he owes a change of answer on the
 * airless planet, which is what guarantees the resolution it waits for is recorded.</p>
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

    private static final String PLAYER_ATMOS = "atmosphere";
    private static final String PLAYER_BREATHABLE = "breathable";

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
                + "            <atmosphereDensity>" + atmosDensity + "</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    /** This class's reader of the server's ordered event log. */
    private Events serverEvents() {
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
     * The shared fake player starts this scenario resolved as breathable in the overworld, whatever
     * world a sibling scenario left him in — so the move to an airless planet that follows is a
     * change of answer, which is what the instrument records.
     */
    private void startFromTheOverworld() throws Exception {
        enterDim(0, OVERWORLD_UPDATES);
        // EXPERIMENT: the dose is OVERWORLD_UPDATES living updates in the overworld; the ticker posts
        // one per server tick and then stops, so overshoot delivers none extra.
        GameTicks.advance(client(), GameTicks.server(), OVERWORLD_UPDATES + TICK_SLACK);
    }

    /**
     * Stations the fake player in {@code dim} and waits for that dimension's handler to RESOLVE him,
     * on the record it emits ({@code player_atmosphere_changed}, carrying the resolver's dim).
     *
     * <p>The record is an EDGE, and every caller arrives with one owed: he starts resolved as
     * breathable in the overworld ({@link #startFromTheOverworld}), so going airless changes the
     * answer, and going on from airless to breathable changes it again. So the wait cannot expire on
     * a healthy path. Marked before the station,
     * because the first living update may resolve him before a later mark could be taken.</p>
     */
    private String enterDimAndAwaitResolution(int dim) throws Exception {
        Events events = serverEvents();
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
     * The overworld is breathable, and the gate says so for a player standing in it.
     *
     * <p>red-witnessed: with {@code AtmosphereHandler#getAtmosphereType} at
     * {@code if (StellurgyConfiguration.getCurrentConfig().enableOxygen)} preceded by a return of
     * VACUUM for dimension 0, this reads {@code breathable=false}, 2026-09-28. Removing the handler's own
     * dimension check instead stays GREEN — no other world's handler exists in this scenario to
     * answer for the overworld.</p>
     *
     * <p>red-witnessed: with {@code AtmosphereHandler#getAtmosphereType(Entity)} at
     * {@code return DimensionManager.getInstance().getDimensionProperties(dimId).getAtmosphere()}
     * answering VACUUM for dimension 0: "overworld baseline: cache must be empty or non-Stellurgy;
     * hasCached=true atmos=vacuum", 2026-09-28 (taken on the other line of this test before the two
     * were merged).</p>
     */
    @Test
    public void aPlayerInTheOverworldResolvesBreathableAir() throws Exception {
        enterDim(0, OVERWORLD_UPDATES);
        // EXPERIMENT: the dose is OVERWORLD_UPDATES living updates in the overworld. The ticker
        // posts one per server tick and then stops, so this many server ticks (plus the slack)
        // deliver all of them; overshoot delivers none extra, so the verdict does not depend on the
        // box's speed.
        GameTicks.advance(client(), GameTicks.server(), OVERWORLD_UPDATES + TICK_SLACK);
        String resp = exec("stellurgytest atmosphere for-player");
        assertFalse("the gate must answer SOMETHING for a player in the overworld: " + resp,
                field(PLAYER_ATMOS, resp).isEmpty());
        assertEquals("the overworld must resolve as breathable for a player standing in it: " + resp,
                "true", field(PLAYER_BREATHABLE, resp));
    }

    /**
     * An airless planet resolves as unbreathable for a player standing on it.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. SOMETHING — {@code AtmosphereHandler#getAtmosphereType} at {@code return DimensionManager.getInstance().getDimensionProperties(dimId).getAtmosphere();} answering nothing for an unbreathable dimension: "the gate must answer
     * SOMETHING for a player on a Stellurgy planet: … \"hasAtmosphere\":false". UNBREATHABLE — the
     * same line answering {@code AIR} for every dimension: "a planet declared with zero atmosphere
     * must resolve as unbreathable … \"breathable\":true".</p>
     */
    @Test
    public void aPlayerOnAnAirlessPlanetResolvesUnbreathableAir() throws Exception {
        startFromTheOverworld();
        enterDimAndAwaitResolution(DIM_VAC);
        String resp = exec("stellurgytest atmosphere for-player");
        assertFalse("the gate must answer SOMETHING for a player on a Stellurgy planet: " + resp,
                field(PLAYER_ATMOS, resp).isEmpty());
        assertEquals("a planet declared with zero atmosphere must resolve as unbreathable for a "
                + "player standing on it: " + resp, "false", field(PLAYER_BREATHABLE, resp));
    }

    /**
     * Dim change clears the entry; the new dim repopulates with its own.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, all at {@code AtmosphereHandler#getAtmosphereType} at {@code return DimensionManager.getInstance().getDimensionProperties(dimId).getAtmosphere();}.
     * SOMETHING BEFORE — answering nothing for an unbreathable dimension: "the airless planet must
     * resolve to SOMETHING before the move". UNBREATHABLE BEFORE — answering {@code AIR} everywhere:
     * "the airless planet must resolve as unbreathable before the move: … \"breathable\":true".
     * BREATHABLE AFTER — answering {@code NoO2} for a breathable planet: "the breathable planet must
     * resolve as breathable after the move: … \"NoO2\"".</p>
     *
     * <p>Not asserted: which dimension's handler resolved him after the move, because the wait
     * selects its record by that very field, so reading it back could not disagree; and that the
     * airless planet's atmosphere does not survive the move, because it is implied by the two
     * breathability verdicts either side of it.</p>
     */
    @Test
    public void aDimChangeMakesAPlayerResolveTheNewDimsAir() throws Exception {
        startFromTheOverworld();
        enterDimAndAwaitResolution(DIM_VAC);
        String onVacuum = exec("stellurgytest atmosphere for-player");
        String atmoVac = field(PLAYER_ATMOS, onVacuum);
        assertFalse("the airless planet must resolve to SOMETHING before the move: " + onVacuum,
                atmoVac.isEmpty());
        assertEquals("the airless planet must resolve as unbreathable before the move: " + onVacuum,
                "false", field(PLAYER_BREATHABLE, onVacuum));

        // THE MOVE ITSELF is the link this waits on: the arriving dimension's own handler resolving
        // him. That record is what separates "he arrived and was re-resolved" from "he arrived and
        // nobody asked" — the state read below cannot tell those apart, because a gate that never ran
        // leaves the same answer standing.
        enterDimAndAwaitResolution(DIM_AIR);

        String onAir = exec("stellurgytest atmosphere for-player");
        assertEquals("the breathable planet must resolve as breathable after the move: " + onAir,
                "true", field(PLAYER_BREATHABLE, onAir));
    }
}
