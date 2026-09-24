package zmaster587.advancedRocketry.test.client;

import java.util.HashMap;
import java.util.Map;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.PlayerState;
import zmaster587.advancedRocketry.test.StationInfo;


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

    private static final String POS_X = "posX";
    private static final String POS_Y = "posY";
    private static final String POS_Z = "posZ";
    private static final String STATION_ID = "id";

    /** {@code ARConfiguration.spaceDimId}'s default. */
    private static final int SPACE_DIM = -2;

    @Override
    protected String subsystem() {
        return "space-dim-guard";
    }

    private int intField(String field, String src, String name) {
        Reply reply = Reply.of(src);
        assertTrue("field " + name + " missing in: " + src, reply.has(field));
        return reply.integer(field);
    }

    private double doubleField(String field, String src, String name) {
        double value = Reply.of(src).number(field);
        return value;
    }

    // ── reading the two event logs ────────────────────────────────────────────
    //
    // The guard's contract is a CROSS-SIDE chain: the server hands the body to a teleporter bound
    // for the overworld, and the player's own client is respawned into it. The base's
    // {@link #events()} reads the SERVER log and {@link #clientEvents()} the client's, both behind
    // the same reader — the same mark, the same "is anybody recording" assertion, the same failure
    // narrative on both sides.

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
                (Reply.of(list).arrayLength("stations") == 0));

        PlayerState pre = PlayerState.read(this::exec);
        scenario().requireArranged("baseline must be overworld dim 0; " + pre.raw(), 0 == pre.dim);

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
        String placed = events.awaitField(mark, "teleporter_placed", "dim", 0,
                "a player who lands in the space dim with NO station registered must be put back in"
                        + " the overworld by the guard's own teleporter", GUARD_LINK_BUDGET_TICKS);
        scenario().record("guardPlacement", placed);

        // …and the player-visible half: his own client is respawned into the overworld. Read off the
        // client's record of its dimension changes rather than sampled — a client torn down and
        // rebuilt twice between two samples shows one change or none, and the records show both, in
        // order.
        ClientEvents.awaitDim(clientLog, clientMark, 0,
                "the player whose body the guard moved must SEE the overworld, not merely be"
                        + " reported there", GUARD_LINK_BUDGET_TICKS);

        PlayerState after = PlayerState.read(this::exec);
        assertEquals("no-station fallback must transfer player back to overworld; player is in dim "
                + after.dim + " — " + after.raw(), 0, after.dim);
    }

    /**
     * With a registered station, a player who lands in the space dim outside the station's bounds
     * gets teleported to the station's spawn location — not back to the overworld.
     *
     * <p>red-witnessed: with the guard's call moved back into {@code PlanetEventHandler.playerTick}
     * (the living update, inside the network handler's update) and {@code spaceDimensionGuard}
     * disabled, this fails with "the guard must move a body off a given point ONCE … origin=206,64,154"
     * (2026-09-24, run alone).</p>
     */
    @Test
    public void registeredStationTeleportTargetsStationSpawn() throws Exception {
        scenario().arranging("create a station orbiting the overworld")
                .describeOnFailureWith("artest station list", "artest player health");
        String createResp = exec("artest station create " + plot().dim);
        scenario().requireArranged("station create must succeed: " + createResp,
                !Reply.of(createResp).has("error"));
        int stationId = intField(STATION_ID, createResp, "station id");
        scenario().record("stationId", stationId);

        StationInfo info = StationInfo.byId(this::exec, stationId);
        int spawnX = info.spawnX();
        int spawnY = info.spawnY();
        int spawnZ = info.spawnZ();
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
            events.awaitField(mark, "teleporter_placed", "dim", SPACE_DIM,
                    "the arrangement's own transfer into the space dim must land before the body is"
                            + " moved 50 000 blocks WITHIN it", GUARD_LINK_BUDGET_TICKS);
        } catch (AssertionError arrangement) {
            scenario().arrangementFailed(arrangement.getMessage());
        }
        // THE MARK GOES BEFORE THE STIMULUS, one statement above the teleport that puts the body
        // where the guard must act on it — which is what makes the wait below a link and not a
        // sample taken after the fact.
        long guardMark = events.markInstrumented();
        exec("tp @a 50000 100 50000");

        // THE GUARD'S OWN ACT, awaited as a record. This was a poll of the body's X until it had
        // moved a block, defended in place by the fact that the station branch commits through
        // `Entity.setPositionAndUpdate` and nothing recorded it: `teleporter_placed` covers only the
        // OTHER branch, and `pos_jump` fires on a position write only when the VERTICAL move passes
        // its threshold — while this guard moves a body fifty thousand blocks sideways and leaves Y
        // roughly alone. So the one act this scenario is about was invisible.
        //
        // A missing record is not a ground for a poll; it is the work. The seam now speaks:
        // `space_guard_relocated`, recorded by a TEST-ONLY mixin at the exact invoke the guard
        // commits through, carrying where the body was actually left. What this buys over the poll
        // is not the wait — it is the failure. A poll that expired said "he is still at 50000",
        // which is equally true of a guard that declined, a guard that never ran, and a server that
        // never received the teleport.
        //
        // And it is the relocation OF THE BODY AT 50 000, named by where it was taken from: the
        // transfer above lands him outside every slot too, the guard relocates that arrival, and
        // "a relocation happened" can be closed by it.
        //
        // The posX read right after the record is the contract, not a race: the relocation must HOLD
        // on the server from the tick it is made. A guard that teleported from a living update did
        // not — the network handler's update writes the pre-tick position back after it, so the
        // server kept the old one until the client confirmed, re-fired every tick until then, and
        // this read saw 50 000 whenever the confirm was late (red in 3 of 5 full runs, 2026-09-24).
        events.awaitMatching(guardMark, "space_guard_relocated",
                reply -> {
                    for (String r : Events.records(reply)) {
                        if (Math.abs(Events.number(r, "fromX") - 50000.0) < 2.0
                                && Math.abs(Events.number(r, "fromZ") - 50000.0) < 2.0) {
                            return true;
                        }
                    }
                    return false;
                },
                "taken from (50000, 50000)",
                "the space-dimension guard must MOVE a body standing in no station's slot onto a"
                        + " station spawn — this is the act the scenario is about, and it is a"
                        + " discrete decision production takes, not a value that settles",
                GUARD_LINK_BUDGET_TICKS);
        PlayerState after = PlayerState.read(this::exec);
        int dim = after.dim;
        assertEquals("player must remain in the space dim — he should be teleported to the "
                + "station's spawn, not back to overworld; dim=" + dim + " " + after,
                SPACE_DIM, dim);

        double posX = after.x;
        double posY = after.y;
        double posZ = after.z;
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

        // ONCE per position. Each relocation also tells the player he has no station, twice; a move
        // that did not hold is re-made (and re-announced) from the SAME point on the next tick, so
        // two relocations taken from one point are that failure — measured 2026-09-24 as four in a
        // row from the transfer's landing point, with nothing else running.
        String relocations = events.since(mark, "space_guard_relocated");
        Map<String, Integer> byOrigin = new HashMap<>();
        for (String r : Events.records(relocations)) {
            String origin = Math.round(Events.number(r, "fromX")) + ","
                    + Math.round(Events.number(r, "fromY")) + "," + Math.round(Events.number(r, "fromZ"));
            byOrigin.merge(origin, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : byOrigin.entrySet()) {
            assertEquals("the guard must move a body off a given point ONCE — a relocation that is "
                            + "re-made from the same point did not hold on the server, and the player "
                            + "is told twice more each time. origin=" + e.getKey()
                            + " relocations since the transfer: " + relocations,
                    1, (int) e.getValue());
        }

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
                0, Events.countRecords(placements, "dim", "0"));
    }
}
