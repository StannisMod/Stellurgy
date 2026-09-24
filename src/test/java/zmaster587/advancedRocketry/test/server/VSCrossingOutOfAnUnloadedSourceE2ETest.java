package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipReadiness;

import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The other way a crossing can leave a ship behind: the REGISTRY entry of a source that was not loaded
 * when its blocks were cut.
 *
 * <p>{@link VSCrossingLeavesNoShipBehindE2ETest} covers the case where the source ship is loaded — there,
 * a physics object is what can be stranded. This one covers the opposite starting state, where no physics
 * object exists at all, so whatever collects a crossing's leftovers has to work without one. An entry left
 * behind here has no blocks and nothing loaded behind it, yet it still answers position lookups in that
 * world — including the opening lookup of the next crossing out of the same place — and it is persisted
 * with the world, so it outlives a restart.</p>
 *
 * <p><b>Its own class, and its own server.</b> The arrangement it needs — a registered ship that is NOT
 * loaded — is exactly the state that makes the shared-harness load pump crash the server, so this leg is
 * kept away from any method that holds {@code permaload}. {@code permaload} is never set true here.</p>
 *
 * <p><b>The counts are taken before anything asks the world to load a ship</b>, because loading a
 * leftover entry is itself what would collect it: measuring afterwards would measure the measurement.</p>
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSCrossingOutOfAnUnloadedSourceE2ETest extends AbstractHeadlessServerTest {


    private static final int BASE_X = 7000, BASE_Z = 7000;
    private static final int BUILD_Y = FixtureSite.OPEN_AIR_Y, SKY_Y = 150;
    private static final int HOP = 160;
    private static final double POSE_TOLERANCE = 64.0;

    /**
     * Link budgets in SERVER TICKS — 200 is the ten seconds the old {@code 40 x 250 ms} meant on an
     * idle box. On the server's clock because what is waited for (a queued spawn, a cut ship being
     * collected, a hull dropped by the load controller) is served by the server tick loop, and the
     * world these ships live in is precisely the one that may not be ticking.
     */
    private static final int WAIT_TICKS = 200;

    /**
     * The one class that has to TURN THE AFFORDANCE OFF, because its subject is the state the
     * affordance removes.
     *
     * <p>A test server holds every ship permanently loaded from the moment the probes register —
     * a headless run has no player to hold one, and every other scenario wants its craft to survive
     * between probe calls. Here a registered ship that is NOT loaded is the whole arrangement: with
     * the flag on, this class would silently become a copy of the loaded-source class next door and
     * could not fail. Said here, once, rather than left to the default being what it used to be.</p>
     */
    @org.junit.Before
    public void theSourceMustBeAbleToUNLOAD() throws Exception {
        ShipReadiness.letShipsUnload(this::exec,
                "this class's subject IS a registered ship nobody has loaded; held loaded, it would"
                + " silently become a copy of the loaded-source class next door");
    }

    @Test
    public void aCrossingOutOfAnUnloadedSourceLeavesNoRegistryEntry() throws Exception {

        // Marked BEFORE the build, because the unload this class is built on happens INSIDE it —
        // measured on the gate of 2026-09-22, where a mark taken afterwards saw no `ship_unloaded`
        // for 200 ticks while the counters already read `loaded=0 registry=1`. There is no second
        // unload to wait for.
        long unloadMark = events.mark();
        buildShip();
        // With no player near it and no permaload, VS unloads the ship again and keeps its registry entry.
        // Registered, with nothing loaded behind it, is this test's whole subject; if the ship stayed
        // loaded this would silently become a copy of the other class's arrangement.
        awaitSourceUnloaded(unloadMark);

        int registryBefore = queryableShips();
        int loadedBefore = loadedShips();

        // Marked before the crossing: it cuts the hull and pastes a new one, so the source's entry
        // is removed and the arrival is a fresh registry add. The crossing re-assembles under the
        // identity the ship crossed with (VSIntegration.crossShip), so `shipId` names both — the
        // removal is the SOURCE's, because nothing removes the arrival.
        long crossMark = events.mark();
        String cross = repack(BASE_X, BUILD_Y, BASE_X + HOP, SKY_Y);
        assertTrue("the crossing itself failed, so this test measures nothing: " + cross,
                Reply.of(cross).ok());
        // The crossing owes the registry two changes, and both land on later world ticks: the cut
        // source is marked dead and collected by the physics mod's own pass, and the paste is
        // queued and registered when the spawn queue drains. Linked on each by its own identity,
        // neither of which loads anything; the counts are read only once both have happened.
        events.awaitField(crossMark, "ship_removed", "vsShip", shipId,
                "a crossing out of an UNLOADED source left its registry entry behind — the cut source"
                        + " " + shipId + " was never collected", WAIT_TICKS);
        events.awaitField(crossMark, "ship_spawned", "arShip", durableShipId,
                "the crossed ship was never registered at the destination", WAIT_TICKS);
        // The instrument that makes the OTHER outcome legible, proven to fire on this one. The
        // physics mod drops a queued spawn whose flood is too big or touches bedrock with a line on
        // stderr and nothing else, so when the wait above expires the only account of why is the
        // `ship_spawn_flood` record among what it prints. That record comes from a redirect declared
        // `require = 0`; a green run that saw none would be the day it stopped weaving.
        String floods = events.since(crossMark, "ship_spawn_flood");
        assertTrue("ARRANGEMENT: the arrival's spawn flood must be on the record, and accepted — a"
                        + " missing one means the refusal instrument no longer weaves: " + floods,
                Events.anyRecordHasAll(floods, "refused", "false"));

        int registryAfter = queryableShips();
        int loadedAfter = loadedShips();

        // Only now prove the crossing really produced a ship at the destination. Doing it after the reads
        // keeps the load pump out of the measurement, and still fails loudly - rather than as a clean
        // conservation - if nothing ever arrived.
        requireArrivedAt(crossMark, BASE_X + HOP, SKY_Y);

        assertEquals("a crossing out of an UNLOADED source left its registry entry behind: registered "
                        + "ships went " + registryBefore + " -> " + registryAfter + " across a crossing "
                        + "that moved a single ship (loaded " + loadedBefore + " -> " + loadedAfter + "). "
                        + "An entry with no blocks and nothing loaded behind it still answers every "
                        + "position lookup in this world, the next crossing out of this cell included, "
                        + "and it is written to disk with the world.",
                registryBefore, registryAfter);
    }

    /**
     * A blockless record nobody deregisters is COLLECTED, not merely avoided.
     *
     * <p>The leg above measures a crossing that cleans up after itself, and it passes because the
     * crossing's own path deregisters by hand. That proves the hand call works; it says nothing about
     * what happens on a path that has no hand call — and every such path leaves a record that owns no
     * blocks, answers position lookups in its world, and is written to disk with it. The manager's
     * registry sweep is what has to collect those, and this is the leg that can fail if it does not.
     *
     * <p><b>The garbage is PLANTED, and the plant is measured on its own call.</b> Provoking it means
     * winning a race — cutting a loaded ship and unloading it inside one tick — and a collector's
     * test should be able to state its arrangement rather than hope for it. The registry size taken
     * inside the planting call is what proves a record really was added, because two probe commands
     * are separated by a full world pass: a count read afterwards is already post-collection and
     * cannot tell a working sweep from a plant that never happened.
     *
     * <p><b>Nothing here loads a ship.</b> Loading a leftover entry is itself one of the things that
     * collects it, so a leg that pumped a load would be measuring its own instrument.
     */
    @Test
    public void aBlocklessRecordNobodyDeregisteredIsSweptFromTheRegistry() throws Exception {

        int registryBefore = queryableShips();

        // Marked before the plant: the collection is announced once, and the record the sweep
        // removes does not exist yet.
        long plantMark = events.mark();
        String planted = exec("artest vs strand-blockless-record 0 "
                + BASE_X + " " + BUILD_Y + " " + BASE_Z);
        assertTrue("the fault injection itself failed, so this leg measures nothing: " + planted,
                Reply.of(planted).ok());
        assertEquals("the plant must actually have put a record in the registry — read on the "
                        + "planting call itself, before any tick could collect it. Without this the "
                        + "assertion below is satisfied by a plant that never happened: " + planted,
                registryBefore + 1, extractInt(planted, "countAfterAdd"));

        // Linked on the registry's own REMOVAL of the planted record, by its uuid. A registered ship
        // owning NO blocks, with nothing loaded behind it and no queue holding it, must be collected
        // by the manager's own registry sweep — no caller deregistered this one, which is the whole
        // point: a path that forgets to, or a hull cut while nothing was loaded to be walked, leaves
        // exactly this. It answers position lookups and it is persisted with the world.
        //
        // The count this replaces could not say WHICH entry went. Two records leaving and one
        // arriving in the same window returns the count to where it started, and a poll reading only
        // the total would have called that a successful sweep of the one it planted.
        events.awaitField(plantMark, "ship_removed", "vsShip", Reply.of(planted).text("uuid"),
                "the manager's registry sweep never collected the planted blockless record;"
                        + " registry went " + registryBefore + " -> " + queryableShips()
                        + "; planted=" + planted, WAIT_TICKS);
    }

    // --- arrangement --------------------------------------------------------------------------------

    private void buildShip() throws Exception {
        // Marked before the assemble: the registry add is made inside it and is announced once.
        long buildMark = events.mark();
        String coords = placeFixture(FixtureSite.openAir(0, BASE_X, BASE_Z), "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));
        durableShipId = ShipIdentity.nameFromAssembly(asm);
        // The registry's own add, naming THIS craft — the count comparison it replaces was a
        // statement about the dimension and could be satisfied by any other scenario's hull.
        events.awaitField(buildMark, "ship_spawned", "arShip", durableShipId,
                "the ship never entered VS's registry: " + counters(), WAIT_TICKS);
        // The identity is taken HERE, while the craft is still loaded, because the whole subject of
        // this class is what happens once it is not: the durable->physics bridge repairs its index by
        // reading flight computers, which force-loads the ship it is asked about. Resolving the id
        // later would therefore undo the arrangement it was needed for. The physics id survives the
        // unload — the registry keeps the entry, which is the fact under test.
        shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableShipId);
    }

    /** The craft this test built — captured while loaded, used to cut it once it is not. */
    private String shipId;

    /**
     * The same craft's DURABLE name, which the crossing carries and the physics id does not: a
     * crossing cuts a hull and pastes a new one, so the arrival has a new physics id and the name is
     * the only handle that spans it.
     */
    private String durableShipId;

    private String repack(int sx, int sy, int dx, int dy) throws Exception {
        // The crossing CUTS a ship, so it is told WHICH: the positional form resolves the yard as
        // "whatever craft is nearest", and this test deliberately leaves an unloaded registry entry
        // behind — exactly the candidate such a lookup should never be allowed to reach for.
        return exec("artest vs ship-repack 0 id " + shipId + " " + sx + " " + sy + " " + BASE_Z
                + " " + dx + " " + dy + " " + BASE_Z);
    }

    // --- observation --------------------------------------------------------------------------------

    private int loadedShips() throws Exception {
        return extractInt(exec("artest vs ship-count 0"), "count");
    }

    private int queryableShips() throws Exception {
        return extractInt(exec("artest vs ship-count-all 0"), "count");
    }

    private String counters() throws Exception {
        return "[loaded=" + loadedShips() + " registry=" + queryableShips() + "]";
    }

    /**
     * Is any loaded ship's own pose at {@code (x,y,BASE_Z)}? Every loaded ship and its pose in ONE
     * probe call — the nearest-ship lookup this replaced answered with a single craft however far
     * away it was, so the pose filter that followed only ever tested the one the lookup chose.
     *
     * <p>One call is not an optimisation here: nothing holds ships loaded in this class, so the
     * caller's pump leaves a hull resident for about a tick, and a second round-trip inside that gap
     * reads a world where it has already gone (measured).</p>
     */
    private boolean shipIsAt(int x, int y) throws Exception {
        return ShipIdentity.aLoadedShipIsAt(this::exec, 0, x, y, BASE_Z, POSE_TOLERANCE);
    }

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /**
     * Wait for the craft this test built to be UNLOADED — the substrate's own {@code unload()},
     * named by the hull's physics id.
     *
     * <p>This class's whole arrangement is a registered ship that nobody has loaded, and the poll it
     * replaces asked for that by a POSITION: "no loaded ship stands here any more". That is the
     * right fact asked the wrong way round — it is satisfied by a hull that moved as readily as by
     * one that unloaded, and it cannot say which happened. The record is the unload.</p>
     *
     * <p><b>TWO STATEMENTS, because "it happened" is not "it holds".</b> The caller's mark precedes
     * the build, so the window can contain a load/unload/load sequence and the link alone would be
     * satisfied by an unload the build then undid. The read that follows is the standing fact, and
     * it is what makes the early mark safe: a hull that is loaded NOW fails here, rather than
     * letting the crossing below cut a loaded source — the arrangement this class exists not to
     * be.</p>
     */
    private void awaitSourceUnloaded(long mark) throws Exception {
        events.awaitField(mark, "ship_unloaded", "vsShip", shipId,
                "the source ship never unloaded, so this test would measure the loaded-source"
                        + " arrangement instead of its own: " + counters(), WAIT_TICKS);
        assertEquals("the source unloaded and was loaded again, so this is the loaded-source"
                + " arrangement and not this class's: " + counters(), 0, loadedShips());
    }

    /**
     * Wait for the crossing's arrival to be REGISTERED and then UNLOADED, pump a load, and read where
     * it stands.
     *
     * <p><b>What stood between the registration and the pump was sixty ticks, and the story it
     * carried was wrong.</b> It said the arrived hull's world transform "had not propagated yet"
     * (measured 2026-09-22 as {@code [loaded=1 registry=1]} with no hull at the pose). But a queued
     * ship's record is created with its transform AT the anchor it was assembled on —
     * {@code ValkyrienUtils.createNewShip} passes the anchor's world position as the ship's position —
     * so a registered arrival stands at its paste site from its first tick. And the same day's gate
     * found the real cause of that exact signature: the failure message was built BEFORE the pose was
     * read, its probe calls spent the tick the hull was resident, and one message printed
     * {@code loaded=1} and {@code count:0} side by side. The read was moved into a local that day
     * (below), and the sixty ticks stayed on the strength of a diagnosis the fix beside them had
     * refuted.</p>
     *
     * <p><b>What the stretch did buy, and what replaces it.</b> Nothing holds ships loaded in this
     * class, so a freshly spawned hull is loaded by its spawn and dropped by the load controller
     * straight after. A pump that lands while it is still resident finds nothing to load and the
     * hull is dropped under the read; a pump after the drop loads it for the read. So the wait is for
     * that drop — the arrival's own {@code ship_unloaded}, later than its {@code ship_spawned} — and
     * the pump follows it. That is the working hypothesis for why sixty ticks were enough; the link
     * holds whether or not it is the whole story, because it establishes the state the pump needs.</p>
     *
     * <p><b>The pump comes last, immediately before the read, and that order is load-bearing.</b>
     * A hull is resident for about a tick after a load here, so pumping before the unload link would
     * read a world the ship had already left again.</p>
     */
    private void requireArrivedAt(long mark, int x, int y) throws Exception {
        String spawned = events.awaitRecordWithField(mark, "ship_spawned", "arShip", durableShipId,
                "the crossed ship was never registered at the destination: " + counters(),
                WAIT_TICKS);
        final String arrivalKey = Events.text(spawned, "vsShip");
        final double spawnedAt = Events.number(spawned, "seq");
        events.awaitMatching(mark, "ship_unloaded",
                seen -> {
                    for (String r : Events.recordsWhere(seen, "vsShip", arrivalKey)) {
                        if (Events.number(r, "seq") > spawnedAt) {
                            return true;
                        }
                    }
                    return false;
                },
                "naming the arrival " + arrivalKey + ", later than its registration",
                "the arrival must be dropped by the load controller — nothing holds ships loaded in"
                        + " this class — before a pump can load it for the read: " + counters(),
                WAIT_TICKS);
        exec("artest vs load-ships 0");
        // READ INTO A LOCAL, and nothing may come between it and the pump above. Java evaluates an
        // assertion's MESSAGE before its condition, so `assertTrue("… " + counters(), shipIsAt(…))`
        // spends three probe round-trips on the message and only then asks the question — and this
        // class's ships are resident for about a tick after a load. Measured 2026-09-22: that exact
        // shape reported `[loaded=1 registry=1]` from the message and `{"count":0,"ships":[]}` from
        // a read taken moments later in the SAME failure, which is the hull unloading between two
        // probe calls. The diagnostics are built afterwards, when the answer is already in hand.
        boolean arrived = shipIsAt(x, y);
        assertTrue("the arrival was registered, dropped and pumped, but no loaded ship stands within "
                        + POSE_TOLERANCE + " of " + x + "," + y + "," + BASE_Z + ": " + counters()
                        // WHERE the hulls are, for a failure that survives this: "not at the
                        // destination" has several causes a boolean cannot separate — still at the
                        // source, mid-transform, or gone again — and each wants a different fix.
                        // Read AFTER the verdict, so it describes the aftermath and not the subject.
                        + " | loaded hulls now: " + exec("artest vs ships-loaded 0"),
                arrived);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }


    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, 0.0);
    }
}
