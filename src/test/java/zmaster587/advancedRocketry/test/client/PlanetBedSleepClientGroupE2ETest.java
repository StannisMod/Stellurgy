package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.assertTrue;

/**
 * Both sides of {@code allowTimeSkipOnPlanets}, on one client: a bed on a planet must set your spawn
 * and wake you, and whether it also brings the MORNING is the flag's to decide.
 *
 * <ul>
 *   <li><b>Locked (the shipped default).</b> The player sleeps, is TOLD this world's morning is not
 *       coming, and the planet's clock does not jump — a planet's day is the turning of a body in an
 *       orbit, and a bed does not fast-forward it.</li>
 *   <li><b>Opted back in.</b> With the arcade mechanic on, the skip must land on the PLANET's own
 *       dawn — a multiple of its {@code rotationalPeriod} — where vanilla's hard-coded rounding would
 *       put 24000, still night on a 30000-tick day. That was the original report
 *       (dercodeKoenig/AdvancedRocketry#66).</li>
 * </ul>
 *
 * <p><b>Neither side is a witness alone</b>, which is why they belong in one class rather than two: a
 * build that ignored the flag entirely would satisfy whichever of them happened to match its
 * hard-coded behaviour. Here the same client runs both, minutes apart.</p>
 *
 * <h2>Why this could be merged at all</h2>
 *
 * <p>These were two classes, each paying its own server + client boot, and the reason recorded for it
 * was that each writes its own {@code advancedRocketry.cfg} — one with the flag true, one with it
 * false. That is a conflict between two whole-FILE writes, not between two tests: the flag is read at
 * every use ({@code TimeSkipPolicy.allows}) and is on the whitelist of {@code artest config set},
 * whose own comment says flipping it at runtime is how both sides of a flag get exercised in one
 * server. The locked scenario was ALREADY flipping it at runtime to stage its night. So each scenario
 * now SETS the side it is about and MEASURES that the set took, the family reset puts the shipped
 * default back, and the only thing that still has to exist before the server boots — the planet
 * catalogue — is declared through {@link #seedGameDirectory} and merged into one galaxy holding both
 * planets.</p>
 *
 * <p>The two scenarios use SEPARATE planets ({@value #DIM_LOCKED} and {@value #DIM_SKIP}) rather than
 * one, deliberately: each keeps the dimension its green runs were taken on, and a clock the other
 * scenario moved can never be mistaken for this one's staging.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PlanetBedSleepClientGroupE2ETest extends AbstractSharedClientE2ETest {

    @Override
    protected String subsystem() {
        return "planet-bed-sleep";
    }

    /** The time-locked planet — the shipped default's side. */
    private static final int DIM_LOCKED = 9502;
    /** The planet the skip is allowed on. */
    private static final int DIM_SKIP = 9501;

    private static final int ROTATIONAL_PERIOD = 30000;
    private static final String PLAYER = "ForgeTestClient";
    private static final String FLAG = "allowTimeSkipOnPlanets";

    /** Mid-air stone platform well above worldgen terrain — no mobs, flat, deterministic. */
    private static final int PLAT_Y = 150;
    private static final int BED_X = 8, BED_Y = PLAT_Y + 1, BED_FOOT_Z = 9, BED_HEAD_Z = 10;

    /** Planet-night for a 30000-tick day: phase 0.67, well clear of both dawn and dusk. */
    private static final long STAGED_NIGHT = 20000L;
    /** How far a clock may drift while the scenario runs and still count as "it did not jump". */
    private static final long DRIFT_ALLOWANCE = 2000L;

    private static final String LOCKED_NEEDLE = "turns at its own rate";

    @Override
    protected void seedGameDirectory(GameDirSeed seed) {
        // ONE catalogue, two planets. The flag is NOT seeded: it is read at every use, so seeding it
        // would pin one side for the whole class and make the other untestable here — which is the
        // shape that kept these two apart.
        seed.planetDefs("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                + planet("SleepPlanet", DIM_SKIP)
                + planet("LockedPlanet", DIM_LOCKED)
                + "    </star>\n"
                + "</galaxy>\n", getClass());
    }

    /** One planet entry, identical but for its name and dimension — the two differ in nothing else. */
    private static String planet(String name, int dim) {
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
                + "            <rotationalPeriod>" + ROTATIONAL_PERIOD + "</rotationalPeriod>\n"
                + "            <atmosphereDensity>100</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    /**
     * The flag is this family's shared mutable, so it goes back to the shipped default between
     * scenarios — and the restore is READ BACK, on the principle the shared base is built on: a reset
     * nobody checks is indistinguishable from no reset. A scenario that needs the other side sets it
     * itself, which is what makes the order of these two methods irrelevant.
     */
    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        setFlag(false);
    }

    // ── the shipped default: a bed does not bring a planet's morning ─────────────────────────────

    @Test
    public void sleepingOnAPlanetSetsSpawnAndWakesYouWithoutBringingMorning() throws Exception {
        // The premise, MEASURED rather than seeded: this scenario is about the default, so a build
        // whose default changed must fail here rather than quietly measure the other side.
        String shipped = exec("artest config get " + FLAG);
        scenario().requireArranged("this scenario is about the SHIPPED default of " + FLAG
                + ", so it reads it instead of writing it: " + shipped,
                shipped.contains("\"value\":false"));

        exec("gamerule doMobSpawning false");
        stageSleepingSite(DIM_LOCKED);

        // Stage planet-night THROUGH the flag, then lock it again before anyone sleeps: staging needs
        // /time set to reach the planet, which is the very thing under test.
        setFlag(true);
        exec("time set " + STAGED_NIGHT);
        setFlag(false);
        bot().waitTicks(30); // skylightSubtracted has to catch up for trySleep

        long staged = dimTime(DIM_LOCKED);
        scenario().requireArranged("the staging must have reached the planet's own clock, or the"
                        + " player sleeps in daylight and nothing below is a measurement. got="
                        + staged, staged >= STAGED_NIGHT && staged < STAGED_NIGHT + DRIFT_ALLOWANCE);

        // THE MARK, before the click. The assertion below used to name its own ambiguity and refuse
        // to resolve it — "either the lock did not engage or the player never actually slept" — and
        // it was right to refuse, because nothing it could read told the two apart. The chain does:
        // if he never slept, `sleep_in_bed`/`player_wake_up` are simply absent, and the failure says
        // so instead of leaving the reader with two candidates.
        zmaster587.advancedRocketry.test.Events events = new zmaster587.advancedRocketry.test.Events(
                this::exec, bot()::waitTicks);
        long mark = events.mark();

        JsonObject click = bot().interactBlock(BED_X, BED_Y, BED_FOOT_Z);
        assertTrue("bed right-click must not error: " + click, click.has("result"));

        // He must reach the server, be offered the bed, and WAKE from it. Only then does the absence
        // of the message below mean what the assertion says it means.
        events.assertChain(mark, "a player who right-clicks a bed on a time-locked planet must still"
                        + " sleep in it — the lock withholds the MORNING, not the bed", 260,
                "right_click_block", "sleep_in_bed", "player_wake_up");

        // A full vanilla sleep completes 100 ticks after everyone is in bed; poll past that.
        String seen = pollForLockedMessage();
        assertTrue("THE LOAD-BEARING ONE: the player must be TOLD that this world's morning is not"
                        + " coming, and that line is only ever sent from inside the completed-sleep"
                        + " branch — so its absence means either the lock did not engage or the"
                        + " player never actually slept — and the chain above has already ruled the"
                        + " second one out, so this is the lock. Historically this test could not"
                        + " tell those two"
                        + " apart by assuming. chat=" + seen,
                seen.contains(LOCKED_NEEDLE));

        long after = dimTime(DIM_LOCKED);
        assertTrue("THE CONTRACT: a bed may not fast-forward a planet's day. The clock was at "
                        + staged + " before the sleep and " + after + " after it — a jump to this"
                        + " planet's next dawn would be " + ROTATIONAL_PERIOD + ", six times the"
                        + " allowance",
                after - staged <= DRIFT_ALLOWANCE);
    }

    // ── opted back in: the skip lands on the PLANET's dawn, not vanilla's ────────────────────────

    @Test
    public void sleepingOnPlanetSkipsToPlanetaryDawnOnly() throws Exception {
        setFlag(true);

        // trySleep's mob scan (±8 around the bed) must stay empty; the mid-air platform handles
        // existing worldgen mobs, this handles new spawns.
        exec("gamerule doMobSpawning false");
        stageSleepingSite(DIM_SKIP);

        // Night on every clock: vanilla /time set writes ALL loaded worlds, and on the wrapped planet
        // that lands in the per-dim state. Phase 20000/30000 ≈ 0.67 is night on the planet;
        // 20000/24000 is night in the overworld.
        exec("time set " + STAGED_NIGHT);
        bot().waitTicks(30); // let skylightSubtracted catch up (isDaytime gate)

        JsonObject before = dimTimeJson(DIM_SKIP);
        long staged = before.get("worldTime").getAsLong();
        assertTrue("planet clock must be at the staged night time (~20000, tick drift "
                + "tolerated): " + before, staged >= 20000 && staged < 22000);

        // The real player right-clicks the bed foot (server normalizes to the head) -> production
        // trySleep -> fully asleep after 100 ticks -> the sleep skip runs WorldServer's setWorldTime
        // through MixinWorldServer's rotationalPeriod rounding.
        // THE MARK, taken BEFORE the click. Everything the server records from here on is readable
        // afterwards, so nothing can be missed between two samples and there is no race at the start
        // — which is what a poll on the world clock could never give this test.
        zmaster587.advancedRocketry.test.Events events = new zmaster587.advancedRocketry.test.Events(
                this::exec, bot()::waitTicks);
        long mark = events.mark();

        JsonObject click = bot().interactBlock(BED_X, BED_Y, BED_FOOT_Z);
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

        // Poll for the planetary dawn: next multiple of 30000 after 20000 is exactly 30000. Vanilla's
        // hard-coded rounding would give 24000 — mid-night on this planet — which the modulo
        // assertion rejects.
        long planetTime = waitForPlanetDawn(DIM_SKIP);
        assertTrue("sleep skip must land at/after the next planetary dawn (30000), got "
                + planetTime, planetTime >= ROTATIONAL_PERIOD);
        assertTrue("sleep skip must land ON planetary dawn (multiple of " + ROTATIONAL_PERIOD
                        + ", vanilla 24000-rounding would miss it): " + planetTime,
                planetTime % ROTATIONAL_PERIOD < 2400);

        // Per-dim isolation: the overworld's clock keeps ticking from 20000 — the planet's sleep skip
        // must NOT touch it.
        long overworldTime = dimTime(0);
        assertTrue("overworld clock must be unaffected by the planet's sleep skip "
                        + "(expected ~20000 + elapsed, got " + overworldTime + ")",
                overworldTime >= 20000 && overworldTime < 24000);
    }

    // ── arrangement, shared by both scenarios ────────────────────────────────────────────────────

    /**
     * Put the player on a stone platform beside a made bed, on {@code dim}, and prove every step of
     * it landed.
     *
     * <p>None of this was asserted once, and that is why one of these scenarios was red for a month
     * with a message about the world CLOCK: the staging silently did not stage, the player ended up
     * 87 blocks below the bed, and the server dropped his right-click on its reach check — which it
     * does WITHOUT telling anybody, while the client still reports SUCCESS because its own prediction
     * ran. Measured 2026-08-21: {@code posY:64.0} against a bed at 151, with an empty chat and
     * breathable air.</p>
     */
    private void stageSleepingSite(int dim) throws Exception {
        String weather = exec("artest weather set " + dim + " clear 12000");
        scenario().requireArranged("could not clear the planet's weather: " + weather,
                weather.contains("\"ok\":true"));
        String platform = exec("artest fill " + dim + " 4 " + PLAT_Y + " 4 12 " + PLAT_Y
                + " 12 minecraft:stone");
        scenario().requireArranged("the sleeping platform was not built, so there is nothing to lie"
                + " down on: " + platform, platform.contains("\"ok\":true"));
        String foot = exec("artest place " + dim + " " + BED_X + " " + BED_Y + " " + BED_FOOT_Z
                + " minecraft:bed 0");
        scenario().requireArranged("the bed's FOOT was not placed: " + foot,
                foot.contains("\"ok\":true"));
        String head = exec("artest place " + dim + " " + BED_X + " " + BED_Y + " " + BED_HEAD_Z
                + " minecraft:bed 8");
        scenario().requireArranged("the bed's HEAD was not placed: " + head,
                head.contains("\"ok\":true"));

        exec("artest tp " + dim);
        waitForClientDim(dim);

        // WAIT FOR THE EVENT the client records when it APPLIES a chunk's data — the first instant it
        // can see these blocks. Movement in Minecraft is client-driven: a client that has not received
        // these chunks yet simulates a fall through blocks the server has, and its movement packets
        // carry the SERVER's player down with them. Measured 2026-08-21: teleported to y=151, read
        // back at 142.6 twenty ticks later (free fall) and at 64 by the time the sleep was attempted.
        // No Forge event reports that moment: the load event fires on an EMPTY chunk, before the data
        // is applied, so a recorder on it would confidently report a chunk that contains nothing.
        //
        // He is teleported INSIDE the wait because chunks stream around where the player IS: waiting
        // before putting him there waits for nothing, and once he is there he falls out of range of
        // the very chunk he is waiting for.
        JsonObject markReply = bot().eventMark();
        long chunkMark = markReply.get("seq").getAsLong();
        scenario().requireArranged("the client event recorder is not running, so an empty log below"
                        + " would mean nothing: " + markReply,
                markReply.get("recording").getAsBoolean());
        String chunkSeen = "";
        for (int attempt = 0; attempt < 60; attempt++) {
            exec("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
            bot().waitTicks(5);
            chunkSeen = bot().eventsSince(chunkMark, "chunk_data_applied").toString();
            if (chunkSeen.contains("\"cx\":0") && chunkSeen.contains("\"cz\":0")) {
                break;
            }
        }
        scenario().requireArranged("the client never applied the platform's chunk data, so it keeps"
                        + " simulating a fall through blocks the server has: " + chunkSeen,
                chunkSeen.contains("\"cx\":0") && chunkSeen.contains("\"cz\":0"));

        // Vanilla console /tp (same-dim) puts the player on the platform, a bed-reach-range step
        // north of the bed head (|Δz| = 2.5 ≤ 3).
        exec("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
        bot().waitTicks(20);

        // AND HE MUST STILL BE THERE. A /tp onto a platform that is not there drops him to the
        // terrain, and every downstream observation then describes a player standing in a field
        // somewhere else. The bed reach is ~4.5 blocks, so anything past a couple of blocks of settle
        // has already broken the scenario.
        String where = exec("artest oxygen player " + PLAYER);
        double standingY = readDouble(where, "posY");
        scenario().requireArranged("the player is not on the sleeping platform (expected y≈" + BED_Y
                        + ", got " + standingY + "). He cannot reach the bed from there and the"
                        + " server will drop his right-click on its reach check without a word: "
                        + where,
                Math.abs(standingY - BED_Y) < 2.0);
        scenario().record("stagedOn", dim).record("standingY", standingY);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Set the time-skip flag and READ IT BACK. The read-back is the point: this is the one global
     * both scenarios move, and a set that silently did not take would make each of them measure the
     * other one's contract.
     */
    private void setFlag(boolean allowed) throws Exception {
        String set = exec("artest config set " + FLAG + " " + allowed);
        scenario().requireArranged("the time-skip flag must actually be " + allowed
                + " — every claim in this class is about which side of it is in force: " + set,
                set.contains("\"newValue\":" + allowed));
    }

    /** The client's own chat, i18n resolved — what the player actually reads. */
    private String pollForLockedMessage() throws Exception {
        StringBuilder last = new StringBuilder();
        for (int attempt = 0; attempt < 30; attempt++) {
            bot().waitTicks(20);
            last.setLength(0);
            JsonArray lines = bot().reportChat(20).getAsJsonArray("lines");
            if (lines != null) {
                for (JsonElement line : lines) {
                    last.append(line.getAsString()).append(" | ");
                }
            }
            if (last.indexOf(LOCKED_NEEDLE) >= 0) {
                break;
            }
        }
        return last.toString();
    }

    private JsonObject dimTimeJson(int dim) throws Exception {
        String raw = exec("artest dim time " + dim);
        int start = raw.indexOf('{');
        assertTrue("dim time probe must return JSON: " + raw, start >= 0);
        return new JsonParser().parse(raw.substring(start)).getAsJsonObject();
    }

    private long dimTime(int dim) throws Exception {
        return dimTimeJson(dim).get("worldTime").getAsLong();
    }

    /** Polls ~30 s for the planet clock to jump past the staged night (sleep takes 100+ ticks). */
    private long waitForPlanetDawn(int dim) throws Exception {
        long last = -1;
        for (int waited = 0; waited < 600; waited += 20) {
            last = dimTime(dim);
            if (last >= ROTATIONAL_PERIOD) {
                return last;
            }
            bot().waitTicks(20);
        }
        throw new AssertionError("planet never reached its dawn — either the player "
                + "never fell asleep (trySleep rejected?) or the sleep skip landed off "
                + "planetary dawn (vanilla 24000-rounding instead of rotationalPeriod); "
                + "last planet worldTime=" + last);
    }

    private void waitForClientDim(int expectedDim) throws Exception {
        for (int waited = 0; waited < 200; waited += 10) {
            bot().waitTicks(10);
            JsonObject w = bot().reportWeather();
            if (w != null && w.has("dim") && w.get("dim").getAsInt() == expectedDim) {
                return;
            }
        }
        scenario().arrangementFailed("client never reached dim " + expectedDim
                + " (last weather report: " + bot().reportWeather() + ")");
    }

    /** A numeric field of a probe reply, failing loudly rather than substituting a plausible zero. */
    private static double readDouble(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":(-?[0-9.eE+\\-]+)").matcher(json);
        assertTrue("expected a number \"" + key + "\" in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }
}
