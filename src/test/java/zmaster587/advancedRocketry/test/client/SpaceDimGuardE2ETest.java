package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.Events;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The space-dim "outside-any-station" teleport guard, both branches.
 *
 * <p>Production: {@link zmaster587.advancedRocketry.event.PlanetEventHandler#playerTick}. Every
 * server tick, if the player is in {@code ARConfiguration.spaceDimId}, NOT inside any registered
 * station's bounds, and NOT riding a rocket, the handler either teleports him to the FURTHEST
 * registered station's {@code getSpawnLocation()}, or — when no
 * {@link zmaster587.advancedRocketry.api.stations.ISpaceObject} is registered at all — transfers him
 * to dim 0 through {@code PlayerList.transferPlayerToDimension} with a {@code TeleporterNoPortal}.</p>
 *
 * <h2>Why this class boots ONE harness for two scenarios, and why it is still its own class</h2>
 *
 * <p>It used to spin up a server and a client per method — 175.1 s for two assertions, measured
 * 2026-08-07 at 8 forks. It now shares one, like the rest of the tier.</p>
 *
 * <p>It does not join a bigger group, and the reason is a constraint no other class here has:
 * <b>{@link #noStationFallbackTeleportsPlayerToOverworld()} requires a world in which no station has
 * ever been registered</b>. Its sibling registers one, so the order matters, and
 * {@code NAME_ASCENDING} delivers it (n &lt; r) — but that is a fact about two names, not a
 * guarantee anyone should lean on silently. So the precondition is ASSERTED as an arrangement step:
 * if a future scenario in this class ever registers a station first, this reddens as ARRANGEMENT
 * (the fixture was wrong) rather than as CONTRACT (production is broken), which are opposite
 * responses.</p>
 *
 * <p>The old class also created its own temp work directory to get "a workdir without persisted
 * station NBT". That bought nothing: {@code RealDedicatedServerHarness.start()} already creates a
 * fresh temp directory per harness, so the custom one was an empty temp dir standing in for an empty
 * temp dir. Dropping it is what let this class share the base at all.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SpaceDimGuardE2ETest extends AbstractSharedClientE2ETest {

    private static final Pattern DIM = Pattern.compile("\"dim\":(-?\\d+)");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.eE+-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.eE+-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.eE+-]+)");
    private static final Pattern SPAWN_X = Pattern.compile("\"spawnX\":(-?\\d+)");
    private static final Pattern SPAWN_Y = Pattern.compile("\"spawnY\":(-?\\d+)");
    private static final Pattern SPAWN_Z = Pattern.compile("\"spawnZ\":(-?\\d+)");
    private static final Pattern STATION_ID = Pattern.compile("\"id\":(-?\\d+)");

    /** {@code ARConfiguration.spaceDimId}'s default. */
    private static final int SPACE_DIM = -2;

    @Override
    protected String subsystem() {
        return "space-dim-guard";
    }

    private int intField(Pattern p, String src, String name) {
        Matcher m = p.matcher(src);
        assertTrue("field " + name + " missing in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }

    private double doubleField(Pattern p, String src, String name) {
        Matcher m = p.matcher(src);
        assertTrue("field " + name + " missing in: " + src, m.find());
        return Double.parseDouble(m.group(1));
    }

    // ── reading the two event logs ────────────────────────────────────────────
    //
    // The guard's contract is a CROSS-SIDE chain: the server hands the body to a teleporter bound
    // for the overworld, and the player's own client is respawned into it. The base's
    // {@link #events()} reads the SERVER log and {@link #clientEvents()} the client's, both behind
    // the same reader — the same mark, the same "is anybody recording" assertion, the same failure
    // narrative on both sides.

    /**
     * Wait for a record of {@code type} that CARRIES {@code needle}, and fail naming the whole chain
     * that DID happen.
     *
     * <p>{@link Events#await} waits for a TYPE, which is the right verb when one occurrence of the
     * type is the link. Here it is not: one scenario produces two {@code teleporter_placed} records —
     * the arrangement's transfer INTO the space dim and the guard's transfer back out of it — so the
     * link is a record with a particular destination in it, not the first record of the type.</p>
     *
     * <p>{@code needle} must end at a field boundary ({@code "dim":0,} rather than {@code "dim":0}):
     * a payload's numbers are not delimited on the right, so a needle without the comma matches every
     * value it is a prefix of.</p>
     */
    private String awaitRecordCarrying(Events events, long mark, String type, String needle,
                                       String what, int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, type);
            if (Events.countRecords(reply, needle) > 0) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying " + needle + " was recorded"
                + " within " + tickBudget + " ticks. What DID happen since the mark: "
                + Events.typesOf(events.since(mark)) + " | raw: " + reply);
    }

    /**
     * How long one link of the guard's chain may take. The guard runs on EVERY {@code
     * LivingUpdateEvent} with no throttle, so what this budgets is a dimension transfer's round trip
     * plus the probe's own, never the decision — a deadline for a discrete event rather than a guess
     * at how long a value takes to settle.
     */
    private static final int GUARD_LINK_BUDGET_TICKS = 200;

    /**
     * With NO registered station, a player who lands in the space dim gets kicked back to the
     * overworld on the next server tick.
     */
    @Test
    public void noStationFallbackTeleportsPlayerToOverworld() throws Exception {
        scenario().arranging("confirm this world has no station in it yet")
                .describeOnFailureWith("artest station list", "artest player health");

        // The guard has two branches and this one is only reachable while the station registry is
        // EMPTY. On a shared world that is a property of the run, not of the boot, so it is checked
        // rather than assumed — see the class javadoc.
        String list = exec("artest station list");
        scenario().requireArranged("this scenario exercises the NO-STATION branch, so the registry"
                + " must still be empty when it runs; it holds: " + list,
                list.contains("\"stations\":[]"));

        String pre = exec("artest player health");
        scenario().requireArranged("baseline must be overworld dim 0; " + pre,
                0 == intField(DIM, pre, "dim"));

        scenario().asserting("the guard transfers a station-less player back to the overworld");
        // BOTH marks before the stimulus, so nothing that happens in between can be missed: the
        // transfer into the space dim and the guard's answer to it are one server tick apart, and a
        // reader that marked afterwards could not tell "already done" from "never happened".
        Events events = events();
        long mark = events.markInstrumented();
        Events clientLog = clientEvents();
        long clientMark = clientLog.mark();

        exec("artest tp " + SPACE_DIM);

        // The guard's own commit, on the server: it hands the body to a BasicTeleporter bound for
        // dim 0 (the no-station branch), and that teleporter's placement is the link. The
        // arrangement's own transfer records the same type with dim -2, which is why this waits for
        // a record CARRYING the destination rather than for the first record of the type.
        String placed = awaitRecordCarrying(events, mark, "teleporter_placed", "\"dim\":0,",
                "a player who lands in the space dim with NO station registered must be put back in"
                        + " the overworld by the guard's own teleporter", GUARD_LINK_BUDGET_TICKS);
        scenario().record("guardPlacement", placed);

        // …and the player-visible half: his own client is respawned into the overworld. Read off the
        // client's record of its dimension changes rather than sampled — a client torn down and
        // rebuilt twice between two samples shows one change or none, and the records show both, in
        // order.
        awaitRecordCarrying(clientLog, clientMark, "client_dimension_changed", "\"dim\":0,",
                "the player whose body the guard moved must SEE the overworld: his client is"
                        + " respawned into it", GUARD_LINK_BUDGET_TICKS);

        String after = exec("artest player health");
        int dim = intField(DIM, after, "dim");
        assertEquals("no-station fallback must transfer player back to overworld; player is in dim "
                + dim + " — " + after, 0, dim);
    }

    /**
     * With a registered station, a player who lands in the space dim outside the station's bounds
     * gets teleported to the station's spawn location — not back to the overworld.
     */
    @Test
    public void registeredStationTeleportTargetsStationSpawn() throws Exception {
        scenario().arranging("create a station orbiting the overworld")
                .describeOnFailureWith("artest station list", "artest player health");
        String createResp = exec("artest station create " + plot().dim);
        scenario().requireArranged("station create must succeed: " + createResp,
                !createResp.contains("\"error\""));
        int stationId = intField(STATION_ID, createResp, "station id");
        scenario().record("stationId", stationId);

        String info = exec("artest station info " + stationId);
        int spawnX = intField(SPAWN_X, info, "spawnX");
        int spawnY = intField(SPAWN_Y, info, "spawnY");
        int spawnZ = intField(SPAWN_Z, info, "spawnZ");
        scenario().record("stationSpawn", spawnX + "," + spawnY + "," + spawnZ);

        // The default space-dim spawn lands in station-id-1's slot (the spiral indexing puts the
        // first station near origin), which would make the guard skip teleporting (the player is
        // "in" the station's slot). So immediately move him 50 000 blocks away — that resolves to a
        // slot index our station does not occupy, so getSpaceStationFromBlockCoords returns null
        // and the guard fires.
        scenario().asserting("the guard puts an out-of-bounds player on the station's spawn");
        Events events = events();
        long mark = events.markInstrumented();
        exec("artest tp " + SPACE_DIM);
        // ARRANGEMENT, and it is a LINK rather than a budget: vanilla /tp moves a body WITHIN the
        // world it is in, so the transfer into the space dim must have landed before the /tp below
        // is issued or the whole scenario runs in the overworld at the right coordinates. The
        // teleporter that carries the transfer records where it put him.
        try {
            awaitRecordCarrying(events, mark, "teleporter_placed", "\"dim\":" + SPACE_DIM + ",",
                    "the arrangement's own transfer into the space dim must land before the body is"
                            + " moved 50 000 blocks WITHIN it", GUARD_LINK_BUDGET_TICKS);
        } catch (AssertionError arrangement) {
            scenario().arrangementFailed(arrangement.getMessage());
        }
        exec("tp @a 50000 100 50000");

        // STILL A POLL, and deliberately: the station branch commits through
        // Entity.setPositionAndUpdate, for which no event exists — the vocabulary's
        // `space_guard_relocated` was deferred, and the only existing witness (`pos_jump`) records
        // a position write only when |Δy| > 16, so a station whose spawnY sits within 16 blocks of
        // y=100 moves 50 000 blocks in X/Z and is recorded nowhere. The wait therefore samples the
        // body's own X, which is a value and not a link; what the migration CAN do is name the
        // branch afterwards, which the absence assertion at the end of this method does.
        //
        // The original waited exactly 5 ticks, reasoning that the guard runs every tick and that
        // further ticks only let gravity drag the player away from spawnY — true of the wait's
        // PURPOSE, but a fixed wait says how long we are willing to wait, and under a loaded run the
        // server's player tick does not arrive on our schedule (measured 2026-08-07: the player was
        // still standing at 50000 when the five ticks were up, and the leg indicted production for
        // it). Polling exits at the EARLIEST tick the teleport is visible, which is also the least
        // free-fall the posY check below can be handed.
        String after = exec("artest player health");
        int waitedTicks = 0;
        while (waitedTicks < 120
                && Math.abs(doubleField(POS_X, after, "posX") - 50000.5) < 1.0) {
            bot().waitTicks(2);
            waitedTicks += 2;
            after = exec("artest player health");
        }
        scenario().record("ticksUntilGuardMovedHim", waitedTicks);
        int dim = intField(DIM, after, "dim");
        assertEquals("player must remain in the space dim — he should be teleported to the "
                + "station's spawn, not back to overworld; dim=" + dim + " " + after,
                SPACE_DIM, dim);

        double posX = doubleField(POS_X, after, "posX");
        double posY = doubleField(POS_Y, after, "posY");
        double posZ = doubleField(POS_Z, after, "posZ");
        // The handler uses setPositionAndUpdate(spawn.x, spawn.y, spawn.z) exactly. X/Z motion in
        // vacuum is zero (no input), so a tight 2.0 epsilon holds. Y drifts down: gravity pulls the
        // player ~1 block/tick after a few ticks of accumulation, so 6.0 covers the 5-tick
        // free-fall window while still pinning "teleported to the spawn area, not to the overworld".
        assertEquals("player posX must match station spawnX after the guard fires; spawn=("
                        + spawnX + "," + spawnY + "," + spawnZ + ") player=(" + posX + "," + posY
                        + "," + posZ + ")", spawnX, posX, 2.0);
        assertEquals("player posY must match station spawnY (within the free-fall window)",
                spawnY, posY, 6.0);
        assertEquals("player posZ must match station spawnZ", spawnZ, posZ, 2.0);

        // WHICH BRANCH the guard took, as an absence — and the absence is only worth something
        // because the positive pins above say the body DID move onto the station's spawn, so the
        // guard demonstrably fired. The old form here was `assertNotEquals(0, dim)` on the very
        // `dim` the line above had just pinned to SPACE_DIM: once dim == -2 it is trivially != 0, so
        // it could never discriminate the fallback branch it was named for.
        //
        // The fallback branch is the ONE thing that leaves a record: it transfers through a
        // BasicTeleporter, which is exactly what the arrangement's own transfer used, so the
        // instrument is proven to have run in this very scenario — the reply below carries the
        // arrangement's placement into the space dim.
        String placements = events.since(mark, "teleporter_placed");
        Events.assertInstrumentRan(placements, "teleporter_events",
                "the guard did NOT fall back to the overworld");
        assertEquals("a station exists, so the guard must take the teleport-to-station branch and"
                        + " never the overworld fallback; a placement into dim 0 since the mark is"
                        + " that fallback having fired: " + placements,
                0, Events.countRecords(placements, "\"dim\":0,"));
    }
}
