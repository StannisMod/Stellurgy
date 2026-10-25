package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.ServerOnly;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Both sides of {@code allowTimeSkipOnPlanets}, on one client: a bed on a planet must set your spawn
 * and wake you, and whether it also brings the MORNING is the flag's to decide (C16 CLOCK-7).
 *
 * <ul>
 *   <li><b>Locked.</b> The player sleeps, is TOLD this world's morning is not coming, and the planet's
 *       time of day does not jump.</li>
 *   <li><b>Allowed (the shipped default).</b> The sleep moves the universe: the space clock jumps, every
 *       body's time of day with it, and the planet wakes at its OWN dawn — a multiple of its
 *       {@code rotationalPeriod} — where vanilla's hard-coded rounding would put 24000, still night on a
 *       30000-tick day (dercodeKoenig/AdvancedRocketry#66).</li>
 * </ul>
 *
 * <p>Night is staged by moving the universe ({@code time add}) until the planet reads it: a body's time of
 * day is its turn at the space clock, so no world's counter can be set alone.</p>
 *
 * <p><b>Neither side is a witness alone</b>, which is why they belong in one class rather than two: a
 * build that ignored the flag entirely would satisfy whichever of them happened to match its
 * hard-coded behaviour. Here the same client runs both, minutes apart.</p>
 *
 * <h2>Why this could be merged at all</h2>
 *
 * <p>These were two classes, each paying its own server + client boot, and the reason recorded for it
 * was that each writes its own {@code stellurgy.cfg} — one with the flag true, one with it
 * false. That is a conflict between two whole-FILE writes, not between two tests: the flag is read at
 * every use ({@code TimeSkipPolicy.allows}) and is on the whitelist of {@code stellurgytest config set},
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
public class PlanetBedSleepClientGroupTest extends AbstractSharedClientTest {

    @Override
    protected String subsystem() {
        return "planet-bed-sleep";
    }

    /** The time-locked planet — the shipped default's side. */
    private static final int DIM_LOCKED = 9502;

    /** Passes of a planet's own clock after a `time set` — one recomputes the skylight the bed's
     *  daytime gate reads, and the second makes sure a pass began after the write. */
    private static final int SKYLIGHT_WORLD_TICKS = 2;
    /** The planet the skip is allowed on. */
    private static final int DIM_SKIP = 9501;

    private static final int ROTATIONAL_PERIOD = 30000;

    /**
     * The band the STAGED night time must fall in, in ticks.
     *
     * <p>The lower end is the arrangement's own number — the clock is set to 20 000 — and the width
     * is the test's: a few hundred ticks pass between staging and reading, so the gate tolerates
     * drift without tolerating a clock that was never staged.</p>
     */
    private static final int STAGED_NIGHT_TICKS = 20000;
    /** @see #STAGED_NIGHT_TICKS */
    private static final int STAGED_NIGHT_DRIFT_TICKS = 2000;

    /**
     * How near a multiple of the planet's own rotational period the clock must land, in ticks, for
     * the skip to have landed ON planetary dawn.
     *
     * <p>The TEST'S OWN: the defect it exists for is vanilla rounding the skip to its own 24 000,
     * which lands the planet's clock nowhere near its dawn. A tenth of the period is far inside
     * that error and far outside tick drift.</p>
     */
    private static final int PLANETARY_DAWN_TOLERANCE_TICKS = 2400;

    /** A vanilla day, in ticks — the overworld clock's own wrap, cited so the unaffected-clock gate
     *  reads as the wrap it is rather than as a tuned bound. */
    private static final int VANILLA_DAY_TICKS = 24000;

    /**
     * How near the sleeping platform the player must stand, in blocks, to be able to reach the bed.
     *
     * <p>The TEST'S OWN: the server drops a right-click from further away, so this is the
     * arrangement asserting what production is about to require.</p>
     */
    private static final double ON_THE_PLATFORM_BLOCKS = 2.0;
    private static final String PLAYER = "ForgeTestClient";
    private static final String FLAG = "allowTimeSkipOnPlanets";

    /** Mid-air stone platform well above worldgen terrain — no mobs, flat, deterministic. */
    private static final int PLAT_Y = 150;
    private static final int BED_X = 8, BED_Y = PLAT_Y + 1, BED_FOOT_Z = 9, BED_HEAD_Z = 10;

    /** Planet-night for a 30000-tick day: phase 0.67, well clear of both dawn and dusk. */
    private static final long STAGED_NIGHT = 20000L;
    /** How far a clock may drift while the scenario runs and still count as "it did not jump". */
    private static final long DRIFT_ALLOWANCE = 2000L;

    /**
     * How long the skip policy's verdict may take to appear after the bed is clicked, in ticks.
     *
     * <p>A deadline for a discrete decision, not a settle: a vanilla sleep completes 100 ticks after
     * everyone is in bed, and the policy is asked at that completion — so this is that, with room for
     * a loaded box. The chain above has already established the sleep itself, so an expiry here is
     * the policy never being asked rather than a player who never got into bed.</p>
     */
    private static final int SLEEP_VERDICT_BUDGET_TICKS = 400;

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
        setFlag(true);
    }

    // ── locked: a bed does not bring a planet's morning ──────────────────────────────────────────

    @Pins("CLOCK-7")
    @RedWitness(verdict = "locked", member = "TimeSkip#nightEnds",
            at = "return true; // the lock's own path: the sleepers are told when dawn is and woken, nothing moves",
            replacedBy = "skip(world.getMinecraftServer(), TimeOfDay.ticksToDawn(world).orElse(0L)); return true;",
            fails = "THE CONTRACT: a bed may not fast-forward a planet's day", on = "2026-10-24")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advanceWorld(serverClient(), DIM_LOCKED,"
            + " SKYLIGHT_WORLD_TICKS);",
            why = "trySleep's daytime gate reads skylightSubtracted, which WorldServer.tick recomputes from the"
                    + " clock on every pass of THAT world — so a pass of the planet's own clock is the whole of"
                    + " what is needed, counted there rather than on the client.")
    @ServerOnly(at = "GameTicks.advanceWorld(serverClient(), DIM_LOCKED, SKYLIGHT_WORLD_TICKS);",
            why = "the daytime gate is recomputed on the planet world's own pass; the read is the server's world"
                    + " time.")
    public void sleepingOnAPlanetSetsSpawnAndWakesYouWithoutBringingMorning() throws Exception {
        arrange("gamerule doMobSpawning false");
        stageSleepingSite(DIM_LOCKED);

        // Planet-night by moving the universe — the planet's time of day is its turn at the space clock —
        // then lock the planet's skip before anyone sleeps.
        stageNight(DIM_LOCKED);
        setFlag(false);
        GameTicks.advanceWorld(serverClient(), DIM_LOCKED, SKYLIGHT_WORLD_TICKS);

        long staged = dimTime(DIM_LOCKED);
        scenario().requireArranged("the staging must have reached the planet's own clock, or the"
                        + " player sleeps in daylight and nothing below is a measurement. got="
                        + staged, Math.floorMod(staged - STAGED_NIGHT, (long) ROTATIONAL_PERIOD) < DRIFT_ALLOWANCE);

        // THE MARK, before the click. The assertion below used to name its own ambiguity and refuse
        // to resolve it — "either the lock did not engage or the player never actually slept" — and
        // it was right to refuse, because nothing it could read told the two apart. The chain does:
        // if he never slept, `sleep_in_bed`/`player_wake_up` are simply absent, and the failure says
        // so instead of leaving the reader with two candidates.
        dev.stannismod.stellurgy.test.Events events = serverEvents();
        long mark = events.mark();

        JsonObject click = bot().interactBlock(BED_X, BED_Y, BED_FOOT_Z);
        assertTrue("bed right-click must not error: " + click, click.has("result"));

        // He must reach the server, be offered the bed, and WAKE from it. Only then does the absence
        // of the message below mean what the assertion says it means.
        events.assertChain(mark, "a player who right-clicks a bed on a time-locked planet must still"
                        + " sleep in it — the lock withholds the MORNING, not the bed", 260,
                "right_click_block", "sleep_in_bed", "player_wake_up");

        // THE LOAD-BEARING ONE, and it is the POLICY'S OWN VERDICT rather than the sentence the
        // player reads. `allows` is asked from inside the completed-sleep redirect, so a record for
        // this dimension carrying `allowed:false` says both halves at once: a sleep got that far, and
        // the skip was refused on this world.
        //
        // A thirty-iteration poll of the client's chat stood here, matching a fragment of the line
        // the refusal produces. That line is a rendering of this decision: it moves when the language
        // file moves, it names no dimension, and a client that has scrolled past it reads identically
        // to one that was never sent.
        String decided = events.awaitMatching(mark, "time_skip_decided",
                seen -> dev.stannismod.stellurgy.test.Events
                        .recordsWhere(seen, "dim", String.valueOf(DIM_LOCKED)).stream()
                        .anyMatch(decision -> "false".equals(
                                dev.stannismod.stellurgy.test.Events.text(decision, "allowed"))),
                "refusing the skip on dim " + DIM_LOCKED,
                "a bed on a time-locked planet must have its skip REFUSED by the policy — the lock"
                        + " withholds the morning, and this is the decision that withholds it",
                SLEEP_VERDICT_BUDGET_TICKS);
        scenario().record("timeSkipDecision", decided);

        long after = dimTime(DIM_LOCKED);
        assertTrue("THE CONTRACT: a bed may not fast-forward a planet's day. The clock was at "
                        + staged + " before the sleep and " + after + " after it — a jump to this"
                        + " planet's next dawn would be " + ROTATIONAL_PERIOD + ", six times the"
                        + " allowance",
                after - staged <= DRIFT_ALLOWANCE);
    }

    // ── the shipped default: the sleep moves the universe to the PLANET's dawn ──────────────────

    /**
     * A completed sleep on a planet whose skip is allowed — the only player on the server, so the server-wide
     * mode is met — moves the universe (C16 CLOCK-7): the space clock jumps against the other planet's age, that
     * planet's time of day moves with it although nobody slept there, and the sleeper's planet wakes at its own
     * dawn, the next multiple of its rotational period.
     */
    @Pins("CLOCK-7")
    @RedWitness(verdict = "the universe moved", member = "TimeSkip#nightEnds",
            at = "skip(server, skip.getAsLong());", replacedBy = "",
            fails = "CLOCK-7: the sleep moved the universe", on = "2026-10-24")
    @RedWitness(verdict = "dawn", member = "TimeSkip#skipFor",
            at = "earliest = Math.min(earliest, part.dawn.getAsLong());", replacedBy = "earliest = Math.min(earliest, 24000L);",
            fails = "sleep skip must land ON planetary dawn", on = "2026-10-24")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advanceWorld(serverClient(), DIM_SKIP,"
            + " SKYLIGHT_WORLD_TICKS);",
            why = "the same daytime gate, recomputed on a pass of the planet's own clock.")
    @ServerOnly(at = "GameTicks.advanceWorld(serverClient(), DIM_SKIP, SKYLIGHT_WORLD_TICKS);",
            why = "the daytime gate is recomputed on the planet world's own pass; the read is the server's world"
                    + " time.")
    public void sleepingOnPlanetSkipsToPlanetaryDawnOnly() throws Exception {
        setFlag(true);

        // trySleep's mob scan (±8 around the bed) must stay empty; the mid-air platform handles
        // existing worldgen mobs, this handles new spawns.
        arrange("gamerule doMobSpawning false").raw();
        stageSleepingSite(DIM_SKIP);

        // Planet-night, by moving the universe until the planet reads it (phase 20000/30000 ≈ 0.67).
        stageNight(DIM_SKIP);
        GameTicks.advanceWorld(serverClient(), DIM_SKIP, SKYLIGHT_WORLD_TICKS);
        // The other world of this galaxy, where nobody sleeps: the overworld is no body here — the catalogue
        // names no Earth — so it keeps vanilla's counter, and the world that must follow is the other planet.
        JsonObject otherBefore = dimTimeJson(DIM_LOCKED);

        JsonObject before = dimTimeJson(DIM_SKIP);
        long staged = before.get("worldTime").getAsLong();
        scenario().requireArranged("planet clock must be at the staged night time (~20000 within its day, tick "
                + "drift tolerated): " + before,
                Math.floorMod(staged - STAGED_NIGHT_TICKS, (long) ROTATIONAL_PERIOD) < STAGED_NIGHT_DRIFT_TICKS);

        // The real player right-clicks the bed foot (server normalizes to the head) -> production
        // trySleep -> fully asleep after 100 ticks -> the sleep skip runs WorldServer's setWorldTime
        // through MixinWorldServer's rotationalPeriod rounding.
        // THE MARK, taken BEFORE the click. Everything the server records from here on is readable
        // afterwards, so nothing can be missed between two samples and there is no race at the start
        // — which is what a poll on the world clock could never give this test.
        dev.stannismod.stellurgy.test.Events events = serverEvents();
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

        // THE SKIP ITSELF, as the policy allowed it from inside the sleep redirect. `allows` is asked
        // there and the redirect writes the clock on the next line, synchronously, in the same tick
        // (`MixinWorldServer`, then vanilla's `wakeAllPlayers`) — so once this record exists the
        // clock has already been written and ONE read is the measurement. Narrowed by the caller: a
        // probe and the `/time` guard ask the same policy about the same world.
        String skipped = events.awaitMatching(mark, "time_skip_decided",
                seen -> dev.stannismod.stellurgy.test.Events
                        .recordsWhere(seen, "dim", String.valueOf(DIM_SKIP)).stream()
                        .anyMatch(decision -> "true".equals(
                                        dev.stannismod.stellurgy.test.Events.text(decision, "allowed"))
                                && String.valueOf(dev.stannismod.stellurgy.test.Events
                                        .text(decision, "caller"))
                                        .contains("roundSleepWakeToRotationalPeriod")),
                "the sleep redirect allowing the skip on dim " + DIM_SKIP,
                "a completed sleep on a planet whose skip is allowed must reach the policy from the"
                        + " sleep redirect and be ALLOWED — without it the clock below was never"
                        + " asked to move",
                SLEEP_VERDICT_BUDGET_TICKS);
        scenario().record("timeSkipDecision", skipped);

        // The universe moved: the space clock against that world's age — which no skip moves — jumped, and its
        // time of day moved with the clock, staying the clock plus its constant.
        JsonObject otherAfter = dimTimeJson(DIM_LOCKED);
        long skip = (otherAfter.get("spaceClock").getAsLong() - otherAfter.get("totalTime").getAsLong())
                - (otherBefore.get("spaceClock").getAsLong() - otherBefore.get("totalTime").getAsLong());
        long apart = Math.floorMod((otherAfter.get("worldTime").getAsLong() - otherBefore.get("worldTime").getAsLong())
                - (otherAfter.get("spaceClock").getAsLong() - otherBefore.get("spaceClock").getAsLong()), (long) ROTATIONAL_PERIOD);
        assertTrue("CLOCK-7: the sleep moved the universe — the space clock against the other planet's age moved "
                + skip + ": before " + otherBefore + " | after " + otherAfter, skip > 0L);
        assertEquals("CLOCK-7: the other planet's time of day moved with the universe: before " + otherBefore
                + " | after " + otherAfter, 0L, apart);

        // Next multiple of 30000 after 20000 is exactly 30000. A skip of anything but the planet's own
        // dawn — vanilla's 24000 among them — lands elsewhere in its day, which the modulo rejects.
        long planetTime = dimTime(DIM_SKIP);
        assertTrue("sleep skip must land ON planetary dawn (multiple of " + ROTATIONAL_PERIOD
                        + ", vanilla 24000-rounding would miss it): " + planetTime,
                Math.floorMod(planetTime, (long) ROTATIONAL_PERIOD) < PLANETARY_DAWN_TOLERANCE_TICKS);
    }

    /**
     * Move the universe forward until {@code dim} reads {@link #STAGED_NIGHT}: its time of day is its body's
     * turn at the space clock, so the clock is what is moved, by what this world's own read says is missing.
     */
    private void stageNight(int dim) throws Exception {
        long now = dimTime(dim);
        arrange("time add " + Math.floorMod(STAGED_NIGHT - now, (long) ROTATIONAL_PERIOD));
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
    @Wait(shape = Wait.Shape.WINDOW, at = "bot().waitWorldTicks(20);",
            why = "a player's position is his client's to move, so a platform that is not there shows as the fall"
                    + " his client simulates over these ticks.")
    private void stageSleepingSite(int dim) throws Exception {
        String weather = arrange("stellurgytest weather set " + dim + " clear 12000").raw();
        scenario().requireArranged("could not clear the planet's weather: " + weather,
                Reply.of(weather).ok());
        String platform = arrange("stellurgytest fill " + dim + " 4 " + PLAT_Y + " 4 12 " + PLAT_Y
                + " 12 minecraft:stone").raw();
        scenario().requireArranged("the sleeping platform was not built, so there is nothing to lie"
                + " down on: " + platform, Reply.of(platform).ok());
        String foot = arrange("stellurgytest place " + dim + " " + BED_X + " " + BED_Y + " " + BED_FOOT_Z
                + " minecraft:bed 0").raw();
        scenario().requireArranged("the bed's FOOT was not placed: " + foot,
                Reply.of(foot).ok());
        String head = arrange("stellurgytest place " + dim + " " + BED_X + " " + BED_Y + " " + BED_HEAD_Z
                + " minecraft:bed 8").raw();
        scenario().requireArranged("the bed's HEAD was not placed: " + head,
                Reply.of(head).ok());

        long transferMark = clientEvents().mark();
        arrange("stellurgytest tp " + dim);
        awaitClientDim(transferMark, dim);

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
        // The adapter's mark makes the recorder check itself; it is caught and RE-TYPED here,
        // because a recorder that is not running is this scenario's ARRANGEMENT failing and must not
        // read as the contract under test breaking.
        long chunkMark;
        try {
            chunkMark = clientEvents().mark();
        } catch (AssertionError notArranged) {
            scenario().arrangementFailed(notArranged.getMessage());
            return; // unreachable: arrangementFailed always throws
        }
        // The re-sent teleport is the STIMULUS, not the wait: the chunk is sent because the player
        // keeps arriving, so the loop's iterations are doing work rather than watching a clock.
        // ONE record for the chunk at (0, 0) — two separate field tests are satisfied by a record
        // for (0, 9) beside one for (7, 0).
        try {
            clientEvents().awaitMatching(chunkMark, "chunk_data_applied",
                    reply -> Events.anyRecordHasAll(reply, "cx", "0", "cz", "0"),
                    "for the chunk at (0, 0)",
                    "the platform's chunk data never reached this client", 300,
                    () -> arrange("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5").raw());
        } catch (AssertionError never) {
            scenario().arrangementFailed("the client never applied the platform's chunk data, so it"
                    + " keeps simulating a fall through blocks the server has: "
                    + never.getMessage());
        }

        // Vanilla console /tp (same-dim) puts the player on the platform, a bed-reach-range step
        // north of the bed head (|Δz| = 2.5 ≤ 3).
        long placedMark = clientEvents().mark();
        arrange("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
        awaitClientPlacedNear(placedMark, 8.5, 7.5,
                "the player's own client must stand him on the platform - his movement is his");
        bot().waitWorldTicks(20);

        // AND HE MUST STILL BE THERE. A /tp onto a platform that is not there drops him to the
        // terrain, and every downstream observation then describes a player standing in a field
        // somewhere else. The bed reach is ~4.5 blocks, so anything past a couple of blocks of settle
        // has already broken the scenario.
        String where = read("stellurgytest oxygen player " + PLAYER).raw();
        double standingY = readDouble(where, "posY");
        scenario().requireArranged("the player is not on the sleeping platform (expected y≈" + BED_Y
                        + ", got " + standingY + "). He cannot reach the bed from there and the"
                        + " server will drop his right-click on its reach check without a word: "
                        + where,
                Math.abs(standingY - BED_Y) < ON_THE_PLATFORM_BLOCKS);
        scenario().record("stagedOn", dim).record("standingY", standingY);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Set the time-skip flag and READ IT BACK. The read-back is the point: this is the one global
     * both scenarios move, and a set that silently did not take would make each of them measure the
     * other one's contract.
     */
    private void setFlag(boolean allowed) throws Exception {
        String set = arrange("stellurgytest config set " + FLAG + " " + allowed).raw();
        scenario().requireArranged("the time-skip flag must actually be " + allowed
                + " — every claim in this class is about which side of it is in force: " + set,
                String.valueOf(allowed).equals(Reply.of(set).text("newValue")));
    }

    // `pollForLockedMessage` lived here: thirty rounds of twenty ticks, joining the client's last
    // twenty chat lines and looking for a fragment of the sentence a locked bed produces. It is gone
    // — the scenario waits on `time_skip_decided`, the policy's own verdict, which names the world it
    // was asked about and cannot be scrolled past.

    private JsonObject dimTimeJson(int dim) throws Exception {
        String raw = arrange("stellurgytest dim time " + dim).raw();
        int start = raw.indexOf('{');
        assertTrue("dim time probe must return JSON: " + raw, start >= 0);
        return new JsonParser().parse(raw.substring(start)).getAsJsonObject();
    }

    private long dimTime(int dim) throws Exception {
        return dimTimeJson(dim).get("worldTime").getAsLong();
    }

    /**
     * The client is IN {@code expectedDim}, waited for as the RESPAWN packet that puts it there —
     * {@link ClientEvents#awaitDim}, which is where the wait and its narrative live.
     *
     * <p>What is local is the TYPING: a client that never arrived has measured nothing about the
     * sleep these scenarios are for, so the failure is an arrangement's and not an assertion's.</p>
     *
     * @param transferMark the CLIENT's own mark, taken BEFORE the command that transfers him
     */
    private void awaitClientDim(long transferMark, int expectedDim) throws Exception {
        try {
            ClientEvents.awaitDim(clientEvents(), transferMark, expectedDim,
                    "everything below is about what he sees there", DIM_LINK_BUDGET_TICKS,
                    () -> "last weather report: " + bot().reportWeather());
        } catch (AssertionError never) {
            scenario().arrangementFailed(never.getMessage());
        }
    }

    /** How long the client is given to FOLLOW a transfer the server has already performed: one
     *  round trip plus a world teardown and rebuild. The old poll's own ceiling. */
    private static final int DIM_LINK_BUDGET_TICKS = 200;

    /** A numeric field of a probe reply, failing loudly rather than substituting a plausible zero. */
    private static double readDouble(String json, String key) {
        Reply reply = Reply.of(json);
        assertTrue("expected a number \"" + key + "\" in: " + json, reply.has(key));
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return reply.numberOr(key, Double.NaN);
    }
}
